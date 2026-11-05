package com.makefunstuff.herdr.ui.spaces

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.AppContainer
import com.makefunstuff.herdr.core.board.TabMenus
import com.makefunstuff.herdr.core.board.WorkAgentChoice
import com.makefunstuff.herdr.core.board.WorkBoard
import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.core.board.WorkTab
import com.makefunstuff.herdr.core.shepherdr.ModelMenus
import com.makefunstuff.herdr.core.shepherdr.V2ModelMenu
import com.makefunstuff.herdr.core.shepherdr.V2ModelPick
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.ActionRow
import com.makefunstuff.herdr.ui.design.ChipIcon
import com.makefunstuff.herdr.ui.design.DangerButton
import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrSheet
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.MenuSection
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.SegmentedPills
import com.makefunstuff.herdr.ui.design.SheetTitle
import com.makefunstuff.herdr.ui.design.HerdrSwitch
import com.makefunstuff.herdr.ui.design.Toasts
import com.makefunstuff.herdr.ui.design.chipFor
import com.makefunstuff.herdr.ui.manage.ConfirmDialog
import com.makefunstuff.herdr.ui.manage.RenameDialog
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * A tab's menu, as Shepherdr 3.1's (its tab bar's right-click, a phone's header ⋯): Rename…, Switch agent, Restart,
 * Model… and Close tab, to get a stuck agent going again from the phone. Each goes through the machine's Shepherdr
 * service as 3.1's web app does; Restart, Switch agent and Close ask first.
 */

/** What the tab menu opened: one of its rows, on [tab] of [space] on machine [machineId]. */
data class TabMenuOpen(val machineId: String, val space: WorkSpace, val tab: WorkTab, val row: TabMenus.Row)

/** The tab menu's dialogs and sheets, kept by the screen so they outlive the menu that opened them. */
@Stable
class TabMenuState internal constructor(private val container: AppContainer, private val scope: CoroutineScope) {
    var open by mutableStateOf<TabMenuOpen?>(null)
        internal set

    /** Switch agent's pick waiting for its Switch: the agent's name and kind. */
    internal var switchTo by mutableStateOf<WorkAgentChoice?>(null)

    /** The Assistant's workers still running, counted for Close; null while counting or not asked. */
    internal var workers by mutableStateOf<Int?>(null)

    fun show(o: TabMenuOpen) {
        switchTo = null
        workers = null
        open = o
    }

    fun dismiss() {
        open = null
        switchTo = null
        workers = null
    }

    /** Runs [act] on [o]'s machine's board, says [done] or why it failed, and reads the board and the machine again. */
    internal fun run(o: TabMenuOpen, done: String?, act: suspend (WorkBoard) -> Unit) {
        dismiss()
        scope.launch {
            val session = container.hosts.session(o.machineId)
            val result = runCatching { act(session?.awaitWorkBoard() ?: error("The machine isn't connected")) }
            result.exceptionOrNull()?.let { Toasts.show(it.message ?: "It didn't work") } ?: done?.let { Toasts.show(it) }
            session?.refresh()
            runCatching { container.boards.readAll(freshMs = 0) }
        }
    }

    internal suspend fun board(machineId: String): WorkBoard =
        container.hosts.session(machineId)?.awaitWorkBoard() ?: error("The machine isn't connected")
}

@Composable
fun rememberTabMenu(): TabMenuState {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    return remember(container, scope) { TabMenuState(container, scope) }
}

private fun TabMenus.Row.icon(): ImageVector = when (this) {
    TabMenus.Row.Rename -> HerdrIcons.SquarePen
    TabMenus.Row.Switch -> HerdrIcons.ArrowLeftRight
    TabMenus.Row.Restart -> HerdrIcons.RotateCcw
    TabMenus.Row.Model -> HerdrIcons.Cpu
    TabMenus.Row.Close -> HerdrIcons.X
}

/**
 * [tab]'s rows under a heading naming it, for a menu or sheet that [onPicked] closes; what a row opens [state] draws
 * ([TabMenuDialogs]). [item] draws a row (a menu's by default).
 */
@Composable
fun TabMenuRows(
    state: TabMenuState,
    machineId: String,
    space: WorkSpace,
    tab: WorkTab,
    onPicked: () -> Unit,
    heading: String = "Tab · ${tab.name}",
    item: @Composable (label: String, icon: ImageVector, destructive: Boolean, onClick: () -> Unit) -> Unit = { label, icon, destructive, onClick ->
        MenuRow(label, icon, onClick, destructive = destructive)
    },
) {
    MenuSection(heading)
    TabMenus.rows(tab).forEach { row ->
        item(row.label, row.icon(), row == TabMenus.Row.Close) {
            onPicked()
            state.show(TabMenuOpen(machineId, space, tab, row))
        }
    }
}

/** What a tab menu's row opened: Rename's field, Switch agent's list, a confirmation, the model menu. */
@Composable
fun TabMenuDialogs(state: TabMenuState) {
    val o = state.open ?: return
    val tab = o.tab
    when (o.row) {
        TabMenus.Row.Rename -> RenameDialog("Rename tab", if (tab.named) tab.name else "", onDismiss = state::dismiss) { name ->
            state.run(o, null) { it.renameTab(o.space.id, tab, name) }
        }
        TabMenus.Row.Restart -> ConfirmDialog(
            "Restart ${tab.name}?",
            "A new ${agentName(tab)} takes over this tab and picks up from the old one's log. What it is doing now stops.",
            "Restart",
            onDismiss = state::dismiss,
        ) { state.run(o, "Restarting ${tab.name}…") { it.restartTab(tab) } }
        TabMenus.Row.Switch -> state.switchTo?.let { to ->
            ConfirmDialog(
                "Switch to ${to.name}?",
                "${to.name} takes over this tab and picks up from ${agentName(tab)}'s log. What it is doing now stops.",
                "Switch",
                onDismiss = state::dismiss,
            ) { state.run(o, "Switching to ${to.name}…") { it.restartTab(tab, to.kind) } }
        } ?: SwitchSheet(state, o)
        TabMenus.Row.Model -> ModelMenuSheet(state, o)
        TabMenus.Row.Close -> CloseDialog(state, o)
    }
}

/** The agent's name in a sentence: "Claude Code", else its kind, else "agent". */
private fun agentName(tab: WorkTab): String = when (tab.agent) {
    "claude" -> "Claude Code"
    "codex" -> "Codex"
    null -> "agent"
    else -> tab.agent!!
}

/** Switch agent: the machine's installed agents, the one running now marked Current. */
@Composable
private fun SwitchSheet(state: TabMenuState, o: TabMenuOpen) {
    var agents by remember(o) { mutableStateOf<List<WorkAgentChoice>?>(null) }
    var failed by remember(o) { mutableStateOf<String?>(null) }
    LaunchedEffect(o) {
        runCatching { state.board(o.machineId).tabAgents(o.space.id) }
            .onSuccess { agents = TabMenus.switchChoices(it) }.onFailure { failed = it.message ?: "Couldn't load agents" }
    }
    HerdrSheet(onDismissRequest = state::dismiss) {
        SheetTitle(TabMenus.SWITCH_TITLE, subtitle = o.tab.name)
        Column(Modifier.padding(horizontal = Space.s)) {
            val list = agents
            when {
                failed != null -> Notice(failed!!, Modifier.padding(horizontal = Space.m), color = Herdr.colors.red, icon = HerdrIcons.CircleAlert)
                list == null -> Quiet(TabMenus.SWITCH_LOADING)
                list.isEmpty() -> Quiet("No agent is installed there.")
                else -> list.forEach { a ->
                    val now = a.kind == o.tab.agent
                    ActionRow(
                        a.name, enabled = !now, detail = if (now) TabMenus.SWITCH_CURRENT else null,
                        trailing = { ChipIcon(chipFor(a.kind), size = 18.dp) },
                        onClick = { state.switchTo = a },
                    )
                }
            }
        }
        Spacer(Modifier.heightIn(min = Space.m))
    }
}

@Composable
private fun Quiet(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted, modifier = Modifier.padding(horizontal = Space.m, vertical = Space.m))
}

/**
 * Close tab, as 3.1 closes one: another tab asks, then closes; the Assistant's counts its workers first and, with
 * some running, asks what becomes of them; a space's last tab asks to close the space.
 */
@Composable
private fun CloseDialog(state: TabMenuState, o: TabMenuOpen) {
    val tab = o.tab
    when (val what = TabMenus.close(o.space, tab)) {
        is TabMenus.Close.Tab -> ConfirmDialog(
            "Close “${tab.name}”?", "The tab closes and what runs in it ends.", "Close tab", onDismiss = state::dismiss,
        ) { state.run(o, null) { it.closeTab(what.id) } }
        TabMenus.Close.Space -> ConfirmDialog(
            TabMenus.closeSpaceTitle(o.space.name), TabMenus.CLOSE_SPACE_DETAIL, TabMenus.CLOSE_SPACE_VERB, onDismiss = state::dismiss,
        ) { state.run(o, null) { it.closeSpace(o.space.id) } }
        TabMenus.Close.Nowhere -> LaunchedEffect(o) {
            state.dismiss()
            Toasts.show("Close it in the Herdr window.")
        }
        TabMenus.Close.Assistant -> {
            LaunchedEffect(o) {
                runCatching { state.board(o.machineId).assistantWorkers(o.space.id) }
                    .onSuccess { state.workers = it }
                    .onFailure { state.dismiss(); Toasts.show(it.message ?: "The tab didn't close") }
            }
            val workers = state.workers ?: return
            if (workers == 0) {
                ConfirmDialog(
                    "Close “${tab.name}”?", "The Assistant ends and its tab closes.", "Close tab", onDismiss = state::dismiss,
                ) { state.run(o, null) { it.closeAssistant(o.space.id, stopWorkers = false) } }
            } else {
                HerdrDialog(
                    onDismissRequest = state::dismiss,
                    title = { Text(TabMenus.CLOSE_ASSISTANT_TITLE) },
                    text = { Text(TabMenus.closeAssistantDetail(workers)) },
                    confirmButton = {
                        DangerButton(TabMenus.CLOSE_ASSISTANT_STOP) { state.run(o, null) { it.closeAssistant(o.space.id, stopWorkers = true) } }
                    },
                    dismissButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            QuietButton(onClick = state::dismiss) { Text("Cancel") }
                            QuietButton(onClick = { state.run(o, null) { it.closeAssistant(o.space.id, stopWorkers = false) } }) {
                                Text(TabMenus.CLOSE_ASSISTANT_KEEP)
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * The model menu (3.1's, its chat's model button): Model (every installed CLI's models; another CLI's switches agent),
 * Reasoning, Fast where the model has it, Permissions, and one Restart with… once something differs from what runs.
 */
@Composable
private fun ModelMenuSheet(state: TabMenuState, o: TabMenuOpen) {
    var menu by remember(o) { mutableStateOf<V2ModelMenu?>(null) }
    var sel by remember(o) { mutableStateOf<V2ModelPick?>(null) }
    var error by remember(o) { mutableStateOf<String?>(null) }
    var perms by remember(o) { mutableStateOf(false) }
    LaunchedEffect(o) {
        runCatching { state.board(o.machineId).modelMenu(o.tab.chat) }
            .onSuccess { menu = it; sel = it.current }.onFailure { error = it.message ?: "Couldn't read what it runs on." }
    }
    val c = Herdr.colors
    HerdrSheet(onDismissRequest = state::dismiss) {
        SheetTitle(ModelMenus.MODEL, subtitle = o.tab.name)
        val m = menu
        val s = sel
        if (m == null || s == null) {
            if (error != null) Notice(error!!, Modifier.padding(horizontal = Space.xl), color = c.red, icon = HerdrIcons.CircleAlert)
            else Quiet("Reading what it runs on…")
            Spacer(Modifier.heightIn(min = Space.l))
            return@HerdrSheet
        }
        val remote = m.remote
        Column(Modifier.padding(horizontal = Space.s)) {
            if (remote != null) Notice("It restarts from its own machine's window: $remote.", Modifier.padding(horizontal = Space.m, vertical = Space.xs))
            ModelMenus.models(m).forEach { (agent, model) ->
                val on = agent == s.agent && model.id == s.model
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = on, enabled = remote == null, role = Role.RadioButton) { sel = ModelMenus.choose(m, s, agent, model) }
                        .padding(horizontal = Space.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { ChipIcon(chipFor(agent), size = 18.dp) }
                    Spacer(Modifier.width(Space.m))
                    Text(
                        model.name.ifBlank { model.id }, style = MaterialTheme.typography.bodyLarge, color = if (on) c.strong else c.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (on) Icon(HerdrIcons.Check, "Selected", tint = c.accent, modifier = Modifier.size(18.dp))
                }
            }
            val model = ModelMenus.model(m, s)
            val levels = model?.efforts.orEmpty()
            if (levels.isNotEmpty()) {
                MenuSection(ModelMenus.REASONING)
                SegmentedPills(
                    levels.map(ModelMenus::shortLevelName), levels.indexOf(ModelMenus.level(m, s)),
                    onSelect = { i -> if (remote == null) sel = s.copy(effort = levels[i]) },
                    modifier = Modifier.padding(horizontal = Space.m),
                )
            }
            val cli = m.menus[s.agent]
            if (model?.fast == true && cli?.fastLocked != true) {
                ActionRow(
                    ModelMenus.FAST, icon = HerdrIcons.Zap, enabled = remote == null,
                    modifier = Modifier.semantics { stateDescription = if (s.fast) "On" else "Off" },
                    trailing = { HerdrSwitch(s.fast, enabled = remote == null) },
                    onClick = { sel = s.copy(fast = !s.fast) },
                )
            }
            if (cli?.perms?.isNotEmpty() == true) {
                ActionRow(
                    ModelMenus.PERMISSIONS, icon = HerdrIcons.ShieldCheck, enabled = remote == null,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(cli.perms.firstOrNull { it.id == s.perm }?.label.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                            Icon(if (perms) HerdrIcons.ChevronUp else HerdrIcons.ChevronDown, null, tint = c.muted, modifier = Modifier.padding(start = Space.xs).size(18.dp))
                        }
                    },
                    onClick = { perms = !perms },
                )
                if (perms) cli.perms.forEach { p ->
                    ActionRow(p.label, selected = s.perm == p.id, modifier = Modifier.padding(start = Space.xl), onClick = { sel = s.copy(perm = p.id); perms = false })
                }
            }
            error?.let { Notice(it, Modifier.padding(horizontal = Space.m, vertical = Space.xs), color = c.red, icon = HerdrIcons.CircleAlert) }
            val changes = ModelMenus.changes(m, s)
            if (changes.isNotEmpty()) {
                PrimaryButton(ModelMenus.restartWords(changes), Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.m)) {
                    val level = ModelMenus.level(m, s)
                    state.run(o, "Restarting ${o.tab.name}…") { it.restartWith(o.tab.chat, s, level) }
                }
            }
        }
        Spacer(Modifier.heightIn(min = Space.m))
    }
}
