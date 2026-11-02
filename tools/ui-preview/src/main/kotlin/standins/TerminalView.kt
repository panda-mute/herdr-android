// Stand-in for app/…/ui/terminal/TerminalView.kt (an Android View with touch, IME and an Android
// Canvas): the same API, drawing the same grid with the shared TerminalPainter onto Skia.
package com.makefunstuff.herdr.ui.terminal

import android.content.Context
import android.graphics.Typeface
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.makefunstuff.herdr.core.terminal.TerminalCanvas
import com.makefunstuff.herdr.core.terminal.TerminalEmulator
import com.makefunstuff.herdr.core.terminal.TerminalPainter
import com.makefunstuff.herdr.core.terminal.TerminalPalette
import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

class TerminalView(context: Context) : View(context) {
    var emulator: TerminalEmulator? = null
        set(value) { field = value; invalidate() }

    var onInput: (String) -> Unit = {}
    var onGridSize: (cols: Int, rows: Int) -> Unit = { _, _ -> }
    var onScroll: (lines: Int) -> Unit = {}
    var onLongPress: () -> Unit = {}
    var onCellTap: (CellPoint) -> Unit = {}
    var onCellLongPress: (CellPoint) -> Unit = {}
    var selection: Pair<CellPoint, CellPoint>? = null
        set(value) { field = value; invalidate() }
    var selecting = false
    var fitToView = true
        set(value) { field = value; reportGrid(); invalidate() }
    var ctrlSticky = false
    var altSticky = false
    var onModifiersConsumed: () -> Unit = {}
    var onFontSizeChanged: (Float) -> Unit = {}

    private val density = preview.SCALE
    private var fontSizePx = 11f * density
    private var face: org.jetbrains.skia.Typeface? = null
    private var font = Font(null, fontSizePx)
    private var cellW = 1f
    private var cellH = 1f
    private var baseline = 0f
    private val painter = TerminalPainter()
    private var selectionColor = 0x554A90D9

    val fontSizeSp: Float get() = fontSizePx / density

    init {
        updateMetrics()
        // The newest terminal: the screen's live one until a swipe opens its history.
        preview.PreviewTerminal.live = this
    }

    fun setTypeface(face: Typeface) {
        val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream(face.file)?.readBytes() ?: return
        this.face = FontMgr.default.makeFromData(Data.makeFromBytes(bytes))
        updateMetrics(); reportGrid(); invalidate()
    }

    fun setColors(palette: TerminalPalette, selection: Int) {
        painter.palette = palette
        selectionColor = (selection and 0xFFFFFF) or (0x99 shl 24)
        invalidate()
    }

    fun setFontSizeSp(sp: Float) {
        fontSizePx = sp * density
        updateMetrics(); reportGrid(); invalidate()
    }

    private fun updateMetrics() {
        font = Font(face, fontSizePx)
        cellW = font.measureTextWidth("M")
        val m = font.metrics
        cellH = ceil(((m.descent - m.ascent) * 1.1f).toDouble()).toFloat()
        baseline = -m.ascent + (cellH - (m.descent - m.ascent)) / 2f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = reportGrid()

    private fun reportGrid() {
        if (!fitToView || width == 0 || height == 0) return
        val cols = max(10, floor((width - 2 * PAD * density) / cellW).toInt())
        val rows = max(4, floor((height - 2 * PAD * density) / cellH).toInt())
        onGridSize(cols, rows)
    }

    fun startSelection(cell: CellPoint) {
        selecting = true
        // A scene can't drag across the grid: it gives the selection to show ([preview.PreviewTerminal]).
        selection = preview.PreviewTerminal.selection ?: (cell to cell)
    }

    /** Shows the grid's last line when it is taller than the view (the phone's own history). */
    private var atBottom = false
    fun panToBottom() { atBottom = true; invalidate() }

    fun clearSelection() {
        selecting = false
        selection = null
    }

    fun showKeyboard() {}

    fun screenText(): String = emulator?.let { synchronized(it.lock) { it.screenText() } } ?: ""

    @Composable
    override fun Content(modifier: Modifier) {
        Canvas(modifier.clipToBounds()) {
            drawRevision.longValue // redraw on invalidate()
            drawIntoCanvas { c ->
                val canvas = c.nativeCanvas
                canvas.clear(painter.palette.background)
                val emu = emulator ?: return@drawIntoCanvas
                val pad = PAD * density
                synchronized(emu.lock) {
                    canvas.save()
                    val panY = if (atBottom) max(0f, emu.rows * cellH + 2 * pad - size.height) else 0f
                    canvas.translate(pad, pad - panY)
                    painter.paint(emu, Surface(canvas), cellW, cellH, baseline) { drawSelection(canvas, emu) }
                    canvas.restore()
                }
            }
        }
    }

    private fun drawSelection(canvas: org.jetbrains.skia.Canvas, emu: TerminalEmulator) {
        val (a, b) = selection ?: return
        val first = if (a.row < b.row || (a.row == b.row && a.col <= b.col)) a else b
        val last = if (first === a) b else a
        val paint = Paint().apply { color = selectionColor }
        for (row in first.row..last.row) {
            val from = if (row == first.row) first.col else 0
            val to = if (row == last.row) last.col else emu.cols - 1
            if (to < from) continue
            canvas.drawRect(Rect.makeLTRB(from * cellW, row * cellH, (to + 1) * cellW, (row + 1) * cellH), paint)
        }
    }

    private inner class Surface(val canvas: org.jetbrains.skia.Canvas) : TerminalCanvas {
        private val fill = Paint()
        private val text = Paint().apply { isAntiAlias = true }

        override fun fillRect(left: Float, top: Float, right: Float, bottom: Float, argb: Int) {
            fill.color = argb
            canvas.drawRect(Rect.makeLTRB(left, top, right, bottom), fill)
        }

        override fun drawText(text: String, x: Float, baseline: Float, maxWidth: Float, argb: Int, bold: Boolean, italic: Boolean) {
            this.text.color = argb
            // Symbols the monospace font lacks come from a fallback font, as Android's Paint does.
            val cp = text.codePointAt(0)
            val missing = face != null && Font(face, fontSizePx).getUTF32Glyph(cp) == 0.toShort()
            val use = if (missing) FontMgr.default.matchFamilyStyleCharacter(null, org.jetbrains.skia.FontStyle.NORMAL, null, cp) ?: face else face
            val f = Font(use, fontSizePx).apply {
                isEmboldened = bold
                skewX = if (italic) -0.2f else 0f
                val w = measureTextWidth(text)
                if (w > maxWidth * 1.05f && w > 0f) scaleX = maxWidth / w
            }
            canvas.drawString(text, x, baseline, f, this.text)
        }
    }

    companion object {
        private const val PAD = 4f
    }
}

/** A cell in the pane's viewport: `row` 0 is the top line on screen, `col` 0 the first column. */
data class CellPoint(val row: Int, val col: Int)
