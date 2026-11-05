package com.makefunstuff.herdr.ui.code

import com.makefunstuff.herdr.ui.change.ChangeScreenRow
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.code.ChangeSet
import com.makefunstuff.herdr.core.code.CheckoutInfo
import com.makefunstuff.herdr.core.code.Commit
import com.makefunstuff.herdr.core.code.DiffScope
import com.makefunstuff.herdr.core.code.FileTree
import com.makefunstuff.herdr.core.code.GitStatus
import com.makefunstuff.herdr.core.code.QuickOpen
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.data.CodeStore
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.copyToClipboard
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.ErrorState
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.design.SegmentedPills
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

/** The checkout screen's three views. */
enum class CheckoutTab(val label: String) { Files("Files"), Changes("Changes"), History("History") }

/**
 * A checkout on a machine: its files as folders with "Go to file"; its changes (Git's status and
 * the diff against its base or its last commit); its history. Search, the comments waiting and the
 * other checkouts are in the bar. [spot]'s folder can be anywhere in the checkout: it opens at the top.
 */
@Composable
fun CheckoutScreen(nav: NavController, spot: CodeSpot, tab: CheckoutTab) {
    val container = LocalContainer.current
    val session = container.hosts.session(spot.machine)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val host by session.state.collectAsStateWithLifecycle()
    var again by remember { mutableIntStateOf(0) }
    val info = rememberLoad(infoKey(spot), container.code.cache, again) {
        session.code().info(spot.dir).also { i ->
            // Known under its top folder too, for the screens opened from here.
            container.code.cache.put(infoKey(spot.copy(dir = i.top)), i)
        }
    }
    when (info) {
        Load.Loading -> Frame(nav, spot.dir.trimEnd('/').substringAfterLast('/'), host.machine.label) {
            LoadingState("Opening ${host.shortPath(spot.dir)}", Modifier.align(Alignment.Center))
        }
        is Load.Failed -> Frame(nav, spot.dir.trimEnd('/').substringAfterLast('/'), host.machine.label) {
            ErrorState("Couldn't open ${host.shortPath(spot.dir)}", info.message, onRetry = { again++ })
        }
        is Load.Ready -> Checkout(nav, session, host, spot.copy(dir = info.value.top), info.value, tab, onRefresh = {
            container.code.cache.drop("files:${spot.machine}:${info.value.top}")
            container.code.cache.drop("status:${spot.machine}:${info.value.top}")
            container.code.cache.drop("changes:${spot.machine}:${info.value.top}")
            container.code.cache.drop("log:${spot.machine}:${info.value.top}")
            container.code.cache.drop("file:${spot.machine}:${info.value.top}:")
            again++
        }, again = again)
    }
}

@Composable
private fun Frame(nav: NavController, title: String, subtitle: String, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Herdr.colors.ground,
        topBar = { HerdrTopBar(title, subtitle = subtitle, onBack = { nav.popBackStack() }) },
    ) { padding -> Box(Modifier.padding(padding).fillMaxSize(), content = content) }
}

@Composable
private fun Checkout(
    nav: NavController,
    session: HostSession,
    host: HostState,
    spot: CodeSpot,
    info: CheckoutInfo,
    startTab: CheckoutTab,
    onRefresh: () -> Unit,
    again: Int,
) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val notes by container.code.notes.collectAsStateWithLifecycle()
    val pending = notes.count { it.machineId == spot.machine && it.root == spot.dir }
    var tab by rememberSaveable { mutableStateOf(startTab) }
    var menu by remember { mutableStateOf(false) }
    val tabs = if (info.git) CheckoutTab.entries else listOf(CheckoutTab.Files)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar(
                info.name,
                subtitle = listOfNotNull(info.branch.ifEmpty { if (info.git) "detached" else "not in Git" }, host.machine.label).joinToString(" · "),
                onBack = { nav.popBackStack() },
            ) {
                BarIcon(HerdrIcons.Search, "Search in the code", { nav.navigate(Routes.codeSearch(spot)) })
                BarIcon(HerdrIcons.MessageSquare, "Comments", { nav.navigate(Routes.codeNotes(spot)) }, badge = pending)
                BarIcon(HerdrIcons.Ellipsis, "More", { menu = true })
                HerdrMenu(expanded = menu, onDismissRequest = { menu = false }, title = info.name, subtitle = host.shortPath(info.top)) {
                    MenuRow("Read it again", HerdrIcons.RefreshCw, { menu = false; onRefresh() })
                    MenuRow("Copy the folder's path", HerdrIcons.Copy, { menu = false; context.copyToClipboard("Folder", info.top) })
                    ChangeScreenRow { menu = false }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (tabs.size > 1) SegmentedPills(
                tabs.map { it.label }, tabs.indexOf(tab).coerceAtLeast(0), onSelect = { tab = tabs[it] },
                modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.s),
            )
            Box(Modifier.weight(1f)) {
                when (if (tab in tabs) tab else CheckoutTab.Files) {
                    CheckoutTab.Files -> FilesTab(nav, session, spot, container.code, again)
                    CheckoutTab.Changes -> ChangesTab(nav, session, spot, info, container.code, again)
                    CheckoutTab.History -> HistoryTab(nav, session, spot, info, container.code, again)
                }
            }
        }
    }
}

// ---- Files --------------------------------------------------------------------------------------

@Composable
internal fun FilesTab(nav: NavController, session: HostSession, spot: CodeSpot, store: CodeStore, again: Int) {
    val files = rememberLoad("files:${spot.machine}:${spot.dir}", store.cache, again) { FileTree(session.code().files(spot.dir)) }
    var folder by rememberSaveable(spot.dir) { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = folder.isNotEmpty() && query.isEmpty()) { folder = folder.substringBeforeLast('/', "") }

    Column(Modifier.fillMaxSize()) {
        HerdrField(
            query, { query = it }, singleLine = true,
            placeholder = { Text("Go to file") },
            leadingIcon = { Icon(HerdrIcons.Search, null, Modifier.size(20.dp)) },
            trailingIcon = if (query.isNotEmpty()) ({ BarIcon(HerdrIcons.X, "Clear", { query = "" }) }) else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            modifier = Modifier.padding(horizontal = Space.screen).padding(bottom = Space.xs),
        )
        when (files) {
            Load.Loading -> Box(Modifier.fillMaxSize()) { LoadingState("Listing the files", Modifier.align(Alignment.Center)) }
            is Load.Failed -> ErrorMessage(files.message, Modifier.padding(Space.screen), onRetry = null)
            is Load.Ready -> {
                val tree = files.value
                if (query.isNotBlank()) {
                    val found = remember(tree, query) { QuickOpen.find(tree.paths, query) }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xxl)) {
                        if (found.isEmpty()) item { EmptyHint("No file's name has \"${query.trim()}\" in it.") }
                        items(found, key = { it.path }) { m ->
                            FileRow(m.path, nameMatches = m.at) { nav.navigate(Routes.codeFile(spot, m.path)) }
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xxl)) {
                        item(key = "crumbs") { Crumbs(spot.dir.trimEnd('/').substringAfterLast('/'), folder) { folder = it } }
                        val list = tree.list(folder)
                        if (list.isEmpty()) item { EmptyHint(if (tree.size == 0) "No files here." else "This folder is empty.") }
                        items(list, key = { it.path }) { e ->
                            if (e.folder) {
                                EntryRow(HerdrIcons.Folder, e.name, "${e.count} file${if (e.count == 1) "" else "s"}", chevron = true) { folder = e.path }
                            } else {
                                FileRow(e.path, inFolder = true) { nav.navigate(Routes.codeFile(spot, e.path)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Where in the tree you are: the checkout, then each folder, every part a way back up. */
@Composable
private fun Crumbs(top: String, folder: String, onGo: (String) -> Unit) {
    val c = Herdr.colors
    val parts = if (folder.isEmpty()) emptyList() else folder.split('/')
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crumb(top, last = parts.isEmpty()) { onGo("") }
        parts.forEachIndexed { i, p ->
            Icon(HerdrIcons.ChevronRight, null, tint = c.muted, modifier = Modifier.size(16.dp))
            Crumb(p, last = i == parts.lastIndex) { onGo(parts.take(i + 1).joinToString("/")) }
        }
    }
}

@Composable
private fun Crumb(name: String, last: Boolean, onClick: () -> Unit) {
    val c = Herdr.colors
    Text(
        name, style = MaterialTheme.typography.labelLarge, color = if (last) c.strong else c.accent, maxLines = 1,
        modifier = Modifier.heightIn(min = 40.dp).clickable(enabled = !last, onClickLabel = "Go to $name", onClick = onClick)
            .padding(horizontal = Space.s, vertical = Space.m),
    )
}

/** A file in the tree, or found by name: its name, and its folder when it was found. */
@Composable
private fun FileRow(path: String, inFolder: Boolean = false, nameMatches: List<Int> = emptyList(), onClick: () -> Unit) {
    val c = Herdr.colors
    val nameStart = path.lastIndexOf('/') + 1
    val title = buildAnnotatedString {
        append(path.substring(nameStart))
        nameMatches.filter { it >= nameStart }.forEach { addStyle(SpanStyle(color = c.strong, fontWeight = FontWeight.Bold), it - nameStart, it - nameStart + 1) }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "Open the file", onClick = onClick)
            .padding(horizontal = Space.screen, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(fileIcon(path), null, tint = c.muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!inFolder && nameStart > 0) Text(
                path.substring(0, nameStart - 1), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EntryRow(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, meta: String?, chevron: Boolean, onClick: () -> Unit) {
    val c = Herdr.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "Open $name", onClick = onClick)
            .padding(horizontal = Space.screen, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.m))
        Text(name, style = MaterialTheme.typography.bodyLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (meta != null) Text(meta, style = MaterialTheme.typography.labelMedium, color = c.muted)
        if (chevron) Icon(HerdrIcons.ChevronRight, null, tint = c.muted, modifier = Modifier.padding(start = Space.s).size(18.dp))
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted, modifier = Modifier.padding(Space.screen))
}

// ---- Changes ------------------------------------------------------------------------------------

/** A diff's source as a route carries it: against the base, not committed yet, or a commit. */
object DiffSource {
    fun base(base: String) = "base:$base"
    const val UNCOMMITTED = "head"
    fun commit(sha: String) = "commit:$sha"

    /** How a comment names where its lines were read. */
    fun words(src: String): String = when {
        src.startsWith("base:") -> "the diff against ${src.removePrefix("base:")}"
        src == UNCOMMITTED -> "the changes not committed yet"
        src.startsWith("commit:") -> "commit ${src.removePrefix("commit:").take(7)}"
        else -> ""
    }
}

@Composable
internal fun ChangesTab(nav: NavController, session: HostSession, spot: CodeSpot, info: CheckoutInfo, store: CodeStore, again: Int) {
    var uncommitted by rememberSaveable { mutableStateOf(info.base.isEmpty()) }
    val status = rememberLoad("status:${spot.machine}:${spot.dir}", store.cache, again) { session.code().status(spot.dir) }
    val src = if (uncommitted) DiffSource.UNCOMMITTED else DiffSource.base(info.base)
    val changes = rememberLoad(changesKey(spot, src), store.cache, again) {
        session.code().changes(spot.dir, info.base, if (uncommitted) DiffScope.Uncommitted else DiffScope.Base)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = Space.xxl)) {
        item(key = "status") { StatusCard(status, info) }
        if (info.base.isNotEmpty()) item(key = "scope") {
            SegmentedPills(
                listOf("Against ${info.base}", "Not committed"), if (uncommitted) 1 else 0, onSelect = { uncommitted = it == 1 },
                modifier = Modifier.padding(top = Space.m),
            )
        }
        changesList(changes, empty = if (uncommitted) "Nothing changed since the last commit." else "Nothing here that ${info.base} doesn't have.") { path ->
            nav.navigate(Routes.codeDiff(spot, src, path))
        }
    }
}

fun changesKey(spot: CodeSpot, src: String) = "changes:${spot.machine}:${spot.dir}:$src"

/** The files of a change set, under a line that counts them. */
fun LazyListScope.changesList(changes: Load<ChangeSet>, empty: String, onOpen: (String) -> Unit) {
    when (changes) {
        Load.Loading -> item(key = "loading") { LoadingState("Reading the changes", Modifier.padding(top = Space.xxl)) }
        is Load.Failed -> item(key = "failed") { ErrorMessage(changes.message, Modifier.padding(top = Space.m)) }
        is Load.Ready -> {
            val set = changes.value
            when {
                set.noBase -> item(key = "nobase") { Notice("This branch shares no history with its base, so there is nothing to compare.", Modifier.padding(top = Space.m)) }
                set.files.isEmpty() -> item(key = "none") { EmptyHint(empty) }
                else -> {
                    item(key = "count") {
                        SectionHeading(
                            "${set.files.size} file${if (set.files.size == 1) "" else "s"} changed", top = Space.l,
                            trailing = { AddedRemoved(set.added, set.removed) },
                        )
                    }
                    if (set.truncated) item(key = "cut") { Notice("The diff is very large: the last files are left out.", Modifier.padding(bottom = Space.s)) }
                    item(key = "files") {
                        QuietCard {
                            set.files.forEachIndexed { i, f ->
                                if (i > 0) RowDivider(inset = 52.dp)
                                DiffFileRow(f) { onOpen(f.path) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: Load<GitStatus>, info: CheckoutInfo) {
    val c = Herdr.colors
    QuietCard(padded = true, modifier = Modifier.padding(top = Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(HerdrIcons.GitBranch, null, tint = c.muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(info.branch.ifEmpty { "Detached at ${info.head}" }, style = MaterialTheme.typography.titleMedium, color = c.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val s = status.value
                Text(
                    when {
                        status is Load.Failed -> status.message
                        s == null -> "Reading its status"
                        else -> listOfNotNull(
                            s.upstream?.let { u ->
                                when {
                                    s.ahead > 0 && s.behind > 0 -> "${s.ahead} ahead and ${s.behind} behind $u"
                                    s.ahead > 0 -> "${s.ahead} ahead of $u"
                                    s.behind > 0 -> "${s.behind} behind $u"
                                    else -> "up to date with $u"
                                }
                            },
                            when (val n = s.entries.size) {
                                0 -> "nothing uncommitted"
                                else -> "$n file${if (n == 1) "" else "s"} not committed" +
                                    s.entries.count { it.untracked }.let { u -> if (u > 0) ", $u new" else "" } +
                                    s.entries.count { it.conflicted }.let { k -> if (k > 0) ", $k in conflict" else "" }
                            },
                        ).joinToString(" · ").replaceFirstChar { it.uppercase() }
                    },
                    style = MaterialTheme.typography.bodySmall, color = if (status is Load.Failed) c.red else c.muted,
                )
            }
        }
    }
}

// ---- History ------------------------------------------------------------------------------------

@Composable
private fun HistoryTab(nav: NavController, session: HostSession, spot: CodeSpot, info: CheckoutInfo, store: CodeStore, again: Int) {
    val log = rememberLoad("log:${spot.machine}:${spot.dir}", store.cache, again) { session.code().log(spot.dir, info.base) }
    when (log) {
        Load.Loading -> Box(Modifier.fillMaxSize()) { LoadingState("Reading the history", Modifier.align(Alignment.Center)) }
        is Load.Failed -> ErrorMessage(log.message, Modifier.padding(Space.screen))
        is Load.Ready -> {
            val (ahead, earlier) = log.value.partition { it.ahead }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = Space.xxl)) {
                if (log.value.isEmpty()) item { EmptyHint("No commits yet.") }
                fun group(title: String, list: List<Commit>, first: Boolean) {
                    if (list.isEmpty()) return
                    item(key = "h:$title") { SectionHeading(title, top = if (first) Space.s else Space.xl, trailing = { CountPill("${list.size}") }) }
                    item(key = "c:$title") {
                        QuietCard {
                            list.forEachIndexed { i, cm ->
                                if (i > 0) RowDivider()
                                CommitRow(cm) { nav.navigate(Routes.codeCommit(spot, cm.sha)) }
                            }
                        }
                    }
                }
                group(if (info.base.isEmpty()) "Commits" else "Not in ${info.base} yet", ahead, first = true)
                group(if (ahead.isEmpty()) "Commits" else "Earlier", earlier, first = ahead.isEmpty())
            }
        }
    }
}

@Composable
private fun CommitRow(cm: Commit, onClick: () -> Unit) {
    val now = LocalContainer.current.code.now()
    ListRow(
        title = cm.subject.ifEmpty { "(no message)" },
        subtitle = "${cm.short} · ${cm.author} · ${ago(cm.at, now)}" + refs(cm.refs),
        titleLines = 2,
        onClickLabel = "Show the commit",
        onClick = onClick,
    )
}

/** The branches and tags at a commit, without HEAD's arrow: " · main, origin/main". */
private fun refs(raw: String): String {
    val names = raw.split(", ").map { it.removePrefix("HEAD -> ").removePrefix("tag: ") }.filter { it.isNotBlank() && it != "HEAD" }
    return if (names.isEmpty()) "" else " · " + names.take(3).joinToString(", ")
}
