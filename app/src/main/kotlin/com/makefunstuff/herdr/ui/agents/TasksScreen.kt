package com.makefunstuff.herdr.ui.agents

import com.makefunstuff.herdr.core.hc.HcMapping
import com.makefunstuff.herdr.ui.manage.ExplainSheet
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.platform.LocalView
import com.makefunstuff.herdr.ui.design.confirmTap
import com.makefunstuff.herdr.core.chat.OptionRole
import com.makefunstuff.herdr.core.guard.Guarded

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import com.makefunstuff.herdr.core.home.HomeBoard
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.LoadingState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.makefunstuff.herdr.LocalPanes
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.board.CardAnswer
import com.makefunstuff.herdr.core.board.WorkCard
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.chat.AgentInput
import com.makefunstuff.herdr.core.chat.BlockedPrompt
import com.makefunstuff.herdr.core.home.AgentRow
import com.makefunstuff.herdr.core.home.Home
import com.makefunstuff.herdr.core.home.HomeAgent
import com.makefunstuff.herdr.core.home.Waiting
import com.makefunstuff.herdr.core.push.Alerts
import com.makefunstuff.herdr.ui.board.BoardPanel
import com.makefunstuff.herdr.ui.board.PanelCancel
import com.makefunstuff.herdr.ui.board.ReadBoards
import com.makefunstuff.herdr.ui.board.rememberBoardProblems
import com.makefunstuff.herdr.ui.board.rememberBoards
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrFab
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.manage.AgentActionsSheet
import com.makefunstuff.herdr.ui.share.Ask
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Rows a resting section shows before "more". */
private const val FOLDED = 4

/** A card as it stood when it was answered here: it stays hidden until this changes. */
private fun sig(w: Waiting): String = when (w) {
    is Waiting.Question -> "${w.agent.agent.stateChangeSeq}"
    is Waiting.Task -> "${w.task.status}|${w.task.line}"
    is Waiting.Coordinator -> "asks"
    is Waiting.Secret -> "secret"
}

/** What is being said back from a card: an answer to an agent's question. */
private sealed interface Reply {
    val item: Waiting
    data class ToAgent(override val item: Waiting.Question) : Reply
}

/** What the board or the machine said no with. */
private data class Refusal(val words: String)

/**
 * The Tasks tab, as Shepherdr 3.1's Tasks list: a filter row, then what waits for you across
 * machines (each card with its answers: Yes, No and Reply for a question; the card's choices for a
 * task), the Ready tasks with Accept, then the work by status: at work, queued, to do, done. One
 * row per task: a task's agent is its row. Agents that work on no task are rows too, and swipe:
 * right marks one seen, left shows Pin. The + offers New task, the Assistant and
 * Start an agent. On a wide window it is the left pane ([com.makefunstuff.herdr.Panes]): what it
 * opens shows beside it, chosen here. [listState] comes from above, so it stays as the list moves
 * between the two layouts.
 */
@Composable
fun TasksScreen(
    nav: NavController,
    listState: LazyListState = rememberLazyListState(),
) {
    val container = LocalContainer.current
    val panes = LocalPanes.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val seen by container.seen.marks.collectAsStateWithLifecycle()
    val triage by container.triage.state.collectAsStateWithLifecycle()
    val push by container.push.state.collectAsStateWithLifecycle()
    val boards = rememberBoards()
    val boardProblems = rememberBoardProblems()
    val readNow = remember { Channel<Unit>(Channel.CONFLATED) }
    ReadBoards(readNow)
    // Beside a wide window's list: what is open in the middle, to show it chosen here.
    val entry by nav.currentBackStackEntryAsState()
    val inMiddle = if (panes.wide) entry?.let(::opened) else null
    // The agent whose long-press sheet is open, with the task it works on.
    var menuAgent by remember { mutableStateOf<Pair<HomeAgent, WorkTask?>?>(null) }
    // "Why this state?" asked from TalkBack's actions on a card.
    var why by remember { mutableStateOf<HomeAgent?>(null) }
    // The row swiped open on the left (Pin), by agent key: one at a time.
    var swiped by remember { mutableStateOf<String?>(null) }
    val triageActions = rememberTriageActions()
    var reply by remember { mutableStateOf<Reply?>(null) }
    var refusal by remember { mutableStateOf<Refusal?>(null) }
    var allDone by rememberSaveable { mutableStateOf(false) }
    var allIdle by rememberSaveable { mutableStateOf(false) }
    // Cards answered here, by key, with how they stood; and the ones whose answer is on its way.
    val handled = remember { mutableStateMapOf<String, String>() }
    val busy = remember { mutableStateMapOf<String, String>() }
    val agents = remember(hosts) { HomeAgent.of(hosts) }
    val home = remember(agents, boards, seen, triage, push.rules) { Home.of(agents, boards, seen, triage, push.rules) }
    val needsYou = home.needsYou.filter { handled[it.key] != sig(it) }
    // A card answered here shows again once it changed and waits anew: a task that asked again, worded the same.
    LaunchedEffect(home.needsYou) {
        // Only on the machines shown: one filtered out hasn't changed.
        val now = home.needsYou.associate { it.key to sig(it) }
        handled.keys.filter { now[it] != handled[it] }.forEach { handled.remove(it) }
    }
    val showMachine = hosts.size > 1

    val guard = com.makefunstuff.herdr.ui.common.LocalGuard.current
    val view = LocalView.current

    fun press(q: Waiting.Question, prompt: BlockedPrompt, index: Int) {
        if (q.key in busy) return
        val label = prompt.options[index].label
        busy[q.key] = "Answering: $label"
        view.confirmTap()
        scope.launch {
            runCatching {
                val session = container.hosts.session(q.machineId) ?: error("This machine isn't in the app any more")
                AgentInput.choose(session.awaitAgent(q.agent.agent.paneId, 10_000), q.agent.agent.paneId, prompt.question, index, label, prompt.details)
                session.refresh()
            }
                .onSuccess {
                    handled[q.key] = sig(q)
                    container.inbox.dismiss(Alerts.agentTag(q.machineId, q.agent.agent.paneId))
                    context.toast("Answered: $label")
                }
                .onFailure { context.toast(it.message ?: "Couldn't answer") }
            busy.remove(q.key)
        }
    }

    /** An answer from the card; a permanent "always allow" asks for the fingerprint first, as on the chat's card and the notification. */
    fun answer(q: Waiting.Question, prompt: BlockedPrompt, index: Int) {
        if (prompt.role(index) == OptionRole.Always) {
            guard.run(Guarded.AlwaysAllow, "${prompt.shortLabel(index)}?", prompt.details.firstOrNull { it.startsWith("$ ") } ?: prompt.question) { press(q, prompt, index) }
        } else press(q, prompt, index)
    }

    fun say(r: Reply, text: String) {
        val w = r.item
        busy[w.key] = "Sending"
        scope.launch {
            runCatching {
                val session = container.hosts.session(w.machineId) ?: error("This machine isn't in the app any more")
                when (r) {
                    is Reply.ToAgent -> {
                        AgentInput.send(session.awaitAgent(r.item.agent.agent.paneId, 10_000), r.item.agent.agent.paneId, text, named = true, blocked = true)
                        session.refresh()
                        "Sent: $text"
                    }
                }
            }
                .onSuccess { handled[w.key] = sig(w); context.toast(it) }
                .onFailure { refusal = Refusal(it.message ?: "Couldn't send that") }
            busy.remove(w.key)
            readNow.trySend(Unit)
        }
    }

    /**
     * A Shepherdr 3 task's [what] through Shepherdr: its merge (the fingerprint first, when that is on) or an
     * answer to its card. The card hides once it is done.
     */
    fun work(w: Waiting.Task, what: String, run: suspend (com.makefunstuff.herdr.core.board.WorkBoard) -> String) {
        if (w.key in busy) return
        busy[w.key] = "$what · ${w.task.name}"
        scope.launch {
            runCatching { run((container.hosts.session(w.machineId) ?: error("This machine isn't in the app any more")).awaitWorkBoard()) }
                .onSuccess {
                    handled[w.key] = sig(w)
                    context.toast(it.ifEmpty { "Done" })
                }
                .onFailure { refusal = Refusal(it.message ?: "Shepherdr refused") }
            busy.remove(w.key)
            readNow.trySend(Unit)
        }
    }

    /** The secret ask whose masked box is up. */
    var giving by remember { mutableStateOf<Waiting.Secret?>(null) }

    /** A Ready task whose Accept card is up, and its machine. */
    var acceptFor by remember { mutableStateOf<Pair<Waiting.Task, WorkTask>?>(null) }
    // The Accept card first, which says what you approve (shp's own, as it has no evidence to read).
    fun merge(w: Waiting.Task, t: WorkTask) {
        acceptFor = w to t
    }

    fun choose(w: Waiting.Task, card: WorkCard, i: Int) =
        work(w, "Answering: ${card.question.choices.getOrNull(i)?.label.orEmpty()}") { it.answer(CardAnswer(card, i)).ifEmpty { "Answered" } }

    /** A task opens on its board, as its chat. */
    fun openTask(w: Waiting.Task) = panes.openBoard(nav, w.machineId, w.task.id)

    fun openAgent(a: HomeAgent) = panes.openAgent(nav, a.machineId, a.agent, hosts.any { it.machine.id == a.machineId && it.onShepherdr })

    /**
     * What an agent's long-press sheet offers besides its triage, as TalkBack's actions on its card
     * or row: its terminal, why it is in this state, and the sheet itself for the rest.
     */
    fun sheetActions(a: HomeAgent, task: WorkTask?) = listOfNotNull(
        CustomAccessibilityAction("Open terminal") { panes.open(nav, Routes.terminal(a.machineId, a.agent.paneId)); true },
        // Classic herdr explains its agents' states; herdr-core doesn't.
        CustomAccessibilityAction("Why this state?") { why = a; true }.takeIf { HcMapping.address(a.agent.paneId) == null },
        CustomAccessibilityAction("More actions") { menuAgent = a to task; true },
    )

    // 3.1's Tasks list: what waits for you, then the work by status, one row per task (its agent on it).
    val asked = needsYou.mapNotNull { w -> w.task?.let { w.machineId to it.id } }.toSet()
    // Where Shepherdr's label doesn't tie an agent to its task (an agent outside herdr-core), its title does: the same
    // words on the same machine are one piece of work, shown once.
    fun titleOf(a: HomeAgent, t: WorkTask?) = a.machineId to (t?.title ?: a.agent.subtitle ?: a.agent.displayName)
    val askedTitles = needsYou.mapNotNull { w ->
        when (w) {
            is Waiting.Question -> titleOf(w.agent, w.task)
            is Waiting.Task -> w.machineId to w.task.title
            is Waiting.Coordinator, is Waiting.Secret -> null
        }
    }.toSet()
    var query by rememberSaveable { mutableStateOf("") }
    fun hit(vararg words: String?) = query.isBlank() || words.filterNotNull().any { it.contains(query.trim(), ignoreCase = true) }
    fun hitRow(r: AgentRow) = hit(r.task?.title, r.task?.name, r.agent.agent.displayName, r.agent.agent.subtitle)
    // The filter narrows all of it, as 3.1's "Filter tasks" does.
    fun rows(list: List<AgentRow>) = list.filter { r ->
        if (r.task != null) (r.agent.machineId to r.task!!.id) !in asked else titleOf(r.agent, null) !in askedTitles
    }.filter(::hitRow)
    val agentRows = rows(home.pinned + home.working + home.done + home.idle)
    val taskWithAgent = agentRows.mapNotNull { r -> r.task?.let { r.agent.machineId to it.id } }.toSet()
    val agentTitles = agentRows.map { titleOf(it.agent, it.task) }.toSet() + askedTitles
    val ready = needsYou.filter { it is Waiting.Task && it.task.status == WorkStatus.Ready && hit(it.task.title, it.task.name, it.task.listName) }
    val waiting = (needsYou - ready.toSet()).filter { w ->
        when (w) {
            is Waiting.Task -> hit(w.task.title, w.task.name, w.task.listName)
            is Waiting.Question -> hit(w.task?.title, w.task?.name, w.agent.agent.displayName, w.agent.agent.subtitle)
            is Waiting.Secret -> hit(w.task?.title, w.task?.name, w.space?.name, w.ask.name)
            is Waiting.Coordinator -> hit(w.space.name)
        }
    }
    // Tasks no agent row carries, by status: Shepherdr's own rows.
    fun tasks(vararg st: WorkStatus) = boards.flatMap { b ->
        b.tasks.filter { t ->
            t.status in st && (b.machineId to t.id) !in asked && (b.machineId to t.id) !in taskWithAgent && (b.machineId to t.title) !in agentTitles &&
                hit(t.title, t.name, t.listName)
        }.map { b to it }
    }
    val workingTasks = tasks(WorkStatus.Work, WorkStatus.Merging)
    val queued = tasks(WorkStatus.Queued)
    val todo = tasks(WorkStatus.Todo)
    val doneTasks = tasks(WorkStatus.Done)
    val pinnedRows = rows(home.pinned)
    val workingRows = rows(home.working)
    val doneRows = rows(home.done)
    val idleRows = rows(home.idle)
    val counts = mapOf(
        TaskFilter.NeedsYou to waiting.size, TaskFilter.Ready to ready.size,
        TaskFilter.Working to workingRows.size + workingTasks.size, TaskFilter.Queued to queued.size,
        TaskFilter.Todo to todo.size, TaskFilter.Done to doneRows.size + doneTasks.size,
        TaskFilter.Idle to idleRows.size,
    )
    var shown by rememberSaveable { mutableStateOf(TaskFilter.All) }
    fun on(f: TaskFilter) = shown == TaskFilter.All || shown == f
    var adding by remember { mutableStateOf(false) }
    val connecting = hosts.count { it.status is com.makefunstuff.herdr.core.host.HostStatus.Connecting }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar("Tasks") {
                BarIcon(HerdrIcons.Search, "Search tasks, agents and chats", { panes.open(nav, Routes.search()) })
            }
        },
        floatingActionButton = {
            // 3.1's + menu: New task, the Assistant, Start an agent.
            if (hosts.any { it.online }) HerdrFab(null, "New task, Assistant or agent") { adding = true }
        },
    ) { padding ->
        when {
            hosts.isEmpty() -> EmptyState(
                "No machines yet",
                "Add a machine that runs herdr. Its tasks and agents show up here, the ones waiting on you first.",
                icon = HerdrIcons.Server,
            ) { PrimaryButton("Add machine") { nav.navigate(Routes.edit()) } }

            agents.isEmpty() && boards.isEmpty() && hosts.none { it.online } && connecting > 0 ->
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingState("Connecting to ${hosts.size} machine${if (hosts.size == 1) "" else "s"}")
                }

            agents.isEmpty() && boards.isEmpty() -> EmptyState(
                if (hosts.any { it.online }) "Nothing to do" else "Couldn't connect",
                if (hosts.any { it.online }) "No tasks or agents on any machine. Ask an Assistant to plan some, or start an agent."
                else "None of your machines answered. Open the spaces list to see why.",
                icon = if (hosts.any { it.online }) HerdrIcons.ClipboardList else HerdrIcons.Server,
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.padding(padding),
                // Room under the last row for the + button.
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                item(key = "task-filter") {
                    HerdrField(
                        query, { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = Space.screen).padding(top = Space.s, bottom = Space.xs),
                        placeholder = { Text("Filter tasks") }, leadingIcon = { Icon(HerdrIcons.Search, null, Modifier.size(18.dp)) },
                        singleLine = true,
                    )
                }
                item(key = "filters") { FilterRow(shown, counts) { shown = it } }
                boardProblems.forEach { (label, p) ->
                    item(key = "problem/$label") {
                        ErrorMessage("$label's tasks: $p", Modifier.padding(horizontal = Space.screen, vertical = Space.xs), onRetry = { readNow.trySend(Unit) })
                    }
                }
                val lines = Lines(
                    inMiddle, showMachine, triageActions, swiped, { swiped = it },
                    onOpen = ::openAgent, onMenu = { a, t -> menuAgent = a to t },
                    more = ::sheetActions,
                )
                if (shown == TaskFilter.All) section("Pinned", "Pinned · ${pinnedRows.size}", pinnedRows, showAll = true, onMore = {}, lines)
                if (on(TaskFilter.NeedsYou)) {
                    item(key = "h-needs") {
                        SectionHeading(
                            if (waiting.isEmpty()) "Needs you" else "Needs you · ${waiting.size}",
                            Modifier.padding(horizontal = Space.screen), top = Space.s,
                            color = if (waiting.isEmpty()) Herdr.colors.muted else Herdr.colors.red,
                        )
                    }
                    if (waiting.isEmpty()) item(key = "none-waiting") {
                        Text(
                            "Nothing waits for you", style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted,
                            modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.xs),
                        )
                    }
                }
                @Composable
                fun card(w: Waiting) = WaitingRow(
                    w, showMachine, busy = busy[w.key],
                    space = (w.task?.space?.takeIf { it.isNotEmpty() }?.let { id ->
                        boards.firstOrNull { it.machineId == w.machineId }?.work?.spaces?.firstOrNull { it.id == id }?.name
                    }).orEmpty(),
                    onOpen = {
                        when (w) {
                            is Waiting.Question -> openAgent(w.agent)
                            is Waiting.Task -> openTask(w)
                            is Waiting.Coordinator -> panes.open(nav, Routes.coordinator(w.machineId, w.space.id))
                            is Waiting.Secret -> {
                                val t = w.task
                                if (t != null) panes.openBoard(nav, w.machineId, t.id) else panes.open(nav, Routes.coordinator(w.machineId, w.space?.id))
                            }
                        }
                    },
                    onGive = { if (w is Waiting.Secret) giving = w },
                    onMerge = { if (w is Waiting.Task) merge(w, w.task) },
                    onChoose = { prompt, i -> if (w is Waiting.Question) answer(w, prompt, i) },
                    onReply = { if (w is Waiting.Question) reply = Reply.ToAgent(w) },
                    onMenu = (w as? Waiting.Question)?.let { { menuAgent = it.agent to it.task } },
                    more = (w as? Waiting.Question)?.let { sheetActions(it.agent, it.task) }.orEmpty(),
                )
                if (on(TaskFilter.NeedsYou)) items(waiting, key = { it.key }) { card(it) }
                if (on(TaskFilter.Ready) && ready.isNotEmpty()) {
                    item(key = "h-ready") { SectionHeading("Ready · ${ready.size}", Modifier.padding(horizontal = Space.screen), top = Space.l, color = Herdr.colors.done) }
                    items(ready, key = { it.key }) { card(it) }
                }
                if (on(TaskFilter.Working)) {
                    section("Working", "Working · ${workingRows.size + workingTasks.size}", workingRows, showAll = true, onMore = {}, lines, extra = workingTasks.size)
                    taskRows("working", workingTasks, showMachine, onHeading = workingRows.isEmpty(), heading = "Working · ${workingTasks.size}") { (b, t) -> panes.openBoard(nav, b.machineId, t.id, alone = true) }
                }
                if (on(TaskFilter.Queued)) taskRows("queued", queued, showMachine, heading = "Queued · ${queued.size}") { (b, t) -> panes.openBoard(nav, b.machineId, t.id, alone = true) }
                if (on(TaskFilter.Todo)) taskRows("todo", todo, showMachine, heading = "To do · ${todo.size}") { (b, t) -> panes.openBoard(nav, b.machineId, t.id, alone = true) }
                if (on(TaskFilter.Done)) {
                    // Done holds the finishes not looked at yet: Mark all seen empties it (they rest with the idle ones).
                    section(
                        "Done", "Done · ${doneRows.size + doneTasks.size}", doneRows, showAll = allDone, onMore = { allDone = !allDone }, lines,
                        onSeen = if (home.fresh > 0) ({ container.seen.set(Home.seenAll(seen, home, agents)) }) else null,
                        extra = doneTasks.size,
                    )
                    taskRows(
                        "done", if (allDone) doneTasks else doneTasks.take(DONE_SHOWN), showMachine, onHeading = doneRows.isEmpty(),
                        heading = "Done · ${doneTasks.size}",
                    ) { (b, t) -> panes.openBoard(nav, b.machineId, t.id, alone = true) }
                    if (doneTasks.size > DONE_SHOWN) item(key = "more-done-tasks") {
                        Text(
                            if (allDone) "Show less" else "Show ${doneTasks.size - DONE_SHOWN} more",
                            style = MaterialTheme.typography.labelLarge, color = Herdr.colors.accent,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { allDone = !allDone }.padding(horizontal = Space.screen, vertical = Space.m),
                        )
                    }
                }
                if (on(TaskFilter.Idle)) {
                    section("Idle", "Idle · ${idleRows.size}", idleRows, showAll = allIdle, onMore = { allIdle = !allIdle }, lines)
                }
                if (!triage.used && (workingRows.size + doneRows.size + idleRows.size) > 0 && shown == TaskFilter.All) {
                    item(key = "triage-hint") {
                        Notice(
                            "Swipe an agent right: seen. Left: pin. Its long-press menu has those and its space's Notify.",
                            Modifier.padding(horizontal = Space.screen, vertical = Space.l),
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        NewMenu(
            hosts.filter { it.online }, onDismiss = { adding = false },
            onTask = { mid -> adding = false; panes.open(nav, Routes.start(mid)) },
            onAssistant = { mid -> adding = false; panes.open(nav, Routes.coordinator(mid)) },
            onAgent = { mid -> adding = false; panes.open(nav, Routes.start(mid)) },
        )
    }

    menuAgent?.let { (a, task) ->
        container.hosts.session(a.machineId)?.let { session ->
            AgentActionsSheet(
                session = session,
                agent = a.agent,
                paneLabel = a.agent.paneId,
                onDismiss = { menuAgent = null },
                onTerminal = { menuAgent = null; panes.open(nav, Routes.terminal(a.machineId, a.agent.paneId)) },
                triage = { TriageRows(a) },
                task = task,
            )
        }
    }
    why?.let { a -> container.hosts.session(a.machineId)?.let { ExplainSheet(it, a.agent.paneId, onDismiss = { why = null }) } }
    reply?.let { r ->
        var text by remember(r) { mutableStateOf("") }
        val dismiss = { reply = null }
        BoardPanel(
            when (r) {
                is Reply.ToAgent -> "Answer ${r.item.task?.name ?: r.item.agent.agent.displayName}"
            },
            onDismiss = dismiss,
            buttons = {
                PanelCancel(dismiss)
                PrimaryButton("Send", enabled = text.isNotBlank()) { reply = null; say(r, text.trim()) }
            },
        ) {
            (r as? Reply.ToAgent)?.let { q -> rememberAsked(q.item.agent)?.question }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            HerdrField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, placeholder = { Text("Your answer") })
        }
    }
    refusal?.let { r ->
        BoardPanel(
            "That didn't go through", onDismiss = { refusal = null }, accent = Herdr.colors.red,
            buttons = {
                PrimaryButton("OK") { refusal = null }
            },
        ) {
            MarkdownText(r.words)
        }
    }
    giving?.let { w ->
        SecretSheet(
            w, onDismiss = { giving = null },
            onKept = { said ->
                giving = null
                handled[w.key] = sig(w)
                context.toast(said)
                readNow.trySend(Unit)
            },
            keep = { level, value ->
                (container.hosts.session(w.machineId) ?: error("This machine isn't in the app any more")).awaitWorkBoard()
                    .keepSecret(w.need.chat, w.ask, level, value)
            },
        )
    }
    acceptFor?.let { (w, t) ->
        val board by androidx.compose.runtime.produceState<com.makefunstuff.herdr.core.board.WorkBoard?>(null, w.machineId) {
            value = runCatching { container.hosts.session(w.machineId)?.awaitWorkBoard() }.getOrNull()
        }
        val shp = container.hosts.session(w.machineId)?.state?.value?.boardApi is com.makefunstuff.herdr.core.board.BoardApi.Shp
        if (shp) com.makefunstuff.herdr.ui.board.ShpAcceptSheet(
            board, t, onDismiss = { acceptFor = null },
            onAccepted = { context.toast(it); readNow.trySend(Unit) },
            confirm = { run -> guard.run(Guarded.Merge, "Accept ${t.name}?", t.title) { view.confirmTap(); run() } },
        ) else com.makefunstuff.herdr.ui.board.AcceptSheet(
            board, t, onDismiss = { acceptFor = null },
            onProof = { id -> nav.navigate(com.makefunstuff.herdr.Routes.artifact(w.machineId, id, null)) },
            onAccepted = {
                context.toast(it)
                readNow.trySend(Unit)
                container.changes.accepted(w.machineId, t)
            },
            confirm = { run -> guard.run(Guarded.Merge, "Accept ${t.name}?", t.title) { view.confirmTap(); run() } },
        )
    }
}

/** What every one-line agent on the list shares: what is open beside it, its swipes, its taps. */
private class Lines(
    val inMiddle: String?,
    val showMachine: Boolean,
    val actions: TriageActions,
    /** The row swiped open on the left, by agent key. */
    val swiped: String?,
    val onSwiped: (String?) -> Unit,
    val onOpen: (HomeAgent) -> Unit,
    val onMenu: (HomeAgent, WorkTask?) -> Unit,
    /** The rest of an agent's long-press sheet, as TalkBack's actions on its row. */
    val more: (HomeAgent, WorkTask?) -> List<CustomAccessibilityAction>,
)

/** A section of one-line agents under its [heading]; a long resting one folds after [FOLDED] rows. [onSeen]: Mark all seen. */
private fun LazyListScope.section(
    title: String,
    heading: String,
    rows: List<AgentRow>,
    showAll: Boolean,
    onMore: () -> Unit,
    lines: Lines,
    onSeen: (() -> Unit)? = null,
    /** Task rows listed after these under the same heading. */
    extra: Int = 0,
) {
    if (rows.isEmpty()) return
    item(key = "h-$title") {
        SectionHeading(
            heading,
            Modifier.padding(start = Space.screen, end = Space.s), top = Space.l,
            trailing = if (onSeen != null) ({ QuietButton(onClick = onSeen, color = Herdr.colors.accent) { Text("Mark all seen") } }) else null,
        )
    }
    val shown = if (showAll || rows.size <= FOLDED + 1) rows else rows.take(FOLDED)
    items(shown, key = { "${title}/${it.agent.key}" }) { row ->
        val key = row.agent.key
        SwipeRow(row, lines.actions, open = lines.swiped == key, onOpen = { lines.onSwiped(if (it) key else null) }) {
            AgentLine(
                row, lines.showMachine, selected = lines.inMiddle == key,
                onOpen = { lines.onOpen(row.agent) }, onMenu = { lines.onMenu(row.agent, row.task) },
                actions = lines.actions.accessibility(row) + lines.more(row.agent, row.task),
            )
        }
    }
    if (rows.size > FOLDED + 1) {
        item(key = "more-$title") {
            Text(
                if (showAll) "Show fewer" else "${rows.size - FOLDED} more",
                style = MaterialTheme.typography.labelLarge, color = Herdr.colors.accent,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onMore).padding(horizontal = Space.screen + Space.xs, vertical = Space.m),
            )
        }
    }
}

/** 3.1's status filters: all, then each group with its count, the ones holding nothing left out. */
enum class TaskFilter(val label: String) { All("All"), NeedsYou("Needs you"), Ready("Ready"), Working("Working"), Queued("Queued"), Todo("To do"), Done("Done"), Idle("Idle") }

/** Done tasks shown before "Show N more", as 3.1's list cuts them. */
private const val DONE_SHOWN = 5

/** The filter row under the bar: All, then every group that holds something, with its count. */
@Composable
private fun FilterRow(shown: TaskFilter, counts: Map<TaskFilter, Int>, onPick: (TaskFilter) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Space.screen),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        val choices = TaskFilter.entries.filter { it == TaskFilter.All || it == shown || (counts[it] ?: 0) > 0 }
        items(choices, key = { it.name }) { f ->
            val n = counts[f] ?: 0
            HerdrChip(if (f == TaskFilter.All) "All" else "${f.label} $n", selected = shown == f, onClick = { onPick(if (shown == f) TaskFilter.All else f) })
        }
    }
}

/** Shepherdr tasks no agent row carries, as rows under [heading] (or under the rows above, when [onHeading] is false). */
private fun LazyListScope.taskRows(
    key: String,
    list: List<Pair<HomeBoard, WorkTask>>,
    showMachine: Boolean,
    heading: String,
    onHeading: Boolean = true,
    onOpen: (Pair<HomeBoard, WorkTask>) -> Unit,
) {
    if (list.isEmpty()) return
    if (onHeading) item(key = "h-tasks-$key") { SectionHeading(heading, Modifier.padding(horizontal = Space.screen), top = Space.l) }
    items(list, key = { "t-$key/${it.first.machineId}/${it.second.id}" }) { bt ->
        val (b, t) = bt
        Box(Modifier.padding(horizontal = Space.screen)) {
            com.makefunstuff.herdr.ui.board.WorkTaskRow(
                t, onOpen = { onOpen(bt) },
                space = listOfNotNull(b.machineLabel.takeIf { showMachine }, b.work.spaces.firstOrNull { it.id == t.space }?.name).joinToString(" · "),
                showWord = false,
            )
        }
    }
}

/** 3.1's + menu on a phone: New task, the Assistant, Start an agent; on which machine, when there are several. */
@Composable
private fun NewMenu(
    online: List<com.makefunstuff.herdr.core.host.HostState>,
    onDismiss: () -> Unit,
    onTask: (String?) -> Unit,
    onAssistant: (String) -> Unit,
    onAgent: (String?) -> Unit,
) {
    val boards = online.filter { it.onShepherdr }
    HerdrMenu(expanded = true, onDismissRequest = onDismiss, title = "New") {
        if (boards.isNotEmpty()) MenuRow("New task", HerdrIcons.SquarePen, { onTask(boards.singleOrNull()?.machine?.id) })
        boards.forEach { h ->
            MenuRow(if (boards.size == 1) "Assistant" else "Assistant · ${h.machine.label}", HerdrIcons.MessagesSquare, { onAssistant(h.machine.id) })
        }
        MenuRow("Start an agent", HerdrIcons.Bot, { onAgent(online.singleOrNull()?.machine?.id) })
    }
}

/** What the middle of a wide window shows, as the list names it: `machine/pane` for an agent's chat or terminal, else the route. */
private fun opened(entry: NavBackStackEntry): String? {
    val args = entry.arguments
    return when (entry.destination.route) {
        Routes.AGENT, Routes.TERMINAL -> "${args?.getString("mid")}/${Uri.decode(args?.getString("pane").orEmpty())}"
        else -> null
    }
}

/** Picks the line most likely to be the agent's question from the bottom of its screen. */
object PromptGuess {
    private val noise = Regex("^[\\s│╭╮╰╯─┃━┏┓┗┛>❯›·•|_=+-]*$")

    fun question(screen: String): String? {
        val lines = screen.lines()
            .map { it.trim().trim('│', '┃').trim() }
            .filter { it.isNotEmpty() && !noise.matches(it) }
        if (lines.isEmpty()) return null
        return lines.lastOrNull { it.endsWith("?") } ?: lines.last()
    }
}
