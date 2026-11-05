package com.makefunstuff.herdr.ui.agents

import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makefunstuff.herdr.core.chat.ChatTurns
import com.makefunstuff.herdr.core.code.DiffLine
import com.makefunstuff.herdr.core.code.FileChange
import com.makefunstuff.herdr.core.code.FileDiff
import com.makefunstuff.herdr.core.code.Language
import com.makefunstuff.herdr.core.code.Syntax
import com.makefunstuff.herdr.ui.code.AddedRemoved
import com.makefunstuff.herdr.ui.code.CodeRow
import com.makefunstuff.herdr.ui.code.highlight
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Radius
import com.makefunstuff.herdr.ui.theme.Space

/** Lines of an edit shown before "Show all": enough to read a small one whole. */
private const val FOLDED_LINES = 12

private val DiffStyle = CodeStyle.copy(fontSize = 12.sp, lineHeight = 18.sp)

/**
 * An edit as the chat shows it opened: the file, then its removed and added lines on red and green,
 * wrapped to the phone's width and coloured as code. A long one shows its first lines until "Show all".
 */
@Composable
fun EditDiff(diff: FileDiff, key: String, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    val language = remember(diff.path) { Syntax.language(diff.path) }
    val rows = remember(diff, language) { editRows(diff, language) }
    val count = rows.count { it.kind != CodeRow.Kind.Hunk }
    // Folded only when that hides more than a few lines.
    val long = count > FOLDED_LINES + 3
    var all by rememberSaveable(key) { mutableStateOf(false) }
    val shown = if (all || !long) rows else rows.take(rows.indexOfNth(FOLDED_LINES) { it.kind != CodeRow.Kind.Hunk } + 1)
    val shape = RectangleShape
    Column(modifier.fillMaxWidth().clip(shape).background(c.panel).border(1.dp, c.line, shape)) {
        Row(Modifier.fillMaxWidth().padding(start = Space.m, end = Space.m, top = Space.s, bottom = Space.xs), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(ChatTurns.homePath(diff.path), style = DiffStyle, color = c.text)
                changeWord(diff)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = c.muted) }
            }
            Spacer(Modifier.width(Space.s))
            AddedRemoved(diff.added, diff.removed, Modifier.padding(top = 1.dp))
        }
        if (shown.isNotEmpty()) SelectionContainer {
            Column(Modifier.fillMaxWidth().padding(bottom = if (long) 0.dp else Space.xs)) {
                shown.forEach { DiffRow(it, language) }
            }
        }
        if (long) {
            Text(
                if (all) "Show fewer" else "Show all $count lines",
                style = MaterialTheme.typography.labelLarge,
                color = c.accent,
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
                    .clickable(role = Role.Button) { all = !all }
                    .padding(horizontal = Space.m, vertical = Space.s),
            )
        }
    }
}

/** What happened to the file, when it is more than a change to its lines. */
private fun changeWord(diff: FileDiff): String? = when (diff.change) {
    FileChange.Added -> "new file"
    FileChange.Deleted -> "deleted"
    FileChange.Renamed -> diff.oldPath?.let { "moved from ${ChatTurns.homePath(it)}" }
    else -> null
}

@Composable
private fun DiffRow(row: CodeRow, language: Language?) {
    val c = Herdr.colors
    if (row.kind == CodeRow.Kind.Hunk || row.kind == CodeRow.Kind.Note) {
        Text(
            row.text, style = DiffStyle, color = c.muted, maxLines = 2,
            modifier = Modifier.fillMaxWidth().background(if (row.kind == CodeRow.Kind.Hunk) c.hover else c.panel).padding(horizontal = Space.m, vertical = 2.dp),
        )
        return
    }
    val tint = when (row.kind) {
        CodeRow.Kind.Added -> c.lineTint(c.done).compositeOver(c.panel)
        CodeRow.Kind.Removed -> c.lineTint(c.red).compositeOver(c.panel)
        else -> c.panel
    }
    val text = remember(row, language, c) { highlight(row, language, c) }
    Row(Modifier.fillMaxWidth().background(tint)) {
        Text(
            when (row.kind) { CodeRow.Kind.Added -> "+"; CodeRow.Kind.Removed -> "−"; else -> " " },
            style = DiffStyle,
            color = when (row.kind) { CodeRow.Kind.Added -> c.done; CodeRow.Kind.Removed -> c.red; else -> c.muted },
            modifier = Modifier.padding(start = Space.s).width(14.dp),
        )
        Text(text, style = DiffStyle, color = c.text, modifier = Modifier.weight(1f).padding(end = Space.m))
    }
}

/**
 * [diff]'s lines as rows: a hunk's heading when it says where it is (`Line 41 · fun main()`) and
 * between hunks, the code's colouring carried from line to line within one.
 */
internal fun editRows(diff: FileDiff, language: Language?): List<CodeRow> = buildList {
    diff.hunks.forEachIndexed { hi, h ->
        val first = h.lines.firstOrNull { it.kind != DiffLine.Kind.Note }
        val where = (first?.new ?: first?.old)?.let { "Line $it" }
        val heading = listOfNotNull(where, h.section.ifEmpty { null }).joinToString(" · ")
        if (heading.isNotEmpty() || hi > 0) add(CodeRow("h$hi", heading.ifEmpty { "⋯" }, emptyList(), CodeRow.Kind.Hunk))
        var state = 0
        h.lines.forEachIndexed { li, l ->
            val kind = when (l.kind) {
                DiffLine.Kind.Added -> CodeRow.Kind.Added
                DiffLine.Kind.Removed -> CodeRow.Kind.Removed
                DiffLine.Kind.Context -> CodeRow.Kind.Code
                DiffLine.Kind.Note -> CodeRow.Kind.Note
            }
            add(CodeRow("$hi:$li", l.text, emptyList(), kind, state))
            if (language != null && kind != CodeRow.Kind.Note) state = Syntax.line(language, l.text, state).next
        }
    }
}

/** The index of the [n]th element (from 1) that matches, or the last index when fewer do. */
private inline fun <T> List<T>.indexOfNth(n: Int, match: (T) -> Boolean): Int {
    var seen = 0
    forEachIndexed { i, t -> if (match(t) && ++seen == n) return i }
    return lastIndex
}
