package com.makefunstuff.herdr.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.core.github.PrRef
import com.makefunstuff.herdr.core.github.PrReview
import com.makefunstuff.herdr.core.shepherdr.V2PrAnswer
import com.makefunstuff.herdr.core.shepherdr.V2PrCheck
import com.makefunstuff.herdr.core.shepherdr.V2PullRequest
import com.makefunstuff.herdr.core.shepherdr.Worktree
import com.makefunstuff.herdr.ui.code.ChangesTab
import com.makefunstuff.herdr.ui.code.CodeSpot
import com.makefunstuff.herdr.ui.code.FilesTab
import com.makefunstuff.herdr.ui.code.Load
import com.makefunstuff.herdr.ui.code.code
import com.makefunstuff.herdr.ui.code.infoKey
import com.makefunstuff.herdr.ui.code.rememberLoad
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.copyToClipboard
import com.makefunstuff.herdr.ui.common.openUrl
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.design.StateBlock
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.design.ViewTabs
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A task view's tabs, as 3.1's: its chat, then its folder's Files and Changes, its Pull request, its Browser. */
internal enum class TaskView(val label: String) { Chat("Chat"), Files("Files"), Changes("Changes"), PullRequest("Pull request"), Browser("Browser") }

/**
 * The tabs [t] has now, as 3.1's (`itemsFor`): Pull request only once it has one, Browser where its machine's service
 * has one (the Browser add-on).
 */
internal fun taskViews(t: WorkTask): List<TaskView> = TaskView.entries.filter {
    when (it) {
        TaskView.PullRequest -> t.pr.isNotEmpty()
        TaskView.Browser -> t.browser != null
        else -> true
    }
}

/**
 * The task view's tab strip, under its bar: Chat, its agent's Terminal (as 3.1's task view has it),
 * then Files, Changes and its Pull request. Terminal opens the terminal full screen; the tab that is
 * shown stays Chat, which is where Back returns.
 */
@Composable
internal fun TaskViewTabs(t: WorkTask, view: TaskView, onView: (TaskView) -> Unit, onTerminal: (() -> Unit)? = null) {
    val views = taskViews(t)
    val labels = buildList {
        add(TaskView.Chat.label)
        if (onTerminal != null) add("Terminal")
        views.drop(1).forEach { add(it.label) }
    }
    val offset = if (onTerminal != null) 1 else 0
    val selected = if (view == TaskView.Chat) 0 else offset + views.indexOf(view)
    ViewTabs(labels, selected, onSelect = { i ->
        when {
            i == 0 -> onView(TaskView.Chat)
            onTerminal != null && i == 1 -> onTerminal()
            else -> onView(views[i - offset])
        }
    })
}

/**
 * [t]'s Files or Changes: the folder it works in (its worktree, as Shepherdr names it), read over SSH as the code
 * screens read any checkout. A file or a change opens full screen, where chosen lines can be sent to its agent.
 * Each time the tab shows, it reads again; what it read last stays on screen meanwhile.
 */
@Composable
internal fun TaskCode(nav: NavController, session: HostSession, machineId: String, t: WorkTask, view: TaskView) {
    val container = LocalContainer.current
    var again by remember(t.id) { mutableIntStateOf(0) }
    LaunchedEffect(t.id, view) { again++ }
    val folder = rememberLoad("taskdir:$machineId:${t.id}", container.code.cache, again) { session.awaitWorkBoard().folder(t) }
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    when (folder) {
        Load.Loading -> Box(Modifier.fillMaxSize()) { LoadingState("Finding its folder", Modifier.align(Alignment.Center)) }
        is Load.Failed -> TaskViewNote(HerdrIcons.Folder, "No folder", folder.message, onRetry = { again++ })
        is Load.Ready -> {
            val f = folder.value
            // Its folder on another of Shepherdr's machines: read there, when the phone has it too.
            val there = if (f.machine == null) machineId else hosts.firstOrNull { it.named(f.machine!!) }?.machine?.id
            val code = there?.let { container.hosts.session(it) }
            if (there == null || code == null) {
                TaskViewNote(HerdrIcons.Server, "On another machine", "Its folder is on ${f.machine}, which this phone doesn't connect to.")
                return
            }
            val start = CodeSpot(there, f.folder, task = t.id, taskName = t.name, board = machineId.takeIf { it != there })
            val info = rememberLoad(infoKey(start), container.code.cache, again) {
                code.code().info(start.dir).also { i -> container.code.cache.put(infoKey(start.copy(dir = i.top)), i) }
            }
            when (info) {
                Load.Loading -> Box(Modifier.fillMaxSize()) { LoadingState("Opening its folder", Modifier.align(Alignment.Center)) }
                is Load.Failed -> TaskViewNote(HerdrIcons.Folder, "Can't open it", info.message, onRetry = { again++ })
                is Load.Ready -> {
                    val i = info.value
                    val spot = start.copy(dir = i.top)
                    Column(Modifier.fillMaxSize().padding(top = Space.s)) {
                        when {
                            view == TaskView.Files -> FilesTab(nav, code, spot, container.code, again)
                            !i.git -> TaskViewNote(HerdrIcons.GitCompare, "Not in Git", "This task's folder isn't a git repository.")
                            else -> ChangesTab(nav, code, spot, i, container.code, again)
                        }
                    }
                }
            }
        }
    }
}

/** Whether Shepherdr's name for a machine ("devbox") is this one: its label, or its host's first name. */
private fun HostState.named(name: String): Boolean =
    machine.label.equals(name, ignoreCase = true) || machine.host.equals(name, ignoreCase = true) ||
        machine.host.substringBefore('.').equals(name, ignoreCase = true)

/**
 * [t]'s pull request, to review from the phone: read whole with gh on its machine (the person's own login
 * there) while the tab shows, then every 30 s, as the desktop's PR panel reads it. Approve, Request changes,
 * Comment and a reply in a thread open [ReviewSheet]; Resolve acts at once. Where gh can't read it (not
 * installed, not signed in), the board's own answer shows its state and checks, and says why there's no review.
 */
@Composable
internal fun TaskPullRequest(session: HostSession, t: WorkTask) {
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ref = remember(t.pr) { PrRef.parse(t.pr) }
    val client = remember(session) { session.github() }
    var review by remember(t.id, ref) { mutableStateOf<PrReview?>(null) }
    var why by remember(t.id, ref) { mutableStateOf<String?>(null) }
    // The threads being resolved or reopened now: their button waits for GitHub.
    var resolving by remember(t.id) { mutableStateOf(setOf<String>()) }
    var answer by remember(t.id) { mutableStateOf<V2PrAnswer?>(null) }
    var problem by remember(t.id) { mutableStateOf<String?>(null) }
    var write by remember(t.id) { mutableStateOf<ReviewWrite?>(null) }
    var again by remember(t.id) { mutableIntStateOf(0) }
    LaunchedEffect(t.id, ref, again) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (ref != null) {
                    try {
                        review = client.load(ref)
                        why = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        why = e.message ?: "GitHub didn't answer."
                    }
                } else why = "Its link isn't a GitHub pull request: ${t.pr}"
                if (review == null) {
                    try {
                        answer = session.awaitWorkBoard().pullRequest(t)
                        problem = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        problem = e.message ?: "GitHub didn't answer."
                    }
                }
                delay(30_000)
            }
        }
    }
    val r = review
    val pr = answer?.pr
    when {
        r != null -> ReviewBody(
            r, onWrite = { write = it },
            onResolve = { th ->
                if (th.id !in resolving) {
                    resolving = resolving + th.id
                    scope.launch {
                        try {
                            client.resolve(th.id, !th.resolved)
                            context.toast(if (th.resolved) "Thread reopened" else "Thread resolved")
                            again++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            context.toast(e.message ?: "GitHub didn't take it")
                        } finally {
                            resolving = resolving - th.id
                        }
                    }
                }
            },
            resolving = resolving,
            // Read before, and the latest read failed: what shows may be old.
            note = why?.let { "Couldn't read it again: $it" },
        )
        pr != null -> PullRequestBody(pr, why?.let { "Can't review it from the phone: $it" })
        why == null && answer == null && problem == null -> Box(Modifier.fillMaxSize()) { LoadingState(Worktree.ASKING_GITHUB, Modifier.align(Alignment.Center)) }
        else -> {
            val error = why ?: problem ?: answer?.error
            if (error != null && answer?.why == null) TaskViewNote(HerdrIcons.GitPullRequest, "GitHub didn't answer", error, error = true, onRetry = { again++ })
            else TaskViewNote(HerdrIcons.GitPullRequest, "No pull request", answer?.why ?: Worktree.NO_PR)
        }
    }
    val w = write
    if (r != null && w != null) ReviewSheet(r, w, client, scope, onDismiss = { write = null }, onDone = { write = null; again++ })
}

/** The pull request's page from the board's answer alone: its head, links and checks, and [note] (why there's no review). */
@Composable
internal fun PullRequestBody(pr: V2PullRequest, note: String? = null) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).padding(top = Space.m, bottom = Space.xxl),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        PrHead(pr)
        PrLinks(pr)
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted) }
        PrChecks(pr)
    }
}

/** Its state and number, the size of the change, its title and branches. */
@Composable
internal fun PrHead(pr: V2PullRequest) {
    val c = Herdr.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusBadge(Worktree.state(pr.state), prColor(pr.state))
        Text("#${pr.number}", style = MaterialTheme.typography.labelLarge, color = c.muted, modifier = Modifier.padding(start = Space.s))
        Spacer(Modifier.weight(1f))
        pr.files?.let { Text(Worktree.filesCount(it), style = MaterialTheme.typography.labelMedium, color = c.muted) }
        if (pr.add > 0) Text("+%,d".format(pr.add), style = MaterialTheme.typography.labelMedium, color = c.done, modifier = Modifier.padding(start = Space.s))
        if (pr.del > 0) Text("−%,d".format(pr.del), style = MaterialTheme.typography.labelMedium, color = c.red, modifier = Modifier.padding(start = Space.s))
    }
    Text(pr.title, style = MaterialTheme.typography.titleMedium, color = c.strong)
    pr.head?.let { head ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(HerdrIcons.GitBranch, null, tint = c.muted, modifier = Modifier.size(14.dp))
            Text(
                "$head → ${pr.base.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = Space.xs),
            )
        }
    }
}

/** Open on GitHub and Copy link. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrLinks(pr: V2PullRequest) {
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        QuietButton(onClick = { context.openUrl(pr.url) }) {
            Icon(HerdrIcons.ExternalLink, null, Modifier.size(16.dp))
            Text(Worktree.OPEN_ON_GITHUB)
        }
        QuietButton(onClick = { context.copyToClipboard("Pull request", pr.url, "Link copied") }) {
            Icon(HerdrIcons.Copy, null, Modifier.size(16.dp))
            Text(Worktree.COPY_LINK)
        }
    }
}

/** Its checks, what needs a look first; each opens on GitHub. */
@Composable
internal fun PrChecks(pr: V2PullRequest) {
    val context = LocalContext.current
    val c = Herdr.colors
    SectionHeading(
        Worktree.CHECKS, top = Space.s,
        trailing = if (pr.checks.isNotEmpty()) ({ Text(Worktree.checksLine(pr.checks), style = MaterialTheme.typography.labelMedium, color = c.muted) }) else null,
    )
    if (pr.checks.isEmpty()) {
        Text(pr.checksError ?: Worktree.NO_CHECKS, style = MaterialTheme.typography.bodyMedium, color = if (pr.checksError != null) c.red else c.muted)
    } else QuietCard {
        pr.checks.forEachIndexed { i, ch ->
            if (i > 0) RowDivider(inset = 52.dp)
            CheckRow(ch) { ch.url?.let(context::openUrl) }
        }
    }
}

@Composable
private fun CheckRow(ch: V2PrCheck, onOpen: () -> Unit) {
    val c = Herdr.colors
    val (icon, color) = checkLook(ch.state)
    ListRow(
        title = ch.name,
        subtitle = listOfNotNull(Worktree.check(ch.state), ch.workflow).joinToString(" · "),
        leading = { Icon(icon, null, tint = color, modifier = Modifier.size(20.dp)) },
        trailing = if (ch.url != null) ({ Icon(HerdrIcons.ExternalLink, null, tint = c.muted, modifier = Modifier.size(16.dp)) }) else null,
        onClickLabel = if (ch.url != null) "Open on GitHub" else null,
        onClick = if (ch.url != null) onOpen else null,
    )
}

/** A check's mark: passed green, failed red, running amber (work), skipped dim. */
@Composable
private fun checkLook(state: String): Pair<ImageVector, Color> = Herdr.colors.let { c ->
    when (state) {
        "pass" -> HerdrIcons.CircleCheck to c.done
        "fail" -> HerdrIcons.CircleX to c.red
        "skip" -> HerdrIcons.Minus to c.muted
        else -> HerdrIcons.Clock to c.working
    }
}

/** A pull request's state in its colour: open green, merged teal (as a Done task), draft and closed dim. */
@Composable
private fun prColor(state: String): Color = Herdr.colors.let { c ->
    when (state) {
        "open" -> c.done
        "merged" -> c.teal
        else -> c.idle
    }
}

/** What a task view says when it has nothing to show, and why; with Retry where reading again may help. */
@Composable
internal fun TaskViewNote(icon: ImageVector, title: String, text: String, error: Boolean = false, onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        StateBlock(
            icon, title, text, iconTint = if (error) Herdr.colors.red else null,
            action = onRetry?.let { r -> { QuietButton(onClick = r) { Text("Try again") } } },
        )
    }
}
