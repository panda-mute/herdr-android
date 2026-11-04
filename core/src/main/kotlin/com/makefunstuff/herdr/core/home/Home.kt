package com.makefunstuff.herdr.core.home

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.board.BoardAction
import com.makefunstuff.herdr.core.board.BoardStatus
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.WorkOverview
import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.core.push.LiveItems

/** An agent as the home list sees it: the machine it runs on, where in herdr, and its workspace's name. */
data class HomeAgent(
    val machineId: String,
    val machineLabel: String,
    val agent: AgentInfo,
    /** `workspace › tab`, as the app shows it. */
    val location: String,
    /** The workspace's name alone: a board task's agent works in one named after the task. */
    val workspace: String = location.substringBefore(" › "),
) {
    val key: String get() = "$machineId/${agent.paneId}"

    companion object {
        /** Every agent on the machines' last snapshots (classic herdr's, then herdr-core's), in the machines' order. */
        fun of(hosts: List<HostState>): List<HomeAgent> = hosts.flatMap { s ->
            val snap = s.view ?: return@flatMap emptyList()
            snap.agents.map { HomeAgent(s.machine.id, s.machine.label, it, snap.location(it.workspaceId, it.tabId), snap.workspace(it.workspaceId)?.label.orEmpty()) }
        }
    }

    /** Which of its finishes this is: herdr's completion_seq, or on an older herdr the state change that ended it. */
    val finish: Long get() = agent.completionSeq ?: agent.stateChangeSeq
}

/** A board's tasks as the app last read them on one machine; on Shepherdr, [work] is the board as it sent them. */
data class HomeBoard(val machineId: String, val machineLabel: String, val tasks: List<BoardTask>, val work: WorkOverview? = null)

/** Something on the Needs you list. [key] stays the same while it waits, whatever it waits for. */
sealed interface Waiting {
    val key: String
    val machineId: String
    /** The board task, when there is one: its key and title name the item. */
    val task: BoardTask?

    /** An agent asking a question, with the board task it works on, when it works on one. */
    data class Question(val agent: HomeAgent, override val task: BoardTask?) : Waiting {
        override val key: String get() = "agent/${agent.key}"
        override val machineId: String get() = agent.machineId
    }

    /**
     * A board task waiting for the human, with its agent when one is running on that machine. A Shepherdr
     * task is [work] too ([task] is its v1 row): it is answered and merged through Shepherdr.
     */
    data class Task(
        override val machineId: String,
        val machineLabel: String,
        override val task: BoardTask,
        val agent: HomeAgent?,
        val work: WorkTask? = null,
    ) : Waiting {
        override val key: String get() = "task/$machineId/${task.id}"
    }

    /** A Shepherdr space whose Assistant asks the human something: it is answered in that Assistant's chat. */
    data class Coordinator(override val machineId: String, val machineLabel: String, val space: WorkSpace) : Waiting {
        override val key: String get() = "asks/$machineId/${space.id}"
        override val task: BoardTask? get() = null
    }
}

/**
 * An agent on the list below Needs you: its board task when it has one; [new] for a finish not looked
 * at yet; how the phone triaged it: [pinned], [snooze] (while it holds), [muted].
 */
data class AgentRow(
    val agent: HomeAgent,
    val task: BoardTask?,
    val new: Boolean = false,
    val pinned: Boolean = false,
    val snooze: Snooze? = null,
    val muted: Boolean = false,
)

/**
 * The Agents screen's list, across machines: what waits for you first (blocked agents and board
 * tasks waiting for a yes, a fix or an answer, an agent and its task shown once), then the agents
 * pinned, the agents at work, the ones that finished and weren't looked at yet, the rest (a finish
 * seen is at rest), and those snoozed. A question always shows in Needs you, pinned, snoozed or not.
 */
data class Home(
    val needsYou: List<Waiting> = emptyList(),
    val pinned: List<AgentRow> = emptyList(),
    val working: List<AgentRow> = emptyList(),
    val done: List<AgentRow> = emptyList(),
    val idle: List<AgentRow> = emptyList(),
    val snoozed: List<AgentRow> = emptyList(),
) {
    /** Finished and not looked at: herdr still says `done`, and the phone hasn't opened it since (pinned ones too). */
    val fresh: Int get() = done.count { it.new } + pinned.count { it.new }

    val isEmpty: Boolean get() = needsYou.isEmpty() && pinned.isEmpty() && working.isEmpty() && done.isEmpty() && idle.isEmpty() && snoozed.isEmpty()

    companion object {
        /** [agent]'s task among its own machine's board [tasks], by the names herdr-projects gives them; null when it works on none. */
        fun taskOf(agent: HomeAgent, tasks: List<BoardTask>): BoardTask? {
            // Shepherdr labels its task agents' terminals with their task.
            agent.agent.shepherdrTask?.let { id -> return tasks.firstOrNull { it.id == id && !it.archived } }
            return tasks.firstOrNull { t ->
                t.machine.isEmpty() && !t.archived && LiveItems.belongs(agent.agent.name.orEmpty(), agent.workspace, t.id, t.name)
            }
        }

        /** [name] says nothing of what [agent] works on: it's only its CLI's (`claude`) or a launcher's hash (`shep-1a2b…`). */
        fun isBareName(agent: AgentInfo, name: String): Boolean =
            name.equals(agent.agent, ignoreCase = true) || name.equals(agent.displayAgent, ignoreCase = true) || HASH.matches(name)

        private val HASH = Regex("""shep-[0-9a-f]{16}""")

        /**
         * The agent at work on [t] among [agents]: its worker before its reviewer (`sw-<repo>-<n>-r`, `…-r-0042`),
         * which matches the task's names too while both are open. Null when none is.
         */
        fun agentOf(agents: List<HomeAgent>, t: BoardTask): HomeAgent? =
            agents.filter { taskOf(it, listOf(t)) != null }.minByOrNull { if (REVIEWER.containsMatchIn(it.agent.name.orEmpty())) 1 else 0 }

        private val REVIEWER = Regex("""-r(?:-\d+)?$""")

        /** The order tasks wait in: ready to merge first, then what failed, plans and choices, questions, the rest. */
        val ORDER = listOf(
            BoardStatus.Approval, BoardStatus.ReviewFailed, BoardStatus.Error, BoardStatus.PlanReady, BoardStatus.PickOne,
            BoardStatus.Asks, BoardStatus.AsksOk, BoardStatus.NeedsLogin, BoardStatus.Stopped,
        )

        /**
         * The list from every machine's [agents] (in the machines' order) and [boards]. [seen] holds,
         * by [HomeAgent.key], the finish the phone last showed of an agent ([HomeAgent.finish]);
         * [triage] what is pinned, snoozed (as it stands at [now]) and muted.
         */
        fun of(
            agents: List<HomeAgent>,
            boards: List<HomeBoard>,
            seen: Map<String, Long> = emptyMap(),
            triage: Triage = Triage(),
            now: Long = 0,
        ): Home {
            val tasks = own(boards)
            // Each agent's task: one of its own machine's board, by the names herdr-projects gives them.
            val taskOf = agents.associate { a -> a.key to taskOf(a, tasks[a.machineId].orEmpty()) }
            val questions = agents.filter { it.agent.agentStatus == AgentStatus.Blocked }
            val asked = questions.mapNotNull { taskOf[it.key]?.let { t -> it.machineId to t.id } }.toSet()
            val waiting = boards.flatMap { b ->
                tasks[b.machineId].orEmpty()
                    .filter { it.needsYou && (b.machineId to it.id) !in asked }
                    .sortedBy { ORDER.indexOf(it.status).let { i -> if (i < 0) ORDER.size else i } }
                    .map { t ->
                        val agent = agents.firstOrNull { it.machineId == b.machineId && taskOf[it.key]?.id == t.id }
                        Waiting.Task(b.machineId, b.machineLabel, t, agent, b.work?.tasks?.firstOrNull { it.id == t.id })
                    }
            }
            // An agent whose task waits is that task's card; it isn't listed again below.
            val inCards = waiting.mapNotNull { it.agent?.key }.toSet()
            val rest = agents.filter { it.agent.agentStatus != AgentStatus.Blocked && it.key !in inCards }
            fun isNew(a: HomeAgent) = a.agent.agentStatus == AgentStatus.Done && seen[a.key] != a.finish
            fun row(a: HomeAgent) = AgentRow(
                a, taskOf[a.key], new = isNew(a), pinned = triage.pinned(a.key), snooze = triage.snoozeOf(a, now), muted = a.key in triage.muted,
            )
            val bySeq = compareByDescending<HomeAgent> { it.agent.stateChangeSeq }
            // Pinned stay on top in the order pinned; snoozed wait at the bottom; the rest by their state.
            val pinned = triage.pins.mapNotNull { k -> rest.firstOrNull { it.key == k } }
            val snoozed = rest.filter { it.key !in triage.pins && triage.snoozeOf(it, now) != null }.sortedWith(bySeq)
            val listed = rest.filter { it !in pinned && it !in snoozed }
            return Home(
                needsYou = questions.sortedWith(bySeq).map { Waiting.Question(it, taskOf[it.key]) } + waiting +
                    boards.flatMap { b -> b.work?.spaces.orEmpty().filter { it.asks }.map { Waiting.Coordinator(b.machineId, b.machineLabel, it) } },
                pinned = pinned.map(::row),
                working = listed.filter { it.agent.agentStatus == AgentStatus.Working }.sortedWith(bySeq).map(::row),
                done = listed.filter(::isNew).sortedWith(bySeq).map(::row),
                idle = listed.filter { it.agent.agentStatus == AgentStatus.Idle || it.agent.agentStatus == AgentStatus.Unknown || it.agent.agentStatus == AgentStatus.Done && !isNew(it) }
                    .sortedWith(bySeq).map(::row),
                snoozed = snoozed.map(::row),
            )
        }

        /**
         * Each machine's board, without another machine's task (`vm:repo/3`) when that machine's own
         * board, read here too, lists it: a task waits on one card, not two.
         */
        fun own(boards: List<HomeBoard>): Map<String, List<BoardTask>> {
            val own = boards.flatMap { b -> b.tasks.filter { it.machine.isEmpty() }.map { it.id.substringAfter(':') to it.title } }.toSet()
            return boards.associate { b ->
                b.machineId to b.tasks.filter { it.machine.isEmpty() || (it.id.substringAfter(':') to it.title) !in own }
            }
        }

        /**
         * The answer a waiting task's card offers beside Open, in the card's words: approve and merge,
         * fix (the review's findings, or a failure, sent back to its agent), approve a plan, reply to its
         * question, carry on after a stop. Null for a pick among attempts, which needs the comparison.
         */
        fun answer(t: BoardTask): Pair<BoardAction, String>? = when (t.status) {
            BoardStatus.Approval -> BoardAction.Approve to "Approve and merge"
            BoardStatus.ReviewFailed -> BoardAction.Review to "Fix"
            BoardStatus.Error -> if (t.stage == "approval") BoardAction.Fix to "Fix" else BoardAction.Retry to "Retry"
            BoardStatus.PlanReady -> BoardAction.ApprovePlan to "Approve plan"
            BoardStatus.Asks, BoardStatus.AsksOk, BoardStatus.NeedsLogin -> BoardAction.Tell to "Reply"
            BoardStatus.Stopped -> BoardAction.Retry to "Continue"
            else -> null
        }

        /** The marks to keep: [marks] with every finished agent in [home] (pinned ones too) marked seen now, and agents gone dropped. */
        fun seenAll(marks: Map<String, Long>, home: Home, agents: List<HomeAgent>): Map<String, Long> =
            keep(marks + (home.done + home.pinned.filter { it.new }).associate { it.agent.key to it.agent.finish }, agents)

        /** [marks] for the agents still there, so the store doesn't grow with every pane that ever finished. */
        fun keep(marks: Map<String, Long>, agents: List<HomeAgent>): Map<String, Long> {
            val there = agents.map { it.key }.toSet()
            return marks.filterKeys { it in there }
        }
    }
}

/** One machine's part of the Board tab: what waits for you, what is at work, what is to do, and how many are done. */
data class BoardGroup(
    val machineId: String,
    val machineLabel: String,
    val needsYou: List<BoardTask>,
    val working: List<BoardTask>,
    val todo: List<BoardTask>,
    val done: Int,
) {
    val tasks: List<BoardTask> get() = needsYou + working + todo

    companion object {
        private val WORKING = setOf(BoardStatus.Working, BoardStatus.Reviewing, BoardStatus.Queued)

        /** Every machine's board in one list, a group each (in the machines' order), [machine] alone when given. */
        fun of(boards: List<HomeBoard>, machine: String? = null): List<BoardGroup> {
            val tasks = Home.own(boards)
            return boards.filter { machine == null || it.machineId == machine }.map { b ->
                val list = tasks[b.machineId].orEmpty().filter { !it.archived }
                BoardGroup(
                    b.machineId, b.machineLabel,
                    needsYou = list.filter { it.needsYou }.sortedBy { Home.ORDER.indexOf(it.status).let { i -> if (i < 0) Home.ORDER.size else i } },
                    working = list.filter { !it.needsYou && it.status in WORKING },
                    todo = list.filter { !it.needsYou && it.status == BoardStatus.Todo },
                    done = list.count { !it.needsYou && it.status == BoardStatus.Done },
                )
            }
        }

        /** How many tasks wait for you across [boards], each once: the Board tab's badge. */
        fun waiting(boards: List<HomeBoard>): Int = Home.own(boards).values.sumOf { l -> l.count { it.needsYou } }
    }
}

/**
 * What newly needs you, for the in-app alert: the Needs you items that weren't there (or waited for
 * something else) the last time, and none from a machine read for the first time, so opening the app
 * doesn't announce what already waited.
 */
class WaitingNews {
    private val known = HashMap<String, String>()
    private val ready = HashSet<String>()

    /**
     * [waiting] now; the items new since the last call. [agentsRead] and [boardsRead]: the machines
     * whose agents, and whose boards, the app has read (their items count from the next call on).
     */
    @Synchronized
    fun update(waiting: List<Waiting>, agentsRead: Set<String>, boardsRead: Set<String>): List<Waiting> {
        val fresh = waiting.filter { source(it) in ready && known[it.key] != sig(it) }
        known.clear()
        waiting.forEach { known[it.key] = sig(it) }
        agentsRead.forEach { ready += "agents:$it" }
        boardsRead.forEach { ready += "board:$it" }
        return fresh
    }

    private fun source(w: Waiting) = when (w) {
        is Waiting.Question -> "agents:${w.machineId}"
        is Waiting.Task, is Waiting.Coordinator -> "board:${w.machineId}"
    }

    companion object {
        /** What an item waits for: an agent's question (its state change), or a task's state. */
        fun sig(w: Waiting): String = when (w) {
            is Waiting.Question -> "q${w.agent.agent.stateChangeSeq}"
            is Waiting.Task -> listOf(w.task.state, w.task.stage, w.task.kind, w.task.reviewRound, w.work?.line).joinToString("|")
            is Waiting.Coordinator -> "asks"
        }
    }
}
