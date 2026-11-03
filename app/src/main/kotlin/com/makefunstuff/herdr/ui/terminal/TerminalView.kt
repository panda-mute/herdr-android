package com.makefunstuff.herdr.ui.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.makefunstuff.herdr.core.terminal.TerminalCanvas
import com.makefunstuff.herdr.core.terminal.TerminalPainter
import com.makefunstuff.herdr.core.terminal.TerminalPalette
import com.makefunstuff.herdr.core.terminal.Keys
import com.makefunstuff.herdr.core.terminal.TerminalEmulator
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a [TerminalEmulator] and turns touch and keyboard input into terminal bytes.
 *
 * In "fit" mode the grid follows the view size and [onGridSize] reports it. Otherwise the grid
 * keeps the host's size and the user pans across it.
 */
@SuppressLint("ViewConstructor")
class TerminalView(context: Context) : View(context) {
    var emulator: TerminalEmulator? = null
        set(value) { field = value; invalidate() }

    /** Called with text or escape sequences to send to the pane. */
    var onInput: (String) -> Unit = {}
    /** Called with the grid that fits the view, in fit mode. */
    var onGridSize: (cols: Int, rows: Int) -> Unit = { _, _ -> }
    /** Called for vertical drags in fit mode: positive lines scroll back. */
    var onScroll: (lines: Int) -> Unit = {}
    var onLongPress: () -> Unit = {}
    /** Called for a tap that lands on a cell (for links and the keyboard). */
    var onCellTap: (CellPoint) -> Unit = {}
    /** Called for a long press on a cell: the screen starts a text selection there. */
    var onCellLongPress: (CellPoint) -> Unit = {}

    /** Anchor and cursor of the current selection, in viewport cells. Drawn while set. */
    var selection: Pair<CellPoint, CellPoint>? = null
        set(value) { field = value; invalidate() }

    /** True while a selection is being made: drags move its cursor instead of scrolling. */
    var selecting = false
        private set

    var fitToView = true
        set(value) { field = value; panX = 0f; reportGrid(); invalidate() }

    /** Sticky modifiers from the extra-keys row, consumed by the next typed key. */
    var ctrlSticky = false
    var altSticky = false
    var onModifiersConsumed: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private var fontSizePx = 11f * resources.displayMetrics.scaledDensity
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }

    /** The face the grid is drawn in (the app's JetBrains Mono); bold is the same face, emboldened. */
    fun setTypeface(face: Typeface) {
        textPaint.typeface = face
        boldPaint.typeface = Typeface.create(face, Typeface.BOLD)
        updateMetrics()
        reportGrid()
        invalidate()
    }

    /** The theme's colours: ground, text, cursor and ANSI colours, and the selection's fill. */
    fun setColors(palette: TerminalPalette, selection: Int) {
        painter.palette = palette
        selectionColor = (selection and 0xFFFFFF) or (0x99 shl 24)
        invalidate()
    }

    private var selectionColor = 0x554A90D9
    private val bgPaint = Paint()
    private var cellW = 1f
    private var cellH = 1f
    private var baseline = 0f
    private var panX = 0f
    private var panY = 0f
    private var scrollAccum = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        updateMetrics()
    }

    /** Called after a pinch changes the text size, with the new size in sp. */
    var onFontSizeChanged: (Float) -> Unit = {}

    val fontSizeSp: Float get() = fontSizePx / resources.displayMetrics.scaledDensity

    fun setFontSizeSp(sp: Float) {
        fontSizePx = sp * resources.displayMetrics.scaledDensity
        updateMetrics()
        reportGrid()
        invalidate()
    }

    private fun updateMetrics() {
        textPaint.textSize = fontSizePx
        boldPaint.textSize = fontSizePx
        cellW = textPaint.measureText("M")
        val fm = textPaint.fontMetrics
        cellH = Math.ceil(((fm.descent - fm.ascent) * 1.1f).toDouble()).toFloat()
        baseline = -fm.ascent + (cellH - (fm.descent - fm.ascent)) / 2f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reportGrid()
    }

    private fun reportGrid() {
        if (!fitToView || width == 0 || height == 0) return
        val cols = max(10, floor((width - 2 * PAD * density) / cellW).toInt())
        val rows = max(4, floor((height - 2 * PAD * density) / cellH).toInt())
        onGridSize(cols, rows)
    }

    /**
     * Starts a text selection at [cell]: taps move its cursor, drags extend it, and the screen
     * reads [selection] back to copy. Ends with [clearSelection].
     */
    fun startSelection(cell: CellPoint) {
        selecting = true
        selection = cell to cell
    }

    /** Pans to the grid's last line, for a grid taller than the view (the phone's own history). */
    fun panToBottom() {
        panY = Float.MAX_VALUE
        invalidate()
    }

    fun clearSelection() {
        selecting = false
        selection = null
    }

    /** The cell under a touch, or null in the padding or past the grid. */
    private fun cellAt(x: Float, y: Float): CellPoint? {
        val emu = emulator ?: return null
        val pad = PAD * density
        val col = floor((x - pad + panX) / cellW).toInt()
        val row = floor((y - pad + panY) / cellH).toInt()
        if (col < 0 || row < 0 || col >= emu.cols || row >= emu.rows) return null
        return CellPoint(row, col)
    }

    // ---- Drawing ------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        // Compose draws this view unclipped: the fill and a panned grid would paint over what's above it.
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(painter.palette.background)
        val emu = emulator ?: return
        val pad = PAD * density
        synchronized(emu.lock) {
            clampPan(emu)
            canvas.save()
            canvas.translate(Math.round(pad - panX).toFloat(), Math.round(pad - panY).toFloat())
            surface.canvas = canvas
            // The selection on the grid's ground, under the text (the painter fills the ground first).
            painter.paint(emu, surface, cellW, cellH, baseline) { drawSelection(canvas, emu) }
            drawComposing(canvas, emu)
            surface.canvas = null
            canvas.restore()
        }
    }

    private fun clampPan(emu: TerminalEmulator) {
        val pad = PAD * density
        val maxX = max(0f, emu.cols * cellW + 2 * pad - width)
        val maxY = max(0f, emu.rows * cellH + 2 * pad - height)
        panX = panX.coerceIn(0f, maxX)
        panY = panY.coerceIn(0f, maxY)
    }

    private val painter = TerminalPainter()
    private val surface = AndroidSurface()
    private val selectionPaint = Paint()

    /** A translucent block over the selected cells, under the terminal's own text. */
    private fun drawSelection(canvas: Canvas, emu: TerminalEmulator) {
        val (a, b) = selection ?: return
        val first = if (a.row < b.row || (a.row == b.row && a.col <= b.col)) a else b
        val last = if (first === a) b else a
        selectionPaint.color = selectionColor
        for (row in first.row..last.row) {
            val from = if (row == first.row) first.col else 0
            val to = if (row == last.row) last.col else emu.cols - 1
            if (to < from) continue
            canvas.drawRect(from * cellW, row * cellH, (to + 1) * cellW, (row + 1) * cellH, selectionPaint)
        }
    }

    /** Adapts the shared painter to an Android canvas. */
    private inner class AndroidSurface : TerminalCanvas {
        var canvas: Canvas? = null

        override fun fillRect(left: Float, top: Float, right: Float, bottom: Float, argb: Int) {
            bgPaint.color = argb
            canvas?.drawRect(left, top, right, bottom, bgPaint)
        }

        override fun drawText(text: String, x: Float, baseline: Float, maxWidth: Float, argb: Int, bold: Boolean, italic: Boolean) {
            val paint = if (bold) boldPaint else textPaint
            paint.color = argb
            paint.textSkewX = if (italic) -0.2f else 0f
            // Symbols the monospace font lacks come from wider fallback fonts; squeeze them into the cell.
            val w = paint.measureText(text)
            paint.textScaleX = if (w > maxWidth * 1.05f && w > 0f) maxWidth / w else 1f
            canvas?.drawText(text, x, baseline, paint)
            paint.textScaleX = 1f
        }
    }

    // ---- Touch -------------------------------------------------------------------------

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val sp = fontSizePx / resources.displayMetrics.scaledDensity
            setFontSizeSp((sp * detector.scaleFactor).coerceIn(com.makefunstuff.herdr.data.TextSizeStore.TERMINAL_RANGE))
            onFontSizeChanged(fontSizeSp)
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val cell = cellAt(e.x, e.y)
            if (cell == null) {
                showKeyboard()
            } else if (selecting) {
                selection = (selection?.first ?: cell) to cell
            } else {
                onCellTap(cell)
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val cell = cellAt(e.x, e.y)
            if (cell != null) onCellLongPress(cell) else onLongPress()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleDetector.isInProgress) return true
            if (selecting) {
                val cell = cellAt(e2.x, e2.y) ?: return true
                selection = (selection?.first ?: cell) to cell
                return true
            }
            val emu = emulator
            val horizontal = abs(dx) > abs(dy)
            if (!fitToView && emu != null) {
                panX += dx
                // Pan vertically while the host grid is taller than the view; pulled past its top, scroll back.
                val maxY = max(0f, emu.rows * cellH + 2 * PAD * density - height)
                val panning = maxY > 0f && !(panY <= 0f && dy < 0f)
                if (panning && !horizontal) panY = (panY + dy).coerceIn(0f, maxY)
                invalidate()
                if (panning || horizontal) return true
            }
            if (!horizontal) {
                scrollAccum += dy
                val lines = (scrollAccum / cellH).toInt()
                if (lines != 0) {
                    scrollAccum -= lines * cellH
                    onScroll(-lines)
                }
            }
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) scrollAccum = 0f
        return true
    }

    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    // ---- Keyboard ----------------------------------------------------------------------

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Plain text without suggestions: a password field would switch Chinese, Japanese and other
        // keyboards to English only.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        setComposing("")
        return object : BaseInputConnection(this, false) {
            // Text the keyboard is still composing (pinyin before it becomes 你好) stays on the
            // phone, drawn at the cursor; only what it commits goes to the pane.
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                setComposing("")
                typeText(text.toString())
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                setComposing(text.toString())
                return true
            }

            override fun finishComposingText(): Boolean {
                // The keyboard keeps what it composed as it is.
                val text = composing
                setComposing("")
                typeText(text)
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (composing.isNotEmpty()) {
                    setComposing(composing.dropLast(beforeLength))
                    return true
                }
                repeat(max(1, beforeLength)) { onInput(Keys.BACKSPACE) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
                return true
            }
        }
    }

    /** What the keyboard is composing, not yet sent; drawn over the grid at the cursor. */
    private var composing = ""

    private fun setComposing(text: String) {
        if (text == composing) return
        composing = text
        invalidate()
    }

    private val composingPaint = Paint()

    /** [composing] on the theme's ground at the pane's cursor, underlined, kept inside the grid. */
    private fun drawComposing(canvas: Canvas, emu: TerminalEmulator) {
        if (composing.isEmpty()) return
        val w = textPaint.measureText(composing)
        val x = (emu.cursorX * cellW).coerceAtMost(emu.cols * cellW - w).coerceAtLeast(0f)
        val y = emu.cursorY * cellH
        composingPaint.color = painter.palette.background
        canvas.drawRect(x, y, x + w, y + cellH, composingPaint)
        textPaint.color = painter.palette.foreground
        canvas.drawText(composing, x, y + baseline, textPaint)
        composingPaint.color = painter.palette.cursor
        canvas.drawRect(x, y + cellH - 2 * density, x + w, y + cellH, composingPaint)
    }

    private fun typeText(text: String) {
        if (text.isEmpty()) return
        val normalized = text.replace("\n", "\r")
        if (ctrlSticky || altSticky) {
            val first = normalized.substring(0, Character.charCount(normalized.codePointAt(0)))
            onInput(Keys.withModifiers(first, ctrlSticky, altSticky))
            ctrlSticky = false
            altSticky = false
            onModifiersConsumed()
            if (normalized.length > first.length) onInput(normalized.substring(first.length))
        } else {
            onInput(normalized)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val seq = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Keys.ENTER
            KeyEvent.KEYCODE_DEL -> Keys.BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~"
            KeyEvent.KEYCODE_TAB -> if (event.isShiftPressed) Keys.SHIFT_TAB else Keys.TAB
            KeyEvent.KEYCODE_ESCAPE -> Keys.ESC
            KeyEvent.KEYCODE_DPAD_UP -> Keys.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Keys.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Keys.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Keys.RIGHT
            KeyEvent.KEYCODE_MOVE_HOME -> Keys.HOME
            KeyEvent.KEYCODE_MOVE_END -> Keys.END
            KeyEvent.KEYCODE_PAGE_UP -> Keys.PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> Keys.PAGE_DOWN
            KeyEvent.KEYCODE_BACK -> return super.onKeyDown(keyCode, event)
            else -> null
        }
        if (seq != null) {
            onInput(if (event.isAltPressed || altSticky) Keys.ESC + seq else seq)
            if (altSticky) { altSticky = false; onModifiersConsumed() }
            return true
        }
        val unicode = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
        if (unicode == 0) return super.onKeyDown(keyCode, event)
        val text = String(Character.toChars(unicode))
        val ctrl = event.isCtrlPressed || ctrlSticky
        val alt = event.isAltPressed || altSticky
        onInput(Keys.withModifiers(text, ctrl, alt))
        if (ctrlSticky || altSticky) {
            ctrlSticky = false
            altSticky = false
            onModifiersConsumed()
        }
        return true
    }

    /** Visible screen text, for copying. */
    fun screenText(): String = emulator?.let { synchronized(it.lock) { it.screenText() } } ?: ""

    companion object {
        private const val PAD = 4f
    }
}

/** A cell in the pane's viewport: `row` 0 is the top line on screen, `col` 0 the first column. */
data class CellPoint(val row: Int, val col: Int)
