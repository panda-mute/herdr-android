package com.makefunstuff.herdr

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanesTest {
    @Test fun narrowestWideWindowLeavesTheAppRoom() {
        val side = Panes.sidebar(WIDE)
        assertEquals(256.dp, side)
        // The app keeps more than a phone's width.
        assert(WIDE - side >= 412.dp) { "app is ${WIDE - side}" }
    }

    @Test fun bigWindowsGiveTheRestToTheApp() {
        assertEquals(320.dp, Panes.sidebar(1920.dp))
    }

    @Test fun boardRoutes() {
        assertEquals("m1" to null, Routes.boardOf("board/m1"))
        assertNull(Routes.boardOf("agent/m1/w1:p1"))
        assertNull(Routes.boardOf(Routes.TASKS))
    }
}
