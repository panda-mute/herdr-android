package com.makefunstuff.herdr.ui.agents

import com.makefunstuff.herdr.core.hc.HcMapping
import com.makefunstuff.herdr.core.home.Home
import com.makefunstuff.herdr.core.home.HomeAgent
import com.makefunstuff.herdr.ui.design.confirmTap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import com.makefunstuff.herdr.core.chat.BlockedPrompt
import com.makefunstuff.herdr.core.chat.OptionRole
import com.makefunstuff.herdr.core.guard.Guarded
import com.makefunstuff.herdr.ui.common.LocalGuard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import com.makefunstuff.herdr.ui.design.typingInShortWindow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.ui.code.CodeSpot
import com.makefunstuff.herdr.ui.quick.QuickPlace
import com.makefunstuff.herdr.ui.quick.QuickSheets
import com.makefunstuff.herdr.bubble.BubbleChat
import com.makefunstuff.herdr.bubble.LocalBubble
import com.makefunstuff.herdr.ui.common.rememberFloat
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.chat.AgentChat
import com.makefunstuff.herdr.core.chat.AgentInput
import com.makefunstuff.herdr.core.push.Alerts
import com.makefunstuff.herdr.core.chat.ChatItem
import com.makefunstuff.herdr.core.chat.AwaySummary
import com.makefunstuff.herdr.core.chat.ChatTurns
import com.makefunstuff.herdr.core.chat.AgentCommand
import com.makefunstuff.herdr.core.chat.AgentCommands
import com.makefunstuff.herdr.core.chat.Completions
import com.makefunstuff.herdr.core.chat.Held
import com.makefunstuff.herdr.core.chat.VoiceNotes
import com.makefunstuff.herdr.ui.common.LocalVoiceMessages
import com.makefunstuff.herdr.ui.common.rememberVoiceMessages
import com.makefunstuff.herdr.core.chat.HeldMessage
import com.makefunstuff.herdr.core.chat.ModelPill
import com.makefunstuff.herdr.core.usage.UsageRules
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ChatComposer
import com.makefunstuff.herdr.ui.design.ChatSurface
import com.makefunstuff.herdr.ui.design.ComposerIconButton
import com.makefunstuff.herdr.ui.common.rememberAttacher
import com.makefunstuff.herdr.ui.images.AttachMenu
import com.makefunstuff.herdr.ui.images.ChatImages
import com.makefunstuff.herdr.ui.images.ComposerTray
import com.makefunstuff.herdr.ui.images.LocalChatImages
import com.makefunstuff.herdr.ui.images.rememberImageTray
import androidx.compose.runtime.CompositionLocalProvider
import com.makefunstuff.herdr.core.host.Attachments
import com.makefunstuff.herdr.ui.common.rememberDraft
import com.makefunstuff.herdr.data.DraftStore
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.NewLine
import com.makefunstuff.herdr.ui.design.NewestButton
import com.makefunstuff.herdr.ui.design.rememberStickToBottom
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.common.StatusDot
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import com.makefunstuff.herdr.ui.manage.AgentActionsSheet
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.theme.color
import com.makefunstuff.herdr.ui.theme.title
import kotlinx.coroutines.launch

/**
 * An agent as a chat: its conversation, its state, its questions with their choices, and a box to
 * send the next prompt. The terminal is one tap away for anything the chat can't do.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentScreen(nav: NavController, machineId: String, paneId: String) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = container.hosts.session(machineId)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val host by session.state.collectAsStateWithLifecycle()
    val snapshot = host.view
    // One of herdr-core's terminals (the desktop app's), not a classic herdr pane.
    val onCore = HcMapping.address(paneId) != null
    val agent = snapshot?.agents?.firstOrNull { it.paneId == paneId }
    val pane = snapshot?.pane(paneId)
    val status = agent?.agentStatus ?: pane?.agentStatus ?: AgentStatus.Unknown

    // What the agent is working on: herdr's title for it, or the title the agent gives its terminal.
    val activity = agent?.let { it.title?.takeIf { t -> t.isNotBlank() } ?: it.terminalTitle?.takeIf { t -> t.isNotBlank() } }

    val name = agent?.let { it.name?.takeIf { n -> n.isNotBlank() } ?: it.displayName } ?: pane?.displayName ?: paneId
    // The task it works on, from the boards as last read (Shepherdr 2's too): it names the chat and its sheet.
    val boards = com.makefunstuff.herdr.ui.board.rememberBoards()
    val task = remember(boards, agent, snapshot) {
        val a = agent ?: return@remember null
        val home = HomeAgent(machineId, host.machine.label, a, snapshot?.location(a.workspaceId, a.tabId).orEmpty(), snapshot?.workspace(a.workspaceId)?.label.orEmpty())
        Home.taskOf(home, Home.own(boards)[machineId].orEmpty())
    }
    // What the title bar says it works on: its task, else its own name (unless that is only its CLI's or a
    // launcher's hash, `shep-1a2b…`), else its workspace, else (herdr-core's catch-all "Other terminals") its
    // terminal's title. Not its step message, which changes with every step.
    val workspaceLabel = agent?.let { snapshot?.workspace(it.workspaceId)?.label?.ifBlank { null } }
    val heading = task?.let { "${it.name} · ${it.title}" }
        ?: name.takeIf { agent == null || !Home.isBareName(agent, it) }
        ?: workspaceLabel?.takeIf { !it.startsWith("Other terminals") }
        ?: agent?.terminalTitle?.ifBlank { null } ?: workspaceLabel ?: name
    // Floating over other apps as a bubble, from here; in that bubble, back to the app.
    val bubbleChat = remember(machineId, paneId) { BubbleChat.Agent(machineId, paneId) }
    val float = rememberFloat(bubbleChat, heading, host.machine.label)
    val inBubble = LocalBubble.current != null

    // The chat is open: its notification has done its job.
    LaunchedEffect(machineId, paneId) { container.inbox.dismiss(Alerts.agentTag(machineId, paneId)) }
    // The launcher's recent agents: this one first, as "claude · api-server".
    val shortcutTitle = agent?.let { a -> listOfNotNull(a.agent ?: name, snapshot?.workspace(a.workspaceId)?.label?.ifBlank { null }).joinToString(" · ") }
    LaunchedEffect(machineId, paneId, shortcutTitle) { shortcutTitle?.let { container.shortcuts.opened(machineId, paneId, it, host.machine.label) } }
    // A finish shown here is seen: the Agents screen no longer lists it as new.
    val finished = agent?.takeIf { it.agentStatus == AgentStatus.Done }?.let { it.completionSeq ?: it.stateChangeSeq }
    LaunchedEffect(machineId, paneId, finished) { finished?.let { container.seen.mark(machineId, paneId, it) } }

    val chat = remember(session, paneId) { AgentChat(session, paneId, scope) }
    DisposableEffect(chat) {
        chat.start()
        onDispose { chat.close() }
    }
    val state by chat.state.collectAsStateWithLifecycle()

    // Kept per chat across navigation, rotation and restarts; cleared only once the message is sent.
    var draft by rememberDraft(DraftStore.agent(machineId, paneId))
    var sending by remember { mutableStateOf(false) }
    var showKeys by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf<QuickPlace?>(null) }
    val listState = rememberLazyListState()
    val stick = rememberStickToBottom(listState)
    // Pictures for the agent wait in the composer (and are copied to its machine) until the message goes.
    val tray = rememberImageTray(DraftStore.agent(machineId, paneId), machineId, session, host.online)
    // A file for the agent: copied to its machine, then named at the start of the draft.
    val attach = rememberAttacher(session, onAttached = { path -> draft = Attachments.withMention(draft, path) }, onImage = tray::add)
    var attachMenu by remember { mutableStateOf(false) }

    // What was sent while it worked waits above the composer; the quick replies; the model's details.
    val chatKey = DraftStore.agent(machineId, paneId)
    val composerState by container.composer.state.collectAsStateWithLifecycle()
    val held = composerState.held[chatKey].orEmpty()
    val replies = composerState.replies
    var heldSending by remember { mutableStateOf(false) }
    var heldOpen by remember { mutableStateOf(false) }
    var modelSheet by remember { mutableStateOf(false) }
    val kind = agent?.agent?.takeIf { it.isNotBlank() }
    val pill = remember(agent?.tokens, state.use) { ModelPill.of(agent?.tokens.orEmpty(), state.use) }
    // Its folder, for `@` files; its own `/` commands. Read over SSH the first time each is asked for.
    val cwd = (agent?.cwd ?: pane?.foregroundCwd ?: pane?.cwd)?.ifBlank { null }
    var commands by remember(machineId, kind, cwd) { mutableStateOf<List<AgentCommand>?>(null) }
    var files by remember(machineId, cwd) { mutableStateOf<List<String>?>(null) }
    // Why a list couldn't be read: it isn't asked for again while the chat is open.
    var commandsFailed by remember(machineId, kind, cwd) { mutableStateOf<String?>(null) }
    var filesFailed by remember(machineId, cwd) { mutableStateOf<String?>(null) }
    val lister = remember(session) { AgentCommands.Client { command, timeout -> session.awaitConnection().run(command, timeout) } }

    // The clock the turns are timed by: ticking each second while the agent works.
    var now by remember { mutableLongStateOf(container.clock()) }
    LaunchedEffect(status) {
        now = container.clock()
        while (status == AgentStatus.Working) {
            delay(1_000)
            now = container.clock()
        }
    }
    // When the chat was last on screen, read each time it comes back; written when it goes.
    var since by remember { mutableLongStateOf(container.looked.at(machineId, paneId)) }
    var back by remember { mutableLongStateOf(container.clock()) }
    var awayHidden by remember { mutableStateOf(false) }
    LifecycleStartEffect(machineId, paneId) {
        since = container.looked.at(machineId, paneId)
        back = container.clock()
        awayHidden = false
        onStopOrDispose { container.looked.mark(machineId, paneId, container.clock()) }
    }

    fun keys(k: List<String>) {
        scope.launch {
            runCatching { AgentInput.keys(session.awaitAgent(paneId), paneId, k, named = agent != null) }.onFailure { context.toast(it.message ?: "Couldn't send keys") }
            session.refresh()
        }
    }

    // The question docked over the composer: an answer on its way, a "No, tell it…" being written,
    // the reply after Amend, and whether it stays open while the keyboard is up.
    val guard = LocalGuard.current
    val view = LocalView.current
    val composerFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var answering by remember { mutableStateOf(false) }
    var telling by remember { mutableStateOf<Telling?>(null) }
    var amending by remember { mutableStateOf(false) }
    var cardOpen by remember { mutableStateOf(false) }
    val prompt = state.prompt
    // A new question (or none): what was begun for the last one is over.
    LaunchedEffect(prompt?.question, status) {
        if (status != AgentStatus.Blocked || telling?.prompt?.same(prompt) == false) telling = null
        if (status != AgentStatus.Blocked) amending = false
        cardOpen = false
    }

    fun reply() {
        composerFocus.requestFocus()
        keyboard?.show()
    }

    /** Carries out [block] on the question shown, which it checks is still on the agent's screen. */
    fun answer(block: suspend (com.makefunstuff.herdr.core.chat.AgentPort) -> Unit) {
        if (answering) return
        answering = true
        // A light confirmation under the thumb.
        view.confirmTap()
        scope.launch {
            runCatching { block(session.awaitAgent(paneId)) }.onFailure { context.toast(it.message ?: "Couldn't answer") }
            answering = false
            session.refresh()
        }
    }

    fun press(p: PromptPress) {
        val asked = prompt
        val named = agent != null
        when (p) {
            is PromptPress.Option -> {
                if (asked == null) return
                val choose = { answer { api -> AgentInput.choose(api, paneId, asked, p.index, named) } }
                // "Yes, and don't ask again" changes what the agent may do for good: the fingerprint first, when that is on.
                if (asked.role(p.index) == OptionRole.Always) guard.run(Guarded.AlwaysAllow, "${asked.shortLabel(p.index)}?", command(asked), choose)
                else choose()
            }
            is PromptPress.Tell -> if (asked != null) { telling = Telling(asked, p.index); reply() }
            is PromptPress.Ticks -> if (asked != null) answer { api -> AgentInput.tick(api, paneId, asked, p.indices, named) }
            is PromptPress.Key -> {
                keys(listOf(p.key))
                if (p.reply) { amending = true; reply() }
            }
            is PromptPress.Keys -> keys(p.keys)
        }
    }

    /** Sends what is typed, or a quick [reply] (the draft and its pictures stay as they are). */
    fun send(reply: String? = null) {
        val typed = reply ?: draft
        val pictures = if (reply == null) tray.images else emptyList()
        // The pictures are named in front of what you typed.
        val text = if (reply == null) tray.message(typed) else reply
        if (text.isEmpty() || sending || (reply == null && tray.waiting)) return
        // You wrote: what happened while you were away is behind you.
        awayHidden = true
        val at = container.clock()
        // While it works, the words wait above the composer rather than land in the middle of its turn.
        if (Held.holds(status, named = agent != null)) {
            container.composer.hold(chatKey, text, at)
            if (reply == null) {
                draft = DraftStore.afterSend(draft, typed)
                tray.sent(pictures)
            }
            return
        }
        sending = true
        val blocked = status == AgentStatus.Blocked
        // What the words answer: "No, tell it…" (the no, then the words as the next prompt), the question's own
        // "Type something." row, or (after Amend, or for a question with neither) typed into its screen.
        val tell = telling?.takeIf { blocked }
        val own = prompt?.takeIf { blocked && tell == null && !amending && it.customIndex != null && !it.multiSelect }
        // Counted before the send, so a transcript that is quicker than this call still confirms it.
        if (!blocked || tell != null) chat.addPending(text)
        // What you just said is the newest line: bring the chat to its end, also if you had scrolled up.
        stick.sent()
        scope.launch {
            runCatching {
                val api = session.awaitAgent(paneId)
                when {
                    tell != null -> AgentInput.refuseAndTell(api, paneId, tell.prompt, tell.index, text, named = agent != null)
                    own != null -> AgentInput.typeAnswer(api, paneId, own, text, named = agent != null)
                    else -> AgentInput.send(api, paneId, text, named = agent != null, blocked = blocked)
                }
            }.onSuccess {
                if (reply == null) {
                    draft = DraftStore.afterSend(draft, typed)
                    tray.sent(pictures)
                }
                telling = null
                amending = false
            }.onFailure {
                if (!blocked || tell != null) chat.dropPending(text)
                context.toast(it.message ?: "Couldn't send")
            }
            sending = false
            session.refresh()
        }
    }

    /**
     * A voice message copied to the machine ([remote]), to the agent: its path as an `@` mention, the
     * way a file goes to a herdr agent, which its program (Claude Code, Codex, pi) reads itself. While
     * the agent works it is held like typed words, and also while it asks something: typed into its
     * question, the path would answer it. Refused, it throws, and the message waits to retry.
     */
    suspend fun deliverVoice(remote: String) {
        val text = Attachments.mention(remote)
        if (Held.holds(status, named = agent != null) || status == AgentStatus.Blocked) {
            container.composer.hold(chatKey, text, container.clock())
            return
        }
        chat.addPending(text)
        stick.sent()
        try {
            AgentInput.send(session.awaitAgent(paneId), paneId, text, named = agent != null, blocked = false)
        } catch (t: Throwable) {
            chat.dropPending(text)
            throw t
        }
        session.refresh()
    }
    val voice = rememberVoiceMessages(
        chatKey,
        online = host.online,
        upload = { file, name -> file.inputStream().use { session.upload(name, it) } },
        deliver = { remote, _ -> deliverVoice(remote) },
        fetch = { remote -> session.fetchImage(remote) },
    )

    /** A held message, now: sent as typed then (Claude Code and Codex take words mid-turn as their next). */
    fun sendHeld(m: HeldMessage) {
        if (heldSending || sending || !Held.canSend(status, false)) return
        heldSending = true
        awayHidden = true
        chat.addPending(m.text)
        stick.sent()
        scope.launch {
            runCatching { AgentInput.send(session.awaitAgent(paneId), paneId, m.text, named = agent != null, blocked = false) }
                .onSuccess { container.composer.drop(chatKey, m.id) }
                .onFailure {
                    chat.dropPending(m.text)
                    context.toast(it.message ?: "Couldn't send")
                }
            heldSending = false
            session.refresh()
        }
    }

    // Turns folded or opened by hand stay as they were left, also once the turn settles.
    val opened = remember(machineId, paneId) { mutableStateMapOf<String, Boolean>() }
    val zone = remember { container.briefWork.zone() }
    val turns = remember(state.items, state.turns) { ChatTurns.of(state.items, state.turns) }
    val steps = remember(state.turns) { ChatTurns.Steps(state.turns) }
    // The turns are timed on the machine's clock: one running ahead of the phone's shows as steps from the future.
    var ahead by remember(machineId) { mutableLongStateOf(0L) }
    LaunchedEffect(state.turns) { ahead = maxOf(ahead, ChatTurns.ahead(state.turns, container.clock())) }
    val machineNow = now + ahead
    // The newest turn runs while the agent works or asks; not once a message was sent over it and the transcript hasn't shown it yet.
    val live = when {
        state.pending.isNotEmpty() -> null
        status == AgentStatus.Working -> ChatRow.Live.Working
        status == AgentStatus.Blocked -> ChatRow.Live.Blocked
        else -> null
    }
    // Voice messages still on the phone (on their way, or not sent) come after what the agent has.
    // One whose `@path` is on its way already shows as that.
    val waitingVoice = voice.notes.filter { n -> !n.sent && state.pending.none { p -> n.remote != null && VoiceNotes.mentioned(p.text) == n.remote } }
    val rows = remember(turns, steps, live, opened.toMap(), machineNow, state.pending, state.source, state.screen, state.truncated, state.thinking, waitingVoice) {
        buildList {
            if (state.truncated) add(ChatRow.Info("truncated", "Earlier messages aren't loaded"))
            when (state.source) {
                AgentChat.Source.Transcript -> addAll(
                    chatRows(turns, steps, live, opened, machineNow, state.thinking) { at -> clock(at - ahead, now, zone) },
                )
                AgentChat.Source.Screen -> {
                    add(ChatRow.Info("screen-note", "No transcript for this agent, so this is its screen"))
                    // TUIs pad with blank rows; one blank line is enough on a phone.
                    val screen = state.screen.lines().takeLast(200).joinToString("\n").replace(Regex("\n\\s*\n(\\s*\n)+"), "\n\n")
                    if (screen.isNotBlank()) add(ChatRow.Screen(screen))
                }
                AgentChat.Source.Loading -> {}
            }
            state.pending.forEachIndexed { i, p ->
                add(ChatRow.User(ChatItem.User("$i", p.text), pending = true))
            }
            waitingVoice.forEach { add(ChatRow.Voice(it)) }
        }
    }
    val liveHead = rows.any { it is ChatRow.Head && it.live != null }
    val typing = typingInShortWindow()
    // The newest turn's header says it works; with the strip hidden for the keyboard and no such header, a line under the chat does.
    val thinking = rows.any { it is ChatRow.Thinking || (it is ChatRow.Thought && it.live) }
    val workingLine = status == AgentStatus.Working && typing && !liveHead && !thinking
    // At the newest message, and brought down to every new one: the rules every chat shares.
    stick.Follow(remember(rows) { rows.filter { it.isMessage }.map { it.key } }, content = rows to workingLine)
    stick.SentWith(voice.notes.map { it.id })
    // What happened since the chat was last on screen, until hidden or until you write.
    val away = remember(state.turns, since, back, ahead, live) {
        AwaySummary.of(state.turns, since + ahead, back + ahead, live != null)
    }?.takeIf { !awayHidden && state.source == AgentChat.Source.Transcript }

    val imeUp = WindowInsets.isImeVisible
    // The quick replies, when on: for an empty box, while the agent isn't asking something.
    val chips = replies.on && replies.shown.isNotEmpty() && agent != null && draft.isEmpty() && tray.isEmpty && status != AgentStatus.Blocked && !typing
    val guardState by container.guard.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = Herdr.colors.ground,
        topBar = {
            if (!typing) HerdrTopBar(
                heading,
                subtitle = listOfNotNull(
                    agent?.agent?.takeIf { !it.equals(heading, ignoreCase = true) },
                    host.machine.label,
                    snapshot?.let { s -> pane?.let { s.location(it.workspaceId, it.tabId) } },
                ).joinToString(" · "),
                onBack = if (inBubble) null else ({ nav.popBackStack() }),
            ) {
                QuietButton(onClick = { nav.navigate(Routes.terminal(machineId, paneId)) }) { Text("Terminal") }
                BarIcon(HerdrIcons.Ellipsis, "More", { menu = true })
            }
        },
    ) { padding ->
        // The top bar's inset comes from the scaffold; the bottom follows the keyboard or the nav bar.
        Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize().imePadding().navigationBarsPadding()) {
            if (!typing) {
                // What the newest turn is at, and its time when no header in the chat says it: one Working line.
                val running = state.turns.lastOrNull()?.takeIf { live == ChatRow.Live.Working && turns.lastOrNull()?.let { ChatTurns.mayBeRunning(it, machineNow) } != false }
                StatusStrip(
                    status, agent?.statusLabel,
                    listOfNotNull(
                        running?.at?.takeIf { !liveHead && it in 1 until machineNow }?.let { ChatTurns.duration(machineNow - it) },
                        ChatTurns.doing(running) ?: activity,
                    ).joinToString(" · ").ifEmpty { null },
                    online = host.online,
                )
            }
            away?.let { a -> AwayCard(a, onHide = { awayHidden = true }, modifier = Modifier.padding(horizontal = Space.screen).padding(top = Space.s)) }
            val sizes by container.textSizes.state.collectAsStateWithLifecycle()
            ChatSurface(
                Modifier.weight(1f).fillMaxWidth(),
                textScale = sizes.chat,
                composer = {
                    ChatComposer(
                        draft = draft,
                        onDraft = { draft = it },
                        placeholder = when {
                            status == AgentStatus.Blocked && telling != null -> "Tell it what to do instead…"
                            status == AgentStatus.Blocked && amending -> "Add to your answer…"
                            status == AgentStatus.Blocked && prompt?.customIndex != null && !prompt.multiSelect -> "Type your own answer…"
                            status == AgentStatus.Blocked -> "Type an answer…"
                            agent != null -> "Message ${agent.name?.takeIf { it.isNotBlank() } ?: agent.displayName}…"
                            else -> "Type into the pane…"
                        },
                        onSend = ::send,
                        sending = sending,
                        holdSend = attach.uploading || tray.waiting,
                        status = when {
                            sending -> "Sending…"
                            !host.online -> "Not connected"
                            attach.uploading -> "Uploading file…"
                            tray.waiting -> "Copying pictures…"
                            else -> null
                        },
                        leading = {
                            ComposerIconButton(HerdrIcons.Plus, "Attach files", enabled = !attach.uploading, onClick = { attachMenu = true })
                            pill?.let { ModelPillButton(it) { modelSheet = true } }
                        },
                        attachments = { ComposerTray(tray) },
                        voice = voice.recorder,
                        attached = !tray.isEmpty,
                        stop = if (status == AgentStatus.Working) ({ keys(listOf("esc")) }) else null,
                        floating = true,
                        focusRequester = composerFocus,
                        completion = if (agent != null && status != AgentStatus.Blocked) ({ query, accept ->
                            Completion(
                                query, kind, cwd, commands, files, if (query.kind == Completions.Kind.Command) commandsFailed else filesFailed,
                                load = {
                                    scope.launch {
                                        if (query.kind == Completions.Kind.Command) {
                                            runCatching { commands = lister.commands(kind.orEmpty(), cwd.orEmpty()) }
                                                .onFailure { commandsFailed = it.message ?: "Couldn't read the machine" }
                                        } else if (cwd != null) {
                                            runCatching { files = lister.files(cwd) }
                                                .onFailure { filesFailed = it.message ?: "Couldn't read the machine" }
                                        }
                                    }
                                },
                                onPick = { accept(it.insert) },
                            )
                        }) else null,
                        above = if (showKeys || status == AgentStatus.Blocked || held.isNotEmpty() || chips) ({
                            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                                if (held.isNotEmpty()) {
                                    HeldList(
                                        held, status, kind ?: name,
                                        canSend = Held.canSend(status, heldSending || sending),
                                        onSendNow = ::sendHeld,
                                        onDiscard = { container.composer.drop(chatKey, it.id) },
                                        // While it asks something, the question comes first: the rows fold into their caption.
                                        folded = status == AgentStatus.Blocked && !heldOpen,
                                        onUnfold = { heldOpen = true },
                                    )
                                }
                                if (status == AgentStatus.Blocked) {
                                    // Docked over the composer, so it can't scroll away; folded to a line while you write.
                                    val fold = telling != null || (imeUp && !cardOpen)
                                    if (fold) {
                                        PromptBar(
                                            prompt?.question ?: "Waiting for your answer",
                                            telling = telling != null,
                                            onOpen = { if (telling == null) cardOpen = true },
                                            onCancelTell = { telling = null },
                                        )
                                    } else {
                                        PromptCard(
                                            prompt, state.screen, answering = answering,
                                            alwaysGuarded = guardState.asks(Guarded.AlwaysAllow) && container.guard.unlock.canAsk(),
                                            onPress = ::press,
                                        )
                                    }
                                }
                                if (showKeys) KeyRow(onKeys = ::keys)
                                if (chips) QuickReplyRow(
                                    replies.shown, onReply = { send(it) },
                                    onEdit = if (inBubble) null else ({ nav.navigate(Routes.COMPOSER) }),
                                )
                            }
                        }) else null,
                    )
                },
            ) { bottom ->
                when {
                    agent == null && pane == null && snapshot != null -> Box(Modifier.padding(bottom = bottom)) {
                        EmptyState("This agent is gone", "Its pane closed, or the agent exited.", icon = HerdrIcons.Bot)
                    }
                    rows.isEmpty() && state.source == AgentChat.Source.Loading -> Box(Modifier.fillMaxSize().padding(bottom = bottom)) {
                        if (host.online) LoadingState("Loading the conversation", Modifier.align(Alignment.Center))
                        else EmptyState("Not connected", host.machine.label, icon = HerdrIcons.Monitor)
                    }
                    rows.isEmpty() -> Box(Modifier.padding(bottom = bottom)) {
                        EmptyState("No messages yet", "Send a prompt to get started.", icon = HerdrIcons.MessagesSquare)
                    }
                    else -> CompositionLocalProvider(
                        LocalChatImages provides ChatImages(machineId, session, container.images, host.online),
                        LocalVoiceMessages provides voice,
                        LocalOpenSubagent provides { tool -> tool.subagent?.let { nav.navigate(Routes.subagent(machineId, paneId, it, tool.summary)) } },
                    ) { LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        // The composer floats over the list's end: its height stays free under the newest entry.
                        contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.m, bottom = bottom + Space.xs),
                        verticalArrangement = Arrangement.spacedBy(Space.m, Alignment.Bottom),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (workingLine) item(key = "working") { WorkingLine(activity) }
                        items(rows.asReversed(), key = { it.key }) { row ->
                            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                                if (row.key == stick.unread) NewLine()
                                ChatRowView(row, onToggle = { head -> opened[head.turn] = !head.open }, onThought = { t -> opened[t.key] = !t.open })
                            }
                        }
                    } }
                }
                NewestButton(stick, bottom)
            }
        }
    }

    if (menu) {
        AgentActionsSheet(
            task = task,
            session = session,
            agent = agent,
            paneLabel = pane?.displayName ?: paneId,
            onDismiss = { menu = false },
            onTerminal = { menu = false; nav.navigate(Routes.terminal(machineId, paneId)) },
            onToggleKeys = { menu = false; showKeys = !showKeys },
            showKeys = showKeys,
            onKey = { menu = false; keys(it) },
            onFloat = float?.let { f -> { menu = false; f() } },
            onOpenApp = if (inBubble) ({ menu = false; container.bubbles.openApp(bubbleChat) }) else null,
            onCode = (agent?.cwd ?: pane?.foregroundCwd ?: pane?.cwd)?.ifBlank { null }?.takeIf { !inBubble }?.let { dir ->
                { menu = false; nav.navigate(Routes.codeAt(CodeSpot(machineId, dir, pane = paneId))) }
            },
            // herdr-core reports no session to follow, and quick commands type into classic herdr's panes.
            onActivity = if (inBubble || onCore) null else ({ menu = false; nav.navigate(Routes.activity(machineId, pane = paneId)) }),
            onQuick = if (inBubble || onCore) null else ({
                menu = false
                val name = agent?.let { it.name?.takeIf { n -> n.isNotBlank() } ?: it.displayName }
                quick = QuickPlace(
                    machineId, (agent?.cwd ?: pane?.foregroundCwd ?: pane?.cwd).orEmpty(), label = name ?: pane?.displayName.orEmpty(),
                    workspaceId = agent?.workspaceId ?: pane?.workspaceId, pane = paneId, agentName = name,
                )
            }),
        )
    }
    quick?.let { place -> QuickSheets(nav, place, onDismiss = { quick = null }) }
    if (modelSheet) pill?.let { p ->
        val latest by container.usage.latest.collectAsStateWithLifecycle()
        val left = kind?.let { UsageRules.leftFor(it, latest[machineId]?.report, container.usage.nowSeconds(), container.usage.zone()) }
        ModelSheet(
            p, agent = listOfNotNull(kind ?: name, host.machine.label).joinToString(" on "),
            stateLabel = agent?.stateLabels?.get(agent.agentStatusRaw)?.takeIf { it != agent.agentStatusRaw },
            reported = agent?.tokens.orEmpty().filterKeys { it != "model" && it != "context" },
            limit = left?.let { "${it.provider.name}: ${it.words}" },
            onUsage = if (inBubble) null else ({ modelSheet = false; nav.navigate(Routes.usage(machineId)) }),
            onDismiss = { modelSheet = false },
        )
    }
    if (attachMenu) AttachMenu(attach, tray, online = host.online, onDismiss = { attachMenu = false })
}

/** The command a question is about, for the fingerprint's prompt: codex's `$ …` line, or the line under Claude's tool header. */
private fun command(p: BlockedPrompt): String =
    p.details.firstOrNull { it.startsWith("$ ") }
        ?: p.details.getOrNull(1)?.takeIf { p.details[0].split(' ').size <= 3 }
        ?: p.question

/** When a turn started: `10:05` today, `Oct 16, 09:12` before, on the phone's clock. */
internal fun clock(at: Long, now: Long, zone: ZoneId): String {
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate() != Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return DateTimeFormatter.ofPattern(if (day) "MMM d, HH:mm" else "HH:mm", Locale.getDefault()).withZone(zone).format(Instant.ofEpochMilli(at))
}

/** "No, tell it…" being written: the option to press once the words go, on this question. */
private class Telling(val prompt: BlockedPrompt, val index: Int)

/** Under the bar: the agent's state in its colour, and what it is doing. */
@Composable
private fun StatusStrip(status: AgentStatus, label: String?, activity: String?, online: Boolean) {
    val c = Herdr.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(c.card).padding(horizontal = Space.screen, vertical = Space.s),
    ) {
        StatusDot(status.color())
        Spacer(Modifier.width(Space.s))
        Text(
            when (status) {
                AgentStatus.Unknown -> label?.takeIf { it != "unknown" } ?: "Status unknown"
                else -> status.title()
            },
            style = MaterialTheme.typography.labelLarge,
            color = status.color(),
        )
        if (activity != null && status != AgentStatus.Blocked) {
            Text(
                " · $activity",
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.weight(1f))
        if (!online) Text("offline", style = MaterialTheme.typography.labelMedium, color = c.faint)
    }
}

@Composable
private fun KeyRow(onKeys: (List<String>) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        listOf(
            "Enter" to "enter", "Esc" to "esc", "↑" to "up", "↓" to "down", "Tab" to "tab", "⇧Tab" to "shift+tab",
            "1" to "1", "2" to "2", "3" to "3", "y" to "y", "n" to "n", "Ctrl+C" to "ctrl+c",
        ).forEach { (label, key) -> KeyButton(label) { onKeys(listOf(key)) } }
    }
}

/**
 * The menu for the word being completed: the agent's commands for `/`, its folder's files for `@`.
 * Each list is read once ([load]) and then filtered on the phone as you type.
 */
@Composable
private fun Completion(
    query: Completions.Query,
    kind: String?,
    cwd: String?,
    commands: List<AgentCommand>?,
    files: List<String>?,
    failed: String?,
    load: () -> Unit,
    onPick: (CompletionRow) -> Unit,
) {
    when (query.kind) {
        Completions.Kind.Command -> {
            val known = AgentCommands.knows(kind)
            LaunchedEffect(known, commands == null) { if (known && commands == null && failed == null) load() }
            // The built-in ones at once, the machine's own when they come.
            val all = commands ?: AgentCommands.builtins(kind)
            val rows = commandRows(Completions.commands(all, query.text))
            CompletionMenu(
                "${kind ?: "Agent"} commands", rows,
                note = when {
                    !known -> "The app doesn't know ${kind ?: "this agent"}'s commands"
                    rows.isEmpty() -> "No command matches /${query.text}"
                    else -> null
                },
                onPick = onPick,
            )
        }
        Completions.Kind.File -> {
            LaunchedEffect(cwd, files == null) { if (cwd != null && files == null && failed == null) load() }
            val rows = fileRows(Completions.files(files.orEmpty(), query.text, limit = 30))
            CompletionMenu(
                "Files in ${cwd?.substringAfterLast('/') ?: "its folder"}", rows,
                note = when {
                    cwd == null -> "Its folder isn't known"
                    failed != null && files == null -> failed
                    files == null -> "Listing the files…"
                    rows.isEmpty() -> "No file matches ${query.text}"
                    else -> null
                },
                onPick = onPick,
            )
        }
    }
}
