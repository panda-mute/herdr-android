package com.makefunstuff.herdr.core.terminal

/**
 * A compact VT/xterm screen model for herdr's terminal frames.
 *
 * herdr streams a pane as ANSI "blits": cursor positioning, SGR styles and text, with an
 * occasional full repaint. This emulator covers that plus the usual cursor, erase, scroll-region
 * and alternate-screen sequences so ordinary terminal output also renders sensibly.
 *
 * With a [historyLimit] it keeps a scrollback, as a classic terminal does: the lines that scroll off
 * the top of the main screen, up to that many, and [scrollBack] shows them. Without one (classic
 * herdr, which owns scrolling on the host) it keeps none. [row], [cell] and [lineText] read what is
 * shown, the screen or the history scrolled to.
 *
 * Not thread-safe by itself: callers hold [lock] while feeding or reading.
 */
class TerminalEmulator(cols: Int, rows: Int, val historyLimit: Int = 0) {
    val lock = Any()

    var cols: Int = cols.coerceAtLeast(1); private set
    var rows: Int = rows.coerceAtLeast(1); private set

    private var main = Grid(this.cols, this.rows)
    private var alt = Grid(this.cols, this.rows)
    private var grid = main
    val isAltScreen: Boolean get() = grid === alt

    var cursorX = 0; private set
    var cursorY = 0; private set
    var cursorVisible = true; private set
    private var wrapPending = false
    private var autowrap = true

    private var fg = Cell.DEFAULT_COLOR
    private var bg = Cell.DEFAULT_COLOR
    private var attrs = 0

    private var scrollTop = 0
    private var scrollBottom = this.rows - 1

    private var savedX = 0
    private var savedY = 0
    private var savedFg = Cell.DEFAULT_COLOR
    private var savedBg = Cell.DEFAULT_COLOR
    private var savedAttrs = 0

    /** The scrollback, oldest first; each line as wide as the screen was when it scrolled off. */
    private val history = ArrayDeque<Array<Cell>>()
    val historySize: Int get() = history.size

    /** Lines ever dropped from the oldest end of the history: a line's [absolute] number stays its own. */
    private var dropped = 0L

    /** How many lines back the view is scrolled into the history; 0 shows the live screen. */
    var scrolledBack = 0; private set

    /** A found match to mark: [line] as [absolute] numbers it, columns [from] to [to] inclusive. */
    var mark: Mark? = null

    data class Mark(val line: Long, val from: Int, val to: Int)

    /** Increments whenever the visible content changes. */
    var revision: Long = 0; private set

    /** Last OSC 0/2 title the stream set, if any. */
    var title: String? = null; private set

    // Parser state
    private enum class State { Ground, Escape, EscapeCharset, Csi, Osc, OscEsc, Str, StrEsc }
    private var state = State.Ground
    private val params = StringBuilder()
    private var privateMarker: Char? = null
    private var intermediate: Char? = null
    private val osc = StringBuilder()

    // UTF-8 decoding across chunk boundaries
    private var utf8Need = 0
    private var utf8Code = 0

    /** The cell shown at [x], [y]. */
    fun cell(x: Int, y: Int): Cell = row(y)[x]

    /** The row shown at [y]: the screen's, or the history's while scrolled back. */
    fun row(y: Int): Array<Cell> {
        if (scrolledBack == 0) return grid.lines[y]
        val i = history.size - scrolledBack + y
        return if (i < history.size) fitted(history[i]) else grid.lines[i - history.size]
    }

    /** The text of the row shown at [y]. */
    fun lineText(y: Int): String = text(row(y))

    /** The text of the screen's line the cursor is on, wherever the view is scrolled. */
    fun cursorLineText(): String = text(grid.lines[cursorY])

    /** The text shown, the screen or the history scrolled to. */
    fun screenText(): String = (0 until rows).joinToString("\n") { lineText(it) }.trimEnd()

    /** Every line's text, the history's then the screen's, numbered from [firstLine]. */
    fun allLines(): List<String> = history.map(::text) + grid.lines.map(::text)

    /** The [absolute] number of the oldest line [allLines] gives. */
    val firstLine: Long get() = dropped

    /** The number every line keeps while newer ones scroll in: [index] counts from the oldest kept line. */
    fun absolute(index: Int): Long = dropped + index

    /** The [absolute] number of the line shown at the view's top. */
    val topLine: Long get() = dropped + history.size - scrolledBack

    /**
     * Scrolls the view [lines] back into the history (negative: forward, toward the screen), within
     * what is kept. True when the view moved.
     */
    fun scrollBack(lines: Int): Boolean {
        val to = (scrolledBack.toLong() + lines).coerceIn(0L, history.size.toLong()).toInt()
        if (to == scrolledBack) return false
        scrolledBack = to
        revision++
        return true
    }

    /** Scrolls so the line [absolute] numbers [line] is shown, a third down the view if it isn't already. */
    fun reveal(line: Long) {
        val index = (line - dropped).coerceIn(0L, (history.size + rows - 1).toLong()).toInt()
        val top = history.size - scrolledBack
        if (index in top until top + rows) return
        scrollBack(history.size - index + rows / 3 - scrolledBack)
    }

    /**
     * Puts [lines] (plain text, oldest first) above the history, as if they had scrolled off before
     * it: what `terminal.read` had before the terminal was attached. Kept within [historyLimit].
     */
    fun prependHistory(lines: List<String>) {
        if (historyLimit <= 0 || lines.isEmpty()) return
        val room = historyLimit - history.size
        if (room <= 0) return
        for (line in lines.takeLast(room).asReversed()) history.addFirst(plainRow(line))
        revision++
    }

    private fun plainRow(line: String): Array<Cell> {
        val row = Array(cols) { Cell.BLANK }
        var x = 0
        var i = 0
        while (i < line.length && x < cols) {
            val cp = line.codePointAt(i)
            i += Character.charCount(cp)
            val width = CharWidth.of(cp)
            if (width == 0 || cp < 0x20) continue
            if (width == 2 && x == cols - 1) break
            row[x] = Cell(String(Character.toChars(cp)), Cell.DEFAULT_COLOR, Cell.DEFAULT_COLOR, 0)
            if (width == 2) row[x + 1] = Cell(Cell.CONTINUATION, Cell.DEFAULT_COLOR, Cell.DEFAULT_COLOR, 0)
            x += width
        }
        return row
    }

    /** A history line at the screen's width now: cut, or filled with blanks. */
    private fun fitted(line: Array<Cell>): Array<Cell> =
        if (line.size == cols) line else Array(cols) { x -> line.getOrElse(x) { Cell.BLANK } }

    private fun text(line: Array<Cell>): String = buildString {
        for (c in line) if (!c.isContinuation) append(c.text ?: " ")
    }.trimEnd()

    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceAtLeast(1)
        val r = newRows.coerceAtLeast(1)
        if (c == cols && r == rows) return
        main = main.resized(c, r)
        alt = alt.resized(c, r)
        grid = if (grid === alt) alt else main
        cols = c
        rows = r
        scrollTop = 0
        scrollBottom = r - 1
        cursorX = cursorX.coerceIn(0, c - 1)
        cursorY = cursorY.coerceIn(0, r - 1)
        wrapPending = false
        revision++
    }

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        for (i in offset until offset + length) feedByte(bytes[i].toInt() and 0xFF)
        revision++
    }

    fun feed(text: String) = feed(text.toByteArray(Charsets.UTF_8))

    private fun feedByte(b: Int) {
        if (utf8Need > 0) {
            if (b and 0xC0 == 0x80) {
                utf8Code = (utf8Code shl 6) or (b and 0x3F)
                if (--utf8Need == 0) onCodePoint(utf8Code)
                return
            }
            utf8Need = 0
            onCodePoint(0xFFFD)
        }
        when {
            b < 0x80 -> onCodePoint(b)
            b and 0xE0 == 0xC0 -> { utf8Code = b and 0x1F; utf8Need = 1 }
            b and 0xF0 == 0xE0 -> { utf8Code = b and 0x0F; utf8Need = 2 }
            b and 0xF8 == 0xF0 -> { utf8Code = b and 0x07; utf8Need = 3 }
            else -> onCodePoint(0xFFFD)
        }
    }

    private fun onCodePoint(cp: Int) {
        when (state) {
            State.Ground -> ground(cp)
            State.Escape -> escape(cp)
            State.EscapeCharset -> state = State.Ground
            State.Csi -> csi(cp)
            State.Osc -> when (cp) {
                0x07 -> { finishOsc(); state = State.Ground }
                0x1B -> state = State.OscEsc
                else -> if (osc.length < 4096) osc.appendCodePoint(cp)
            }
            State.OscEsc -> {
                finishOsc()
                state = State.Ground
                if (cp != '\\'.code) onCodePoint(cp)
            }
            // DCS, APC (kitty graphics), PM, SOS: swallow until ST
            State.Str -> if (cp == 0x1B) state = State.StrEsc else if (cp == 0x07) state = State.Ground
            State.StrEsc -> state = if (cp == '\\'.code) State.Ground else State.Str
        }
    }

    private fun ground(cp: Int) {
        when (cp) {
            0x1B -> state = State.Escape
            0x0D -> { cursorX = 0; wrapPending = false }
            0x0A, 0x0B, 0x0C -> lineFeed()
            0x08 -> { if (cursorX > 0) cursorX--; wrapPending = false }
            0x09 -> { cursorX = minOf(cols - 1, (cursorX / 8 + 1) * 8); wrapPending = false }
            0x07, 0x00, 0x0E, 0x0F -> {}
            else -> if (cp >= 0x20 && cp != 0x7F) print(cp)
        }
    }

    private fun escape(cp: Int) {
        state = State.Ground
        when (cp.toChar()) {
            '[' -> { state = State.Csi; params.setLength(0); privateMarker = null; intermediate = null }
            ']' -> { state = State.Osc; osc.setLength(0) }
            'P', '_', '^', 'X' -> state = State.Str
            '(', ')', '*', '+', '#', '%' -> state = State.EscapeCharset
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> lineFeed()
            'E' -> { cursorX = 0; lineFeed() }
            'M' -> reverseIndex()
            'c' -> reset()
            else -> {}
        }
    }

    private fun csi(cp: Int) {
        val ch = cp.toChar()
        when {
            ch in '0'..'9' || ch == ';' || ch == ':' -> params.append(ch)
            ch in "?><=" && params.isEmpty() -> privateMarker = ch
            cp in 0x20..0x2F -> intermediate = ch
            cp in 0x40..0x7E -> { state = State.Ground; executeCsi(ch) }
            cp == 0x1B -> state = State.Escape
            else -> state = State.Ground
        }
    }

    private fun parsedParams(): List<String> = if (params.isEmpty()) emptyList() else params.split(';')

    private fun p(list: List<String>, i: Int, default: Int): Int {
        val v = list.getOrNull(i)?.substringBefore(':')?.toIntOrNull() ?: return default
        return if (v == 0) default else v
    }

    private fun executeCsi(final: Char) {
        val ps = parsedParams()
        if (intermediate != null) return // cursor shape (" q") and friends
        if (privateMarker == '?') {
            if (final == 'h' || final == 'l') ps.forEach { setPrivateMode(it.toIntOrNull() ?: -1, final == 'h') }
            return
        }
        if (privateMarker != null) return
        if (final != 'm') wrapPending = false
        when (final) {
            'H', 'f' -> moveTo(p(ps, 1, 1) - 1, p(ps, 0, 1) - 1)
            'A' -> cursorY = maxOf(if (cursorY >= scrollTop) scrollTop else 0, cursorY - p(ps, 0, 1))
            'B', 'e' -> cursorY = minOf(if (cursorY <= scrollBottom) scrollBottom else rows - 1, cursorY + p(ps, 0, 1))
            'C', 'a' -> cursorX = minOf(cols - 1, cursorX + p(ps, 0, 1))
            'D' -> cursorX = maxOf(0, cursorX - p(ps, 0, 1))
            'E' -> { cursorX = 0; cursorY = minOf(rows - 1, cursorY + p(ps, 0, 1)) }
            'F' -> { cursorX = 0; cursorY = maxOf(0, cursorY - p(ps, 0, 1)) }
            'G', '`' -> cursorX = (p(ps, 0, 1) - 1).coerceIn(0, cols - 1)
            'd' -> cursorY = (p(ps, 0, 1) - 1).coerceIn(0, rows - 1)
            'J' -> eraseDisplay(ps.getOrNull(0)?.toIntOrNull() ?: 0)
            'K' -> eraseLine(ps.getOrNull(0)?.toIntOrNull() ?: 0)
            'X' -> clearCells(cursorY, cursorX, minOf(cols, cursorX + p(ps, 0, 1)))
            'P' -> deleteChars(p(ps, 0, 1))
            '@' -> insertChars(p(ps, 0, 1))
            'L' -> if (cursorY in scrollTop..scrollBottom) scrollDown(cursorY, scrollBottom, p(ps, 0, 1))
            'M' -> if (cursorY in scrollTop..scrollBottom) scrollUp(cursorY, scrollBottom, p(ps, 0, 1))
            'S' -> scrollUp(scrollTop, scrollBottom, p(ps, 0, 1))
            'T' -> scrollDown(scrollTop, scrollBottom, p(ps, 0, 1))
            'm' -> sgr(ps)
            'r' -> {
                val top = p(ps, 0, 1) - 1
                val bottom = p(ps, 1, rows) - 1
                if (top < bottom && bottom < rows) {
                    scrollTop = top
                    scrollBottom = bottom
                    moveTo(0, 0)
                }
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            else -> {}
        }
    }

    private fun setPrivateMode(mode: Int, on: Boolean) {
        when (mode) {
            25 -> cursorVisible = on
            7 -> autowrap = on
            47, 1047 -> switchScreen(on, clear = false)
            1049 -> {
                if (on) saveCursor()
                switchScreen(on, clear = true)
                if (!on) restoreCursor()
            }
            else -> {}
        }
    }

    private fun switchScreen(toAlt: Boolean, clear: Boolean) {
        val target = if (toAlt) alt else main
        if (grid === target) return
        grid = target
        if (toAlt && clear) alt.clearAll(blank())
    }

    private fun finishOsc() {
        val text = osc.toString()
        val sep = text.indexOf(';')
        if (sep > 0) {
            when (text.substring(0, sep)) {
                "0", "2" -> title = text.substring(sep + 1)
            }
        }
    }

    private fun print(cp: Int) {
        val width = CharWidth.of(cp)
        if (width == 0) {
            appendCombining(cp)
            return
        }
        if (wrapPending) {
            if (autowrap) {
                cursorX = 0
                lineFeed()
            }
            wrapPending = false
        }
        if (width == 2 && cursorX == cols - 1) {
            // A wide character doesn't fit in the last column; wrap first.
            clearCells(cursorY, cursorX, cols)
            if (autowrap) {
                cursorX = 0
                lineFeed()
            } else {
                return
            }
        }
        val row = grid.lines[cursorY]
        // Overwriting half of a wide character clears the other half.
        if (row[cursorX].isContinuation && cursorX > 0) row[cursorX - 1] = blank()
        if (cursorX + 1 < cols && row[cursorX + 1].isContinuation && width == 1) row[cursorX + 1] = blank()
        row[cursorX] = Cell(String(Character.toChars(cp)), fg, bg, attrs)
        if (width == 2) {
            row[cursorX + 1] = Cell(Cell.CONTINUATION, fg, bg, attrs)
        }
        val next = cursorX + width
        if (next >= cols) {
            cursorX = cols - 1
            wrapPending = true
        } else {
            cursorX = next
        }
    }

    private fun appendCombining(cp: Int) {
        var x = if (wrapPending) cursorX else cursorX - 1
        if (x < 0) return
        val row = grid.lines[cursorY]
        if (row[x].isContinuation && x > 0) x--
        val existing = row[x]
        row[x] = existing.copy(text = (existing.text ?: " ") + String(Character.toChars(cp)))
    }

    private fun lineFeed() {
        if (cursorY == scrollBottom) {
            scrollUp(scrollTop, scrollBottom, 1)
        } else if (cursorY < rows - 1) {
            cursorY++
        }
    }

    private fun reverseIndex() {
        if (cursorY == scrollTop) scrollDown(scrollTop, scrollBottom, 1) else if (cursorY > 0) cursorY--
    }

    private fun moveTo(x: Int, y: Int) {
        cursorX = x.coerceIn(0, cols - 1)
        cursorY = y.coerceIn(0, rows - 1)
        wrapPending = false
    }

    private fun blank() = Cell(null, Cell.DEFAULT_COLOR, bg, 0)

    private fun clearCells(y: Int, from: Int, to: Int) {
        val row = grid.lines[y]
        val b = blank()
        for (x in from.coerceAtLeast(0) until to.coerceAtMost(cols)) row[x] = b
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                clearCells(cursorY, cursorX, cols)
                for (y in cursorY + 1 until rows) clearCells(y, 0, cols)
            }
            1 -> {
                for (y in 0 until cursorY) clearCells(y, 0, cols)
                clearCells(cursorY, 0, cursorX + 1)
            }
            2 -> for (y in 0 until rows) clearCells(y, 0, cols)
            // xterm's: the scrollback goes too.
            3 -> {
                for (y in 0 until rows) clearCells(y, 0, cols)
                if (grid === main) clearHistory()
            }
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> clearCells(cursorY, cursorX, cols)
            1 -> clearCells(cursorY, 0, cursorX + 1)
            2 -> clearCells(cursorY, 0, cols)
        }
    }

    private fun deleteChars(n: Int) {
        val row = grid.lines[cursorY]
        val count = minOf(n, cols - cursorX)
        for (x in cursorX until cols) row[x] = if (x + count < cols) row[x + count] else blank()
    }

    private fun insertChars(n: Int) {
        val row = grid.lines[cursorY]
        val count = minOf(n, cols - cursorX)
        for (x in cols - 1 downTo cursorX) row[x] = if (x - count >= cursorX) row[x - count] else blank()
    }

    private fun scrollUp(top: Int, bottom: Int, n: Int) {
        val count = minOf(n, bottom - top + 1)
        // Off the top of the main screen: into the history, the view staying on what it shows.
        if (historyLimit > 0 && top == 0 && grid === main) {
            for (y in 0 until count) history.addLast(grid.lines[y])
            if (scrolledBack > 0) scrolledBack += count
            while (history.size > historyLimit) {
                history.removeFirst()
                dropped++
            }
            scrolledBack = scrolledBack.coerceAtMost(history.size)
        }
        for (y in top..bottom) {
            grid.lines[y] = if (y + count <= bottom) grid.lines[y + count] else Array(cols) { blank() }
        }
    }

    private fun scrollDown(top: Int, bottom: Int, n: Int) {
        val count = minOf(n, bottom - top + 1)
        for (y in bottom downTo top) {
            grid.lines[y] = if (y - count >= top) grid.lines[y - count] else Array(cols) { blank() }
        }
    }

    private fun saveCursor() {
        savedX = cursorX; savedY = cursorY
        savedFg = fg; savedBg = bg; savedAttrs = attrs
    }

    private fun restoreCursor() {
        moveTo(savedX, savedY)
        fg = savedFg; bg = savedBg; attrs = savedAttrs
    }

    private fun clearHistory() {
        dropped += history.size
        history.clear()
        scrolledBack = 0
    }

    private fun reset() {
        clearHistory()
        main = Grid(cols, rows)
        alt = Grid(cols, rows)
        grid = main
        fg = Cell.DEFAULT_COLOR; bg = Cell.DEFAULT_COLOR; attrs = 0
        scrollTop = 0; scrollBottom = rows - 1
        moveTo(0, 0)
        cursorVisible = true
    }

    private fun sgr(ps: List<String>) {
        if (ps.isEmpty()) {
            fg = Cell.DEFAULT_COLOR; bg = Cell.DEFAULT_COLOR; attrs = 0
            return
        }
        var i = 0
        while (i < ps.size) {
            val raw = ps[i]
            val sub = raw.split(':')
            val code = sub[0].toIntOrNull() ?: 0
            when (code) {
                0 -> { fg = Cell.DEFAULT_COLOR; bg = Cell.DEFAULT_COLOR; attrs = 0 }
                1 -> attrs = attrs or Cell.BOLD
                2 -> attrs = attrs or Cell.DIM
                3 -> attrs = attrs or Cell.ITALIC
                4 -> attrs = if (sub.getOrNull(1) == "0") attrs and Cell.UNDERLINE.inv() else attrs or Cell.UNDERLINE
                5, 6 -> attrs = attrs or Cell.BLINK
                7 -> attrs = attrs or Cell.INVERSE
                8 -> attrs = attrs or Cell.HIDDEN
                9 -> attrs = attrs or Cell.STRIKE
                21 -> attrs = attrs or Cell.UNDERLINE
                22 -> attrs = attrs and (Cell.BOLD or Cell.DIM).inv()
                23 -> attrs = attrs and Cell.ITALIC.inv()
                24 -> attrs = attrs and Cell.UNDERLINE.inv()
                25 -> attrs = attrs and Cell.BLINK.inv()
                27 -> attrs = attrs and Cell.INVERSE.inv()
                28 -> attrs = attrs and Cell.HIDDEN.inv()
                29 -> attrs = attrs and Cell.STRIKE.inv()
                in 30..37 -> fg = code - 30
                39 -> fg = Cell.DEFAULT_COLOR
                in 40..47 -> bg = code - 40
                49 -> bg = Cell.DEFAULT_COLOR
                in 90..97 -> fg = code - 90 + 8
                in 100..107 -> bg = code - 100 + 8
                38, 48, 58 -> {
                    val (color, consumed) = extendedColor(sub, ps, i)
                    if (color != null) {
                        if (code == 38) fg = color else if (code == 48) bg = color
                    }
                    i += consumed
                }
                59 -> {}
                else -> {}
            }
            i++
        }
    }

    /** Parses 38;5;n / 38;2;r;g;b and their colon forms. Returns the color and extra params consumed. */
    private fun extendedColor(sub: List<String>, ps: List<String>, i: Int): Pair<Int?, Int> {
        if (sub.size > 1) {
            return when (sub[1]) {
                "5" -> (sub.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 255)) to 0
                "2" -> {
                    // 38:2:<colorspace>:r:g:b or 38:2:r:g:b
                    val nums = sub.drop(2)
                    val rgb = if (nums.size >= 4) nums.drop(1) else nums
                    val r = rgb.getOrNull(0)?.toIntOrNull()
                    val g = rgb.getOrNull(1)?.toIntOrNull()
                    val b = rgb.getOrNull(2)?.toIntOrNull()
                    (if (r != null && g != null && b != null) Cell.rgb(r, g, b) else null) to 0
                }
                else -> null to 0
            }
        }
        return when (ps.getOrNull(i + 1)) {
            "5" -> (ps.getOrNull(i + 2)?.toIntOrNull()?.coerceIn(0, 255)) to 2
            "2" -> {
                val r = ps.getOrNull(i + 2)?.toIntOrNull()
                val g = ps.getOrNull(i + 3)?.toIntOrNull()
                val b = ps.getOrNull(i + 4)?.toIntOrNull()
                (if (r != null && g != null && b != null) Cell.rgb(r, g, b) else null) to 4
            }
            else -> null to 0
        }
    }

    private class Grid(val width: Int, val height: Int) {
        val lines: Array<Array<Cell>> = Array(height) { Array(width) { Cell.BLANK } }

        fun clearAll(blank: Cell) {
            for (line in lines) line.fill(blank)
        }

        fun resized(c: Int, r: Int): Grid {
            val g = Grid(c, r)
            for (y in 0 until minOf(r, height)) {
                for (x in 0 until minOf(c, width)) g.lines[y][x] = lines[y][x]
            }
            return g
        }
    }
}

/** One screen cell. [text] is null for blank, [CONTINUATION] for the right half of a wide char. */
data class Cell(val text: String?, val fg: Int, val bg: Int, val attrs: Int) {
    val isContinuation: Boolean get() = text === CONTINUATION || text == CONTINUATION

    companion object {
        const val DEFAULT_COLOR = -1
        const val RGB_FLAG = 0x1000000
        const val CONTINUATION = ""

        const val BOLD = 1
        const val DIM = 2
        const val ITALIC = 4
        const val UNDERLINE = 8
        const val BLINK = 16
        const val INVERSE = 32
        const val HIDDEN = 64
        const val STRIKE = 128

        val BLANK = Cell(null, DEFAULT_COLOR, DEFAULT_COLOR, 0)

        fun rgb(r: Int, g: Int, b: Int) =
            RGB_FLAG or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

        fun isRgb(color: Int) = color >= RGB_FLAG
    }
}
