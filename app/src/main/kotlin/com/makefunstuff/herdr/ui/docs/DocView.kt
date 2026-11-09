package com.makefunstuff.herdr.ui.docs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makefunstuff.herdr.core.docs.DocBlock
import com.makefunstuff.herdr.core.docs.DocEntry
import com.makefunstuff.herdr.core.docs.HerdrDoc
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.Led
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.design.hairline
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.images.MessageImages
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.HerdrColors
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.theme.caps

/**
 * A herdr.doc/2 document drawn natively, as the desktop's Docs panel (mods/px-doc) draws it, in the workbench's look:
 * frames, mono capitals for labels, a state's colour only with its word.
 */
object DocLook {
    /** A block's tone (ok, warn, bad, info) as a state colour: the desktop's done, working, blocked and accent. */
    fun tone(c: HerdrColors, tone: String?): Color? = when (tone) {
        "ok", "good" -> c.done
        "warn" -> c.working
        "bad" -> c.red
        "info" -> c.accent
        else -> null
    }

    /** A finding's severity as its colour: critical and high blocked, medium working, low quiet. */
    fun severity(c: HerdrColors, s: String?): Color = when (s) {
        "critical", "high" -> c.red
        "medium" -> c.working
        else -> c.idle
    }

    /** A document's status as its word and colour; null where it has none. */
    fun status(c: HerdrColors, s: String?): Pair<String, Color>? = when (s) {
        null, "" -> null
        "work" -> "working" to c.working
        "need" -> "needs you" to c.red
        "ready" -> "ready" to c.done
        "done" -> "done" to c.done
        "fail" -> "failed" to c.red
        "info" -> "info" to c.idle
        else -> s to c.idle
    }

    /** What kind of document it is, as its icon. */
    fun icon(kind: String?): ImageVector = when (kind) {
        "investigation" -> HerdrIcons.ScanSearch
        "incident" -> HerdrIcons.TriangleAlert
        "runbook" -> HerdrIcons.BookOpen
        "design" -> HerdrIcons.PenLine
        "notes" -> HerdrIcons.NotebookPen
        "report" -> HerdrIcons.Newspaper
        else -> HerdrIcons.FileText
    }
}

/** The document's head: its kind, title, status, subtitle, tags and when it was last changed ([updated], already said). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DocHead(doc: HerdrDoc, updated: String?, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            doc.kind?.let { KindTag(it) }
            Spacer(Modifier.weight(1f))
            DocLook.status(c, doc.status)?.let { (word, color) -> StatusBadge(word, color) }
        }
        Text(
            doc.title, style = MaterialTheme.typography.headlineSmall, color = c.strong,
            modifier = Modifier.padding(top = Space.xs).semantics { heading() },
        )
        doc.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
        if (doc.tags.isNotEmpty() || updated != null) {
            FlowRow(
                Modifier.padding(top = Space.xs),
                horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                doc.tags.forEach { Tag(it) }
                updated?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.align(Alignment.CenterVertically).padding(start = Space.xs))
                }
            }
        }
    }
}

/** A document's kind, as the desktop marks it: an amber-framed tag in capitals. */
@Composable
fun KindTag(kind: String) {
    val c = Herdr.colors
    Text(
        kind.caps(), style = MaterialTheme.typography.labelSmall, color = c.accent, maxLines = 1,
        modifier = Modifier.border(1.dp, c.frame).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun Tag(text: String) {
    val c = Herdr.colors
    Text(text, style = MaterialTheme.typography.labelMedium, color = c.muted, maxLines = 1, modifier = Modifier.border(1.dp, c.line).padding(horizontal = 6.dp, vertical = 1.dp))
}

/** A list entry's second line: its kind, then when it was changed. */
fun DocEntry.line(now: Long): String = listOfNotNull(
    if (broken) "can't be read" else kind,
    mtime.takeIf { it > 0 }?.let { com.makefunstuff.herdr.ui.code.ago(it, now) },
).joinToString(" · ")

/** One block, by its type; [key] names it among the document's, for the sections' folds. */
@Composable
fun DocBlockView(block: DocBlock, key: String, machineId: String, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    when (block) {
        is DocBlock.Heading -> DocHeading(block.text, block.level, modifier)
        is DocBlock.Text -> SelectionContainer(modifier) { Text(block.text, style = MaterialTheme.typography.bodyMedium, color = c.text) }
        is DocBlock.Md -> MarkdownText(block.text, modifier)
        DocBlock.Divider -> Box(modifier.fillMaxWidth().padding(vertical = Space.xs).height(1.dp).background(c.line))
        is DocBlock.Callout -> {
            val col = DocLook.tone(c, block.tone) ?: c.accent
            Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).background(c.tint(col))) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(col))
                Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    block.title?.let { Text(it.caps(), style = MaterialTheme.typography.labelSmall, color = col) }
                    MarkdownText(block.text, color = c.text)
                }
            }
        }
        is DocBlock.Quote -> Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(c.lineStrong))
            Column(Modifier.padding(start = Space.m, top = 2.dp, bottom = 2.dp)) {
                SelectionContainer { Text(block.text, style = MaterialTheme.typography.bodyMedium, color = c.muted) }
                listOfNotNull(block.who, block.where).filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let {
                    Text("— " + it.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        is DocBlock.Stats -> Stats(block, modifier)
        is DocBlock.Kv -> Framed(modifier) {
            block.items.forEachIndexed { i, (k, v) ->
                if (i > 0) Rule()
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Text(
                        k.caps(), style = MaterialTheme.typography.labelSmall, color = c.muted,
                        modifier = Modifier.width(112.dp).fillMaxHeight().background(c.panel).padding(horizontal = Space.s, vertical = 6.dp),
                    )
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(v, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.padding(horizontal = Space.s, vertical = 4.dp))
                    }
                }
            }
        }
        is DocBlock.Items -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            block.items.forEachIndexed { i, text ->
                Row {
                    Text(
                        if (block.numbered) "${i + 1}." else "▪", style = MaterialTheme.typography.labelMedium,
                        color = c.accent, modifier = Modifier.width(24.dp).padding(top = 2.dp),
                    )
                    MarkdownText(text, Modifier.weight(1f))
                }
            }
        }
        is DocBlock.Todo -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEach { t ->
                Row(
                    Modifier.semantics(mergeDescendants = true) { stateDescription = if (t.done) "Done" else "Not done" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(12.dp).background(if (t.done) c.done else c.ground).border(1.dp, if (t.done) c.done else c.lineStrong))
                    Text(
                        t.text, style = MaterialTheme.typography.bodyMedium, color = if (t.done) c.muted else c.text,
                        textDecoration = if (t.done) TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f).padding(start = Space.s),
                    )
                    t.who?.takeIf { it.isNotBlank() }?.let { Box(Modifier.padding(start = Space.s)) { Tag(it) } }
                }
            }
        }
        is DocBlock.Checks -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEach { x ->
                Row(verticalAlignment = Alignment.Top) {
                    val (word, col) = when (x.ok) {
                        true -> "pass" to c.done
                        false -> "fail" to c.red
                        null -> "not run" to c.idle
                    }
                    StatusBadge(word, col, Modifier.width(76.dp))
                    Column(Modifier.weight(1f).padding(start = Space.xs)) {
                        Text(x.text, style = MaterialTheme.typography.bodyMedium, color = c.text)
                        x.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
                    }
                }
            }
        }
        is DocBlock.Steps -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEachIndexed { i, s ->
                val now = s.state == "now"
                val col = when (s.state) {
                    "done" -> c.done
                    "now" -> c.primary
                    else -> c.lineStrong
                }
                Row(
                    Modifier.semantics(mergeDescendants = true) {
                        stateDescription = when (s.state) { "done" -> "Done"; "now" -> "Now"; "skip" -> "Skipped"; else -> "To do" }
                    },
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        Modifier.size(width = 26.dp, height = 20.dp).background(if (now) c.primary else c.card).border(1.dp, col),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            (i + 1).toString().padStart(2, '0'), style = MaterialTheme.typography.labelSmall,
                            color = when (s.state) { "now" -> c.onPrimary; "done" -> c.done; else -> c.muted },
                        )
                    }
                    Column(Modifier.weight(1f).padding(start = Space.s)) {
                        Text(
                            s.text, style = MaterialTheme.typography.bodyMedium,
                            color = if (s.state == "skip") c.muted else c.text,
                            textDecoration = if (s.state == "skip") TextDecoration.LineThrough else null,
                        )
                        s.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
                    }
                }
            }
        }
        is DocBlock.Timeline -> Column(modifier.fillMaxWidth()) {
            block.items.forEachIndexed { i, m ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Text(
                        m.time, style = MaterialTheme.typography.labelMedium, color = c.muted, textAlign = TextAlign.End,
                        modifier = Modifier.width(52.dp).padding(top = 2.dp),
                    )
                    Column(Modifier.width(20.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Led(DocLook.tone(c, m.tone) ?: c.accent, Modifier.padding(top = 6.dp))
                        if (i < block.items.lastIndex) Box(Modifier.width(1.dp).weight(1f).background(c.lineStrong))
                    }
                    Column(Modifier.weight(1f).padding(bottom = Space.s)) {
                        Text(m.text, style = MaterialTheme.typography.bodyMedium, color = c.text)
                        m.who?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
                    }
                }
            }
        }
        is DocBlock.Finding -> {
            val col = DocLook.severity(c, block.severity)
            Framed(modifier) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(c.panel), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (block.severity ?: "finding").caps(), style = MaterialTheme.typography.labelSmall, color = c.ground,
                        modifier = Modifier.fillMaxHeight().background(col).padding(horizontal = Space.s, vertical = 6.dp),
                    )
                    Text(
                        block.title, style = MaterialTheme.typography.titleMedium, color = c.strong,
                        modifier = Modifier.weight(1f).padding(horizontal = Space.s, vertical = 6.dp),
                    )
                }
                Rule()
                Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    block.detail?.takeIf { it.isNotBlank() }?.let { MarkdownText(it) }
                    block.evidence.forEachIndexed { i, e -> DocBlockView(e, "$key-ev$i", machineId) }
                    block.fix?.takeIf { it.isNotBlank() }?.let {
                        Row {
                            Text("FIX", style = MaterialTheme.typography.labelSmall, color = c.done, modifier = Modifier.width(36.dp).padding(top = 3.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        is DocBlock.Evidence -> Framed(modifier, edge = c.line) {
            Column(Modifier.fillMaxWidth().background(c.card).padding(horizontal = Space.s, vertical = 6.dp)) {
                // Where it ran over the command: beside it, a long command would squeeze it to a letter a line.
                block.where?.takeIf { it.isNotBlank() }?.let { Text(it.caps(), style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Row {
                    Text("$", style = Mono12, color = c.accent)
                    SelectionContainer(Modifier.weight(1f).padding(start = Space.s)) { Text(block.command, style = Mono12, color = c.text) }
                }
            }
            Lines(block.output.lines().take(80), c.muted)
        }
        is DocBlock.Code -> Framed(modifier, edge = c.line) {
            (block.file ?: block.lang)?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.fillMaxWidth().background(c.card).padding(horizontal = Space.s, vertical = 4.dp))
            }
            Lines(block.text.lines().take(300), c.text)
        }
        is DocBlock.Diff -> Framed(modifier, edge = c.line) {
            block.file?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.fillMaxWidth().background(c.card).padding(horizontal = Space.s, vertical = 4.dp))
            }
            val lines = block.text.lines().take(300)
            SelectionContainer {
                Column(Modifier.fillMaxWidth().background(c.ground).horizontalScroll(rememberScrollState()).padding(vertical = Space.xs)) {
                    lines.forEach { l ->
                        val (ink, bg) = when {
                            l.startsWith("+") && !l.startsWith("+++") -> c.done to c.tint(c.done)
                            l.startsWith("-") && !l.startsWith("---") -> c.red to c.tint(c.red)
                            l.startsWith("@@") -> c.muted to Color.Transparent
                            else -> c.text to Color.Transparent
                        }
                        Text(untab(l).ifEmpty { " " }, style = Mono12, color = ink, softWrap = false, modifier = Modifier.background(bg).padding(horizontal = Space.s))
                    }
                }
            }
        }
        is DocBlock.Table -> DocTable(block, modifier)
        is DocBlock.Bars -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEach { b ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(b.label, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.dp))
                    Box(Modifier.weight(1f).height(10.dp).background(c.card)) {
                        Box(Modifier.fillMaxWidth((b.value / block.max).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(DocLook.tone(c, b.tone) ?: c.accent))
                    }
                    Text(
                        number(b.value) + (block.unit?.let { " $it" } ?: ""), style = MaterialTheme.typography.labelMedium, color = c.text,
                        textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(72.dp),
                    )
                }
            }
        }
        is DocBlock.Progress -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text(block.label, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.weight(1f))
                Text("${number(block.value)}/${number(block.max)}", style = MaterialTheme.typography.labelMedium, color = c.text)
            }
            val on = ((block.value / block.max).coerceIn(0.0, 1.0) * 24).toInt()
            val col = DocLook.tone(c, block.tone) ?: c.accent
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                repeat(24) { i -> Box(Modifier.weight(1f).height(6.dp).background(if (i < on) col else c.card)) }
            }
        }
        is DocBlock.Links -> Links(block, modifier)
        is DocBlock.Image -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            // The picture is on the machine: read back as a chat's pictures are, a tap shows it full size.
            MessageImages(listOf(block.path))
            block.caption?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
        }
        is DocBlock.Section -> Section(block, key, machineId, modifier)
        is DocBlock.Columns -> BoxWithConstraints(modifier.fillMaxWidth()) {
            val cols = block.columns.filter { it.isNotEmpty() }
            // Side by side where there is room for them (a tablet, the phone turned); one under the other on a phone.
            if (maxWidth >= 600.dp) Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                cols.forEachIndexed { ci, col ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        col.forEachIndexed { i, b -> DocBlockView(b, "$key-c$ci-$i", machineId) }
                    }
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                cols.forEachIndexed { ci, col -> col.forEachIndexed { i, b -> DocBlockView(b, "$key-c$ci-$i", machineId) } }
            }
        }
    }
}

private val Mono12 = CodeStyle.copy(fontSize = 12.sp, lineHeight = 18.sp)

/** Tabs as four spaces: the mono face has no glyph for a tab. */
private fun untab(line: String) = line.replace("\t", "    ")

/** 42, not 42.0; 3.5 as it is. */
private fun number(v: Double): String = if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

/** A heading as the desktop draws it: its words on a tile (amber for the first level), then a rule to the edge. */
@Composable
private fun DocHeading(text: String, level: Int, modifier: Modifier) {
    val c = Herdr.colors
    Row(modifier.fillMaxWidth().padding(top = if (level == 1) Space.s else Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.caps(),
            style = if (level == 3) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleSmall,
            color = if (level == 1) c.onPrimary else c.strong,
            modifier = Modifier.background(if (level == 1) c.primary else c.card).padding(horizontal = Space.s, vertical = 3.dp)
                .semantics { heading(); contentDescription = text },
        )
        Box(Modifier.weight(1f).padding(start = Space.s).height(1.dp).background(c.line))
    }
}

/** A frame around rows (a key/value list, a finding, code): one module, square, 1dp. */
@Composable
private fun Framed(modifier: Modifier, edge: Color = Herdr.colors.lineStrong, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().hairline(HerdrShapes.Card, edge)) { content() }
}

@Composable
private fun Rule() = Box(Modifier.fillMaxWidth().height(1.dp).background(Herdr.colors.line))

/** Monospaced lines on the ground, scrolling sideways, never wrapped: output and code as they were printed. */
@Composable
private fun Lines(lines: List<String>, color: Color) {
    SelectionContainer {
        Column(Modifier.fillMaxWidth().background(Herdr.colors.ground).horizontalScroll(rememberScrollState()).padding(Space.s)) {
            lines.forEach { Text(untab(it).ifEmpty { " " }, style = Mono12, color = color, softWrap = false) }
        }
    }
}

/** Big numbers, as many to a row as fit at about 110dp each. */
@Composable
private fun Stats(block: DocBlock.Stats, modifier: Modifier) {
    val c = Herdr.colors
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val per = (maxWidth / 110.dp).toInt().coerceIn(1, 4)
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            block.items.chunked(per).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    row.forEach { s ->
                        val col = DocLook.tone(c, s.tone)
                        Column(
                            Modifier.weight(1f).fillMaxHeight().background(c.panel).border(1.dp, col ?: c.lineStrong).padding(horizontal = Space.s, vertical = 6.dp)
                                .semantics(mergeDescendants = true) {},
                        ) {
                            Text(s.label.caps(), style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(s.value, style = MaterialTheme.typography.headlineSmall, color = col ?: c.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            s.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
                        }
                    }
                    repeat(per - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** A table that scrolls sideways, each column as wide as its longest cell needs (within bounds). */
@Composable
private fun DocTable(block: DocBlock.Table, modifier: Modifier) {
    val c = Herdr.colors
    val count = maxOf(block.columns.size, block.rows.maxOfOrNull { it.size } ?: 0)
    if (count == 0) return
    val natural: List<Float> = (0 until count).map { i ->
        val longest = (listOfNotNull(block.columns.getOrNull(i)) + block.rows.mapNotNull { it.getOrNull(i) }).maxOfOrNull { it.length } ?: 4
        (longest * 7.5f + 20).coerceIn(56f, 260f)
    }
    BoxWithConstraints(modifier.fillMaxWidth().hairline(HerdrShapes.Card, c.lineStrong)) {
        // Narrower than the screen: the columns share the room left, so the frame holds no empty strip.
        val room = maxWidth.value
        val sum = natural.sum()
        val widths: List<Dp> = natural.map { (if (sum < room) it * room / sum else it).dp }
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            if (block.columns.isNotEmpty()) Row(Modifier.background(c.card)) {
                widths.forEachIndexed { i, w ->
                    Text(
                        block.columns.getOrNull(i).orEmpty().caps(), style = MaterialTheme.typography.labelSmall, color = c.muted,
                        textAlign = if (i in block.right) TextAlign.End else TextAlign.Start,
                        modifier = Modifier.width(w).padding(horizontal = Space.s, vertical = 6.dp),
                    )
                }
            }
            block.rows.forEachIndexed { ri, r ->
                Box(Modifier.width(widths.fold(0.dp) { a, b -> a + b }).height(1.dp).background(c.line))
                Row(Modifier.background(if (ri % 2 == 1) c.panel else Color.Transparent)) {
                    widths.forEachIndexed { i, w ->
                        Text(
                            r.getOrNull(i).orEmpty(), style = MaterialTheme.typography.bodySmall, color = c.text,
                            textAlign = if (i in block.right) TextAlign.End else TextAlign.Start,
                            modifier = Modifier.width(w).padding(horizontal = Space.s, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Links, each a framed row that opens in the browser. */
@Composable
private fun Links(block: DocBlock.Links, modifier: Modifier) {
    val c = Herdr.colors
    val uri = LocalUriHandler.current
    Framed(modifier) {
        block.items.forEachIndexed { i, l ->
            if (i > 0) Rule()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "Open in the browser") { runCatching { uri.openUri(l.url) } }
                    .padding(horizontal = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = Space.xs)) {
                    Text(l.label, style = MaterialTheme.typography.bodyMedium, color = c.accent, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (l.label != l.url) Text(l.url, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(HerdrIcons.ExternalLink, null, tint = c.muted, modifier = Modifier.padding(start = Space.s).size(16.dp))
            }
        }
    }
}

/**
 * A titled group that folds, as the document says it starts (open, or [DocBlock.Section.collapsed]): a heading with its
 * chevron, a rule and how many blocks it holds, its blocks under it. Not a frame: its blocks may be modules themselves.
 */
@Composable
private fun Section(block: DocBlock.Section, key: String, machineId: String, modifier: Modifier) {
    val c = Herdr.colors
    // Saved in its list item, which a top-level section's title keys (HerdrDocs.names); a nested one by its place in it.
    var open by rememberSaveable { mutableStateOf(!block.collapsed) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(onClickLabel = if (open) "Fold" else "Unfold") { open = !open }
                .semantics(mergeDescendants = true) { stateDescription = if (open) "Open" else "Folded" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(24.dp).background(c.card).border(1.dp, c.lineStrong), contentAlignment = Alignment.Center) {
                Icon(if (open) HerdrIcons.ChevronDown else HerdrIcons.ChevronRight, null, tint = c.text, modifier = Modifier.size(14.dp))
            }
            Text(
                block.title.caps(), style = MaterialTheme.typography.titleSmall, color = c.strong, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Space.s).semantics { heading() },
            )
            Box(Modifier.weight(1f).height(1.dp).background(c.line))
            Text(block.blocks.size.toString(), style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.padding(start = Space.s))
        }
        if (open) {
            block.intro?.takeIf { it.isNotBlank() }?.let { MarkdownText(it) }
            block.blocks.forEachIndexed { i, b -> DocBlockView(b, "$key-$i", machineId) }
        }
    }
}
