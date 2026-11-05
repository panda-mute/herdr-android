package com.makefunstuff.herdr.ui.start

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.LocalPanes
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.start.AgentLaunch
import com.makefunstuff.herdr.core.start.Folder
import com.makefunstuff.herdr.core.start.StartWork
import com.makefunstuff.herdr.ui.board.BoardPanel
import com.makefunstuff.herdr.ui.board.PanelCancel
import com.makefunstuff.herdr.ui.board.rememberWorkBoards
import com.makefunstuff.herdr.ui.board.ADD_THROUGH_COORDINATOR
import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.FieldLabel
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.SegmentedPills
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * Start work from a sentence: what you want first, then how (a board task, which a space's Assistant
 * makes on Shepherdr; or an agent now) and where (the machine, then a
 * recent folder or one found with the folder browser). [machineId] and [text] start it there. The
 * agent is started as herdr starts it: no mode that skips its permission prompts is offered here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StartWorkScreen(nav: NavController, machineId: String? = null, text: String = "") {
    val container = LocalContainer.current
    val panes = LocalPanes.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val starts by container.starts.state.collectAsStateWithLifecycle()
    var words by rememberSaveable { mutableStateOf(text) }
    var board by rememberSaveable { mutableStateOf(starts.board) }
    var chosen by rememberSaveable { mutableStateOf(machineId ?: starts.machine) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var browsing by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // Where each way can run: a board task on a machine with a board, an agent on any machine online.
    val online = hosts.filter { it.online && it.machine.enabled }
    val able = online.filter { if (board) it.onShepherdr else it.classic } // an agent starts in classic herdr
    val host = able.firstOrNull { it.machine.id == chosen } ?: able.firstOrNull()
    val session = host?.let { container.hosts.session(it.machine.id) }
    val mid = host?.machine?.id
    // Shepherdr 3 makes a task through a space's coordinator: the spaces take the repos' place.
    val v2 = board && host?.onShepherdr == true
    val works = rememberWorkBoards()
    val spaces by produceState(works[mid ?: ""]?.spaces, mid, v2) {
        if (!v2 || session == null) return@produceState
        if (value == null) value = runCatching { session.awaitWorkBoard().overview().spaces }.getOrNull()
    }
    var spaceId by rememberSaveable { mutableStateOf<String?>(null) }
    val space = spaces?.let { l -> l.firstOrNull { it.id == spaceId } ?: l.firstOrNull { !it.home } ?: l.firstOrNull() }
    // The folders used and the workspaces'.
    val places = remember(host?.snapshot, starts, mid) { mid?.let { StartWork.recentFolders(host.snapshot, container.starts.folders(it)) }.orEmpty() }
    val place = folder?.takeIf { it.isNotEmpty() } ?: places.firstOrNull()
    // The agents installed there, as herdr's integrations and the machine's programs say; the usual list when it can't say.
    val kinds by produceState<List<String>?>(null, mid, board) {
        value = null
        if (session == null || board) return@produceState
        value = StartWork.installedOn(session)
    }
    val agent = kind?.takeIf { kinds?.contains(it) == true } ?: mid?.let { starts.kinds[it] }?.takeIf { kinds?.contains(it) == true } ?: kinds?.firstOrNull()

    fun askCoordinator(s: HostSession, m: String, said: String, to: WorkSpace) {
        val (title, desc) = StartWork.task(said)
        busy = true
        scope.launch {
            runCatching { s.awaitWorkBoard().addTask(to.id, title, desc) }
                .onSuccess {
                    container.starts.started(m, null, board = true, startNow = starts.startNow)
                    context.toast(it)
                    nav.popBackStack()
                    panes.openBoard(nav, m)
                }
                .onFailure { context.toast(it.message ?: "Shepherdr refused") }
            busy = false
        }
    }

    fun startAgent(s: HostSession, m: String, said: String, k: String, dir: String) {
        val snapshot = host?.snapshot
        busy = true
        scope.launch {
            runCatching {
                val taken = snapshot?.agents?.mapNotNull { it.name }.orEmpty()
                AgentLaunch.inFolder(s.awaitApi(10_000), snapshot, dir, k, StartWork.agentName(said, k, taken), said).also { s.refresh() }
            }
                .onSuccess { pane ->
                    container.starts.started(m, dir, board = false, startNow = starts.startNow, kind = k)
                    nav.popBackStack()
                    panes.open(nav, Routes.agent(m, pane))
                }
                .onFailure { context.toast(it.message ?: "Couldn't start $k") }
            busy = false
        }
    }

    fun go() {
        val s = session ?: return
        val m = mid ?: return
        if (v2) askCoordinator(s, m, words.trim(), space ?: return)
        else if (!board) startAgent(s, m, words.trim(), agent ?: return, place ?: return)
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (text.isEmpty()) runCatching { focus.requestFocus() } }
    val c = Herdr.colors
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = c.ground,
        topBar = { HerdrTopBar("Start work", subtitle = "Say what you want, then where it runs", onBack = { nav.popBackStack() }) },
    ) { padding ->
        if (online.isEmpty()) {
            EmptyState(
                if (hosts.isEmpty()) "No machines yet" else "No machine online",
                if (hosts.isEmpty()) "Add a machine that runs herdr, and work can start there from here." else "Work starts once one of your machines answers.",
                icon = HerdrIcons.Monitor,
            )
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.screen, vertical = Space.m),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                HerdrField(
                    words, { words = it }, modifier = Modifier.fillMaxWidth().focusRequester(focus), minLines = 3,
                    placeholder = { Text("What should be done? “Add a last-answered line to each machine card, with a test”") },
                )
                FieldLabel("How")
                SegmentedPills(listOf("Board task", "Agent now"), if (board) 0 else 1, { board = it == 0; folder = null })
                Text(
                    if (board) ADD_THROUGH_COORDINATOR
                    else "Starts an agent in the folder now and gives it what you said. It asks before it acts, as it does at the desk.",
                    style = MaterialTheme.typography.bodySmall, color = c.muted,
                )
                FieldLabel("Where")
                if (able.isEmpty()) Notice("None of the machines online has a work board. An agent now can start on any of them.")
                else FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    able.forEach { h -> HerdrChip(h.machine.label, selected = h.machine.id == mid, onClick = { chosen = h.machine.id; folder = null; kind = null }) }
                }
                if (host != null && v2) {
                    Text("Space", style = MaterialTheme.typography.bodyMedium, color = c.muted)
                    val l = spaces
                    if (l == null) LoadingState("Reading ${host.machine.label}'s spaces")
                    else FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        l.forEach { sp ->
                            HerdrChip(
                                selected = sp.id == space?.id, onClick = { spaceId = sp.id },
                                label = { Text(sp.name, maxLines = 1) },
                                leadingIcon = { Icon(HerdrIcons.MessagesSquare, null, Modifier.size(16.dp)) },
                            )
                        }
                    }
                } else if (host != null && !board) {
                    Text("Recent folders", style = MaterialTheme.typography.bodyMedium, color = c.muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        places.forEach { p ->
                            HerdrChip(
                                selected = p == place, onClick = { folder = p },
                                label = { Text(StartWork.folderName(p), maxLines = 1) },
                                leadingIcon = { Icon(HerdrIcons.FolderGit2, null, Modifier.size(16.dp)) },
                            )
                        }
                        HerdrChip(
                            selected = false, onClick = { browsing = true },
                            label = { Text("Browse…") },
                            leadingIcon = { Icon(HerdrIcons.FolderOpen, null, Modifier.size(16.dp)) },
                        )
                    }
                    place?.takeIf { it.startsWith("/") || it.startsWith("~") }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1)
                    }
                    FieldLabel("Agent")
                    val list = kinds
                    if (list == null) LoadingState("Asking ${host.machine.label} which agents it has")
                    else FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        list.forEach { k -> HerdrChip(k, selected = k == agent, onClick = { kind = k }) }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.m).navigationBarsPadding()) {
                val ready = !busy && words.isNotBlank() && host != null && (if (board) v2 && space != null else place != null && agent != null)
                PrimaryButton(
                    when {
                        busy && v2 -> "Sending…"
                        v2 -> "Send to the Assistant"
                        busy -> "Starting…"
                        board -> "Send to the Assistant"
                        else -> "Start ${agent ?: "the agent"}"
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = ready, onClick = ::go,
                )
            }
        }
    }
    // The browser opens beside the folder chosen (in the folder it is in), else at home.
    val near = place?.takeIf { it.startsWith("/") || it.startsWith("~") }?.let { Folder(it.trimEnd('/'), emptyList()).parent }
    if (browsing && session != null) FolderBrowser(session, start = near, onDismiss = { browsing = false }) {
        browsing = false
        folder = it
    }
}

/** The machine's folders, one level at a time from [start] (the home folder when null); the chosen one goes to [onPick]. */
@Composable
private fun FolderBrowser(session: HostSession, start: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var at by rememberSaveable { mutableStateOf(start) }
    val listing by produceState<Result<Folder?>?>(null, at) {
        value = null
        value = runCatching { Folder.parse(session.awaitConnection(10_000).run(Folder.command(at), timeoutSeconds = 15).stdout) }
    }
    val f = listing?.getOrNull()
    BoardPanel(
        f?.path?.let(StartWork::folderName)?.let { "Folder: $it" } ?: "Choose a folder",
        onDismiss,
        buttons = {
            PanelCancel(onDismiss)
            PrimaryButton("Use this folder", enabled = f != null) { f?.let { onPick(it.path) } }
        },
    ) {
        val l = listing
        when {
            l == null -> LoadingState("Reading ${at ?: "the home folder"}")
            l.isFailure -> ErrorMessage(l.exceptionOrNull()?.message ?: "The machine didn't answer")
            f == null -> {
                ErrorMessage("Couldn't open ${at ?: "the home folder"}")
                if (at != null) QuietButton(onClick = { at = null }) { Text("Open the home folder") }
            }
            else -> {
                Text(f.path, style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted)
                QuietCard {
                    f.parent?.let { up ->
                        ListRow("Up", subtitle = up, leading = { Icon(HerdrIcons.ArrowUp, null, tint = Herdr.colors.muted) }, onClick = { at = up })
                        if (f.folders.isNotEmpty()) RowDivider()
                    }
                    if (f.folders.isEmpty()) Text(
                        "No folders in here", style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted,
                        modifier = Modifier.padding(Space.l),
                    )
                    f.folders.forEachIndexed { i, e ->
                        if (i > 0) RowDivider()
                        ListRow(
                            e.name,
                            subtitle = if (e.repo) "Git repository" else null,
                            leading = { Icon(if (e.repo) HerdrIcons.FolderGit2 else HerdrIcons.Folder, null, tint = if (e.repo) Herdr.colors.accent else Herdr.colors.muted) },
                            chevron = true,
                            onClick = { at = f.child(e.name) },
                        )
                    }
                }
            }
        }
    }
}

