package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.terminal.TerminalCanvas
import com.makefunstuff.herdr.core.terminal.TerminalEmulator
import com.makefunstuff.herdr.core.terminal.TerminalPainter
import com.makefunstuff.herdr.core.terminal.TerminalPalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PainterTest {
    private class Recorder : TerminalCanvas {
        val rects = mutableListOf<List<Float>>()
        val texts = mutableListOf<Pair<String, Float>>()
        val colors = mutableListOf<Int>()
        override fun fillRect(left: Float, top: Float, right: Float, bottom: Float, argb: Int) {
            rects += listOf(left, top, right, bottom)
        }
        override fun drawText(text: String, x: Float, baseline: Float, maxWidth: Float, argb: Int, bold: Boolean, italic: Boolean) {
            texts += text to maxWidth
            colors += argb
        }
    }

    private fun paint(text: String, cols: Int = 4): Recorder {
        val emu = TerminalEmulator(cols, 2)
        emu.feed(text.toByteArray())
        return Recorder().also { TerminalPainter().paint(emu, it, 10f, 20f, 15f, showCursor = false) }
    }

    @Test
    fun boxLinesAreShapesThatSpanTheirCells() {
        val r = paint("──│█")
        assertTrue(r.texts.isEmpty(), "box and block characters are drawn as shapes, not text: ${r.texts}")
        // Two horizontal lines join edge to edge across cells 0..2.
        assertTrue(r.rects.any { it[0] == 0f && it[2] >= 5f }, "${r.rects}")
        assertTrue(r.rects.any { it[0] <= 15f && it[2] == 20f }, "${r.rects}")
        // The vertical line in cell 2 runs the full cell height; the full block fills cell 3.
        assertTrue(r.rects.any { it[1] == 0f && it[3] == 20f && it[0] in 20f..30f })
        assertTrue(r.rects.contains(listOf(30f, 0f, 40f, 20f)))
    }

    @Test
    fun wideGlyphsGetTwoCellsAndTextGetsOne() {
        val r = paint("a界")
        assertEquals(listOf("a" to 10f, "界" to 20f), r.texts)
    }

    @Test
    fun minimumContrastLiftsUnreadableTextButNotBoxLines() {
        // White text on a paper-coloured terminal, as Claude Code draws its titles in a light theme.
        val paper = 0xFFFAF8F3.toInt()
        val palette = TerminalPalette(0xFF2A251F.toInt(), paper, 0xFF8C5000.toInt(), TerminalPalette.Default.ansi, minimumContrast = 4.5)
        val emu = TerminalEmulator(4, 1)
        emu.feed("\u001b[38;2;255;255;255mab─".toByteArray())
        val r = Recorder()
        TerminalPainter(palette).paint(emu, r, 10f, 20f, 15f, showCursor = false)
        assertEquals(listOf("a", "b"), r.texts.map { it.first })
        r.colors.forEach { assertTrue(TerminalPainter.contrast(it, paper) >= 4.5, "text lifted to 4.5:1") }
        // Readable colours are left as the program asked.
        assertEquals(0xFF2A251F.toInt(), TerminalPainter.ensureContrast(0xFF2A251F.toInt(), paper, 4.5))
    }
}

class LightSurfaceTest {
    private val ground = 0xFFF7F5EF.toInt()
    private fun hex(c: Int) = "%06X".format(c and 0xFFFFFF)

    @Test
    fun darkProgramBackgroundsTurnLightOnALightGround() {
        val p = com.makefunstuff.herdr.core.terminal.TerminalPainter
        // Codex's prompt shade, from a dark aubergine terminal: a light panel near the ground.
        val codex = p.lightSurface(0xFF48273E.toInt(), ground)
        kotlin.test.assertTrue(p.luminance(codex) > 0.75, hex(codex))
        kotlin.test.assertTrue(p.luminance(codex) < p.luminance(ground), hex(codex))
        // Claude's dark diff red keeps its hue: red over green and blue.
        val red = p.lightSurface(0xFF3D0100.toInt(), ground)
        kotlin.test.assertTrue((red shr 16 and 0xFF) > (red shr 8 and 0xFF), hex(red))
        // Light backgrounds stay as the program set them.
        kotlin.test.assertEquals(0xFFDDEEFF.toInt(), p.lightSurface(0xFFDDEEFF.toInt(), ground))
    }
}
