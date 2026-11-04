package com.makefunstuff.herdr.ui.artifacts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.shepherdr.RecipeBlock
import com.makefunstuff.herdr.core.shepherdr.Recipes
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.hairline
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

/**
 * A recipe artifact drawn natively, as Shepherdr's web app draws it in HTML (`web/src/screens/Recipe.tsx`) and its
 * window in GPUI: each block of `core/recipe.js` at a phone's width (columns one under the other). A choice, a form
 * or a button sends its answer to the artifact's keeper ([onAnswer], in recipe.js's words). In [markup] a tap picks
 * the block it lands on ([onPick]) for a note instead of doing anything; [picked] is outlined. [picture] gives a
 * picture beside the recipe by its name, null while it is read or where it isn't there.
 */
@Composable
fun RecipeView(
    blocks: List<RecipeBlock>,
    picture: (String) -> ImageBitmap?,
    modifier: Modifier = Modifier,
    markup: Boolean = false,
    picked: String? = null,
    onPick: (RecipeBlock) -> Unit = {},
    onAnswer: (String) -> Unit = {},
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = Space.screen, vertical = Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Blocks(blocks, Ctx(picture, markup, picked, onPick, onAnswer))
    }
}

private class Ctx(
    val picture: (String) -> ImageBitmap?,
    val markup: Boolean,
    val picked: String?,
    val onPick: (RecipeBlock) -> Unit,
    val onAnswer: (String) -> Unit,
)

@Composable
private fun Blocks(blocks: List<RecipeBlock>, ctx: Ctx) {
    for (b in blocks) Picked(b, ctx) { Block(b, ctx) }
}

/** In Mark up, a block is one tap target, outlined once picked. */
@Composable
private fun Picked(b: RecipeBlock, ctx: Ctx, content: @Composable () -> Unit) {
    val c = Herdr.colors
    if (!ctx.markup) return content()
    val on = ctx.picked == b.id
    Box(
        Modifier.fillMaxWidth().clip(HerdrShapes.Field)
            .border(if (on) 2.dp else 1.dp, if (on) c.accent else c.line, HerdrShapes.Field)
            .clickable(onClickLabel = "Note on ${Recipes.blockName(b)}") { ctx.onPick(b) }
            .padding(Space.xs),
    ) {
        content()
        // Over it, so a tap never reaches its own controls.
        Box(Modifier.matchParentSize().clickable { ctx.onPick(b) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Block(b: RecipeBlock, ctx: Ctx) {
    val c = Herdr.colors
    val t = MaterialTheme.typography
    when (b) {
        is RecipeBlock.Heading -> Text(
            b.text,
            style = when (b.level) { 1 -> t.headlineSmall; 2 -> t.titleLarge; else -> t.titleMedium },
            color = c.strong, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = if (b.level == 1) 0.dp else Space.xs).semantics { heading() },
        )
        is RecipeBlock.Text -> MarkdownText(b.md, style = t.bodyLarge)
        is RecipeBlock.Bullets -> MarkdownText(
            b.items.mapIndexed { i, x -> (if (b.ordered) "${i + 1}. " else "- ") + x.replace("\n", " ") }.joinToString("\n"),
            style = t.bodyLarge,
        )
        is RecipeBlock.Table -> Table(b)
        is RecipeBlock.Chart -> Chart(b)
        is RecipeBlock.Stats -> FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            for (s in b.items) {
                Column(
                    Modifier.widthIn(min = 96.dp).clip(HerdrShapes.Tile).background(c.card).hairline(HerdrShapes.Tile, c.line).padding(Space.m),
                ) {
                    Text(s.label, style = t.labelMedium, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.value, style = t.headlineSmall, color = c.strong, fontWeight = FontWeight.SemiBold)
                    s.note?.let { Text(it, style = t.labelMedium, color = c.muted) }
                }
            }
        }
        is RecipeBlock.Image -> Picture(b, ctx)
        is RecipeBlock.Pair -> Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Picture(b.before.copy(caption = b.before.caption ?: "Before"), ctx)
            Picture(b.after.copy(caption = b.after.caption ?: "After"), ctx)
        }
        is RecipeBlock.Code -> Text(
            b.text, style = CodeStyle, color = c.text, softWrap = false,
            modifier = Modifier.fillMaxWidth().clip(HerdrShapes.Field).background(c.card).hairline(HerdrShapes.Field, c.line)
                .horizontalScroll(rememberScrollState()).padding(Space.m),
        )
        is RecipeBlock.Callout -> {
            val tone = when (b.tone) { "good" -> c.done; "warn" -> c.working; "bad" -> c.red; else -> c.accent }
            Row(Modifier.fillMaxWidth().clip(HerdrShapes.Field).background(tone.copy(alpha = if (c.dark) 0.14f else 0.10f)).padding(Space.m)) {
                Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(tone))
                Spacer(Modifier.width(Space.s))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    b.title?.let { Text(it, style = t.titleSmall, color = c.strong) }
                    b.md?.let { MarkdownText(it, style = t.bodyMedium) }
                }
            }
        }
        // A phone's width: one under the other.
        is RecipeBlock.Columns -> Column(verticalArrangement = Arrangement.spacedBy(Space.m)) { for (col in b.columns) Blocks(col, ctx) }
        is RecipeBlock.Tabs -> {
            var on by remember(b.id) { mutableStateOf(0) }
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    b.tabs.forEachIndexed { i, tab ->
                        val sel = i == on
                        Text(
                            tab.label, style = t.labelLarge, color = if (sel) c.strong else c.muted,
                            modifier = Modifier.clip(HerdrShapes.Pill).background(if (sel) c.hover else Color.Transparent)
                                .clickable { on = i }.heightIn(min = 40.dp).padding(horizontal = Space.m, vertical = Space.s),
                        )
                    }
                }
                b.tabs.getOrNull(on)?.let { Blocks(it.blocks, ctx) }
            }
        }
        is RecipeBlock.Details -> {
            var open by remember(b.id) { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Row(Modifier.fillMaxWidth().clickable { open = !open }.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (open) HerdrIcons.ChevronDown else HerdrIcons.ChevronRight, null, Modifier.size(16.dp), tint = c.muted)
                    Spacer(Modifier.width(Space.xs))
                    Text(b.summary, style = t.titleSmall, color = c.text)
                }
                if (open) Column(Modifier.padding(start = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) { Blocks(b.blocks, ctx) }
            }
        }
        is RecipeBlock.Divider -> HorizontalDivider(color = c.line)
        is RecipeBlock.Choice -> Choice(b, ctx)
        is RecipeBlock.Form -> Form(b, ctx)
        is RecipeBlock.Button -> SecondaryButton(b.label, enabled = !ctx.markup) { ctx.onAnswer(b.reply) }
        is RecipeBlock.Unknown -> Text(
            "A part this app doesn't draw yet" + (b.type.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()) + ".",
            style = t.bodySmall, color = c.muted,
        )
    }
}

@Composable
private fun Table(b: RecipeBlock.Table) {
    val c = Herdr.colors
    val t = MaterialTheme.typography
    Column(
        Modifier.fillMaxWidth().clip(HerdrShapes.Field).hairline(HerdrShapes.Field, c.line).horizontalScroll(rememberScrollState())
            .width(androidx.compose.foundation.layout.IntrinsicSize.Max),
    ) {
        @Composable
        fun row(cells: List<String>, head: Boolean) = Row(Modifier.fillMaxWidth().background(if (head) c.card else Color.Transparent)) {
            b.columns.indices.forEach { i ->
                val right = b.columns[i].align == "right"
                Text(
                    cells.getOrNull(i).orEmpty(), style = if (head) t.labelLarge else t.bodyMedium, color = if (head) c.muted else c.text,
                    textAlign = if (right) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.widthIn(min = 96.dp, max = 220.dp).padding(horizontal = Space.m, vertical = Space.s),
                )
            }
        }
        row(b.columns.map { it.label }, head = true)
        for (r in b.rows) {
            HorizontalDivider(color = c.line)
            row(r, head = false)
        }
    }
}

/** Bars and a line on a shared scale, a pie as its slices; each series in its own colour, a legend under it. */
@Composable
private fun Chart(b: RecipeBlock.Chart) {
    val c = Herdr.colors
    val t = MaterialTheme.typography
    val palette = listOf(c.accent, c.done, c.working, c.red, c.idle)
    val words = when (b.kind) {
        "pie" -> Recipes.slices(b).joinToString(", ") { (l, v, _) -> "$l ${num(v)}" }
        else -> b.series.joinToString("; ") { s -> s.name + ": " + s.values.mapIndexed { i, v -> "${b.labels.getOrNull(i).orEmpty()} ${num(v)}" }.joinToString(", ") }
    }
    Column(Modifier.semantics { contentDescription = "Chart: $words" }, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        when (b.kind) {
            "pie" -> {
                val slices = Recipes.slices(b)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(132.dp)) {
                        var at = -90f
                        slices.forEachIndexed { i, (_, _, share) ->
                            val sweep = (share * 360).toFloat()
                            drawArc(palette[i % palette.size], at, sweep, useCenter = true)
                            at += sweep
                        }
                    }
                    Spacer(Modifier.width(Space.l))
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        slices.forEachIndexed { i, (label, v, share) -> Legend(palette[i % palette.size], "$label · ${num(v)} (${Math.round(share * 100)}%)") }
                    }
                }
            }
            "line" -> {
                val max = maxOf(1e-9, b.series.flatMap { it.values }.maxOfOrNull { maxOf(0.0, it) } ?: 0.0)
                Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                    drawLine(c.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                    b.series.forEachIndexed { si, s ->
                        val p = Path()
                        s.values.forEachIndexed { i, v ->
                            val x = if (s.values.size > 1) size.width * i / (s.values.size - 1) else size.width / 2
                            val y = size.height * (1 - (maxOf(0.0, v) / max).toFloat())
                            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                        }
                        drawPath(p, palette[si % palette.size], style = Stroke(2.dp.toPx()))
                    }
                }
                Labels(b.labels)
            }
            else -> {
                val bars = Recipes.bars(b)
                val groups = maxOf(1, b.labels.size, b.series.maxOfOrNull { it.values.size } ?: 0)
                val per = maxOf(1, b.series.size)
                Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                    val slot = size.width / groups
                    val w = slot * 0.7f / per
                    drawLine(c.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                    bars.forEach { bar ->
                        val h = (size.height * bar.h).toFloat()
                        drawRect(palette[bar.series % palette.size], Offset(bar.i * slot + slot * 0.15f + bar.series * w, size.height - h), Size(w, h))
                    }
                }
                Labels(b.labels)
            }
        }
        if (b.kind != "pie" && b.series.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) { b.series.forEachIndexed { i, s -> Legend(palette[i % palette.size], s.name) } }
        }
        if (b.kind != "pie" && b.series.size == 1) Text(words, style = t.labelMedium, color = c.muted)
    }
}

@Composable
private fun Labels(labels: List<String>) {
    if (labels.isEmpty()) return
    Row(Modifier.fillMaxWidth()) {
        for (l in labels) Text(l, style = MaterialTheme.typography.labelMedium, color = Herdr.colors.muted, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Legend(color: Color, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    Spacer(Modifier.width(Space.xs))
    Text(text, style = MaterialTheme.typography.labelMedium, color = Herdr.colors.text)
}

private fun num(v: Double): String = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else "%.1f".format(v)

@Composable
private fun Picture(b: RecipeBlock.Image, ctx: Ctx) {
    val c = Herdr.colors
    val image = ctx.picture(b.src)
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        val ratio = image?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }
            ?: if ((b.w ?: 0) > 0 && (b.h ?: 0) > 0) b.w!!.toFloat() / b.h!! else 16f / 10
        Box(
            Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.3f, 4f)).clip(HerdrShapes.Tile).background(c.card).hairline(HerdrShapes.Tile, c.line),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) Image(image, b.caption, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
            else Icon(HerdrIcons.Image, null, Modifier.size(28.dp), tint = c.muted)
        }
        b.caption?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = c.muted) }
    }
}

@Composable
private fun Choice(b: RecipeBlock.Choice, ctx: Ctx) {
    val c = Herdr.colors
    val t = MaterialTheme.typography
    var sent by remember(b.id) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(b.question, style = t.titleMedium, color = c.strong)
        for (o in b.options) {
            val on = sent == o.label
            Column(
                Modifier.fillMaxWidth().clip(HerdrShapes.Field).background(if (on) c.accentTint else c.card)
                    .hairline(HerdrShapes.Field, if (on) c.accent else c.line)
                    .clickable(enabled = !ctx.markup && sent == null) {
                        sent = o.label
                        ctx.onAnswer(Recipes.choiceReply(b, o.label))
                    }
                    .heightIn(min = 48.dp).padding(horizontal = Space.m, vertical = Space.s),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(o.label, style = t.bodyLarge, color = c.text)
                o.description?.let { Text(it, style = t.bodySmall, color = c.muted) }
            }
        }
        sent?.let { Text("Sent: $it", style = t.labelMedium, color = c.muted) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Form(b: RecipeBlock.Form, ctx: Ctx) {
    val c = Herdr.colors
    val t = MaterialTheme.typography
    val values = remember(b.id) { mutableStateMapOf<String, Any?>() }
    var sent by remember(b.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(HerdrShapes.Card).background(c.card).hairline(HerdrShapes.Card, c.line).padding(Space.card),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        b.title?.let { Text(it, style = t.titleMedium, color = c.strong) }
        for (f in b.fields) {
            val label = f.label ?: f.name
            when (f.kind) {
                "checkbox" -> Row(
                    Modifier.fillMaxWidth().clickable(enabled = !ctx.markup) { values[f.name] = values[f.name] != true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(values[f.name] == true, onCheckedChange = { values[f.name] = it }, enabled = !ctx.markup)
                    Text(label, style = t.bodyLarge, color = c.text)
                }
                "select" -> Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(label, style = t.labelLarge, color = c.muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        for (o in f.options) {
                            val on = values[f.name] == o
                            Text(
                                o, style = t.labelLarge, color = if (on) c.strong else c.text,
                                modifier = Modifier.clip(HerdrShapes.Pill).background(if (on) c.accentTint else c.input)
                                    .hairline(HerdrShapes.Pill, if (on) c.accent else c.line)
                                    .clickable(enabled = !ctx.markup) { values[f.name] = o }
                                    .heightIn(min = 36.dp).padding(horizontal = Space.m, vertical = Space.s),
                            )
                        }
                    }
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(label, style = t.labelLarge, color = c.muted)
                    BasicTextField(
                        value = values[f.name] as? String ?: "",
                        onValueChange = { values[f.name] = it },
                        enabled = !ctx.markup,
                        singleLine = f.kind != "textarea",
                        textStyle = t.bodyLarge.copy(color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        modifier = Modifier.fillMaxWidth().clip(HerdrShapes.Field).background(c.input).hairline(HerdrShapes.Field, c.line)
                            .heightIn(min = if (f.kind == "textarea") 96.dp else 44.dp).padding(horizontal = Space.m, vertical = Space.s),
                    )
                }
            }
        }
        PrimaryButton(if (sent) "Sent" else b.submit ?: "Send", enabled = !ctx.markup && !sent) {
            sent = true
            ctx.onAnswer(Recipes.formReply(b, values))
        }
    }
}
