package com.makefunstuff.herdr.core.brief

import kotlinx.serialization.Serializable

/**
 * The brief: what the agents and the work board did across the machines since the person last
 * looked (overnight, say), and what waits for them now. Plain data, built by [Briefs] from what each
 * machine says, so the rules are tested without a phone; the app shows it, posts it as a
 * notification at the times they choose, and reads it aloud ([BriefWords.spoken]).
 */
@Serializable
data class Brief(
    /** When it was put together (the phone's clock, ms). */
    val at: Long,
    /** What it covers: from [since] to [at]. */
    val since: Long,
    val machines: List<BriefMachine>,
    /** What waits for you now: tasks to accept or answer, agents that ask. */
    val waiting: List<BriefItem> = emptyList(),
    /** Tasks done in the window: merged, or done without a pull request. */
    val done: List<BriefItem> = emptyList(),
    /** Agents of your own (not a board task's) that finished in the window. */
    val finished: List<BriefItem> = emptyList(),
    /** Tasks and agents at work now. */
    val working: List<BriefItem> = emptyList(),
    /** Tasks that started work in the window, and tasks added in it. */
    val started: Int = 0,
    val added: Int = 0,
) {
    val merged: Int get() = done.count { it.kind == BriefItem.Kind.Merged }
    val needsYou: Int get() = waiting.size

    /** Nothing happened in the window and nothing waits. */
    val quiet: Boolean get() = waiting.isEmpty() && done.isEmpty() && finished.isEmpty() && started == 0 && added == 0

    val reached: List<BriefMachine> get() = machines.filter { it.coverage != BriefMachine.Coverage.Unreached }
}

/** One line of the brief: a task or an agent, as it stands or what it did. */
@Serializable
data class BriefItem(
    val kind: Kind,
    val machineId: String,
    val machineLabel: String,
    /** The task's title, or what the agent works on. */
    val title: String,
    /** The task's short key (`AND-7`), or the agent's name as its chat shows it. */
    val name: String = "",
    /** Where it stands, in the board's words: "Ready", "Needs you", "merged", "asks you". */
    val status: String = "",
    /** The board's "doing now" line, or what an agent asks. */
    val detail: String = "",
    /** When it happened, on the phone's clock (ms); 0 when the brief can't tell. */
    val at: Long = 0,
    /** It happened in the window (a task that waited before is still listed, as not new). */
    val new: Boolean = true,
    val taskId: String = "",
    val paneId: String = "",
    /** A task's pull request. */
    val pr: String = "",
    /** A [com.makefunstuff.herdr.core.board.WorkStatus] name for a task; empty for an agent. */
    val board: String = "",
    /** A task's progress, 0 to 100, while it works. */
    val progress: Int? = null,
) {
    enum class Kind { Waiting, Merged, Done, Finished, Working }

    val isTask: Boolean get() = taskId.isNotEmpty()

    /** One per task or agent across the machines, for the screen and for what is being read aloud. */
    val ref: String get() = if (isTask) "task:$machineId:$taskId" else "agent:$machineId:$paneId"
}

/** How a machine came into the brief. */
@Serializable
data class BriefMachine(
    val id: String,
    val label: String,
    val coverage: Coverage,
    /** Why it isn't in the brief, or what is missing ("its board didn't answer"). */
    val problem: String? = null,
    /** [Coverage.Partial]: herdr-push kept track from here (the phone's clock, ms; 0 the whole window)… */
    val from: Long = 0,
    /** …to here (0: up to now). [Coverage.Compared]: when the app last saw the machine's tasks. */
    val upto: Long = 0,
) {
    enum class Coverage {
        /** herdr-push's journal covers the whole window: everything that happened there is in it. */
        Journal,
        /** The journal covers part of the window: herdr-push started later, or was stopped for a while. */
        Partial,
        /** No journal: compared with what the app showed when it was last looked at ([upto]). */
        Compared,
        /** No journal, and the app never saw it before: only how it stands now. */
        NowOnly,
        /** It couldn't be reached. */
        Unreached,
    }
}
