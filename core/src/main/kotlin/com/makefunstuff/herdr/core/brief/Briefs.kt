package com.makefunstuff.herdr.core.brief

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.push.Tally

/** What the brief got from one machine. */
data class MachineLook(
    val id: String,
    val label: String,
    /** Its board's tasks now; null where it has no board, or the board didn't answer ([boardFailed]). */
    val tasks: List<WorkTask>? = null,
    /** It has the work board, but `list` didn't answer. */
    val boardFailed: Boolean = false,
    /** Its agents now; null when herdr didn't answer. */
    val snapshot: SessionSnapshot? = null,
    /** What a blocked agent asks, by pane, read from its screen. */
    val questions: Map<String, String> = emptyMap(),
    /** herdr-push's journal there; null where it keeps none. */
    val journal: Journal? = null,
    /** What the app showed of it when the person last looked; used where there is no journal. */
    val baseline: Baseline? = null,
    /** It couldn't be reached: why. */
    val problem: String? = null,
)

/**
 * Puts the brief together: what each machine's journal says its agents did in the window (or, without one,
 * what changed since the app last showed it), what its tasks did since the app last showed them, and how its
 * tasks and agents stand now.
 *
 * Each task shows once, where it tells the most: waits for you, done in the window, at work. A task's own
 * agent is the task, so it isn't listed as an agent.
 */
object Briefs {

    /** A journal whose last look is older than this stopped keeping track (herdr-push isn't running). */
    const val STALE_MS = 10L * 60 * 1000

    fun build(looks: List<MachineLook>, since: Long, now: Long): Brief {
        val parts = looks.map { one(it, since, now) }
        fun order(items: List<BriefItem>) = items.sortedWith(compareByDescending<BriefItem> { it.new }.thenByDescending { it.at })
        return Brief(
            at = now,
            since = since,
            machines = parts.map { it.machine },
            waiting = order(parts.flatMap { it.waiting }),
            done = parts.flatMap { it.done }.sortedByDescending { it.at },
            finished = parts.flatMap { it.finished }.sortedByDescending { it.at },
            working = parts.flatMap { it.working },
            started = parts.sumOf { it.started },
            added = parts.sumOf { it.added },
        )
    }

    private class Part(
        val machine: BriefMachine,
        val waiting: List<BriefItem> = emptyList(),
        val done: List<BriefItem> = emptyList(),
        val finished: List<BriefItem> = emptyList(),
        val working: List<BriefItem> = emptyList(),
        val started: Int = 0,
        val added: Int = 0,
    )

    private fun one(m: MachineLook, since: Long, now: Long): Part {
        if (m.problem != null) return Part(BriefMachine(m.id, m.label, BriefMachine.Coverage.Unreached, m.problem))
        val j = m.journal
        // The machine's clock on the phone's: the journal says what its clock read as it answered.
        val offset = if (j != null && j.now > 0) now - j.now * 1000 else 0L
        fun phone(at: Long) = at * 1000 + offset
        val window = j?.entries.orEmpty().filter { phone(it.at) >= since }
        val base = if (j == null) m.baseline else null

        val missing = listOfNotNull(
            if (m.boardFailed) "its board didn't answer" else null,
            if (m.snapshot == null) "herdr didn't answer" else null,
        ).joinToString(" and ").ifEmpty { null }?.replaceFirstChar { it.uppercase() }
        val machine = when {
            j != null -> {
                val from = phone(j.from ?: 0)
                val upto = j.upto?.let(::phone) ?: now
                val live = upto >= now - STALE_MS
                if (from <= since && live) BriefMachine(m.id, m.label, BriefMachine.Coverage.Journal, missing)
                else BriefMachine(m.id, m.label, BriefMachine.Coverage.Partial, missing, from = if (from > since) from else 0, upto = if (live) 0 else upto)
            }
            base?.tasks != null || base?.agents != null ->
                BriefMachine(m.id, m.label, BriefMachine.Coverage.Compared, missing, upto = maxOf(base.tasksAt, base.agentsAt))
            else -> BriefMachine(m.id, m.label, BriefMachine.Coverage.NowOnly, missing)
        }

        val tasks = m.tasks.orEmpty()
        // The journal keeps agents only: a task's news is how it stands now against what the app last showed.
        val seen = m.baseline?.tasks
        val snapshot = m.snapshot

        /** Whether [t]'s state now came about since the app last showed it. */
        fun happened(t: WorkTask): Pair<Boolean, Long> = (seen == null || seen[t.id] != t.status.name) to 0L

        fun item(kind: BriefItem.Kind, t: WorkTask, status: String = t.status.label, at: Pair<Boolean, Long> = happened(t)) = BriefItem(
            kind = kind,
            machineId = m.id,
            machineLabel = m.label,
            title = t.title,
            name = t.name,
            status = status,
            detail = t.line,
            at = at.second,
            new = at.first,
            taskId = t.id,
            pr = t.pr,
            board = t.status.name,
            progress = t.progress?.takeIf { kind == BriefItem.Kind.Working },
        )

        // Agents: whose they are, and what to call them.
        // Shepherdr labels a task's agent with it, or opens its workspace under the task; an agent on the same work as a task is the task too.
        fun owned(a: AgentInfo, workspace: String) = tasks.any {
            a.shepherdrTask == it.id || Tally.belongs(workspace, it.id) || (a.title != null && a.title == it.title)
        }
        fun workspaceOf(a: AgentInfo) = snapshot?.workspace(a.workspaceId)?.label.orEmpty()
        fun agentItem(kind: BriefItem.Kind, a: AgentInfo, status: String, detail: String, at: Pair<Boolean, Long>) = BriefItem(
            kind = kind,
            machineId = m.id,
            machineLabel = m.label,
            title = a.subtitle ?: a.displayName,
            name = a.displayName,
            status = status,
            detail = detail,
            at = at.second,
            new = at.first,
            paneId = a.paneId,
        )
        val agentEntries = window.filterIsInstance<JournalEntry.Agent>()
        fun agentHappened(a: AgentInfo): Pair<Boolean, Long> = when {
            j != null -> agentEntries.lastOrNull { it.agent.paneId == a.paneId && Baseline.mark(it.agent) == Baseline.mark(a) }
                ?.let { true to phone(it.at) } ?: (false to 0L)
            base?.agents != null -> (base.agents[a.paneId] != Baseline.mark(a)) to 0L
            else -> true to 0L
        }

        val asking = snapshot?.agents.orEmpty().filter { it.agentStatus == AgentStatus.Blocked && !owned(it, workspaceOf(it)) }
        val waiting = tasks.filter { it.status.needsYou }.map { item(BriefItem.Kind.Waiting, it) } +
            asking.map { a -> agentItem(BriefItem.Kind.Waiting, a, "asks you", m.questions[a.paneId].orEmpty(), agentHappened(a)) }

        // Done since the app last showed them: never shown, nothing is news yet.
        val done = if (seen == null) emptyList() else tasks.filter { it.status == WorkStatus.Done && seen[it.id] != WorkStatus.Done.name }
        val doneItems = done.map { t ->
            if (t.pr.isNotEmpty()) item(BriefItem.Kind.Merged, t, "merged", true to 0L) else item(BriefItem.Kind.Done, t, "done", true to 0L)
        }

        val asks = asking.map { it.paneId }.toSet()
        val finished: List<BriefItem> = when {
            j != null -> agentEntries.filter { it.agent.agentStatus == AgentStatus.Done }
                .associateBy { it.agent.paneId }.values
                .filter { !owned(it.agent, it.workspace) && it.agent.paneId !in asks }
                // What it worked on as it finished; it may be on something else now.
                .map { e -> agentItem(BriefItem.Kind.Finished, e.agent, "finished", e.where, true to phone(e.at)) }
            base?.agents != null -> snapshot?.agents.orEmpty()
                .filter { it.agentStatus == AgentStatus.Done && base.agents[it.paneId] != Baseline.mark(it) }
                .filter { !owned(it, workspaceOf(it)) }
                .map { agentItem(BriefItem.Kind.Finished, it, "finished", snapshot!!.location(it.workspaceId, it.tabId), true to 0L) }
            else -> emptyList()
        }

        val running = tasks.filter { it.status == WorkStatus.Work || it.status == WorkStatus.Merging }
        val working = running.map { item(BriefItem.Kind.Working, it, it.status.label, false to 0L) } +
            snapshot?.agents.orEmpty()
                .filter { it.agentStatus == AgentStatus.Working && !owned(it, workspaceOf(it)) }
                .map { agentItem(BriefItem.Kind.Working, it, "working", snapshot!!.location(it.workspaceId, it.tabId), false to 0L) }

        val waitedBefore = setOf(WorkStatus.Todo.name, WorkStatus.Queued.name)
        val (started, added) = if (seen == null) 0 to 0 else
            running.count { seen[it.id] == null || seen[it.id] in waitedBefore } to tasks.count { seen[it.id] == null }
        return Part(machine, waiting, doneItems, finished, working, started, added)
    }
}
