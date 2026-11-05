package com.makefunstuff.herdr.ui.images

import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.makefunstuff.herdr.core.host.ImageSizing
import com.makefunstuff.herdr.core.host.Markup
import com.makefunstuff.herdr.data.TrayImage
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.Spinner
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** What the editor's bar offers: cropping, or one of the marks ([Markup.Tool]). */
enum class EditTool(val label: String, val mark: Markup.Tool?) {
    Crop("Crop", null), Pen("Draw", Markup.Tool.Pen), Arrow("Arrow", Markup.Tool.Arrow), Highlight("Highlight", Markup.Tool.Highlight),
}

/** The marks' colours, each with its name for a screen reader: bright enough to read on a screenshot, light or dark. */
private val Inks = listOf(
    0xFFFF3B30 to "Red", 0xFFFFCC00 to "Yellow", 0xFF0A84FF to "Blue", 0xFF34C759 to "Green", 0xFFFFFFFF to "White", 0xFF000000 to "Black",
)

/**
 * A picture in the composer, before it is sent: shown whole, with what you can do to it. **Crop**
 * keeps a part (drag the frame's corners, edges or middle); **Draw**, **Arrow** and **Highlight**
 * mark it up, each in a colour of its own. **Done** writes the picture as it now looks in place of
 * the old one ([onSave], which copies it to the machine again); the bin takes it out of the
 * message ([onRemove]); closing leaves it as it was.
 */
@Composable
fun ImageEditor(image: TrayImage, onSave: (TrayImage) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit, initialTool: EditTool? = null) {
    val c = Herdr.colors
    val store = LocalContainer.current.images
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap = rememberImage(image.path, ImageSizing.MAX_EDGE)
    var tool by remember { mutableStateOf(initialTool) }
    var marks by remember { mutableStateOf(emptyList<Markup.Mark>()) }
    var current by remember { mutableStateOf<Markup.Mark?>(null) }
    var crop by remember { mutableStateOf(Markup.Crop()) }
    var inks by remember { mutableStateOf(mapOf(Markup.Tool.Pen to Inks[0].first, Markup.Tool.Arrow to Inks[0].first, Markup.Tool.Highlight to Inks[1].first)) }
    var saving by remember { mutableStateOf(false) }
    var downAt by remember { mutableStateOf<Offset?>(null) }
    val changed = marks.isNotEmpty() || !crop.isWhole

    fun done() {
        val bmp = bitmap
        if (!changed || bmp == null) return onDismiss()
        saving = true
        scope.launch {
            try {
                val edited = withContext(Dispatchers.Default) { render(bmp, crop, marks) }
                val format = ImageSizing.format(null, image.name)
                val file = withContext(Dispatchers.IO) { store.newFile(format.extension).also { ImageCodec.save(edited, it, format) } }
                onSave(image.copy(name = ImageSizing.rename(image.name, format), path = file.path, width = edited.width, height = edited.height, remote = null))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                context.toast(t.message ?: "Couldn't save the picture")
                saving = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(c.ground)) {
            // The bar: close, what this is, undo, take it out, Done.
            Row(
                Modifier.fillMaxWidth().background(c.panel).statusBarsPadding().padding(start = Space.xs, end = Space.m, top = Space.xs, bottom = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarIcon(HerdrIcons.X, "Close without changing it", onDismiss)
                Column(Modifier.weight(1f).padding(start = Space.xs)) {
                    Text("Picture", style = MaterialTheme.typography.titleMedium, color = c.strong, maxLines = 1)
                    val kept = bitmap?.let { Markup.cropPixels(crop, it.width, it.height) }
                    Text(
                        listOfNotNull(image.name, kept?.let { "${it[2]} × ${it[3]}" }).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                BarIcon(HerdrIcons.Undo2, "Undo", {
                    when {
                        marks.isNotEmpty() -> marks = marks.dropLast(1)
                        !crop.isWhole -> crop = Markup.Crop()
                    }
                }, enabled = changed && !saving)
                BarIcon(HerdrIcons.Trash2, "Remove from the message", onRemove, enabled = !saving)
                Spacer(Modifier.width(Space.xs))
                if (saving) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Spinner(size = 20.dp) }
                else PrimaryButton("Done", enabled = bitmap != null, onClick = ::done)
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(Space.l), contentAlignment = Alignment.Center) {
                val bmp = bitmap
                if (bmp == null) {
                    Spinner()
                    return@BoxWithConstraints
                }
                // Cropping shows the whole picture with its frame; otherwise the part kept fills the space.
                val cropping = tool == EditTool.Crop
                val region = if (cropping) Markup.Crop() else crop
                val boxW = constraints.maxWidth.toFloat()
                val boxH = constraints.maxHeight.toFloat()
                val scale = min(boxW / (bmp.width * region.width), boxH / (bmp.height * region.height))
                val shownW = bmp.width * region.width * scale
                val shownH = bmp.height * region.height * scale
                val left = (boxW - shownW) / 2
                val top = (boxH - shownH) / 2
                // A point of the picture on screen, and back.
                val fullW = bmp.width * scale
                val fullH = bmp.height * scale
                fun onScreen(p: Markup.Spot) = Offset(left + (p.x - region.left) * fullW, top + (p.y - region.top) * fullH)
                fun onPicture(o: Offset) = Markup.Spot(
                    (region.left + (o.x - left) / fullW).coerceIn(0f, 1f),
                    (region.top + (o.y - top) / fullH).coerceIn(0f, 1f),
                )
                val unit = max(bmp.width, bmp.height) * scale
                val reach = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.toPx() }
                val frameColor = Color.White
                Canvas(
                    Modifier.fillMaxSize()
                        .semantics { contentDescription = "The picture" + if (marks.isEmpty()) "" else ", with ${marks.size} marks" }
                        // Where the finger went down: a drag is only known once it has moved a little, and a
                        // stroke starts, or a corner is taken, where the finger touched rather than there.
                        .pointerInput(Unit) { awaitEachGesture { downAt = awaitFirstDown(requireUnconsumed = false).position } }
                        .pointerInput(tool, inks, region, scale, left, top) {
                            var handle: Markup.Handle? = null
                            detectDragGestures(
                                onDragStart = { moved ->
                                    val o = downAt ?: moved
                                    val t = tool
                                    when {
                                        t == EditTool.Crop -> handle = Markup.handleAt(crop, fullW, fullH, o.x - left, o.y - top, reach)
                                        t?.mark != null -> current = Markup.Mark(t.mark, inks.getValue(t.mark), listOf(onPicture(o)))
                                    }
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    val t = tool
                                    val m = current
                                    when {
                                        t == EditTool.Crop -> handle?.let { crop = Markup.drag(crop, it, amount.x / fullW, amount.y / fullH) }
                                        m != null && m.tool == Markup.Tool.Arrow -> current = m.copy(points = listOf(m.points.first(), onPicture(change.position)))
                                        m != null -> current = m.copy(points = Markup.extend(m.points, onPicture(change.position), 2f / unit))
                                    }
                                },
                                onDragEnd = {
                                    current?.let { marks = marks + it }
                                    current = null
                                    handle = null
                                },
                                onDragCancel = {
                                    current = null
                                    handle = null
                                },
                            )
                        },
                ) {
                    clipRect(left, top, left + shownW, top + shownH) {
                        val l = (region.left * bmp.width).toInt()
                        val t = (region.top * bmp.height).toInt()
                        drawImage(
                            bmp,
                            srcOffset = IntOffset(l, t),
                            srcSize = IntSize((region.width * bmp.width).toInt().coerceIn(1, bmp.width - l), (region.height * bmp.height).toInt().coerceIn(1, bmp.height - t)),
                            dstOffset = IntOffset(left.toInt(), top.toInt()),
                            dstSize = IntSize(shownW.toInt(), shownH.toInt()),
                        )
                        drawMarks(marks + listOfNotNull(current), ::onScreen, unit)
                    }
                    if (cropping) drawCropFrame(onScreen(Markup.Spot(crop.left, crop.top)), onScreen(Markup.Spot(crop.right, crop.bottom)), Offset(left, top), Size(shownW, shownH), frameColor)
                }
            }

            // The tools, and the chosen one's colours or what it does.
            Column(Modifier.fillMaxWidth().background(c.panel).navigationBarsPadding().padding(horizontal = Space.m, vertical = Space.xs)) {
                val t = tool
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        t == EditTool.Crop -> {
                            Text("Drag the frame's corners or edges", style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.weight(1f))
                            QuietButton(onClick = { crop = Markup.Crop() }, enabled = !crop.isWhole) { Text("Reset") }
                        }
                        t?.mark != null -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                            Inks.forEach { (ink, name) ->
                                Swatch(Color(ink), name, selected = inks[t.mark] == ink) { inks = inks + (t.mark to ink) }
                            }
                        }
                        else -> Text(
                            "Choose a tool to cut it down or to mark what the agent should look at",
                            style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.weight(1f).padding(horizontal = Space.xs),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    EditTool.entries.forEach { e ->
                        ToolButton(
                            when (e) {
                                EditTool.Crop -> HerdrIcons.Crop
                                EditTool.Pen -> HerdrIcons.PenLine
                                EditTool.Arrow -> HerdrIcons.MoveUpRight
                                EditTool.Highlight -> HerdrIcons.Highlighter
                            },
                            e.label, selected = tool == e, enabled = !saving,
                        ) { tool = if (tool == e) null else e }
                    }
                }
            }
        }
    }
}

/** One tool in the editor's bar: its glyph over its name; the chosen one on `hover` in strong text, as a chosen chip. */
@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Herdr.colors
    val tint = when {
        !enabled -> c.off
        selected -> c.strong
        else -> c.muted
    }
    Column(
        Modifier.width(76.dp).heightIn(min = 56.dp).clip(com.makefunstuff.herdr.ui.design.HerdrShapes.Field)
            .background(if (selected) c.hover else Color.Transparent)
            .selectable(selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(vertical = Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1, modifier = Modifier.padding(top = Space.xxs))
    }
}

/** A colour to draw in: a 28dp dot in a 48dp target, the chosen one ringed. */
@Composable
private fun Swatch(color: Color, name: String, selected: Boolean, onClick: () -> Unit) {
    val c = Herdr.colors
    Box(
        Modifier.size(48.dp).clip(RectangleShape).selectable(selected, role = Role.RadioButton, onClick = onClick).semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(34.dp).border(2.dp, if (selected) c.strong else Color.Transparent, RectangleShape).padding(4.dp)
                .background(color, RectangleShape).border(1.dp, c.lineStrong, RectangleShape),
        )
    }
}

/** The marks, each point placed by [at]; [unit] is the picture's long side as drawn, which a mark's thickness is a part of. */
internal fun DrawScope.drawMarks(marks: List<Markup.Mark>, at: (Markup.Spot) -> Offset, unit: Float) {
    for (m in marks) {
        if (m.points.isEmpty()) continue
        val width = Markup.thickness(m.tool) * unit
        val ink = Color(m.color).let { if (m.tool == Markup.Tool.Highlight) it.copy(alpha = Markup.HIGHLIGHT_ALPHA) else it }
        val pts = m.points.map(at)
        when (m.tool) {
            Markup.Tool.Arrow -> {
                val from = pts.first()
                val to = pts.last()
                val head = Markup.arrowHead(from.x, from.y, to.x, to.y, width * 4.5f) ?: continue
                drawLine(ink, from, to, width, StrokeCap.Round)
                drawLine(ink, to, Offset(head.first.x, head.first.y), width, StrokeCap.Round)
                drawLine(ink, to, Offset(head.second.x, head.second.y), width, StrokeCap.Round)
            }
            else -> {
                if (pts.size == 1) {
                    drawCircle(ink, width / 2, pts.first())
                    continue
                }
                // Through the midpoints, so a finger's line comes out smooth.
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) {
                        val mid = (pts[i - 1] + pts[i]) / 2f
                        quadraticTo(pts[i - 1].x, pts[i - 1].y, mid.x, mid.y)
                    }
                    lineTo(pts.last().x, pts.last().y)
                }
                drawPath(path, ink, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** The crop frame from [from] to [to] over the picture shown at [origin], [size]: what goes is dimmed, the frame has corners to hold. */
private fun DrawScope.drawCropFrame(from: Offset, to: Offset, origin: Offset, size: Size, color: Color) {
    val dim = Color.Black.copy(alpha = 0.55f)
    val right = origin.x + size.width
    val bottom = origin.y + size.height
    drawRect(dim, origin, Size(size.width, from.y - origin.y))
    drawRect(dim, Offset(origin.x, to.y), Size(size.width, bottom - to.y))
    drawRect(dim, Offset(origin.x, from.y), Size(from.x - origin.x, to.y - from.y))
    drawRect(dim, Offset(to.x, from.y), Size(right - to.x, to.y - from.y))
    val line = 1.dp.toPx()
    drawRect(color, from, Size(to.x - from.x, to.y - from.y), style = Stroke(line * 1.5f))
    // Thirds, faint, to place what matters.
    for (i in 1..2) {
        val x = from.x + (to.x - from.x) * i / 3f
        val y = from.y + (to.y - from.y) * i / 3f
        drawLine(color.copy(alpha = 0.35f), Offset(x, from.y), Offset(x, to.y), line)
        drawLine(color.copy(alpha = 0.35f), Offset(from.x, y), Offset(to.x, y), line)
    }
    val arm = 18.dp.toPx()
    val thick = 4.dp.toPx()
    for ((cx, cy) in listOf(from.x to from.y, to.x to from.y, from.x to to.y, to.x to to.y)) {
        val dx = if (cx == from.x) arm else -arm
        val dy = if (cy == from.y) arm else -arm
        drawLine(color, Offset(cx, cy), Offset(cx + dx, cy), thick, StrokeCap.Square)
        drawLine(color, Offset(cx, cy), Offset(cx, cy + dy), thick, StrokeCap.Square)
    }
}

/** [image] as it is to be sent: the part [crop] keeps, with [marks] drawn on it, at its own pixels. */
internal fun render(image: ImageBitmap, crop: Markup.Crop, marks: List<Markup.Mark>): ImageBitmap {
    val (l, t, w, h) = Markup.cropPixels(crop, image.width, image.height).toList()
    val out = ImageBitmap(w, h)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(out), Size(w.toFloat(), h.toFloat())) {
        drawImage(image, srcOffset = IntOffset(l, t), srcSize = IntSize(w, h), dstOffset = IntOffset.Zero, dstSize = IntSize(w, h))
        drawMarks(marks, { p -> Offset(p.x * image.width - l, p.y * image.height - t) }, max(image.width, image.height).toFloat())
    }
    return out
}
