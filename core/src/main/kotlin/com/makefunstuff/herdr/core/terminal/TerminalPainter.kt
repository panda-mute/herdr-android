package com.makefunstuff.herdr.core.terminal

import kotlin.math.max
import kotlin.math.roundToInt

/** The drawing operations [TerminalPainter] needs; Android's Canvas and Java2D both provide them. */
interface TerminalCanvas {
    fun fillRect(left: Float, top: Float, right: Float, bottom: Float, argb: Int)

    /** Draws [text] from [x] on [baseline], squeezed horizontally if it is wider than [maxWidth]. */
    fun drawText(text: String, x: Float, baseline: Float, maxWidth: Float, argb: Int, bold: Boolean, italic: Boolean)
}

class TerminalPalette(
    val foreground: Int,
    val background: Int,
    val cursor: Int,
    val ansi: IntArray,
    /**
     * The least contrast a cell's text keeps on its background (WCAG ratio; 1 = as the program
     * asked). Text below it moves toward black or white until it reaches it, as xterm.js's
     * `minimumContrastRatio` does; dim text needs half, and box-drawing and block glyphs keep
     * their colour so borders still meet the background.
     */
    val minimumContrast: Double = 1.0,
) {
    fun resolve(color: Int, fallback: Int): Int = when {
        color == Cell.DEFAULT_COLOR -> fallback
        Cell.isRgb(color) -> OPAQUE or (color and 0xFFFFFF)
        color in 0..15 -> ansi[color]
        color in 16..231 -> {
            val i = color - 16
            OPAQUE or (CUBE[i / 36] shl 16) or (CUBE[(i / 6) % 6] shl 8) or CUBE[i % 6]
        }
        color in 232..255 -> {
            val v = 8 + (color - 232) * 10
            OPAQUE or (v shl 16) or (v shl 8) or v
        }
        else -> fallback
    }

    companion object {
        private const val OPAQUE = 0xFF000000.toInt()
        private val CUBE = intArrayOf(0, 95, 135, 175, 215, 255)

        val Default = TerminalPalette(
            foreground = 0xFFD8DEDA.toInt(),
            background = 0xFF0B0D0C.toInt(),
            cursor = 0xFF7DD3B6.toInt(),
            ansi = intArrayOf(
                0xFF1D2321.toInt(), 0xFFE0675C.toInt(), 0xFF7CC68B.toInt(), 0xFFE2B64A.toInt(),
                0xFF6E9FE6.toInt(), 0xFFC38AD8.toInt(), 0xFF5CC3C0.toInt(), 0xFFC9D1CC.toInt(),
                0xFF5B6560.toInt(), 0xFFF2877D.toInt(), 0xFF98DCA6.toInt(), 0xFFF0CC6C.toInt(),
                0xFF8FB8F5.toInt(), 0xFFD6A6E8.toInt(), 0xFF7FDAD6.toInt(), 0xFFF4F7F5.toInt(),
            ),
        )
    }
}

/**
 * Paints a [TerminalEmulator] grid. Box-drawing and block characters are drawn as shapes that
 * fill their cell exactly, the way desktop terminals do, so agent TUIs keep unbroken borders
 * whatever font and line height the phone uses. Cell edges snap to whole pixels to avoid seams.
 */
class TerminalPainter(var palette: TerminalPalette = TerminalPalette.Default) {

    /**
     * Caller holds [TerminalEmulator.lock]. [underText] draws on the grid's ground before the cells
     * (a selection's fill), so the text stays on top of it.
     */
    fun paint(
        emu: TerminalEmulator,
        canvas: TerminalCanvas,
        cellW: Float,
        cellH: Float,
        baseline: Float,
        showCursor: Boolean = true,
        underText: (() -> Unit)? = null,
    ) {
        val p = palette
        canvas.fillRect(0f, 0f, px(emu.cols * cellW), px(emu.rows * cellH), p.background)
        val light = luminance(p.background) > 0.5
        underText?.invoke()
        // A found match, as a selection is: on the ground, under the text.
        emu.mark?.let { m ->
            val y = m.line - emu.topLine
            if (y in 0 until emu.rows) {
                canvas.fillRect(px(m.from * cellW), px(y * cellH), px((m.to + 1) * cellW), px((y + 1) * cellH), (p.cursor and 0xFFFFFF) or (0x70 shl 24))
            }
        }
        for (y in 0 until emu.rows) {
            val row = emu.row(y)
            val top = px(y * cellH)
            val bottom = px((y + 1) * cellH)
            for (x in 0 until emu.cols) {
                val cell = row[x]
                if (cell.isContinuation) continue
                val span = if (x + 1 < emu.cols && row[x + 1].isContinuation) 2 else 1
                val left = px(x * cellW)
                val right = px((x + span) * cellW)
                var fg = p.resolve(cell.fg, p.foreground)
                var bg = p.resolve(cell.bg, p.background)
                if (light && Cell.isRgb(cell.bg)) bg = lightSurface(bg, p.background)
                if (cell.attrs and Cell.BOLD != 0 && cell.fg in 0..7) fg = p.resolve(cell.fg + 8, p.foreground)
                if (cell.attrs and Cell.INVERSE != 0) { val t = fg; fg = bg; bg = t }
                val dim = cell.attrs and Cell.DIM != 0
                if (dim) fg = blend(fg, bg, 0.6f)
                if (bg != p.background) canvas.fillRect(left, top, right, bottom, bg)
                if (cell.attrs and Cell.HIDDEN != 0) continue
                val text = cell.text
                if (!text.isNullOrEmpty() && text != " ") {
                    val cp = text.codePointAt(0)
                    if (p.minimumContrast > 1.0 && !isGraphic(cp)) {
                        fg = ensureContrast(fg, bg, if (dim) maxOf(1.0, p.minimumContrast / 2) else p.minimumContrast)
                    }
                    when {
                        cp in 0x2500..0x257F && BOX[cp - 0x2500] != 0 ->
                            drawBox(canvas, BOX[cp - 0x2500], left, top, right, bottom, cellW, fg)
                        cp in 0x2580..0x259F -> drawBlock(canvas, cp, left, top, right, bottom, fg, bg)
                        else -> canvas.drawText(
                            text, left, top + baseline, right - left, fg,
                            bold = cell.attrs and Cell.BOLD != 0, italic = cell.attrs and Cell.ITALIC != 0,
                        )
                    }
                }
                val line = max(1f, (cellH / 14f).roundToInt().toFloat())
                if (cell.attrs and Cell.UNDERLINE != 0) {
                    val uy = px(top + baseline + line)
                    canvas.fillRect(left, uy, right, uy + line, fg)
                }
                if (cell.attrs and Cell.STRIKE != 0) {
                    val sy = px(top + cellH / 2)
                    canvas.fillRect(left, sy, right, sy + line, fg)
                }
            }
        }
        if (showCursor && emu.cursorVisible && emu.scrolledBack == 0 && emu.cursorY < emu.rows && emu.cursorX < emu.cols) {
            canvas.fillRect(
                px(emu.cursorX * cellW), px(emu.cursorY * cellH),
                px((emu.cursorX + 1) * cellW), px((emu.cursorY + 1) * cellH),
                (p.cursor and 0xFFFFFF) or (0xA0 shl 24),
            )
        }
    }

    private fun drawBox(c: TerminalCanvas, spec: Int, l: Float, t: Float, r: Float, b: Float, cellW: Float, color: Int) {
        val light = max(1f, (cellW / 8f).roundToInt().toFloat())
        val cx = px((l + r) / 2f - light / 2f) + light / 2f
        val cy = px((t + b) / 2f - light / 2f) + light / 2f
        val dashes = (spec shr 8) and 7
        fun thickness(w: Int) = if (w == 2) light * 2 else light
        // Horizontal arms
        for ((weight, from, to) in listOf(
            Triple((spec shr 6) and 3, l, cx),
            Triple((spec shr 2) and 3, cx, r),
        )) {
            if (weight == 0) continue
            val th = thickness(weight)
            val a = if (from == l) l else from - th / 2f
            val z = if (to == r) r else to + th / 2f
            if (weight == 3) {
                hLine(c, a, z, cy - light * 1.5f, light, color, dashes, l, r)
                hLine(c, a, z, cy + light * 0.5f, light, color, dashes, l, r)
            } else {
                hLine(c, a, z, cy - th / 2f, th, color, dashes, l, r)
            }
        }
        // Vertical arms
        for ((weight, from, to) in listOf(
            Triple(spec and 3, t, cy),
            Triple((spec shr 4) and 3, cy, b),
        )) {
            if (weight == 0) continue
            val th = thickness(weight)
            val a = if (from == t) t else from - th / 2f
            val z = if (to == b) b else to + th / 2f
            if (weight == 3) {
                vLine(c, a, z, cx - light * 1.5f, light, color, dashes, t, b)
                vLine(c, a, z, cx + light * 0.5f, light, color, dashes, t, b)
            } else {
                vLine(c, a, z, cx - th / 2f, th, color, dashes, t, b)
            }
        }
    }

    private fun hLine(c: TerminalCanvas, a: Float, z: Float, y: Float, th: Float, color: Int, dashes: Int, l: Float, r: Float) {
        val top = px(y)
        if (dashes == 0) { c.fillRect(a, top, z, top + th, color); return }
        val seg = (r - l) / dashes
        for (i in 0 until dashes) {
            val s = l + i * seg
            c.fillRect(px(s), top, px(s + seg * 0.6f), top + th, color)
        }
    }

    private fun vLine(c: TerminalCanvas, a: Float, z: Float, x: Float, th: Float, color: Int, dashes: Int, t: Float, b: Float) {
        val left = px(x)
        if (dashes == 0) { c.fillRect(left, a, left + th, z, color); return }
        val seg = (b - t) / dashes
        for (i in 0 until dashes) {
            val s = t + i * seg
            c.fillRect(left, px(s), left + th, px(s + seg * 0.6f), color)
        }
    }

    private fun drawBlock(c: TerminalCanvas, cp: Int, l: Float, t: Float, r: Float, b: Float, fg: Int, bg: Int) {
        val w = r - l
        val h = b - t
        val mx = px(l + w / 2)
        val my = px(t + h / 2)
        when (cp) {
            0x2580 -> c.fillRect(l, t, r, my, fg)
            in 0x2581..0x2588 -> c.fillRect(l, px(b - h * (cp - 0x2580) / 8f), r, b, fg)
            in 0x2589..0x258F -> c.fillRect(l, t, px(l + w * (0x2590 - cp) / 8f), b, fg)
            0x2590 -> c.fillRect(mx, t, r, b, fg)
            0x2591 -> c.fillRect(l, t, r, b, blend(fg, bg, 0.25f))
            0x2592 -> c.fillRect(l, t, r, b, blend(fg, bg, 0.5f))
            0x2593 -> c.fillRect(l, t, r, b, blend(fg, bg, 0.75f))
            0x2594 -> c.fillRect(l, t, r, px(t + h / 8f), fg)
            0x2595 -> c.fillRect(px(r - w / 8f), t, r, b, fg)
            else -> {
                // Quadrants: bits are upper-left, upper-right, lower-left, lower-right.
                val q = QUADRANTS[cp - 0x2596]
                if (q and 1 != 0) c.fillRect(l, t, mx, my, fg)
                if (q and 2 != 0) c.fillRect(mx, t, r, my, fg)
                if (q and 4 != 0) c.fillRect(l, my, mx, b, fg)
                if (q and 8 != 0) c.fillRect(mx, my, r, b, fg)
            }
        }
    }

    /** Box drawing, blocks and powerline glyphs: drawn in the program's colour so they meet the background. */
    private fun isGraphic(cp: Int) = cp in 0x2500..0x259F || cp in 0xE0B0..0xE0BF

    companion object {
        /** WCAG relative luminance of an ARGB colour. */
        fun luminance(argb: Int): Double {
            fun ch(v: Int): Double { val c = v / 255.0; return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
        }

        fun contrast(a: Int, b: Int): Double {
            val x = luminance(a) + 0.05; val y = luminance(b) + 0.05
            return maxOf(x, y) / minOf(x, y)
        }

        /** [fg] moved toward black or white (whichever [bg] is further from) until it reads at [ratio] on [bg]. */
        fun ensureContrast(fg: Int, bg: Int, ratio: Double): Int {
            if (contrast(fg, bg) >= ratio) return fg
            val toward = if (luminance(bg) > 0.18) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            var c = fg
            var t = 0f
            while (t < 1f) {
                t = minOf(1f, t + 0.05f)
                c = blend(fg, toward, 1f - t)
                if (contrast(c, bg) >= ratio) break
            }
            return c
        }
        private fun px(v: Float): Float = v.roundToInt().toFloat()

        /**
         * A dark background a program chose for a dark terminal (Codex shades its prompt from the
         * computer's background, Claude Code's dark theme shades diffs), on a light [ground]: its
         * lightness turned over, its hue kept, half way to the ground. Light ones stay as they are.
         */
        fun lightSurface(bg: Int, ground: Int): Int {
            if (luminance(bg) >= DARK_SURFACE) return bg
            val r = ((bg shr 16) and 0xFF) / 255f
            val g = ((bg shr 8) and 0xFF) / 255f
            val b = (bg and 0xFF) / 255f
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val l = (max + min) / 2
            val d = max - min
            val s = if (d == 0f) 0f else d / (1 - kotlin.math.abs(2 * l - 1))
            val h = when {
                d == 0f -> 0f
                max == r -> 60 * (((g - b) / d) % 6)
                max == g -> 60 * ((b - r) / d + 2)
                else -> 60 * ((r - g) / d + 4)
            }.let { if (it < 0) it + 360 else it }
            val turned = hsl(h, s, 1 - l * 0.45f)
            return blend(turned, ground, 0.5f)
        }

        /** Below this a background reads as a dark terminal's surface. */
        private const val DARK_SURFACE = 0.06

        private fun hsl(h: Float, s: Float, l: Float): Int {
            val c = (1 - kotlin.math.abs(2 * l - 1)) * s
            val x = c * (1 - kotlin.math.abs((h / 60) % 2 - 1))
            val m = l - c / 2
            val (r, g, b) = when ((h / 60).toInt()) {
                0 -> Triple(c, x, 0f)
                1 -> Triple(x, c, 0f)
                2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c)
                4 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            fun ch(v: Float) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
            return 0xFF000000.toInt() or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }

        /** [amount] of [fg] over [bg]. */
        fun blend(fg: Int, bg: Int, amount: Float): Int {
            fun ch(c: Int, s: Int) = (c shr s) and 0xFF
            fun mix(s: Int) = (ch(fg, s) * amount + ch(bg, s) * (1 - amount)).roundToInt() and 0xFF
            return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }

        // ▖ ▗ ▘ ▙ ▚ ▛ ▜ ▝ ▞ ▟
        private val QUADRANTS = intArrayOf(4, 8, 1, 1 or 4 or 8, 1 or 8, 1 or 2 or 4, 1 or 2 or 8, 2, 2 or 4, 2 or 4 or 8)

        /**
         * U+2500..U+257F, generated from the Unicode names. Two bits per arm (0 none, 1 light,
         * 2 heavy, 3 double): up, right, down, left; then the dash count (bits 8-10) and an arc
         * flag (bit 11, drawn as a square corner). 0 means "leave it to the font" (diagonals).
         */
        private val BOX = intArrayOf(
        68, 136, 17, 34, 836, 904, 785, 802, 1092, 1160, 1041, 1058, 20, 24, 36, 40,
        80, 144, 96, 160, 5, 9, 6, 10, 65, 129, 66, 130, 21, 25, 22, 37,
        38, 26, 41, 42, 81, 145, 82, 97, 98, 146, 161, 162, 84, 148, 88, 152,
        100, 164, 104, 168, 69, 133, 73, 137, 70, 134, 74, 138, 85, 149, 89, 153,
        86, 101, 102, 150, 90, 165, 105, 154, 169, 166, 106, 170, 580, 648, 529, 546,
        204, 51, 28, 52, 60, 208, 112, 240, 13, 7, 15, 193, 67, 195, 29, 55,
        63, 209, 115, 243, 220, 116, 252, 205, 71, 207, 221, 119, 255, 2068, 2128, 2113,
        2053, 0, 0, 0, 64, 1, 4, 16, 128, 2, 8, 32, 72, 33, 132, 18,
        )
    }
}
