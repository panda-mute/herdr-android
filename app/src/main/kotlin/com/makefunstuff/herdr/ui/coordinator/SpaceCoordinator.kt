package com.makefunstuff.herdr.ui.coordinator

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.LocalPanes
import com.makefunstuff.herdr.core.board.CardAnswer
import com.makefunstuff.herdr.core.board.CoordinatorThread
import com.makefunstuff.herdr.core.board.WorkBoard
import com.makefunstuff.herdr.core.board.WorkCard
import com.makefunstuff.herdr.core.board.WorkOverview
import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.host.Attachments
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.host.Images
import com.makefunstuff.herdr.core.shepherdr.OutgoingLine
import com.makefunstuff.herdr.core.shepherdr.SpaceChat
import com.makefunstuff.herdr.core.shepherdr.ChatNotice
import com.makefunstuff.herdr.core.shepherdr.SpaceChatItem
import com.makefunstuff.herdr.core.shepherdr.V2ReplyQuote
import com.makefunstuff.herdr.data.DraftStore
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.ui.artifacts.ArtifactPost
import com.makefunstuff.herdr.ui.artifacts.ChatArtifacts
import com.makefunstuff.herdr.ui.artifacts.LocalChatArtifacts
import com.makefunstuff.herdr.ui.board.AgentChip
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.common.detail
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.common.rememberAttacher
import com.makefunstuff.herdr.ui.common.rememberDraft
import com.makefunstuff.herdr.ui.common.LocalVoiceMessages
import com.makefunstuff.herdr.ui.common.VoiceMessage
import com.makefunstuff.herdr.ui.common.rememberVoiceMessages
import com.makefunstuff.herdr.core.chat.VoiceNotes
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.BubbleShape
import com.makefunstuff.herdr.ui.design.VoiceBar
import com.makefunstuff.herdr.ui.design.VoiceBarActions
import com.makefunstuff.herdr.voice.rememberVoiceMic
import com.makefunstuff.herdr.core.voice.VoiceState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import com.makefunstuff.herdr.ui.design.ChatComposer
import com.makefunstuff.herdr.ui.design.ChatSurface
import com.makefunstuff.herdr.ui.design.ComposerIconButton
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.NewLine
import com.makefunstuff.herdr.ui.design.NewestButton
import com.makefunstuff.herdr.ui.design.rememberStickToBottom
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.theme.Mono
import com.makefunstuff.herdr.ui.design.hairline
import com.makefunstuff.herdr.ui.design.HerdrShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.design.typingInShortWindow
import com.makefunstuff.herdr.ui.images.AttachMenu
import com.makefunstuff.herdr.ui.images.ChatImages
import com.makefunstuff.herdr.ui.images.ComposerTray
import com.makefunstuff.herdr.ui.images.LocalChatImages
import com.makefunstuff.herdr.ui.images.MessageImages
import com.makefunstuff.herdr.ui.images.rememberImageTray
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * A space's coordinator on Shepherdr 3, as a chat: the spaces to pick from under the bar (the one you
 * came from, else the home space), the chat live as Shepherdr sends it, with its update cards and
 * question cards answered here, and what waits on you over the composer.
 */
@Composable
internal fun SpaceCoordinator(nav: NavController, machineId: String, session: HostSession, from: String?) {
    val container = LocalContainer.current
    val panes = LocalPanes.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by session.state.collectAsStateWithLifecycle()
    var board by remember { mutableStateOf<WorkBoard?>(null) }
    var overview by remember { mutableStateOf<WorkOverview?>(null) }
    var picked by rememberSaveable(machineId) { mutableStateOf(from) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(machineId, state.online) {
        if (!state.online) return@LaunchedEffect
        while (board == null) {
            try {
                board = session.awaitWorkBoard()
                problem = null
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                problem = t.message ?: "Couldn't reach Shepherdr"
                delay(5_000)
            }
        }
    }
    // The spaces and what needs you in each, live.
    LaunchedEffect(board) {
        val b = board ?: return@LaunchedEffect
        while (true) {
            try {
                b.overviews().collect { overview = it }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                problem = t.message ?: "Couldn't read Shepherdr's spaces"
            }
            delay(5_000)
        }
    }
    val spaces = overview?.spaces.orEmpty()
    val space = spaces.firstOrNull { it.id == picked } ?: spaces.firstOrNull { it.home } ?: spaces.firstOrNull()

    // The home space's draft is the machine's, where a share puts its words.
    val draftKey = if (space == null || space.home) DraftStore.coordinator(machineId) else DraftStore.coordinator(machineId, space.id)
    var draft by rememberDraft(draftKey)
    // A list of its own for each space, so another space opens at its newest too.
    val list = remember(space?.id) { LazyListState() }
    val stick = rememberStickToBottom(list)
    // A voice message: its file goes into Shepherdr's inbox, then to the coordinator as a file sent in its chat.
    val voice = rememberVoiceMessages(
        draftKey,
        online = state.online && board != null && space != null,
        upload = { file, name ->
            val b = board ?: throw java.io.IOException("Shepherdr isn't reachable")
            val id = space?.id ?: throw java.io.IOException("No space to send it to")
            b.upload(id, name, file.readBytes(), VoiceNotes.MIME)
        },
        deliver = { remote, id ->
            val b = board ?: throw java.io.IOException("Shepherdr isn't reachable")
            b.sayFiles(space?.id ?: throw java.io.IOException("No space to send it to"), listOf(remote), cid = id)
        },
        fetch = { remote ->
            val b = board ?: throw java.io.IOException("Shepherdr isn't reachable")
            b.file(space?.id ?: throw java.io.IOException("No space"), remote)
        },
    )
    val tray = rememberImageTray(draftKey, machineId, session, state.online)
    val attach = rememberAttacher(session, onAttached = { path -> draft = Attachments.withMention(draft, path) }, onImage = tray::add)
    var attachMenu by remember { mutableStateOf(false) }
    val shared = rememberSharedFiles(machineId, session, state, tray) { path -> draft = Attachments.withMention(draft, path) }
    val uploading = attach.uploading || shared.copying

    var thread by remember(space?.id) { mutableStateOf<CoordinatorThread?>(null) }
    var outgoing by remember(space?.id) { mutableStateOf<List<OutgoingLine>>(emptyList()) }
    var replyTo by remember(space?.id) { mutableStateOf<V2ReplyQuote?>(null) }
    /** The question cards an answer is on its way to, by question id. */
    var answering by remember(space?.id) { mutableStateOf<Set<String>>(emptySet()) }
    var chatProblem by remember(space?.id) { mutableStateOf<String?>(null) }
    /** The task whose Accept card is up. */
    var accepting by remember(space?.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(board, space?.id) {
        val b = board ?: return@LaunchedEffect
        val id = space?.id ?: return@LaunchedEffect
        while (true) {
            try {
                b.coordinatorThreads(id).collect { t ->
                    thread = t
                    chatProblem = null
                    t.v2?.let { chat ->
                        outgoing = SpaceChat.stillOutgoing(chat, outgoing)
                        // A voice message Shepherdr held, then couldn't hand over: on the phone again, with Retry.
                        chat.failed?.takeIf { it.isNotEmpty() }?.let { f -> voice.refused(f.associate { it.cid to it.error }) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                chatProblem = t.message ?: "Couldn't read the chat"
            }
            delay(3_000)
        }
    }
    val t = thread
    val items = remember(t, outgoing, voice.notes) { t?.v2?.let { SpaceChat.items(t.space, it, outgoing, voice.notes, container.clock()) }.orEmpty() }

    fun send(text: String, reply: V2ReplyQuote?) {
        val b = board ?: return
        val id = space?.id ?: return
        val line = OutgoingLine(UUID.randomUUID().toString(), text, reply)
        outgoing = outgoing + line
        stick.sent()
        scope.launch {
            runCatching { b.say(id, text, cid = line.cid, reply = reply?.id) }.onFailure {
                outgoing = outgoing - line
                context.toast(it.message ?: "Couldn't send")
            }
        }
    }

    fun sendDraft() {
        val typed = draft
        val pictures = tray.images
        // The pictures are named in front of what you typed.
        val text = tray.message(typed)
        if (text.isEmpty() || tray.waiting || board == null) return
        send(text, replyTo)
        draft = DraftStore.afterSend(draft, typed)
        tray.sent(pictures)
        replyTo = null
    }

    fun answer(a: CardAnswer) {
        val b = board ?: return
        val q = a.card.question.id
        answering = answering + q
        scope.launch {
            runCatching { b.answer(a) }.onFailure { context.toast(it.message ?: "Couldn't answer") }
            answering = answering - q
        }
    }


    val waits = t?.cards.orEmpty()
    // Under the newest message: only when it says something, else it would be the newest item, and a reader just over it "up the chat".
    val foot = t?.working == true || (chatProblem ?: problem) != null || shared.waitNote != null
    // At the newest message, and brought down to every new one: the rules every chat shares.
    stick.Follow(remember(items) { items.map { it.id } }, content = items to foot)
    stick.SentWith(voice.notes.map { it.id })
    val typing = typingInShortWindow()
    val c = Herdr.colors
    // The coordinator's voice: on for one chat at a time, this one's bar shown over its composer.
    val speech = container.coordinatorVoice
    val heard by speech.state.collectAsStateWithLifecycle()
    val voiceHere = heard.on && heard.machineId == machineId && heard.space == space?.id
    val askMic = rememberVoiceMic()
    // A voice call with this space's Assistant (Shepherdr's voice agent on the machine).
    val calls = container.voiceCalls
    val call by calls.state.collectAsStateWithLifecycle()
    val callHere = call.machineId == machineId && call.space == space?.id && call.task == null && (call.on || call.problem != null)
    Scaffold(
        containerColor = c.ground,
        topBar = {
            if (!typing) HerdrTopBar(
                "Assistant",
                subtitle = listOfNotNull(space?.let(::spaceName), state.machine.label, stateWords(t, state.online, state.status.label())).joinToString(" · "),
                onBack = { nav.popBackStack() },
                actions = {
                    // The space's artifacts, where Shepherdr keeps them.
                    if (overview?.artifacts != null && space != null) {
                        BarIcon(HerdrIcons.LayoutDashboard, "Artifacts", { nav.navigate(Routes.artifacts(machineId, space.id)) })
                    }
                    BarIcon(
                        HerdrIcons.Phone, if (callHere && call.on) "End the call" else "Call the Assistant",
                        onClick = {
                            val s = space
                            if (callHere && call.on) calls.stop()
                            else if (s != null) askMic { allowed -> if (allowed) calls.start(machineId, s.id, null, "the ${spaceName(s)} Assistant") }
                        },
                        enabled = space != null && state.online, selected = callHere && call.on,
                    )
                    BarIcon(
                        HerdrIcons.Headphones, if (voiceHere) "Turn voice off" else "Hear the Assistant",
                        onClick = {
                            val s = space
                            if (voiceHere) speech.stop()
                            else if (s != null) askMic { speech.start(machineId, s.id, spaceName(s)) }
                        },
                        enabled = space != null, selected = voiceHere,
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize().imePadding().navigationBarsPadding()) {
            if (!typing && spaces.size > 1) SpacePicker(spaces, space?.id) { picked = it }
            val sizes by container.textSizes.state.collectAsStateWithLifecycle()
            ChatSurface(
                Modifier.weight(1f).fillMaxWidth(),
                textScale = sizes.chat,
                composer = {
                    Column {
                        if (callHere && !typing) CallBar(call, Modifier.padding(horizontal = Space.screen).padding(bottom = Space.xs), calls::stop)
                        if (waits.isNotEmpty() && draft.isEmpty()) {
                            WaitsForYou(waits, Modifier.padding(horizontal = Space.screen).padding(bottom = Space.xs)) { card ->
                                val at = items.indexOfFirst { it.holds(card) }
                                if (at >= 0) scope.launch { list.animateScrollToItem(items.size - at - if (foot) 0 else 1) }
                            }
                        }
                        if (voiceHere) {
                            VoiceBar(
                                heard,
                                remember(speech) {
                                    VoiceBarActions(
                                        talk = speech::talk, hush = speech::hush, handsFree = speech::setHandsFree,
                                        mute = speech::setMuted, speed = speech::setSpeed, close = speech::stop,
                                    )
                                },
                                Modifier.padding(horizontal = Space.screen).padding(bottom = Space.xs),
                            )
                        }
                        replyTo?.let { r -> ReplyingTo(r, Modifier.padding(horizontal = Space.screen).padding(bottom = Space.xs)) { replyTo = null } }
                        ChatComposer(
                            draft = draft,
                            onDraft = { draft = it },
                            placeholder = space?.let { "Tell ${it.name}'s Assistant…" } ?: "Tell the Assistant…",
                            onSend = ::sendDraft,
                            enabled = state.online && board != null && space != null,
                            status = when {
                                !state.online -> "Not connected"
                                uploading -> "Uploading file…"
                                tray.waiting -> "Copying pictures…"
                                else -> null
                            },
                            holdSend = uploading || tray.waiting,
                            attachments = { ComposerTray(tray) },
                            attached = !tray.isEmpty,
                            floating = true,
                            leading = { ComposerIconButton(HerdrIcons.Plus, "Attach files", enabled = state.online && !uploading) { attachMenu = true } },
                            voice = voice.recorder,
                        )
                    }
                },
            ) { bottom ->
                when {
                    !state.online && t == null -> Box(Modifier.padding(bottom = bottom)) {
                        ChatEmpty(HerdrIcons.Monitor, state.status.label(), listOfNotNull(state.status.detail() ?: "Waiting for ${state.machine.label}…", shared.waitNote).joinToString("\n\n"))
                    }
                    t == null -> Box(Modifier.fillMaxSize().padding(bottom = bottom), contentAlignment = Alignment.Center) {
                        val why = chatProblem ?: problem
                        if (why != null) ChatEmpty(HerdrIcons.CircleAlert, "Can't reach Shepherdr", why) else LoadingState("Reading the chat")
                    }
                    else -> CompositionLocalProvider(
                        LocalChatImages provides ChatImages(machineId, session, container.images, state.online),
                        LocalVoiceMessages provides voice,
                        LocalChatArtifacts provides ChatArtifacts(machineId, overview?.artifacts, board, read = overview != null) { id, v -> nav.navigate(Routes.artifact(machineId, id, v)) },
                    ) {
                        LazyColumn(
                            state = list,
                            // Newest at the bottom: it stays at its end when the keyboard opens or the message box grows.
                            reverseLayout = true,
                            // A touch anywhere in the chat stops the voice at once (and does what it touched too).
                            modifier = Modifier.fillMaxSize().then(
                                if (voiceHere && heard.phase == VoiceState.Phase.Speaking) Modifier.pointerInput(Unit) {
                                    awaitEachGesture { awaitFirstDown(requireUnconsumed = false); speech.hush() }
                                } else Modifier,
                            ),
                            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.m, bottom = bottom + Space.xs),
                            verticalArrangement = Arrangement.spacedBy(Space.m, Alignment.Bottom),
                        ) {
                            if (foot) item(key = "foot") {
                                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                                    if (t.working) Working("Assistant", t.v2?.doing)
                                    (chatProblem ?: problem)?.let { Notice(it, c.red) }
                                    shared.waitNote?.let { Notice(it, null) }
                                }
                            }
                            if (items.isEmpty()) {
                                item(key = "empty") {
                                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                        val n = t.v2?.let(ChatNotice::of)
                                        if (n != null) ChatEmpty(HerdrIcons.SquareTerminal, n.first, n.second.orEmpty())
                                        else ChatEmpty(HerdrIcons.MessagesSquare, ChatNotice.EMPTY_TITLE, ChatNotice.EMPTY_BODY)
                                    }
                                }
                            } else t.v2?.let(ChatNotice::of)?.let { n ->
                                // Shepherdr's notice instead of the conversation; what it read of the chat still shows.
                                item(key = "notice") { Notice(listOfNotNull(n.first, n.second).joinToString(". "), null) }
                            }
                            items(items.asReversed(), key = { it.id }) { item ->
                                Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                                    if (item.id == stick.unread) NewLine()
                                    ChatItem(
                                        item, answering,
                                        onAnswer = ::answer,
                                        onReply = { q -> replyTo = q },
                                        onRetry = { w -> outgoing = outgoing.filterNot { "cid:${it.cid}" == w.id }; send(w.text, w.reply) },
                                        who = "the Assistant",
                                        onAccept = { id -> accepting = id },
                                        agentOf = { id -> overview?.tasks?.firstOrNull { it.id == id && it.chat.isNotEmpty() }?.agent?.ifEmpty { "agent" } },
                                        // A task opens as its agent's chat, on the board.
                                        onAgent = { id -> panes.openBoard(nav, machineId, id, alone = true) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (t != null) NewestButton(stick, bottom)
            }
        }
    }
    if (attachMenu) AttachMenu(attach, tray, online = state.online, onDismiss = { attachMenu = false })
    accepting?.let { id ->
        // Its task as the board has it; where the board isn't read yet, enough of it for the card, which reads the rest.
        val task = overview?.tasks?.firstOrNull { it.id == id || it.name == id }
            ?: com.makefunstuff.herdr.core.board.WorkTask(id, id.substringAfter(':'), "", space?.id.orEmpty(), com.makefunstuff.herdr.core.board.WorkStatus.Ready)
        com.makefunstuff.herdr.ui.board.AcceptSheet(
            board, task, onDismiss = { accepting = null },
            onProof = { proof -> nav.navigate(Routes.artifact(machineId, proof, null)) },
            onAccepted = { context.toast(it) },
        )
    }
}

/** A space as people say it: the home space is "Home". */
private fun spaceName(s: WorkSpace) = if (s.home) "Home" else s.name

private fun stateWords(t: CoordinatorThread?, online: Boolean, offline: String): String = when {
    !online -> offline
    t == null -> "Shepherdr"
    t.working -> "working"
    t.cards.isNotEmpty() -> "waits for you"
    else -> "idle"
}

/** Whether [card] is asked in this item: its own question, or one of a task's copied into an update card. */
internal fun SpaceChatItem.holds(card: WorkCard): Boolean = when (this) {
    is SpaceChatItem.Question -> this.card.chat == card.chat && this.card.question.id == card.question.id
    is SpaceChatItem.Update -> asks.any { it.chat == card.chat && it.question.id == card.question.id }
    else -> false
}

/** The machine's spaces, one chip each, a red dot on one that needs you. */
@Composable
private fun SpacePicker(spaces: List<WorkSpace>, selected: String?, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.screen),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        spaces.forEach { s ->
            HerdrChip(
                selected = s.id == selected,
                onClick = { onPick(s.id) },
                // The dot says it to the eye, the words to a screen reader.
                label = {
                    Text(
                        spaceName(s), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { contentDescription = spaceName(s) + if (s.needsYou) ", needs you" else "" },
                    )
                },
                leadingIcon = if (s.needsYou) ({ StatusDot(Herdr.colors.red, size = 8) }) else null,
            )
        }
    }
}

/** Over the composer: how many questions wait for you, the first by name; a tap goes to it. */
@Composable
internal fun WaitsForYou(cards: List<WorkCard>, modifier: Modifier, onGo: (WorkCard) -> Unit) {
    val c = Herdr.colors
    val first = cards.first()
    QuietCard(modifier, onClick = { onGo(first) }, onClickLabel = "Go to the question") {
        Row(Modifier.padding(horizontal = Space.l, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(c.red, size = 8)
            Spacer(Modifier.width(Space.s))
            Text(
                if (cards.size == 1) "Waits for you" else "${cards.size} wait for you",
                style = MaterialTheme.typography.labelLarge, color = c.red,
            )
            Spacer(Modifier.width(Space.s))
            Text(
                listOfNotNull(first.task.takeIf { it.isNotEmpty() && it !in first.question.title }, first.question.title).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Over the composer while you reply to a message: its start, and an X that stops replying. */
@Composable
internal fun ReplyingTo(q: V2ReplyQuote, modifier: Modifier, onCancel: () -> Unit) {
    val c = Herdr.colors
    QuietCard(modifier) {
        Row(Modifier.padding(start = Space.l), verticalAlignment = Alignment.CenterVertically) {
            Icon(HerdrIcons.Reply, null, Modifier.size(16.dp), tint = c.muted)
            Spacer(Modifier.width(Space.s))
            Text("Reply to: " + q.text, style = MaterialTheme.typography.bodyMedium, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            ComposerIconButton(HerdrIcons.X, "Don't reply", onClick = onCancel)
        }
    }
}

/**
 * One item of a Shepherdr chat, a space's Assistant's or a task's agent's ([who], as your waiting words say it).
 * [agentOf] names the agent of a news line's task ("Claude Code"), whose chip [onAgent] opens; a ready line's
 * Accept opens its Accept card ([onAccept]).
 */
@Composable
internal fun ChatItem(
    item: SpaceChatItem,
    answering: Set<String>,
    onAnswer: (CardAnswer) -> Unit,
    onReply: (V2ReplyQuote) -> Unit,
    onRetry: (SpaceChatItem.Words) -> Unit,
    who: String = "the Assistant",
    agentOf: (String) -> String? = { null },
    onAgent: (String) -> Unit = {},
    onAccept: ((String) -> Unit)? = null,
) {
    val c = Herdr.colors
    when (item) {
        is SpaceChatItem.Words -> Words(item, onReply, onRetry, who)
        is SpaceChatItem.Update -> Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            UpdateRow(item, agentOf(item.task)?.let { a -> a to { onAgent(item.task) } })
            if (item.accept && onAccept != null) Row(Modifier.fillMaxWidth().padding(start = 20.dp)) {
                PrimaryButton(com.makefunstuff.herdr.core.shepherdr.Accept.ACCEPT) { onAccept(item.task) }
            }
            item.asks.forEach { card -> QuestionCard(card, null, card.question.id in answering, onAnswer) }
            item.answered?.let { Text("Answered: $it", style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.padding(start = 20.dp)) }
        }
        is SpaceChatItem.Question -> Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (item.intro.isNotEmpty()) MarkdownText(item.intro, Modifier.padding(horizontal = Space.xs), style = MaterialTheme.typography.bodyLarge)
            QuestionCard(item.card, item.answer, item.card.question.id in answering, onAnswer)
        }
        is SpaceChatItem.Voice -> VoiceMessage(item.path, item.millis, item.you, item.note)
        is SpaceChatItem.File -> Row(
            Modifier.fillMaxWidth(), horizontalArrangement = if (item.you) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HerdrIcons.File, null, Modifier.size(16.dp), tint = c.muted)
            Spacer(Modifier.width(Space.xs))
            Text(item.name, style = MaterialTheme.typography.bodyMedium, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        is SpaceChatItem.Artifact -> ArtifactPost(item.artifact, item.version, item.intro, item.later)
        is SpaceChatItem.NewSession -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { RowDivider(0.dp) }
            Text("New session", style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.padding(horizontal = Space.s))
            Box(Modifier.weight(1f)) { RowDivider(0.dp) }
        }
        is SpaceChatItem.Note -> Text(
            item.text, style = MaterialTheme.typography.labelMedium, color = c.muted,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.xs), textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
        is SpaceChatItem.Steps -> StepsLine(item)
        is SpaceChatItem.Unreadable -> Text(
            "Something this app can't show yet. Open the terminal or Shepherdr to see it.",
            style = MaterialTheme.typography.labelMedium, color = c.muted,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.xs),
        )
    }
}

/** A turn's tool steps as one quiet line ("▸ Worked for 14m · 9 steps"); a tap shows the rows that say most. */
@Composable
private fun StepsLine(s: SpaceChatItem.Steps) {
    val c = Herdr.colors
    var open by rememberSaveable(s.id) { mutableStateOf(false) }
    val head = listOfNotNull(
        s.millis?.let { "Worked for " + SpaceChat.took(it) } ?: "Working",
        if (s.steps == 1) "1 step" else "${s.steps} steps",
        s.failed.takeIf { it > 0 }?.let { "$it failed" },
    ).joinToString(" · ")
    Column(
        Modifier.fillMaxWidth().clickable(enabled = s.rows.isNotEmpty(), onClickLabel = if (open) "Fold the steps" else "Show the steps") { open = !open }
            .padding(horizontal = Space.xs, vertical = Space.xs),
    ) {
        Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (open) HerdrIcons.ChevronDown else HerdrIcons.ChevronRight, null, Modifier.size(14.dp), tint = c.faint)
            Spacer(Modifier.width(Space.xs))
            Text(head, style = MaterialTheme.typography.labelMedium, color = if (s.failed > 0) c.red else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (open) Column(Modifier.padding(start = 18.dp, top = Space.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            s.rows.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (s.more > 0) Text("and ${s.more} more", style = MaterialTheme.typography.bodySmall, color = c.faint)
        }
    }
}

/** Yours on the right on a card, the coordinator's as prose on the left; a long press replies to it. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun Words(w: SpaceChatItem.Words, onReply: (V2ReplyQuote) -> Unit, onRetry: (SpaceChatItem.Words) -> Unit, who: String) {
    val c = Herdr.colors
    val mine = w.you
    val split = remember(w.text, mine) { if (mine) Images.split(w.text) else Images.Split(emptyList(), w.text) }
    val quote = V2ReplyQuote(id = w.id, who = if (mine) "you" else "agent", text = w.text.take(120))
    Row(
        Modifier.fillMaxWidth().padding(start = if (mine) 48.dp else 0.dp, end = if (mine) 0.dp else 32.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            w.reply?.let { r ->
                Row(Modifier.padding(bottom = Space.xs, start = Space.xs, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    Icon(HerdrIcons.Reply, null, Modifier.size(14.dp), tint = c.muted)
                    Spacer(Modifier.width(Space.xs))
                    Text(r.text, style = MaterialTheme.typography.labelMedium, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Surface(
                color = if (mine) c.card else Color.Transparent,
                contentColor = c.text,
                shape = BubbleShape,
                border = if (mine && c.bubbleBorder.alpha > 0f) androidx.compose.foundation.BorderStroke(1.dp, c.bubbleBorder) else null,
                modifier = Modifier.alpha(if (w.waiting) 0.72f else 1f).combinedClickable(
                    onClick = {}, onLongClick = if (w.id.startsWith("cid:")) null else ({ onReply(quote) }), onLongClickLabel = "reply",
                ),
            ) {
                Column(Modifier.padding(horizontal = if (mine) Space.l else Space.xs, vertical = if (mine) Space.m else Space.xs)) {
                    if (split.paths.isNotEmpty()) MessageImages(split.paths, Modifier.padding(bottom = if (split.text.isEmpty()) 0.dp else Space.s))
                    if (split.text.isNotEmpty()) MarkdownText(split.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (w.reactions.isNotEmpty()) {
                FlowRow(Modifier.padding(top = Space.xs, start = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    w.reactions.groupBy { it.emoji }.forEach { (emoji, by) ->
                        Text(if (by.size > 1) "$emoji ${by.size}" else emoji, style = MaterialTheme.typography.labelMedium, color = c.muted)
                    }
                }
            }
            when {
                w.failed != null -> Row(Modifier.padding(top = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    Text("Not sent: ${w.failed}", style = MaterialTheme.typography.labelMedium, color = c.red, modifier = Modifier.weight(1f, fill = false))
                    if (w.id.startsWith("cid:")) QuietButton(onClick = { onRetry(w) }, color = c.accent) { Text("Retry") }
                }
                w.waiting -> Row(Modifier.padding(top = Space.xs, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(c.faint, size = 6)
                    Spacer(Modifier.width(6.dp))
                    Text("Sending · waiting for $who", style = MaterialTheme.typography.labelMedium, color = c.faint)
                }
            }
        }
    }
}

/**
 * Shepherdr's card about a task: its state's dot, the task and what happened, its pull request, and
 * [agent]'s chip (its CLI's name and what opens its chat) once the task has one.
 */
@Composable
private fun UpdateRow(u: SpaceChatItem.Update, agent: Pair<String, () -> Unit>?) {
    val c = Herdr.colors
    val uri = LocalUriHandler.current
    val tint = if (u.red != null) c.red else u.status?.let { statusColor(it) } ?: c.idle
    QuietCard(onClick = u.pr?.url?.takeIf { it.isNotEmpty() }?.let { url -> { runCatching { uri.openUri(url) } } }, onClickLabel = "Open the pull request") {
        Row(Modifier.padding(horizontal = Space.l, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
            if (u.red != null) Icon(HerdrIcons.CircleAlert, null, Modifier.size(16.dp), tint = c.red) else StatusDot(tint, size = 8)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (u.task.isNotEmpty()) {
                        Text(u.task, style = MaterialTheme.typography.titleSmall, color = c.strong)
                        Spacer(Modifier.width(Space.s))
                    }
                    Text(u.text, style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            u.pr?.takeIf { it.number > 0 }?.let { pr ->
                Spacer(Modifier.width(Space.s))
                Icon(HerdrIcons.GitPullRequest, null, Modifier.size(14.dp), tint = c.muted)
                Spacer(Modifier.width(Space.xs))
                Text("#${pr.number}", style = MaterialTheme.typography.labelLarge, color = c.muted)
            }
            agent?.let { (cli, open) -> AgentChip(cli, open) }
        }
    }
}

@Composable
private fun statusColor(s: WorkStatus): Color {
    val c = Herdr.colors
    return when (s) {
        WorkStatus.Need -> c.red
        WorkStatus.Ready -> c.done
        WorkStatus.Work, WorkStatus.Merging -> c.working
        else -> c.idle
    }
}

/**
 * A question card: its title, a plan's steps, and its choices as rows to tap (each with its line of
 * why); a choice that needs a note (a plan sent back), an Other answer or a plain question takes words
 * first. Answered, it shows the answer and no choices.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(card: WorkCard, answer: String?, busy: Boolean, onAnswer: (CardAnswer) -> Unit) {
    val c = Herdr.colors
    val q = card.question
    val open = answer == null
    /** The choice that waits for its note (-2: an Other or plain answer); null while none does. */
    var writing by remember(q.id) { mutableStateOf<Int?>(if (q.kind == "Reply") -2 else null) }
    var words by remember(q.id) { mutableStateOf("") }
    var picks by remember(q.id) { mutableStateOf<Set<Int>>(emptySet()) }
    /** A card that asks several questions: the answer picked for each, in order. */
    var parts by remember(q.id) { mutableStateOf<Map<Int, Set<Int>>>(emptyMap()) }
    QuietCard(edge = if (open) c.lineStrong else c.line) {
        Column(Modifier.padding(start = Space.l, end = Space.l, top = Space.m, bottom = if (open) Space.xs else Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    q.asks ?: when {
                        q.kind == "Plan" -> "Plan"
                        card.task.isNotEmpty() -> "${card.task} asks"
                        else -> "Question"
                    },
                    style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.weight(1f),
                )
                if (open) StatusBadge(WorkStatus.Need.label, c.red)
            }
            Spacer(Modifier.size(Space.xs))
            MarkdownText(q.title, style = MaterialTheme.typography.titleMedium, color = c.strong)
            // A plan, or what a permission asks to run, in the CLI's own words.
            q.plan?.takeIf { it.isNotBlank() }?.let { plan ->
                Box(Modifier.padding(top = Space.s).fillMaxWidth().heightIn(max = 320.dp).background(c.panel, HerdrShapes.Field).verticalScroll(rememberScrollState()).padding(Space.s)) {
                    MarkdownText(plan, style = MaterialTheme.typography.bodyMedium)
                }
            }
            q.screen?.takeIf { it.isNotBlank() && q.press }?.let { screen ->
                Text(screen, style = MaterialTheme.typography.bodySmall, fontFamily = Mono, color = c.text, modifier = Modifier.padding(top = Space.s), maxLines = 12)
            }
            q.steps?.takeIf { it.isNotEmpty() }?.let { steps ->
                Column(Modifier.padding(top = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    steps.forEachIndexed { i, s ->
                        Row {
                            Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.width(22.dp))
                            Text(s, style = MaterialTheme.typography.bodyMedium, color = c.text)
                        }
                    }
                }
            }
            if (!open) {
                Row(Modifier.padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Icon(HerdrIcons.Check, null, Modifier.size(16.dp), tint = c.done)
                    Spacer(Modifier.width(Space.xs))
                    Text(answer!!, style = MaterialTheme.typography.bodyMedium, color = c.muted)
                }
            }
        }
        if (!open) return@QuietCard
        if (busy) {
            Text("Sending your answer…", style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(horizontal = Space.l, vertical = Space.m))
            return@QuietCard
        }
        val w = writing
        if (w != null) {
            Column(Modifier.padding(horizontal = Space.l, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                HerdrField(
                    words, { words = it }, Modifier.fillMaxWidth(), singleLine = false,
                    placeholder = { Text(if (w >= 0) "Say why, for “${q.choices.getOrNull(w)?.label.orEmpty()}”" else "Your answer", color = c.muted) },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    PrimaryButton("Send", enabled = words.isNotBlank()) {
                        onAnswer(if (w >= 0) CardAnswer(card, w, note = words.trim()) else CardAnswer(card, -1, other = words.trim()))
                    }
                    if (q.kind != "Reply") SecondaryButton("Back") { writing = null }
                }
            }
            return@QuietCard
        }
        q.parts?.takeIf { it.isNotEmpty() }?.let { ps ->
            // Several questions on one card: a pick for each, then Send with every answer in its words.
            Column(Modifier.padding(horizontal = Space.l, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                ps.forEachIndexed { pi, p ->
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text(p.header ?: p.title, style = MaterialTheme.typography.titleSmall, color = c.strong)
                        if (p.header != null) Text(p.title, style = MaterialTheme.typography.bodyMedium, color = c.text)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            p.choices.forEachIndexed { ci, ch ->
                                val on = ci in parts[pi].orEmpty()
                                HerdrChip(
                                    selected = on,
                                    onClick = {
                                        val was = parts[pi].orEmpty()
                                        parts = parts + (pi to if (p.multi) (if (on) was - ci else was + ci) else setOf(ci))
                                    },
                                    label = { Text(ch.label) },
                                )
                            }
                        }
                    }
                }
                PrimaryButton("Send", enabled = ps.indices.all { parts[it].orEmpty().isNotEmpty() }) {
                    onAnswer(CardAnswer(card, -1, answers = ps.mapIndexed { pi, p -> parts[pi].orEmpty().sorted().joinToString(", ") { p.choices[it].label } }))
                }
            }
            return@QuietCard
        }
        q.choices.forEachIndexed { i, choice ->
            RowDivider(0.dp)
            val picked = i in picks
            ChoiceRow(choice.label, choice.description, if (q.multi) picked else null) {
                when {
                    q.multi -> picks = if (picked) picks - i else picks + i
                    q.noteRequiredFor == i -> writing = i
                    else -> onAnswer(CardAnswer(card, i))
                }
            }
        }
        if (q.other) {
            RowDivider(0.dp)
            ChoiceRow("Other…", null, null) { writing = -2 }
        }
        if (q.multi) {
            Box(Modifier.padding(horizontal = Space.l, vertical = Space.s)) {
                PrimaryButton("Send", enabled = picks.isNotEmpty()) { onAnswer(CardAnswer(card, picks.min(), choices = picks.sorted())) }
            }
        }
    }
}

/** One choice on a question card: its label, its line of why, a box to tick on a multiple choice ([ticked] non-null). */
@Composable
private fun ChoiceRow(label: String, description: String?, ticked: Boolean?, onClick: () -> Unit) {
    val c = Herdr.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ticked != null) {
            Icon(if (ticked) HerdrIcons.CircleCheck else HerdrIcons.Circle, null, Modifier.size(18.dp), tint = if (ticked) c.accent else c.muted)
            Spacer(Modifier.width(Space.m))
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = c.strong, fontWeight = FontWeight.Medium)
            description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
        }
        if (ticked == null) Icon(HerdrIcons.ChevronRight, null, Modifier.size(16.dp), tint = c.muted)
    }
}
