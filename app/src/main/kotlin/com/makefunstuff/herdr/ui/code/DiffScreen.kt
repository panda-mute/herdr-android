package com.makefunstuff.herdr.ui.code

import com.makefunstuff.herdr.ui.change.ChangeScreenRow
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.code.ChangeSet
import com.makefunstuff.herdr.core.code.CommitDiff
import com.makefunstuff.herdr.core.code.DiffLine
import com.makefunstuff.herdr.core.code.DiffScope
import com.makefunstuff.herdr.core.code.FileChange
import com.makefunstuff.herdr.core.code.FileDiff
import com.makefunstuff.herdr.core.code.Language
import com.makefunstuff.herdr.core.code.Syntax
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.data.CodeStore
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.copyToClipboard
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ErrorState
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

/** The files of a diff's [src] (see [DiffSource]), read once and kept for the screens that show them. */
@Composable
private fun rememberDiff(session: HostSession, spot: CodeSpot, src: String, store: CodeStore): Load<List<FileDiff>> {
    if (src.startsWith("commit:")) {
        val sha = src.removePrefix("commit:")
        return when (val l = rememberLoad(commitKey(spot, sha), store.cache) { session.code().show(spot.dir, sha) }) {
            is Load.Ready -> Load.Ready(l.value.files)
            is Load.Failed -> l
            Load.Loading -> Load.Loading
        }
    }
    val l = rememberLoad(changesKey(spot, src), store.cache) {
        if (src == DiffSource.UNCOMMITTED) session.code().changes(spot.dir, "", DiffScope.Uncommitted)
        else session.code().changes(spot.dir, src.removePrefix("base:"), DiffScope.Base)
    }
    return when (l) {
        is Load.Ready -> Load.Ready(l.value.files)
        is Load.Failed -> l
        Load.Loading -> Load.Loading
    }
}

fun commitKey(spot: CodeSpot, sha: String) = "commit:${spot.machine}:${spot.dir}:$sha"

/**
 * One file of a diff: its hunks, the removed and added lines on their states' tints, both line
 * numbers, the code coloured. The bar steps to the diff's previous and next file. Choosing lines
 * offers to comment on them; the comment quotes them with their `+` and `-`.
 */
@Composable
fun DiffScreen(nav: NavController, spot: CodeSpot, src: String, path: String) {
    val container = LocalContainer.current
    val session = container.hosts.session(spot.machine)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val context = LocalContext.current
    val host by session.state.collectAsStateWithLifecycle()
    val view by container.code.view.collectAsStateWithLifecycle()
    val notes by container.code.notes.collectAsStateWithLifecycle()
    val diff = rememberDiff(session, spot, src, container.code)
    val files = diff.value.orEmpty()
    val at = files.indexOfFirst { it.path == path || it.oldPath == path }
    val f = files.getOrNull(at)
    val language = remember(path) { Syntax.language(path) }
    val rows = remember(f, language) { f?.let { diffRows(it, language) }.orEmpty() }
    var chosen by remember(path) { mutableStateOf<IntRange?>(null) }
    var anchor by remember(path) { mutableStateOf<Int?>(null) }
    var commenting by remember { mutableStateOf(false) }
    var showView by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val pending = notes.count { it.machineId == spot.machine && it.root == spot.dir }
    BackHandler(enabled = chosen != null) { chosen = null; anchor = null }

    fun step(to: Int) {
        val other = files.getOrNull(to) ?: return
        nav.navigate(Routes.codeDiff(spot, src, other.path)) { popUpTo(Routes.CODE_DIFF) { inclusive = true } }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Herdr.colors.panel,
        topBar = {
            HerdrTopBar(
                path.substringAfterLast('/'),
                subtitle = listOfNotNull(
                    DiffSource.words(src).replaceFirstChar { it.uppercase() },
                    if (files.size > 1 && at >= 0) "${at + 1} of ${files.size}" else null,
                ).joinToString(" · "),
                onBack = { nav.popBackStack() },
            ) {
                if (files.size > 1) {
                    BarIcon(HerdrIcons.ChevronUp, "Previous file", { step(at - 1) }, enabled = at > 0)
                    BarIcon(HerdrIcons.ChevronDown, "Next file", { step(at + 1) }, enabled = at in 0 until files.lastIndex)
                }
                BarIcon(HerdrIcons.Ellipsis, "More", { menu = true })
                HerdrMenu(expanded = menu, onDismissRequest = { menu = false }, title = path) {
                    if (f != null && f.change != FileChange.Deleted) MenuRow(
                        if (src.startsWith("commit:")) "Open the file as it is now" else "Open the file", HerdrIcons.FileCode,
                        { menu = false; nav.navigate(Routes.codeFile(spot, f.path, firstLine(f))) },
                    )
                    MenuRow("Text size and wrapping", HerdrIcons.ALargeSmall, { menu = false; showView = true })
                    MenuRow(if (pending > 0) "Comments · $pending waiting" else "Comments", HerdrIcons.MessageSquare, { menu = false; nav.navigate(Routes.codeNotes(spot)) })
                    MenuRow("Copy the file's path", HerdrIcons.Copy, { menu = false; context.copyToClipboard("Path", path) })
                    ChangeScreenRow { menu = false }
                }
            }
        },
        bottomBar = {
            chosen?.let { range ->
                SelectionBar(
                    linesLabel(rows, range),
                    onCopy = { context.copyToClipboard(path, quote(rows, range)) },
                    onClear = { chosen = null; anchor = null },
                    onComment = { commenting = true },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                diff is Load.Failed -> ErrorState("Couldn't read the changes", diff.message)
                diff is Load.Loading -> LoadingState("Reading the changes", Modifier.align(Alignment.Center))
                f == null -> EmptyState("Not in this diff any more", "$path has no changes here now.", icon = HerdrIcons.FileDiff)
                f.binary -> EmptyState("A binary file", "$path was ${f.change.word()}; its contents can't be shown.", icon = HerdrIcons.File)
                f.modeOnly -> EmptyState("Only its mode changed", "$path was made executable, or no longer is. Its text is the same.", icon = HerdrIcons.File)
                f.hunks.isEmpty() -> EmptyState("No lines changed", "$path was ${f.change.word()} with nothing in it.", icon = HerdrIcons.File)
                else -> Column(Modifier.fillMaxSize()) {
                    if (f.change == FileChange.Renamed && f.oldPath != null) CodeNote("Renamed from ${f.oldPath}")
                    CodeLines(
                        rows, language, view, chosen, null,
                        onChoose = { i -> choose(chosen, anchor, i).let { (r, a) -> chosen = r; anchor = a } },
                        state = list, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (showView) ViewSheet(view, onChange = container.code::setView, onDismiss = { showView = false })
    val range = chosen
    if (commenting && range != null && f != null) {
        val (start, end, old) = lineSpan(rows, range)
        CommentSheet(
            spot = spot, host = host,
            draft = NoteDraft(f.path, start, end, quote = quote(rows, range), language = "diff", source = DiffSource.words(src), oldSide = old),
            onDismiss = { commenting = false },
            onDone = { commenting = false; chosen = null; anchor = null },
        )
    }
}

/** A diff's lines as rows: each hunk under its heading, the code's state carried from line to line within it. */
internal fun diffRows(f: FileDiff, language: Language?): List<CodeRow> = buildList {
    f.hunks.forEachIndexed { hi, h ->
        val where = h.newStart.takeIf { it > 0 }?.let { "Line $it" } ?: "Old line ${h.oldStart}"
        add(CodeRow("h$hi", if (h.section.isNotEmpty()) "$where · ${h.section}" else where, emptyList(), CodeRow.Kind.Hunk))
        var state = 0
        h.lines.forEachIndexed { li, l ->
            val kind = when (l.kind) {
                DiffLine.Kind.Added -> CodeRow.Kind.Added
                DiffLine.Kind.Removed -> CodeRow.Kind.Removed
                DiffLine.Kind.Context -> CodeRow.Kind.Code
                DiffLine.Kind.Note -> CodeRow.Kind.Note
            }
            add(CodeRow("$hi:$li", l.text, listOf(l.old, l.new), kind, state))
            if (language != null && kind != CodeRow.Kind.Note) state = Syntax.line(language, l.text, state).next
        }
    }
}

/** The first line the diff changed in the file as it is now. */
private fun firstLine(f: FileDiff): Int? = f.hunks.firstOrNull()?.lines?.firstOrNull { it.kind == DiffLine.Kind.Added }?.new ?: f.hunks.firstOrNull()?.newStart

/** One commit: its message, who and when, and the files it changed. */
@Composable
fun CommitScreen(nav: NavController, spot: CodeSpot, sha: String) {
    val container = LocalContainer.current
    val session = container.hosts.session(spot.machine)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val context = LocalContext.current
    val commit = rememberLoad(commitKey(spot, sha), container.code.cache) { session.code().show(spot.dir, sha) }
    val c = Herdr.colors
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = c.ground,
        topBar = {
            HerdrTopBar("Commit ${sha.take(7)}", subtitle = spot.dir.trimEnd('/').substringAfterLast('/'), onBack = { nav.popBackStack() }) {
                BarIcon(HerdrIcons.Copy, "Copy the commit's id", { context.copyToClipboard("Commit", sha) })
            }
        },
    ) { padding ->
        when (commit) {
            Load.Loading -> Box(Modifier.padding(padding).fillMaxSize()) { LoadingState("Reading the commit", Modifier.align(Alignment.Center)) }
            is Load.Failed -> Box(Modifier.padding(padding).fillMaxSize()) { ErrorState("Couldn't read the commit", commit.message) }
            is Load.Ready -> {
                val cd: CommitDiff = commit.value
                val src = DiffSource.commit(cd.commit.sha)
                LazyColumn(
                    Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.m, bottom = Space.xxl),
                ) {
                    item(key = "head") {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            Text(cd.commit.subject.ifEmpty { "(no message)" }, style = MaterialTheme.typography.headlineSmall, color = c.strong)
                            Text("${cd.commit.author} · ${ago(cd.commit.at, container.code.now())}", style = MaterialTheme.typography.bodySmall, color = c.muted)
                            Text(cd.commit.sha, style = CodeStyle, color = c.muted)
                            if (cd.commit.body.isNotEmpty()) Text(
                                cd.commit.body, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.padding(top = Space.s),
                            )
                        }
                    }
                    changesList(Load.Ready(ChangeSet(cd.commit.parents.firstOrNull().orEmpty(), cd.files, cd.truncated)), empty = "This commit changed no files.") { p ->
                        nav.navigate(Routes.codeDiff(spot, src, p))
                    }
                }
            }
        }
    }
}
