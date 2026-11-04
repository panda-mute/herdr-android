package com.makefunstuff.herdr.ui.machines

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import com.makefunstuff.herdr.ui.design.SectionHeading
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.LocalPanes
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.ui.worktrees.workspaceFolder
import com.makefunstuff.herdr.ui.code.CodeSpot
import com.makefunstuff.herdr.ui.quick.QuickPlace
import com.makefunstuff.herdr.ui.quick.QuickSheets
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.PaneInfo
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.api.TabInfo
import com.makefunstuff.herdr.core.api.TabLayout
import com.makefunstuff.herdr.core.api.WorkspaceInfo
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.design.HerdrFab
import com.makefunstuff.herdr.ui.design.RowDivider
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.detail
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.AgentMark
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.manage.NoticeRow
import com.makefunstuff.herdr.ui.manage.NoticesSheet
import com.makefunstuff.herdr.ui.manage.NotifySheet
import com.makefunstuff.herdr.ui.manage.PaneActionsSheet
import com.makefunstuff.herdr.ui.manage.TabActionsSheet
import com.makefunstuff.herdr.ui.manage.WorkspaceActionsSheet
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.statusWord
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.theme.color
import com.makefunstuff.herdr.ui.theme.status
import com.makefunstuff.herdr.ui.worktrees.CreateWorktreeSheet
import com.makefunstuff.herdr.ui.worktrees.RemoveRequest
import com.makefunstuff.herdr.ui.worktrees.RemoveWorktreeFlow
import com.makefunstuff.herdr.ui.worktrees.RepoTarget
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachineScreen(nav: NavController, machineId: String) {
    val container = LocalContainer.current
    val panes = LocalPanes.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = container.hosts.session(machineId)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val state by session.state.collectAsStateWithLifecycle()
    val answeredAt by session.answeredAt.collectAsStateWithLifecycle()
    // Classic herdr's workspaces; not its last ones while the machine is online for herdr-core alone.
    val snap = state.snapshot?.takeIf { state.classicMissing == null }
    // The desktop app's spaces, tabs and terminals (herdr-core), shown after classic herdr's workspaces.
    val core = state.herdrCore
    val list = rememberLazyListState()
    // New hides while the list moves down (it would cover a pane's terminal button), and is back on the
    // way up and at the end, where the list leaves room for it under the last pane.
    var showNew by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        var last = 0 to 0
        snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.canScrollForward) }.collect { (i, o, more) ->
            val at = i to o
            if (compareValuesBy(at, last, { it.first }, { it.second }) > 0) showNew = false
            if (compareValuesBy(at, last, { it.first }, { it.second }) < 0 || !more) showNew = true
            last = at
        }
    }
    var menu by remember { mutableStateOf(false) }
    var newSheet by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf<QuickPlace?>(null) }
    var importing by remember { mutableStateOf<List<ImportCandidate>?>(null) }
    var workspaceMenu by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var tabMenu by remember { mutableStateOf<TabInfo?>(null) }
    var paneMenu by remember { mutableStateOf<PaneInfo?>(null) }
    var showNotices by remember { mutableStateOf(false) }
    var showNotify by remember { mutableStateOf(false) }
    val selectedTab = remember { mutableStateMapOf<String, String>() }

    var newWorktreeFor by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var removing by remember { mutableStateOf<RemoveRequest?>(null) }

    fun openPane(pane: PaneInfo) {
        val hasAgent = state.view?.agents?.any { it.paneId == pane.paneId } == true
        nav.navigate(if (hasAgent) Routes.agent(machineId, pane.paneId) else Routes.terminal(machineId, pane.paneId))
    }

    Scaffold(
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar(
                state.machine.label,
                subtitle = barLine(state.status.label(), state.machine.session, answeredAt, rememberSeconds()),
                onBack = { nav.popBackStack() },
            ) {
                if (state.notices.isNotEmpty()) {
                    BarIcon(HerdrIcons.Bell, "Notifications", { showNotices = true; session.markNoticesRead() }, badge = state.unreadNotices)
                }
                BarIcon(HerdrIcons.Search, "Search ${state.machine.label}", { nav.navigate(Routes.search(machineId)) })
                if (state.classic) BarIcon(HerdrIcons.Zap, "Quick commands", { quick = QuickPlace(machineId, label = state.machine.label) })
                BarIcon(HerdrIcons.RefreshCw, "Refresh", { session.retry(); session.refresh(); scope.launch { session.checkBoard() } })
                BarIcon(HerdrIcons.Ellipsis, "More", { menu = true })
                HerdrMenu(expanded = menu, onDismissRequest = { menu = false }, title = state.machine.label) {
                    MenuRow("Notify herdr…", HerdrIcons.BellRing, { menu = false; showNotify = true })
                    MenuRow("Plugins", HerdrIcons.Puzzle, { menu = false; nav.navigate(Routes.admin(machineId, 0)) })
                    MenuRow("Integrations", HerdrIcons.Plug, { menu = false; nav.navigate(Routes.admin(machineId, 1)) })
                    MenuRow("Server and updates", HerdrIcons.CloudDownload, { menu = false; nav.navigate(Routes.admin(machineId, 2)) })
                    MenuRow("herdr's saved machines", HerdrIcons.Network, { menu = false; nav.navigate(Routes.admin(machineId, 3)) })
                    RowDivider(inset = Space.l)
                    MenuRow("Import herdr machines from here", HerdrIcons.Download, {
                        menu = false
                        scope.launch {
                            runCatching { MachineImport.discover(session, container.machines.machines.value) }
                                .onSuccess {
                                    if (it.isEmpty()) context.toast("No saved herdr machines on ${state.machine.label}") else importing = it
                                }
                                .onFailure { context.toast(it.message ?: "Couldn't read machines") }
                        }
                    })
                    MenuRow("Code", HerdrIcons.Code, { menu = false; nav.navigate(Routes.codeRepos(machineId)) })
                    MenuRow("Quick command runs", HerdrIcons.History, { menu = false; nav.navigate(Routes.quickRuns(machineId)) })
                    MenuRow("Worktrees", HerdrIcons.FolderGit2, { menu = false; nav.navigate(Routes.worktrees(machineId)) })
                    MenuRow("Health and cleanup", HerdrIcons.Activity, { menu = false; nav.navigate(Routes.health(machineId)) })
                    MenuRow("Usage and limits", HerdrIcons.Gauge, { menu = false; nav.navigate(Routes.usage(machineId)) })
                    MenuRow("Agent logins", HerdrIcons.KeyRound, { menu = false; nav.navigate(Routes.logins(machineId)) })
                    MenuRow("Edit machine", HerdrIcons.Pencil, { menu = false; nav.navigate(Routes.edit(machineId)) })
                }
            }
        },
        floatingActionButton = {
            // New workspaces, tabs and agents are classic herdr's: herdr-core's spaces are the desktop app's own.
            AnimatedVisibility(state.classic && showNew, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
                HerdrFab("New") { newSheet = true }
            }
        },
    ) { padding ->
        if (snap == null && core == null) {
            EmptyState(state.status.label(), state.status.detail() ?: "Waiting for ${state.machine.label}…", icon = HerdrIcons.Monitor)
            return@Scaffold
        }
        LazyColumn(
            state = list,
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.xs, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            if (!state.online) {
                item { Notice("Offline · showing the last known state", color = Herdr.colors.notice, icon = HerdrIcons.Clock) }
            }
            if (state.hasBoard || state.online) item(key = "places") {
                Column(Modifier.padding(top = Space.s)) {
                    MachinePlaces(
                        session,
                        onBoard = { panes.openBoard(nav, machineId) },
                        onCoordinator = { nav.navigate(Routes.coordinator(machineId)) },
                        onHealth = { nav.navigate(Routes.health(machineId)) },
                        onUsage = { nav.navigate(Routes.usage(machineId)) },
                        onRoutines = { nav.navigate(Routes.routines(machineId)) },
                        onLogins = { nav.navigate(Routes.logins(machineId)) },
                    )
                }
            }
            if (snap != null) item(key = "workspaces") {
                SectionHeading(if (snap.workspaces.isEmpty()) "Workspaces" else "Workspaces · ${snap.workspaces.size}", top = Space.m)
            }
            if (snap != null && snap.workspaces.isEmpty()) {
                item { Text("No workspaces yet. Tap New to create one.", color = Herdr.colors.muted, style = MaterialTheme.typography.bodyMedium) }
            }
            if (snap != null) items(snap.workspaces, key = { it.workspaceId }) { ws ->
                WorkspaceCard(
                    ws,
                    snap,
                    meta = state.workspaceMeta[ws.workspaceId].orEmpty(),
                    selectedTabId = selectedTab[ws.workspaceId] ?: ws.activeTabId,
                    onSelectTab = { selectedTab[ws.workspaceId] = it },
                    onOpenPane = ::openPane,
                    onTerminal = { nav.navigate(Routes.terminal(machineId, it.paneId)) },
                    online = state.online,
                    onWorktrees = { nav.navigate(Routes.worktrees(machineId, ws.workspaceId)) },
                    onNewWorktree = { newWorktreeFor = ws },
                    onRemoveWorktree = {
                        val wt = ws.worktree ?: return@WorkspaceCard
                        removing = RemoveRequest(ws.label.ifBlank { wt.checkoutPath.substringAfterLast('/') }, wt.checkoutPath, ws.workspaceId, RepoTarget(ws.workspaceId, null))
                    },
                    onWorkspaceMenu = { workspaceMenu = ws },
                    onTabMenu = { tabMenu = it },
                    onPaneMenu = { paneMenu = it },
                )
            }
            if (core != null) {
                val spaces = core.snapshot.workspaces
                item(key = "herdr-core") {
                    // Its other sessions too (Shepherdr's): then it is herdr-core's, not the desktop app's alone.
                    SectionHeading((if (core.sessions.isEmpty()) "Desktop app" else "herdr-core") + if (spaces.isEmpty()) "" else " · ${spaces.size}", top = Space.m)
                    Text(
                        if (core.sessions.isEmpty()) "herdr-core ${core.version}: its spaces, tabs and terminals"
                        else "herdr-core ${core.version}, sessions ${core.sessions.joinToString()}: their spaces, tabs and terminals",
                        style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted, modifier = Modifier.padding(bottom = Space.xs),
                    )
                }
                if (spaces.isEmpty()) {
                    item(key = "herdr-core-empty") { Text("No terminals open in the desktop app.", color = Herdr.colors.muted, style = MaterialTheme.typography.bodyMedium) }
                }
                items(spaces, key = { it.workspaceId }) { ws ->
                    // herdr-core has no worktrees, layouts or actions on its spaces: the panes open, nothing more.
                    WorkspaceCard(
                        ws,
                        core.snapshot,
                        meta = emptyMap(),
                        selectedTabId = selectedTab[ws.workspaceId] ?: ws.activeTabId,
                        onSelectTab = { selectedTab[ws.workspaceId] = it },
                        onOpenPane = ::openPane,
                        onTerminal = { nav.navigate(Routes.terminal(machineId, it.paneId)) },
                        online = state.online,
                    )
                }
            }
        }
    }

    if (newSheet && snap != null) {
        NewSheet(session, snap, onDismiss = { newSheet = false })
    }

    newWorktreeFor?.let { ws ->
        CreateWorktreeSheet(
            session,
            ws.worktree?.repoName?.ifBlank { null } ?: ws.label,
            RepoTarget(ws.workspaceId, null),
            branches = emptyList(),
            onDismiss = { newWorktreeFor = null },
            onNeedsTrust = { newWorktreeFor = null; nav.navigate(Routes.worktrees(machineId, ws.workspaceId)) },
        )
    }

    removing?.let { req -> RemoveWorktreeFlow(session, req) { removing = null } }

    importing?.let { candidates ->
        ImportDialog(
            source = state.machine.label,
            candidates = candidates,
            onDismiss = { importing = null },
            onImport = { chosen, via ->
                chosen.forEach { container.machines.upsert(it.toMachine(if (via) machineId else null)) }
                importing = null
                context.toast("Imported ${chosen.size} machine${if (chosen.size == 1) "" else "s"}")
            },
        )
    }

    snap?.let { s ->
        workspaceMenu?.let { ws ->
            val folder = ws.worktree?.checkoutPath?.ifBlank { null } ?: s.workspaceFolder(ws.workspaceId)
            WorkspaceActionsSheet(
                session, ws, s, state.workspaceMeta[ws.workspaceId].orEmpty(),
                onCode = folder?.let { f -> { nav.navigate(Routes.codeAt(CodeSpot(machineId, f))) } },
                onQuick = { quick = QuickPlace(machineId, folder.orEmpty(), label = ws.label.ifBlank { ws.workspaceId }, workspaceId = ws.workspaceId) },
                onTab = { tabMenu = it },
                onDevServers = { nav.navigate(Routes.preview(machineId, ws.workspaceId)) },
            ) { workspaceMenu = null }
        }
        tabMenu?.let { tab -> TabActionsSheet(session, tab, s) { tabMenu = null } }
        paneMenu?.let { pane -> PaneActionsSheet(session, pane, s) { paneMenu = null } }
    }
    quick?.let { place -> QuickSheets(nav, place, onDismiss = { quick = null }) }
    if (showNotices) {
        val rows = state.notices.map { NoticeRow(machineId, state.machine.label, it) }
        NoticesSheet(
            rows = rows,
            showMachine = false,
            onOpenAgent = { _, pane ->
                showNotices = false
                // A Shepherdr 3 agent's notice opens its task's chat or Coordinator, as from the Agents list.
                val agent = state.view?.agents?.firstOrNull { it.paneId == pane }?.takeIf { state.onShepherdr }
                nav.navigate(
                    agent?.shepherdrTask?.let { Routes.board(machineId, it, alone = true) }
                        ?: agent?.shepherdrSpace?.let { Routes.coordinator(machineId, it) }
                        ?: Routes.agent(machineId, pane),
                )
            },
            onClear = { session.clearNotices() },
            onDismiss = { showNotices = false },
        )
    }
    if (showNotify) {
        NotifySheet(session, state.machine.label) { showNotify = false }
    }
}

/**
 * A workspace: its name with the branch (and pull request) under it, then a 48dp row per pane: who
 * runs there (the agent's mark and state, or the shell's folder), which opens its chat or terminal,
 * and a terminal button. Tab chips and the layout thumbnail show only for a workspace with several
 * tabs; with one, its tab's actions are in the workspace's ⋯.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceCard(
    ws: WorkspaceInfo,
    snap: SessionSnapshot,
    meta: Map<String, String>,
    selectedTabId: String,
    onSelectTab: (String) -> Unit,
    onOpenPane: (PaneInfo) -> Unit,
    onTerminal: (PaneInfo) -> Unit,
    online: Boolean,
    // Null for herdr-core's spaces, which have none of classic herdr's worktrees or actions.
    onWorktrees: (() -> Unit)? = null,
    onNewWorktree: () -> Unit = {},
    onRemoveWorktree: () -> Unit = {},
    onWorkspaceMenu: (() -> Unit)? = null,
    onTabMenu: ((TabInfo) -> Unit)? = null,
    onPaneMenu: ((PaneInfo) -> Unit)? = null,
) {
    val c = Herdr.colors
    var menu by remember { mutableStateOf(false) }
    val repo = ws.worktree
    val tabs = snap.tabs.filter { it.workspaceId == ws.workspaceId }
    val tabId = tabs.firstOrNull { it.tabId == selectedTabId }?.tabId ?: tabs.firstOrNull()?.tabId
    val panes = snap.panes.filter { it.tabId == tabId }
    val layout = tabId?.let { snap.layout(it) }
    val under = workspaceLine(meta, linked = repo?.isLinkedWorktree == true)
    QuietCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // Long-press the header for the workspace's Git actions.
            modifier = (if (onWorktrees != null) Modifier.combinedClickable(onClick = {}, onLongClickLabel = "show the workspace's actions", onLongClick = { menu = true }) else Modifier)
                .padding(start = Space.card, top = Space.xs, end = Space.xs).then(if (onWorkspaceMenu == null) Modifier.heightIn(min = 48.dp) else Modifier),
        ) {
            StatusDot(if (ws.agentStatus == AgentStatus.Unknown) c.off else ws.agentStatus.color())
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(ws.label.ifBlank { ws.workspaceId }, style = MaterialTheme.typography.titleMedium, color = c.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (under.isNotEmpty()) {
                    Text(under, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            onWorkspaceMenu?.let { RowIcon(HerdrIcons.Ellipsis, "Workspace actions", it) }
        }
        HerdrMenu(expanded = menu, onDismissRequest = { menu = false }, title = ws.label.ifBlank { ws.workspaceId }) {
            MenuRow(
                if (repo != null) "Worktrees of ${repo.repoName.ifBlank { "this repository" }}" else "Worktrees in this folder",
                HerdrIcons.FolderGit2, { menu = false; onWorktrees?.invoke() },
            )
            MenuRow("New worktree…", HerdrIcons.Plus, { menu = false; onNewWorktree() }, enabled = online)
            if (repo?.isLinkedWorktree == true) {
                MenuRow("Remove this worktree…", HerdrIcons.Trash2, { menu = false; onRemoveWorktree() }, enabled = online, destructive = true)
            }
        }
        if (tabs.size > 1) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = Space.card, end = Space.xs)) {
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    items(tabs, key = { it.tabId }) { t ->
                        HerdrChip(t.label.ifBlank { t.tabId }, selected = t.tabId == tabId, onClick = { onSelectTab(t.tabId) })
                    }
                }
                tabs.firstOrNull { it.tabId == tabId }?.takeIf { onTabMenu != null }?.let { active ->
                    RowIcon(HerdrIcons.Ellipsis, "Tab actions", { onTabMenu?.invoke(active) })
                }
            }
        }
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(bottom = Space.xs)) {
            if (tabs.size > 1 && layout != null && layout.panes.size > 1) {
                LayoutMap(layout, snap, Modifier.padding(start = Space.card, top = Space.xs).size(width = 56.dp, height = 40.dp))
            }
            Column(Modifier.weight(1f)) {
                panes.forEach { pane -> PaneLine(pane, snap, onOpen = { onOpenPane(pane) }, onTerminal = { onTerminal(pane) }, onMenu = onPaneMenu?.let { m -> { m(pane) } }) }
            }
        }
    }
}

/** A pane in one 48dp row: the agent's mark and state, or the shell's glyph and folder; a tap opens it, the icon its terminal. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaneLine(pane: PaneInfo, snap: SessionSnapshot, onOpen: () -> Unit, onTerminal: () -> Unit, onMenu: (() -> Unit)?) {
    val c = Herdr.colors
    val agent = snap.agents.firstOrNull { it.paneId == pane.paneId }
    val status = agent?.agentStatus ?: pane.agentStatus
    val name = agent?.displayName ?: pane.displayName
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .combinedClickable(onClickLabel = if (agent != null) "Open the chat" else "Open the terminal", onClick = onOpen, onLongClickLabel = onMenu?.let { "Pane actions" }, onLongClick = onMenu)
            .padding(start = Space.card, end = Space.xs),
    ) {
        // The leading cell says who the pane is: the agent's brand mark, else the shell's glyph.
        if (agent != null) AgentMark(agent.agent ?: agent.displayName, size = 20.dp)
        else Icon(HerdrIcons.Terminal, null, tint = c.muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.m))
        Text(
            buildAnnotatedString {
                append(name)
                if (agent != null) withStyle(SpanStyle(color = if (status == AgentStatus.Unknown) c.muted else status.color())) {
                    append(" · " + agent.statusWord())
                }
                else withStyle(SpanStyle(color = c.muted)) {
                    (pane.foregroundCwd ?: pane.cwd)?.let { append(" · " + it.replace(Regex("^/(home|Users)/[^/]+"), "~")) }
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        RowIcon(HerdrIcons.SquareTerminal, "Terminal of $name", onTerminal)
    }
}

/** Under a workspace's name: its branch, its pull request and whether it is a worktree ("work/9-last-answered · PR #9"). */
private fun workspaceLine(meta: Map<String, String>, linked: Boolean): String = buildList {
    meta.forEach { (k, v) ->
        when (k.lowercase()) {
            "branch" -> add(v)
            "pr" -> add("PR " + v)
            else -> add("$k: $v")
        }
    }
    if (linked) add("worktree")
}.joinToString(" · ")

/**
 * The bar's line: the machine's state, its session when not the default one, and when it last
 * answered ("Online · answered 4 s ago", "Reconnecting · answered 3 min ago").
 */
private fun barLine(status: String, session: String, answeredAt: Long?, nowSeconds: Long): String = listOfNotNull(
    status,
    session.takeIf { it.isNotEmpty() && it != "default" }?.let { "session $it" },
    answeredAt?.let { "answered " + answeredAgo(nowSeconds * 1000 - it) },
).joinToString(" · ")

/** "just now", "12 s ago", "3 min ago", "2 h ago". */
internal fun answeredAgo(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 5 -> "just now"
        s < 60 -> "$s s ago"
        s < 3600 -> "${s / 60} min ago"
        else -> "${s / 3600} h ago"
    }
}

/** The phone's clock in seconds, again every second while shown. */
@Composable
private fun rememberSeconds(): Long {
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(1000)
            value = System.currentTimeMillis() / 1000
        }
    }
    return now
}

/** A 48dp icon button inside a row or card, quieter than a bar icon. */
@Composable
private fun RowIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Herdr.colors.muted, modifier = Modifier.size(20.dp)) }
}

/** A thumbnail of the tab's split layout, from herdr's pane rects. */
@Composable
private fun LayoutMap(layout: TabLayout, snap: SessionSnapshot, modifier: Modifier) {
    val c = Herdr.colors
    val colors = layout.panes.associate { p -> p.paneId to snap.agents.firstOrNull { it.paneId == p.paneId }?.agentStatus?.let { c.status(it) } }
    Canvas(modifier.semantics { contentDescription = "Layout: ${layout.panes.size} panes" }) {
        val area = layout.area
        if (area.width <= 0 || area.height <= 0) return@Canvas
        val sx = size.width / area.width
        val sy = size.height / area.height
        layout.panes.forEach { p ->
            val color = colors[p.paneId]
            val topLeft = Offset((p.rect.x - area.x) * sx + 1f, (p.rect.y - area.y) * sy + 1f)
            val sz = Size(p.rect.width * sx - 2f, p.rect.height * sy - 2f)
            drawRoundRect(color?.copy(alpha = 0.35f) ?: c.fieldFocused, topLeft, sz, CornerRadius(3.dp.toPx()))
        }
    }
}

@Composable
private fun ImportDialog(
    source: String,
    candidates: List<ImportCandidate>,
    onDismiss: () -> Unit,
    onImport: (List<ImportCandidate>, Boolean) -> Unit,
) {
    val chosen = remember { mutableStateMapOf<String, Boolean>().apply { candidates.forEach { put(it.id, !it.alreadyAdded) } } }
    var via by remember { mutableStateOf(true) }
    HerdrDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import from $source") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                candidates.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = chosen[c.id] == true, onCheckedChange = { chosen[c.id] = it }, enabled = !c.alreadyAdded)
                        Column {
                            Text(c.label)
                            Text(
                                if (c.alreadyAdded) "Already added" else "${c.user}@${c.host}:${c.port} · ${c.session}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Herdr.colors.muted,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = via, onCheckedChange = { via = it })
                    Text("Connect through $source", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Leave this on if these machines are only reachable from $source, such as on its home network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            QuietButton(onClick = { onImport(candidates.filter { chosen[it.id] == true && !it.alreadyAdded }, via) }) { Text("Import") }
        },
        dismissButton = { QuietButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
