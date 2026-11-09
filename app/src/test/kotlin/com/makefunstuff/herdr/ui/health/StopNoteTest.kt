package com.makefunstuff.herdr.ui.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stop dialog names the signals on Linux and macOS, and not on Windows, which has none. */
class StopNoteTest {
    @Test
    fun `unix machines get SIGTERM then SIGKILL`() {
        val t = stopNote("devbox", windows = false)
        assertTrue(t, "SIGTERM on devbox" in t && "SIGKILL" in t)
    }

    @Test
    fun `windows machines get no signals`() {
        val t = stopNote("office-pc", windows = true)
        assertFalse(t, "SIG" in t)
        assertTrue(t, "office-pc" in t && "5 seconds" in t)
    }
}
