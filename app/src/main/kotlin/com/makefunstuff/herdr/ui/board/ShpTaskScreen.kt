package com.makefunstuff.herdr.ui.board

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.makefunstuff.herdr.core.board.CardAnswer
import com.makefunstuff.herdr.core.board.CoordinatorThread
import com.makefunstuff.herdr.core.board.WorkBoard
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.board.WorkTaskDetails
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.shepherdr.Accept
import com.makefunstuff.herdr.data.DraftStore
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.common.openUrl
import com.makefunstuff.herdr.ui.common.rememberDraft
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.coordinator.ChatEmpty
import com.makefunstuff.herdr.ui.coordinator.Working
import com.makefunstuff.herdr.ui.design.ViewTabs
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ChatComposer
import com.makefunstuff.herdr.ui.design.ChatSurface
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.InfoLine
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the simple-herdr-projects task's ⋯ shows over it. */
private enum class ShpSheet { Menu, Said, Brief, Facts }

/**
 * A simple-herdr-projects task: where it stands, what it waits on, and its worker's chat read
 * with `hc read` and answered with `shp say` (which sends through `hc send`). Accept slides up its
 * own confirmation, and then runs `shp accept --yes`. No Assistant, artifacts or voice: shp has none.
 */
@Composable
internal fun ShpTaskScreen(
    session: HostSession,
    machineId: String,
    t: WorkTask,
    space: String,
    busy: String?,
    onBack: () -> Unit,
    onMerge: () -> Unit,
    onAnswer: (CardAnswer) -> Unit,
    backEnabled: Boolean = true,
) {
    BackHandler(enabled = backEnabled, onBack = onBack)
    val container = LocalContainer.current
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state by session.state.collectAsStateWithLifecycle()
    val c = Herdr.colors
    var details by remember(t.id) { mutableStateOf<WorkTaskDetails?>(null) }
    var thread by remember(t.id) { mutableStateOf<CoordinatorThread?>(null) }
    var problem by remember(t.id) { mutableStateOf<String?>(null) }
    var sheet by remember(t.id) { mutableStateOf<ShpSheet?>(null) }
    // Its chat, or (once shp names one) its pull request to review.
    var onPr by remember(t.id) { mutableStateOf(false) }
    var sending by remember(t.id) { mutableStateOf(false) }
    var draft by rememberDraft(DraftStore.task(machineId, t.id))

    // Read while the screen is in front: the task every few seconds, and its worker's terminal with hc read.
    LaunchedEffect(t.id, t.status, state.online) {
        if (!state.online) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                while (true) {
                    try {
                        details = session.awaitWorkBoard().details(t)
                        problem = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        problem = e.message ?: "Couldn't read the task"
                    }
                    delay(8_000)
                }
            }
            launch {
                while (true) {
                    try {
                        session.awaitWorkBoard().taskThreads(t).collect { th ->
                            thread = th
                            if (th.lines.isNotEmpty()) problem = null
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        problem = e.message ?: "Couldn't read its chat"
                    }
                    delay(3_000)
                }
            }
        }
    }

    val th = thread
    val lines = th?.lines.orEmpty().map { it.text }
    val canWrite = t.status == WorkStatus.Work || t.status == WorkStatus.Need
    val d = details

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || sending || !canWrite) return
        sending = true
        // On the app's scope: leaving the screen right after Send doesn't stop it.
        container.scope.launch {
            try {
                session.awaitWorkBoard().tell(t, text)
                withContext(Dispatchers.Main) { draft = DraftStore.afterSend(draft, text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    draft = if (draft.isBlank()) text else text + "\n" + draft
                    context.toast(e.message ?: "Couldn't send")
                }
            }
            withContext(Dispatchers.Main) { sending = false }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = c.ground,
            topBar = {
                HerdrTopBar(
                    t.title,
                    subtitle = listOfNotNull(t.name, space.ifEmpty { null }, state.machine.label).joinToString(" · "),
                    onBack = onBack, backLabel = "Back to the board",
                ) {
                    BarIcon(HerdrIcons.Ellipsis, "Details and more", { sheet = ShpSheet.Menu })
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().imePadding().navigationBarsPadding()) {
                ShpStrip(t, busy, onMerge)
                if (t.pr.isNotEmpty()) ViewTabs(listOf(TaskView.Chat.label, TaskView.PullRequest.label), if (onPr) 1 else 0, onSelect = { onPr = it == 1 })
                if (onPr && t.pr.isNotEmpty()) Box(Modifier.weight(1f).fillMaxWidth()) { TaskPullRequest(session, t) }
                else ChatSurface(
                    Modifier.weight(1f).fillMaxWidth(),
                    composer = {
                        Column {
                            d?.cards?.takeIf { it.isNotEmpty() }?.let { cards ->
                                Column(
                                    Modifier.padding(horizontal = Space.screen).padding(bottom = Space.xs),
                                    verticalArrangement = Arrangement.spacedBy(Space.s),
                                ) {
                                    cards.forEach { card -> QuestionCard(card, enabled = busy == null && state.online, onSend = onAnswer) }
                                }
                            }
                            if (canWrite) ChatComposer(
                                draft = draft,
                                onDraft = { draft = it },
                                placeholder = "Message ${t.name}",
                                onSend = ::send,
                                enabled = state.online && !sending,
                                sending = sending,
                                status = when {
                                    !state.online -> "Not connected"
                                    sending -> "Sending…"
                                    else -> null
                                },
                                floating = true,
                            ) else Notice(
                                "Its worker is not running now, so there is nothing to write to. Messages go to it while it works or waits for you.",
                                Modifier.padding(horizontal = Space.screen).padding(bottom = Space.m),
                            )
                        }
                    },
                ) { bottom ->
                    if (!state.online && thread == null) {
                        Box(Modifier.padding(bottom = bottom)) {
                            ChatEmpty(HerdrIcons.Monitor, state.status.label(), "Waiting for ${state.machine.label}…")
                        }
                    } else if (th == null) {
                        Box(Modifier.fillMaxSize().padding(bottom = bottom), contentAlignment = Alignment.Center) {
                            val why = problem
                            if (why != null) ChatEmpty(HerdrIcons.CircleAlert, "Can't read its chat", why) else LoadingState("Reading its chat")
                        }
                    } else if (lines.isEmpty() && !canWrite) {
                        // A task whose worker has finished has no chat to show: its words and brief take its place.
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.m, bottom = bottom + Space.xs),
                        ) {
                            item(key = "done") {
                                Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
                                    if (d == null) LoadingState("Reading the task")
                                    if (!d?.summary.isNullOrEmpty()) Section("What it did") { MarkdownText(d.summary, style = MaterialTheme.typography.bodyMedium) }
                                    if (!d?.brief.isNullOrEmpty()) Section("Brief") { MarkdownText(d.brief, style = MaterialTheme.typography.bodyMedium) }
                                    if (d != null && d.summary.isEmpty() && d.brief.isEmpty()) ChatEmpty(
                                        HerdrIcons.SquareTerminal, "Its worker has finished",
                                        "Nothing was kept of what it did.",
                                    )
                                    problem?.let { Notice(it, color = c.red) }
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            reverseLayout = true,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.m, bottom = bottom + Space.xs),
                            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom),
                        ) {
                            item(key = "foot") {
                                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                                    if (th.working) Working(t.name)
                                    problem?.let { Notice(it, color = c.red) }
                                }
                            }
                            if (lines.isEmpty()) item(key = "empty") {
                                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                    val done = t.status == WorkStatus.Ready || t.status == WorkStatus.Done || t.status == WorkStatus.Merging
                                    ChatEmpty(
                                        HerdrIcons.SquareTerminal,
                                        if (done) "Its worker has finished" else "No live worker",
                                        if (done) "What it did stays in its task: open ⋯ for the summary and brief."
                                        else "Its worker writes in a terminal on ${state.machine.label} while it works.",
                                    )
                                }
                            }
                            items(lines.asReversed()) { line ->
                                Text(
                                    line.ifEmpty { " " },
                                    style = CodeStyle,
                                    color = if (line.startsWith("❯")) c.accent else c.text,
                                )
                            }
                        }
                    }
                }
            }
        }
        when (sheet) {
            ShpSheet.Menu -> BoardPanel("${t.name} · ${t.title}", onDismiss = { sheet = null }) {
                Column {
                    if (!d?.summary.isNullOrEmpty()) MenuRow("What it did", HerdrIcons.FileText, { sheet = ShpSheet.Said })
                    if (!d?.brief.isNullOrEmpty()) MenuRow("Brief", HerdrIcons.FileText, { sheet = ShpSheet.Brief })
                    if (!d?.facts.isNullOrEmpty() || !d?.branch.isNullOrEmpty() || t.pr.isNotEmpty()) {
                        MenuRow("Facts", HerdrIcons.Info, { sheet = ShpSheet.Facts })
                    }
                    if (d == null) LoadingState("Reading the task")
                }
            }
            ShpSheet.Said -> ShpPanel("What it did", { sheet = ShpSheet.Menu }) { MarkdownText(d?.summary.orEmpty(), style = MaterialTheme.typography.bodyMedium) }
            ShpSheet.Brief -> ShpPanel("Brief", { sheet = ShpSheet.Menu }) { MarkdownText(d?.brief.orEmpty(), style = MaterialTheme.typography.bodyMedium) }
            ShpSheet.Facts -> ShpPanel("Facts", { sheet = ShpSheet.Menu }) {
                if (!d?.branch.isNullOrEmpty()) InfoLine("Branch", d.branch)
                if (!d?.facts.isNullOrEmpty()) Text(d.facts, style = MaterialTheme.typography.bodySmall, color = c.muted)
                if (t.pr.isNotEmpty()) LinkRow("Pull request", t.pr.substringAfter("github.com/"), { context.openUrl(t.pr) })
            }
            null -> {}
        }
    }
}

/** Under the bar: where it stands, how far along, how long is left, and Accept on a ready one. */
@Composable
private fun ShpStrip(t: WorkTask, busy: String?, onMerge: () -> Unit) {
    val c = Herdr.colors
    val p = t.progress?.takeIf { t.status == WorkStatus.Work }
    Column(Modifier.fillMaxWidth().background(c.panel)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = Space.screen, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBadge(t.status.label, t.status.color())
            Column(Modifier.weight(1f).padding(start = Space.s)) {
                if (t.line.isNotEmpty()) Text(t.line, style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (p != null || t.left != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p != null) Progress(p, t.status.color(), Modifier.width(72.dp))
                    t.left?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = c.working, modifier = Modifier.padding(start = Space.s)) }
                }
            }
            if (t.status == WorkStatus.Ready) PrimaryButton(onClick = onMerge, enabled = busy == null) {
                Icon(HerdrIcons.GitMerge, null, Modifier.size(18.dp))
                Spacer(Modifier.width(Space.xs))
                Text(if (t.accepting) "Accepting…" else "Accept")
            }
        }
        if (busy != null) BusyLine(busy)
        com.makefunstuff.herdr.ui.design.RowDivider(0.dp)
    }
}

/** One of the task's details in full, over its chat; back goes to the menu. */
@Composable
private fun ShpPanel(title: String, onBack: () -> Unit, content: @Composable () -> Unit) =
    BoardPanel(title, onDismiss = onBack, buttons = { PrimaryButton("Done", onClick = onBack) }) { content() }

/**
 * simple-herdr-projects' Accept, in place of Shepherdr's card: shp has no evidence to read, so this
 * says what accepting does and what the task says it did. Accept then runs `shp accept --yes`.
 */
@Composable
fun ShpAcceptSheet(
    board: WorkBoard?,
    task: WorkTask,
    onDismiss: () -> Unit,
    onAccepted: (String) -> Unit = {},
    confirm: (run: () -> Unit) -> Unit = { it() },
) {
    val c = Herdr.colors
    val scope = rememberCoroutineScope()
    var error by remember(task.id) { mutableStateOf<String?>(null) }
    var busy by remember(task.id) { mutableStateOf(false) }
    fun accept() {
        val b = board ?: return
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                onAccepted(b.approve(task))
                onDismiss()
            } catch (x: CancellationException) {
                throw x
            } catch (x: Exception) {
                error = x.message ?: "It wasn't accepted"
                busy = false
            }
        }
    }
    com.makefunstuff.herdr.ui.design.HerdrSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Column {
                Text(Accept.title(task.name), style = MaterialTheme.typography.titleLarge, color = c.strong)
                Text(task.title, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (task.line.isNotEmpty()) MarkdownText(task.line, style = MaterialTheme.typography.bodyLarge)
            Notice("Accepting runs the space's checks, then merges the task or opens and merges its pull request, as the space is set up. simple-herdr-projects does it on the machine.")
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.red) }
            Row(Modifier.fillMaxWidth().padding(top = Space.s, bottom = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End)) {
                SecondaryButton(Accept.CANCEL, onClick = onDismiss)
                PrimaryButton(if (busy || task.accepting) Accept.ACCEPTING else Accept.ACCEPT, enabled = board != null && !busy) { confirm(::accept) }
            }
        }
    }
}
