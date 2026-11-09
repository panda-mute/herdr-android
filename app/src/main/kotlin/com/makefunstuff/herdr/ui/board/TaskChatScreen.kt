package com.makefunstuff.herdr.ui.board

import com.makefunstuff.herdr.ui.change.ChangeScreenRow
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import com.makefunstuff.herdr.LocalPanes
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.chat.ChatHeader
import com.makefunstuff.herdr.core.chat.ChatOwner
import com.makefunstuff.herdr.ui.agents.ownRows
import com.makefunstuff.herdr.ui.agents.rememberOwnTranscript
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.board.CardAnswer
import com.makefunstuff.herdr.core.board.CoordinatorThread
import com.makefunstuff.herdr.core.board.TabMenus
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.board.WorkTaskDetails
import com.makefunstuff.herdr.core.board.WorkBoard
import com.makefunstuff.herdr.core.shepherdr.V2Artifact
import com.makefunstuff.herdr.ui.artifacts.ChatArtifacts
import com.makefunstuff.herdr.ui.artifacts.LocalChatArtifacts
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.shepherdr.OutgoingLine
import com.makefunstuff.herdr.core.shepherdr.SpaceChat
import com.makefunstuff.herdr.core.shepherdr.V2Board
import androidx.compose.runtime.rememberUpdatedState
import com.makefunstuff.herdr.core.shepherdr.ChatFind
import com.makefunstuff.herdr.ui.coordinator.ChatJump
import com.makefunstuff.herdr.ui.coordinator.ChatPlaces
import com.makefunstuff.herdr.ui.coordinator.ChatReactions
import com.makefunstuff.herdr.ui.coordinator.FindBar
import com.makefunstuff.herdr.ui.coordinator.MessageSheet
import com.makefunstuff.herdr.ui.coordinator.lit
import com.makefunstuff.herdr.ui.coordinator.rememberChatFind
import com.makefunstuff.herdr.core.shepherdr.V2ReplyQuote
import com.makefunstuff.herdr.data.DraftStore
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.common.detail
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.common.rememberDraft
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.coordinator.ChatEmpty
import com.makefunstuff.herdr.ui.coordinator.ChatItem
import com.makefunstuff.herdr.ui.coordinator.Notice
import com.makefunstuff.herdr.ui.coordinator.ReplyingTo
import com.makefunstuff.herdr.ui.coordinator.WaitsForYou
import com.makefunstuff.herdr.ui.coordinator.Working
import com.makefunstuff.herdr.ui.coordinator.holds
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ChatComposer
import com.makefunstuff.herdr.ui.design.ChatSurface
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.InfoLine
import com.makefunstuff.herdr.ui.design.Led
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.NewLine
import com.makefunstuff.herdr.ui.design.NewestButton
import com.makefunstuff.herdr.ui.design.rememberStickToBottom
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.design.typingInShortWindow
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import com.makefunstuff.herdr.core.chat.VoiceNotes
import com.makefunstuff.herdr.core.host.Attachments
import com.makefunstuff.herdr.ui.common.rememberAttacher
import com.makefunstuff.herdr.ui.design.ComposerIconButton
import com.makefunstuff.herdr.ui.images.AttachMenu
import com.makefunstuff.herdr.ui.images.ChatImages
import com.makefunstuff.herdr.ui.images.ComposerTray
import com.makefunstuff.herdr.ui.images.LocalChatImages
import com.makefunstuff.herdr.ui.images.rememberImageTray
import com.makefunstuff.herdr.ui.common.LocalVoiceMessages
import com.makefunstuff.herdr.ui.common.rememberVoiceMessages

/** What the task chat's ⋯ shows over it: its menu, or one of its details in full. */
private enum class TaskSheet { Menu, Said, Brief, Plan, Facts }

/**
 * A Shepherdr 3 task as its agent's chat, as Shepherdr's own web UI has it: the task's chat live (its
 * agent's words, each turn's steps folded to a line, its question cards answered here), a strip under
 * the bar with where it stands, its pull request and the one thing it waits for (Merge), and a composer
 * to its agent. Under the strip, 3.1's task view tabs: Chat, then its folder's Files and Changes, its
 * Pull request with its checks and its live Browser ([TaskView]), each in the chat's place. What it did, its brief, plan and
 * facts, and Close, are in ⋯.
 */
@Composable
internal fun TaskChatScreen(
    nav: NavController,
    session: HostSession,
    machineId: String,
    t: WorkTask,
    space: String,
    busy: String?,
    onBack: () -> Unit,
    onMerge: () -> Unit,
    onClose: () -> Unit,
    backEnabled: Boolean = true,
    /** The machine's artifacts (null where Shepherdr keeps none, or until it answers), for the chat's artifact cards. */
    artifacts: List<V2Artifact>? = null,
    artifactsRead: Boolean = false,
    /** Opens an artifact (at a version, or its latest for null), or the task's space's Artifacts list (null: none). */
    onArtifact: (id: String, v: Int?) -> Unit = { _, _ -> },
    onArtifacts: (() -> Unit)? = null,
) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by session.state.collectAsStateWithLifecycle()
    var sheet by remember(t.id) { mutableStateOf<TaskSheet?>(null) }
    // Its chat, or the tab picked under its bar in its place (Files, Changes, Pull request), as 3.1's task view.
    var picked by rememberSaveable(t.id) { mutableStateOf(TaskView.Chat) }
    val view = picked.takeIf { it in taskViews(t) } ?: TaskView.Chat
    // The board its artifact cards read their pictures through.
    var board by remember { mutableStateOf<WorkBoard?>(null) }
    LaunchedEffect(state.online) {
        while (state.online && board == null) {
            board = runCatching { session.awaitWorkBoard() }.getOrNull()
            if (board == null) kotlinx.coroutines.delay(5_000)
        }
    }
    BackHandler(enabled = backEnabled && sheet == null, onBack = { if (view != TaskView.Chat) picked = TaskView.Chat else onBack() })

    // Its brief, plan and facts: read when it opens (again until that works), and when the board says it changed.
    var details by remember(t.id) { mutableStateOf<WorkTaskDetails?>(null) }
    LaunchedEffect(t, state.online) {
        while (state.online) {
            try {
                details = session.awaitWorkBoard().details(t)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                delay(8_000)
            }
        }
    }

    var thread by remember(t.chat) { mutableStateOf<CoordinatorThread?>(null) }
    var outgoing by remember(t.chat) { mutableStateOf<List<OutgoingLine>>(emptyList()) }
    var replyTo by remember(t.chat) { mutableStateOf<V2ReplyQuote?>(null) }
    /** The question cards an answer is on its way to, by question id. */
    var answering by remember(t.chat) { mutableStateOf<Set<String>>(emptySet()) }
    // A voice message: its file goes into Shepherdr's inbox, then to the agent as a file sent in its chat (as the Coordinator's).
    val voice = rememberVoiceMessages(
        DraftStore.task(machineId, t.id),
        online = state.online && t.chat.isNotEmpty(),
        upload = { file, name -> session.awaitWorkBoard().uploadFor(t, name, file.readBytes(), VoiceNotes.MIME) },
        deliver = { remote, id -> session.awaitWorkBoard().tellFiles(t, listOf(remote), cid = id) },
        fetch = { remote -> session.awaitWorkBoard().taskFile(t, remote) },
        machine = machineId,
        board = { session.awaitWorkBoard() },
    )
    var problem by remember(t.chat) { mutableStateOf<String?>(null) }
    LaunchedEffect(t.id, t.chat, state.online) {
        if (!state.online) return@LaunchedEffect
        while (true) {
            try {
                session.awaitWorkBoard().taskThreads(t).collect { th ->
                    thread = th
                    problem = null
                    th.v2?.let { chat ->
                        outgoing = SpaceChat.stillOutgoing(chat, outgoing)
                        // A voice message Shepherdr held, then couldn't hand over: on the phone again, with Retry.
                        chat.failed?.takeIf { it.isNotEmpty() }?.let { f -> voice.refused(f.associate { it.cid to it.error }) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                problem = e.message ?: "Couldn't read the chat"
            }
            delay(3_000)
        }
    }
    val th = thread
    val items = remember(th, outgoing, voice.notes) { th?.v2?.let { SpaceChat.items(th.space, it, outgoing, voice.notes, container.clock(), steps = true) }.orEmpty() }
    // A list of its own for each task, so another task opens at its newest too; kept to its newest message (StickToBottom).
    val list = remember(t.id) { LazyListState() }
    val stick = rememberStickToBottom(list)
    val foot = th?.working == true || problem != null || !th?.v2?.blocked.isNullOrBlank()
    stick.Follow(remember(items) { items.map { it.id } }, content = items to foot)
    val notice = items.isNotEmpty() && th?.v2?.let(com.makefunstuff.herdr.core.shepherdr.ChatNotice::of) != null
    // Where each message sits in the list, newest first under the foot and the notice: for find and a reply's line.
    val where = rememberUpdatedState { id: String -> items.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { i -> (if (foot) 1 else 0) + (if (notice) 1 else 0) + items.size - 1 - i } }
    val places = remember(list) { ChatPlaces(list) { where.value(it) } }
    val find = rememberChatFind(t.id, items, places)
    val jump = remember(places) { ChatJump(scope, places) }
    val reactions = remember(t.id, t.chat) {
        ChatReactions(scope, { m, e -> session.awaitWorkBoard().react(t.space, t, m, e) }) { context.toast(it) }
    }
    LaunchedEffect(items) { reactions.settle(items) }

    var draft by rememberDraft(DraftStore.task(machineId, t.id))
    // Files and pictures, as in an agent's own chat: copied to its machine, named in the message by their path.
    val tray = rememberImageTray(DraftStore.task(machineId, t.id), machineId, session, state.online)
    val attach = rememberAttacher(session, onAttached = { path -> draft = Attachments.withMention(draft, path) }, onImage = tray::add)
    var attachMenu by remember { mutableStateOf(false) }
    val kind = V2Board.agentKind(t.agent).ifEmpty { "the agent" }
    // Its agent's terminal: where Shepherdr lost its log, its own transcript shows in place of "Can't find this conversation".
    val worker = state.view?.agents?.let { ChatOwner.taskAgent(it, t.id) }
    val own = rememberOwnTranscript(session, worker?.paneId?.takeIf { ChatOwner.readsOwn(th?.v2, worker) }, worker?.agentStatus ?: AgentStatus.Unknown)
    val machines by container.hosts.hosts.collectAsStateWithLifecycle()
    val panes = LocalPanes.current

    /** The screen is still open: a send that fails after it closed puts its words back in the saved draft instead. */
    var open by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { open = false } }

    fun send(text: String, reply: V2ReplyQuote?) {
        val line = OutgoingLine(UUID.randomUUID().toString(), text, reply)
        outgoing = outgoing + line
        stick.sent()
        // On the app's scope: leaving the screen right after Send doesn't stop it.
        container.scope.launch {
            try {
                session.awaitWorkBoard().tell(t, text, cid = line.cid, reply = reply?.id)
                // Its chat not on screen (still read, or unreadable): nothing else would show that it went.
                if (thread == null) withContext(Dispatchers.Main) { context.toast("Sent to ${t.name}") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    outgoing = outgoing - line
                    // Not lost: back in the message box, in front of anything typed since.
                    if (open) draft = if (draft.isBlank()) text else text + "\n" + draft
                    else container.drafts.add(DraftStore.task(machineId, t.id), text)
                    context.toast(e.message ?: "Couldn't send")
                }
            }
        }
    }

    fun sendDraft() {
        val typed = draft
        val pictures = tray.images
        // The pictures are named in front of what you typed.
        val text = tray.message(typed)
        if (text.isEmpty() || tray.waiting) return
        send(text, replyTo)
        draft = DraftStore.afterSend(draft, typed)
        tray.sent(pictures)
        replyTo = null
    }

    fun answer(a: CardAnswer) {
        val q = a.card.question.id
        answering = answering + q
        scope.launch {
            runCatching { session.awaitWorkBoard().answer(a) }.onFailure { context.toast(it.message ?: "Couldn't answer") }
            answering = answering - q
        }
    }

    val c = Herdr.colors
    val typing = typingInShortWindow()
    val waits = th?.cards.orEmpty()
    // A voice call with its agent (Shepherdr's voice agent on the machine), while it runs.
    val calls = container.voiceCalls
    val call by calls.state.collectAsStateWithLifecycle()
    val callHere = call.machineId == machineId && call.task == t.id && (call.on || call.problem != null)
    val askMic = com.makefunstuff.herdr.voice.rememberVoiceMic()
    val canClose = t.status != WorkStatus.Done && t.status != WorkStatus.Merging
    // Its agent's terminal, when it has one now: a tab in the strip and the way out of an empty chat.
    val terminalPane = TabMenus.taskTerminal(state.view, t)
    val openTerminal: (() -> Unit)? = terminalPane?.let { pane -> { nav.navigate(Routes.terminal(machineId, pane)) } }
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = c.ground,
            topBar = {
                if (!typing) Column {
                    // Whose chat (3.1's): the task, then its agent, space, machine when there are several, branch.
                    HerdrTopBar(
                        // The task's own title first, as 3.1's phone shows it: its key and branch go under it.
                        t.title,
                        subtitle = ChatHeader.line(
                            t.name, kind.takeIf { t.agent.isNotEmpty() }, space, ChatHeader.machine(state.machine.label, machines.size),
                            details?.branch?.ifBlank { null } ?: ChatHeader.folder(worker?.cwd),
                        ).ifEmpty { null },
                        onBack = onBack, backLabel = "Back to the board",
                        onTitle = if (t.space.isNotEmpty()) ({ panes.open(nav, Routes.coordinator(machineId, t.space)) }) else null,
                        titleAction = "Open ${space.ifEmpty { "its space" }}'s Assistant",
                    ) {
                        if (t.chat.isNotEmpty() && t.status != WorkStatus.Done) BarIcon(
                            HerdrIcons.Phone, if (callHere && call.on) "End the call" else "Call ${t.name}",
                            onClick = {
                                if (callHere && call.on) calls.stop()
                                else askMic { allowed -> if (allowed) calls.start(machineId, t.space, t.id, t.name) }
                            },
                            enabled = state.online, selected = callHere && call.on,
                        )
                        if (view == TaskView.Chat && th != null && !find.open) BarIcon(HerdrIcons.Search, ChatFind.PLACEHOLDER, { find.open = true })
                        BarIcon(HerdrIcons.Ellipsis, "Details and more", { sheet = TaskSheet.Menu })
                    }
                    TaskStrip(t, details, busy, onMerge, onPr = { picked = TaskView.PullRequest })
                    TaskViewTabs(t, view, onView = { picked = it }, onTerminal = openTerminal)
                }
            },
        ) { padding ->
            if (view != TaskView.Chat) Box(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize().imePadding().navigationBarsPadding()) {
                when (view) {
                    TaskView.Files, TaskView.Changes -> TaskCode(nav, session, machineId, t, view)
                    TaskView.Browser -> TaskBrowserView(session, t)
                    else -> TaskPullRequest(session, t)
                }
            } else Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize().imePadding().navigationBarsPadding()) {
                if (find.open) FindBar(find, earlier = th?.v2?.earlier?.takeIf { it }?.let {
                    {
                        scope.launch {
                            runCatching { session.awaitWorkBoard().earlier(t.space, t) }.onFailure { find.loading = false; context.toast(it.message ?: "Couldn't read earlier") }
                        }
                    }
                })
                val sizes by container.textSizes.state.collectAsStateWithLifecycle()
                ChatSurface(
                    Modifier.weight(1f).fillMaxWidth(),
                    textScale = sizes.chat,
                    composer = {
                        Column {
                            if (callHere && !typing) com.makefunstuff.herdr.ui.coordinator.CallBar(call, Modifier.padding(horizontal = Space.screen).padding(bottom = Space.xs), calls::stop)
                            if (waits.isNotEmpty() && draft.isEmpty()) {
                                WaitsForYou(waits, Modifier.padding(horizontal = Space.screen).padding(bottom = Space.xs)) { card ->
                                    val at = items.indexOfFirst { it.holds(card) }
                                    if (at >= 0) scope.launch { list.animateScrollToItem(items.size - at - if (foot) 0 else 1) }
                                }
                            }
                            replyTo?.let { r -> ReplyingTo(r, Modifier.padding(horizontal = Space.screen).padding(bottom = Space.xs)) { replyTo = null } }
                            ChatComposer(
                                draft = draft,
                                onDraft = { draft = it },
                                placeholder = "Message $kind",
                                onSend = ::sendDraft,
                                // Not waiting for its chat to be read: what you write goes to the agent all the same.
                                enabled = state.online,
                                status = when {
                                    !state.online -> "Not connected"
                                    attach.uploading -> "Uploading file…"
                                    tray.waiting -> "Copying pictures…"
                                    else -> null
                                },
                                holdSend = attach.uploading || tray.waiting,
                                attachments = { ComposerTray(tray) },
                                attached = !tray.isEmpty,
                                floating = true,
                                leading = { ComposerIconButton(HerdrIcons.Plus, "Attach files", enabled = state.online && !attach.uploading) { attachMenu = true } },
                                voice = voice.recorder,
                                dictation = voice.dictation,
                            )
                        }
                    },
                ) { bottom ->
                    when {
                        !state.online && th == null -> Box(Modifier.padding(bottom = bottom)) {
                            ChatEmpty(HerdrIcons.Monitor, state.status.label(), state.status.detail() ?: "Waiting for ${state.machine.label}…")
                        }
                        th == null -> Box(Modifier.fillMaxSize().padding(bottom = bottom), contentAlignment = Alignment.Center) {
                            val why = problem
                            if (why != null) ChatEmpty(HerdrIcons.CircleAlert, "Can't read its chat", why) else LoadingState("Reading the chat")
                        }
                        else -> CompositionLocalProvider(
                            LocalChatImages provides ChatImages(machineId, session, container.images, state.online),
                            LocalVoiceMessages provides voice,
                            LocalChatArtifacts provides ChatArtifacts(machineId, artifacts, board, artifactsRead, onArtifact),
                        ) { Box(Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = list,
                            // Newest at the bottom: it stays at its end when the keyboard opens or the message box grows.
                            reverseLayout = true,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.m, bottom = bottom + Space.xs),
                            verticalArrangement = Arrangement.spacedBy(Space.m, Alignment.Bottom),
                        ) {
                            if (foot) item(key = "foot") {
                                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                                    if (th.working) Working(kind.replaceFirstChar { it.uppercase() }, th.v2?.doing)
                                    // It asks in its terminal and no card here asks for it: what it says it waits for.
                                    th.v2?.blocked?.takeIf { it.isNotBlank() }?.let { Notice("Needs you: $it", c.red) }
                                    problem?.let { Notice(it, c.red) }
                                }
                            }
                            val notice = th.v2?.let(com.makefunstuff.herdr.core.shepherdr.ChatNotice::of)
                            if (own != null) {
                                ownRows(own)
                                item(key = "own-note") { Notice(ChatOwner.NOTE, null) }
                            } else if (items.isEmpty()) item(key = "empty") {
                                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                    val finished = t.status == WorkStatus.Ready || t.status == WorkStatus.Done || t.status == WorkStatus.Merging
                                    if (notice != null) ChatEmpty(HerdrIcons.SquareTerminal, notice.first, notice.second.orEmpty())
                                    else if (finished && openTerminal != null) ChatEmpty(
                                        HerdrIcons.SquareTerminal, "No chat was kept",
                                        "Its agent worked in its terminal, which is still open.",
                                        action = {
                                            PrimaryButton(openTerminal) {
                                                Icon(HerdrIcons.SquareTerminal, null, Modifier.size(18.dp))
                                                Spacer(Modifier.width(Space.s))
                                                Text("Open terminal")
                                            }
                                        },
                                    )
                                    else if (finished) ChatEmpty(
                                        HerdrIcons.SquareTerminal, "No chat was kept",
                                        "Its agent worked in its terminal, which has ended.",
                                    )
                                    else ChatEmpty(HerdrIcons.MessagesSquare, "Nothing said yet", "Its agent writes here as it works. You can write to it too.")
                                }
                            } else if (notice != null) item(key = "notice") { Notice(listOfNotNull(notice.first, notice.second).joinToString(". "), null) }
                            if (own == null) items(items.asReversed(), key = { it.id }) { item ->
                                Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                                    if (item.id == stick.unread) NewLine()
                                    Box(Modifier.lit(item.id, find, jump)) { ChatItem(
                                        item, answering,
                                        onAnswer = ::answer,
                                        onReply = { q -> replyTo = q },
                                        onRetry = { w -> outgoing = outgoing.filterNot { "cid:${it.cid}" == w.id }; send(w.text, w.reply) },
                                        who = kind,
                                        reactions = reactions,
                                        onJump = jump::to,
                                    ) }
                                }
                            }
                        }
                        NewestButton(stick, bottom)
                        } }
                    }
                }
            }
        }

        if (attachMenu) AttachMenu(attach, tray, online = state.online, onDismiss = { attachMenu = false })
        MessageSheet(reactions) { replyTo = it }
        val d = details
        when (sheet) {
            TaskSheet.Menu -> BoardPanel("${t.name} · ${t.title}", onDismiss = { sheet = null }) {
                Column {
                    // Its agent's terminal, the screen the chat reads.
                    TabMenus.taskTerminal(state.view, t)?.let { pane ->
                        MenuRow("Terminal", HerdrIcons.SquareTerminal, { sheet = null; nav.navigate(Routes.terminal(machineId, pane)) })
                    }
                    if (!d?.summary.isNullOrEmpty()) MenuRow("What it did", HerdrIcons.FileText, { sheet = TaskSheet.Said })
                    if (!d?.brief.isNullOrEmpty()) MenuRow("Brief", HerdrIcons.FileText, { sheet = TaskSheet.Brief })
                    if (!d?.plan.isNullOrEmpty()) MenuRow("Plan", HerdrIcons.ListChecks, { sheet = TaskSheet.Plan })
                    if (!d?.facts.isNullOrEmpty() || !d?.branch.isNullOrEmpty()) MenuRow("Facts", HerdrIcons.Info, { sheet = TaskSheet.Facts })
                    if (onArtifacts != null) MenuRow("Artifacts", HerdrIcons.LayoutDashboard, { sheet = null; onArtifacts() })
                    if (d == null) LoadingState("Reading the task")
                    ChangeScreenRow { sheet = null }
                    if (canClose) MenuRow("Close, nothing merged…", HerdrIcons.CircleStop, { sheet = null; onClose() }, enabled = busy == null, destructive = true)
                }
            }
            TaskSheet.Said -> DetailPanel("What it did", { sheet = TaskSheet.Menu }) { MarkdownText(d?.summary.orEmpty(), style = MaterialTheme.typography.bodyMedium) }
            TaskSheet.Brief -> DetailPanel("Brief", { sheet = TaskSheet.Menu }) { MarkdownText(d?.brief.orEmpty(), style = MaterialTheme.typography.bodyMedium) }
            TaskSheet.Plan -> DetailPanel("Plan", { sheet = TaskSheet.Menu }) {
                d?.plan.orEmpty().forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium) }
            }
            TaskSheet.Facts -> DetailPanel("Facts", { sheet = TaskSheet.Menu }) {
                if (!d?.branch.isNullOrEmpty()) InfoLine("Branch", d.branch)
                if (!d?.facts.isNullOrEmpty()) Text(d.facts, style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            null -> {}
        }
    }
}

/** One of a task's details in full, over its chat; back goes to the menu. */
@Composable
private fun DetailPanel(title: String, onBack: () -> Unit, content: @Composable () -> Unit) =
    BoardPanel(title, onDismiss = onBack, buttons = { PrimaryButton("Done", onClick = onBack) }) { content() }

/**
 * Under the task chat's bar: its status, its pull request, what it last said of itself (a ready one's
 * checks and review, from its facts), and the one action it waits for, Merge, at the end.
 */
@Composable
private fun TaskStrip(t: WorkTask, d: WorkTaskDetails?, busy: String?, onMerge: () -> Unit, onPr: () -> Unit) {
    val c = Herdr.colors
    val line = if (t.status == WorkStatus.Ready) d?.facts?.let(::factsLine).orEmpty().ifEmpty { t.line } else t.line
    Column(Modifier.fillMaxWidth().background(c.panel)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = Space.screen, end = Space.screen, top = Space.xs, bottom = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Its status once: a Ready one says it with its Accept button, so only its light shows; every
            // other state keeps its word because nothing else on the strip says it.
            if (t.status == WorkStatus.Ready) Led(
                t.status.color(), Modifier.semantics { contentDescription = "Ready" },
            ) else if (t.status != WorkStatus.Work) StatusBadge(t.status.label, t.status.color())
            prNumberOf(t.pr)?.let { n ->
                Spacer(Modifier.width(Space.s))
                Row(
                    Modifier.heightIn(min = 48.dp).clip(HerdrShapes.Pill).clickable(role = Role.Button, onClickLabel = "Open the pull request", onClick = onPr)
                        .padding(horizontal = Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(HerdrIcons.GitPullRequest, null, Modifier.size(14.dp), tint = c.accent)
                    Text("PR #$n", style = MaterialTheme.typography.labelMedium, color = c.accent, modifier = Modifier.padding(start = 2.dp))
                }
            }
            Text(
                line, style = MaterialTheme.typography.labelMedium, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = Space.s),
            )
            if (t.status == WorkStatus.Ready) PrimaryButton(onClick = onMerge, enabled = busy == null, modifier = Modifier.padding(start = Space.s)) {
                Icon(HerdrIcons.GitMerge, null, Modifier.size(18.dp))
                Text(if (t.accepting) "Accepting…" else "Accept")
            }
        }
        busy?.let { BusyLine(it) }
        RowDivider(0.dp)
    }
}

/** A ready task's facts at a glance: the checks and the review, without its PR and commit (the strip names the PR). */
internal fun factsLine(facts: String): String =
    facts.split(" · ").map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("PR #") && !it.startsWith("commit ") && !it.startsWith("branch ") }
        .take(2).joinToString(" · ") { it.substringBefore(" (") }
