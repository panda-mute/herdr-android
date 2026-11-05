package com.makefunstuff.herdr.core.push

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import kotlinx.serialization.Serializable

/**
 * One thing the status widget counts: an agent or a board task that needs you or works. The rest
 * is what ties a task's own agent to it ([Tally.belongs]), so they are counted once.
 */
@Serializable
data class TallyItem(
    /** [Alerts.agentTag] or [Tally.taskTag]. */
    val tag: String,
    val needsYou: Boolean,
    /** When this was learned, on the phone's clock (ms). */
    val at: Long,
    val agentName: String = "",
    val workspace: String = "",
    val taskId: String = "",
    val taskName: String = "",
) {
    val isTask: Boolean get() = taskId.isNotEmpty()
}

/** What is known of one machine's agents, or of its board's tasks, and when it was last told. */
@Serializable
data class TallyPart(val items: List<TallyItem> = emptyList(), val at: Long = 0)

/** What needs you and what works, per machine: agents and tasks apart, as each source tells only one of them. */
@Serializable
data class TallyState(val agents: Map<String, TallyPart> = emptyMap(), val tasks: Map<String, TallyPart> = emptyMap())

/** The counts across machines. [at]: when the newest of it was learned (ms); 0 when nothing is known yet. */
data class Counts(val needYou: Int = 0, val working: Int = 0, val machines: Int = 0, val at: Long = 0) {
    val known: Boolean get() = at > 0

    /** "2 need you · 3 working", "1 needs you", "3 working", or "All quiet". */
    val words: String get() = when {
        needYou > 0 && working > 0 -> "${needYouWords()} · $working working"
        needYou > 0 -> needYouWords()
        working > 0 -> "$working working"
        else -> "All quiet"
    }

    fun needYouWords(): String = if (needYou == 1) "1 needs you" else "$needYou need you"
}

/**
 * What the status widget and the tile show: how many agents and board tasks need you and how many
 * work, across machines. Told from every source the app has: what it sees on screen, its own looks
 * while closed, the board's list, and the machines' pushes. Plain data, so the rules are tested here.
 *
 * - A task's own agent counts as the task.
 * - What works and was not confirmed for [WORK_MS] is no longer counted (its machine may be gone),
 *   and what needs you after [NEED_MS] neither.
 */
object Tally {
    /** As long as a Live Update stays up without word from its machine. */
    const val WORK_MS = 75L * 60 * 1000
    const val NEED_MS = 24L * 3600 * 1000

    fun agent(machineId: String, a: AgentInfo, workspace: String, at: Long): TallyItem? {
        val needsYou = when (a.agentStatus) {
            AgentStatus.Blocked -> true
            AgentStatus.Working -> false
            else -> return null
        }
        return TallyItem(Alerts.agentTag(machineId, a.paneId), needsYou, at, agentName = a.name.orEmpty(), workspace = workspace)
    }

    fun taskTag(machineId: String, taskId: String) = "task:$machineId:$taskId"

    fun task(machineId: String, t: WorkTask, at: Long): TallyItem? {
        val needsYou = when {
            t.status.needsYou -> true
            t.status == WorkStatus.Work || t.status == WorkStatus.Merging -> false
            else -> return null
        }
        return TallyItem(taskTag(machineId, t.id), needsYou, at, taskId = t.id, taskName = t.name)
    }

    /** Whether the agent in [workspace] works on task [taskId]: Shepherdr opens a task's workspace under its id (`HA-10`, `HA-10 Fix login`). */
    fun belongs(workspace: String, taskId: String): Boolean =
        taskId.isNotEmpty() && (workspace == taskId || workspace.startsWith("$taskId "))

    /** Everything [snapshot] says about [machineId]'s agents (the app sees it on screen, or looked). */
    fun withAgents(s: TallyState, machineId: String, snapshot: SessionSnapshot, at: Long): TallyState {
        val items = snapshot.agents.mapNotNull { agent(machineId, it, snapshot.workspace(it.workspaceId)?.label.orEmpty(), at) }
        return s.copy(agents = s.agents + (machineId to TallyPart(items, at)))
    }

    /** The board's whole list of [machineId]'s tasks. */
    fun withTasks(s: TallyState, machineId: String, tasks: List<WorkTask>, at: Long): TallyState {
        val items = tasks.mapNotNull { task(machineId, it, at) }
        return s.copy(tasks = s.tasks + (machineId to TallyPart(items, at)))
    }

    /** What a push from [machineId] says: one agent's new state, or (its live event) all that works there. */
    fun withPush(s: TallyState, machineId: String, events: List<PushEvent>, at: Long): TallyState = events.fold(s) { acc, e ->
        when (e) {
            is PushEvent.Agent -> acc.copy(agents = acc.agents.one(machineId, Alerts.agentTag(machineId, e.agent.paneId), agent(machineId, e.agent, e.where.substringBefore(" › "), at), at))
            is PushEvent.Live -> {
                var next = acc
                e.agents?.let { list ->
                    val working = list.mapNotNull { agent(machineId, it.agent, it.workspace.ifEmpty { it.where.substringBefore(" › ") }, at) }
                    next = next.copy(agents = next.agents.working(machineId, working, at))
                }
                next
            }
            // A machine low on RAM or disk is neither an agent nor a task: the counts stay.
            PushEvent.Test, is PushEvent.Health, is PushEvent.Usage -> acc
        }
    }

    /** Leaves only [machineIds] (the machines in the app and on). */
    fun only(s: TallyState, machineIds: Set<String>): TallyState =
        TallyState(s.agents.filterKeys { it in machineIds }, s.tasks.filterKeys { it in machineIds })

    /** The counts for [machineIds] at [now]. */
    fun count(s: TallyState, machineIds: Set<String>, now: Long): Counts {
        val each = machineIds.mapNotNull { machine(s, it, now) }
        return Counts(
            needYou = each.sumOf { it.needTasks + it.needAgents },
            working = each.sumOf { it.working },
            machines = each.size,
            at = each.maxOfOrNull { it.at } ?: 0,
        )
    }

    /** The machine whose board has the most tasks waiting for you (the first of them, in [machineIds]' order), or null. */
    fun boardWaiting(s: TallyState, machineIds: Collection<String>, now: Long): String? =
        machineIds.mapNotNull { id -> machine(s, id, now)?.takeIf { it.needTasks > 0 }?.let { id to it.needTasks } }.maxByOrNull { it.second }?.first

    /** Whether an agent that isn't a task's own needs you on one of [machineIds]. */
    fun agentWaiting(s: TallyState, machineIds: Collection<String>, now: Long): Boolean =
        machineIds.any { id -> (machine(s, id, now)?.needAgents ?: 0) > 0 }

    private class Machine(val needTasks: Int, val needAgents: Int, val working: Int, val at: Long)

    private fun machine(s: TallyState, id: String, now: Long): Machine? {
        val agents = s.agents[id]
        val tasks = s.tasks[id]
        if (agents == null && tasks == null) return null
        fun fresh(i: TallyItem) = now - i.at < if (i.needsYou) NEED_MS else WORK_MS
        val ts = tasks?.items.orEmpty().filter(::fresh)
        // A task's own agent is the task: counted only when the task isn't, in the same state.
        val ags = agents?.items.orEmpty().filter(::fresh).filter { a ->
            ts.none { it.needsYou == a.needsYou && belongs(a.workspace, it.taskId) }
        }
        return Machine(
            needTasks = ts.count { it.needsYou },
            needAgents = ags.count { it.needsYou },
            working = ts.count { !it.needsYou } + ags.count { !it.needsYou },
            at = maxOf(agents?.at ?: 0, tasks?.at ?: 0),
        )
    }

    /** [machineId]'s part with [tag] replaced by [item], or taken out when null. */
    private fun Map<String, TallyPart>.one(machineId: String, tag: String, item: TallyItem?, at: Long): Map<String, TallyPart> {
        val old = this[machineId]
        val items = old?.items.orEmpty().filter { it.tag != tag } + listOfNotNull(item)
        // A single change says nothing of the rest, so the part's own time stays unless it had none.
        return this + (machineId to TallyPart(items, if (old == null) at else maxOf(old.at, at)))
    }

    /** [machineId]'s part with what works replaced by [working]; what needs you stays, unless it works now. */
    private fun Map<String, TallyPart>.working(machineId: String, working: List<TallyItem>, at: Long): Map<String, TallyPart> {
        val tags = working.map { it.tag }.toSet()
        val need = this[machineId]?.items.orEmpty().filter { it.needsYou && it.tag !in tags }
        return this + (machineId to TallyPart(need + working, at))
    }
}
