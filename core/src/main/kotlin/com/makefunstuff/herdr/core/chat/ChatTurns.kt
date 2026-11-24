package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.activity.Step
import com.makefunstuff.herdr.core.activity.StepKind
import com.makefunstuff.herdr.core.activity.StepState
import com.makefunstuff.herdr.core.activity.Tools
import com.makefunstuff.herdr.core.activity.Turn
import com.makefunstuff.herdr.core.code.FileDiff

/**
 * One turn of the chat as the herdr web UI's work block shows it: what you said, everything the
 * agent did on the way (tool calls, the narration between them, quiet markers) folded under one
 * "Worked for 2m 36s · 1 edit · 1 command" header, and what it said after its last action, outside
 * the fold, as its answer (with the reasoning that led to it, above it).
 */
data class ChatTurn(
    /** Stays the same while the turn grows: its first item's id. */
    val key: String,
    /** What started it, when the chat shows it (not before the first message, or after a cut). */
    val user: ChatItem.User?,
    val work: List<ChatItem>,
    /** What came after its last action: its words ([ChatItem.Agent]) and the reasoning between them ([ChatItem.Reasoning]). */
    val answer: List<ChatItem>,
    /** When it started and its last activity, on the machine's clock in ms; 0 when unknown. */
    val at: Long,
    val end: Long,
    val counts: WorkCounts,
    /** Tool calls that failed: said apart from the counts, since the fold hides their rows. */
    val failed: Int,
    /** Settled, its work still shows: it holds words the answer doesn't end on (no answer at all, or the agent spoke after it). */
    val keepsOpen: Boolean,
) {
    /** A header only for a turn that did something: words alone show as they are. */
    val hasWork: Boolean get() = work.any { it is ChatItem.Tool }
    val took: Long? get() = if (at > 0 && end >= at) end - at else null

    /** It said something after its last action, not only thought. */
    val answered: Boolean get() = answer.any { it is ChatItem.Agent }
}

/** How many of each kind of call a turn made, as the header counts them. */
data class WorkCounts(val edits: Int = 0, val reads: Int = 0, val commands: Int = 0, val others: Int = 0) {
    /** `1 edit · 2 file reads · 1 command · 1 other tool`, in the order a reader cares about. */
    val summary: String
        get() = listOfNotNull(
            count(edits, "edit", "edits"), count(reads, "file read", "file reads"),
            count(commands, "command", "commands"), count(others, "other tool", "other tools"),
        ).joinToString(" · ")

    private fun count(n: Int, one: String, many: String) = if (n <= 0) null else "$n ${if (n == 1) one else many}"
}

/**
 * A tool call's row: the object worth reading (paths short, from the repo) and what an edit changed,
 * its `+12 −2` and its [diffs] to show opened.
 */
data class ToolLine(
    val text: String,
    val isPath: Boolean,
    val added: Int? = null,
    val removed: Int? = null,
    val diffs: List<FileDiff> = emptyList(),
)

object ChatTurns {
    fun changes(turns: List<ChatTurn>, steps: Steps): Map<String, List<FileDiff>> = buildMap {
        for (turn in turns) {
            val diffs = turn.work.asSequence().filterIsInstance<ChatItem.Tool>()
                .filter { it.state == ToolState.Done && (it.diffs.isNotEmpty() || steps.of(it.id).any { s -> s.diff != null }) }
                .distinctBy { it.id }.flatMap { tool -> line(tool, steps).let { if (it.added == null) emptyList() else it.diffs } }.toList()
            if (diffs.isNotEmpty()) put(turn.key, diffs)
        }
    }

    /**
     * Cuts [items] into turns at each message from you. [turns] is the same transcript read as timed
     * steps ([com.makefunstuff.herdr.core.activity.ActivityLog]): it gives the times, the kinds and
     * the failures, matched by the ids both readers give a message or a call.
     */
    fun of(items: List<ChatItem>, turns: List<Turn>): List<ChatTurn> = of(items, Steps(turns))

    fun of(items: List<ChatItem>, steps: Steps): List<ChatTurn> {
        val out = ArrayList<ChatTurn>()
        var user: ChatItem.User? = null
        val parts = ArrayList<ChatItem>()
        fun close() {
            if (user != null || parts.isNotEmpty()) out += turn(user, parts.toList(), steps)
            user = null
            parts.clear()
        }
        for (item in items) {
            if (item is ChatItem.User) {
                close()
                user = item
            } else {
                parts += item
            }
        }
        close()
        return out
    }

    private fun turn(user: ChatItem.User?, parts: List<ChatItem>, steps: Steps): ChatTurn {
        // Everything up to the last action is work; the words (and thoughts) after it are the answer.
        val lastAction = parts.indexOfLast { it !is ChatItem.Agent && it !is ChatItem.Reasoning }
        val answer = parts.drop(lastAction + 1)
        val work = parts.take(lastAction + 1)
        val tools = work.filterIsInstance<ChatItem.Tool>()
        val start = user?.let { steps.turn(it.id) }
        val known = parts.flatMap { steps.of(it.id) }
        val at = start?.at?.takeIf { it > 0 } ?: known.filter { it.at > 0 }.minOfOrNull { it.at } ?: 0
        val end = maxOf(start?.end ?: 0, known.maxOfOrNull { maxOf(it.at, it.end) } ?: 0)
        var counts = WorkCounts()
        for (t in tools) {
            counts = when (kind(t, steps)) {
                StepKind.Edit -> counts.copy(edits = counts.edits + 1)
                StepKind.Read -> counts.copy(reads = counts.reads + 1)
                StepKind.Command, StepKind.Test -> counts.copy(commands = counts.commands + 1)
                // A plan update is the plan, not work to count.
                StepKind.Plan -> counts
                else -> counts.copy(others = counts.others + 1)
            }
        }
        val failed = tools.count { t -> t.state == ToolState.Failed || steps.of(t.id).any { it.failed } }
        return ChatTurn(
            key = user?.id ?: parts.first().id,
            user = user,
            work = work,
            answer = answer,
            at = at,
            end = end,
            counts = counts,
            failed = failed,
            keepsOpen = answer.none { it is ChatItem.Agent } && work.any { it is ChatItem.Agent },
        )
    }

    private fun kind(tool: ChatItem.Tool, steps: Steps): StepKind =
        steps.of(tool.id).firstOrNull()?.kind ?: Tools.kind(tool.name, tool.summary)

    /** The row of [tool]: its file paths short, with an edit's `+12 −2`, or its own summary. */
    fun line(tool: ChatItem.Tool, all: Steps): ToolLine {
        val steps = all.of(tool.id)
        val paths = steps.filter { it.kind == StepKind.Read || it.kind == StepKind.Edit }.mapNotNull { it.path }.distinct()
        // The result's diff, with its line numbers, before the one read from the call; a failed call changed nothing.
        val diffs = steps.mapNotNull { it.diff }.ifEmpty { tool.diffs.map { relative(it, paths) } }
        val counted = diffs.takeIf { it.isNotEmpty() && tool.state != ToolState.Failed && steps.none { it.failed } }
        val added = counted?.sumOf { it.added }
        val removed = counted?.sumOf { it.removed }
        val first = tool.summary.lineSequence().firstOrNull().orEmpty().trim()
        return when {
            paths.isNotEmpty() && (first.isEmpty() || looksLikePath(first) || paths.any { first.endsWith(it) }) ->
                ToolLine(paths.joinToString(", ") { shortPath(it) }, true, added, removed, diffs)
            looksLikePath(first) -> ToolLine(shortPath(first), true, added, removed, diffs)
            else -> ToolLine(first, false, added, removed, diffs)
        }
    }

    /** [diff] with its paths as the steps name them (from the repo) when they are the same files. */
    private fun relative(diff: FileDiff, paths: List<String>): FileDiff {
        fun of(path: String?) = path?.let { p -> paths.firstOrNull { p == it || p.endsWith("/$it") } ?: p }
        return diff.copy(oldPath = of(diff.oldPath), newPath = of(diff.newPath))
    }

    private fun looksLikePath(text: String) = ' ' !in text && '/' in text && (text.startsWith("/") || text.startsWith("~") || text.startsWith("./") || '.' in text.substringAfterLast('/'))

    private val HOME = Regex("""^/(?:home|Users)/[^/]+(?=/|$)|^/root(?=/|$)""")

    /** [path] whole, with home as `~`. */
    fun homePath(path: String): String = path.removePrefix("file://").replace(HOME, "~")

    /**
     * A path a phone can show: from the repo when it is in it (the reader gives those already), home
     * as `~`, and a deep one as its first folder and its last two parts: `core/…/host/HostState.kt`.
     */
    fun shortPath(path: String): String {
        val p = homePath(path).removePrefix("./")
        val parts = p.split('/')
        if (parts.size <= 4) return p
        return parts.first() + "/…/" + parts.takeLast(2).joinToString("/")
    }

    /** Words after its last call this long ago: the turn is over, whatever the agent works on now. */
    const val SETTLED_MS = 3 * 60_000L

    /**
     * Whether [turn] can be the one an agent reported working is at, at [now]: not when it ended on
     * its answer a while ago, as when a prompt typed in the terminal or sent by the board hasn't
     * reached the transcript yet (that turn would reopen, timed from its old start).
     */
    fun mayBeRunning(turn: ChatTurn, now: Long): Boolean =
        !turn.answered || turn.end <= 0 || now - turn.end < SETTLED_MS

    /**
     * How far the machine's clock is ahead of the phone's at least, from [turns] (timed on the
     * machine) seen at the phone's [now]: a step can't have happened later than now. Added to the
     * phone's clock to time the turns, it keeps a fast machine from showing no time, or a short one.
     */
    fun ahead(turns: List<Turn>, now: Long): Long =
        maxOf(0L, (turns.lastOrNull()?.let { t -> maxOf(t.end, t.steps.maxOfOrNull { maxOf(it.at, it.end) } ?: 0) } ?: 0) - now)

    /** `7s`, `1m 12s`, `3m`, `1h 5m`: as the web UI's work header says it. */
    fun duration(ms: Long): String {
        val seconds = (ms + 500) / 1000
        if (seconds < 60) return "${seconds}s"
        val minutes = seconds / 60
        val rest = seconds % 60
        if (minutes < 60) return if (rest > 0) "${minutes}m ${rest}s" else "${minutes}m"
        return "${minutes / 60}h ${minutes % 60}m"
    }

    /** What the agent is at in [turn], in a few words: `editing HostState.kt`, `running tests`; null when nothing runs. */
    fun doing(turn: Turn?): String? {
        val step = turn?.steps?.lastOrNull { it.state == StepState.Running && it.kind != StepKind.Message } ?: return null
        // A file has a name with a dot; a folder a search ran in doesn't.
        val file = step.path?.substringAfterLast('/')?.takeIf { '.' in it }
        return when (step.kind) {
            StepKind.Edit -> file?.let { "editing $it" } ?: "editing files"
            StepKind.Read -> file?.let { "reading $it" } ?: "searching the code"
            StepKind.Test -> "running tests"
            StepKind.Command -> "running a command"
            StepKind.Web -> "on the web"
            StepKind.Plan -> "planning"
            else -> step.tool.takeIf { it.isNotEmpty() }?.let { "using ${it.substringAfterLast("__")}" }
        }
    }

    /** The steps of a transcript's turns by the ids the chat's items have. */
    class Steps(turns: List<Turn>) {
        private val byId = HashMap<String, MutableList<Step>>()
        private val starts = HashMap<String, Turn>()

        init {
            for (t in turns) {
                starts[t.id] = t
                for (s in t.steps) {
                    byId.getOrPut(s.id) { ArrayList() } += s
                    // codex's file change is one call in the chat and a step per file in the reader (`<id>/<file>`).
                    if (s.kind == StepKind.Edit && '/' in s.id) byId.getOrPut(s.id.substringBefore('/')) { ArrayList() } += s
                }
            }
        }

        fun of(id: String): List<Step> = byId[id].orEmpty()

        /** The turn your message [id] started. */
        fun turn(id: String): Turn? = starts[id]
    }
}
