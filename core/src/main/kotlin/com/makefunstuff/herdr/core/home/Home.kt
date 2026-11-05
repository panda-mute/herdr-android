package com.makefunstuff.herdr.core.home

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.board.WorkOverview
import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.core.shepherdr.V2Need
import com.makefunstuff.herdr.core.shepherdr.V2SecretAsk
import com.makefunstuff.herdr.core.push.AlertRules
import com.makefunstuff.herdr.core.push.Notify

/** An agent as the home list sees it: the machine it runs on, where in herdr, and its workspace's name. */
data class HomeAgent(
    val machineId: String,
    val machineLabel: String,
    val agent: AgentInfo,
    /** `workspace › tab`, as the app shows it. */
    val location: String,
    /** The workspace's name alone: a task's agent may work in one named after the task. */
    val workspace: String = location.substringBefore(" › "),
) {
    val key: String get() = "$machineId/${agent.paneId}"

    /** Its space, as the drawer keys it ([com.makefunstuff.herdr.core.push.Notify.key]): its workspace's name on its machine. */
    val space: String get() = Notify.key(machineId, workspace.ifBlank { agent.workspaceId })

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

/** A machine's Shepherdr board as the app last read it: its spaces and tasks. */
data class HomeBoard(val machineId: String, val machineLabel: String, val work: WorkOverview) {
    val tasks: List<WorkTask> get() = work.tasks
}

/** Something on the Needs you list. [key] stays the same while it waits, whatever it waits for. */
sealed interface Waiting {
    val key: String
    val machineId: String
    /** The task, when there is one: its name and title name the item. */
    val task: WorkTask?

    /** An agent asking a question, with the task it works on, when it works on one. */
    data class Question(val agent: HomeAgent, override val task: WorkTask?) : Waiting {
        override val key: String get() = "agent/${agent.key}"
        override val machineId: String get() = agent.machineId
    }

    /** A task waiting for the human, with its agent when one is running on that machine; answered and merged through Shepherdr. */
    data class Task(
        override val machineId: String,
        val machineLabel: String,
        override val task: WorkTask,
        val agent: HomeAgent?,
    ) : Waiting {
        override val key: String get() = "task/$machineId/${task.id}"
    }

    /** A Shepherdr space whose Assistant asks the human something: it is answered in that Assistant's chat. */
    data class Coordinator(override val machineId: String, val machineLabel: String, val space: WorkSpace) : Waiting {
        override val key: String get() = "asks/$machineId/${space.id}"
        override val task: WorkTask? get() = null
    }

    /**
     * An agent that asks for a secret by name ("Wants the secret GITHUB_TOKEN"), answered in a masked box: [need]
     * carries the ask ([V2Need.secret]) and its chat; [task] its task, or none for a space's Assistant in [space].
     */
    data class Secret(
        override val machineId: String,
        val machineLabel: String,
        val need: V2Need,
        override val task: WorkTask?,
        val space: WorkSpace?,
    ) : Waiting {
        override val key: String get() = "secret/$machineId/${need.id}"
        val ask: V2SecretAsk get() = need.secret ?: V2SecretAsk()
    }
}

/**
 * An agent on the list below Needs you: its task when it has one; [new] for a finish not looked
 * at yet; [pinned] on top by the phone; [muted]: its space's Notify is Off (or it was muted before Notify).
 */
data class AgentRow(
    val agent: HomeAgent,
    val task: WorkTask?,
    val new: Boolean = false,
    val pinned: Boolean = false,
    val muted: Boolean = false,
)

/**
 * The Agents screen's list, across machines: what waits for you first (blocked agents and tasks
 * waiting for an answer or an Accept, an agent and its task shown once), then the agents
 * pinned, the agents at work, the ones that finished and weren't looked at yet, and the rest (a finish
 * seen is at rest). A question always shows in Needs you, pinned or not.
 */
data class Home(
    val needsYou: List<Waiting> = emptyList(),
    val pinned: List<AgentRow> = emptyList(),
    val working: List<AgentRow> = emptyList(),
    val done: List<AgentRow> = emptyList(),
    val idle: List<AgentRow> = emptyList(),
) {
    /** Finished and not looked at: herdr still says `done`, and the phone hasn't opened it since (pinned ones too). */
    val fresh: Int get() = done.count { it.new } + pinned.count { it.new }

    val isEmpty: Boolean get() = needsYou.isEmpty() && pinned.isEmpty() && working.isEmpty() && done.isEmpty() && idle.isEmpty()

    companion object {
        /**
         * [agent]'s task among its own machine's [tasks]: the one Shepherdr labels its terminal with, else the one its
         * workspace is named after (`HA-10`, `HA-10 Fix login`); null when it works on none.
         */
        fun taskOf(agent: HomeAgent, tasks: List<WorkTask>): WorkTask? {
            agent.agent.shepherdrTask?.let { id -> return tasks.firstOrNull { it.id == id } }
            val ws = agent.workspace.ifEmpty { return null }
            return tasks.firstOrNull { t -> ws == t.id || ws.startsWith("${t.id} ") }
        }

        /** [name] says nothing of what [agent] works on: it's only its CLI's (`claude`) or a launcher's hash (`shep-1a2b…`). */
        fun isBareName(agent: AgentInfo, name: String): Boolean =
            name.equals(agent.agent, ignoreCase = true) || name.equals(agent.displayAgent, ignoreCase = true) || HASH.matches(name)

        private val HASH = Regex("""shep-[0-9a-f]{16}""")

        /**
         * The list from every machine's [agents] (in the machines' order) and [boards]. [seen] holds,
         * by [HomeAgent.key], the finish the phone last showed of an agent ([HomeAgent.finish]);
         * [triage] what is pinned (and muted from before Notify); [rules] each space's Notify.
         */
        fun of(
            agents: List<HomeAgent>,
            boards: List<HomeBoard>,
            seen: Map<String, Long> = emptyMap(),
            triage: Triage = Triage(),
            rules: AlertRules = AlertRules(),
        ): Home {
            val tasks = boards.associate { it.machineId to it.tasks }
            // Each agent's task: one of its own machine's board.
            val taskOf = agents.associate { a -> a.key to taskOf(a, tasks[a.machineId].orEmpty()) }
            val questions = agents.filter { it.agent.agentStatus == AgentStatus.Blocked }
            val asked = questions.mapNotNull { taskOf[it.key]?.let { t -> it.machineId to t.id } }.toSet()
            // Every secret an agent asks for, as 3.1's Needs you lists them: a task that waits on one is that item.
            val secrets = boards.flatMap { b ->
                b.work.needs.filter { it.secret != null && it.secret.saved == null }.map { n ->
                    Waiting.Secret(b.machineId, b.machineLabel, n, n.task?.let { id -> b.tasks.firstOrNull { it.id == id } }, b.work.spaces.firstOrNull { it.id == n.place })
                }
            }
            val secretTasks = secrets.mapNotNull { s -> s.task?.let { s.machineId to it.id } }.toSet()
            val waiting = boards.flatMap { b ->
                // Ready to accept first, then what asks: as the board lists them otherwise.
                b.tasks.filter { it.status.needsYou && (b.machineId to it.id) !in asked && !(it.status == WorkStatus.Need && (b.machineId to it.id) in secretTasks) }.sortedBy { if (it.status == WorkStatus.Ready) 0 else 1 }
                    .map { t ->
                        val agent = agents.firstOrNull { it.machineId == b.machineId && taskOf[it.key]?.id == t.id }
                        Waiting.Task(b.machineId, b.machineLabel, t, agent)
                    }
            }
            // An agent whose task waits is that task's card; it isn't listed again below.
            val inCards = waiting.mapNotNull { it.agent?.key }.toSet()
            val rest = agents.filter { it.agent.agentStatus != AgentStatus.Blocked && it.key !in inCards }
            fun isNew(a: HomeAgent) = a.agent.agentStatus == AgentStatus.Done && seen[a.key] != a.finish
            fun row(a: HomeAgent) = AgentRow(
                a, taskOf[a.key], new = isNew(a), pinned = triage.pinned(a.key),
                muted = a.key in triage.muted || rules.notifyOf(a.space) == Notify.Off,
            )
            val bySeq = compareByDescending<HomeAgent> { it.agent.stateChangeSeq }
            // Pinned stay on top in the order pinned; the rest by their state.
            val pinned = triage.pins.mapNotNull { k -> rest.firstOrNull { it.key == k } }
            val listed = rest.filter { it !in pinned }
            return Home(
                needsYou = questions.sortedWith(bySeq).map { Waiting.Question(it, taskOf[it.key]) } + secrets + waiting +
                    boards.flatMap { b -> b.work.spaces.filter { it.asks }.map { Waiting.Coordinator(b.machineId, b.machineLabel, it) } },
                pinned = pinned.map(::row),
                working = listed.filter { it.agent.agentStatus == AgentStatus.Working }.sortedWith(bySeq).map(::row),
                done = listed.filter(::isNew).sortedWith(bySeq).map(::row),
                idle = listed.filter { it.agent.agentStatus == AgentStatus.Idle || it.agent.agentStatus == AgentStatus.Unknown || it.agent.agentStatus == AgentStatus.Done && !isNew(it) }
                    .sortedWith(bySeq).map(::row),
            )
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
        is Waiting.Task, is Waiting.Coordinator, is Waiting.Secret -> "board:${w.machineId}"
    }

    companion object {
        /** What an item waits for: an agent's question (its state change), or a task's state. */
        fun sig(w: Waiting): String = when (w) {
            is Waiting.Question -> "q${w.agent.agent.stateChangeSeq}"
            is Waiting.Task -> "${w.task.status}|${w.task.line}"
            is Waiting.Coordinator -> "asks"
            is Waiting.Secret -> "secret"
        }
    }
}
