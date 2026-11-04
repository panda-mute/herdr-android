package com.makefunstuff.herdr.ui.board

import com.makefunstuff.herdr.ui.design.approves
import androidx.compose.ui.platform.LocalView
import com.makefunstuff.herdr.ui.design.confirmTap
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.AppContainer
import com.makefunstuff.herdr.LocalPanes
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.board.Approvals
import com.makefunstuff.herdr.core.board.BoardAction
import com.makefunstuff.herdr.core.guard.Guarded
import com.makefunstuff.herdr.ui.common.LocalGuard
import com.makefunstuff.herdr.core.board.BoardStatus
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.CheckRun
import com.makefunstuff.herdr.core.board.CheckState
import com.makefunstuff.herdr.core.board.TaskDetails
import com.makefunstuff.herdr.core.board.WorkFacts
import com.makefunstuff.herdr.core.code.ChangeSet
import com.makefunstuff.herdr.core.code.DiffScope
import com.makefunstuff.herdr.core.host.HostStatus
import com.makefunstuff.herdr.core.push.Alerts
import com.makefunstuff.herdr.ui.code.AddedRemoved
import com.makefunstuff.herdr.ui.code.ChangeLetter
import com.makefunstuff.herdr.ui.code.CheckoutTab
import com.makefunstuff.herdr.ui.code.CodeSpot
import com.makefunstuff.herdr.ui.code.DiffFileRow
import com.makefunstuff.herdr.ui.code.DiffSource
import com.makefunstuff.herdr.ui.code.Load
import com.makefunstuff.herdr.ui.code.changesKey
import com.makefunstuff.herdr.ui.code.code
import com.makefunstuff.herdr.ui.code.infoKey
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.common.Pill
import com.makefunstuff.herdr.ui.common.openUrl
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.DangerButton
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.StateBlock
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What one card shows beyond its row of the list, read when it comes into view; [sig] is the task as it stood then. */
@Stable
private class CardData(val sig: String) {
    var details by mutableStateOf<TaskDetails?>(null)
    var facts by mutableStateOf<WorkFacts?>(null)
    var factsProblem by mutableStateOf<String?>(null)
    /** Null: there is no worktree to read the diff from. */
    var changes by mutableStateOf<Load<ChangeSet>?>(null)
    var spot by mutableStateOf<CodeSpot?>(null)
    var base by mutableStateOf("")
    var problem by mutableStateOf<String?>(null)
    var read = false
}

/** A task as it stands: a card read for it, or an action taken on it, holds until this changes. */
private fun sig(t: BoardTask) = listOf(t.state, t.stage, t.kind, t.msg, t.reviewRound, t.reviewPassed, t.approvalBlocked).joinToString("|")

/** What a card's action is part-way through: words to send back, being sure to stop, being sure to merge what failed. */
private sealed interface Ask {
    val item: Approvals.Item
    data class SendBack(override val item: Approvals.Item) : Ask
    data class Stop(override val item: Approvals.Item) : Ask
    data class Anyway(override val item: Approvals.Item, val failed: List<String>) : Ask
}

/**
 * Finished work, one card per task that waits for you (ready to approve, review failed, plan ready,
 * pick one), across every machine with a work board: what changed, the review's verdict and its
 * findings, the checks, the pull request. Swipe to the next; act on the card in front. A card that
 * was acted on goes, and the next one shows. Opens at [startTask] on [startMachine] when given.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(nav: NavController, startMachine: String? = null, startTask: String? = null) {
    val container = LocalContainer.current
    val panes = LocalPanes.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val guard = LocalGuard.current
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    // herdr-projects 1.x's: a Shepherdr 3 machine's task is merged from its own screens.
    val boards = hosts.filter { it.hasBoard && !it.onShepherdr }
    val online = boards.filter { it.online }.map { it.machine.id }
    var lists by remember { mutableStateOf<Map<String, List<BoardTask>>>(emptyMap()) }
    var problems by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    // Cards acted on here, by key, with the task as it stood: gone until the board says something new of it.
    val handled = remember { mutableStateMapOf<String, String>() }
    // What each card read, by key: the pager's page and the action bar share it; read again once the task changes.
    val cards = remember { HashMap<String, CardData>() }
    fun cardFor(item: Approvals.Item): CardData {
        val s = sig(item.task)
        return cards[item.key]?.takeIf { it.sig == s } ?: CardData(s).also { cards[item.key] = it }
    }
    // A Shepherdr 3 task has no review card (a notification tapped before the app knew its board): once
    // its machine says it runs Shepherdr 3, the task opens on its board instead, as its agent's chat.
    val startOnV2 = startMachine != null && startTask != null && hosts.any { it.machine.id == startMachine && it.onShepherdr }
    LaunchedEffect(startOnV2) {
        if (!startOnV2 || startMachine == null || startTask == null) return@LaunchedEffect
        // Only a task Shepherdr 3 lists: herdr-projects 1.x may run beside it, with review cards of its own.
        val session = container.hosts.session(startMachine) ?: return@LaunchedEffect
        val onV2 = runCatching { session.awaitWorkBoard().overview().tasks.any { it.id == startTask } }.getOrDefault(false)
        if (onV2) {
            nav.popBackStack()
            panes.openBoard(nav, startMachine, startTask, alone = true)
        }
    }
    val readNow = remember { Channel<Unit>(Channel.CONFLATED) }
    var busy by remember { mutableStateOf<String?>(null) }
    var ask by remember { mutableStateOf<Ask?>(null) }
    var refusal by remember { mutableStateOf<String?>(null) }

    // Every board's list, read again while the screen is in front (sooner after an action); nothing while it's backgrounded.
    LaunchedEffect(online.joinToString(",")) {
        if (online.isEmpty()) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val read = coroutineScope {
                    online.map { id ->
                        async {
                            id to try {
                                Result.success(container.hosts.session(id)?.awaitBoard()?.tasks().orEmpty())
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                Result.failure(t)
                            }
                        }
                    }.awaitAll()
                }
                read.forEach { (id, r) -> r.getOrNull()?.let { container.tally.tasks(id, it); container.boards.put(id, it) } }
                lists = lists.filterKeys { it in online } + read.mapNotNull { (id, r) -> r.getOrNull()?.let { id to it } }
                problems = read.mapNotNull { (id, r) -> r.exceptionOrNull()?.let { id to (it.message ?: "Couldn't read the board") } }.toMap()
                loaded = true
                withTimeoutOrNull(10_000L) { readNow.receive() }
            }
        }
    }

    val queue = Approvals.queue(boards.mapNotNull { h -> lists[h.machine.id]?.let { h.machine.id to it } })
        .filter { handled[it.key] != sig(it.task) }
    val pager = rememberPagerState { queue.size }
    var currentKey by rememberSaveable { mutableStateOf<String?>(null) }
    var started by rememberSaveable { mutableStateOf(false) }
    val keys = queue.map { it.key }
    // Open at the card asked for; later keep the card in front in front as the list changes. One that went leaves its place to the next.
    LaunchedEffect(keys, loaded) {
        if (queue.isEmpty() || !loaded) return@LaunchedEffect
        val at = if (!started) {
            started = true
            queue.indexOfFirst { it.machineId == startMachine && it.task.id == startTask }.takeIf { it >= 0 }
                ?: queue.indexOfFirst { it.machineId == startMachine }.takeIf { it >= 0 }
        } else queue.indexOfFirst { it.key == currentKey }.takeIf { it >= 0 }
        if (at != null && at != pager.currentPage) pager.scrollToPage(at)
    }
    val current = queue.getOrNull(pager.currentPage)
    LaunchedEffect(current?.key) {
        currentKey = current?.key ?: currentKey
        // The card in front has done its notification's job.
        current?.let { container.inbox.dismiss(Alerts.taskTag(it.machineId, it.task.id)) }
    }

    fun act(item: Approvals.Item, action: BoardAction, text: String, letter: String) {
        if (busy != null) return
        val session = container.hosts.session(item.machineId) ?: return
        busy = "${action.label.removeSuffix("…")} · ${item.task.name}"
        if (action.approves) view.confirmTap()
        scope.launch {
            runCatching { session.awaitBoard().act(action, item.task.id, text, letter) }
                .onSuccess { context.toast(it.ifEmpty { "Done" }); handled[item.key] = sig(item.task) }
                .onFailure { refusal = it.message ?: "The board refused" }
            busy = null
            readNow.trySend(Unit)
        }
    }

    fun perform(item: Approvals.Item, action: BoardAction, text: String = "", letter: String = "") {
        if (busy != null) return
        // A merge can't be taken back: the fingerprint first, when that is on.
        if (action == BoardAction.Approve || action == BoardAction.ApproveAnyway) {
            return guard.run(Guarded.Merge, "Approve and merge ${item.task.name}?", item.task.title) { act(item, action, text, letter) }
        }
        act(item, action, text, letter)
    }

    fun openBoard(item: Approvals.Item?) {
        if (item == null) boards.firstOrNull()?.let { panes.openBoard(nav, it.machine.id) } else panes.openBoard(nav, item.machineId, item.task.id)
    }

    val n = queue.size
    val page = pager.currentPage.coerceAtMost((n - 1).coerceAtLeast(0))
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Herdr.colors.ground,
            topBar = {
                HerdrTopBar(
                    "Finished work",
                    subtitle = when {
                        n == 0 -> if (loaded) "Nothing waits for you" else "Reading the boards"
                        n == 1 -> "1 waits for you"
                        page == n - 1 -> "$n of $n · the last one"
                        else -> "${page + 1} of $n · ${n - page - 1} more after this"
                    },
                    onBack = { nav.popBackStack() },
                ) {
                    if (n > 1) {
                        BarIcon(HerdrIcons.ChevronLeft, "Previous card", { scope.launch { pager.animateScrollToPage(page - 1) } }, enabled = page > 0)
                        BarIcon(HerdrIcons.ChevronRight, "Next card", { scope.launch { pager.animateScrollToPage(page + 1) } }, enabled = page < n - 1)
                    }
                    BarIcon(HerdrIcons.SquareKanban, "Open on the board", { openBoard(current) })
                }
            },
            bottomBar = {
                val item = current
                if (item != null) {
                    val data = cardFor(item)
                    CardActions(
                        item, data.details, data.facts, busy,
                        onApprove = { action -> perform(item, action) },
                        onAnyway = { failed -> ask = Ask.Anyway(item, failed) },
                        onSendBack = { ask = Ask.SendBack(item) },
                        onStop = { ask = Ask.Stop(item) },
                        onNext = if (page < n - 1) ({ scope.launch { pager.animateScrollToPage(page + 1) } }) else null,
                    )
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                busy?.let { BusyLine(it) }
                problems.forEach { (id, p) ->
                    val label = boards.firstOrNull { it.machine.id == id }?.machine?.label ?: id
                    ErrorMessage("$label: $p", Modifier.padding(horizontal = Space.screen, vertical = Space.xs), onRetry = { readNow.trySend(Unit) })
                }
                when {
                    boards.isEmpty() && hosts.any { it.machine.enabled && (it.status == HostStatus.Connecting || (it.online && !it.boardChecked)) } ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingState("Connecting to your machines") }
                    boards.isEmpty() -> StateBlock(
                        HerdrIcons.SquareKanban, "No work board", "None of your machines has herdr-projects, so no task waits for a review.",
                        Modifier.padding(top = Space.xxl),
                    )
                    online.isEmpty() -> StateBlock(
                        HerdrIcons.Monitor, "Not connected",
                        "Waiting for ${boards.joinToString(", ") { it.machine.label }} to answer. The cards come once a board can be read.",
                        Modifier.padding(top = Space.xxl),
                    )
                    !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingState("Reading the boards") }
                    queue.isEmpty() -> Column(Modifier.fillMaxWidth().padding(top = Space.xxl), horizontalAlignment = Alignment.CenterHorizontally) {
                        StateBlock(
                            HerdrIcons.CircleCheckBig, "All caught up",
                            "Finished work shows here, one card a task, when it waits for your yes: ready to approve, review failed, a plan, a pick.",
                        )
                        SecondaryButton("Open the board", Modifier.padding(top = Space.l)) { openBoard(null) }
                    }
                    else -> HorizontalPager(
                        pager,
                        modifier = Modifier.fillMaxSize(),
                        key = { queue.getOrNull(it)?.key ?: it },
                        beyondViewportPageCount = 1,
                        pageSpacing = Space.l,
                    ) { p ->
                        val item = queue.getOrNull(p) ?: return@HorizontalPager
                        val data = cardFor(item)
                        LaunchedEffect(data) { if (!data.read) { data.read = true; readCard(container, item, data) } }
                        ReviewCard(
                            item, data,
                            machineLabel = if (boards.size > 1) boards.firstOrNull { it.machine.id == item.machineId }?.machine?.label else null,
                            busy = busy != null,
                            onDiff = { spot, path -> nav.navigate(Routes.codeDiff(spot, DiffSource.base(data.base), path)) },
                            onCode = { spot -> nav.navigate(Routes.codeAt(spot, CheckoutTab.Changes)) },
                            onPick = { letter -> perform(item, BoardAction.Pick, letter = letter) },
                        )
                    }
                }
            }
        }

        refusal?.let { r ->
            BoardPanel("The board said no", onDismiss = { refusal = null }, accent = Herdr.colors.red, buttons = { PrimaryButton("OK") { refusal = null } }) {
                MarkdownText(r)
            }
        }
        when (val a = ask) {
            null -> {}
            is Ask.SendBack -> SendBackPanel(
                a.item.task, onDismiss = { ask = null },
                onSend = { text -> ask = null; perform(a.item, if (a.item.task.status == BoardStatus.PlanReady) BoardAction.SendPlanBack else BoardAction.Tell, text) },
                onFindings = { ask = null; perform(a.item, BoardAction.Review) },
            )
            is Ask.Stop -> BoardPanel(
                "Stop ${a.item.task.name}?", onDismiss = { ask = null },
                buttons = {
                    QuietButton(onClick = { ask = null }) { Text("Cancel") }
                    DangerButton("Stop it") { ask = null; perform(a.item, BoardAction.Stop) }
                },
            ) { Text("Stops ${a.item.task.name}'s agent. Its work is kept, and the task waits for you as stopped.", color = Herdr.colors.muted) }
            is Ask.Anyway -> AnywayPanel(a.item.task, a.failed, onDismiss = { ask = null }, onApprove = { ask = null; perform(a.item, BoardAction.ApproveAnyway) })
        }
    }
}

/** Reads a card: the task's details, its record's facts (in parallel), then its worktree's diff against its base. */
private suspend fun readCard(container: AppContainer, item: Approvals.Item, data: CardData) {
    val session = container.hosts.session(item.machineId) ?: return
    try {
        val board = session.awaitBoard()
        coroutineScope {
            val facts = async {
                try {
                    data.facts = board.facts(item.task)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    data.factsProblem = t.message ?: "Couldn't read its checks"
                }
            }
            val d = board.details(item.task.id)
            data.details = d
            // Another machine's task works in a folder over there: its diff can't be read here.
            if (d.worktree.isNotEmpty() && item.task.machine.isEmpty()) {
                val spot = CodeSpot(item.machineId, d.worktree, task = item.task.id, taskName = item.task.name)
                data.spot = spot
                data.changes = Load.Loading
                data.changes = try {
                    val code = session.code()
                    val info = withContext(Dispatchers.IO) { code.info(spot.dir) }
                    container.code.cache.put(infoKey(spot), info)
                    data.base = info.base
                    // Read again, not from the cache: the agent may have changed it since. The diff screen takes it from there.
                    val set = withContext(Dispatchers.IO) { code.changes(spot.dir, info.base, DiffScope.Base) }
                    container.code.cache.put(changesKey(spot, DiffSource.base(info.base)), set)
                    Load.Ready(set)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Load.Failed(t.message ?: "Couldn't read the diff")
                }
            }
            facts.await()
        }
    } catch (e: CancellationException) {
        data.read = false
        throw e
    } catch (t: Throwable) {
        data.problem = t.message ?: "Couldn't read the task"
    }
}

/** One card: the task, what failed, what changed and the agent's note, the plan or the attempts, the review, the checks, the links. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewCard(
    item: Approvals.Item,
    data: CardData,
    machineLabel: String?,
    busy: Boolean,
    onDiff: (CodeSpot, String) -> Unit,
    onCode: (CodeSpot) -> Unit,
    onPick: (String) -> Unit,
) {
    val t = item.task
    val c = Herdr.colors
    val context = LocalContext.current
    val d = data.details
    val f = data.facts
    val failed = Approvals.failed(t, d, f)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = Space.screen).padding(top = Space.m, bottom = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(t.name, style = CodeStyle.copy(fontSize = 13.sp), color = c.strong, modifier = Modifier.align(Alignment.CenterVertically))
                StatusBadge(t.status.word, t.status.color())
                if (machineLabel != null) Pill(machineLabel)
                if (t.agent.isNotEmpty()) Pill(t.agent)
            }
            Text(t.title, style = MaterialTheme.typography.headlineSmall, color = c.strong)
        }
        data.problem?.let { ErrorMessage(it) }
        if (failed.isNotEmpty()) QuietCard(padded = true, color = c.tint(c.red).compositeOver(c.ground), edge = c.red.copy(alpha = 0.35f)) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text("What failed", style = MaterialTheme.typography.titleSmall, color = c.red, modifier = Modifier.semantics { heading() })
                failed.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = c.text) }
            }
        }
        // A plan has no change yet; each of several attempts says its own.
        if (t.status != BoardStatus.PlanReady && t.status != BoardStatus.PickOne) ChangesPart(data, onDiff, onCode)
        NotePart(f?.note.orEmpty(), loading = f == null && data.factsProblem == null && data.problem == null)
        if (d != null && d.plan.isNotEmpty()) Part("Plan", HerdrIcons.ClipboardList) { MarkdownText(d.plan, style = MaterialTheme.typography.bodyMedium) }
        if (d != null && d.attempts.isNotEmpty()) Part(if (t.status == BoardStatus.PickOne) "Pick one of ${d.attempts.size}" else "Attempts", HerdrIcons.ListTodo) {
            d.attempts.forEachIndexed { i, a ->
                if (i > 0) RowDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${a.letter} · ${a.agent}", style = MaterialTheme.typography.titleSmall)
                        Text(listOf(a.standing, a.stat).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                    if (a.report.isNotEmpty()) QuietButton(onClick = { context.openUrl(a.report) }, color = c.accent) { Text("Report") }
                    if (t.status == BoardStatus.PickOne) PrimaryButton("Pick ${a.letter}", enabled = !busy) { onPick(a.letter) }
                }
            }
            if (d.comparison.isNotEmpty()) MarkdownText(d.comparison, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        ReviewPart(d)
        ChecksPart(f, data.factsProblem, loading = f == null && data.factsProblem == null && data.problem == null)
        val pr = d?.pr?.ifEmpty { null } ?: t.pr.ifEmpty { null }
        if (pr != null) LinkRow("Pull request" + t.prNumber.takeIf { it.isNotEmpty() }?.let { " #$it" }.orEmpty(), pr.substringAfter("github.com/")) { context.openUrl(pr) }
        d?.reportLink?.takeIf { it.isNotEmpty() }?.let { r -> LinkRow(if (d.research) "Research report" else "Report", r.substringAfter("://")) { context.openUrl(r) } }
    }
}

/** A titled part of a card, padded. */
@Composable
private fun Part(title: String, icon: ImageVector, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    QuietCard(padded = true, modifier = Modifier.animateContentSize()) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            PartHead(title, icon, trailing = trailing)
            content()
        }
    }
}

@Composable
private fun PartHead(title: String, icon: ImageVector, tint: Color = Herdr.colors.muted, trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Herdr.colors.strong, modifier = Modifier.weight(1f).padding(start = Space.s).semantics { heading() })
        trailing?.invoke()
    }
}

/**
 * What changed: how many files and lines, the biggest few; a tap opens every file, each a tap from
 * its diff (the code browser's diff view), and the checkout's changes in the code browser.
 */
@Composable
private fun ChangesPart(data: CardData, onDiff: (CodeSpot, String) -> Unit, onCode: (CodeSpot) -> Unit) {
    val c = Herdr.colors
    val changes = data.changes
    val set = changes?.value
    var open by rememberSaveable(data.spot?.dir) { mutableStateOf(false) }
    val stat = data.facts?.stat.orEmpty()
    QuietCard(modifier = Modifier.animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .then(if (set != null && set.files.isNotEmpty()) Modifier.clickable(onClickLabel = if (open) "Hide the files" else "Show the full diff") { open = !open } else Modifier)
                .padding(horizontal = Space.card, vertical = Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HerdrIcons.GitCompare, null, tint = c.muted, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = Space.s)) {
                Text("What changed", style = MaterialTheme.typography.titleMedium, color = c.strong, modifier = Modifier.semantics { heading() })
                Text(
                    when {
                        set != null && set.noBase -> "It shares no history with its base"
                        set != null -> "${set.files.size} file${if (set.files.size == 1) "" else "s"} changed" + (if (data.base.isNotEmpty()) " against ${data.base}" else "")
                        changes is Load.Failed -> stat.ifEmpty { changes.message }
                        changes == Load.Loading || (data.details == null && data.problem == null) -> "Reading the diff"
                        else -> stat.ifEmpty { "No worktree to read the diff from" }
                    },
                    style = MaterialTheme.typography.bodySmall, color = c.muted,
                )
            }
            if (set != null && set.files.isNotEmpty()) {
                AddedRemoved(set.added, set.removed, Modifier.padding(start = Space.s))
                Icon(if (open) HerdrIcons.ChevronUp else HerdrIcons.ChevronDown, null, tint = c.muted, modifier = Modifier.padding(start = Space.s).size(20.dp))
            }
        }
        if (changes is Load.Failed) ErrorMessage(changes.message, Modifier.padding(start = Space.card, end = Space.card, bottom = Space.m))
        val spot = data.spot
        if (set != null && spot != null && set.files.isNotEmpty()) {
            if (!open) {
                // The biggest few, so the card says where the work is without opening it.
                val top = set.files.sortedByDescending { it.added + it.removed }.take(3)
                Column(Modifier.padding(start = Space.card, end = Space.card, bottom = Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    top.forEach { fd ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ChangeLetter(fd.change)
                            Text(
                                fd.path, style = MaterialTheme.typography.bodySmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(horizontal = Space.s),
                            )
                            AddedRemoved(fd.added, fd.removed)
                        }
                    }
                    if (set.files.size > top.size) Text("and ${set.files.size - top.size} more · tap for the full diff", style = MaterialTheme.typography.labelMedium, color = c.muted)
                }
            } else {
                if (set.truncated) Text("The diff is very large: the last files are left out.", style = MaterialTheme.typography.bodySmall, color = c.notice, modifier = Modifier.padding(horizontal = Space.card))
                set.files.forEach { fd ->
                    RowDivider(inset = 52.dp)
                    DiffFileRow(fd) { onDiff(spot, fd.path) }
                }
                RowDivider()
                QuietButton(onClick = { onCode(spot) }, color = c.accent, modifier = Modifier.padding(horizontal = Space.xs)) {
                    Icon(HerdrIcons.FolderGit2, null, modifier = Modifier.size(16.dp))
                    Text("Open its changes and files in Code")
                }
            }
        }
    }
}

/** The agent's closing note: its own words on what it did; a long one opens in full on a tap. */
@Composable
private fun NotePart(note: String, loading: Boolean) {
    if (note.isEmpty() && !loading) return
    val c = Herdr.colors
    var full by remember(note) { mutableStateOf(false) }
    val long = note.length > 420 || note.lines().size > 8
    Part("Closing note", HerdrIcons.NotebookPen) {
        if (note.isEmpty()) Text("Reading the agent's note", style = MaterialTheme.typography.bodySmall, color = c.muted)
        else {
            Box(if (long && !full) Modifier.heightIn(max = 168.dp).clipToBounds() else Modifier) {
                MarkdownText(note, style = MaterialTheme.typography.bodyMedium)
            }
            if (long) QuietButton(onClick = { full = !full }, color = c.accent, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(if (full) "Show less" else "Read all of it")
            }
        }
    }
}

/** The newest review: its round and verdict, its findings counted by severity, the most severe first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewPart(d: TaskDetails?) {
    val c = Herdr.colors
    val r = d?.reviews?.lastOrNull()
    var all by remember(r) { mutableStateOf(false) }
    val passed = r != null && r.pass == true && r.error.isEmpty()
    QuietCard(padded = true, modifier = Modifier.animateContentSize()) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            PartHead(
                if (r == null) "Review" else "Review · ${Approvals.verdict(r)}",
                if (passed) HerdrIcons.CircleCheck else if (r == null) HerdrIcons.ListChecks else HerdrIcons.CircleAlert,
                tint = if (passed) c.done else if (r == null) c.muted else c.red,
            )
            when {
                d == null -> Text("Reading the review", style = MaterialTheme.typography.bodySmall, color = c.muted)
                r == null -> Text("No review on record.", style = MaterialTheme.typography.bodySmall, color = c.muted)
                else -> {
                    Text(
                        listOfNotNull("Round ${r.round}", r.by.substringAfterLast('/').ifEmpty { null }, "${d.reviews.size} review${if (d.reviews.size == 1) "" else "s"} in all".takeIf { d.reviews.size > 1 })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = c.muted,
                    )
                    val sev = Approvals.severities(r)
                    if (sev.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        sev.forEach { (s, k) -> Pill("$k $s", color = severityColor(s)) }
                    } else if (r.error.isEmpty()) Text("No findings.", style = MaterialTheme.typography.bodySmall, color = c.muted)
                    if (r.error.isNotEmpty()) MarkdownText(r.error, style = MaterialTheme.typography.bodyMedium, color = c.red)
                    if (r.text.isNotEmpty()) MarkdownText(r.text, style = MaterialTheme.typography.bodyMedium)
                    val findings = r.findings.sortedBy { Approvals.severityRank(it.severity) }
                    (if (all) findings else findings.take(3)).forEach { FindingView(it) }
                    if (findings.size > 3) QuietButton(onClick = { all = !all }, color = c.accent, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text(if (all) "Show fewer" else "Show all ${findings.size} findings")
                    }
                }
            }
        }
    }
}

/** The local check (the board's, on its machine) and the pull request's checks on GitHub, each open on a tap. */
@Composable
private fun ChecksPart(f: WorkFacts?, problem: String?, loading: Boolean) {
    val c = Herdr.colors
    val context = LocalContext.current
    Part("Checks", HerdrIcons.ShieldCheck) {
        when {
            loading -> Text("Reading the checks", style = MaterialTheme.typography.bodySmall, color = c.muted)
            f == null -> Text(problem ?: "Couldn't read the checks", style = MaterialTheme.typography.bodySmall, color = c.red)
            else -> {
                CheckLine(
                    "Local check", f.local.state,
                    when (f.local.state) {
                        CheckState.Passed -> "Passed" + f.local.words.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
                        CheckState.None -> "None on record"
                        else -> f.local.words
                    },
                )
                val runs = f.pr.runs
                CheckLine(
                    "GitHub", f.pr.state,
                    if (runs.isEmpty()) f.pr.words
                    else listOf(CheckState.Failed to "failed", CheckState.Running to "running", CheckState.Passed to "passed")
                        .mapNotNull { (s, w) -> runs.count { it.state == s }.takeIf { it > 0 }?.let { "$it $w" } }.joinToString(", "),
                )
                runs.forEach { r -> RunRow(r) { if (r.link.isNotEmpty()) context.openUrl(r.link) } }
            }
        }
    }
}

@Composable
private fun CheckLine(label: String, state: CheckState, words: String) {
    val (icon, tint) = state.look()
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Herdr.colors.strong, modifier = Modifier.padding(start = Space.s).width(92.dp))
        Text(words, style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RunRow(r: CheckRun, onClick: () -> Unit) {
    val (icon, tint) = r.state.look()
    val c = Herdr.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(enabled = r.link.isNotEmpty(), onClickLabel = "Open ${r.name} on GitHub", onClick = onClick)
            .padding(start = Space.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Text(
            buildAnnotatedString {
                append(r.name)
                if (r.workflow.isNotEmpty()) withStyle(SpanStyle(color = c.muted, fontSize = MaterialTheme.typography.bodySmall.fontSize)) { append(" · ${r.workflow}") }
            },
            style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Space.s).weight(1f),
        )
        if (r.link.isNotEmpty()) Icon(HerdrIcons.ExternalLink, null, tint = c.faint, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun CheckState.look(): Pair<ImageVector, Color> {
    val c = Herdr.colors
    return when (this) {
        CheckState.Passed -> HerdrIcons.CircleCheck to c.done
        CheckState.Failed -> HerdrIcons.CircleX to c.red
        CheckState.Running -> HerdrIcons.Hourglass to c.working
        CheckState.None -> HerdrIcons.Circle to c.muted
    }
}

/**
 * The card's actions, in thumb reach: Approve (merge), or Approve anyway when something failed,
 * Approve plan for a plan; then Send back, Stop and Next.
 */
@Composable
private fun CardActions(
    item: Approvals.Item,
    details: TaskDetails?,
    facts: WorkFacts?,
    busy: String?,
    onApprove: (BoardAction) -> Unit,
    onAnyway: (List<String>) -> Unit,
    onSendBack: () -> Unit,
    onStop: () -> Unit,
    onNext: (() -> Unit)?,
) {
    val c = Herdr.colors
    val t = item.task
    val failed = Approvals.failed(t, details, facts)
    val free = busy == null
    Column(
        Modifier.fillMaxWidth().background(c.panel).navigationBarsPadding().padding(horizontal = Space.screen, vertical = Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        when {
            t.status == BoardStatus.PlanReady -> PrimaryButton("Approve plan", Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = free) { onApprove(BoardAction.ApprovePlan) }
            t.status == BoardStatus.PickOne -> Text(
                "Pick one of the attempts on the card to keep it.", style = MaterialTheme.typography.bodyMedium, color = c.muted,
                modifier = Modifier.fillMaxWidth().padding(vertical = Space.s),
            )
            failed.isNotEmpty() -> DangerButton("Approve anyway…", Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = free) { onAnyway(failed) }
            else -> PrimaryButton(onClick = { onApprove(BoardAction.Approve) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = free) {
                Icon(HerdrIcons.GitMerge, null, modifier = Modifier.size(16.dp))
                Text("Approve and merge")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            SecondaryButton(onClick = onSendBack, modifier = Modifier.weight(1f), enabled = free) {
                Icon(HerdrIcons.MessageSquare, null, modifier = Modifier.size(16.dp))
                Text("Send back", maxLines = 1)
            }
            SecondaryButton(onClick = onStop, modifier = Modifier.weight(1f), enabled = free) {
                Icon(HerdrIcons.CircleStop, null, modifier = Modifier.size(16.dp))
                Text("Stop", maxLines = 1)
            }
            if (onNext != null) SecondaryButton(onClick = onNext, modifier = Modifier.weight(1f)) {
                Text("Next", maxLines = 1)
                Icon(HerdrIcons.ChevronRight, null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Send back: words for the task's agent (`tell`); a failed review's findings can go instead (`review`). */
@Composable
private fun SendBackPanel(t: BoardTask, onDismiss: () -> Unit, onSend: (String) -> Unit, onFindings: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val plan = t.status == BoardStatus.PlanReady
    BoardPanel(
        if (plan) "Send the plan back" else "Send ${t.name} back", onDismiss,
        buttons = {
            QuietButton(onClick = onDismiss) { Text("Cancel") }
            PrimaryButton("Send", enabled = text.isNotBlank()) { onSend(text.trim()) }
        },
    ) {
        Text(
            if (plan) "Your words go to its agent, which writes the plan again." else "Your words go to its agent, which carries on with the work. It comes back here when it is done again.",
            style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted,
        )
        HerdrField(
            text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3,
            placeholder = { Text(if (plan) "What should change in the plan?" else "What should change?") },
        )
        if (t.status == BoardStatus.ReviewFailed) QuietButton(onClick = onFindings, color = Herdr.colors.accent) {
            Icon(HerdrIcons.ListChecks, null, modifier = Modifier.size(16.dp))
            Text("Send the review's findings instead")
        }
    }
}

/** Approve anyway: says what failed, and merges only once that is ticked as read. */
@Composable
private fun AnywayPanel(t: BoardTask, failed: List<String>, onDismiss: () -> Unit, onApprove: () -> Unit) {
    var sure by remember { mutableStateOf(false) }
    val c = Herdr.colors
    BoardPanel(
        "Approve ${t.name} anyway?", onDismiss, accent = c.red,
        buttons = {
            QuietButton(onClick = onDismiss) { Text("Cancel") }
            DangerButton("Approve anyway", enabled = sure, onClick = onApprove)
        },
    ) {
        Text("This merges the work as it is, although:", style = MaterialTheme.typography.bodyMedium, color = c.muted)
        failed.forEach { f ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(HerdrIcons.CircleAlert, null, tint = c.red, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                Text(f, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), modifier = Modifier.padding(start = Space.s))
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "I know") { sure = !sure }.semantics { contentDescription = "I know what failed, merge it anyway" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(sure, { sure = it })
            Text("I know what failed, merge it anyway", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The board's way into the review cards, above its tasks while finished work waits for a yes. */
@Composable
fun ReviewEntryCard(count: Int, onOpen: () -> Unit) {
    val c = Herdr.colors
    QuietCard(
        Modifier.semantics(mergeDescendants = true) {},
        onClick = onOpen, onClickLabel = "Review them one card at a time", padded = true,
        color = c.accentTint.compositeOver(c.card), edge = c.accent.copy(alpha = 0.35f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(HerdrIcons.Layers, null, tint = c.accent, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = Space.m)) {
                Text("Review finished work", style = MaterialTheme.typography.titleMedium, color = c.strong)
                Text(
                    (if (count == 1) "1 task waits" else "$count tasks wait") + " for your yes · a card each, swipe through",
                    style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(HerdrIcons.ChevronRight, null, tint = c.muted, modifier = Modifier.size(20.dp))
        }
    }
}
