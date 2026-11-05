package com.makefunstuff.herdr.core.activity

import com.makefunstuff.herdr.core.code.FileDiff

/**
 * What an agent did, step by step, so a person can follow a task without reading its whole chat or
 * terminal: built from what the agent's harness records (Claude Code's, codex's and pi's session
 * files, [ActivityLog]).
 */
enum class StepKind(val label: String) {
    Message("Message"),
    Command("Command"),
    /** A command that runs tests or checks (a build, a linter): passed or failed. */
    Test("Test"),
    /** A file read, or a search through files. */
    Read("Read"),
    Edit("Edit"),
    Web("Web"),
    Tool("Tool"),
    Plan("Plan"),
    Review("Review"),
    Handover("Hand-over"),
    Board("Board"),
    /** A quiet marker: interrupted, compacted, an error of the harness's own. */
    Notice("Notice"),
}

enum class StepState { Running, Done, Failed }

/** One thing the agent (or the board) did. Times are the machine's clock, in ms; 0 where unknown. */
data class Step(
    val id: String,
    val kind: StepKind,
    /** The one line worth showing: a command, a path, a query, a message's first line. */
    val title: String,
    val at: Long = 0,
    val end: Long = 0,
    val state: StepState = StepState.Done,
    /** A command's exit status, when the harness said. */
    val exit: Int? = null,
    /** More than the title: a message's whole text, a command in full, a review's verdict. */
    val detail: String? = null,
    /** What it printed or answered, cut to a size a phone shows. */
    val output: String? = null,
    /** The file it read or changed. */
    val path: String? = null,
    /** What an edit changed in [path]. */
    val diff: FileDiff? = null,
    /** The harness's own name for the tool (`Bash`, `exec_command`, `edit`). */
    val tool: String = "",
    /** A short note beside the title: `in the background`, `3 findings`. */
    val note: String? = null,
) {
    /** How long it took, when both ends are known. */
    val took: Long? get() = if (at > 0 && end >= at) end - at else null
    val failed: Boolean get() = state == StepState.Failed
}

/** Who a session's turns belong to on the board: the task's agent, or its reviewer in a round. */
data class Role(val name: String, val round: Int = 0) {
    val label: String get() = when {
        name == AGENT -> "Agent"
        name == REVIEWER && round > 0 -> "Reviewer · round $round"
        name == REVIEWER -> "Reviewer"
        else -> name.replaceFirstChar { it.uppercase() }
    }

    companion object {
        const val AGENT = "agent"
        const val REVIEWER = "reviewer"
        val Agent = Role(AGENT)
    }
}

/**
 * One turn: what started it (a message from you, the board, a background job finishing) and every
 * step the agent took until it stopped.
 */
data class Turn(
    val id: String,
    /** What started it, in a few words (the board's own messages are named for what they do). */
    val prompt: String,
    /** The whole message that started it. */
    val text: String,
    val from: Origin,
    val at: Long,
    val end: Long,
    val steps: List<Step>,
    val role: Role = Role.Agent,
    /** The harness and model that ran it (`claude`, `codex/gpt-6.1`). */
    val agent: String = "",
) {
    val took: Long? get() = if (at > 0 && end >= at) end - at else null
    val failures: Int get() = steps.count { it.failed }
    val running: Boolean get() = steps.any { it.state == StepState.Running }
}

/** Where a turn's first message came from. */
enum class Origin { You, Board, Agent, System }

/** The timeline as shown: turns and the board's events between them, oldest first. */
sealed interface Entry {
    val at: Long
    val key: String

    data class TurnEntry(val turn: Turn) : Entry {
        override val at: Long get() = turn.at
        override val key: String get() = "t:${turn.id}"
    }

    /** Something the board did or noticed: started an agent, a review's verdict, a merge. */
    data class Event(val step: Step) : Entry {
        override val at: Long get() = step.at
        override val key: String get() = "e:${step.id}"
    }
}

/** What the filter chips keep. */
enum class StepFilter(val label: String) {
    All("All"),
    Failures("Failures"),
    Commands("Commands"),
    Edits("Edits"),
    Tests("Tests"),
    Messages("Messages");

    fun keeps(s: Step): Boolean = when (this) {
        All -> true
        Messages -> s.kind == StepKind.Message
        Commands -> s.kind == StepKind.Command || s.kind == StepKind.Test
        Edits -> s.kind == StepKind.Edit
        Tests -> s.kind == StepKind.Test
        Failures -> s.failed
    }
}

/** The whole timeline of a task or an agent. */
data class Timeline(
    val entries: List<Entry>,
    /** Some session's start wasn't read (only its end, as it is long). */
    val truncated: Boolean = false,
    /** Sessions the board names that couldn't be found on the machine. */
    val missing: Int = 0,
) {
    val turns: List<Turn> get() = entries.mapNotNull { (it as? Entry.TurnEntry)?.turn }
    val steps: List<Step> get() = entries.flatMap { e -> if (e is Entry.TurnEntry) e.turn.steps else listOf((e as Entry.Event).step) }
    val failures: Int get() = steps.count { it.failed }
    val at: Long get() = entries.firstOrNull { it.at > 0 }?.at ?: 0
    val end: Long get() = entries.maxOfOrNull { e -> if (e is Entry.TurnEntry) maxOf(e.turn.end, e.turn.at) else e.at } ?: 0

    /** What [filter] keeps: turns with only their kept steps (a turn with none left goes), events that match. */
    fun filtered(filter: StepFilter): List<Entry> {
        if (filter == StepFilter.All) return entries
        return entries.mapNotNull { e ->
            when (e) {
                is Entry.TurnEntry -> e.turn.steps.filter(filter::keeps).takeIf { it.isNotEmpty() }?.let { Entry.TurnEntry(e.turn.copy(steps = it)) }
                is Entry.Event -> e.takeIf { filter.keeps(it.step) }
            }
        }
    }

    /** How many of each kind, for the chips. */
    fun count(filter: StepFilter): Int = steps.count(filter::keeps)

    companion object {
        val Empty = Timeline(emptyList())

        /** The board's events without one that repeats another's title within five minutes of it. */
        private fun once(events: List<Step>): List<Step> {
            val out = ArrayList<Step>()
            for (e in events.sortedBy { it.at }) {
                val same = out.indexOfFirst { o -> o.kind == StepKind.Board && e.kind == StepKind.Board && o.title == e.title && kotlin.math.abs(o.at - e.at) < 5 * 60_000 }
                if (same < 0) out += e else if (out[same].note == null && e.note != null) out[same] = e.copy(at = out[same].at)
            }
            return out
        }

        /**
         * The sessions' turns and the board's events as one timeline, by time. A board event that
         * repeats a turn's own start (the board typed that message, and the transcript has it) goes.
         */
        fun of(sessions: List<List<Turn>>, events: List<Step>, truncated: Boolean = false, missing: Int = 0): Timeline {
            val turns = sessions.flatten()
            val starts = turns.map { Prompts.key(it.text) }.toSet()
            val kept = events.filter { e ->
                e.kind != StepKind.Board || (
                    (e.detail == null || Prompts.key(e.detail) !in starts) &&
                        // "Started the agent" from the record, when the turn it started is there.
                        turns.none { t -> t.prompt == e.title && t.at > 0 && e.at > 0 && kotlin.math.abs(t.at - e.at) < 5 * 60_000 }
                    )
            }
                // One start told twice (the record's, with its agent, and what the board typed): the one that says more.
                .let(::once)
            val entries = (turns.map { Entry.TurnEntry(it) } + kept.map { Entry.Event(it) })
                // Stable: what has no time keeps its place after what came before it.
                .sortedWith(compareBy { if (it.at > 0) it.at else Long.MAX_VALUE })
            return Timeline(entries, truncated, missing)
        }
    }
}

/** Durations and times as the timeline shows them. */
object Durations {
    /** `0.4s`, `12s`, `3m 05s`, `1h 12m`. */
    fun short(ms: Long): String = when {
        ms < 1_000 -> "%.1fs".format(ms / 1000.0)
        ms < 60_000 -> "${ms / 1000}s"
        ms < 3_600_000 -> "${ms / 60_000}m %02ds".format((ms / 1000) % 60)
        else -> "${ms / 3_600_000}h %02dm".format((ms / 60_000) % 60)
    }
}
