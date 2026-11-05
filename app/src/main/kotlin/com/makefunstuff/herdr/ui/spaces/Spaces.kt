package com.makefunstuff.herdr.ui.spaces

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.PaneInfo
import com.makefunstuff.herdr.core.api.WorkspaceInfo
import com.makefunstuff.herdr.core.board.WorkOverview
import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.core.board.WorkTab
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.core.push.Notify
import com.makefunstuff.herdr.core.usage.UsageRules
import com.makefunstuff.herdr.ui.agents.AgentScreen
import com.makefunstuff.herdr.ui.board.rememberWorkBoards
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.coordinator.CoordinatorScreen
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ChipArt
import com.makefunstuff.herdr.ui.design.ChipIcon
import com.makefunstuff.herdr.ui.design.CircuitBackdrop
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.Led
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.LocalBarDrawer
import com.makefunstuff.herdr.ui.design.LocalBarMore
import com.makefunstuff.herdr.ui.design.LocalInTab
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.MenuSection
import com.makefunstuff.herdr.ui.design.SlotNumber
import com.makefunstuff.herdr.ui.design.chipFor
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.theme.caps
import com.makefunstuff.herdr.ui.theme.color

/*
 * Shepherdr 3.1's phone layout around the app's screens: a drawer of spaces grouped by machine (the
 * desktop workbench's sidebar: each machine a numbered "bus" with its meter, each space a numbered
 * slot with its status light), the shown space's chat as the Chat tab, and its ⋯ with the space's
 * tabs and the + menu's rows.
 */

/**
 * A space as the drawer shows it: every workspace of that name on its machine (classic herdr's and
 * herdr-core's, one row), its agents, and its Shepherdr space when it has one (its Assistant).
 */
data class SpaceSlot(
    val machineId: String,
    val name: String,
    val workspaces: List<WorkspaceInfo>,
    val agents: List<AgentInfo>,
    val panes: List<PaneInfo>,
    val board: WorkSpace?,
) {
    val key: String get() = Notify.key(machineId, name)

    /** The space's one status: what needs you first, then work, then a finish; none at rest. */
    val status: AgentStatus?
        get() = listOf(AgentStatus.Blocked, AgentStatus.Working, AgentStatus.Done).firstOrNull { s ->
            agents.any { it.agentStatus == s } || (s == AgentStatus.Blocked && board?.needsYou == true)
        }

    /** The agent the Chat tab opens when the space has no Assistant: the one that needs you, else at work, else the first. */
    val lead: AgentInfo?
        get() = agents.firstOrNull { it.agentStatus == AgentStatus.Blocked }
            ?: agents.firstOrNull { it.agentStatus == AgentStatus.Working } ?: agents.firstOrNull()

    companion object {
        /**
         * [host]'s spaces in its order, workspaces of one name (classic herdr's and herdr-core's) as one,
         * with its Shepherdr spaces ([work]) tied by their Assistant's label or their name; a Shepherdr
         * space with no terminals open here is a slot of its own.
         */
        fun of(host: HostState, work: WorkOverview?): List<SpaceSlot> {
            val view = host.view
            val byName = LinkedHashMap<String, MutableList<WorkspaceInfo>>()
            view?.workspaces.orEmpty().forEach { ws -> byName.getOrPut(ws.label.ifBlank { ws.workspaceId }.lowercase()) { mutableListOf() } += ws }
            val boardSpaces = work?.spaces.orEmpty()
            val used = HashSet<String>()
            val slots = byName.values.map { list ->
                val ids = list.map { it.workspaceId }.toSet()
                val agents = view?.agents.orEmpty().filter { it.workspaceId in ids }
                val name = list.first().label.ifBlank { list.first().workspaceId }
                val board = agents.firstNotNullOfOrNull { a -> a.shepherdrSpace?.let { id -> boardSpaces.firstOrNull { it.id == id } } }
                    ?: boardSpaces.firstOrNull { it.name.equals(name, ignoreCase = true) && it.id !in used }
                board?.let { used += it.id }
                SpaceSlot(host.machine.id, name, list, agents, view?.panes.orEmpty().filter { it.workspaceId in ids }, board)
            }
            val alone = boardSpaces.filter { it.id !in used && !it.unreachable }.map {
                SpaceSlot(host.machine.id, if (it.home) "Home" else it.name, emptyList(), emptyList(), emptyList(), it)
            }
            return alone.filter { it.board?.home == true } + slots + alone.filter { it.board?.home != true }
        }
    }
}

/** The space the Chat tab shows, by [SpaceSlot.key]; kept through rotation and a restart of the activity. */
@Stable
class SpaceChoice(key: String? = null) {
    var key by mutableStateOf(key)

    companion object {
        val Saver = listSaver<SpaceChoice, String?>({ listOf(it.key) }, { SpaceChoice(it[0]) })
    }
}

val LocalSpaceChoice = compositionLocalOf { SpaceChoice() }

/** Every machine's spaces, in the machines' order. */
@Composable
fun rememberSpaces(): List<Pair<HostState, List<SpaceSlot>>> {
    val container = LocalContainer.current
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val work = rememberWorkBoards()
    return remember(hosts, work) { hosts.map { h -> h to SpaceSlot.of(h, work[h.machine.id]) } }
}

/** The space shown: the one chosen, else the first with an Assistant on a machine that answers, else the first with an agent, else the first. */
fun shownSpace(spaces: List<Pair<HostState, List<SpaceSlot>>>, chosen: String?): SpaceSlot? {
    val all = spaces.filter { it.first.online }.flatMap { it.second }
    return all.firstOrNull { it.key == chosen }
        ?: all.firstOrNull { it.board != null } ?: all.firstOrNull { it.agents.isNotEmpty() } ?: all.firstOrNull()
}

/**
 * The drawer: the brand row (the workbench chip, HERDR, its WORKBENCH tag, search), SPACES, then
 * each machine as a bus (BUS 1 · DEVBOX, its light, its meter; a tap opens the machine) with its
 * spaces as numbered slots (a long press: the space's Notify), and at its foot Settings and Add machine.
 */
@Composable
fun SpacesDrawer(nav: NavController, onPick: (SpaceSlot) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    val spaces = rememberSpaces()
    val choice = LocalSpaceChoice.current
    val shown = shownSpace(spaces, choice.key)
    val push by LocalContainer.current.push.state.collectAsStateWithLifecycle()
    var held by remember { mutableStateOf<Pair<SpaceSlot, String>?>(null) }
    fun go(route: String) { onClose(); nav.navigate(route) }
    held?.let { (slot, machine) -> SpaceSheet(slot, machine, onDismiss = { held = null }) }
    Column(modifier.fillMaxHeight().background(c.panel).statusBarsPadding().navigationBarsPadding()) {
        // Brand row.
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = Space.l, end = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChipIcon(ChipArt.Herdr, size = 20.dp)
            Spacer(Modifier.width(Space.s))
            Text("HERDR", style = MaterialTheme.typography.titleLarge, color = c.strong)
            Spacer(Modifier.width(Space.s))
            Text(
                "WORKBENCH", style = MaterialTheme.typography.labelSmall, color = c.accent,
                modifier = Modifier.drawBehind { drawRect(c.accent, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())) }
                    .padding(horizontal = Space.xs, vertical = 1.dp),
            )
            Spacer(Modifier.weight(1f))
            BarIcon(HerdrIcons.Search, "Search", { go(Routes.search()) })
        }
        Rule()
        Text(
            "SPACES", style = MaterialTheme.typography.titleSmall, color = c.muted,
            modifier = Modifier.padding(start = Space.l, top = Space.m, bottom = Space.xs).semantics { heading() },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CircuitBackdrop(Modifier.matchParentSize())
            LazyColumn(Modifier.fillMaxSize()) {
                spaces.forEachIndexed { i, (host, slots) ->
                    item(key = "bus/${host.machine.id}") {
                        Bus(i + 1, host, onOpen = { go(Routes.machine(host.machine.id)) })
                    }
                    if (host.online) {
                        if (slots.isEmpty()) item(key = "none/${host.machine.id}") {
                            Text(
                                "No spaces open", style = MaterialTheme.typography.bodySmall, color = c.muted,
                                modifier = Modifier.background(c.panel).fillMaxWidth().padding(start = Space.l + 34.dp, top = Space.xs, bottom = Space.s),
                            )
                        }
                        items(slots, key = { "slot/${it.key}" }) { slot ->
                            Slot(
                                slots.indexOf(slot) + 1, slot, on = slot.key == shown?.key, off = push.rules.notifyOf(slot.key) == Notify.Off,
                                onHold = { held = slot to host.machine.label },
                            ) { onPick(slot); onClose() }
                        }
                    }
                }
            }
        }
        Rule()
        // The foot: Settings and Add machine, as 3.1's sidebar has them.
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.xs, vertical = Space.xs)) {
            FootButton(HerdrIcons.Settings, "Settings", Modifier.weight(1f)) { go(Routes.SETTINGS) }
            FootButton(HerdrIcons.Plus, "Add machine", Modifier.weight(1f)) { go(Routes.edit()) }
        }
    }
}

@Composable
private fun Rule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Herdr.colors.lineStrong))
}

@Composable
private fun FootButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Herdr.colors
    Row(
        modifier.heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Space.s))
        Text(label.caps(), style = MaterialTheme.typography.labelMedium, color = c.text, maxLines = 1)
    }
}

/**
 * A machine as the desktop's workbench names it, a bus: "BUS 1 · DEVBOX", its connection light, and
 * while it answers its meter (CPU, memory, the tightest plan); while it doesn't, why. A tap opens the machine.
 */
@Composable
private fun Bus(n: Int, host: HostState, onOpen: () -> Unit) {
    val c = Herdr.colors
    val online = host.online
    Row(
        Modifier.fillMaxWidth().background(c.panel).heightIn(min = 40.dp)
            .clickable(onClickLabel = "Open ${host.machine.label}", onClick = onOpen)
            .padding(start = Space.l, end = Space.m, top = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HerdrIcons.Server, null, tint = c.muted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Space.s))
        Text(
            "BUS $n · ${host.machine.label.caps()}", style = MaterialTheme.typography.labelSmall, color = if (online) c.text else c.muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(Space.s))
        if (online) Meter(host.machine.id) else Text(host.status.label(), style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 1)
        Spacer(Modifier.weight(0.01f))
        Spacer(Modifier.width(Space.s))
        Led(if (online) c.done else c.off, size = 6.dp)
    }
}

/** A machine's meter, as 3.1's sidebar draws it: thin bars for CPU, memory and its tightest plan; amber near a limit, red past it. */
@Composable
private fun Meter(machineId: String) {
    val container = LocalContainer.current
    val health by container.health.latest.collectAsStateWithLifecycle()
    val usage by container.usage.latest.collectAsStateWithLifecycle()
    val s = health[machineId]
    val plan = remember(usage, machineId) {
        usage[machineId]?.let { e -> UsageRules.tightest(mapOf(machineId to e.report), container.usage.nowSeconds())?.limit?.percent }
    }
    val levels = listOfNotNull(
        s?.let { "CPU" to (it.cpuBusy ?: it.loadPercent).coerceIn(0.0, 100.0) },
        s?.memory?.let { "Memory" to it.usedPercent },
        plan?.let { "Plan" to it },
    )
    if (levels.isEmpty()) return
    val c = Herdr.colors
    Row(
        Modifier.semantics { contentDescription = levels.joinToString(", ") { (k, v) -> "$k ${v.toInt()}%" } },
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom,
    ) {
        levels.forEach { (_, v) ->
            val tone = when {
                v >= UsageRules.ALMOST -> c.red
                v >= UsageRules.NEAR -> c.accent
                else -> c.muted
            }
            Box(Modifier.size(width = 4.dp, height = 12.dp).background(c.line).drawBehind {
                val h = size.height * (v / 100.0).toFloat().coerceIn(0.08f, 1f)
                drawRect(tone, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
            })
        }
    }
}

/**
 * A space as a numbered slot: its index cell, its name in capitals, its one status light; the shown one amber
 * with a bar at its start. [off]: its Notify is Off, so its name is dim beside a crossed-out bell (its light still shows).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Slot(n: Int, slot: SpaceSlot, on: Boolean, off: Boolean, onHold: () -> Unit, onClick: () -> Unit) {
    val c = Herdr.colors
    val status = slot.status
    Row(
        Modifier.fillMaxWidth().background(if (on) c.selected else c.panel)
            .drawBehind { if (on) drawRect(c.accent, size = Size(3.dp.toPx(), size.height)) }
            .heightIn(min = 44.dp)
            .combinedClickable(onClickLabel = "Show its chat", onLongClickLabel = "Notify", onLongClick = onHold, onClick = onClick)
            .semantics(mergeDescendants = true) {
                selected = on
                contentDescription = listOfNotNull(slot.name, status?.let { it.name.lowercase() }, "notify off".takeIf { off }).joinToString(", ")
            }
            .padding(start = Space.l, end = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SlotNumber(n, on)
        Spacer(Modifier.width(Space.s))
        Text(
            slot.name.caps(), style = MaterialTheme.typography.labelLarge, color = if (on) c.accent else if (off) c.muted else c.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (off) {
            Icon(HerdrIcons.BellOff, null, tint = c.muted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Space.s))
        }
        val program = slot.lead?.let { it.agent ?: it.displayName }
        if (slot.board != null) Text("ASSISTANT", style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 1)
        else if (program != null) ChipIcon(chipFor(program), size = 16.dp)
        Spacer(Modifier.width(Space.s))
        Led(status?.color() ?: c.line)
    }
}

/**
 * The Chat tab: the shown space's chat, as 3.1's phone shows it. A space with an Assistant shows
 * its Assistant's chat; another its agent's (the one that needs you first); one with no agent its
 * terminals to open. Its bar's leading button opens the drawer, and its ⋯ holds the space's tabs
 * and the + menu's rows.
 */
@Composable
fun ChatTab(nav: NavController) {
    val openDrawer = LocalBarDrawer.current
    val spaces = rememberSpaces()
    val choice = LocalSpaceChoice.current
    val slot = shownSpace(spaces, choice.key)
    val host = spaces.firstOrNull { it.first.machine.id == slot?.machineId }?.first
    var menu by remember { mutableStateOf(false) }
    val tabMenu = rememberTabMenu()
    val more: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (slot != null) BarIcon(HerdrIcons.Layers, "Tabs and new", { menu = true })
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalInTab provides (openDrawer != null), LocalBarMore provides more) {
        when {
            slot == null || host == null -> Column(Modifier.fillMaxSize()) {
                HerdrTopBar("Chat")
                Box(Modifier.weight(1f)) {
                    CircuitBackdrop(Modifier.fillMaxSize())
                    if (spaces.isEmpty()) EmptyState("No machines yet", "Add a machine that runs herdr: its spaces show in the drawer.", icon = HerdrIcons.Server)
                    else EmptyState("No space open", "Open the drawer to pick a space. Its Assistant, or its agent, shows here.", icon = HerdrIcons.MessagesSquare)
                }
            }
            slot.board != null && host.onShepherdr -> androidx.compose.runtime.key(slot.key) { CoordinatorScreen(nav, slot.machineId, slot.board.id) }
            slot.lead != null -> androidx.compose.runtime.key(slot.key) { AgentScreen(nav, slot.machineId, slot.lead!!.paneId) }
            else -> Column(Modifier.fillMaxSize()) {
                HerdrTopBar(slot.name, subtitle = host.machine.label)
                Box(Modifier.weight(1f)) {
                    CircuitBackdrop(Modifier.fillMaxSize())
                    if (slot.panes.isEmpty()) EmptyState("No agent here", "Nothing runs in ${slot.name} yet.", icon = HerdrIcons.Terminal)
                    else LazyColumn(Modifier.fillMaxSize().background(Herdr.colors.ground)) {
                        item { com.makefunstuff.herdr.ui.design.SectionHeading("Terminals", Modifier.padding(horizontal = Space.screen), top = Space.m) }
                        items(slot.panes, key = { it.paneId }) { p ->
                            ListRow(
                                p.displayName, subtitle = p.foregroundCwd ?: p.cwd,
                                leading = { ChipIcon(chipFor(p.agent ?: "shell"), size = 20.dp) },
                                chevron = true, onClick = { nav.navigate(Routes.terminal(slot.machineId, p.paneId)) },
                            )
                        }
                    }
                }
            }
        }
    }
    if (menu && slot != null && host != null) SpaceMenu(nav, slot, host, tabMenu) { menu = false }
    TabMenuDialogs(tabMenu)
}

/** The tab the Chat tab shows, as Shepherdr's tab bar knows it: a Shepherdr space's chat is its Assistant's tab. */
fun SpaceSlot.shownTab(host: HostState): WorkTab? = board?.takeIf { host.onShepherdr }?.tabs?.firstOrNull { it.assistant }

/**
 * The shown space's ⋯ on a phone (3.1's header menu): the shown tab's menu, its tabs (each agent's chat, each
 * terminal), then New task, the Assistant and Start an agent, and its machine.
 */
@Composable
private fun SpaceMenu(nav: NavController, slot: SpaceSlot, host: HostState, tabMenu: TabMenuState, onDismiss: () -> Unit) {
    fun go(route: String) { onDismiss(); nav.navigate(route) }
    HerdrMenu(expanded = true, onDismissRequest = onDismiss, title = slot.name, subtitle = host.machine.label) {
        val board = slot.board
        slot.shownTab(host)?.let { tab -> if (board != null) TabMenuRows(tabMenu, slot.machineId, board, tab, onPicked = onDismiss) }
        if (slot.panes.isNotEmpty()) {
            MenuSection("Tabs")
            slot.panes.forEach { p ->
                val agent = slot.agents.firstOrNull { it.paneId == p.paneId }
                val name = agent?.let { a -> a.displayName + (a.agentStatus.takeIf { it == AgentStatus.Blocked }?.let { " · needs you" } ?: "") } ?: p.displayName
                MenuRow(name, if (agent != null) HerdrIcons.MessageSquare else HerdrIcons.Terminal, {
                    go(if (agent != null) Routes.agent(slot.machineId, p.paneId) else Routes.terminal(slot.machineId, p.paneId))
                })
                if (agent != null) MenuRow("Terminal · ${agent.displayName}", HerdrIcons.Terminal, { go(Routes.terminal(slot.machineId, p.paneId)) })
            }
        }
        MenuSection("New")
        if (host.onShepherdr) MenuRow("New task", HerdrIcons.SquarePen, { go(Routes.start(slot.machineId)) })
        if (host.onShepherdr && slot.board != null) MenuRow("Assistant", HerdrIcons.MessagesSquare, { go(Routes.coordinator(slot.machineId, slot.board.id)) })
        MenuRow("Start an agent", HerdrIcons.Bot, { go(Routes.start(slot.machineId)) }, enabled = host.online)
        MenuSection("Machine")
        MenuRow(host.machine.label, HerdrIcons.Server, { go(Routes.machine(slot.machineId)) })
        com.makefunstuff.herdr.ui.change.ChangeScreenRow(onDismiss)
    }
}

/**
 * The Artifacts tab: the shown space's artifacts, as 3.1's phone's third tab. Artifacts are
 * Shepherdr's: a space on a machine without it says so.
 */
@Composable
fun ArtifactsTab(nav: NavController) {
    val spaces = rememberSpaces()
    val slot = shownSpace(spaces, LocalSpaceChoice.current.key)
    val host = spaces.firstOrNull { it.first.machine.id == slot?.machineId }?.first
    if (slot != null && host != null && host.onShepherdr) {
        androidx.compose.runtime.key(slot.key) {
            com.makefunstuff.herdr.ui.artifacts.ArtifactsScreen(nav, slot.machineId, slot.board?.id, inTab = true)
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        HerdrTopBar("Artifacts")
        Box(Modifier.weight(1f)) {
            CircuitBackdrop(Modifier.fillMaxSize())
            EmptyState(
                "No artifacts here",
                if (slot == null) "Pick a space in the drawer: its artifacts show here."
                else "Artifacts are kept by Shepherdr, which ${host?.machine?.label ?: "this machine"} doesn't run.",
                icon = HerdrIcons.LayoutDashboard,
            )
        }
    }
}
