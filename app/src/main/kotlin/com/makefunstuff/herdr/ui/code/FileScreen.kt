package com.makefunstuff.herdr.ui.code

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.code.FileText
import com.makefunstuff.herdr.core.code.Syntax
import com.makefunstuff.herdr.data.CodeView
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.common.copyToClipboard
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.BottomActionBar
import com.makefunstuff.herdr.ui.design.ErrorState
import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrSheet
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.SheetTitle
import com.makefunstuff.herdr.ui.design.SwitchRow
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One file, to read on a phone: numbered and coloured lines, wrapped or not, at the size you set;
 * Go to line; Markdown shown as it reads (and its source a tap away). Choosing lines offers to
 * comment on them. A file bigger than [com.makefunstuff.herdr.core.code.CodeCommands.READ_LIMIT]
 * shows its first part and says so.
 */
@Composable
fun FileScreen(nav: NavController, spot: CodeSpot, path: String, line: Int?) {
    val container = LocalContainer.current
    val session = container.hosts.session(spot.machine)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host by session.state.collectAsStateWithLifecycle()
    val view by container.code.view.collectAsStateWithLifecycle()
    val notes by container.code.notes.collectAsStateWithLifecycle()
    var again by remember { mutableIntStateOf(0) }
    val file = rememberLoad("file:${spot.machine}:${spot.dir}:$path", container.code.cache, again) { session.code().read(spot.dir, path) }

    val name = path.substringAfterLast('/')
    val folder = path.substringBeforeLast('/', "")
    val markdown = Syntax.language(path) == Syntax.markdown
    var source by rememberSaveable { mutableStateOf(!markdown || line != null) }
    var showView by remember { mutableStateOf(false) }
    var goTo by remember { mutableStateOf(false) }
    var marked by rememberSaveable { mutableStateOf(line?.let { it - 1 }) }
    var chosen by remember { mutableStateOf<IntRange?>(null) }
    var anchor by remember { mutableStateOf<Int?>(null) }
    var commenting by remember { mutableStateOf(false) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = ((line ?: 1) - 4).coerceAtLeast(0))
    val pending = notes.count { it.machineId == spot.machine && it.root == spot.dir }

    val text = file.value
    val language = remember(text) { text?.let { Syntax.language(path, it.lines.firstOrNull()) } }
    // The highlighter's state at each line, worked out away from the screen; until then each line starts fresh.
    val states by produceState<IntArray?>(null, text, language) {
        value = if (text == null || language == null) null else withContext(Dispatchers.Default) { Syntax.states(language, text.lines) }
    }
    val rows = remember(text, states) {
        text?.lines?.mapIndexed { i, l -> CodeRow("$i", l, listOf(i + 1), state = states?.getOrNull(i) ?: 0) }.orEmpty()
    }
    BackHandler(enabled = chosen != null) { chosen = null; anchor = null }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Herdr.colors.panel,
        topBar = {
            HerdrTopBar(name, subtitle = listOf(shortFolder(folder), spot.dir.trimEnd('/').substringAfterLast('/')).joinToString(" · "), onBack = { nav.popBackStack() }) {
                if (markdown && text != null) BarIcon(if (source) HerdrIcons.Eye else HerdrIcons.Code, if (source) "Show as it reads" else "Show the source", { source = !source; chosen = null })
                if (source) BarIcon(HerdrIcons.Hash, "Go to line", { goTo = true }, enabled = rows.isNotEmpty())
                BarIcon(HerdrIcons.ALargeSmall, "Text size and wrapping", { showView = true })
                BarIcon(HerdrIcons.MessageSquare, "Comments", { nav.navigate(Routes.codeNotes(spot)) }, badge = pending)
            }
        },
        bottomBar = {
            chosen?.let { range ->
                SelectionBar(
                    linesLabel(rows, range),
                    onCopy = { context.copyToClipboard(name, range.joinToString("\n") { rows[it].text }) },
                    onClear = { chosen = null; anchor = null },
                    onComment = { commenting = true },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (file) {
                Load.Loading -> LoadingState("Reading $name", Modifier.align(Alignment.Center))
                is Load.Failed -> ErrorState("Couldn't read $name", file.message, onRetry = { again++ })
                is Load.Ready -> {
                    val f = file.value
                    when {
                        f.binary -> EmptyState("Not a text file", "$name holds binary data (${size(f.size)}), which can't be shown here.", icon = HerdrIcons.File)
                        f.text.isEmpty() -> EmptyState("Empty file", "$name has nothing in it.", icon = HerdrIcons.File)
                        !source -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.screen)) {
                            if (f.truncated) Cut(f)
                            MarkdownText(f.text, style = MaterialTheme.typography.bodyLarge)
                        }
                        else -> Column(Modifier.fillMaxSize()) {
                            if (f.truncated) Cut(f)
                            CodeLines(
                                rows, language, view, chosen, marked,
                                onChoose = { i -> choose(chosen, anchor, i).let { (r, a) -> chosen = r; anchor = a; marked = null } },
                                state = list, modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showView) ViewSheet(view, onChange = container.code::setView, onDismiss = { showView = false })
    if (goTo) GoToLine(rows.size, onDismiss = { goTo = false }) { n ->
        goTo = false
        marked = n - 1
        chosen = null
        scope.launch { list.scrollToItem((n - 4).coerceAtLeast(0)) }
    }
    val range = chosen
    if (commenting && range != null) {
        val (start, end) = lineSpan(rows, range)
        CommentSheet(
            spot = spot, host = host,
            draft = NoteDraft(path, start, end, quote = quote(rows, range), language = language?.name.orEmpty(), source = ""),
            onDismiss = { commenting = false },
            onDone = { commenting = false; chosen = null; anchor = null },
        )
    }
}

@Composable
private fun Cut(f: FileText) = CodeNote("Showing the first ${size(f.text.toByteArray().size.toLong())} of ${size(f.size)}.")

/** A file's folder as a bar has room for it: its last two parts ("…/ui/machines"), or "top folder". */
fun shortFolder(folder: String): String {
    if (folder.isEmpty()) return "top folder"
    val parts = folder.split('/')
    return if (parts.size <= 2) folder else "…/" + parts.takeLast(2).joinToString("/")
}

/** "12 KB", "3.4 MB". */
fun size(bytes: Long): String = when {
    bytes < 1024 -> "$bytes bytes"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

/** The bar while lines are chosen: which, Copy, let go, and Comment. */
@Composable
fun SelectionBar(label: String, onCopy: () -> Unit, onClear: () -> Unit, onComment: () -> Unit) {
    BottomActionBar {
        BarIcon(HerdrIcons.X, "Let go of the lines", onClear)
        Text(label, style = MaterialTheme.typography.labelLarge, color = Herdr.colors.strong, modifier = Modifier.weight(1f), maxLines = 1)
        BarIcon(HerdrIcons.Copy, "Copy the lines", onCopy)
        PrimaryButton("Comment", onClick = onComment)
    }
}

/** How code is shown: wrapped or not, and the text's size. */
@Composable
fun ViewSheet(view: CodeView, onChange: (CodeView) -> Unit, onDismiss: () -> Unit) {
    HerdrSheet(onDismiss) {
        SheetTitle("Text size and wrapping")
        Column(Modifier.padding(horizontal = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            SwitchRow("Wrap long lines", "Off, long lines scroll sideways", checked = view.wrap) { onChange(view.copy(wrap = it)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Column(Modifier.weight(1f)) {
                    Text("Text size", style = MaterialTheme.typography.titleMedium, color = Herdr.colors.strong)
                    Text("${view.textSize} sp", style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted)
                }
                SecondaryButton("Smaller", enabled = view.textSize > CodeView.SMALLEST) { onChange(view.copy(textSize = view.textSize - 1)) }
                SecondaryButton("Larger", enabled = view.textSize < CodeView.LARGEST) { onChange(view.copy(textSize = view.textSize + 1)) }
            }
            Text(
                "fun main() = println(\"herdr\")", style = codeStyle(view.textSize), color = Herdr.colors.text, maxLines = 1,
                modifier = Modifier.padding(vertical = Space.m),
            )
        }
    }
}

/** Asks for a line number, from 1 to [lines]. */
@Composable
private fun GoToLine(lines: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var typed by remember { mutableStateOf("") }
    val n = typed.trim().toIntOrNull()
    val ok = n != null && n in 1..lines
    HerdrDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to line") },
        text = {
            HerdrField(
                typed, { typed = it.filter(Char::isDigit) }, singleLine = true,
                placeholder = { Text("1 to $lines") },
                supportingText = if (n != null && !ok) ({ Text("The file has $lines lines") }) else null,
                isError = n != null && !ok,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (ok) onGo(n!!) }),
            )
        },
        dismissButton = { QuietButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { QuietButton(onClick = { if (ok) onGo(n!!) }, enabled = ok) { Text("Go") } },
    )
}
