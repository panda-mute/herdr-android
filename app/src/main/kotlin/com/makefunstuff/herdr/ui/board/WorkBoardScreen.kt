package com.makefunstuff.herdr.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
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
import com.makefunstuff.herdr.core.board.CardAnswer
import com.makefunstuff.herdr.core.board.WorkBoard
import com.makefunstuff.herdr.core.board.WorkOverview
import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.board.WorkTasks
import com.makefunstuff.herdr.core.guard.Guarded
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.push.Alerts
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.LocalGuard
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.common.Pill
import com.makefunstuff.herdr.ui.common.detail
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.ErrorState
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrFab
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.design.StateBlock
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.design.confirmTap
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.theme.tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The board's colour for this status, in the current theme: the same as v1's nearest status. */
@Composable
@ReadOnlyComposable
fun WorkStatus.color(): Color = Herdr.colors.tone(tone)

/** What a person is part-way through on a Shepherdr 3 board: closing a task, adding one. */
private sealed interface WorkPending {
    data class Close(val task: WorkTask) : WorkPending
    data object Add : WorkPending
}

/**
 * A machine's Shepherdr 3 board, as [BoardScreen] shows it there: what needs you first (tasks that ask
 * or wait, then those ready to merge, then coordinators that ask), then each space's tasks, done ones
 * folded away. Kept current from Shepherdr's live link, or read every few seconds where there is none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkBoardScreen(
    nav: NavController,
    session: HostSession,
    machineId: String,
    openTask: String? = null,
    pane: BoardChoice? = null,
    alone: Boolean = false,
) {
    val container = LocalContainer.current
    val panes = LocalPanes.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val guard = LocalGuard.current
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    val state by session.state.collectAsStateWithLifecycle()
    var board by remember { mutableStateOf(container.boards.lists.value[machineId]?.work) }
    var problem by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(board != null) }
    var openId by rememberSaveable { mutableStateOf(openTask) }
    if (pane != null) LaunchedEffect(pane.machine, pane.task) {
        if (pane.machine == machineId) pane.task?.let { openId = it; pane.task = null }
    }
    LaunchedEffect(openId) { openId?.let { container.inbox.dismiss(Alerts.taskTag(machineId, it)) } }
    var pending by remember { mutableStateOf<WorkPending?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var refusal by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val readNow = remember { Channel<Unit>(Channel.CONFLATED) }

    fun got(o: WorkOverview) {
        board = o
        problem = null
        loaded = true
        container.boards.put(machineId, o)
        container.tally.tasks(machineId, o.tasks.map(WorkTasks::row))
    }

    // Followed while the screen is in front: the live link's snapshots, and a read now after an action.
    LaunchedEffect(machineId, state.online) {
        if (!state.online) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            coroutineScope {
                launch {
                    for (x in readNow) runCatching { session.awaitWorkBoard().overview() }.onSuccess(::got)
                }
                while (true) {
                    try {
                        session.awaitWorkBoard().overviews().collect(::got)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        problem = t.message ?: "Couldn't read the board"
                        loaded = true
                    }
                    delay(4_000)
                }
            }
        }
    }

    fun act(what: String, task: WorkTask?, approves: Boolean = false, run: suspend (WorkBoard) -> String) {
        if (busy != null) return
        busy = listOfNotNull(what, task?.name).joinToString(" · ")
        if (approves) view.confirmTap()
        scope.launch {
            runCatching { run(session.awaitWorkBoard()) }
                .onSuccess { context.toast(it.ifEmpty { "Done" }) }
                .onFailure { refusal = it.message ?: "Shepherdr refused" }
            busy = null
            readNow.trySend(Unit)
        }
    }

    /** The Ready task whose Accept card is up. */
    var acceptFor by remember { mutableStateOf<WorkTask?>(null) }
    // Shepherdr's Accept: its card first, which says what you approve; the fingerprint then, as for v1's Approve and merge.
    fun merge(t: WorkTask) {
        acceptFor = t
    }
    fun answer(a: CardAnswer, t: WorkTask) = act("Answering", t) { it.answer(a).ifEmpty { "Answered" } }
    /** [space]'s coordinator chat; the home space's without one. */
    fun openCoordinator(space: String? = null) {
        val route = Routes.coordinator(machineId, space)
        if (pane != null) panes.open(nav, route) else nav.navigate(route)
    }

    val o = board
    val needsYou = o?.needsYou.orEmpty()
    // A space whose coordinator has a question open for you.
    val asking = o?.spaces.orEmpty().filter { it.asks }
    val done = o?.tasks.orEmpty().filter { it.status == WorkStatus.Done }
    val many = (o?.spaces?.size ?: 0) > 1
    fun spaceName(id: String) = o?.spaces?.firstOrNull { it.id == id }?.name.orEmpty()

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Herdr.colors.ground,
            topBar = {
                HerdrTopBar(
                    "Board",
                    subtitle = "${state.machine.label} · ${if (o == null) "Shepherdr" else "${needsYou.size + asking.size} need you"}",
                    onBack = if (pane == null) ({ nav.popBackStack() }) else null,
                ) {
                    BarIcon(HerdrIcons.MessagesSquare, "Assistant chat", { openCoordinator() })
                    BarIcon(HerdrIcons.RefreshCw, "Read the board again", { readNow.trySend(Unit) })
                }
            },
            floatingActionButton = { if (o != null) HerdrFab(null, "Add a task") { pending = WorkPending.Add } },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                busy?.let { BusyLine(it) }
                when {
                    !state.online -> EmptyState(state.status.label(), state.status.detail() ?: "Waiting for ${state.machine.label}…", icon = HerdrIcons.Monitor)
                    !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingState("Reading the board on ${state.machine.label}") }
                    o == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ErrorState("Shepherdr isn't answering", problem.orEmpty(), onRetry = { readNow.trySend(Unit) })
                    }
                    else -> PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = { scope.launch { refreshing = true; readNow.trySend(Unit); delay(900); refreshing = false } },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.xs, bottom = 96.dp),
                            // Compact rows, close together: about twice as many as cards fit a screen.
                            verticalArrangement = Arrangement.spacedBy(Space.xxs),
                        ) {
                            problem?.let { p -> item(key = "problem") { ErrorMessage(p, Modifier.padding(vertical = Space.xs), onRetry = { readNow.trySend(Unit) }) } }
                            if (needsYou.isNotEmpty() || asking.isNotEmpty()) {
                                item(key = "h-needs") { SectionHeading("Needs you · ${needsYou.size + asking.size}", top = Space.xs) }
                                items(needsYou, key = { "n-${it.id}" }) { t ->
                                    WorkTaskRow(t, space = if (many) spaceName(t.space) else "", onOpen = { openId = t.id })
                                }
                                items(asking, key = { "a-${it.id}" }) { s -> CoordinatorAsksRow(s, onOpen = { openCoordinator(s.id) }) }
                            }
                            o.spaces.forEach { s ->
                                val open = o.tasksIn(s.id).filter { !it.status.needsYou && it.status != WorkStatus.Done }
                                if (open.isEmpty() && !s.unreachable) return@forEach
                                item(key = "h-${s.id}") {
                                    // The space's heading opens its coordinator's chat.
                                    SectionHeading(
                                        listOfNotNull(s.name, open.size.takeIf { it > 0 }?.toString(), "can't be reached".takeIf { s.unreachable }).joinToString(" · "),
                                        Modifier.clickable(onClickLabel = "Open ${s.name}'s Assistant chat") { openCoordinator(s.id) }.heightIn(min = 48.dp),
                                        top = Space.m,
                                        trailing = { Icon(HerdrIcons.MessagesSquare, null, tint = Herdr.colors.muted, modifier = Modifier.size(18.dp)) },
                                    )
                                }
                                items(open, key = { it.id }) { t -> WorkTaskRow(t, onOpen = { openId = t.id }) }
                            }
                            // Tasks no space works in (queued or to do in a task list of their own): under their list's name.
                            o.tasks.filter { t -> !t.status.needsYou && t.status != WorkStatus.Done && o.spaces.none { it.id == t.space } }
                                .groupBy { it.listName.ifEmpty { "Other tasks" } }.forEach { (list, ts) ->
                                    item(key = "l-$list") { SectionHeading("$list · ${ts.size}", top = Space.m) }
                                    items(ts, key = { "l-${it.id}" }) { t -> WorkTaskRow(t, onOpen = { openId = t.id }) }
                                }
                            if (needsYou.isEmpty() && asking.isEmpty() && o.tasks.none { it.status != WorkStatus.Done }) item(key = "empty") {
                                StateBlock(HerdrIcons.SquareKanban, "Nothing open", "Add a task with +, or ask a space's Assistant.", Modifier.padding(top = Space.xxl))
                            }
                            if (done.isNotEmpty()) {
                                item(key = "h-done") {
                                    SectionHeading(
                                        "Done · ${done.size}",
                                        Modifier.clickable(onClickLabel = if (showDone) "Hide done tasks" else "Show done tasks") { showDone = !showDone }.heightIn(min = 48.dp),
                                        top = Space.m,
                                        trailing = {
                                            Icon(if (showDone) HerdrIcons.ChevronUp else HerdrIcons.ChevronDown, null, tint = Herdr.colors.muted, modifier = Modifier.size(20.dp))
                                        },
                                    )
                                }
                                if (showDone) items(done, key = { "d-${it.id}" }) { t ->
                                    WorkTaskRow(t, space = if (many) spaceName(t.space) else "", onOpen = { openId = t.id })
                                }
                            }
                        }
                    }
                }
            }
        }

        val open = o?.tasks?.firstOrNull { it.id == openId }
        val middle by nav.currentBackStackEntryAsState()
        val middleCloses = pane != null && middle?.destination?.route != Routes.AGENTS
        // A task with an agent opens as its agent's chat; one not started yet as its details.
        if (openId != null && open != null && open.chat.isNotEmpty()) {
            TaskChatScreen(
                session, machineId, open, space = spaceName(open.space), busy = busy,
                onBack = { if (alone && pane == null && openId == openTask) nav.popBackStack() else openId = null },
                backEnabled = !middleCloses,
                onMerge = { merge(open) },
                onClose = { pending = WorkPending.Close(open) },
                artifacts = o?.artifacts,
                artifactsRead = o != null,
                onArtifact = { id, v -> nav.navigate(Routes.artifact(machineId, id, v)) },
                onArtifacts = if (o?.artifacts != null) ({ nav.navigate(Routes.artifacts(machineId, open.space)) }) else null,
            )
        } else if (openId != null && open != null) {
            WorkTaskScreen(
                session, open, space = spaceName(open.space), busy = busy,
                onBack = { if (alone && pane == null && openId == openTask) nav.popBackStack() else openId = null },
                backEnabled = !middleCloses,
                onMerge = { merge(open) },
                onClose = { pending = WorkPending.Close(open) },
                onAnswer = { a -> answer(a, open) },
            )
        }
        acceptFor?.let { t ->
            val board by produceState<WorkBoard?>(null, machineId) { value = runCatching { session.awaitWorkBoard() }.getOrNull() }
            AcceptSheet(
                board, o?.tasks?.firstOrNull { it.id == t.id } ?: t, onDismiss = { acceptFor = null },
                onProof = { id -> nav.navigate(Routes.artifact(machineId, id, null)) },
                onAccepted = { context.toast(it); readNow.trySend(Unit) },
                confirm = { run -> guard.run(Guarded.Merge, "Accept ${t.name}?", t.title) { view.confirmTap(); run() } },
            )
        }
        when (val p = pending) {
            is WorkPending.Close -> BoardPanel(
                "Close ${p.task.name}?", onDismiss = { pending = null },
                buttons = {
                    PanelCancel { pending = null }
                    PrimaryButton("Close it") { pending = null; act("Closing", p.task) { it.close(p.task) } }
                },
            ) {
                Text(p.task.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "It is marked done with nothing merged. Shepherdr refuses while it holds work main lacks.",
                    style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted,
                )
            }
            WorkPending.Add -> if (o != null) AddWorkTaskPanel(
                o.spaces, onDismiss = { pending = null },
                onSend = { space, title, brief -> pending = null; act("Asking the Assistant", null) { it.addTask(space.id, title, brief) } },
            )
            null -> {}
        }
        refusal?.let { r ->
            BoardPanel("Shepherdr said no", onDismiss = { refusal = null }, accent = Herdr.colors.red, buttons = { PrimaryButton("OK") { refusal = null } }) {
                MarkdownText(r)
            }
        }
    }
}

/** The pull request's number in [pr]'s address, or null when it isn't one. */
internal fun prNumberOf(pr: String): String? = pr.substringAfterLast('/').takeIf { "/pull/" in pr && it.isNotEmpty() && it.all(Char::isDigit) }

/** What the Add panel and Start work say about adding a task on Shepherdr 3. */
internal const val ADD_THROUGH_COORDINATOR =
    "Shepherdr makes tasks only through a space's Assistant: this sends it what you wrote, and it adds the task."

/**
 * [AddWorkTaskPanel] for [session]'s board from elsewhere (the Board tab): its spaces as last read, or read
 * now when they weren't yet, so a task never goes to a herdr-projects 1.x left on disk.
 */
@Composable
internal fun AddWorkTask(session: HostSession, read: WorkOverview?, onDismiss: () -> Unit, onSend: (WorkSpace, String, String) -> Unit) {
    val spaces by produceState(read?.spaces, session.machine.id) {
        if (value == null) value = runCatching { session.awaitWorkBoard().overview().spaces }.getOrDefault(emptyList())
    }
    val l = spaces
    if (l == null || l.isEmpty()) BoardPanel("Add a task", onDismiss, buttons = { PanelCancel(onDismiss) }) {
        if (l == null) LoadingState("Reading ${session.machine.label}'s spaces") else ErrorMessage("Shepherdr didn't say which spaces it has")
    } else AddWorkTaskPanel(l, onDismiss, onSend)
}

/**
 * Adding a task on a Shepherdr 3 board: which space, a title and a brief. Sent to that space's
 * coordinator as a message, which makes the task; the panel says so.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddWorkTaskPanel(spaces: List<WorkSpace>, onDismiss: () -> Unit, onSend: (WorkSpace, String, String) -> Unit) {
    // The first space that isn't a home space: where work on a repo usually goes.
    var chosen by rememberSaveable { mutableStateOf((spaces.firstOrNull { !it.home } ?: spaces.firstOrNull())?.id) }
    var title by rememberSaveable { mutableStateOf("") }
    var brief by rememberSaveable { mutableStateOf("") }
    val space = spaces.firstOrNull { it.id == chosen }
    BoardPanel(
        "Add a task", onDismiss,
        buttons = {
            PanelCancel(onDismiss)
            PrimaryButton("Send to the Assistant", enabled = space != null && title.isNotBlank()) { space?.let { onSend(it, title.trim(), brief.trim()) } }
        },
    ) {
        Notice(ADD_THROUGH_COORDINATOR, icon = HerdrIcons.MessagesSquare)
        if (spaces.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            spaces.forEach { s -> HerdrChip(s.name, selected = s.id == chosen, onClick = { chosen = s.id }) }
        }
        HerdrField(title, { title = it }, label = "Title", singleLine = true, modifier = Modifier.fillMaxWidth())
        HerdrField(brief, { brief = it }, label = "Brief", minLines = 4, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("What should be done, and how you'll know it's done") })
    }
}
