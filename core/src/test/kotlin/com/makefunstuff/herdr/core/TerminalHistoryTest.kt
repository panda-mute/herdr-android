package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.terminal.TerminalEmulator
import com.makefunstuff.herdr.core.terminal.TerminalFind
import com.makefunstuff.herdr.core.terminal.TerminalHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** herdr-core's terminal keeps its history on the phone: scrolled through, filled from `terminal.read`, found in. */
class TerminalHistoryTest {
    private fun numbered(emu: TerminalEmulator, from: Int, to: Int) {
        for (i in from..to) emu.feed("line $i\r\n")
    }

    @Test
    fun linesScrolledOffTheTopAreKeptAndShownWhenScrolledBack() {
        val emu = TerminalEmulator(20, 4, historyLimit = 100)
        numbered(emu, 1, 10)
        // The screen: lines 8 to 10 and the cursor's empty line.
        assertEquals("line 8", emu.lineText(0))
        assertEquals(7, emu.historySize)
        assertTrue(emu.scrollBack(3))
        assertEquals(listOf("line 5", "line 6", "line 7", "line 8"), (0 until 4).map(emu::lineText))
        // Past the oldest line it stops there.
        emu.scrollBack(50)
        assertEquals("line 1", emu.lineText(0))
        assertFalse(emu.scrollBack(1))
        emu.scrollBack(-emu.scrolledBack)
        assertEquals("line 8", emu.lineText(0))
    }

    @Test
    fun newOutputLeavesAScrolledBackViewWhereItIs() {
        val emu = TerminalEmulator(20, 4, historyLimit = 100)
        numbered(emu, 1, 10)
        emu.scrollBack(5)
        assertEquals("line 3", emu.lineText(0))
        numbered(emu, 11, 12)
        assertEquals("line 3", emu.lineText(0))
        assertEquals(7, emu.scrolledBack)
    }

    @Test
    fun theHistoryKeepsItsLimitAndClassicHerdrKeepsNone() {
        val emu = TerminalEmulator(20, 4, historyLimit = 5)
        numbered(emu, 1, 20)
        assertEquals(5, emu.historySize)
        emu.scrollBack(5)
        assertEquals("line 13", emu.lineText(0))
        assertEquals(12L, emu.topLine)

        val classic = TerminalEmulator(20, 4)
        numbered(classic, 1, 20)
        assertEquals(0, classic.historySize)
        assertFalse(classic.scrollBack(1))
    }

    @Test
    fun theAlternateScreenAndAScrollRegionAddNothingAndClearingTheScrollbackEmptiesIt() {
        val emu = TerminalEmulator(20, 4, historyLimit = 100)
        emu.feed("\u001b[?1049h")
        numbered(emu, 1, 10)
        emu.feed("\u001b[?1049l")
        assertEquals(0, emu.historySize)
        // A region below the top (a status line kept above it) scrolls nothing into the history.
        emu.feed("\u001b[2;4r")
        numbered(emu, 1, 10)
        assertEquals(0, emu.historySize)
        emu.feed("\u001b[r\u001b[H")
        numbered(emu, 1, 10)
        assertTrue(emu.historySize > 0)
        emu.scrollBack(2)
        emu.feed("\u001b[3J")
        assertEquals(0, emu.historySize)
        assertEquals(0, emu.scrolledBack)
    }

    @Test
    fun theCursorLineIsTheScreensWhileScrolledBack() {
        val emu = TerminalEmulator(20, 4, historyLimit = 100)
        numbered(emu, 1, 10)
        emu.feed("Password: ")
        emu.scrollBack(4)
        assertEquals("Password:", emu.cursorLineText())
        assertEquals("line 4", emu.lineText(0))
    }

    @Test
    fun readsHistoryGoesAboveTheFirstPaintOnce() {
        val screen = listOf("line 8", "line 9", "line 10", "$ ")
        val read = (1..10).map { "line $it" } + "$"
        assertEquals((1..7).map { "line $it" }, TerminalHistory.above(read, screen, 4))
        // Typed on the prompt and run between the read and the attach: the overlap is shorter, its last line longer.
        val later = listOf("line 9", "line 10", "$ ls", "")
        assertEquals((1..8).map { "line $it" }, TerminalHistory.above(read, later, 4))
        // Nothing in common (the screen was redrawn): the read's screen, its last rows, goes.
        assertEquals((1..7).map { "line $it" }, TerminalHistory.above(read, listOf("redrawn"), 4))
        // A full-screen program's read is its screen alone: nothing above it.
        assertEquals(emptyList(), TerminalHistory.above(screen, screen, 4))
    }

    @Test
    fun prependedHistoryIsScrolledToAboveTheKeptLines() {
        val emu = TerminalEmulator(20, 4, historyLimit = 10)
        numbered(emu, 101, 106)
        emu.prependHistory((1..20).map { "old $it" })
        // Within the limit: the newest of the read's lines.
        assertEquals(10, emu.historySize)
        emu.scrollBack(10)
        assertEquals(listOf("old 14", "old 15", "old 16", "old 17"), (0 until 4).map(emu::lineText))
    }

    @Test
    fun findGoesFromTheNewestMatchToOlderOnesAndRoundAgain() {
        val emu = TerminalEmulator(30, 4, historyLimit = 100)
        emu.feed("build ok\r\nerror: one\r\n")
        numbered(emu, 1, 10)
        emu.feed("an ERROR: two\r\n")
        numbered(emu, 11, 13)
        emu.feed("$ ")
        val find = TerminalFind(emu)
        val two = find.find("error")
        assertEquals(TerminalFind.Found(2, 1), two)
        assertEquals(TerminalEmulator.Mark(12, 3, 7), emu.mark)
        val one = find.find("error", backward = true)
        assertEquals(0, one.place)
        assertEquals(TerminalEmulator.Mark(1, 0, 4), emu.mark)
        // Scrolled to, a third down the view.
        assertEquals(0L, emu.topLine)
        // Older than the oldest: round to the newest; newer than the newest: to the oldest.
        assertEquals(1, find.find("error", backward = true).place)
        assertEquals(0, find.find("error", backward = false).place)
        assertEquals(TerminalFind.Found(0, null), find.find("nowhere"))
        assertNull(emu.mark)
    }

    @Test
    fun findCountsWideCharactersAsTheirCells() {
        val emu = TerminalEmulator(30, 4, historyLimit = 100)
        emu.feed("日本 ok\r\n")
        TerminalFind(emu).find("ok")
        assertEquals(TerminalEmulator.Mark(0, 5, 6), emu.mark)
    }
}
