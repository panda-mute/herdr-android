package com.makefunstuff.herdr.ui.machines

import com.makefunstuff.herdr.ui.change.ChangeScreenRow
import androidx.compose.ui.graphics.RectangleShape

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.produceState
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
import com.makefunstuff.herdr.ui.code.CodeSpot
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.PaneInfo
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.api.TabInfo
import com.makefunstuff.herdr.core.api.TabLayout
import com.makefunstuff.herdr.core.api.WorkspaceInfo
import com.makefunstuff.herdr.ui.common.StatusDot
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.detail
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.AgentMark
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.manage.PaneActionsSheet
import com.makefunstuff.herdr.ui.manage.TabActionsSheet
import com.makefunstuff.herdr.ui.manage.WorkspaceActionsSheet
import com.makefunstuff.herdr.ui.manage.shepherdrsSpace
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.statusWord
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.theme.color
import com.makefunstuff.herdr.ui.theme.status

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
    var menu by remember { mutableStateOf(false) }
    var workspaceMenu by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var tabMenu by remember { mutableStateOf<TabInfo?>(null) }
    var paneMenu by remember { mutableStateOf<PaneInfo?>(null) }
    var coreClose by remember { mutableStateOf<PaneInfo?>(null) }
    val selectedTab = remember { mutableStateMapOf<String, String>() }

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
                BarIcon(HerdrIcons.Search, "Search ${state.machine.label}", { nav.navigate(Routes.search(machineId)) })
                BarIcon(HerdrIcons.Ellipsis, "More", { menu = true })
                HerdrMenu(expanded = menu, onDismissRequest = { menu = false }, title = state.machine.label) {
                    MenuRow("Docs", HerdrIcons.FileText, { menu = false; nav.navigate(Routes.docs(machineId)) })
                    MenuRow("Edit machine", HerdrIcons.Pencil, { menu = false; nav.navigate(Routes.edit(machineId)) })
                    ChangeScreenRow { menu = false }
                }
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
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.xs, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            if (!state.online) {
                item { Notice("Offline · showing the last known state", color = Herdr.colors.notice, icon = HerdrIcons.Clock) }
            }
            if (state.online) item(key = "places") {
                Column(Modifier.padding(top = Space.s)) {
                    MachinePlaces(
                        session,
                        onHealth = { nav.navigate(Routes.health(machineId)) },
                        onUsage = { nav.navigate(Routes.usage(machineId)) },
                        onLogins = { nav.navigate(Routes.logins(machineId)) },
                    )
                }
            }
            // One list of spaces, classic herdr's and herdr-core's, by name: the backend is a detail line, never a heading.
            val spaces = (snap?.workspaces.orEmpty().map { it to snap!! } + core?.snapshot?.workspaces.orEmpty().map { it to core!!.snapshot })
                .sortedBy { (ws, _) -> ws.label.ifBlank { ws.workspaceId }.lowercase() }
            val both = snap != null && core != null
            item(key = "spaces") { SectionHeading(if (spaces.isEmpty()) "Spaces" else "Spaces · ${spaces.size}", top = Space.m) }
            if (spaces.isEmpty()) {
                item { Text("No terminals open on this machine.", color = Herdr.colors.muted, style = MaterialTheme.typography.bodyMedium) }
            }
            items(spaces, key = { it.first.workspaceId }) { (ws, from) ->
                val classic = from === snap
                if (classic) WorkspaceCard(
                    ws,
                    from,
                    meta = state.workspaceMeta[ws.workspaceId].orEmpty(),
                    selectedTabId = selectedTab[ws.workspaceId] ?: ws.activeTabId,
                    onSelectTab = { selectedTab[ws.workspaceId] = it },
                    onOpenPane = ::openPane,
                    onTerminal = { nav.navigate(Routes.terminal(machineId, it.paneId)) },
                    backend = if (both) "classic herdr" else null,
                    onWorkspaceMenu = { workspaceMenu = ws },
                    onTabMenu = { tabMenu = it },
                    onPaneMenu = { paneMenu = it },
                ) else WorkspaceCard(
                    // herdr-core has no layouts or actions on its spaces: the panes open, and close (`hc kill`) on a long press.
                    ws,
                    from,
                    meta = emptyMap(),
                    selectedTabId = selectedTab[ws.workspaceId] ?: ws.activeTabId,
                    onSelectTab = { selectedTab[ws.workspaceId] = it },
                    onOpenPane = ::openPane,
                    onTerminal = { nav.navigate(Routes.terminal(machineId, it.paneId)) },
                    backend = if (both) "herdr-core ${core?.version.orEmpty()}".trim() else null,
                    // A space of Shepherdr's (its Assistant's, its tasks' workers') closes as Shepherdr closes them, not here.
                    onPaneMenu = { p: PaneInfo -> coreClose = p }.takeIf { !from.shepherdrsSpace(ws.workspaceId) },
                )
            }
        }
    }

    coreClose?.let { pane ->
        com.makefunstuff.herdr.ui.manage.CloseHcTerminalDialog(session, pane.paneId, pane.displayName, onDismiss = { coreClose = null })
    }
    snap?.let { s ->
        workspaceMenu?.let { ws ->
            val folder = ws.worktree?.checkoutPath?.ifBlank { null } ?: s.workspaceFolder(ws.workspaceId)
            WorkspaceActionsSheet(
                session, ws, s, state.workspaceMeta[ws.workspaceId].orEmpty(),
                onCode = folder?.let { f -> { nav.navigate(Routes.codeAt(CodeSpot(machineId, f))) } },
                onTab = { tabMenu = it },
            ) { workspaceMenu = null }
        }
        tabMenu?.let { tab -> TabActionsSheet(session, tab, s) { tabMenu = null } }
        paneMenu?.let { pane -> PaneActionsSheet(session, pane, s) { paneMenu = null } }
    }
}

/** The folder a workspace's active pane is in: where its code is. */
private fun SessionSnapshot.workspaceFolder(workspaceId: String): String? {
    val ws = workspace(workspaceId) ?: return null
    val paneId = layout(ws.activeTabId)?.focusedPaneId?.ifEmpty { null }
        ?: panes.firstOrNull { it.tabId == ws.activeTabId }?.paneId
        ?: panes.firstOrNull { it.workspaceId == workspaceId }?.paneId
    val pane = panes.firstOrNull { it.paneId == paneId } ?: return null
    return pane.foregroundCwd?.ifBlank { null } ?: pane.cwd?.ifBlank { null }
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
    /** Which herdr it runs on, as a detail line, on a machine that runs both. */
    backend: String? = null,
    // Null for herdr-core's spaces, which have none of classic herdr's actions.
    onWorkspaceMenu: (() -> Unit)? = null,
    onTabMenu: ((TabInfo) -> Unit)? = null,
    onPaneMenu: ((PaneInfo) -> Unit)? = null,
) {
    val c = Herdr.colors
    val repo = ws.worktree
    val tabs = snap.tabs.filter { it.workspaceId == ws.workspaceId }
    val tabId = tabs.firstOrNull { it.tabId == selectedTabId }?.tabId ?: tabs.firstOrNull()?.tabId
    val panes = snap.panes.filter { it.tabId == tabId }
    val layout = tabId?.let { snap.layout(it) }
    val under = listOf(workspaceLine(meta, linked = repo?.isLinkedWorktree == true), backend.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")
    QuietCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = Space.card, top = Space.xs, end = Space.xs).then(if (onWorkspaceMenu == null) Modifier.heightIn(min = 48.dp) else Modifier),
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
        if (tabs.size > 1) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = Space.card, end = Space.xs)) {
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    items(tabs, key = { it.tabId }) { t ->
                        // The tab that needs you (or works, or finished) says so on its own chip, so the space's light is explained.
                        val st = t.agentStatus.takeIf { it != AgentStatus.Unknown && it != AgentStatus.Idle }
                        HerdrChip(
                            selected = t.tabId == tabId, onClick = { onSelectTab(t.tabId) },
                            label = { Text(t.label.ifBlank { t.tabId }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = st?.let { { com.makefunstuff.herdr.ui.design.Led(it.color()) } },
                        )
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
        Modifier.size(48.dp).clip(RectangleShape).clickable(role = Role.Button, onClick = onClick)
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
            drawRect(color?.copy(alpha = 0.35f) ?: c.fieldFocused, topLeft, sz)
        }
    }
}
