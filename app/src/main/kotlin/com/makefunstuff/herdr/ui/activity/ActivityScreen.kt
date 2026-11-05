package com.makefunstuff.herdr.ui.activity

import androidx.compose.ui.graphics.RectangleShape

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.core.activity.ActivityFeed
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.activity.Durations
import com.makefunstuff.herdr.core.activity.Entry
import com.makefunstuff.herdr.core.activity.Origin
import com.makefunstuff.herdr.core.activity.Role
import com.makefunstuff.herdr.core.activity.Step
import com.makefunstuff.herdr.core.activity.StepFilter
import com.makefunstuff.herdr.core.activity.StepKind
import com.makefunstuff.herdr.core.activity.StepState
import com.makefunstuff.herdr.core.activity.Timeline
import com.makefunstuff.herdr.core.activity.Turn
import com.makefunstuff.herdr.core.code.Syntax
import com.makefunstuff.herdr.ui.code.AddedRemoved
import com.makefunstuff.herdr.ui.code.CodeLines
import com.makefunstuff.herdr.ui.code.diffRows
import com.makefunstuff.herdr.ui.common.CodeBlock
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.common.Pill
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.hairline
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Radius
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What an agent did, as a timeline: its turns (what started each, how long it took) with every
 * step in them (messages, commands with their exit status and time, files read and edited, tests,
 * web lookups, tool calls, the plan). Live while the agent works. The agent [paneId]'s own session.
 */
@Composable
fun ActivityScreen(nav: NavController, machineId: String, paneId: String) {
    val container = LocalContainer.current
    val session = container.hosts.session(machineId)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val scope = rememberCoroutineScope()
    val host by session.state.collectAsStateWithLifecycle()
    val feed = remember(session, paneId) { ActivityFeed(session, ActivityFeed.Target.Agent(paneId), scope) }
    DisposableEffect(feed) {
        feed.start()
        onDispose { feed.close() }
    }
    val state by feed.state.collectAsStateWithLifecycle()
    val agent = host.view?.agents?.firstOrNull { it.paneId == paneId }
    val zone = remember { container.briefWork.zone() }
    val view by container.code.view.collectAsStateWithLifecycle()

    var filter by rememberSaveable { mutableStateOf(StepFilter.All) }
    val opened = remember { mutableStateMapOf<String, Boolean>() }
    val openSteps = remember { mutableStateMapOf<String, Boolean>() }
    var diffOf by remember { mutableStateOf<Step?>(null) }
    var jumpTo by remember { mutableStateOf<String?>(null) }
    var failureAt by remember { mutableIntStateOf(-1) }
    val list = rememberLazyListState()

    val timeline = state.timeline
    val turns = remember(timeline) { timeline.turns }
    val newest = turns.lastOrNull()?.id
    fun isOpen(t: Turn) = opened[t.id] ?: (filter != StepFilter.All || turns.size <= 3 || t.id == newest || t.running)
    val rows = remember(timeline, filter, opened.toMap()) { rows(timeline, filter, ::isOpen) }
    val failures = remember(timeline) { timeline.steps.filter { it.failed }.map { it.id } }

    // A jump opens the failure's turn first, then scrolls to it once it is a row.
    LaunchedEffect(jumpTo, rows) {
        val id = jumpTo ?: return@LaunchedEffect
        val i = rows.indexOfFirst { it.stepId == id }
        if (i >= 0) {
            list.animateScrollToItem(i, scrollOffset = -48)
            jumpTo = null
        }
    }
    fun jump() {
        if (failures.isEmpty()) return
        failureAt = (failureAt + 1) % failures.size
        val id = failures[failureAt]
        // A filter that hides it (Edits, Messages) gives way, or the jump would have nowhere to go.
        timeline.steps.firstOrNull { it.id == id }?.let { if (!filter.keeps(it)) filter = StepFilter.All }
        turns.firstOrNull { t -> t.steps.any { it.id == id } }?.let { opened[it.id] = true }
        openSteps[id] = true
        jumpTo = id
    }

    // Live: stay at the newest step unless the reader scrolled up.
    val atEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 2 } ?: true } }
    val atWork = state.live && (state.working || agent?.agentStatus == AgentStatus.Working || turns.lastOrNull()?.running == true)
    LaunchedEffect(rows.size, atWork) {
        if (atWork && atEnd && rows.isNotEmpty()) list.animateScrollToItem(rows.lastIndex)
    }

    val title = agent?.let { it.name?.takeIf { n -> n.isNotBlank() } ?: it.displayName } ?: "Agent"
    BackHandler(enabled = diffOf != null) { diffOf = null }
    diffOf?.let { step ->
        DiffView(step, view) { diffOf = null }
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar(
                "Activity · $title",
                subtitle = listOfNotNull(state.title.ifEmpty { null } ?: agent?.subtitle, host.machine.label).joinToString(" · "),
                onBack = { nav.popBackStack() },
            ) {
                if (atWork) LiveMark()
                if (failures.isNotEmpty()) BarIcon(HerdrIcons.CircleAlert, "Next failure", ::jump, badge = failures.size)
                BarIcon(HerdrIcons.RefreshCw, "Read again", { feed.reload() })
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (timeline.entries.isNotEmpty()) Summary(timeline, filter, onFilter = { filter = it })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading && timeline.entries.isEmpty() -> LoadingState(
                        if (host.online) "Reading what the agent did" else "Waiting for ${host.machine.label}",
                        Modifier.align(Alignment.Center),
                    )
                    timeline.entries.isEmpty() && state.problem != null -> Box(Modifier.padding(Space.screen)) {
                        ErrorMessage(state.problem!!, onRetry = { feed.reload() })
                    }
                    timeline.entries.isEmpty() -> EmptyState(
                        "Nothing to show yet",
                        if (state.empty) "The app can't read this agent's sessions, and the board has no record of it." else "Its steps show here as soon as it takes them.",
                        icon = HerdrIcons.Activity,
                    )
                    rows.isEmpty() -> EmptyState("None of these", "Nothing in this timeline is ${filter.label.lowercase()}.", icon = HerdrIcons.ListChecks)
                    else -> LazyColumn(
                        state = list,
                        contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.s, bottom = Space.xxl + 56.dp),
                        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                    ) {
                        state.problem?.let { p -> item(key = "problem") { ErrorMessage(p, Modifier.padding(bottom = Space.m), onRetry = { feed.reload() }) } }
                        if (timeline.truncated || timeline.missing > 0) item(key = "partial") { PartialNote(timeline) }
                        items(rows, key = { it.key }) { row ->
                            when (row) {
                                is TimelineRow.Event -> EventRow(row.step, zone, open = openSteps[row.step.id] == true) {
                                    openSteps[row.step.id] = openSteps[row.step.id] != true
                                }
                                is TimelineRow.Head -> TurnHead(row.turn, row.open, zone, showDay = row.newDay) { opened[row.turn.id] = !row.open }
                                is TimelineRow.Item -> StepRow(
                                    row.step, zone, last = row.last,
                                    open = openSteps[row.step.id] == true,
                                    onClick = {
                                        if (row.step.diff != null) diffOf = row.step
                                        else openSteps[row.step.id] = openSteps[row.step.id] != true
                                    },
                                )
                            }
                        }
                        // Between steps (thinking, writing): the agent is still at work.
                        if (atWork && turns.lastOrNull()?.running != true) item(key = "working") { WorkingRow() }
                    }
                }
                if (failures.isNotEmpty() && rows.isNotEmpty()) {
                    JumpButton(if (failureAt < 0) "Jump to a failure · ${failures.size}" else "Next failure · ${failureAt + 1} of ${failures.size}", Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Space.m)) {
                        scope.launch { jump() }
                    }
                }
            }
        }
    }
}

/** What the list shows, one row each: an event, a turn's head, a step in an open turn. */
@Immutable
private sealed interface TimelineRow {
    val key: String
    val stepId: String? get() = null

    data class Event(val step: Step) : TimelineRow {
        override val key get() = "e:" + step.id
        override val stepId get() = step.id
    }

    data class Head(val turn: Turn, val open: Boolean, val newDay: Boolean) : TimelineRow {
        override val key get() = "t:" + turn.id
    }

    data class Item(val step: Step, val last: Boolean) : TimelineRow {
        override val key get() = "s:" + step.id
        override val stepId get() = step.id
    }
}

private fun rows(timeline: Timeline, filter: StepFilter, open: (Turn) -> Boolean): List<TimelineRow> = buildList {
    var day = -1L
    for (e in timeline.filtered(filter)) {
        when (e) {
            is Entry.Event -> add(TimelineRow.Event(e.step))
            is Entry.TurnEntry -> {
                val t = e.turn
                val d = if (t.at > 0) t.at / 86_400_000 else day
                val isOpen = open(t)
                add(TimelineRow.Head(t, isOpen, newDay = d != day))
                day = d
                if (isOpen) t.steps.forEachIndexed { i, s -> add(TimelineRow.Item(s, last = i == t.steps.lastIndex)) }
            }
        }
    }
}

// ---- the summary and the filters ----

@Composable
private fun Summary(timeline: Timeline, filter: StepFilter, onFilter: (StepFilter) -> Unit) {
    val c = Herdr.colors
    val turns = timeline.turns
    Column(
        Modifier.fillMaxWidth().background(c.panel).drawBehind {
            drawLine(c.line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
        },
    ) {
        val took = timeline.end - timeline.at
        Text(
            listOfNotNull(
                "${turns.size} turn${if (turns.size == 1) "" else "s"}",
                took.takeIf { timeline.at > 0 && it > 0 }?.let { Durations.short(it) },
                "${timeline.steps.count { it.kind != StepKind.Board }} steps",
                timeline.failures.takeIf { it > 0 }?.let { "$it failed" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = c.muted,
            modifier = Modifier.padding(start = Space.screen, end = Space.screen, top = Space.s),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = Space.screen),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            items(StepFilter.entries.toList(), key = { it.name }) { f ->
                val n = if (f == StepFilter.All) 0 else timeline.count(f)
                if (f == StepFilter.All || n > 0 || f == filter) HerdrChip(
                    selected = f == filter,
                    onClick = { onFilter(f) },
                    label = {
                        Text(
                            if (f == StepFilter.All) f.label else "${f.label} $n",
                            color = if (f == StepFilter.Failures && n > 0 && f != filter) c.red else androidx.compose.ui.graphics.Color.Unspecified,
                            maxLines = 1,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun PartialNote(t: Timeline) {
    Text(
        listOfNotNull(
            "Long sessions show their newest part".takeIf { t.truncated },
            "${t.missing} session${if (t.missing == 1) " the board names isn't" else "s the board names aren't"} on the machine any more".takeIf { t.missing > 0 },
        ).joinToString(" · "),
        style = MaterialTheme.typography.labelMedium,
        color = Herdr.colors.faint,
        modifier = Modifier.fillMaxWidth().padding(bottom = Space.s),
    )
}

// ---- turns ----

@Composable
private fun TurnHead(turn: Turn, open: Boolean, zone: ZoneId, showDay: Boolean, onToggle: () -> Unit) {
    val c = Herdr.colors
    var expanded by rememberSaveable(turn.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(top = Space.l, bottom = Space.xs)
            .clip(RectangleShape).background(c.card).hairline(RectangleShape, c.line)
            .clickable(onClickLabel = if (open) "Fold this turn" else "Show this turn's steps", onClick = onToggle)
            .padding(horizontal = Space.m, vertical = Space.m)
            .semantics { heading() },
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(turn.role.label, color = if (turn.role.name == Role.REVIEWER) c.idle else null)
            Spacer(Modifier.width(Space.s))
            Text(
                listOfNotNull(clock(turn.at, zone, showDay), turn.took?.takeIf { it >= 1000 }?.let { Durations.short(it) }).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = c.muted,
                modifier = Modifier.weight(1f),
            )
            Icon(if (open) HerdrIcons.ChevronUp else HerdrIcons.ChevronDown, null, tint = c.faint, modifier = Modifier.size(18.dp))
        }
        Row(verticalAlignment = Alignment.Top) {
            Icon(origin(turn.from), null, tint = c.muted, modifier = Modifier.padding(top = 2.dp).size(16.dp))
            Spacer(Modifier.width(Space.s))
            Column(Modifier.weight(1f)) {
                Text(
                    when (turn.from) {
                        Origin.You -> "You: "
                        Origin.Board -> "Board: "
                        else -> ""
                    } + turn.prompt,
                    style = MaterialTheme.typography.titleSmall,
                    color = c.strong,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Your own words in full, when there is more than the first line.
                if (turn.from == Origin.You && turn.text.length > turn.prompt.length + 4) {
                    Text(
                        if (expanded) turn.text else "Show the whole message",
                        style = if (expanded) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelMedium,
                        color = if (expanded) c.text else c.accent,
                        modifier = Modifier.padding(top = Space.xxs).clickable { expanded = !expanded },
                    )
                }
            }
        }
        Text(
            listOfNotNull(
                turn.agent.ifEmpty { null },
                "${turn.steps.size} step${if (turn.steps.size == 1) "" else "s"}",
                counts(turn).ifEmpty { null },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = c.faint,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (turn.failures > 0) Text(
            "${turn.failures} failed",
            style = MaterialTheme.typography.labelMedium,
            color = c.red,
        )
    }
}

/** "4 commands, 2 edits, 1 test": what a turn's steps were, the kinds a reader looks for. */
private fun counts(turn: Turn): String {
    val by = turn.steps.groupingBy { it.kind }.eachCount()
    return listOf(
        StepKind.Command to "command", StepKind.Test to "test", StepKind.Edit to "edit", StepKind.Read to "read", StepKind.Web to "web lookup",
    ).mapNotNull { (k, word) -> by[k]?.let { n -> "$n ${if (n == 1) word else word + if (word.endsWith("h")) "es" else "s"}" } }.joinToString(", ")
}

private fun origin(o: Origin): ImageVector = when (o) {
    Origin.You -> HerdrIcons.User
    Origin.Board -> HerdrIcons.SquareKanban
    Origin.Agent -> HerdrIcons.Bot
    Origin.System -> HerdrIcons.Bell
}

// ---- steps ----

@Composable
private fun StepRow(step: Step, zone: ZoneId, last: Boolean, open: Boolean, onClick: () -> Unit) {
    val c = Herdr.colors
    val rail = c.line
    val tint = when (step.state) {
        StepState.Failed -> c.red
        StepState.Running -> c.working
        StepState.Done -> if (step.kind == StepKind.Test) c.done else c.muted
    }
    Row(
        Modifier.fillMaxWidth().clip(RectangleShape).clickable(onClickLabel = if (step.diff != null) "Show the diff" else "Show more", onClick = onClick)
            .drawBehind {
                // The turn's rail, down the middle of the icons.
                val x = 16.dp.toPx()
                drawLine(rail, Offset(x, 0f), Offset(x, if (last) 14.dp.toPx() else size.height), 1.dp.toPx())
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(start = 6.dp).size(20.dp).background(Herdr.colors.ground, RectangleShape), contentAlignment = Alignment.Center) {
            if (step.state == StepState.Running) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp, color = c.working)
            else Icon(icon(step), step.kind.label, tint = tint, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            when (step.kind) {
                StepKind.Message -> MarkdownText(
                    if (open) step.detail ?: step.title else clipped(step.detail ?: step.title),
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> Text(
                    step.title.ifEmpty { step.tool },
                    style = if (mono(step)) CodeStyle.copy(fontSize = 13.sp, lineHeight = 19.sp) else MaterialTheme.typography.bodyMedium,
                    color = if (step.failed) c.red else c.text,
                    maxLines = if (open) 6 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Meta(step, zone)
            if (open) StepDetail(step)
        }
    }
}

/** The line under a step: what kind, how it ended, how long it took, when. */
@Composable
private fun Meta(step: Step, zone: ZoneId) {
    val c = Herdr.colors
    val bits = buildList {
        if (step.kind != StepKind.Message) add(kindWord(step) to c.faint)
        step.exit?.let { add("exit $it" to if (it == 0) c.faint else c.red) }
        if (step.state == StepState.Running) add("running" to c.working)
        step.note?.let { add(it to if (step.failed) c.red else if (step.kind == StepKind.Test) c.done else c.faint) }
        // How long a command ran; a read or an edit only when it took a while.
        val shown = if (step.kind == StepKind.Command || step.kind == StepKind.Test) 100 else 1_000
        step.took?.takeIf { it >= shown && step.kind != StepKind.Message }?.let { add(Durations.short(it) to c.faint) }
        clock(step.at, zone, false)?.let { add(it to c.faint) }
    }
    if (bits.isEmpty()) return
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            bits.forEachIndexed { i, (text, color) ->
                if (i > 0) append(" · ")
                pushStyle(androidx.compose.ui.text.SpanStyle(color = color))
                append(text)
                pop()
            }
        },
        style = MaterialTheme.typography.labelMedium,
        color = c.faint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = Space.xxs),
    )
}

@Composable
private fun StepDetail(step: Step) {
    val c = Herdr.colors
    Column(Modifier.padding(top = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        when (step.kind) {
            StepKind.Message -> {}
            StepKind.Command, StepKind.Test -> {
                step.detail?.let { SelectionContainer { CodeBlock("$ $it", maxLines = 12) } }
                SelectionContainer { CodeBlock(step.output ?: if (step.state == StepState.Running) "Running…" else "(no output)", maxLines = 40) }
            }
            StepKind.Plan, StepKind.Review, StepKind.Board, StepKind.Handover -> step.detail?.let { MarkdownText(it, style = MaterialTheme.typography.bodyMedium) }
            else -> {
                step.path?.takeIf { it != step.title }?.let { Text(it, style = CodeStyle.copy(fontSize = 12.sp), color = c.muted) }
                step.detail?.takeIf { it.trim() != step.title.trim() }?.let { SelectionContainer { CodeBlock(it, maxLines = 16) } }
                step.output?.let { SelectionContainer { CodeBlock(it, maxLines = 30) } }
                if (step.detail == null && step.output == null && step.path == null) Text("Nothing more was recorded.", style = MaterialTheme.typography.labelMedium, color = c.faint)
            }
        }
    }
}

private fun clipped(text: String): String {
    val lines = text.lines()
    val head = lines.take(4).joinToString("\n")
    return if (lines.size > 4 || head.length > 400) head.take(400).trimEnd() + " …" else head
}

private fun mono(step: Step): Boolean = when (step.kind) {
    StepKind.Command, StepKind.Test -> step.detail == null || step.title == step.detail?.lineSequence()?.firstOrNull()?.trim() || step.tool == "!" ||
        step.detail?.contains(step.title) == true
    StepKind.Read, StepKind.Edit -> step.path != null && (step.title.startsWith(step.path!!) || step.title.endsWith(step.path!!))
    else -> false
}

private fun kindWord(step: Step): String = when (step.kind) {
    StepKind.Command -> if (step.tool == "!") "your command" else "command"
    StepKind.Test -> "test"
    StepKind.Read -> if (step.tool.lowercase() in setOf("grep", "glob", "find", "search", "ls")) "searched" else "read"
    StepKind.Edit -> if (step.diff?.change == com.makefunstuff.herdr.core.code.FileChange.Added) "new file" else "edited"
    StepKind.Web -> "web"
    StepKind.Tool -> step.tool.substringAfterLast("__").ifEmpty { "tool" }
    StepKind.Plan -> "plan"
    else -> step.kind.label.lowercase()
}

private fun icon(step: Step): ImageVector = when (step.kind) {
    StepKind.Message -> HerdrIcons.MessageSquare
    StepKind.Command -> if (step.failed) HerdrIcons.CircleX else HerdrIcons.SquareTerminal
    StepKind.Test -> if (step.failed) HerdrIcons.CircleX else HerdrIcons.TestTubeDiagonal
    StepKind.Read -> if (step.tool.lowercase() in setOf("grep", "glob", "find", "search")) HerdrIcons.Search else HerdrIcons.FileText
    StepKind.Edit -> HerdrIcons.FileDiff
    StepKind.Web -> HerdrIcons.Globe
    StepKind.Tool -> HerdrIcons.Wrench
    StepKind.Plan -> HerdrIcons.ListTodo
    StepKind.Review -> if (step.failed) HerdrIcons.CircleX else HerdrIcons.ShieldCheck
    StepKind.Handover -> HerdrIcons.ArrowLeftRight
    StepKind.Board -> eventIcon(step)
    StepKind.Notice -> if (step.failed) HerdrIcons.TriangleAlert else HerdrIcons.Info
}

private fun eventIcon(step: Step): ImageVector = when {
    step.failed -> HerdrIcons.CircleAlert
    step.title.startsWith("Merged") -> HerdrIcons.GitMerge
    step.title.startsWith("Added") -> HerdrIcons.CirclePlus
    step.title.startsWith("Started") -> HerdrIcons.CirclePlay
    step.title.startsWith("Stopped") -> HerdrIcons.CircleStop
    step.title.startsWith("Queued") -> HerdrIcons.Hourglass
    step.title.startsWith("Asked") || step.title.startsWith("Needs") || step.title.startsWith("Waited") -> HerdrIcons.MessageSquare
    step.title.startsWith("Wrote the report") -> HerdrIcons.FileText
    step.title.startsWith("You told") -> HerdrIcons.User
    step.title.startsWith("Archived") -> HerdrIcons.Archive
    else -> HerdrIcons.SquareKanban
}

// ---- the board's events ----

@Composable
private fun EventRow(step: Step, zone: ZoneId, open: Boolean, onClick: () -> Unit) {
    val c = Herdr.colors
    val tone = when {
        step.failed -> c.red
        step.kind == StepKind.Review -> c.done
        step.title.startsWith("Merged") -> c.done
        else -> c.muted
    }
    val more = step.detail != null && step.kind != StepKind.Board || (step.detail != null && step.tool != "board" && step.tool != "you")
    Column(
        Modifier.fillMaxWidth().padding(top = Space.m).clip(RectangleShape)
            .then(if (more) Modifier.clickable(onClickLabel = if (open) "Hide" else "Show more", onClick = onClick) else Modifier)
            .padding(vertical = Space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(HerdrShapes.Tile).background(c.tint(tone)), contentAlignment = Alignment.Center) {
                Icon(icon(step), null, tint = tone, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(step.title, style = MaterialTheme.typography.labelLarge, color = if (step.failed) c.red else c.strong, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(step.note, step.tool.takeIf { step.kind == StepKind.Review && it.isNotEmpty() }, clock(step.at, zone, true)).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelMedium, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (more) Icon(if (open) HerdrIcons.ChevronUp else HerdrIcons.ChevronDown, null, tint = c.faint, modifier = Modifier.size(18.dp))
        }
        if (open && step.detail != null) {
            Box(Modifier.padding(start = 44.dp, top = Space.s)) {
                if (step.detail!!.startsWith("http")) Text(step.detail!!, style = MaterialTheme.typography.bodySmall, color = c.accent)
                else MarkdownText(step.detail!!, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ---- small parts ----

@Composable
private fun LiveMark() {
    val pulse = rememberInfiniteTransition(label = "live")
    val alpha by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha")
    Row(
        Modifier.padding(horizontal = Space.xs).clip(HerdrShapes.Pill).background(Herdr.colors.tint(Herdr.colors.working))
            .padding(horizontal = Space.s, vertical = 3.dp)
            .semantics { contentDescription = "Live: new steps appear as the agent takes them" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(Herdr.colors.working, Modifier.alpha(alpha), size = 7)
        Spacer(Modifier.width(5.dp))
        Text("Live", style = MaterialTheme.typography.labelMedium, color = Herdr.colors.working)
    }
}

@Composable
private fun WorkingRow() {
    Row(Modifier.padding(start = 6.dp, top = Space.s), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = Herdr.colors.working)
        Spacer(Modifier.width(Space.m))
        Text("At work…", style = MaterialTheme.typography.labelMedium, color = Herdr.colors.muted)
    }
}

@Composable
private fun JumpButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Herdr.colors
    Row(
        modifier.heightIn(min = 48.dp).clip(HerdrShapes.Pill).clickable(onClick = onClick).padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(HerdrShapes.Pill).background(c.card).hairline(HerdrShapes.Pill, c.lineStrong).padding(horizontal = Space.l, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HerdrIcons.CircleAlert, null, tint = c.red, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Space.s))
            Text(text, style = MaterialTheme.typography.labelLarge, color = c.strong)
        }
    }
}

/** An edit's diff, over the whole screen; Back closes it. */
@Composable
private fun DiffView(step: Step, view: com.makefunstuff.herdr.data.CodeView, onClose: () -> Unit) {
    val diff = step.diff ?: return
    val path = step.path ?: diff.path
    val language = remember(path) { Syntax.language(path) }
    val rows = remember(diff, language) { diffRows(diff, language) }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Herdr.colors.panel,
        topBar = {
            HerdrTopBar(path.substringAfterLast('/'), subtitle = path.substringBeforeLast('/', "").ifEmpty { null }, onBack = onClose, backLabel = "Back to the timeline") {
                AddedRemoved(diff.added, diff.removed, Modifier.padding(end = Space.m))
            }
        },
    ) { padding ->
        CodeLines(rows, language, view, chosen = null, marked = null, onChoose = {}, state = rememberLazyListState(), modifier = Modifier.padding(padding).fillMaxSize())
    }
}

/** `14:02`, or with [day] `Oct 17, 14:02`, on the phone's clock; null when unknown. */
private fun clock(at: Long, zone: ZoneId, day: Boolean): String? {
    if (at <= 0) return null
    return DateTimeFormatter.ofPattern(if (day) "MMM d, HH:mm" else "HH:mm", Locale.getDefault()).withZone(zone).format(Instant.ofEpochMilli(at))
}
