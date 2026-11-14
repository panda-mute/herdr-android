package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.chat.SentRecall

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SentRecallTest {
    private val h = SentRecall.history(listOf("first", "  ", "second", "second", "third"))

    @Test
    fun historyDropsBlanksAndRepeats() {
        assertEquals(listOf("first", "second", "third"), h)
    }

    @Test
    fun stepsBackThenForwardToAnEmptyBox() {
        val a = SentRecall.older(h, "", null)!!
        assertEquals(SentRecall.Step(0, "third"), a)
        val b = SentRecall.older(h, a.text, a.at)!!
        assertEquals(SentRecall.Step(1, "second"), b)
        val c = SentRecall.older(h, b.text, b.at)!!
        assertEquals(SentRecall.Step(2, "first"), c)
        // The oldest: no further.
        assertNull(SentRecall.older(h, c.text, c.at))
        val d = SentRecall.newer(h, c.text, c.at)!!
        assertEquals(SentRecall.Step(1, "second"), d)
        val e = SentRecall.newer(h, "third", 0)!!
        assertEquals(SentRecall.Step(null, ""), e)
        // An empty box goes no newer.
        assertNull(SentRecall.newer(h, "", null))
    }

    @Test
    fun aDraftStaysAsItIs() {
        assertNull(SentRecall.older(h, "half written", null))
        // A message brought back and then edited is a draft.
        assertNull(SentRecall.older(h, "third!", 0))
        assertNull(SentRecall.newer(h, "third!", 0))
    }

    @Test
    fun nothingSentNothingToBringBack() {
        assertNull(SentRecall.older(emptyList(), "", null))
    }
}
