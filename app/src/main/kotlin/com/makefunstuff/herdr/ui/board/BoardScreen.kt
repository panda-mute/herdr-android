package com.makefunstuff.herdr.ui.board

import com.makefunstuff.herdr.core.board.BoardActions
import com.makefunstuff.herdr.core.board.TaskLine
import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.home.Home
import com.makefunstuff.herdr.core.home.HomeAgent
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import com.makefunstuff.herdr.ui.design.approves
import androidx.compose.ui.platform.LocalView
import com.makefunstuff.herdr.ui.design.confirmTap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.makefunstuff.herdr.BoardChoice
import com.makefunstuff.herdr.LocalPanes
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.ui.code.CheckoutTab
import com.makefunstuff.herdr.ui.code.CodeSpot
import com.makefunstuff.herdr.ui.quick.QuickPlace
import com.makefunstuff.herdr.ui.quick.QuickSheets
import com.makefunstuff.herdr.core.board.Approvals
import com.makefunstuff.herdr.core.board.BoardAction
import com.makefunstuff.herdr.core.guard.Guarded
import com.makefunstuff.herdr.ui.common.LocalGuard
import com.makefunstuff.herdr.core.board.BoardFilter
import com.makefunstuff.herdr.core.board.BoardStatus
import com.makefunstuff.herdr.core.board.WorkTasks
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.shepherdr.V2Board
import com.makefunstuff.herdr.core.push.Alerts
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.common.detail
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.HerdrFab
import com.makefunstuff.herdr.ui.common.Pill
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.ErrorState
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.design.StateBlock
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.theme.tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The colour of this status's one word ([BoardStatus.word]), in the current theme: the same on every board. */
@Composable
@ReadOnlyComposable
fun BoardStatus.color(): Color = Herdr.colors.tone(WorkTasks.status(this).tone)

/** What a person is part-way through doing to a task: asked for words, or asked to be sure. */
sealed interface Pending {
    data class Words(val task: BoardTask, val action: BoardAction) : Pending
    data class Confirm(val task: BoardTask, val action: BoardAction, val letter: String = "") : Pending
    data class Anyway(val task: BoardTask) : Pending
    data class Edit(val task: BoardTask) : Pending
    data object Add : Pending
    /** Starting an agent on a task: which one, with what each has left. */
    data class Start(val task: BoardTask) : Pending
}

/**
 * The work board on one machine, as a phone screen: its tasks as `herdr-projects list --json` gives
 * them, read again while the screen is in front, every action the board's CLI has. With [pane] it is
 * the right pane of a wide window ([Panes]): no way back, and the task asked for in [pane] opens once.
 * With [alone], [openTask] was opened from elsewhere (the Board tab): closing it goes back there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(nav: NavController, machineId: String, openTask: String? = null, pane: BoardChoice? = null, alone: Boolean = false) {
    val container = LocalContainer.current
    val panes = LocalPanes.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val guard = LocalGuard.current
    val view = LocalView.current
    val session = container.hosts.session(machineId)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val state by session.state.collectAsStateWithLifecycle()
    // Shepherdr 3: its own board, through its API; herdr-projects 1.x keeps the screen below.
    if (state.onShepherdr) {
        WorkBoardScreen(nav, session, machineId, openTask, pane, alone)
        return
    }
    var tasks by remember { mutableStateOf<List<BoardTask>>(emptyList()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var archived by rememberSaveable { mutableStateOf(false) }
    var repo by rememberSaveable { mutableStateOf("") }
    var machine by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf(openTask) }
    // A task asked for from elsewhere (a notification, the widget) while this board is on the right.
    if (pane != null) LaunchedEffect(pane.machine, pane.task) {
        if (pane.machine == machineId) pane.task?.let { openId = it; pane.task = null }
    }
    // An open task's notification has done its job.
    LaunchedEffect(openId) { openId?.let { container.inbox.dismiss(Alerts.taskTag(machineId, it)) } }
    var actionsFor by remember { mutableStateOf<BoardTask?>(null) }
    var quick by remember { mutableStateOf<QuickPlace?>(null) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var refusal by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val readNow = remember { Channel<Unit>(Channel.CONFLATED) }
    val owner = LocalLifecycleOwner.current

    // Read while the screen is in front (a few seconds apart, sooner after an action); nothing while it's backgrounded.
    LaunchedEffect(machineId, state.online, state.boardPath, archived, query) {
        if (!state.online || state.boardPath == null) return@LaunchedEffect
        // A search typed letter by letter waits a moment for the next one.
        if (query.isNotBlank()) delay(350)
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    tasks = session.awaitBoard().tasks(BoardFilter(query = query, archived = archived))
                    // The whole list: what the status widget counts on this machine.
                    if (query.isBlank() && !archived) { container.tally.tasks(machineId, tasks); container.boards.put(machineId, tasks) }
                    problem = null
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    problem = t.message ?: "Couldn't read the board"
                }
                loaded = true
                withTimeoutOrNull(4000L) { readNow.receive() }
            }
        }
    }

    fun start(task: BoardTask, agent: String?) {
        if (busy != null) return
        busy = "${BoardAction.Delegate.label} · ${task.name}"
        scope.launch {
            runCatching { session.awaitBoard().delegate(task.id, agent) }
                .onSuccess { context.toast(it.ifEmpty { "Started" }) }
                .onFailure { refusal = it.message ?: "The board refused" }
            busy = null
            readNow.trySend(Unit)
        }
    }

    fun act(task: BoardTask, action: BoardAction, text: String, letter: String) {
        if (busy != null) return
        busy = "${action.label.removeSuffix("…")} · ${task.name}"
        if (action.approves) view.confirmTap()
        scope.launch {
            runCatching { session.awaitBoard().act(action, task.id, text, letter) }
                .onSuccess { context.toast(it.ifEmpty { "Done" }) }
                .onFailure { refusal = it.message ?: "The board refused" }
            busy = null
            readNow.trySend(Unit)
        }
    }

    fun perform(task: BoardTask, action: BoardAction, text: String = "", letter: String = "") {
        if (busy != null) return
        // A merge can't be taken back: the fingerprint first, when that is on.
        if (action == BoardAction.Approve || action == BoardAction.ApproveAnyway) {
            return guard.run(Guarded.Merge, "Approve and merge ${task.name}?", task.title) { act(task, action, text, letter) }
        }
        act(task, action, text, letter)
    }

    /** Offers an action: words first, a confirmation for the ones that can't be undone, else just runs it. */
    fun choose(task: BoardTask, action: BoardAction) {
        pending = when {
            action == BoardAction.Tell || action == BoardAction.SendPlanBack -> Pending.Words(task, action)
            action == BoardAction.ApproveAnyway -> Pending.Anyway(task)
            action == BoardAction.Delegate && task.machine.isEmpty() -> Pending.Start(task)
            busy != null -> null
            action.destructive -> Pending.Confirm(task, action)
            else -> { perform(task, action); null }
        }
    }

    // Each task's agent on this machine, by the names herdr-projects gives them: its chat opens from the card.
    val agents = remember(state) { HomeAgent.of(listOf(state)) }
    fun agentOf(t: BoardTask): AgentInfo? = Home.agentOf(agents, t)?.agent
    fun openAgent(paneId: String) {
        val route = Routes.agent(machineId, paneId)
        // From the right pane the chat opens in the middle, beside this board and its open card.
        if (pane != null) panes.open(nav, route) else nav.navigate(route)
    }
    val repos = remember(tasks) { tasks.map { it.repo }.distinct().sorted() }
    // Finished work waiting for a yes: the review cards go through it one task at a time.
    val waiting = remember(tasks) { tasks.count { Approvals.waits(it) } }
    fun openReview(task: BoardTask?) {
        val route = Routes.review(machineId, task?.id)
        // From the right pane the cards open in the middle, beside this board.
        if (pane != null) panes.open(nav, route) else nav.navigate(route)
    }
    val machines = remember(tasks) { tasks.map { it.machine }.filter { it.isNotEmpty() }.distinct().sorted() }
    val shown = remember(tasks, repo, machine) { tasks.filter { BoardFilter(repo = repo, machine = machine).matches(it) } }
    val groups = remember(shown) {
        listOf(
            "Needs you" to shown.filter { it.needsYou },
            "Working" to shown.filter { !it.needsYou && it.status in setOf(BoardStatus.Working, BoardStatus.Reviewing, BoardStatus.Queued) },
            "To do" to shown.filter { !it.needsYou && it.status == BoardStatus.Todo },
            "Done" to shown.filter { !it.needsYou && it.status == BoardStatus.Done },
            "Archived" to shown.filter { it.status == BoardStatus.Archived },
        ).filter { it.second.isNotEmpty() }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar(
                "Board",
                subtitle = "${state.machine.label} · ${if (archived) "archived" else "${tasks.count { it.needsYou }} need you"}",
                onBack = if (pane == null) ({ nav.popBackStack() }) else null,
                titleContent = if (searching) ({
                    HerdrField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Search tasks") }, modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(HerdrIcons.Search, null, Modifier.size(20.dp)) },
                        shape = HerdrShapes.Pill,
                    )
                }) else null,
            ) {
                BarIcon(if (searching) HerdrIcons.X else HerdrIcons.Search, "Search", { if (searching) query = ""; searching = !searching })
                BarIcon(HerdrIcons.MessagesSquare, "Coordinator chat", {
                    // From the right pane the chat opens in the middle, beside this board.
                    if (pane != null) panes.open(nav, Routes.coordinator(machineId)) else nav.navigate(Routes.coordinator(machineId))
                })
                BarIcon(HerdrIcons.Ellipsis, "Filter and more", { menu = true })
                HerdrMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuRow(if (archived) "Hide archived" else "Show archived", if (archived) HerdrIcons.ArchiveRestore else HerdrIcons.Archive, { archived = !archived; menu = false })
                    MenuRow("Refresh", HerdrIcons.RefreshCw, { readNow.trySend(Unit); menu = false })
                    MenuRow("Review finished work", HerdrIcons.Layers, { menu = false; openReview(null) })
                    MenuRow("Routines", HerdrIcons.CalendarClock, {
                        menu = false
                        if (pane != null) panes.open(nav, Routes.routines(machineId)) else nav.navigate(Routes.routines(machineId))
                    })
                }
            }
        },
        floatingActionButton = {
            if (state.boardPath != null) HerdrFab(null, "Add a task") { pending = Pending.Add }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            busy?.let { b -> BusyLine(b) }
            if (repos.size > 1 || machines.isNotEmpty() || repo.isNotEmpty() || machine.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Space.screen),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    items(repos + machines) { name ->
                        val isMachine = name in machines
                        val on = if (isMachine) machine == name else repo == name
                        HerdrChip(
                            selected = on,
                            onClick = { if (isMachine) machine = if (on) "" else name else repo = if (on) "" else name },
                            label = { Text(if (isMachine) "⇄ $name" else name, maxLines = 1) },
                        )
                    }
                }
            }
            when {
                !state.online -> EmptyState(state.status.label(), state.status.detail() ?: "Waiting for ${state.machine.label}…", icon = HerdrIcons.Monitor)
                state.boardPath == null -> EmptyState("No work board here", "herdr-projects isn't installed on ${state.machine.label}.", icon = HerdrIcons.SquareKanban)
                !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingState("Reading the board on ${state.machine.label}") }
                problem != null && tasks.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ErrorState("The board isn't answering", problem.orEmpty(), onRetry = { readNow.trySend(Unit) })
                }
                else -> PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = { scope.launch { refreshing = true; readNow.trySend(Unit); delay(900); refreshing = false } },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.xs, bottom = 96.dp),
                    // Compact rows, close together: about twice as many as cards fit a screen.
                    verticalArrangement = Arrangement.spacedBy(Space.xxs),
                ) {
                    problem?.let { p -> item(key = "problem") { ErrorMessage(p, Modifier.padding(vertical = Space.xs), onRetry = { readNow.trySend(Unit) }) } }
                    if (waiting > 0 && !archived && query.isBlank()) item(key = "review") { Box(Modifier.padding(vertical = Space.xs)) { ReviewEntryCard(waiting, onOpen = { openReview(null) }) } }
                    if (groups.isEmpty()) {
                        item(key = "empty") {
                            StateBlock(
                                if (query.isNotBlank()) HerdrIcons.Search else HerdrIcons.SquareKanban,
                                if (query.isNotBlank()) "No task matches “$query”" else if (archived) "Nothing archived" else "No tasks yet",
                                if (query.isNotBlank() || archived) null else "Add one with +.",
                                Modifier.padding(top = Space.xxl),
                            )
                        }
                    }
                    groups.forEach { (title, list) ->
                        val collapsible = title == "Done"
                        val open = !collapsible || showDone
                        item(key = "h-$title") {
                            SectionHeading(
                                "$title · ${list.size}",
                                modifier = Modifier
                                    .then(if (collapsible) Modifier.clickable(onClickLabel = if (open) "Hide done tasks" else "Show done tasks") { showDone = !showDone } else Modifier)
                                    .heightIn(min = 48.dp),
                                top = if (title == groups.first().first) Space.xs else Space.m,
                                trailing = if (collapsible) ({
                                    Icon(if (open) HerdrIcons.ChevronUp else HerdrIcons.ChevronDown, null, tint = Herdr.colors.muted, modifier = Modifier.size(20.dp))
                                }) else null,
                            )
                        }
                        if (open) items(list, key = { it.id }) { t ->
                            // Its sheet's actions, as TalkBack's on the card; the sheet for the rest (edit, watches).
                            val actions = BoardActions.forTask(t).map { a -> CustomAccessibilityAction(a.label.removeSuffix("…")) { choose(t, a); true } } +
                                listOfNotNull(
                                    CustomAccessibilityAction("Open its review card") { openReview(t); true }.takeIf { Approvals.waits(t) },
                                    CustomAccessibilityAction("More actions") { actionsFor = t; true },
                                )
                            val live = agentOf(t)
                            // A section of one state (To do, Done) says it in its heading; Needs you and Working hold several.
                            TaskRow(t, onOpen = { openId = t.id }, onMenu = { actionsFor = t }, actions = actions, onAgent = live?.let { a -> { openAgent(a.paneId) } }, showWord = title == "Needs you" || title == "Working")
                        }
                    }
                } }
            }
        }
    }

    val open = tasks.firstOrNull { it.id == openId }
    // On the right of a wide window, Back closes what the middle shows first (its own Back), and the open task after it.
    val middle by nav.currentBackStackEntryAsState()
    val middleCloses = pane != null && middle?.destination?.route != Routes.AGENTS
    if (openId != null && open != null) {
        val live = agentOf(open)
        TaskDetailScreen(session, open, onBack = { if (alone && pane == null && openId == openTask) nav.popBackStack() else openId = null }, busy = busy, onAction = { a -> choose(open, a) }, onPick = { letter -> perform(open, BoardAction.Pick, letter = letter) },
            onMore = { actionsFor = open }, backEnabled = !middleCloses,
            onCode = { d -> nav.navigate(Routes.codeAt(CodeSpot(machineId, d.worktree, task = open.id, taskName = open.name), CheckoutTab.Changes)) },
            onQuick = { d -> quick = QuickPlace(machineId, d.worktree, label = open.name, task = open.id, taskName = open.name) },
            onActivity = if (open.machine.isEmpty()) ({ nav.navigate(Routes.activity(machineId, task = open.id, name = open.name)) }) else null,
            live = live, onOpenAgent = live?.let { a -> { openAgent(a.paneId) } },
            // Its agent's machine: this one, or the one of the app's machines the task runs on.
            onSignIn = {
                val on = if (open.machine.isEmpty()) machineId else container.machines.machines.value.firstOrNull { it.label == open.machine }?.id
                if (on != null) nav.navigate(Routes.logins(on))
                else context.toast("${open.machine} isn't in the app: add it to sign its agent in")
            })
    }
    quick?.let { place -> QuickSheets(nav, place, onDismiss = { quick = null }) }
    actionsFor?.let { t ->
        ActionsSheet(t, onDismiss = { actionsFor = null }, onAction = { a -> actionsFor = null; choose(t, a) }, onEdit = { actionsFor = null; pending = Pending.Edit(t) },
            onOpen = if (openId == t.id) null else ({ actionsFor = null; openId = t.id }),
            onReview = if (Approvals.waits(t)) ({ actionsFor = null; openReview(t) }) else null, machineId = machineId)
    }
    refusal?.let { r ->
        BoardPanel("The board said no", onDismiss = { refusal = null }, accent = Herdr.colors.red,
            buttons = { PrimaryButton("OK") { refusal = null } }) {
            MarkdownText(r)
        }
    }
    BoardDialogs(
        pending = pending, session = session, repos = repos, onDismiss = { pending = null },
        onWords = { p, text -> pending = null; perform(p.task, p.action, text) },
        onConfirm = { p -> pending = null; perform(p.task, p.action, letter = p.letter) },
        onAnyway = { p -> pending = null; perform(p.task, BoardAction.ApproveAnyway) },
        onSaved = { pending = null; readNow.trySend(Unit) },
        onStart = { p, agent -> pending = null; start(p.task, agent) },
    )
    }
}

/** An action running on the board: a thin accent bar and what it is. */
@Composable
internal fun BusyLine(what: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.xs)) {
        LinearProgressIndicator(
            Modifier.fillMaxWidth().height(3.dp).clip(HerdrShapes.Pill).semantics { contentDescription = "Running: $what" },
            color = Herdr.colors.accent, trackColor = Herdr.colors.line,
        )
        Text("$what…", style = MaterialTheme.typography.labelMedium, color = Herdr.colors.muted, modifier = Modifier.padding(top = Space.xs))
    }
}

/** A thin rounded progress bar in a status colour. */
@Composable
internal fun Progress(p: Int, color: Color, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { p / 100f }, color = color, trackColor = Herdr.colors.line,
        gapSize = 0.dp, drawStopIndicator = {},
        modifier = modifier.height(4.dp).clip(HerdrShapes.Pill),
    )
}

/**
 * The card's way to its agent: the agent's name with a chat glyph, in amber, a tap target of its own.
 * A Shepherdr 3 task's CLI ("Claude Code") shows as its kind ("claude").
 */
@Composable
internal fun AgentChip(agent: String, onClick: () -> Unit) {
    val c = Herdr.colors
    Row(
        Modifier.padding(start = Space.s).clip(HerdrShapes.Pill).background(c.accentTint)
            .clickable(onClickLabel = "Open agent", onClick = onClick).padding(horizontal = Space.s, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HerdrIcons.MessageSquare, null, tint = c.accent, modifier = Modifier.size(14.dp))
        Text(
            V2Board.agentKind(agent).ifEmpty { "agent" }, style = MaterialTheme.typography.labelMedium, color = c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Space.xs).widthIn(max = 110.dp),
        )
    }
}
