package com.makefunstuff.herdr.ui.coordinator

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.LocalPanes
import com.makefunstuff.herdr.Routes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.makefunstuff.herdr.core.board.BoardApi
import com.makefunstuff.herdr.core.board.BoardGlance
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.CoordinatorAsk
import com.makefunstuff.herdr.core.board.TaskMentions
import com.makefunstuff.herdr.bubble.BubbleChat
import com.makefunstuff.herdr.bubble.LocalBubble
import com.makefunstuff.herdr.ui.common.rememberFloat
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.core.board.ChatLine
import com.makefunstuff.herdr.core.board.CoordinatorChat
import com.makefunstuff.herdr.core.board.CoordinatorView
import com.makefunstuff.herdr.core.board.Said
import com.makefunstuff.herdr.core.chat.SentMatch
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.common.detail
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BubbleShape
import com.makefunstuff.herdr.ui.design.ReplyBubbleShape
import com.makefunstuff.herdr.ui.design.ChatComposer
import com.makefunstuff.herdr.ui.design.ChatSurface
import com.makefunstuff.herdr.ui.design.typingInShortWindow
import com.makefunstuff.herdr.ui.design.ComposerToggle
import com.makefunstuff.herdr.ui.design.ComposerIconButton
import com.makefunstuff.herdr.ui.common.rememberAttacher
import com.makefunstuff.herdr.core.host.Images
import com.makefunstuff.herdr.ui.images.AttachMenu
import com.makefunstuff.herdr.ui.images.ChatImages
import com.makefunstuff.herdr.ui.images.ComposerTray
import com.makefunstuff.herdr.ui.images.ImageCodec
import com.makefunstuff.herdr.ui.images.ImageTray
import com.makefunstuff.herdr.ui.images.LocalChatImages
import com.makefunstuff.herdr.ui.images.MessageImages
import com.makefunstuff.herdr.ui.images.rememberImageTray
import androidx.compose.runtime.CompositionLocalProvider
import com.makefunstuff.herdr.core.host.Attachments
import com.makefunstuff.herdr.ui.common.rememberDraft
import com.makefunstuff.herdr.data.DraftStore
import androidx.compose.foundation.layout.navigationBarsPadding
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.NewLine
import com.makefunstuff.herdr.ui.design.NewestButton
import com.makefunstuff.herdr.ui.design.rememberStickToBottom
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.StateBlock
import com.makefunstuff.herdr.ui.design.Toasts
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.theme.color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The work board's Coordinator on one machine, as a chat. What it shows and what it sends go the
 * way another machine's board reaches it (`herdr-projects remote list|dispatch`), so the phone and
 * the board's own box share one conversation.
 */
@Composable
fun CoordinatorScreen(nav: NavController, machineId: String, space: String? = null) {
    val session = LocalContainer.current.hosts.session(machineId)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val state by session.state.collectAsStateWithLifecycle()
    // Shepherdr 3 has a coordinator in each space; herdr-projects 1.x one for the machine.
    if (state.boardApi is BoardApi.V2) SpaceCoordinator(nav, machineId, session, space) else BoardCoordinator(nav, machineId, session)
}

/** herdr-projects 1.x's one Coordinator on the machine. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoardCoordinator(nav: NavController, machineId: String, session: HostSession) {
    val container = LocalContainer.current
    val panes = LocalPanes.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by session.state.collectAsStateWithLifecycle()
    // The board's tasks as the app last read them: the strip under the bar, the tasks the answers name, the asks.
    val boardLists by container.boards.lists.collectAsStateWithLifecycle()
    val tasks = boardLists[machineId]?.tasks
    val byKey = remember(tasks) { tasks.orEmpty().filter { it.key.isNotEmpty() }.associateBy { it.key } }
    var view by remember { mutableStateOf<CoordinatorView?>(null) }
    var talk by remember { mutableStateOf<List<Said>>(emptyList()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var tooOld by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    /** Sent from here, not yet seen in what the board answers. */
    var outbox by remember { mutableStateOf<SentMatch.Pending?>(null) }
    var outboxSeen by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    // Kept per machine across navigation, rotation and restarts; cleared only once the message is sent.
    var draft by rememberDraft(DraftStore.coordinator(machineId))
    /** The board's Tab for the next message: what the Coordinator adds from it gets an agent at once. */
    var startAgents by rememberSaveable { mutableStateOf(false) }
    // Pictures for the Coordinator wait in the composer (and are copied to its machine) until the message goes.
    val tray = rememberImageTray(DraftStore.coordinator(machineId), machineId, session, state.online)
    // A file for the Coordinator: copied to the board's machine, then named at the start of the draft.
    val attach = rememberAttacher(session, onAttached = { path -> draft = Attachments.withMention(draft, path) }, onImage = tray::add)
    var attachMenu by remember { mutableStateOf(false) }
    val shared = rememberSharedFiles(machineId, session, state, tray) { path -> draft = Attachments.withMention(draft, path) }
    val uploading = attach.uploading || shared.copying
    val waitNote = shared.waitNote
    val readNow = remember { Channel<Unit>(Channel.CONFLATED) }
    val readTasksNow = remember { Channel<Unit>(Channel.CONFLATED) }
    val list = rememberLazyListState()
    val stick = rememberStickToBottom(list)
    val owner = LocalLifecycleOwner.current

    // The board's tasks, while the screen is in front: every quarter minute, and as soon as the Coordinator answers.
    LaunchedEffect(machineId, state.online, state.boardPath) {
        if (!state.online || state.boardPath == null) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var asked = false
            while (true) {
                container.boards.readOne(machineId, freshMs = if (asked) 0 else 3_000)
                asked = withTimeoutOrNull(15_000) { readTasksNow.receive() } != null
            }
        }
    }

    // Read while the screen is open: often while the Coordinator works, so its answer comes in as it's written.
    LaunchedEffect(machineId, state.online, state.boardPath) {
        if (!state.online || state.boardPath == null) return@LaunchedEffect
        while (true) {
            try {
                val answer = session.awaitBoard().list()
                host = answer.host
                tooOld = answer.tooOld
                val v = answer.coordinator
                view = v
                if (v != null) {
                    talk = CoordinatorChat.merge(talk, v.talk)
                    val mine = outbox
                    if (mine != null) {
                        if (v.sent.isNotEmpty()) outboxSeen = true
                        val said = talk.filter { it.you }.map { it.text }
                        // Confirmed by one more matching message than before it was sent; or the board took it
                        // in and has since answered, though its conversation doesn't hold it.
                        if (SentMatch.count(mine.text, said) > mine.baseline || (outboxSeen && v.sent.isEmpty())) outbox = null
                    }
                }
                problem = null
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                problem = t.message ?: "Couldn't read the board"
            }
            loaded = true
            val busy = view?.busy == true || outbox != null
            withTimeoutOrNull(if (busy) 1200L else 4000L) { readNow.receive() }
        }
    }

    val v = view
    val lines = remember(v, talk, outbox) {
        val mine = outbox
        val base = v?.let { CoordinatorChat.lines(it, talk, mine?.takeIf { m -> m.text.trim() == it.sent.trim() }?.baseline) }.orEmpty()
        if (mine != null && SentMatch.count(mine.text, base.filter { it.you }.map { it.text }) <= mine.baseline) base + ChatLine(true, mine.text, pending = true) else base
    }
    // At the newest line, and brought down to every new one: the rules every chat shares. A line is
    // known by its place, as the list keys it (an answer being written grows on the same line); the one
    // on its way is yours, and sending already went down. The foot under it is followed too.
    val lineKeys = remember(lines) { lines.mapIndexedNotNull { i, l -> lineKey(i, l).takeIf { !l.pending } } }
    stick.Follow(lineKeys, content = lines to hasFoot(v, tooOld, problem, waitNote))
    // A new answer, or the Coordinator done with one, may have added or started tasks.
    LaunchedEffect(lines.count { !it.you }, v?.busy) { if (v != null && !v.busy) readTasksNow.trySend(Unit) }
    val asks = remember(tasks, lines) { tasks?.let { CoordinatorAsk.of(it, lines.map { l -> l.text }) }.orEmpty() }
    val openTask: (BoardTask) -> Unit = { t -> panes.openBoard(nav, machineId, t.id, alone = true) }
    val coordinatorPane = state.snapshot?.let { s ->
        s.agents.firstOrNull { a -> s.tab(a.tabId)?.label == "Coordinator" }?.paneId
    }

    fun send(delegate: Boolean) {
        val typed = draft
        val pictures = tray.images
        // The pictures are named in front of what you typed.
        val text = tray.message(typed)
        if (text.isEmpty() || sending || tray.waiting) return
        sending = true
        // Counted before the send, so a read that comes in while it runs can't be missed.
        val before = SentMatch.count(text, talk.filter { it.you }.map { it.text })
        val tracked = view != null
        if (tracked) { outbox = SentMatch.Pending(text, before); outboxSeen = false }
        stick.sent()
        scope.launch {
            runCatching { session.awaitBoard().send(text, delegate) }
                .onSuccess { msg ->
                    draft = DraftStore.afterSend(draft, typed)
                    tray.sent(pictures)
                    startAgents = false
                    // With no Coordinator set up there, the board files the words as a task instead.
                    if (!tracked) context.toast(msg.ifEmpty { "Sent" })
                }
                .onFailure { if (tracked) outbox = null; context.toast(it.message ?: "Couldn't send") }
            sending = false
            readNow.trySend(Unit)
        }
    }

    // Floating over other apps as a bubble, from here; in that bubble, back to the app.
    val bubbleChat = remember(machineId) { BubbleChat.Coordinator(machineId) }
    val float = rememberFloat(bubbleChat, "Coordinator", state.machine.label)
    val inBubble = LocalBubble.current != null

    val typing = typingInShortWindow()
    Scaffold(
        containerColor = Herdr.colors.ground,
        topBar = {
            if (!typing) HerdrTopBar(
                "Coordinator",
                subtitle = "${state.machine.label} · " + (v?.let { stateWords(it) } ?: if (state.online) "work board" else state.status.label()),
                onBack = if (inBubble) null else ({ nav.popBackStack() }),
            ) {
                if (coordinatorPane != null) QuietButton(onClick = { nav.navigate(Routes.terminal(machineId, coordinatorPane)) }) { Text("Terminal") }
                if (float != null) BarIcon(HerdrIcons.PictureInPicture2, "Float over other apps", float)
                if (inBubble) BarIcon(HerdrIcons.Maximize2, "Open in the app", { container.bubbles.openApp(bubbleChat) })
            }
        },
    ) { padding ->
        // The top bar's inset comes from the scaffold; the bottom follows the keyboard or the nav bar.
        Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize().imePadding().navigationBarsPadding()) {
            if (!typing && tasks != null && state.online && state.boardPath != null) {
                BoardStrip(remember(tasks) { BoardGlance.of(tasks) }) { panes.openBoard(nav, machineId) }
            }
            val sizes by container.textSizes.state.collectAsStateWithLifecycle()
            ChatSurface(
                Modifier.weight(1f).fillMaxWidth(),
                textScale = sizes.chat,
                composer = {
                    Column {
                    // The asks are for a Coordinator that is there and an empty message box.
                    if (v != null && draft.isEmpty() && tray.isEmpty && !sending && asks.isNotEmpty() && state.online) {
                        AskRow(asks, onAsk = { draft = it.text }, Modifier.padding(bottom = Space.xs))
                    }
                    CoordinatorComposer(
                        draft = draft,
                        onDraft = { draft = it },
                        enabled = state.online && state.boardPath != null,
                        sending = sending,
                        hint = if (v == null && loaded) "Add a task on ${state.machine.label}…" else "Tell the Coordinator…",
                        startAgents = startAgents,
                        onStartAgents = { startAgents = it },
                        status = when {
                            sending -> "Sending…"
                            !state.online -> "Not connected"
                            uploading -> "Uploading file…"
                            tray.waiting -> "Copying pictures…"
                            else -> null
                        },
                        onSend = ::send,
                        onAttach = { attachMenu = true },
                        uploading = uploading,
                        tray = tray,
                    )
                    }
                },
            ) { bottom ->
                when {
                    !state.online && v == null -> Box(Modifier.padding(bottom = bottom)) {
                        ChatEmpty(
                            HerdrIcons.Monitor,
                            state.status.label(),
                            listOfNotNull(state.status.detail() ?: "Waiting for ${state.machine.label}…", waitNote).joinToString("\n\n"),
                        )
                    }
                    state.online && state.boardPath == null -> Box(Modifier.padding(bottom = bottom)) {
                        ChatEmpty(HerdrIcons.SquareKanban, "No work board here", "herdr-projects isn't installed on ${state.machine.label}.")
                    }
                    !loaded -> Box(Modifier.fillMaxSize().padding(bottom = bottom), contentAlignment = Alignment.Center) {
                        LoadingState("Reading the board")
                    }
                    else -> CompositionLocalProvider(LocalChatImages provides ChatImages(machineId, session, container.images, state.online)) { LazyColumn(
                        state = list,
                        // Newest at the bottom, as the agent's chat: it stays at its end when the keyboard opens
                        // or the message box grows.
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        // The composer floats over the list's end: its height stays free under the newest line.
                        contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.m, bottom = bottom + Space.xs),
                        verticalArrangement = Arrangement.spacedBy(Space.m, Alignment.Bottom),
                    ) {
                        val foot: @Composable () -> Unit = {
                            Foot(v, tooOld, problem, waitNote, onTerminal = coordinatorPane?.let { p -> { nav.navigate(Routes.terminal(machineId, p)) } })
                        }
                        if (lines.isEmpty()) {
                            item(key = "empty") {
                                Column(Modifier.fillParentMaxSize()) {
                                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        if (v == null) {
                                            ChatEmpty(
                                                HerdrIcons.MessagesSquare,
                                                "No Coordinator here",
                                                "Nothing on ${host.ifEmpty { state.machine.label }} answers as the Coordinator, so what you send is added to the board as a task.",
                                            )
                                        } else {
                                            ChatEmpty(
                                                HerdrIcons.MessagesSquare,
                                                "Nothing said yet",
                                                "Tell the Coordinator what needs doing and it turns it into tasks.",
                                            )
                                        }
                                    }
                                    foot()
                                }
                            }
                        } else {
                            // Only when it says something: an empty one would be the newest item, and a reader just over it "up the chat".
                            if (hasFoot(v, tooOld, problem, waitNote)) item(key = "foot") { foot() }
                            val last = lines.lastIndex
                            itemsIndexed(lines.asReversed(), key = { i, l -> lineKey(last - i, l) }) { i, line ->
                                Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                                    if (lineKey(last - i, line) == stick.unread) NewLine()
                                    Bubble(line, byKey, openTask)
                                }
                            }
                        }
                    } }
                }
                if (lines.isNotEmpty()) NewestButton(stick, bottom)
            }
        }
    }
    if (attachMenu) AttachMenu(attach, tray, online = state.online, onDismiss = { attachMenu = false })
}

/** Files shared to the app for a machine's Coordinator: one is being copied ([copying]); [waitNote] says what waits for it to connect. */
internal class SharedFiles(val copying: Boolean, val waitNote: String?)

/**
 * Files shared to the app for this Coordinator (a screenshot, a log): a picture goes to the composer's
 * pictures ([tray]) at once, any other file is copied over as an attached one once connected ([onFile] its path there).
 */
@Composable
internal fun rememberSharedFiles(machineId: String, session: HostSession, state: HostState, tray: ImageTray, onFile: (String) -> Unit): SharedFiles {
    val container = LocalContainer.current
    val context = LocalContext.current
    val file by androidx.compose.runtime.rememberUpdatedState(onFile)
    var copyingShared by remember { mutableStateOf(false) }
    val sharedWaiting by remember(machineId) { container.shares.files(machineId) }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(machineId) { container.shares.used(machineId) }
    LaunchedEffect(machineId, state.online) {
        val online = state.online
        container.shares.files(machineId).collect { files ->
            for (f in files) {
                if (!online && !Images.isImage(f.name)) continue
                copyingShared = true
                try {
                    // Android may have cleared the app's cache while the share waited.
                    if (!java.io.File(f.path).exists()) throw java.io.IOException("${f.name} was no longer on the phone, so it wasn't attached. Share it again.")
                    if (!sharePicture(f, tray, container.images)) {
                        // Not a picture the phone can read after all: it goes as a file.
                        if (!online) continue
                        file(withContext(Dispatchers.IO) { java.io.File(f.path).inputStream().use { session.upload(f.name, it) } })
                    }
                } catch (e: CancellationException) {
                    copyingShared = false
                    throw e
                } catch (t: Throwable) {
                    context.toast(t.message ?: "Couldn't attach ${f.name}")
                }
                container.shares.done(machineId, f)
            }
            copyingShared = false
        }
    }
    // Shared files (not pictures, which wait in the composer) that wait for this machine to connect, said where the chat shows what is going on.
    val filesWaiting = sharedWaiting.filterNot { Images.isImage(it.name) }
    val waitNote = if (state.online || filesWaiting.isEmpty()) null else {
        val one = filesWaiting.singleOrNull()
        if (one != null) "${one.name} waits to be copied to ${state.machine.label}, and is attached to your message once it connects."
        else "${filesWaiting.size} shared files wait to be copied to ${state.machine.label}, and are attached to your message once it connects."
    }
    return SharedFiles(copyingShared, waitNote)
}

/**
 * A shared file that is a picture, made smaller into [tray]: true when it is there now (or the
 * message was full, which says so), false when the phone can't read it as a picture.
 */
private suspend fun sharePicture(f: com.makefunstuff.herdr.core.share.SharedFile, tray: ImageTray, store: com.makefunstuff.herdr.data.ImageStore): Boolean {
    if (!Images.isImage(f.name)) return false
    val ready = withContext(Dispatchers.IO) { ImageCodec.prepare(java.io.File(f.path), null, f.name, store) } ?: return false
    if (!tray.add(ready)) Toasts.show("A message takes at most ${com.makefunstuff.herdr.data.ImageStore.MAX_IMAGES} pictures, so ${f.name} was left out")
    return true
}

private fun stateWords(v: CoordinatorView): String = when {
    v.error.isNotEmpty() -> "could not start"
    v.queue > 0 && v.state != "working" -> "starting"
    else -> when (v.state) {
        "working" -> "working"
        "starting" -> "starting"
        "blocked" -> "waits for you"
        "idle" -> "idle"
        "exited" -> "stopped · your next message starts it"
        else -> "not running · your next message starts it"
    }
}

/** The screen's own empty state: an icon, a heading and a sentence, centred in the space. */
@Composable
internal fun ChatEmpty(icon: ImageVector, title: String, body: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { StateBlock(icon, title, body) }
}

/**
 * Yours on the right on a neutral raised card; the Coordinator's answer as plain prose on the left, as
 * the web UI's chat, with each task it names on the board ([tasks], by key) as a row with its live state.
 */
@Composable
private fun Bubble(line: ChatLine, tasks: Map<String, BoardTask>, onTask: (BoardTask) -> Unit) {
    val mine = line.you
    // The pictures your message names show as pictures, over its words.
    val split = remember(line.text, mine) { if (mine) Images.split(line.text) else Images.Split(emptyList(), line.text) }
    val parts = remember(line.text, mine, tasks.keys) { if (mine) null else TaskMentions.split(line.text, tasks.keys) }
    val c = Herdr.colors
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = if (mine) 48.dp else 0.dp, end = if (mine) 0.dp else 32.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            Surface(
                color = if (mine) c.card else Color.Transparent,
                contentColor = c.text,
                shape = if (mine) BubbleShape else ReplyBubbleShape,
                border = if (mine && c.bubbleBorder.alpha > 0f) androidx.compose.foundation.BorderStroke(1.dp, c.bubbleBorder) else null,
                modifier = Modifier.alpha(if (line.pending) 0.72f else 1f),
            ) {
                Column(Modifier.padding(horizontal = if (mine) Space.l else Space.xs, vertical = if (mine) Space.m else Space.xs)) {
                    if (split.paths.isNotEmpty()) MessageImages(split.paths, Modifier.padding(bottom = if (split.text.isEmpty()) 0.dp else Space.s))
                    if (parts != null) {
                        parts.forEach { p ->
                            when (p) {
                                is TaskMentions.Part.Words -> MarkdownText(p.text, style = MaterialTheme.typography.bodyLarge)
                                is TaskMentions.Part.Task -> tasks[p.key]?.let { t -> TaskMention(t, p.note) { onTask(t) } }
                            }
                        }
                    } else if (split.text.isNotEmpty()) MarkdownText(split.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (line.pending) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = Space.xs, end = Space.xs),
                ) {
                    StatusDot(c.faint, size = 6)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Sending · waiting for the Coordinator",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.faint,
                    )
                }
            }
        }
    }
}

/** A line of the conversation in the list, by its place and side. */
private fun lineKey(index: Int, line: ChatLine) = "$index/${line.you}"

/** Whether [Foot] says anything. */
private fun hasFoot(v: CoordinatorView?, tooOld: Boolean, problem: String?, waitNote: String?) =
    tooOld || problem != null || waitNote != null ||
        (v != null && (v.state == "working" || v.state == "starting" || v.queue > 0 || v.state == "blocked" || !v.readable || v.error.isNotEmpty()))

/** Under the conversation: that it's working, that it waits for you on its screen, or what went wrong. */
@Composable
private fun Foot(v: CoordinatorView?, tooOld: Boolean, problem: String?, waitNote: String?, onTerminal: (() -> Unit)?) {
    val c = Herdr.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (v != null && (v.state == "working" || v.state == "starting" || v.queue > 0) && v.error.isEmpty()) Working()
        if (v?.state == "blocked") {
            Notice(
                "The Coordinator shows a dialog on its screen that waits for your answer. It is in herdr's \"Coordinator\" tab.",
                c.notice,
                action = onTerminal?.let { "Open its terminal" to it },
            )
        }
        if (v != null && !v.readable) {
            Notice("Its program keeps no conversation the board can read, so only its latest message shows here.", null)
        }
        if (!v?.error.isNullOrEmpty()) Notice("The Coordinator could not be started: ${v?.error}", c.red)
        if (tooOld) Notice("This machine's herdr-projects is too old to send its Coordinator. Update it there (herdr-projects update).", c.notice)
        if (problem != null) Notice(problem, c.red)
        if (waitNote != null) Notice(waitNote, null)
    }
}

@Composable
internal fun Working(who: String = "Coordinator", doing: String? = null) {
    val t = rememberInfiniteTransition(label = "working")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dots")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Space.xs)) {
        StatusDot(Herdr.colors.working, Modifier.alpha(a), size = 8)
        Spacer(Modifier.width(Space.s))
        // Shepherdr's working line: what it does now, in its CLI's words, where it says.
        Text(doing?.takeIf { it.isNotBlank() } ?: "$who is working…", style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** A plain sentence on a tinted tile, in place of a raw error dump; [tint] null is neutral. */
@Composable
internal fun Notice(text: String, tint: Color?, action: Pair<String, () -> Unit>? = null) {
    Column {
        com.makefunstuff.herdr.ui.design.Notice(text, color = tint, icon = if (tint == Herdr.colors.red) HerdrIcons.CircleAlert else HerdrIcons.Info)
        if (action != null) QuietButton(onClick = action.second, color = Herdr.colors.accent) { Text(action.first) }
    }
}

/**
 * The Coordinator's composer: the shared [ChatComposer], with the attach button ([onAttach]) on
 * the left and the board's Tab as a "Start agents" switch beside Send. Off, Send hands the message to the Coordinator; on (amber), Send also gives
 * an agent to every task the Coordinator adds from it, and Send says so to a screen reader. It
 * turns itself off again once that message is sent.
 */
@Composable
internal fun CoordinatorComposer(
    draft: String,
    onDraft: (String) -> Unit,
    enabled: Boolean,
    hint: String,
    startAgents: Boolean,
    onStartAgents: (Boolean) -> Unit,
    onSend: (startAgents: Boolean) -> Unit,
    sending: Boolean = false,
    status: String? = null,
    onAttach: (() -> Unit)? = null,
    uploading: Boolean = false,
    tray: ImageTray? = null,
) {
    ChatComposer(
        draft = draft,
        onDraft = onDraft,
        placeholder = hint,
        onSend = { onSend(startAgents) },
        enabled = enabled,
        sending = sending,
        status = status,
        sendLabel = if (startAgents) "Send and start agents" else "Send",
        holdSend = uploading || tray?.waiting == true,
        attachments = tray?.let { t -> { ComposerTray(t) } },
        attached = tray?.isEmpty == false,
        floating = true,
        leading = onAttach?.let { pick ->
            { ComposerIconButton(HerdrIcons.Plus, "Attach files", enabled = enabled && !uploading, onClick = pick) }
        },
        trailing = {
            ComposerToggle("Start agents", HerdrIcons.Zap, startAgents, onStartAgents, enabled = enabled && !sending)
        },
    )
}
