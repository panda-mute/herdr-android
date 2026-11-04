package com.makefunstuff.herdr.ui.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.LocalPanes
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.home.Waiting
import com.makefunstuff.herdr.core.home.BoardGroup
import com.makefunstuff.herdr.core.home.Home
import com.makefunstuff.herdr.core.home.HomeAgent
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrFab
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.share.Ask
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.makefunstuff.herdr.core.board.WorkOverview
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.ui.common.toast
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The Board tab: every machine's work board in one list, a group per machine (what waits for you
 * first, then what is at work and what is to do), read again while it is in front. A task opens on
 * its machine's board; a group's heading opens that whole board (done and archived tasks, search).
 */
@Composable
fun BoardsScreen(nav: NavController) {
    val container = LocalContainer.current
    val panes = LocalPanes.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    // Each task's agent on its machine: its chat opens from the card.
    val agents = remember(hosts) { HomeAgent.of(hosts) }
    val boards = rememberBoards()
    // Shepherdr 3 machines: their spaces and tasks as the board sent them.
    val works = rememberWorkBoards()
    val problems = rememberBoardProblems()
    val readNow = remember { Channel<Unit>(Channel.CONFLATED) }
    ReadBoards(readNow, everyMs = 10_000)
    val withBoard = hosts.filter { it.hasWorkBoard }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    // A machine without a board (or not checked yet since it connected) shows all.
    val machine = chosen?.takeIf { id -> withBoard.any { it.machine.id == id } }
    val groups = remember(boards, works, machine) { BoardGroup.of(boards.filter { it.machineId !in works }, machine).associateBy { it.machineId } }
    // In the machines' order, each with its v1 group or its Shepherdr 3 board.
    val shown = boards.filter { machine == null || it.machineId == machine }
    // What of the Needs you list (both tabs' badge) this tab shows: tasks and coordinators, on the machine chosen;
    // the rest (agents that ask with no task) is named, so the count adds up to the badge.
    val needsYou = rememberNeedsYou(boards)
    val waiting = needsYou.count { (it !is Waiting.Question || it.task != null) && (machine == null || it.machineId == machine) }
    val elsewhere = if (machine == null) needsYou.size - waiting else 0
    // Adding a task: on the machine shown, or the one chosen first.
    var adding by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar("Board", subtitle = if (boards.isEmpty()) null else listOfNotNull(
                if (waiting > 0) "$waiting need${if (waiting == 1) "s" else ""} you" else if (elsewhere == 0) "Nothing waits for you" else null,
                elsewhere.takeIf { it > 0 }?.let { if (waiting > 0) "$it more on Agents" else "$it on Agents" },
            ).joinToString(" · ")) {
                BarIcon(HerdrIcons.Search, "Search tasks, agents and chats", { panes.open(nav, Routes.search()) })
                BarIcon(HerdrIcons.Layers, "Review finished work", { panes.open(nav, Routes.review()) })
                BarIcon(HerdrIcons.CalendarClock, "Routines", { panes.open(nav, Routes.routines(machine)) })
                BarIcon(HerdrIcons.MessagesSquare, "Ask the Coordinator", { panes.open(nav, Routes.ask(Ask.COORDINATOR)) })
            }
        },
        floatingActionButton = {
            val online = withBoard.filter { it.online }
            if (online.isNotEmpty()) HerdrFab(null, "Add a task") {
                val only = machine ?: online.singleOrNull()?.machine?.id
                if (only != null) adding = only else choosing = true
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (withBoard.size > 1) {
                LazyRow(contentPadding = PaddingValues(horizontal = Space.screen), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    item { HerdrChip("All machines", selected = machine == null, onClick = { chosen = null }) }
                    items(withBoard, key = { it.machine.id }) { h ->
                        HerdrChip(h.machine.label, selected = machine == h.machine.id, onClick = { chosen = if (machine == h.machine.id) null else h.machine.id })
                    }
                }
            }
            when {
                withBoard.isEmpty() -> EmptyState(
                    if (hosts.none { it.online }) "Not connected yet" else "No work board",
                    if (hosts.none { it.online }) "The boards show once a machine with Shepherdr or herdr-projects answers." else "None of your machines has Shepherdr or herdr-projects. Its tasks show here once one does.",
                    icon = HerdrIcons.SquareKanban,
                )
                boards.isEmpty() && problems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingState("Reading the boards") }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.xs, bottom = 96.dp),
                    // Compact rows, close together: about twice as many as cards fit a screen.
                    verticalArrangement = Arrangement.spacedBy(Space.xxs),
                ) {
                    problems.forEach { (label, p) ->
                        item(key = "problem/$label") { ErrorMessage("$label's board: $p", Modifier.padding(vertical = Space.xs), onRetry = { readNow.trySend(Unit) }) }
                    }
                    shown.forEachIndexed { i, b ->
                        works[b.machineId]?.let { w ->
                            workGroup(b.machineId, b.machineLabel, w, top = if (i == 0) Space.xs else Space.m,
                                onBoard = { panes.openBoard(nav, b.machineId) },
                                onCoordinator = { space -> panes.open(nav, Routes.coordinator(b.machineId, space)) },
                                onTask = { t -> panes.openBoard(nav, b.machineId, t.id, alone = true) })
                            return@forEachIndexed
                        }
                        val g = groups[b.machineId] ?: return@forEachIndexed
                        item(key = "h/${g.machineId}") {
                            SectionHeading(
                                listOfNotNull(
                                    g.machineLabel,
                                    g.needsYou.size.takeIf { it > 0 }?.let { "$it need${if (it == 1) "s" else ""} you" },
                                    g.working.size.takeIf { it > 0 }?.let { "$it working" },
                                ).joinToString(" · "),
                                Modifier.clickable(onClickLabel = "Open ${g.machineLabel}'s board") { panes.openBoard(nav, g.machineId) }.heightIn(min = 48.dp),
                                top = if (i == 0) Space.xs else Space.m,
                                trailing = { Icon(HerdrIcons.ChevronRight, null, tint = Herdr.colors.muted, modifier = Modifier.size(20.dp)) },
                            )
                        }
                        if (g.tasks.isEmpty()) item(key = "empty/${g.machineId}") {
                            Text("No open tasks", style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted, modifier = Modifier.padding(start = Space.xs))
                        }
                        items(g.tasks, key = { "${g.machineId}/${it.id}" }) { t ->
                            val live = Home.agentOf(agents.filter { it.machineId == g.machineId }, t)?.agent
                            TaskRow(
                                t, onOpen = { panes.openBoard(nav, g.machineId, t.id, alone = true) }, onMenu = { panes.openBoard(nav, g.machineId, t.id, alone = true) },
                                onAgent = live?.let { a -> { panes.open(nav, Routes.agent(g.machineId, a.paneId)) } },
                            )
                        }
                        if (g.done > 0) item(key = "done/${g.machineId}") {
                            Text(
                                "${g.done} done · on ${g.machineLabel}'s board",
                                style = MaterialTheme.typography.labelLarge, color = Herdr.colors.accent,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { panes.openBoard(nav, g.machineId) }.padding(horizontal = Space.xs, vertical = Space.m),
                            )
                        }
                    }
                }
            }
        }
    }

    if (choosing) {
        BoardPanel("Add a task on", onDismiss = { choosing = false }) {
            withBoard.filter { it.online }.forEach { h ->
                SecondaryButton(h.machine.label, Modifier.fillMaxWidth()) { choosing = false; adding = h.machine.id }
            }
        }
    }
    adding?.let { id ->
        container.hosts.session(id)?.let { session ->
            val repos = boards.firstOrNull { it.machineId == id }?.tasks.orEmpty().map { it.repo }.distinct().sorted()
            if (session.state.value.onShepherdr) AddWorkTask(session, works[id], onDismiss = { adding = null }) { space, title, brief ->
                adding = null
                scope.launch {
                    runCatching { session.awaitWorkBoard().addTask(space.id, title, brief) }
                        .onSuccess { context.toast(it) }.onFailure { context.toast(it.message ?: "Shepherdr refused") }
                    readNow.trySend(Unit)
                }
            } else BoardDialogs(
                pending = Pending.Add, session = session, repos = repos, onDismiss = { adding = null },
                onWords = { _, _ -> }, onConfirm = {}, onAnyway = {},
                onSaved = { adding = null; readNow.trySend(Unit) },
            )
        }
    }
}

/**
 * A Shepherdr 3 machine's part of the Board tab: what needs you, then each space's tasks at work and
 * to do, and how many are done. Its heading opens the whole board.
 */
private fun LazyListScope.workGroup(
    machineId: String, label: String, w: WorkOverview, top: Dp, onBoard: () -> Unit, onCoordinator: (String) -> Unit, onTask: (WorkTask) -> Unit,
) {
    val open = w.tasks.filter { it.status != WorkStatus.Done }
    val needs = open.count { it.status.needsYou }
    val working = open.count { it.status == WorkStatus.Work || it.status == WorkStatus.Merging }
    val done = w.tasks.size - open.size
    val many = w.spaces.size > 1
    item(key = "h/$machineId") {
        SectionHeading(
            listOfNotNull(
                label,
                (needs + w.spaces.count { it.asks }).takeIf { it > 0 }?.let { "$it need${if (it == 1) "s" else ""} you" },
                working.takeIf { it > 0 }?.let { "$it working" },
            ).joinToString(" · "),
            Modifier.clickable(onClickLabel = "Open $label's board", onClick = onBoard).heightIn(min = 48.dp),
            top = top,
            trailing = { Icon(HerdrIcons.ChevronRight, null, tint = Herdr.colors.muted, modifier = Modifier.size(20.dp)) },
        )
    }
    // What needs you first (tasks that ask, then ready ones, then coordinators that ask), then the rest.
    val asking = w.spaces.filter { it.asks }
    if (open.isEmpty() && asking.isEmpty()) item(key = "empty/$machineId") {
        Text("No open tasks", style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted, modifier = Modifier.padding(start = Space.xs))
    }
    fun rows(list: List<WorkTask>) = items(list, key = { "$machineId/${it.id}" }) { t ->
        WorkTaskRow(t, onOpen = { onTask(t) }, space = if (many) w.spaces.firstOrNull { it.id == t.space }?.name.orEmpty() else "")
    }
    rows(open.filter { it.status.needsYou })
    items(asking, key = { "asks/$machineId/${it.id}" }) { s -> CoordinatorAsksRow(s, onOpen = { onCoordinator(s.id) }) }
    rows(open.filter { !it.status.needsYou })
    if (done > 0) item(key = "done/$machineId") {
        Text(
            "$done done · on $label's board",
            style = MaterialTheme.typography.labelLarge, color = Herdr.colors.accent,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onBoard).padding(horizontal = Space.xs, vertical = Space.m),
        )
    }
}
