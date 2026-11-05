package com.makefunstuff.herdr.ui.manage

import com.makefunstuff.herdr.ui.design.LocalScreenChange
import com.makefunstuff.herdr.core.hc.HcMapping
import com.makefunstuff.herdr.core.board.WorkTask
import androidx.compose.material3.RadioButtonDefaults
import com.makefunstuff.herdr.ui.design.SheetTitle
import com.makefunstuff.herdr.ui.design.MenuSection
import com.makefunstuff.herdr.ui.design.KeyChip
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.theme.statusWord
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.QuietButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import com.makefunstuff.herdr.ui.design.ActionRow
import com.makefunstuff.herdr.ui.design.DangerButton
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.HerdrSheet
import com.makefunstuff.herdr.ui.design.HerdrTextField
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.PrimaryButton
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.api.NotificationResult
import com.makefunstuff.herdr.core.api.PaneDestination
import com.makefunstuff.herdr.core.api.PaneInfo
import com.makefunstuff.herdr.core.api.PaneProcessInfo
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.api.TabInfo
import com.makefunstuff.herdr.core.api.WorkspaceInfo
import com.makefunstuff.herdr.core.api.moveIndex
import com.makefunstuff.herdr.core.host.HostSession
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.makefunstuff.herdr.ui.common.copyToClipboard
import com.makefunstuff.herdr.ui.common.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs one management call and tells the person what happened: herdr's own words when it refuses
 * (a plain sentence), the toast when the call fails, a refresh so the screen catches up.
 */
fun manage(
    context: Context,
    scope: CoroutineScope,
    session: HostSession,
    onDone: () -> Unit,
    block: suspend (HerdrApi) -> String?,
) {
    scope.launch {
        val note = runCatching { block(session.awaitApi()) }
            .getOrElse { it.message ?: "herdr didn't answer" }
        session.refresh()
        if (!note.isNullOrBlank()) context.toast(note)
        onDone()
    }
}

/** The shell every management sheet sits in: a title, a one-line subtitle, and a scrolling list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageSheet(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    HerdrSheet(onDismissRequest = onDismiss) {
        SheetTitle(title, subtitle)
        Column(Modifier.fillMaxWidth().padding(start = Space.s, end = Space.s, bottom = Space.l), content = content)
    }
}

/** One row of a sheet: an optional icon, a label, an optional second line, and a tap. */
@Composable
fun ManageRow(
    label: String,
    icon: ImageVector? = null,
    detail: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    selected: Boolean = false,
    chevron: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    ActionRow(
        title = label,
        modifier = modifier,
        icon = icon,
        detail = detail,
        destructive = destructive,
        enabled = enabled,
        selected = selected,
        chevron = chevron,
        onClick = onClick,
        trailing = trailing,
    )
}

/** A heading inside a sheet, to group rows. */
@Composable
fun ManageHeading(text: String) {
    MenuSection(text)
}

/** Renames something. An empty box clears the name. */
@Composable
fun RenameDialog(
    title: String,
    initial: String,
    label: String = "Name",
    allowClear: Boolean = true,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    HerdrDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            HerdrTextField(
                value = text,
                onValueChange = { text = it },
                label = label,
                hint = if (allowClear) "Leave empty to clear the name" else null,
            )
        },
        confirmButton = { PrimaryButton("Save") { onRename(text.trim()) } },
        dismissButton = { QuietButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Confirms something that closes or removes what is there. */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    HerdrDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = { DangerButton(confirmLabel, onClick = onConfirm) },
        dismissButton = { QuietButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun pathShort(path: String?): String? =
    path?.replace(Regex("^/(home|Users)/[^/]+"), "~")

// Workspaces

@Composable
fun WorkspaceActionsSheet(
    session: HostSession,
    ws: WorkspaceInfo,
    snap: SessionSnapshot,
    meta: Map<String, String>,
    /** Opens the code of the workspace's folder. */
    onCode: (() -> Unit)? = null,
    /** Opens its only tab's actions: the machine screen shows no row for a workspace's one tab. */
    onTab: ((TabInfo) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rename by remember { mutableStateOf(false) }
    var metadata by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    val label = ws.label.ifBlank { ws.workspaceId }
    val order = snap.workspaces.map { it.workspaceId }
    val index = order.indexOf(ws.workspaceId)
    val up = moveIndex(order, ws.workspaceId, index - 1)
    val down = moveIndex(order, ws.workspaceId, index + 1)

    ManageSheet(label, "Workspace ${ws.number} · ${ws.tabCount} tab${if (ws.tabCount == 1) "" else "s"} · ${ws.paneCount} pane${if (ws.paneCount == 1) "" else "s"}", onDismiss) {
        if (!ws.focused) {
            ManageRow("Focus in herdr", icon = HerdrIcons.Focus) {
                manage(context, scope, session, onDismiss) { it.focusWorkspace(ws.workspaceId); null }
            }
        }
        if (onCode != null) ManageRow("Browse the code", icon = HerdrIcons.Code, chevron = true) { onDismiss(); onCode() }
        ManageRow("Rename", icon = HerdrIcons.Pencil, chevron = true) { rename = true }
        val onlyTab = snap.tabs.filter { it.workspaceId == ws.workspaceId }.singleOrNull()
        if (onTab != null && onlyTab != null) {
            val tabLabel = onlyTab.label.ifBlank { "${onlyTab.number}" }.let { l -> if (l.all { it.isDigit() }) "Tab $l" else l }
            ManageRow("Its tab", icon = HerdrIcons.Layers, detail = tabLabel, chevron = true) { onDismiss(); onTab(onlyTab) }
        }
        ManageRow(
            "Metadata",
            icon = HerdrIcons.Info,
            detail = meta.entries.joinToString(" · ") { "${it.key}: ${it.value}" }.ifBlank { "Nothing reported" },
            enabled = meta.isNotEmpty(),
            chevron = true,
        ) { metadata = true }
        ManageHeading("Reorder")
        ManageRow("Move up", icon = HerdrIcons.ChevronUp, detail = "Earlier in the workspace list", enabled = up != null) {
            manage(context, scope, session, onDismiss) { it.moveWorkspace(ws.workspaceId, up!!); null }
        }
        ManageRow("Move down", icon = HerdrIcons.ChevronDown, detail = "Later in the workspace list", enabled = down != null) {
            manage(context, scope, session, onDismiss) { it.moveWorkspace(ws.workspaceId, down!!); null }
        }
        ManageHeading("Danger")
        ManageRow("Close workspace", icon = HerdrIcons.Trash2, destructive = true) { confirmClose = true }
    }

    if (rename) {
        RenameDialog("Rename workspace", ws.label, onDismiss = { rename = false }) { name ->
            rename = false
            if (name != ws.label) manage(context, scope, session, onDismiss) { it.renameWorkspace(ws.workspaceId, name); null }
        }
    }
    if (metadata) {
        MetadataSheet(label, meta, onDismiss = { metadata = false })
    }
    if (confirmClose) {
        ConfirmDialog(
            "Close $label?",
            "Its tabs, panes and any agents running in them close too.",
            "Close workspace",
            onDismiss = { confirmClose = false },
        ) {
            confirmClose = false
            manage(context, scope, session, onDismiss) { it.closeWorkspace(ws.workspaceId); null }
        }
    }
}

@Composable
private fun MetadataSheet(title: String, meta: Map<String, String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ManageSheet("$title metadata", "Reported by herdr integrations", onDismiss) {
        meta.forEach { (key, value) ->
            ManageRow(key, detail = value) { context.copyToClipboard(key, value, "Copied $key") }
        }
    }
}

// Tabs

@Composable
fun TabActionsSheet(
    session: HostSession,
    tab: TabInfo,
    snap: SessionSnapshot,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rename by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    val label = tab.label.ifBlank { "${tab.number}" }.let { l -> if (l.all { it.isDigit() }) "Tab $l" else l }
    val order = snap.tabs.filter { it.workspaceId == tab.workspaceId }.map { it.tabId }
    val index = order.indexOf(tab.tabId)
    val up = moveIndex(order, tab.tabId, index - 1)
    val down = moveIndex(order, tab.tabId, index + 1)

    val panes = "${tab.paneCount} pane${if (tab.paneCount == 1) "" else "s"}"
    ManageSheet(label, if (label == "Tab ${tab.number}") panes else "Tab ${tab.number} · $panes", onDismiss) {
        if (!tab.focused) {
            ManageRow("Focus in herdr", icon = HerdrIcons.Focus) {
                manage(context, scope, session, onDismiss) { it.focusTab(tab.tabId); null }
            }
        }
        ManageRow("Rename", icon = HerdrIcons.Pencil, chevron = true) { rename = true }
        val canClose = order.size > 1
        ManageHeading("Reorder")
        ManageRow("Move left", icon = HerdrIcons.ChevronLeft, enabled = up != null) {
            manage(context, scope, session, onDismiss) { it.moveTab(tab.tabId, up!!); null }
        }
        ManageRow("Move right", icon = HerdrIcons.ChevronRight, enabled = down != null) {
            manage(context, scope, session, onDismiss) { it.moveTab(tab.tabId, down!!); null }
        }
        ManageHeading("Danger")
        ManageRow(
            "Close tab",
            icon = HerdrIcons.Trash2,
            detail = if (canClose) null else "A workspace keeps its last tab",
            destructive = true,
            enabled = canClose,
        ) { confirmClose = true }
    }

    if (rename) {
        RenameDialog("Rename tab", tab.label, onDismiss = { rename = false }) { name ->
            rename = false
            if (name != tab.label) manage(context, scope, session, onDismiss) { it.renameTab(tab.tabId, name); null }
        }
    }
    if (confirmClose) {
        ConfirmDialog(
            "Close $label?",
            "Its panes and any agents running in them close too.",
            "Close tab",
            onDismiss = { confirmClose = false },
        ) {
            confirmClose = false
            manage(context, scope, session, onDismiss) { it.closeTab(tab.tabId); null }
        }
    }
}

// Panes

private sealed interface PaneScreen {
    data object Actions : PaneScreen
    data object Move : PaneScreen
    data object Swap : PaneScreen
    data object Process : PaneScreen
}

@Composable
fun PaneActionsSheet(
    session: HostSession,
    pane: PaneInfo,
    snap: SessionSnapshot,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<PaneScreen>(PaneScreen.Actions) }
    var rename by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    val label = pane.displayName
    val layout = snap.layout(pane.tabId)
    val zoomed = layout?.zoomed == true
    val siblings = snap.panes.filter { it.tabId == pane.tabId }
    val open = siblings.size > 1
    val tabs = snap.tabs.filter { it.workspaceId == pane.workspaceId && it.tabId != pane.tabId }

    val title = when (screen) {
        PaneScreen.Move -> "Move $label"
        PaneScreen.Swap -> "Swap $label"
        PaneScreen.Process -> "Running in $label"
        PaneScreen.Actions -> label
    }
    ManageSheet(title, snap.location(pane.workspaceId, pane.tabId) + (pathShort(pane.cwd)?.let { " · $it" } ?: ""), onDismiss) {
        when (screen) {
            PaneScreen.Actions -> {
                if (!pane.focused) {
                    ManageRow("Focus in herdr", icon = HerdrIcons.Focus) {
                        manage(context, scope, session, onDismiss) { it.focusPane(pane.paneId); null }
                    }
                }
                ManageRow("Rename", icon = HerdrIcons.Pencil, chevron = true) { rename = true }
                ManageRow(
                    if (zoomed) "Unzoom" else "Zoom to fit",
                    icon = if (zoomed) HerdrIcons.Minimize2 else HerdrIcons.Maximize2,
                    detail = if (zoomed) "Show the whole layout again" else "Fill the tab with this pane",
                    enabled = open,
                ) {
                    manage(context, scope, session, onDismiss) { it.zoomPane(pane.paneId).refusal }
                }
                ManageRow("What's running", icon = HerdrIcons.Info, chevron = true) { screen = PaneScreen.Process }
                ManageRow("Clear screen and scrollback", icon = HerdrIcons.Trash2) { confirmClear = true }
                ManageHeading("Layout")
                ManageRow("Move to another tab or workspace", icon = HerdrIcons.ArrowRight, chevron = true) { screen = PaneScreen.Move }
                ManageRow("Swap with another pane", icon = HerdrIcons.ArrowLeftRight, enabled = open, chevron = true) { screen = PaneScreen.Swap }
                if (open) {
                    ManageRow("Focus left", icon = HerdrIcons.ChevronLeft) { manage(context, scope, session, onDismiss) { it.focusDirection(pane.paneId, "left").refusal } }
                    ManageRow("Focus right", icon = HerdrIcons.ChevronRight) { manage(context, scope, session, onDismiss) { it.focusDirection(pane.paneId, "right").refusal } }
                    ManageRow("Focus up", icon = HerdrIcons.ChevronUp) { manage(context, scope, session, onDismiss) { it.focusDirection(pane.paneId, "up").refusal } }
                    ManageRow("Focus down", icon = HerdrIcons.ChevronDown) { manage(context, scope, session, onDismiss) { it.focusDirection(pane.paneId, "down").refusal } }
                }
                ManageHeading("Danger")
                ManageRow("Close pane", icon = HerdrIcons.Trash2, destructive = true) { confirmClose = true }
            }

            PaneScreen.Move -> {
                ManageRow("Back", icon = HerdrIcons.ArrowLeft) { screen = PaneScreen.Actions }
                ManageHeading("Into a tab")
                if (tabs.isEmpty()) ManageRow("No other tabs", enabled = false)
                tabs.forEach { t ->
                    ManageRow(t.label.ifBlank { t.tabId }, icon = HerdrIcons.ArrowRight, detail = "Split into this tab") {
                        manage(context, scope, session, onDismiss) {
                            it.movePane(pane.paneId, PaneDestination.Tab(t.tabId)).refusal
                        }
                    }
                }
                ManageHeading("Into something new")
                ManageRow("A new tab here", icon = HerdrIcons.CirclePlus) {
                    manage(context, scope, session, onDismiss) {
                        it.movePane(pane.paneId, PaneDestination.NewTab(workspaceId = pane.workspaceId)).refusal
                    }
                }
                ManageRow("A new workspace", icon = HerdrIcons.CirclePlus) {
                    manage(context, scope, session, onDismiss) {
                        it.movePane(pane.paneId, PaneDestination.NewWorkspace()).refusal
                    }
                }
            }

            PaneScreen.Swap -> {
                ManageRow("Back", icon = HerdrIcons.ArrowLeft) { screen = PaneScreen.Actions }
                ManageHeading("With the pane next to it")
                listOf("left", "right", "up", "down").forEach { direction ->
                    ManageRow("Swap $direction", icon = arrowFor(direction)) {
                        manage(context, scope, session, onDismiss) { it.swapPane(pane.paneId, direction = direction).refusal }
                    }
                }
                ManageHeading("With a pane in this tab")
                siblings.filter { it.paneId != pane.paneId }.forEach { other ->
                    ManageRow(other.displayName, icon = HerdrIcons.ArrowLeftRight) {
                        manage(context, scope, session, onDismiss) { it.swapPane(pane.paneId, targetPaneId = other.paneId).refusal }
                    }
                }
            }

            PaneScreen.Process -> {
                ManageRow("Back", icon = HerdrIcons.ArrowLeft) { screen = PaneScreen.Actions }
                ProcessInfo(session, pane)
            }
        }
    }

    if (rename) {
        RenameDialog("Rename pane", pane.label.orEmpty(), onDismiss = { rename = false }) { name ->
            rename = false
            if (name != pane.label.orEmpty()) {
                manage(context, scope, session, onDismiss) { it.renamePane(pane.paneId, name); null }
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            "Clear $label?",
            "herdr wipes the screen and the scrollback. What is running keeps running.",
            "Clear",
            onDismiss = { confirmClear = false },
        ) {
            confirmClear = false
            manage(context, scope, session, onDismiss) { it.clearPane(pane.paneId); "Cleared." }
        }
    }
    if (confirmClose) {
        ConfirmDialog(
            "Close $label?",
            "Anything running in the pane exits.",
            "Close pane",
            onDismiss = { confirmClose = false },
        ) {
            confirmClose = false
            manage(context, scope, session, onDismiss) { it.closePane(pane.paneId); null }
        }
    }
}

@Composable
private fun ProcessInfo(session: HostSession, pane: PaneInfo) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<PaneProcessInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(pane.paneId, pane.revision) {
        loading = true
        runCatching { session.awaitApi().processInfo(pane.paneId) }
            .onSuccess { info = it }
            .onFailure { error = it.message ?: "Couldn't read the pane" }
        loading = false
    }
    if (loading) {
        LoadingState("Reading the pane")
    } else if (error != null) {
        Box(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s)) { ErrorMessage(error!!) }
    } else {
        val i = info!!
        ManageRow("Shell", detail = "pid ${i.shellPid ?: "?"}" + (i.tty?.let { " · $it" } ?: ""))
        ManageHeading("Foreground")
        if (i.processes.isEmpty()) {
            ManageRow("Nothing but the shell")
        }
        i.processes.forEach { p ->
            ManageRow(p.name, detail = listOfNotNull(p.command, pathShort(p.cwd)).joinToString(" · ")) {
                context.copyToClipboard(p.name, listOfNotNull(p.command, p.cwd).joinToString("\n"), "Copied the command")
            }
        }
    }
}

// Agents

@Composable
fun AgentActionsSheet(
    session: HostSession,
    agent: AgentInfo?,
    paneLabel: String,
    onDismiss: () -> Unit,
    onTerminal: (() -> Unit)? = null,
    onToggleKeys: (() -> Unit)? = null,
    showKeys: Boolean = false,
    onKey: ((List<String>) -> Unit)? = null,
    /** Floats the chat over other apps as a bubble. */
    onFloat: (() -> Unit)? = null,
    /** In the chat's bubble: opens it in the app. */
    onOpenApp: (() -> Unit)? = null,
    /** Opens the code of the agent's folder: its worktree, its changes. */
    onCode: (() -> Unit)? = null,
    /** Opens the timeline of what the agent did (its task's, when it works on one). */
    onActivity: (() -> Unit)? = null,
    /** The Agents list's triage of it: mark seen, pin, its space's Notify. */
    triage: (@Composable () -> Unit)? = null,
    /** The board task it works on, which names it in the title. */
    task: WorkTask? = null,
    /** Its tab's menu (Shepherdr 3's: Rename, Switch agent, Restart, Model, Close), first. */
    tabRows: (@Composable () -> Unit)? = null,
    /** It is a chat's ⋯ menu: "Change this screen" is in it. */
    screenMenu: Boolean = false,
    /** Quits the agent and opens the same conversation again ([com.makefunstuff.herdr.core.chat.AgentRestart]), asked first. */
    onRestart: (() -> Unit)? = null,
    /** The same in one tap, then tells it to continue. */
    onRestartContinue: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val target = agent?.paneId
    var rename by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var explain by remember { mutableStateOf(false) }

    val (title, subtitle) = agentTitle(agent, paneLabel, task)
    ManageSheet(title, subtitle, onDismiss) {
        if (tabRows != null) {
            tabRows()
            ManageHeading("Agent")
        }
        // herdr-core's agents have none of classic herdr's names, focus or explanations: their details only.
        if (target != null && HcMapping.address(target) == null) {
            ManageRow("Rename agent", icon = HerdrIcons.Pencil, chevron = true) { rename = true }
            ManageRow("Focus in herdr", icon = HerdrIcons.Focus) {
                manage(context, scope, session, onDismiss) { it.focusAgent(target); null }
            }
            ManageRow("Why this state?", icon = HerdrIcons.Info, chevron = true) { explain = true }
        }
        if (target != null) ManageRow("Details", icon = HerdrIcons.FileText, chevron = true) { details = true }
        triage?.invoke()
        if (onTerminal != null) ManageRow("Open terminal", icon = HerdrIcons.SquareTerminal, chevron = true) { onTerminal() }
        if (onActivity != null) ManageRow("Activity timeline", detail = "What it did, step by step: commands, edits, tests, reviews", icon = HerdrIcons.Activity, chevron = true) { onActivity() }
        if (onCode != null) ManageRow("Browse the code", detail = "Its files and changes, to read and comment on", icon = HerdrIcons.Code, chevron = true) { onCode() }
        if (onRestart != null) ManageRow("Restart", detail = "Quit it and open the same conversation again", icon = HerdrIcons.RotateCcw, chevron = true) { onRestart() }
        if (onRestartContinue != null) ManageRow("Restart and continue", detail = "The same, then tell it “${com.makefunstuff.herdr.core.chat.AgentRestart.CONTINUE}”", icon = HerdrIcons.RefreshCw) { onRestartContinue() }
        if (onFloat != null) ManageRow("Float over other apps", icon = HerdrIcons.PictureInPicture2) { onFloat() }
        if (onOpenApp != null) ManageRow("Open in the app", icon = HerdrIcons.Maximize2) { onOpenApp() }
        // The chat's own ⋯ (not a row's long press): the screen behind it can be changed.
        if (screenMenu) LocalScreenChange.current?.let { change -> ManageRow("Change this screen", icon = HerdrIcons.WandSparkles) { onDismiss(); change.open() } }
        if (onToggleKeys != null) {
            ManageRow(if (showKeys) "Hide keys" else "Show keys", icon = HerdrIcons.Keyboard) { onToggleKeys() }
        }
        if (onKey != null) {
            ManageHeading("Send keys")
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                listOf("Enter" to "enter", "Esc" to "esc", "Tab" to "tab", "Ctrl+C" to "ctrl+c").forEach { (text, key) ->
                    KeyChip(text) { onKey(listOf(key)) }
                }
            }
        }
    }

    if (rename && target != null) {
        RenameDialog("Rename agent", agent?.name.orEmpty(), onDismiss = { rename = false }) { name ->
            rename = false
            if (name != agent?.name.orEmpty()) {
                manage(context, scope, session, onDismiss) { it.renameAgent(target, name.ifBlank { null }); null }
            }
        }
    }
    if (details && target != null) {
        DetailSheet("Agent details", onDismiss = { details = false }) {
            AgentDetailRows(agent)
        }
    }
    if (explain && target != null) {
        ExplainSheet(session, target, onDismiss = { explain = false })
    }
}

/**
 * An agent's sheet named as the Agents list names it: the title is its task's (or what herdr says
 * it does, or its name), the line under it the task's key, the agent (its own name, then its kind
 * when it has one of its own) and its state: "Speed up /jobs" over "AND-12 · claude · blocked".
 */
fun agentTitle(agent: AgentInfo?, paneLabel: String, task: WorkTask?): Pair<String, String> {
    if (agent == null) return paneLabel to "No agent in this pane"
    val kind = agent.agent?.takeIf { it.isNotBlank() }
    // Its own name, unless that is its kind or its task's id.
    val named = agent.name?.takeIf { n -> n.isNotBlank() && n != kind && n != task?.id }
    val who = when {
        named != null && kind != null -> "$named ($kind)"
        else -> named ?: agent.displayName
    }
    val title = task?.title?.takeIf { it.isNotBlank() } ?: agent.subtitle?.takeIf { it != who && it != named } ?: who
    val subtitle = listOfNotNull(task?.name?.takeIf { it.isNotBlank() }, who.takeIf { it != title }, agent.statusWord().takeIf { it.isNotBlank() })
    return title to subtitle.distinct().joinToString(" · ")
}

@Composable
private fun AgentDetailRows(agent: AgentInfo?) {
    val context = LocalContext.current
    ManageRow("Name", detail = agent?.name?.takeIf { it.isNotBlank() } ?: "(none)")
    ManageRow("Agent", detail = agent?.agent ?: "(unknown)")
    ManageRow("State", detail = agent?.let { if (it.statusWord() == it.agentStatusRaw) it.statusWord() else "${it.statusWord()} (${it.agentStatusRaw})" } ?: "(unknown)")
    ManageRow("Pane", detail = agent?.paneId.orEmpty()) { context.copyToClipboard("pane id", agent?.paneId.orEmpty()) }
    ManageRow("Workspace · tab", detail = listOfNotNull(agent?.workspaceId, agent?.tabId).joinToString(" · "))
    ManageRow("Working dir", detail = pathShort(agent?.cwd) ?: "(unknown)")
    agent?.terminalId?.takeIf { it.isNotBlank() }?.let { ManageRow("Terminal", detail = it) }
    agent?.agentSession?.let { s ->
        ManageRow("Transcript", detail = "${s.agent} · ${s.kind}: ${s.value}") { context.copyToClipboard("session", s.value, "Copied the session") }
    }
    agent?.subtitle?.takeIf { it.isNotBlank() }?.let { ManageRow("Doing", detail = it) }
}

/** "Why this state?": what herdr read on the agent's screen to call it working, blocked or done. */
@Composable
fun ExplainSheet(session: HostSession, target: String, onDismiss: () -> Unit) {
    var summary by remember { mutableStateOf<String?>(null) }
    var extra by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(target) {
        runCatching { session.awaitApi().explainAgent(target) }
            .onSuccess { e ->
                summary = e.summary
                extra = buildList {
                    e.matchedRule?.let { add("Matched rule" to it.replace('_', ' ')) }
                    e.warning?.let { add("Warning" to it) }
                    e.manifest?.let { add("Integration" to it) }
                    e.rules.filter { it.matched }.forEach { rule ->
                        add(
                            rule.id.replace('_', ' ') to listOfNotNull(rule.state, rule.region.ifBlank { null }, rule.preview.ifBlank { null })
                                .joinToString(" · "),
                        )
                    }
                }
            }
            .onFailure { error = it.message ?: "herdr couldn't explain it" }
        loading = false
    }
    DetailSheet("Why this state?", onDismiss = onDismiss) {
        when {
            loading -> ManageRow("Asking herdr…")
            error != null -> Box(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s)) { ErrorMessage(error!!) }
            else -> {
                Text(
                    summary.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                extra.forEach { (k, v) -> ManageRow(k, detail = v) }
            }
        }
    }
}

/** A read-only sheet with a title and however many rows the caller wants. */
@Composable
private fun DetailSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ManageSheet(title, null, onDismiss) { content() }
}

private fun arrowFor(direction: String): ImageVector = when (direction) {
    "left" -> HerdrIcons.ChevronLeft
    "right" -> HerdrIcons.ChevronRight
    "up" -> HerdrIcons.ChevronUp
    else -> HerdrIcons.ChevronDown
}
