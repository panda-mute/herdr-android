package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.ssh.HerdrCommands
import com.makefunstuff.herdr.core.ssh.Machine
import com.makefunstuff.herdr.core.terminal.PhoneHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneHistoryTest {
    @Test
    fun `history is as tall as its wrapped lines, last line at the bottom`() {
        val emu = PhoneHistory.build("one\n\u001B[31mtwo\u001B[0m\n" + "x".repeat(25) + "\n\n\n", cols = 10)
        // one, two, then 25 x's wrapped over three rows; trailing blank lines dropped.
        assertEquals(5, emu.rows)
        assertEquals("one", emu.lineText(0).trimEnd())
        assertEquals("two", emu.lineText(1).trimEnd())
        assertEquals("xxxxx", emu.lineText(4).trimEnd())
    }

    @Test
    fun `wide characters count twice when wrapping`() {
        assertEquals(4, PhoneHistory.visibleWidth("\u001B[1m中文\u001B[0m"))
        assertEquals(2, PhoneHistory.build("中文中文中文", cols = 6).rows)
    }

    @Test
    fun `watching a pane neither takes it over nor sizes it`() {
        val m = Machine(id = "m", label = "m", host = "h", user = "u")
        val observe = HerdrCommands.terminalObserve(m, "w1:p1", 52, 28)
        assertTrue("terminal session observe" in observe.replace("'", ""))
        assertFalse("--takeover" in observe)
        assertTrue("--takeover" in HerdrCommands.terminalControl(m, "w1:p1", 52, 28))
    }
}
