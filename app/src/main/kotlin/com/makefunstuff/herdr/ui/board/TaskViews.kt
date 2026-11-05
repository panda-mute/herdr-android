package com.makefunstuff.herdr.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.SecondaryButton
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

/** A task view's tabs, as 3.1's: its chat, then its folder's Files and Changes, then its Pull request. */
internal enum class TaskView(val label: String) { Chat("Chat"), Files("Files"), Changes("Changes"), PullRequest("Pull request") }

/** The tabs [t] has now: Pull request only once it has one, as 3.1's (`itemsFor`). */
internal fun taskViews(t: WorkTask): List<TaskView> = TaskView.entries.filter { it != TaskView.PullRequest || t.pr.isNotEmpty() }

/** The task view's tab strip, under its bar. */
@Composable
internal fun TaskViewTabs(t: WorkTask, view: TaskView, onView: (TaskView) -> Unit) {
    val views = taskViews(t)
    ViewTabs(views.map { it.label }, views.indexOf(view).coerceAtLeast(0), onSelect = { onView(views[it]) })
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
 * [t]'s pull request as GitHub says it, asked through Shepherdr on its machine while the tab shows (then every
 * 30 s, as 3.1's): its state and number, the size of the change, its title and branches, Open on GitHub and Copy
 * link, then its checks, what needs a look first; each opens on GitHub.
 */
@Composable
internal fun TaskPullRequest(session: HostSession, t: WorkTask) {
    val owner = LocalLifecycleOwner.current
    var answer by remember(t.id) { mutableStateOf<V2PrAnswer?>(null) }
    var problem by remember(t.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(t.id) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    answer = session.awaitWorkBoard().pullRequest(t)
                    problem = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    problem = e.message ?: "GitHub didn't answer."
                }
                delay(30_000)
            }
        }
    }
    val a = answer
    val pr = a?.pr
    when {
        pr != null -> PullRequestBody(pr)
        a == null && problem == null -> Box(Modifier.fillMaxSize()) { LoadingState(Worktree.ASKING_GITHUB, Modifier.align(Alignment.Center)) }
        else -> {
            val error = problem ?: a?.error
            if (error != null) TaskViewNote(HerdrIcons.GitPullRequest, "GitHub didn't answer", error, error = true)
            else TaskViewNote(HerdrIcons.GitPullRequest, "No pull request", a?.why ?: Worktree.NO_PR)
        }
    }
}

/** The pull request's page, from data alone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PullRequestBody(pr: V2PullRequest) {
    val context = LocalContext.current
    val c = Herdr.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).padding(top = Space.m, bottom = Space.xxl),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        // Its state and number, the size of the change.
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            SecondaryButton(onClick = { context.openUrl(pr.url) }) {
                Icon(HerdrIcons.ExternalLink, null, Modifier.size(16.dp))
                Text(Worktree.OPEN_ON_GITHUB)
            }
            QuietButton(onClick = { context.copyToClipboard("Pull request", pr.url, "Link copied") }) {
                Icon(HerdrIcons.Copy, null, Modifier.size(16.dp))
                Text(Worktree.COPY_LINK)
            }
        }
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
private fun TaskViewNote(icon: ImageVector, title: String, text: String, error: Boolean = false, onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        StateBlock(
            icon, title, text, iconTint = if (error) Herdr.colors.red else null,
            action = onRetry?.let { r -> { QuietButton(onClick = r) { Text("Try again") } } },
        )
    }
}
