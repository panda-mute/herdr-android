package com.makefunstuff.herdr.ui.brief

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.LocalPanes
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.brief.BriefWindow
import com.makefunstuff.herdr.brief.Reading
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.brief.Brief
import com.makefunstuff.herdr.core.brief.BriefItem
import com.makefunstuff.herdr.core.brief.BriefMachine
import com.makefunstuff.herdr.core.brief.BriefWords
import com.makefunstuff.herdr.core.chat.Markdown
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.BottomActionBar
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.design.SegmentedPills
import com.makefunstuff.herdr.ui.design.Spinner
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.board.Progress
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.theme.tone
import java.time.ZoneId

/**
 * The brief: what the agents and the board did across the machines since you last looked, and what
 * waits for you now, with Read aloud for the car. Each line opens its task on the board or its
 * agent's chat. While it is read, the line being read is marked and the bar at the bottom has Next
 * and Stop.
 */
@Composable
fun BriefScreen(nav: NavController) {
    val container = LocalContainer.current
    val panes = LocalPanes.current
    val work = container.briefWork
    val zone = remember { work.zone() }
    val view by work.view.collectAsStateWithLifecycle()
    val reading by work.voice.reading.collectAsStateWithLifecycle()
    val machines by container.machines.machines.collectAsStateWithLifecycle()
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { work.refresh() }

    val brief = view.brief
    val c = Herdr.colors
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = c.ground,
        topBar = {
            HerdrTopBar(
                "Brief",
                subtitle = brief?.let { BriefWords.since(it, zone) } ?: "What happened while you were away",
                onBack = { nav.popBackStack() },
            ) {
                if (view.loading) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Spinner(size = 20.dp) }
                else BarIcon(HerdrIcons.RefreshCw, "Read the machines again", { work.refresh() })
                BarIcon(HerdrIcons.CalendarClock, "When the brief comes", { nav.navigate(Routes.BRIEF_SETTINGS) })
            }
        },
        bottomBar = { reading?.let { ReadingBar(it, onNext = work.voice::next, onStop = work.voice::stop) } },
    ) { padding ->
        when {
            machines.isEmpty() -> EmptyState(
                "No machines yet",
                "Add a machine that runs herdr. The brief tells what its agents and its board did while you were away.",
                icon = HerdrIcons.Newspaper,
            )
            brief == null -> LoadingState("Reading your machines", Modifier.padding(padding))
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.m, bottom = Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                item(key = "window") {
                    SegmentedPills(
                        BriefWindow.entries.map { it.label },
                        view.window.ordinal,
                        onSelect = { work.refresh(BriefWindow.entries[it]) },
                    )
                }
                item(key = "head") {
                    Head(brief, zone, reading?.briefAt == brief.at, onRead = { work.read(brief) }, onStop = work.voice::stop)
                }
                fun section(title: String, items: List<BriefItem>) {
                    if (items.isEmpty()) return
                    item(key = "h:$title") {
                        SectionHeading("$title · ${items.size}", Modifier.padding(horizontal = Space.xs), top = Space.m)
                    }
                    items(items, key = { "$title:${it.ref}" }) { i ->
                        ItemRow(i, brief, zone, showMachine = brief.reached.size > 1, reading = reading?.line?.ref == i.ref) {
                            if (i.isTask) panes.openBoard(nav, i.machineId, i.taskId) else panes.openPane(nav, hosts.firstOrNull { it.machine.id == i.machineId }, i.machineId, i.paneId)
                        }
                    }
                }
                section("Waits for you", brief.waiting)
                section("Done", brief.done)
                section("Your agents finished", brief.finished)
                section("At work", brief.working)
                item(key = "machines") { Machines(brief, zone) }
            }
        }
    }
}

/** The brief's title, its one line, and Read aloud. */
@Composable
private fun Head(brief: Brief, zone: ZoneId, reading: Boolean, onRead: () -> Unit, onStop: () -> Unit) {
    val c = Herdr.colors
    QuietCard(padded = true) {
        Text(BriefWords.title(brief.at, zone), style = MaterialTheme.typography.headlineSmall, color = c.strong, modifier = Modifier.semantics { heading() })
        Text(
            "${BriefWords.since(brief, zone)} to ${BriefWords.clock(brief.at, zone)} · " +
                brief.reached.size.let { if (it == 1) "1 machine" else "$it machines" },
            style = MaterialTheme.typography.bodySmall,
            color = c.muted,
        )
        Text(
            if (brief.quiet && brief.working.isEmpty()) "Nothing new happened, and nothing waits for you." else BriefWords.headline(brief),
            style = MaterialTheme.typography.titleMedium,
            color = c.text,
            modifier = Modifier.padding(top = Space.m),
        )
        Spacer(Modifier.height(Space.m))
        if (reading) {
            SecondaryButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Icon(HerdrIcons.Square, null, Modifier.size(16.dp)); Text("Stop reading")
            }
        } else {
            PrimaryButton(onClick = onRead, modifier = Modifier.fillMaxWidth()) {
                Icon(HerdrIcons.Volume2, null, Modifier.size(18.dp)); Text("Read aloud")
            }
        }
    }
}

/** The colour of an item: the board's for a task, the agent states' for an agent. */
@Composable
private fun BriefItem.color(): Color {
    val c = Herdr.colors
    return when (kind) {
        BriefItem.Kind.Waiting -> WorkStatus.entries.firstOrNull { it.name == board }?.let { c.tone(it.tone) } ?: c.red
        BriefItem.Kind.Merged, BriefItem.Kind.Done, BriefItem.Kind.Finished -> c.done
        BriefItem.Kind.Working -> c.working
    }
}

/**
 * One line of the brief, as the board draws a task: a stripe in its colour (wider when it needs you),
 * its key or agent with its status and when, the title, what it says.
 */
@Composable
private fun ItemRow(i: BriefItem, brief: Brief, zone: ZoneId, showMachine: Boolean, reading: Boolean, onOpen: () -> Unit) {
    val c = Herdr.colors
    val color = i.color()
    val needsYou = i.kind == BriefItem.Kind.Waiting
    // A line that only says what the badge says ("Merged") is left out.
    val detail = remember(i.detail, i.status) { Markdown.plain(i.detail).takeUnless { it.equals(i.status, ignoreCase = true) }.orEmpty() }
    val time = when {
        i.at > 0 -> BriefWords.time(i.at, brief.at, zone)
        needsYou && !i.new -> "earlier"
        else -> ""
    }
    val meta = listOfNotNull(if (showMachine) i.machineLabel else null, time.ifEmpty { null }).joinToString(" · ")
    QuietCard(
        Modifier.semantics(mergeDescendants = true) {
            selected = reading
            contentDescription = listOf(i.name, i.status, i.title, detail, meta).filter { it.isNotBlank() }.joinToString(", ")
        },
        onClick = onOpen,
        onClickLabel = if (i.isTask) "Open the task" else "Open the chat",
        // Being read aloud: the chosen row's neutral fill (DESIGN.md: selection is never amber).
        color = if (reading) c.hover else c.card,
        edge = if (reading) c.lineStrong else c.line,
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier.padding(start = Space.s, top = Space.m, bottom = Space.m).width(if (needsYou) 4.dp else 3.dp).fillMaxHeight()
                    .clip(HerdrShapes.Pill).background(color),
            )
            Column(Modifier.padding(start = Space.m, end = Space.l, top = Space.m, bottom = Space.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        i.name,
                        style = if (i.isTask) CodeStyle.copy(fontSize = 12.sp) else MaterialTheme.typography.labelLarge,
                        color = c.strong, maxLines = 1,
                    )
                    Spacer(Modifier.width(Space.s))
                    // A task's badge in the one word for its state, as on the boards.
                    StatusBadge(WorkStatus.entries.firstOrNull { it.name == i.board }?.word ?: i.status, color)
                    Spacer(Modifier.weight(1f))
                    if (meta.isNotEmpty()) Text(
                        meta, style = MaterialTheme.typography.labelMedium, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = Space.s),
                    )
                }
                Text(
                    i.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (i.kind == BriefItem.Kind.Working) c.text else c.strong,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Space.s),
                )
                if (detail.isNotEmpty()) {
                    Text(
                        detail, style = MaterialTheme.typography.bodyMedium, color = if (i.kind == BriefItem.Kind.Waiting && !i.isTask) c.red else c.muted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                i.progress?.let { p -> Progress(p, color, Modifier.fillMaxWidth().padding(top = Space.s)) }
            }
        }
    }
}

/** Each machine and how it came into the brief: everything its journal kept, compared with what you saw, or not reached. */
@Composable
private fun Machines(brief: Brief, zone: ZoneId) {
    val c = Herdr.colors
    Column(Modifier.padding(horizontal = Space.xs), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        SectionHeading("Machines", top = Space.m)
        brief.machines.forEach { m ->
            val note = BriefWords.coverage(m, brief, zone)
            val color = when (m.coverage) {
                BriefMachine.Coverage.Unreached -> c.red
                BriefMachine.Coverage.Journal -> c.done
                else -> c.notice
            }
            Row(verticalAlignment = Alignment.Top) {
                StatusDot(color, Modifier.padding(top = 7.dp), size = 7)
                Column(Modifier.padding(start = Space.m)) {
                    Text(m.label, style = MaterialTheme.typography.titleSmall, color = c.strong)
                    Text(note ?: "Everything herdr-push kept since then", style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
            }
        }
    }
}

/** While the brief is read aloud: which sentence, and Next and Stop, big enough to reach in a car. */
@Composable
private fun ReadingBar(r: Reading, onNext: () -> Unit, onStop: () -> Unit) {
    val c = Herdr.colors
    BottomActionBar {
        Icon(HerdrIcons.Volume2, null, tint = c.accent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text("Reading ${minOf(r.index + 1, r.lines.size)} of ${r.lines.size}", style = MaterialTheme.typography.labelMedium, color = c.muted)
            Text(r.line?.text.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = c.strong, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        BarIcon(HerdrIcons.SkipForward, "Next", onNext)
        BarIcon(HerdrIcons.Square, "Stop reading", onStop)
    }
}
