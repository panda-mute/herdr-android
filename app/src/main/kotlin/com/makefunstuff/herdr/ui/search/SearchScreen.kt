package com.makefunstuff.herdr.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.LocalPanes
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.board.Approvals
import com.makefunstuff.herdr.core.chat.ChatSearch
import com.makefunstuff.herdr.core.search.AgentHit
import com.makefunstuff.herdr.core.search.MachineHit
import com.makefunstuff.herdr.core.search.Search
import com.makefunstuff.herdr.core.search.SearchFilter
import com.makefunstuff.herdr.core.search.TaskHit
import com.makefunstuff.herdr.core.search.WorkspaceHit
import com.makefunstuff.herdr.ui.board.ReadBoards
import com.makefunstuff.herdr.ui.board.color
import com.makefunstuff.herdr.ui.board.rememberBoards
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.color
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.AgentMark
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.IconTile
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.design.Spinner
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.theme.color
import com.makefunstuff.herdr.ui.theme.title
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.statusBarsPadding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One box for agents, tasks, machines, workspaces and words in the recent chats, with chips for what
 * needs you, what works and what is done, and actions: start work about the words, or open a
 * terminal in a workspace found. Opened from Agents, Board and a machine ([machineId]: only that
 * machine's, until its chip is taken off), and with Ctrl+K from a hardware keyboard.
 */
@Composable
fun SearchScreen(nav: NavController, machineId: String? = null, initial: String = "") {
    val container = LocalContainer.current
    val panes = LocalPanes.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    // Shepherdr 3 boards too: their tasks open as their agents' chats.
    val boards = rememberBoards()
    val readNow = remember { Channel<Unit>(Channel.CONFLATED) }
    ReadBoards(readNow, everyMs = 30_000)
    var query by rememberSaveable { mutableStateOf(initial) }
    var filter by rememberSaveable { mutableStateOf(SearchFilter.All) }
    var scoped by rememberSaveable { mutableStateOf(machineId?.takeIf { id -> hosts.any { it.machine.id == id } }) }
    val scopeLabel = hosts.firstOrNull { it.machine.id == scoped }?.machine?.label
    val results = remember(query, hosts, boards, filter, scoped) { Search.of(query, hosts, boards, filter, scoped) }
    val zone = remember { container.briefWork.zone() }

    // Words in the chats: asked of the machines a moment after typing stops, each in its own time.
    val chatHits = remember { mutableStateMapOf<String, List<ChatSearch.Hit>>() }
    var looking by remember { mutableStateOf(0) }
    val online = hosts.filter { it.online && it.snapshot != null && (scoped == null || it.machine.id == scoped) }
    val chatKey = online.joinToString(",") { h -> h.machine.id + ":" + h.snapshot!!.agents.joinToString { it.paneId + it.agentSession?.value.orEmpty() } }
    LaunchedEffect(query.trim(), chatKey) {
        chatHits.clear()
        val q = query.trim()
        if (q.length < ChatSearch.MIN_QUERY) return@LaunchedEffect
        delay(350)
        coroutineScope {
            online.map { h ->
                async {
                    val chats = h.snapshot!!.agents.mapNotNull { a -> a.agentSession?.let { ChatSearch.Chat(a.paneId, it) } }
                    val command = ChatSearch.command(q, chats) ?: return@async
                    looking++
                    try {
                        val session = container.hosts.session(h.machine.id) ?: return@async
                        val out = runCatching { session.awaitConnection(10_000).run(command, timeoutSeconds = 15).stdout }.getOrNull() ?: return@async
                        val kinds = h.snapshot!!.agents.associate { it.paneId to (it.agentSession?.agent ?: it.agent.orEmpty()) }
                        chatHits[h.machine.id] = ChatSearch.parse(out, q, kinds)
                    } finally {
                        looking--
                    }
                }
            }.awaitAll()
        }
    }
    val chats = hosts.flatMap { h -> chatHits[h.machine.id].orEmpty().map { h to it } }
        .filter { (h, hit) -> filter == SearchFilter.All || h.view?.agents?.firstOrNull { it.paneId == hit.paneId }?.let { SearchFilter.of(it.agentStatus) } == filter }

    fun openAgent(h: AgentHit) = panes.openAgent(nav, h.agent.machineId, h.agent.agent, hosts.any { it.machine.id == h.agent.machineId && it.onShepherdr })
    fun openTask(t: TaskHit) {
        val v2 = hosts.firstOrNull { it.machine.id == t.machineId }?.onShepherdr == true
        if (Approvals.waits(t.task) && !v2) panes.open(nav, Routes.review(t.machineId, t.task.id)) else panes.openBoard(nav, t.machineId, t.task.id)
    }
    fun startWork(text: String) = panes.open(nav, Routes.start(scoped, text.trim().ifEmpty { null }))
    var opening by remember { mutableStateOf<String?>(null) }
    fun terminal(w: WorkspaceHit) {
        if (opening != null) return
        opening = w.machineId + "/" + w.workspace.workspaceId
        scope.launch {
            runCatching {
                val session = container.hosts.session(w.machineId) ?: error("This machine isn't in the app any more")
                session.awaitApi(10_000).openShell(w.workspace.workspaceId, "shell", w.folder).also { session.refresh() }
            }
                .onSuccess { panes.open(nav, Routes.terminal(w.machineId, it)) }
                .onFailure { context.toast(it.message ?: "Couldn't open a terminal") }
            opening = null
        }
    }
    // Enter opens the first thing found, as a hardware keyboard's user expects.
    fun first() {
        results.agents.firstOrNull()?.let { return openAgent(it) }
        results.tasks.firstOrNull()?.let { return openTask(it) }
        results.machines.firstOrNull()?.let { return nav.navigate(Routes.machine(it.machineId)) }
        results.workspaces.firstOrNull()?.let { return nav.navigate(Routes.machine(it.machineId)) }
        if (query.isNotBlank()) startWork(query)
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val c = Herdr.colors
    val showMachine = hosts.size > 1 && scoped == null
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = c.ground,
        topBar = {
            Row(
                Modifier.fillMaxWidth().background(c.panel).drawBehind {
                    drawLine(c.line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
                }.statusBarsPadding().padding(start = Space.xs, end = Space.screen, top = Space.s, bottom = Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarIcon(HerdrIcons.ArrowLeft, "Back", { nav.popBackStack() })
                HerdrField(
                    query, { query = it },
                    modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { e ->
                        when {
                            e.type != KeyEventType.KeyDown -> false
                            e.key == Key.Escape -> { nav.popBackStack(); true }
                            else -> false
                        }
                    },
                    singleLine = true,
                    placeholder = { Text(if (scopeLabel != null) "Search $scopeLabel" else "Agents, tasks, machines, chats") },
                    leadingIcon = { Icon(HerdrIcons.Search, null, Modifier.size(20.dp)) },
                    trailingIcon = if (query.isEmpty()) null else ({ BarIcon(HerdrIcons.X, "Clear", { query = "" }) }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { first() }),
                )
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).imePadding(),
            contentPadding = PaddingValues(bottom = Space.xxxl),
        ) {
            item(key = "chips") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Space.screen, vertical = Space.xs),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    if (scopeLabel != null) item(key = "scope") {
                        HerdrChip(
                            selected = true, onClick = { scoped = null },
                            label = { Text(scopeLabel, maxLines = 1) },
                            leadingIcon = { Icon(HerdrIcons.X, "Search every machine", Modifier.size(16.dp)) },
                        )
                    }
                    items(SearchFilter.entries.toList(), key = { it.name }) { f ->
                        val n = (results.counts[f] ?: 0) + if (f == SearchFilter.All) chats.size else 0
                        HerdrChip(if (n > 0) "${f.label} $n" else f.label, selected = filter == f, onClick = { filter = f })
                    }
                }
            }
            if (query.isBlank()) actions(listOf(Action("Start work…", "Say what you want, then where it runs", HerdrIcons.Plus, accent = true) { startWork("") }))
            section("Agents", results.agents, key = { "a/${it.agent.key}" }) { h -> AgentRow(h, showMachine) { openAgent(h) } }
            section("Tasks", results.tasks, key = { "t/${it.machineId}/${it.task.id}" }) { t -> TaskRow(t, showMachine) { openTask(t) } }
            section("Machines", results.machines, key = { "m/${it.machineId}" }) { m -> MachineRow(m) { nav.navigate(Routes.machine(m.machineId)) } }
            section("Workspaces", results.workspaces, key = { "w/${it.machineId}/${it.workspace.workspaceId}" }) { w ->
                WorkspaceRow(w, showMachine, opening = opening == w.machineId + "/" + w.workspace.workspaceId, onOpen = { nav.navigate(Routes.machine(w.machineId)) }, onTerminal = { terminal(w) })
            }
            section("In chats", chats, key = { (h, hit) -> "c/${h.machine.id}/${hit.paneId}/${hit.snippet.hashCode()}" }) { (h, hit) ->
                val a = h.view?.agents?.firstOrNull { it.paneId == hit.paneId }
                val ws = a?.let { h.view?.workspace(it.workspaceId)?.label }
                ListRow(
                    "“${hit.snippet}”",
                    subtitle = listOfNotNull(if (hit.you) "you" else a?.displayName, ws, h.machine.label.takeIf { showMachine }, hit.at?.let { clock(it, zone) }).joinToString(" · "),
                    leading = { IconTile(HerdrIcons.MessageSquare, size = 36.dp) },
                    titleLines = 2,
                    onClickLabel = "Open the chat",
                    onClick = { panes.openPane(nav, h, h.machine.id, hit.paneId) },
                )
            }
            if (looking > 0 && query.trim().length >= ChatSearch.MIN_QUERY) item(key = "looking") {
                Row(Modifier.padding(horizontal = Space.screen + Space.xs, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 16.dp)
                    Spacer(Modifier.width(Space.s))
                    Text("Looking in the chats", style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
            }
            if (query.isNotBlank()) {
                if (results.isEmpty && chats.isEmpty() && looking == 0) item(key = "none") {
                    Text(
                        "Nothing on ${scopeLabel ?: "the machines"} says “${query.trim()}”.",
                        style = MaterialTheme.typography.bodyMedium, color = c.muted,
                        modifier = Modifier.padding(horizontal = Space.screen + Space.xs, vertical = Space.l),
                    )
                }
                val ws = results.workspaces.firstOrNull()
                actions(
                    listOfNotNull(
                        Action("New task about “${query.trim()}”…", "Add it to a board, or start an agent now", HerdrIcons.Plus, accent = true) { startWork(query) },
                        ws?.let { w -> Action("Open a terminal in ${w.name}", w.machineLabel, HerdrIcons.SquareTerminal) { terminal(w) } },
                    ),
                )
            }
        }
    }
}

private class Action(val title: String, val subtitle: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val accent: Boolean = false, val onClick: () -> Unit)

private fun LazyListScope.actions(list: List<Action>) {
    if (list.isEmpty()) return
    item(key = "h-actions") { Heading("Actions") }
    items(list, key = { "x/${it.title}" }) { a ->
        ListRow(
            a.title, subtitle = a.subtitle,
            leading = { IconTile(a.icon, tint = if (a.accent) Herdr.colors.accent else null, size = 36.dp) },
            onClick = a.onClick,
        )
    }
}

private fun <T> LazyListScope.section(title: String, list: List<T>, key: (T) -> String, row: @Composable (T) -> Unit) {
    if (list.isEmpty()) return
    item(key = "h-$title") { Heading(title) }
    items(list, key = key) { row(it) }
}

@Composable
private fun Heading(text: String) = SectionHeading(text, Modifier.padding(horizontal = Space.screen + Space.xs), top = Space.l, color = Herdr.colors.muted)

@Composable
private fun AgentRow(h: AgentHit, showMachine: Boolean, onClick: () -> Unit) {
    val a = h.agent.agent
    val status = a.agentStatus
    ListRow(
        h.title,
        subtitle = listOfNotNull(h.task?.name, a.displayName, listOfNotNull(h.agent.machineLabel.takeIf { showMachine }, h.agent.workspace.ifEmpty { null }).joinToString(" › ").ifEmpty { null }).joinToString(" · "),
        leading = { Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { AgentMark(a.agent ?: a.displayName, size = 24.dp) } },
        trailing = if (h.state != null) ({ StatusBadge(status.title().lowercase(), status.color()) }) else null,
        onClickLabel = "Open the chat",
        onClick = onClick,
    )
}

@Composable
private fun TaskRow(t: TaskHit, showMachine: Boolean, onClick: () -> Unit) {
    val status = t.task.status
    ListRow(
        "${t.task.name} · ${t.task.title}",
        subtitle = listOfNotNull(if (showMachine) "${t.machineLabel} board" else t.task.repo, status.detail ?: t.task.word).joinToString(" · "),
        leading = { IconTile(HerdrIcons.SquareKanban, size = 36.dp) },
        trailing = if (t.task.needsYou) ({ StatusBadge(status.word, status.color()) }) else null,
        onClickLabel = "Open the task",
        onClick = onClick,
    )
}

@Composable
private fun MachineRow(m: MachineHit, onClick: () -> Unit) {
    val agents = m.host.view?.agents?.size ?: 0
    ListRow(
        m.host.machine.label,
        subtitle = listOfNotNull(m.host.status.label(), (if (agents == 1) "1 agent" else "$agents agents").takeIf { m.host.view != null }).joinToString(" · "),
        leading = { IconTile(HerdrIcons.Monitor, dot = m.host.status.color(), size = 36.dp) },
        chevron = true,
        onClickLabel = "Open the machine",
        onClick = onClick,
    )
}

@Composable
private fun WorkspaceRow(w: WorkspaceHit, showMachine: Boolean, opening: Boolean, onOpen: () -> Unit, onTerminal: () -> Unit) {
    ListRow(
        w.name,
        subtitle = listOfNotNull(w.machineLabel.takeIf { showMachine }, w.branch, w.folder?.let(::short)).joinToString(" · "),
        leading = { IconTile(if (w.workspace.worktree != null) HerdrIcons.FolderGit2 else HerdrIcons.Folder, size = 36.dp) },
        trailing = {
            if (opening) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Spinner(size = 20.dp) }
            else BarIcon(HerdrIcons.SquareTerminal, "Open a terminal in ${w.name}", onTerminal)
        },
        onClickLabel = "Open its machine",
        onClick = onOpen,
    )
}

/** A folder as the rows show it: under the home folder as `~/…`. */
private fun short(path: String): String = Regex("^/(home|Users)/[^/]+").replace(path, "~")

/** When something was said, on the phone's clock. */
private fun clock(at: Long, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).withZone(zone).format(Instant.ofEpochMilli(at))
