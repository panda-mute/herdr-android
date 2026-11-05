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
import androidx.compose.foundation.layout.consumeWindowInsets
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
import com.makefunstuff.herdr.core.push.Notify
import com.makefunstuff.herdr.data.IdentityStore
import com.makefunstuff.herdr.ui.activity.ActivityScreen
import com.makefunstuff.herdr.ui.agents.AgentScreen
import com.makefunstuff.herdr.ui.agents.AlertPill
import com.makefunstuff.herdr.ui.agents.TasksScreen
import com.makefunstuff.herdr.ui.design.LocalBarDrawer
import com.makefunstuff.herdr.ui.spaces.ArtifactsTab
import com.makefunstuff.herdr.ui.spaces.ChatTab
import com.makefunstuff.herdr.ui.spaces.LocalSpaceChoice
import com.makefunstuff.herdr.ui.spaces.SpaceChoice
import com.makefunstuff.herdr.ui.spaces.SpaceSlot
import com.makefunstuff.herdr.ui.spaces.SpacesDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.launch
import com.makefunstuff.herdr.ui.board.BoardScreen
import com.makefunstuff.herdr.ui.change.ScreenChangeHost
import com.makefunstuff.herdr.ui.board.rememberBoards
import com.makefunstuff.herdr.ui.board.rememberNeedsYou
import com.makefunstuff.herdr.ui.brief.BriefScreen
import com.makefunstuff.herdr.ui.brief.BriefSettingsScreen
import com.makefunstuff.herdr.ui.code.CheckoutScreen
import com.makefunstuff.herdr.ui.code.CheckoutTab
import com.makefunstuff.herdr.ui.code.CodeSpot
import com.makefunstuff.herdr.ui.code.CommitScreen
import com.makefunstuff.herdr.ui.code.DiffScreen
import com.makefunstuff.herdr.ui.code.FileScreen
import com.makefunstuff.herdr.ui.code.NotesScreen
import com.makefunstuff.herdr.ui.code.SearchScreen
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.coordinator.CoordinatorScreen
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrNavBar
import com.makefunstuff.herdr.ui.design.NavItem
import com.makefunstuff.herdr.ui.health.HealthScreen
import com.makefunstuff.herdr.ui.machines.EditMachineScreen
import com.makefunstuff.herdr.ui.machines.MachineScreen
import com.makefunstuff.herdr.ui.settings.KeySetupDialog
import com.makefunstuff.herdr.ui.settings.QuietHoursScreen
import com.makefunstuff.herdr.ui.settings.SettingsScreen
import com.makefunstuff.herdr.ui.share.AskScreen
import com.makefunstuff.herdr.ui.terminal.TerminalScreen
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.HerdrTheme
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
    /** The Tasks tab: what needs you, then every machine's tasks and agents by status (3.1's Tasks list). */
    const val TASKS = "tasks"
    /** The Chat tab: the space chosen in the drawer, its Assistant's or its agent's chat. */
    const val CHAT = "chat"
    /** The Artifacts tab: the shown space's artifacts. */
    const val ARTIFACTS_TAB = "artifacts"
    const val SETTINGS = "settings"
    const val QUIET = "settings/quiet"
    /** Which actions ask for the fingerprint, and the app lock. */
    const val GUARD = "settings/guard"
    /** The quick replies over an agent's message box. */
    const val COMPOSER = "settings/replies"
    /** Which notifications reach the phone, holding them while you are at a machine, and the watches. */
    const val RULES = "settings/rules"
    /** What happened while you were away (the scheduled brief's notification opens it; set up in Settings). */
    const val BRIEF = "brief"
    const val BRIEF_SETTINGS = "settings/brief"
    /** Settings › Change the app: the machine that builds it, what it has for that, and the update it built. */
    const val CHANGE_APP = "settings/change"
    /** Settings › Autopilot on one machine: its heartbeat, schedule and events, the same in each of its spaces. */
    const val AUTOPILOT = "settings/autopilot/{mid}"
    const val MACHINE = "machine/{id}"
    const val EDIT = "edit?id={id}"
    const val AGENT = "agent/{mid}/{pane}"
    /** The steps of subagent [agent] that the agent in [pane] started; [title] says what it was asked. */
    const val SUBAGENT = "subagent/{mid}/{pane}/{agent}?title={title}"
    const val TERMINAL = "terminal/{mid}/{pane}"
    /** A machine's Assistant: [space]'s (its id), else its home space's. */
    const val COORDINATOR = "coordinator/{mid}?space={space}"
    /** One machine's board; with [task] open, and with `alone` closing the task goes back instead of to the board. */
    const val BOARD = "board/{mid}?task={task}&alone={alone}&accept={accept}"
    const val HEALTH = "health/{mid}"
    /** A machine's AI providers: limits, spend, balances, and what used them. */
    const val USAGE = "usage/{mid}"
    const val LOGINS = "logins/{mid}"
    /** From outside the app (the tile, the widgets, a share): see [com.makefunstuff.herdr.ui.share.Ask]. */
    const val ASK = "ask/{to}"
    /** One checkout on a machine and what is in it (a task's or an agent's folder): its files, changes and history. */
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
    /** What an agent did, as a timeline: its own session (its pane). */
    const val ACTIVITY = "activity/{mid}?pane={pane}"
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
    fun coordinator(mid: String, space: String? = null) = "coordinator/$mid" + (space?.let { "?space=" + Uri.encode(it) } ?: "")
    fun ask(to: String) = "ask/$to"
    fun artifacts(mid: String, space: String? = null) = "artifacts/$mid" + (space?.let { "?space=" + Uri.encode(it) } ?: "")
    fun artifact(mid: String, id: String, v: Int? = null) = "artifact/$mid/${Uri.encode(id)}" + (v?.let { "?v=$it" } ?: "")
    fun board(mid: String, task: String? = null, alone: Boolean = false, accept: Boolean = false) =
        if (task == null) "board/$mid" else "board/$mid?task=${Uri.encode(task)}" + (if (alone) "&alone=1" else "") + if (accept) "&accept=1" else ""
    fun health(mid: String) = "health/$mid"
    fun usage(mid: String) = "usage/$mid"
    fun autopilot(mid: String) = "settings/autopilot/$mid"
    fun logins(mid: String) = "logins/$mid"
    fun codeAt(spot: CodeSpot, tab: CheckoutTab? = null) = "code/${spot.machine}/at?${spot.query()}" + (tab?.let { "&tab=${it.name}" } ?: "")
    fun codeFile(spot: CodeSpot, path: String, line: Int? = null) =
        "code/${spot.machine}/file?${spot.query()}&path=${Uri.encode(path)}" + (line?.let { "&line=$it" } ?: "")
    fun codeSearch(spot: CodeSpot, query: String? = null) = "code/${spot.machine}/search?${spot.query()}" + (query?.let { "&q=${Uri.encode(it)}" } ?: "")
    fun codeDiff(spot: CodeSpot, src: String, file: String) =
        "code/${spot.machine}/diff?${spot.query()}&src=${Uri.encode(src)}&file=${Uri.encode(file)}"
    fun codeCommit(spot: CodeSpot, sha: String) = "code/${spot.machine}/commit?${spot.query()}&sha=$sha"
    fun codeNotes(spot: CodeSpot) = "code/${spot.machine}/notes?${spot.query()}"
    fun activity(mid: String, pane: String) = "activity/$mid?pane=" + Uri.encode(pane)
    fun search(mid: String? = null, q: String? = null) =
        "search" + listOfNotNull(mid?.let { "mid=" + Uri.encode(it) }, q?.let { "q=" + Uri.encode(it) }).joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
    fun start(mid: String? = null, text: String? = null) =
        "start" + listOfNotNull(mid?.let { "mid=" + Uri.encode(it) }, text?.let { "text=" + Uri.encode(it) }).joinToString("&").let { if (it.isEmpty()) "" else "?$it" }

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
    val topLevel = route in setOf(Routes.CHAT, Routes.TASKS, Routes.ARTIFACTS_TAB)
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val triage by container.triage.state.collectAsStateWithLifecycle()
    val push by container.push.state.collectAsStateWithLifecycle()
    val homeBoards = rememberBoards()
    // The Tasks tab's badge: everything on its Needs you list, Shepherdr 3's tasks too.
    val waiting = rememberNeedsYou(homeBoards)
    val needsYou = waiting.size
    val keyState by container.identity.state.collectAsStateWithLifecycle()
    var keySetupDismissed by rememberSaveable { mutableStateOf(false) }
    // The Tasks list's scroll, kept here: it stays as the window crosses the wide line.
    val tasksList = rememberLazyListState()
    val space = rememberSaveable(saver = SpaceChoice.Saver) { SpaceChoice() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

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
        val panes = remember(wide) { Panes(wide) }
        val current by rememberUpdatedState(panes)
        LaunchedEffect(nav) { Links.flow.collect { current.link(nav, it) } }
        // A space picked in the drawer shows in the Chat tab.
        fun pick(slot: SpaceSlot) {
            space.key = slot.key
            nav.goTop(Routes.CHAT)
        }
        val openDrawer: () -> Unit = { scope.launch { drawer.open() } }
        val lineColor = Herdr.colors.lineStrong
        val sideWidth = Panes.sidebar(maxWidth)

        CompositionLocalProvider(LocalPanes provides panes, LocalSpaceChoice provides space) { ScreenChangeHost(nav) {
            val app: @Composable () -> Unit = {
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    containerColor = Herdr.colors.ground,
                    bottomBar = { if (topLevel) AppNavBar(nav, needsYou) },
                ) { padding ->
                    CompositionLocalProvider(LocalBarDrawer provides if (wide) null else openDrawer) {
                        NavHost(
                            nav,
                            startDestination = Routes.TASKS,
                            // Under the bottom bar the system's bar is the bar's: a screen's own navigationBarsPadding adds nothing more.
                            modifier = Modifier.fillMaxSize().padding(bottom = if (topLevel) padding.calculateBottomPadding() else 0.dp)
                                .then(if (topLevel) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier),
                            // Short and quiet: a detail slides in a little from the side, tabs cross-fade.
                            enterTransition = { fadeIn(tween(200)) + slideInHorizontally(tween(220)) { it / 12 } },
                            exitTransition = { fadeOut(tween(150)) },
                            popEnterTransition = { fadeIn(tween(200)) },
                            popExitTransition = { fadeOut(tween(150)) + slideOutHorizontally(tween(200)) { it / 12 } },
                        ) {
                            screens(nav) { TasksScreen(nav, tasksList) }
                        }
                    }
                }
            }
            if (wide) {
                // The spaces stay on the left, as 3.1's sidebar on a desktop; the app fills the rest.
                Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                    SpacesDrawer(nav, onPick = ::pick, onClose = {}, modifier = Modifier.width(sideWidth))
                    PaneEdge()
                    Box(Modifier.weight(1f).fillMaxHeight()) { app() }
                }
            } else {
                ModalNavigationDrawer(
                    drawerState = drawer,
                    // Only a top-level tab opens it with a swipe; a chat's own swipes stay its own.
                    gesturesEnabled = drawer.isOpen,
                    scrimColor = Herdr.colors.scrim,
                    drawerContent = {
                        SpacesDrawer(
                            nav, onPick = ::pick, onClose = { scope.launch { drawer.close() } },
                            modifier = Modifier.width(300.dp).drawBehind {
                                drawLine(lineColor, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1.dp.toPx())
                            },
                        )
                    },
                ) { app() }
            }
            // Another agent needs you while you are in a chat: one line drops from the top.
            AlertPill(
                // An agent asking in a space set to Notify Off (or muted before) doesn't drop a line either; its card still waits on the list.
                nav, panes, entry, remember(waiting, triage, push.rules) {
                    waiting.filter { !(it is Waiting.Question && (it.agent.key in triage.muted || push.rules.notifyOf(it.agent.space) == Notify.Off)) }
                },
                agentsRead = remember(hosts) { hosts.filter { it.agentsKnown }.map { it.machine.id }.toSet() },
                boardsRead = remember(homeBoards) { homeBoards.map { it.machineId }.toSet() },
            )
        } }
    }
}

/** Every screen of the app, by its [Routes] entry; [tasks] is the Tasks tab, whose list state the layout around it keeps. */
internal fun NavGraphBuilder.screens(nav: NavHostController, tasks: @Composable () -> Unit) {
    composable(Routes.TASKS) { tasks() }
    composable(Routes.CHAT) { ChatTab(nav) }
    composable(Routes.ARTIFACTS_TAB) { ArtifactsTab(nav) }
    composable(Routes.SETTINGS) { SettingsScreen(nav) }
    composable(Routes.QUIET) { QuietHoursScreen(nav) }
    composable(Routes.GUARD) { com.makefunstuff.herdr.ui.settings.GuardScreen(nav) }
    composable(Routes.COMPOSER) { com.makefunstuff.herdr.ui.settings.QuickRepliesScreen(nav) }
    composable(Routes.RULES) { com.makefunstuff.herdr.ui.settings.AlertRulesScreen(nav) }
    composable(Routes.BRIEF) { BriefScreen(nav) }
    composable(Routes.BRIEF_SETTINGS) { BriefSettingsScreen(nav) }
    composable(Routes.CHANGE_APP) { com.makefunstuff.herdr.ui.settings.ChangeAppScreen(nav) }
    composable(Routes.AUTOPILOT) { com.makefunstuff.herdr.ui.settings.AutopilotScreen(nav, it.arguments?.getString("mid").orEmpty()) }
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
    composable(Routes.HEALTH) { HealthScreen(nav, it.arguments?.getString("mid").orEmpty()) }
    composable(Routes.USAGE) { com.makefunstuff.herdr.ui.usage.UsageScreen(nav, it.arguments?.getString("mid").orEmpty()) }
    composable(Routes.LOGINS) { com.makefunstuff.herdr.ui.logins.LoginsScreen(nav, it.arguments?.getString("mid").orEmpty()) }
    composable(Routes.BOARD) {
        BoardScreen(
            nav, it.arguments?.getString("mid").orEmpty(), it.arguments?.getString("task")?.let(Uri::decode),
            alone = it.arguments?.getString("alone") == "1", accept = it.arguments?.getString("accept") == "1",
        )
    }
    composable(Routes.ASK) { AskScreen(nav, it.arguments?.getString("to").orEmpty()) }
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
    composable(Routes.ACTIVITY) {
        ActivityScreen(nav, it.arguments?.getString("mid").orEmpty(), it.arguments?.getString("pane")?.let(Uri::decode).orEmpty())
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
}

/** The code browser's place, from a code route's arguments. */
private fun androidx.navigation.NavBackStackEntry.codeSpot(): CodeSpot =
    CodeSpot.of(arguments?.getString("mid").orEmpty()) { arguments?.getString(it) }

/**
 * The bottom bar, as 3.1's phone: Chat (the shown space), Tasks (which owns the Needs you count),
 * Artifacts. Under the app on a phone and beside the sidebar on a wide window alike.
 */
@Composable
private fun AppNavBar(nav: NavHostController, needsYou: Int) {
    val entry by nav.currentBackStackEntryAsState()
    val tab = entry?.destination?.route
    HerdrNavBar {
        NavItem("Chat", HerdrIcons.MessagesSquare, tab == Routes.CHAT) { nav.goTop(Routes.CHAT) }
        NavItem("Tasks", HerdrIcons.ListTodo, tab == Routes.TASKS, badge = needsYou) { nav.goTop(Routes.TASKS) }
        NavItem("Artifacts", HerdrIcons.LayoutDashboard, tab == Routes.ARTIFACTS_TAB) { nav.goTop(Routes.ARTIFACTS_TAB) }
    }
}

/** The hairline between two panes. */
@Composable
private fun PaneEdge() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(Herdr.colors.line))
}

private fun NavHostController.goTop(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
