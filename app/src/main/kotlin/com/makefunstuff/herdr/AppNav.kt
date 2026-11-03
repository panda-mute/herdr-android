package com.makefunstuff.herdr

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.makefunstuff.herdr.core.home.Waiting
import com.makefunstuff.herdr.data.IdentityStore
import com.makefunstuff.herdr.ui.activity.ActivityScreen
import com.makefunstuff.herdr.ui.admin.AdminScreen
import com.makefunstuff.herdr.ui.agents.AgentScreen
import com.makefunstuff.herdr.ui.agents.AgentsScreen
import com.makefunstuff.herdr.ui.agents.AlertPill
import com.makefunstuff.herdr.ui.board.BoardScreen
import com.makefunstuff.herdr.ui.board.BoardsScreen
import com.makefunstuff.herdr.ui.board.rememberBoards
import com.makefunstuff.herdr.ui.board.rememberNeedsYou
import com.makefunstuff.herdr.ui.board.ReviewScreen
import com.makefunstuff.herdr.ui.brief.BriefScreen
import com.makefunstuff.herdr.ui.brief.BriefSettingsScreen
import com.makefunstuff.herdr.ui.code.CheckoutScreen
import com.makefunstuff.herdr.ui.code.CheckoutTab
import com.makefunstuff.herdr.ui.code.CodeMachinesScreen
import com.makefunstuff.herdr.ui.code.CodeReposScreen
import com.makefunstuff.herdr.ui.code.CodeSpot
import com.makefunstuff.herdr.ui.code.CommitScreen
import com.makefunstuff.herdr.ui.code.DiffScreen
import com.makefunstuff.herdr.ui.code.FileScreen
import com.makefunstuff.herdr.ui.code.NotesScreen
import com.makefunstuff.herdr.ui.code.SearchScreen
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.coordinator.CoordinatorScreen
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrNavBar
import com.makefunstuff.herdr.ui.design.NavItem
import com.makefunstuff.herdr.ui.health.HealthScreen
import com.makefunstuff.herdr.ui.layout.LayoutScreen
import com.makefunstuff.herdr.ui.machines.EditMachineScreen
import com.makefunstuff.herdr.ui.machines.MachineScreen
import com.makefunstuff.herdr.ui.machines.MachinesScreen
import com.makefunstuff.herdr.ui.settings.KeySetupDialog
import com.makefunstuff.herdr.ui.settings.QuietHoursScreen
import com.makefunstuff.herdr.ui.settings.SettingsScreen
import com.makefunstuff.herdr.ui.share.AskScreen
import com.makefunstuff.herdr.ui.terminal.TerminalScreen
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.HerdrTheme
import com.makefunstuff.herdr.ui.worktrees.WorktreesScreen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** The whole app: theme, container, navigation and toasts. [nav] and [dark] are given by the desktop preview's scenes. */
@Composable
fun HerdrRoot(container: AppContainer, dark: Boolean = isSystemInDarkTheme(), nav: NavHostController = rememberNavController()) {
    CompositionLocalProvider(LocalContainer provides container) {
        HerdrTheme(dark) {
            Box {
                com.makefunstuff.herdr.ui.common.GuardHost(container.guard) { AppNav(nav) }
                com.makefunstuff.herdr.ui.design.ToastHost()
            }
        }
    }
}

/** Screens to open from outside the app's own navigation: a tapped notification, the tile, a widget, a share. Each is opened once. */
object Links {
    private val routes = Channel<String>(Channel.BUFFERED)

    fun open(route: String) {
        routes.trySend(route)
    }

    val flow get() = routes.receiveAsFlow()
}

object Routes {
    const val AGENTS = "agents"
    /** The Board tab: every machine's tasks in one list. */
    const val BOARDS = "boards"
    const val MACHINES = "machines"
    const val SETTINGS = "settings"
    const val QUIET = "settings/quiet"
    /** Which actions ask for the fingerprint, and the app lock. */
    const val GUARD = "settings/guard"
    /** The quick replies over an agent's message box. */
    const val COMPOSER = "settings/replies"
    /** Which notifications reach the phone, holding them while you are at a machine, and the watches. */
    const val RULES = "settings/rules"
    /** What happened while you were away; and when it comes as a notification. */
    const val BRIEF = "brief"
    const val BRIEF_SETTINGS = "settings/brief"
    const val MACHINE = "machine/{id}"
    const val EDIT = "edit?id={id}"
    const val AGENT = "agent/{mid}/{pane}"
    /** The steps of subagent [agent] that the agent in [pane] started; [title] says what it was asked. */
    const val SUBAGENT = "subagent/{mid}/{pane}/{agent}?title={title}"
    const val TERMINAL = "terminal/{mid}/{pane}"
    const val LAYOUT = "layout/{mid}/{pane}"
    /** A machine's Coordinator; on Shepherdr 2, [space]'s (its id), else its home space's. */
    const val COORDINATOR = "coordinator/{mid}?space={space}"
    /** One machine's board; with [task] open, and with `alone` closing the task goes back instead of to the board. */
    const val BOARD = "board/{mid}?task={task}&alone={alone}"
    /** Finished work across every board, one card a task; opened at a machine's (and a task's) card when given. */
    const val REVIEW = "review?mid={mid}&task={task}"
    const val ADMIN = "admin/{mid}?tab={tab}"
    const val WORKTREES = "worktrees/{mid}?ws={ws}"
    const val HEALTH = "health/{mid}"
    /** A machine's AI providers: limits, spend, balances, and what used them. */
    const val USAGE = "usage/{mid}"
    const val LOGINS = "logins/{mid}"
    /** From outside the app (the tile, the widgets, a share): see [com.makefunstuff.herdr.ui.share.Ask]. */
    const val ASK = "ask/{to}"
    /** The code on the machines: which machine, then its repositories and checkouts; then one checkout and what is in it. */
    const val CODE = "code"
    const val CODE_REPOS = "code/{mid}"
    const val CODE_AT = "code/{mid}/at?${CodeSpot.ARGS}&tab={tab}"
    const val CODE_FILE = "code/{mid}/file?${CodeSpot.ARGS}&path={path}&line={line}"
    const val CODE_SEARCH = "code/{mid}/search?${CodeSpot.ARGS}&q={q}"
    const val CODE_DIFF = "code/{mid}/diff?${CodeSpot.ARGS}&src={src}&file={file}"
    const val CODE_COMMIT = "code/{mid}/commit?${CodeSpot.ARGS}&sha={sha}"
    const val CODE_NOTES = "code/{mid}/notes?${CodeSpot.ARGS}"
    /** One box for agents, tasks, machines, workspaces and chats; from a machine ([mid]) only its own, at first. */
    const val SEARCH = "search?mid={mid}&q={q}"
    /** Start work from a sentence: what, then how and where; on [mid] and with [text] when given. */
    const val START = "start?mid={mid}&text={text}"
    /** What an agent did, as a timeline: a board task's (its key as [name]), or one agent's (its pane). */
    const val ACTIVITY = "activity/{mid}?task={task}&name={name}&pane={pane}"
    /** A machine's quick-command runs, with one open ([quickRuns]'s run, from its notification). */
    const val QUICK = "quick/{mid}?run={run}"
    /** A workspace's dev servers, one open in the in-app browser through SSH ([port] first when given). */
    const val PREVIEW = "preview/{mid}?ws={ws}&port={port}"
    /** The board's automations as routines: one machine's ([mid]), or every connected board's. */
    const val ROUTINES = "routines?mid={mid}"
    /** A Shepherdr space's artifacts ([space], its id; every one on the machine without). */
    const val ARTIFACTS = "artifacts/{mid}?space={space}"
    /** One artifact full screen, at its latest or at version [v]. */
    const val ARTIFACT = "artifact/{mid}/{id}?v={v}"

    fun machine(id: String) = "machine/$id"
    fun edit(id: String? = null) = if (id == null) "edit" else "edit?id=$id"
    fun agent(mid: String, pane: String) = "agent/$mid/${Uri.encode(pane)}"
    fun subagent(mid: String, pane: String, agent: String, title: String) =
        "subagent/$mid/${Uri.encode(pane)}/${Uri.encode(agent)}?title=" + Uri.encode(title)
    fun terminal(mid: String, pane: String) = "terminal/$mid/${Uri.encode(pane)}"
    fun layout(mid: String, pane: String) = "layout/$mid/${Uri.encode(pane)}"
    fun coordinator(mid: String, space: String? = null) = "coordinator/$mid" + (space?.let { "?space=" + Uri.encode(it) } ?: "")
    fun ask(to: String) = "ask/$to"
    fun artifacts(mid: String, space: String? = null) = "artifacts/$mid" + (space?.let { "?space=" + Uri.encode(it) } ?: "")
    fun artifact(mid: String, id: String, v: Int? = null) = "artifact/$mid/${Uri.encode(id)}" + (v?.let { "?v=$it" } ?: "")
    fun board(mid: String, task: String? = null, alone: Boolean = false) =
        if (task == null) "board/$mid" else "board/$mid?task=${Uri.encode(task)}" + if (alone) "&alone=1" else ""
    fun review(mid: String? = null, task: String? = null) =
        "review" + listOfNotNull(mid?.let { "mid=" + Uri.encode(it) }, task?.let { "task=" + Uri.encode(it) }).joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
    fun admin(mid: String, tab: Int = 0) = "admin/$mid?tab=$tab"
    fun health(mid: String) = "health/$mid"
    fun usage(mid: String) = "usage/$mid"
    fun logins(mid: String) = "logins/$mid"
    fun codeRepos(mid: String) = "code/$mid"
    fun codeAt(spot: CodeSpot, tab: CheckoutTab? = null) = "code/${spot.machine}/at?${spot.query()}" + (tab?.let { "&tab=${it.name}" } ?: "")
    fun codeFile(spot: CodeSpot, path: String, line: Int? = null) =
        "code/${spot.machine}/file?${spot.query()}&path=${Uri.encode(path)}" + (line?.let { "&line=$it" } ?: "")
    fun codeSearch(spot: CodeSpot, query: String? = null) = "code/${spot.machine}/search?${spot.query()}" + (query?.let { "&q=${Uri.encode(it)}" } ?: "")
    fun codeDiff(spot: CodeSpot, src: String, file: String) =
        "code/${spot.machine}/diff?${spot.query()}&src=${Uri.encode(src)}&file=${Uri.encode(file)}"
    fun codeCommit(spot: CodeSpot, sha: String) = "code/${spot.machine}/commit?${spot.query()}&sha=$sha"
    fun codeNotes(spot: CodeSpot) = "code/${spot.machine}/notes?${spot.query()}"
    fun activity(mid: String, task: String? = null, name: String? = null, pane: String? = null) =
        "activity/$mid" + listOfNotNull(task?.let { "task=" + Uri.encode(it) }, name?.let { "name=" + Uri.encode(it) }, pane?.let { "pane=" + Uri.encode(it) })
            .joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
    fun preview(mid: String, workspaceId: String, port: Int? = null) =
        "preview/$mid?ws=${Uri.encode(workspaceId)}" + (port?.let { "&port=$it" } ?: "")
    fun search(mid: String? = null, q: String? = null) =
        "search" + listOfNotNull(mid?.let { "mid=" + Uri.encode(it) }, q?.let { "q=" + Uri.encode(it) }).joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
    fun start(mid: String? = null, text: String? = null) =
        "start" + listOfNotNull(mid?.let { "mid=" + Uri.encode(it) }, text?.let { "text=" + Uri.encode(it) }).joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
    fun routines(mid: String? = null) = if (mid == null) "routines" else "routines?mid=${Uri.encode(mid)}"
    fun quickRuns(mid: String, run: String? = null) = if (run == null) "quick/$mid" else "quick/$mid?run=${Uri.encode(run)}"
    fun worktrees(mid: String, workspaceId: String? = null) =
        if (workspaceId == null) "worktrees/$mid" else "worktrees/$mid?ws=${Uri.encode(workspaceId)}"

    /** The machine and the task of a [board] route; null for any other route. */
    fun boardOf(route: String): Pair<String, String?>? {
        if (!route.startsWith("board/")) return null
        val rest = route.removePrefix("board/")
        return rest.substringBefore('?') to rest.substringAfter("?task=", "").substringBefore('&').takeIf { it.isNotEmpty() }?.let(Uri::decode)
    }
}

@Composable
private fun AppNav(nav: NavHostController) {
    val container = LocalContainer.current
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val topLevel = route in setOf(Routes.AGENTS, Routes.BOARDS, Routes.MACHINES)
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val triage by container.triage.state.collectAsStateWithLifecycle()
    val homeBoards = rememberBoards()
    // Both tabs' badge: everything on the Needs you list, Shepherdr 2's tasks too.
    val waiting = rememberNeedsYou(homeBoards)
    val needsYou = waiting.size
    val keyState by container.identity.state.collectAsStateWithLifecycle()
    var keySetupDismissed by rememberSaveable { mutableStateOf(false) }
    // The agents list's machine filter and scroll, kept here: the list moves between the phone layout and the left pane.
    val agentsFilter = rememberSaveable { mutableStateOf<String?>(null) }
    val agentsList = rememberLazyListState()
    val chosenBoard = rememberSaveable(saver = BoardChoice.Saver) { BoardChoice() }
    // The right pane's board keeps its search, filters and open task while a narrower window hides it.
    val boardStates = rememberSaveableStateHolder()

    // No usable key yet: offer to restore one before a new key is made.
    if (!keySetupDismissed && (keyState == IdentityStore.State.Missing || keyState is IdentityStore.State.Unreadable)) {
        KeySetupDialog(
            unreadable = (keyState as? IdentityStore.State.Unreadable)?.reason,
            onDismiss = { keySetupDismissed = true },
        )
    }

    // The window's width, not the screen's: a tablet in split screen or a resized desktop window counts as what it shows.
    // Ctrl+K from a hardware keyboard opens the search, from wherever the app is (once: not over itself).
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Herdr.colors.ground).onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.isCtrlPressed && e.key == Key.K) {
                if (nav.currentDestination?.route != Routes.SEARCH) nav.navigate(Routes.search())
                true
            } else false
        },
    ) {
        val wide = maxWidth >= WIDE
        val boards = hosts.filter { it.hasWorkBoard }.map { it.machine.id }
        // A board already open in the middle (it was open when the window grew) isn't shown twice.
        val board = if (wide && route != Routes.BOARD) chosenBoard.machine?.takeIf { it in boards } ?: boards.firstOrNull() else null
        val panes = remember(wide, board, chosenBoard) { Panes(wide, board, chosenBoard) }
        val (leftWidth, rightWidth) = Panes.widths(maxWidth, board != null)
        val current by rememberUpdatedState(panes)
        LaunchedEffect(nav) { Links.flow.collect { current.link(nav, it) } }

        CompositionLocalProvider(LocalPanes provides panes) {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                containerColor = Herdr.colors.ground,
                bottomBar = { if (topLevel && !wide) AppNavBar(nav, needsYou) },
            ) { padding ->
                // One Row in both layouts, with the NavHost always in the same place in it, so a rotation or a
                // resize across the line keeps every open screen as it was (the activity isn't recreated for them).
                Row(
                    Modifier.fillMaxSize()
                        .then(if (wide) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)) else Modifier),
                ) {
                    if (wide) {
                        Column(Modifier.width(leftWidth).fillMaxHeight()) {
                            Box(Modifier.weight(1f)) { AgentsScreen(nav, agentsFilter, agentsList) }
                            AppNavBar(nav, needsYou)
                        }
                        PaneEdge()
                    }
                    NavHost(
                        nav,
                        startDestination = Routes.AGENTS,
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(
                            bottom = when {
                                !topLevel -> 0.dp
                                // Board and Machines in the middle: the nav bar is under the list, only the system's bar is below them.
                                wide -> WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                                else -> padding.calculateBottomPadding()
                            },
                        ),
                        // Short and quiet: a detail slides in a little from the side, tabs cross-fade.
                        enterTransition = { fadeIn(tween(200)) + slideInHorizontally(tween(220)) { it / 12 } },
                        exitTransition = { fadeOut(tween(150)) },
                        popEnterTransition = { fadeIn(tween(200)) },
                        popExitTransition = { fadeOut(tween(150)) + slideOutHorizontally(tween(200)) { it / 12 } },
                    ) {
                        screens(nav) {
                            // On a wide window the list is on the left already; the middle waits for what it opens.
                            if (LocalPanes.current.wide) NothingOpen() else AgentsScreen(nav, agentsFilter, agentsList)
                        }
                    }
                    if (board != null) {
                        PaneEdge()
                        Box(Modifier.width(rightWidth).fillMaxHeight()) {
                            boardStates.SaveableStateProvider("board/$board") { BoardScreen(nav, board, pane = chosenBoard) }
                        }
                    }
                }
            }
            // Another agent needs you while you are in a chat: one line drops from the top.
            AlertPill(
                // A muted agent asking doesn't drop a line either; its card still waits on the list.
                nav, panes, entry, remember(waiting, triage) { waiting.filter { !(it is Waiting.Question && it.agent.key in triage.muted) } },
                agentsRead = remember(hosts) { hosts.filter { it.agentsKnown }.map { it.machine.id }.toSet() },
                boardsRead = remember(homeBoards) { homeBoards.map { it.machineId }.toSet() },
                fired = container.watchWork.fired,
            )
        }
    }
}

/** Every screen of the app, by its [Routes] entry; [agents] is the Agents tab, which the layout around it places. */
internal fun NavGraphBuilder.screens(nav: NavHostController, agents: @Composable () -> Unit) {
    composable(Routes.AGENTS) { agents() }
    composable(Routes.BOARDS) { BoardsScreen(nav) }
    composable(Routes.MACHINES) { MachinesScreen(nav) }
    composable(Routes.SETTINGS) { SettingsScreen(nav) }
    composable(Routes.QUIET) { QuietHoursScreen(nav) }
    composable(Routes.GUARD) { com.makefunstuff.herdr.ui.settings.GuardScreen(nav) }
    composable(Routes.COMPOSER) { com.makefunstuff.herdr.ui.settings.QuickRepliesScreen(nav) }
    composable(Routes.RULES) { com.makefunstuff.herdr.ui.settings.AlertRulesScreen(nav) }
    composable(Routes.BRIEF) { BriefScreen(nav) }
    composable(Routes.BRIEF_SETTINGS) { BriefSettingsScreen(nav) }
    composable(Routes.MACHINE) { MachineScreen(nav, it.arguments?.getString("id").orEmpty()) }
    composable(Routes.EDIT) { EditMachineScreen(nav, it.arguments?.getString("id")) }
    composable(Routes.AGENT) {
        AgentScreen(nav, it.arguments?.getString("mid").orEmpty(), Uri.decode(it.arguments?.getString("pane").orEmpty()))
    }
    composable(Routes.SUBAGENT) {
        com.makefunstuff.herdr.ui.agents.SubagentScreen(
            nav,
            it.arguments?.getString("mid").orEmpty(),
            Uri.decode(it.arguments?.getString("pane").orEmpty()),
            Uri.decode(it.arguments?.getString("agent").orEmpty()),
            it.arguments?.getString("title")?.let(Uri::decode).orEmpty(),
        )
    }
    composable(Routes.ADMIN) {
        AdminScreen(nav, it.arguments?.getString("mid").orEmpty(), it.arguments?.getString("tab")?.toIntOrNull() ?: 0)
    }
    composable(Routes.WORKTREES) {
        WorktreesScreen(nav, it.arguments?.getString("mid").orEmpty(), it.arguments?.getString("ws")?.let(Uri::decode))
    }
    composable(Routes.HEALTH) { HealthScreen(nav, it.arguments?.getString("mid").orEmpty()) }
    composable(Routes.USAGE) { com.makefunstuff.herdr.ui.usage.UsageScreen(nav, it.arguments?.getString("mid").orEmpty()) }
    composable(Routes.LOGINS) { com.makefunstuff.herdr.ui.logins.LoginsScreen(nav, it.arguments?.getString("mid").orEmpty()) }
    composable(Routes.BOARD) {
        BoardScreen(nav, it.arguments?.getString("mid").orEmpty(), it.arguments?.getString("task")?.let(Uri::decode), alone = it.arguments?.getString("alone") == "1")
    }
    composable(Routes.REVIEW) {
        ReviewScreen(nav, it.arguments?.getString("mid")?.let(Uri::decode)?.ifEmpty { null }, it.arguments?.getString("task")?.let(Uri::decode)?.ifEmpty { null })
    }
    composable(Routes.ASK) { AskScreen(nav, it.arguments?.getString("to").orEmpty()) }
    composable(Routes.CODE) { CodeMachinesScreen(nav) }
    composable(Routes.CODE_REPOS) { CodeReposScreen(nav, it.arguments?.getString("mid").orEmpty()) }
    composable(Routes.CODE_AT) {
        val tab = CheckoutTab.entries.firstOrNull { t -> t.name == it.arguments?.getString("tab") } ?: CheckoutTab.Files
        CheckoutScreen(nav, it.codeSpot(), tab)
    }
    composable(Routes.CODE_FILE) {
        FileScreen(nav, it.codeSpot(), it.arguments?.getString("path")?.let(Uri::decode).orEmpty(), it.arguments?.getString("line")?.toIntOrNull())
    }
    composable(Routes.CODE_SEARCH) { SearchScreen(nav, it.codeSpot(), it.arguments?.getString("q")?.let(Uri::decode).orEmpty()) }
    composable(Routes.CODE_DIFF) {
        DiffScreen(nav, it.codeSpot(), it.arguments?.getString("src")?.let(Uri::decode).orEmpty(), it.arguments?.getString("file")?.let(Uri::decode).orEmpty())
    }
    composable(Routes.CODE_COMMIT) { CommitScreen(nav, it.codeSpot(), it.arguments?.getString("sha").orEmpty()) }
    composable(Routes.CODE_NOTES) { NotesScreen(nav, it.codeSpot()) }
    composable(Routes.QUICK) {
        com.makefunstuff.herdr.ui.quick.QuickRunsScreen(nav, it.arguments?.getString("mid").orEmpty(), it.arguments?.getString("run")?.let(Uri::decode)?.ifEmpty { null })
    }
    composable(Routes.ACTIVITY) {
        ActivityScreen(
            nav, it.arguments?.getString("mid").orEmpty(),
            taskId = it.arguments?.getString("task")?.let(Uri::decode)?.ifEmpty { null },
            taskName = it.arguments?.getString("name")?.let(Uri::decode)?.ifEmpty { null },
            paneId = it.arguments?.getString("pane")?.let(Uri::decode)?.ifEmpty { null },
        )
    }
    composable(Routes.COORDINATOR) {
        CoordinatorScreen(nav, it.arguments?.getString("mid").orEmpty(), it.arguments?.getString("space")?.let(Uri::decode)?.ifEmpty { null })
    }
    composable(Routes.ARTIFACTS) {
        com.makefunstuff.herdr.ui.artifacts.ArtifactsScreen(nav, it.arguments?.getString("mid").orEmpty(), it.arguments?.getString("space")?.let(Uri::decode)?.ifEmpty { null })
    }
    composable(Routes.ARTIFACT) {
        com.makefunstuff.herdr.ui.artifacts.ArtifactScreen(
            nav, it.arguments?.getString("mid").orEmpty(), it.arguments?.getString("id")?.let(Uri::decode).orEmpty(),
            v = it.arguments?.getString("v")?.toIntOrNull(),
        )
    }
    composable(Routes.PREVIEW) {
        com.makefunstuff.herdr.ui.preview.DevServerScreen(
            nav, it.arguments?.getString("mid").orEmpty(), it.arguments?.getString("ws")?.let(Uri::decode).orEmpty(),
            startPort = it.arguments?.getString("port")?.toIntOrNull(),
        )
    }
    composable(Routes.ROUTINES) {
        com.makefunstuff.herdr.ui.routines.RoutinesScreen(nav, it.arguments?.getString("mid")?.let(Uri::decode)?.ifEmpty { null })
    }
    composable(Routes.SEARCH) {
        com.makefunstuff.herdr.ui.search.SearchScreen(
            nav, it.arguments?.getString("mid")?.let(Uri::decode)?.ifEmpty { null }, it.arguments?.getString("q")?.let(Uri::decode).orEmpty(),
        )
    }
    composable(Routes.START) {
        com.makefunstuff.herdr.ui.start.StartWorkScreen(
            nav, it.arguments?.getString("mid")?.let(Uri::decode)?.ifEmpty { null }, it.arguments?.getString("text")?.let(Uri::decode).orEmpty(),
        )
    }
    composable(Routes.TERMINAL) {
        TerminalScreen(nav, it.arguments?.getString("mid").orEmpty(), Uri.decode(it.arguments?.getString("pane").orEmpty()))
    }
    composable(Routes.LAYOUT) {
        LayoutScreen(nav, it.arguments?.getString("mid").orEmpty(), Uri.decode(it.arguments?.getString("pane").orEmpty()))
    }
}

/** The code browser's place, from a code route's arguments. */
private fun androidx.navigation.NavBackStackEntry.codeSpot(): CodeSpot =
    CodeSpot.of(arguments?.getString("mid").orEmpty()) { arguments?.getString(it) }

/**
 * The three top-level screens: under the whole screen on a phone, under the agents list on a wide
 * window. [needsYou] counts the Needs you list, on Agents and Board alike.
 */
@Composable
private fun AppNavBar(nav: NavHostController, needsYou: Int) {
    val entry by nav.currentBackStackEntryAsState()
    // In the middle of a wide window a chat or a machine may be open: the tab is the one it was opened from.
    val tab = remember(entry) {
        listOf(Routes.BOARDS, Routes.MACHINES).firstOrNull { runCatching { nav.getBackStackEntry(it) }.isSuccess } ?: Routes.AGENTS
    }
    HerdrNavBar {
        NavItem("Agents", HerdrIcons.House, tab == Routes.AGENTS, badge = needsYou) { nav.goTop(Routes.AGENTS) }
        NavItem("Board", HerdrIcons.SquareKanban, tab == Routes.BOARDS, badge = needsYou) { nav.goTop(Routes.BOARDS) }
        NavItem("Machines", HerdrIcons.List, tab == Routes.MACHINES) { nav.goTop(Routes.MACHINES) }
    }
}

/** The hairline between two panes. */
@Composable
private fun PaneEdge() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(Herdr.colors.line))
}

/** The middle of a wide window before anything is opened from the list. */
@Composable
private fun NothingOpen() {
    EmptyState(
        "No chat open",
        "Choose an agent on the left to open its chat here. The Coordinator, terminals and machines open here too.",
        icon = HerdrIcons.MessagesSquare,
    )
}

private fun NavHostController.goTop(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
