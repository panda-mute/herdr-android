package com.makefunstuff.herdr.ui.agents

import com.makefunstuff.herdr.ui.theme.Mono
import com.makefunstuff.herdr.core.chat.VoiceNote
import com.makefunstuff.herdr.core.chat.VoiceNotes
import com.makefunstuff.herdr.ui.common.LocalVoiceMessages
import com.makefunstuff.herdr.ui.common.VoiceMessage
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.unit.sp
import com.makefunstuff.herdr.ui.theme.CodeStyle
import androidx.compose.ui.draw.drawBehind
import com.makefunstuff.herdr.core.chat.BlockedPrompt
import com.makefunstuff.herdr.core.chat.ChatItem
import com.makefunstuff.herdr.core.chat.ToolState
import com.makefunstuff.herdr.core.chat.Away
import com.makefunstuff.herdr.core.chat.ChatTurn
import com.makefunstuff.herdr.core.chat.ChatTurns
import com.makefunstuff.herdr.core.chat.EditCalls
import com.makefunstuff.herdr.core.chat.ToolLine
import com.makefunstuff.herdr.ui.code.AddedRemoved
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.makefunstuff.herdr.core.host.Images
import com.makefunstuff.herdr.ui.images.MessageImages
import com.makefunstuff.herdr.ui.common.CodeBlock
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.design.BubbleShape
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.KeyChip
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Radius
import com.makefunstuff.herdr.ui.theme.Space

/** What the chat list shows: transcript items cut into turns, each turn's work folded under one header. */
sealed interface ChatRow {
    val key: String

    /** What you said; a voice message ([VoiceNotes.mentioned]) shows as one. */
    data class User(val item: ChatItem.User, val pending: Boolean = false) : ChatRow {
        override val key get() = if (pending) "pending/${item.id}" else item.id
    }
    /** A voice message of yours still on the phone: on its way to the agent, or not sent. */
    data class Voice(val note: VoiceNote) : ChatRow { override val key get() = "voice/${note.id}" }
    /** The agent's words: its answer, or ([quiet]) narration inside a settled turn's work. */
    data class Agent(val item: ChatItem.Agent, val quiet: Boolean = false) : ChatRow { override val key get() = item.id }
    data class Tools(val tools: List<ChatItem.Tool>, val lines: Map<String, ToolLine> = emptyMap()) : ChatRow {
        override val key get() = "tools/" + tools.first().id
    }
    data class Notice(val item: ChatItem.Notice) : ChatRow { override val key get() = item.id }
    data class Screen(val text: String) : ChatRow { override val key get() = "screen" }
    data class Info(override val key: String, val text: String) : ChatRow
    /** When a turn started: `10:05`, or `Oct 16, 09:12` on another day. */
    data class Time(override val key: String, val text: String) : ChatRow
    /** A turn's work header: "Worked for 2m 36s · 1 edit · 1 command", tapped to fold or open; under [time], when the turn started. */
    data class Head(val turn: String, val title: String, val summary: String, val failed: Int, val live: Live?, val open: Boolean, val time: String? = null) : ChatRow {
        override val key get() = "head/$turn"
    }

    /**
     * The agent's reasoning, folded to its first line and its length; [open], it follows as
     * [ThoughtPart] rows, a piece each, so a long one scrolls as smoothly as the rest. [live] while
     * it is the newest thing in the running turn: the agent may still be at it. [partial]: what the
     * agent's screen shows of a thought it is still writing, not yet in its transcript.
     */
    data class Thought(val item: ChatItem.Reasoning, val open: Boolean, val live: Boolean, val partial: Boolean = false) : ChatRow {
        override val key get() = "thought/${item.id}"
    }
    data class ThoughtPart(override val key: String, val text: String, val last: Boolean) : ChatRow
    /** "Thinking…" at the end of the running turn: its call came back and nothing new is written yet. */
    data class Thinking(override val key: String) : ChatRow

    /** What a turn still running does: works, or waits for you. */
    enum class Live { Working, Blocked }

    /** A row that counts as a new message while the reader is scrolled up. */
    val isMessage: Boolean get() = (this is User && !pending) || (this is Agent && !quiet)
}

/**
 * The rows of [turns]: what you said, when the turn started, its work under a header (open while it
 * runs, folded once it settles unless [opened] says otherwise) and its answer outside the fold.
 * [live] is the newest turn's state while it runs; [now] times it; [time] says when a turn started.
 * [thinking] is what the agent's screen shows it thinking now, not yet in its transcript.
 */
fun chatRows(
    turns: List<ChatTurn>,
    steps: ChatTurns.Steps,
    live: ChatRow.Live?,
    opened: Map<String, Boolean>,
    now: Long,
    thinking: String? = null,
    time: (Long) -> String?,
): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    turns.forEachIndexed { i, turn ->
        turn.user?.let { rows += ChatRow.User(it) }
        val started = turn.at.takeIf { it > 0 }?.let(time)
        // Working only on the newest turn, and not one that settled on its answer a while ago.
        val running = live.takeIf { i == turns.lastIndex && (it == ChatRow.Live.Blocked || ChatTurns.mayBeRunning(turn, now)) }
        if (turn.hasWork) {
            val open = opened[turn.key] ?: (running != null || turn.keepsOpen)
            val title = when {
                running == ChatRow.Live.Blocked -> "Needs you"
                running != null -> if (turn.at > 0 && now > turn.at) "Working for ${ChatTurns.duration(now - turn.at)}" else "Working…"
                else -> turn.took?.let { "Worked for ${ChatTurns.duration(it)}" } ?: "Worked"
            }
            rows += ChatRow.Head(turn.key, title, turn.counts.summary, turn.failed, running, open, started)
            if (open) rows += workRows(turn.work, steps, quiet = running == null, opened)
        } else {
            started?.let { rows += ChatRow.Time("time/${turn.key}", it) }
            rows += workRows(turn.work, steps, quiet = false, opened)
        }
        // While it works, the thought its screen shows streaming, or else its newest thought, is live.
        val working = running == ChatRow.Live.Working
        val streaming = thinking?.takeIf { working }
        val newest = (turn.answer.lastOrNull() ?: turn.work.lastOrNull()).takeIf { working && streaming == null }
        turn.answer.forEach { item ->
            when (item) {
                is ChatItem.Reasoning -> rows += thoughtRows(item, opened, live = item === newest)
                is ChatItem.Agent -> rows += ChatRow.Agent(item)
                else -> {}
            }
        }
        when {
            streaming != null -> rows += thoughtRows(ChatItem.Reasoning("live/${turn.key}", streaming), opened, live = true, partial = true)
            // A call came back and nothing new is written: the model is at work. Not after its words,
            // which may be its answer while a hook still runs.
            newest is ChatItem.Tool && newest.state != ToolState.Running ->
                rows += ChatRow.Thinking("thinking/${turn.key}")
        }
    }
    return rows
}

/** A thought's row, and its pieces when opened by hand ([opened] by its row's key). */
private fun thoughtRows(item: ChatItem.Reasoning, opened: Map<String, Boolean>, live: Boolean = false, partial: Boolean = false): List<ChatRow> {
    val head = ChatRow.Thought(item, open = false, live = live, partial = partial)
    if (opened[head.key] != true) return listOf(head)
    val parts = com.makefunstuff.herdr.core.chat.Thinking.chunks(item.text)
    return listOf(head.copy(open = true)) + parts.mapIndexed { i, text -> ChatRow.ThoughtPart("${head.key}/$i", text, last = i == parts.lastIndex) }
}

/** A turn's work as rows: runs of tool calls as one group, narration and markers between them. */
private fun workRows(items: List<ChatItem>, steps: ChatTurns.Steps, quiet: Boolean, opened: Map<String, Boolean>): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    val run = mutableListOf<ChatItem.Tool>()
    fun flush() {
        if (run.isNotEmpty()) rows += ChatRow.Tools(run.toList(), run.associate { it.id to ChatTurns.line(it, steps) })
        run.clear()
    }
    for (item in items) {
        when (item) {
            is ChatItem.Tool -> run += item
            is ChatItem.Agent -> { flush(); rows += ChatRow.Agent(item, quiet) }
            is ChatItem.Notice -> { flush(); rows += ChatRow.Notice(item) }
            is ChatItem.Reasoning -> { flush(); rows += thoughtRows(item, opened) }
            is ChatItem.User -> { flush(); rows += ChatRow.User(item) }
        }
    }
    flush()
    return rows
}

@Composable
fun ChatRowView(row: ChatRow, onToggle: (ChatRow.Head) -> Unit = {}, onThought: (ChatRow.Thought) -> Unit = {}) {
    when (row) {
        is ChatRow.User -> {
            val path = VoiceNotes.mentioned(row.item.text)
            if (path != null) VoiceMessage(path, VoiceNotes.millis(path) ?: 0, you = true, note = LocalVoiceMessages.current?.note(path))
            else UserBubble(row.item, row.pending)
        }
        is ChatRow.Voice -> VoiceMessage(row.note.remote, row.note.millis, you = true, note = row.note)
        is ChatRow.Agent ->
            // Narration in a settled turn is a quiet row again, under the answer's voice.
            if (row.quiet) MarkdownText(row.item.text, Modifier.fillMaxWidth().padding(horizontal = Space.xs), style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted)
            else MarkdownText(row.item.text, Modifier.fillMaxWidth().padding(end = Space.s), style = MaterialTheme.typography.bodyLarge)
        is ChatRow.Tools -> ToolGroup(row.tools, row.lines)
        is ChatRow.Notice -> NoticeLine(row.item.text)
        is ChatRow.Screen -> SelectionContainer { CodeBlock(row.text) }
        is ChatRow.Info -> NoticeLine(row.text)
        is ChatRow.Time -> TimeLine(row.text)
        is ChatRow.Head -> TurnHead(row) { onToggle(row) }
        is ChatRow.Thought -> ThoughtHead(row) { onThought(row) }
        is ChatRow.ThoughtPart -> ThoughtText(row)
        is ChatRow.Thinking -> ThinkingLine()
    }
}

/** A pulse for what runs now, as the working dot breathes. */
@Composable
private fun pulse(): Float {
    val pulse = rememberInfiniteTransition(label = "thinking")
    val alpha by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha")
    return alpha
}

/** The start of a thought's own column: its rows hang off a hairline there, as a quote does. */
private val THOUGHT_INDENT = 22.dp

/**
 * A thought folded to one quiet row: `Thought · 312 words` over its first line in faint italics,
 * tapped to read it all. Live, it says `Thinking…` and shows its last lines instead.
 */
@Composable
private fun ThoughtHead(row: ChatRow.Thought, onToggle: () -> Unit) {
    val c = Herdr.colors
    val text = row.item.text
    val size = remember(text) { com.makefunstuff.herdr.core.chat.Thinking.size(text) }
    val preview = remember(text, row.live) {
        if (row.live) com.makefunstuff.herdr.core.chat.Thinking.tail(text, 3) else com.makefunstuff.herdr.core.chat.Thinking.preview(text)
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.m))
            .clickable(onClickLabel = if (row.open) "Fold the reasoning" else "Read the reasoning", onClick = onToggle)
            .padding(horizontal = Space.xs, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                Icon(HerdrIcons.Brain, null, Modifier.size(14.dp).alpha(if (row.live) pulse() else 1f), tint = if (row.live) c.working else c.faint)
            }
            Spacer(Modifier.width(6.dp))
            Text(
                if (row.live) "Thinking…" else "Thought",
                style = MaterialTheme.typography.labelLarge,
                color = if (row.live) c.working else c.muted,
                maxLines = 1,
            )
            // Only part of a thought still streaming is on screen: its length isn't known yet.
            if (!row.partial) Text(" · $size", style = MaterialTheme.typography.labelMedium, color = c.faint, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Icon(if (row.open) HerdrIcons.ChevronDown else HerdrIcons.ChevronRight, null, Modifier.size(14.dp), tint = c.faint)
        }
        if (!row.open && preview.isNotEmpty()) {
            Text(
                preview,
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                color = c.faint,
                maxLines = if (row.live) 3 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = THOUGHT_INDENT, top = 2.dp),
            )
        }
    }
}

/** A piece of an opened thought: quiet, small, off the hairline that joins its pieces. */
@Composable
private fun ThoughtText(row: ChatRow.ThoughtPart) {
    val line = Herdr.colors.line
    MarkdownText(
        row.text,
        Modifier.fillMaxWidth()
            .drawBehind {
                // Up through the list's gap above, so the pieces read as one column.
                val x = (Space.xs + 8.dp).toPx()
                drawLine(line, androidx.compose.ui.geometry.Offset(x, -Space.m.toPx()), androidx.compose.ui.geometry.Offset(x, size.height), 1.dp.toPx())
            }
            .padding(start = Space.xs + THOUGHT_INDENT, end = Space.s, bottom = if (row.last) Space.xs else 0.dp),
        style = MaterialTheme.typography.bodySmall,
        color = Herdr.colors.muted,
    )
}

/** "Thinking…" under the running turn while the model is at work and no call runs. */
@Composable
private fun ThinkingLine() {
    val c = Herdr.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Space.xs, vertical = 4.dp)) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            Icon(HerdrIcons.Brain, null, Modifier.size(14.dp).alpha(pulse()), tint = c.working)
        }
        Spacer(Modifier.width(6.dp))
        Text("Thinking…", style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1)
    }
}

@Composable
private fun UserBubble(item: ChatItem.User, pending: Boolean) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    // The pictures it names show as pictures, over the words.
    val split = remember(item.text) { Images.split(item.text) }
    val text = split.text
    val long = text.length > 600 || text.count { it == '\n' } > 12
    Row(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalArrangement = Arrangement.End) {
        // Your turn: a neutral raised card (`.chat-turn-user`), never a wall of colour.
        Surface(
            color = Herdr.colors.card,
            contentColor = Herdr.colors.text,
            shape = BubbleShape,
            border = if (Herdr.colors.bubbleBorder.alpha > 0f) androidx.compose.foundation.BorderStroke(1.dp, Herdr.colors.bubbleBorder) else null,
            modifier = Modifier.alpha(if (pending) 0.6f else 1f).animateContentSize(),
        ) {
            Column(Modifier.padding(horizontal = Space.l, vertical = Space.m)) {
                if (split.paths.isNotEmpty()) MessageImages(split.paths, Modifier.padding(bottom = if (text.isEmpty()) 0.dp else Space.s))
                if (text.isNotEmpty()) SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = if (long && !expanded) 10 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.images > 0) {
                    Text(
                        if (item.images == 1) "1 image attached" else "${item.images} images attached",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp).alpha(0.7f),
                    )
                }
                if (long) {
                    Text(
                        if (expanded) "Show less" else "Show more",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp).clickable { expanded = !expanded },
                    )
                }
                if (pending) Text("Sending…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun NoticeLine(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Herdr.colors.faint,
        textAlign = TextAlign.Center,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = Space.xxl, vertical = Space.xs),
    )
}

/**
 * A run of tool calls as the web UI's work block: quiet one-line rows on the prose edge (state,
 * verb, object), no card; a hairline under the run. Long runs show only their last few until opened.
 */
@Composable
private fun ToolGroup(tools: List<ChatItem.Tool>, lines: Map<String, ToolLine>) {
    var showAll by rememberSaveable(tools.first().id) { mutableStateOf(false) }
    val hidden = if (showAll || tools.size <= 4) 0 else tools.size - 3
    val line = Herdr.colors.line
    Column(
        Modifier.fillMaxWidth().animateContentSize().drawBehind {
            drawLine(line, androidx.compose.ui.geometry.Offset(0f, size.height - 0.5f), androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5f), 1.dp.toPx())
        }.padding(bottom = Space.xs),
    ) {
        if (hidden > 0) {
            Text(
                "▸ $hidden earlier actions",
                style = MaterialTheme.typography.labelLarge,
                color = Herdr.colors.muted,
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(Radius.m)).clickable { showAll = true }
                    .padding(horizontal = Space.xs, vertical = Space.s),
            )
        }
        tools.drop(hidden).forEach { ToolRow(it, lines[it.id]) }
    }
}

/** Opens a subagent's own steps ([ChatItem.Tool.subagent]); none where the chat can't. */
val LocalOpenSubagent = androidx.compose.runtime.staticCompositionLocalOf<((ChatItem.Tool) -> Unit)?> { null }

@Composable
private fun ToolRow(tool: ChatItem.Tool, line: ToolLine?) {
    var open by rememberSaveable(tool.id) { mutableStateOf(false) }
    // The object worth reading: a path from the repo (`core/…/host/HostState.kt`), or the call's own words.
    val text = line?.text ?: tool.summary.lineSequence().firstOrNull().orEmpty()
    val diffs = line?.diffs ?: tool.diffs
    // An edit's diff is all it did; a command or a script that applies a patch keeps its words and output too.
    val onlyDiff = diffs.isNotEmpty() && EditCalls.isEdit(tool.name)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.m)).clickable { open = !open }.padding(horizontal = Space.xs, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolStateIcon(tool.state)
            Spacer(Modifier.width(Space.s))
            Text(
                displayName(tool.name),
                style = MaterialTheme.typography.labelLarge,
                color = if (tool.state == ToolState.Failed) Herdr.colors.red else Herdr.colors.muted,
                maxLines = 1,
            )
            Spacer(Modifier.width(Space.s))
            Text(
                // Opened, the call's own words; an edit's diff names its files whole.
                if (open && !onlyDiff) tool.summary.lineSequence().firstOrNull().orEmpty() else text,
                style = if (line?.isPath == true || looksLikeCode(text)) CodeStyle.copy(fontSize = 12.sp, lineHeight = 18.sp) else MaterialTheme.typography.bodySmall,
                color = Herdr.colors.text,
                maxLines = if (open && !onlyDiff) 4 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            val added = line?.added
            val removed = line?.removed
            if (added != null && removed != null) {
                Spacer(Modifier.width(Space.s))
                AddedRemoved(added, removed)
            }
        }
        if (open) {
            // An edit as its diff; only a call in a shape the app doesn't know shows its raw arguments.
            val input = tool.input?.takeIf { it.trim() != tool.summary.trim() && !onlyDiff }
            if (input != null) {
                Spacer(Modifier.size(6.dp))
                SelectionContainer { CodeBlock(input, maxLines = 30) }
            }
            diffs.forEachIndexed { i, f ->
                Spacer(Modifier.size(6.dp))
                EditDiff(f, "${tool.id}/$i")
            }
            val openSubagent = LocalOpenSubagent.current
            if (tool.subagent != null && openSubagent != null) {
                Spacer(Modifier.size(6.dp))
                QuietButton(onClick = { openSubagent(tool) }) {
                    Text(if (tool.state == ToolState.Running) "Follow its steps" else "See its steps")
                }
            }
            // An edit's result only says it was made; a failed one says why.
            if (!onlyDiff || tool.state == ToolState.Failed) {
                Spacer(Modifier.size(6.dp))
                SelectionContainer {
                    CodeBlock(tool.output ?: if (tool.state == ToolState.Running) "Running…" else "(no output)", maxLines = 40)
                }
            }
        }
    }
}

@Composable
private fun ToolStateIcon(state: ToolState) {
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        when (state) {
            ToolState.Running -> CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp, color = Herdr.colors.working)
            ToolState.Done -> Icon(HerdrIcons.Check, "Done", Modifier.size(16.dp), tint = Herdr.colors.done)
            ToolState.Failed -> Icon(HerdrIcons.X, "Failed", Modifier.size(16.dp), tint = Herdr.colors.red)
        }
    }
}

private fun displayName(name: String): String = when (name.lowercase()) {
    "bash", "shell", "exec_command", "exec" -> "Run"
    "read", "view" -> "Read"
    "edit", "multiedit", "apply_patch", "str_replace_based_edit_tool" -> "Edit"
    "write" -> "Write"
    "grep", "glob", "find", "rg" -> "Search"
    "webfetch", "fetch" -> "Fetch"
    "websearch", "web search", "web.search" -> "Web"
    "task", "agent" -> "Agent"
    "todowrite", "todo", "update_plan" -> "Plan"
    "askuserquestion" -> "Ask"
    "subagenthandback" -> "Report"
    else -> name.substringAfterLast("__").substringAfterLast('.').replaceFirstChar { it.uppercase() }.take(18)
}

/** Paths and shell commands read better in monospace; descriptions don't. */
private fun looksLikeCode(text: String) = text.startsWith("/") || text.startsWith("~") || text.startsWith("./") ||
    text.contains(" && ") || text.contains(" | ") || text.contains('$')

/** "Working…" with a breathing dot, under the newest message. */
@Composable
fun WorkingLine(activity: String?) {
    val pulse = rememberInfiniteTransition(label = "working")
    val alpha by pulse.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        StatusDot(Herdr.colors.working, Modifier.alpha(alpha), size = 8)
        Spacer(Modifier.width(Space.s))
        Text(
            activity?.let { "Working · $it" } ?: "Working…",
            style = MaterialTheme.typography.bodySmall,
            color = Herdr.colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** When a turn started, quiet on the prose edge. */
@Composable
private fun TimeLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Herdr.colors.faint,
        modifier = Modifier.padding(horizontal = Space.xs).semantics { heading() },
    )
}

/**
 * A turn's work header, the web UI's `▸ Worked for 7s · 1 edit · 2 commands`: a quiet footnote once
 * the turn settled (with a hairline under it while folded), the agent's state colour behind a
 * breathing dot while it runs. The failures are their own item, never cut, as the fold hides their rows.
 */
@Composable
private fun TurnHead(head: ChatRow.Head, onToggle: () -> Unit) {
    val c = Herdr.colors
    val line = c.line
    val folded = !head.open && head.live == null
    Column {
        head.time?.let { TimeLine(it) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .then(
                    if (folded) Modifier.drawBehind {
                        drawLine(line, androidx.compose.ui.geometry.Offset(0f, size.height - 0.5f), androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5f), 1.dp.toPx())
                    } else Modifier,
                )
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(Radius.m))
                .clickable(onClickLabel = if (head.open) "Fold this turn's work" else "Show this turn's work", onClick = onToggle)
                .padding(horizontal = Space.xs, vertical = Space.s),
        ) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                when (head.live) {
                    ChatRow.Live.Working -> {
                        val pulse = rememberInfiniteTransition(label = "working")
                        val alpha by pulse.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha")
                        StatusDot(c.working, Modifier.alpha(alpha), size = 8)
                    }
                    ChatRow.Live.Blocked -> StatusDot(c.red, size = 8)
                    null -> Icon(if (head.open) HerdrIcons.ChevronDown else HerdrIcons.ChevronRight, null, Modifier.size(16.dp), tint = c.faint)
                }
            }
            Spacer(Modifier.width(Space.s))
            Text(
                head.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (head.live != null) FontWeight.Medium else FontWeight.Normal,
                color = when (head.live) {
                    ChatRow.Live.Working -> c.working
                    else -> c.muted
                },
                maxLines = 1,
            )
            if (head.summary.isNotEmpty()) {
                Text(
                    " · ${head.summary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (head.failed > 0) {
                Text(" · ${head.failed} failed", style = MaterialTheme.typography.bodySmall, color = c.red, maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * "While you were away · 25 min": what the agent did since the chat was last on screen, in a few
 * lines from its transcript. Docked over the conversation until hidden or until you write.
 */
@Composable
fun AwayCard(away: Away, onHide: () -> Unit, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    com.makefunstuff.herdr.ui.design.QuietCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = Space.l, end = Space.xs)) {
            Icon(HerdrIcons.History, null, Modifier.size(18.dp), tint = c.muted)
            Spacer(Modifier.width(Space.s))
            Text(
                "While you were away · ${away.gone}",
                style = MaterialTheme.typography.titleSmall,
                color = c.strong,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            QuietButton(onClick = onHide) { Text("Hide", color = c.accent) }
        }
        Column(Modifier.padding(start = Space.l, end = Space.l, bottom = Space.m), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            away.lines.forEach { l ->
                Row {
                    Text("•", style = MaterialTheme.typography.bodyMedium, color = c.faint, modifier = Modifier.width(14.dp))
                    Text(l, style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun KeyButton(label: String, onClick: () -> Unit) = KeyChip(label, onClick)
