package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.chat.SentMatch
import com.makefunstuff.herdr.core.chat.SentMatch.Pending
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SentMatchTest {
    private val msg = "I still don't get where why and how I can use pi durable"
    private fun open(p: List<Pending>, seen: List<String>) = SentMatch.unconfirmed(p) { SentMatch.count(it.text, seen) }

    @Test fun transcriptConfirms() {
        assertEquals(1, open(listOf(Pending(msg, 0)), emptyList()).size)
        assertEquals(0, open(listOf(Pending(msg, 0)), listOf(msg)).size)
    }

    @Test fun whitespaceMarkdownAndAppendedBoardLine() {
        assertTrue(SentMatch.same("fix  the\nbug in *login* flow please", "fix the bug in login flow please\n\n[sent with tab]"))
        assertTrue(SentMatch.same(msg, "$msg\n[sent with tab]"))
    }

    @Test fun truncation() {
        val long = "word ".repeat(100)
        assertTrue(SentMatch.same(long, long.take(300)))
    }

    @Test fun shortDifferentMessagesDontMatch() {
        assertFalse(SentMatch.same("ok", "ok thanks a lot for that"))
        assertFalse(SentMatch.same("yes", "no"))
    }

    @Test fun identicalMessagesStayTwo() {
        val two = listOf(Pending(msg, 0), Pending(msg, 0))
        assertEquals(2, open(two, emptyList()).size)
        assertEquals(1, open(two, listOf(msg)).size)
        assertEquals(0, open(two, listOf(msg, msg)).size)
    }

    @Test fun earlierIdenticalMessageDoesNotConfirm() {
        // Same text was said before; only a second copy confirms the new one.
        assertEquals(1, open(listOf(Pending(msg, 1)), listOf(msg)).size)
        assertEquals(0, open(listOf(Pending(msg, 1)), listOf(msg, msg)).size)
    }

    @Test fun reconnectReplayDoesNotConfirmOrDuplicate() {
        // A reconnect re-reads the same transcript: the count is unchanged, nothing is added or lost.
        val seen = listOf(msg)
        assertEquals(1, open(listOf(Pending(msg, 1)), seen).size)
        assertEquals(1, open(open(listOf(Pending(msg, 1)), seen), seen).size)
    }

    @Test fun screenSource() {
        val screen = "> I still don't get where why and\n  how I can use pi durable"
        assertEquals(1, SentMatch.countOnScreen(msg, screen.replace("\n  ", " ")))
        assertEquals(0, open(listOf(Pending(msg, 0)).also { }, emptyList()).size - 1)
        assertEquals(0, SentMatch.unconfirmed(listOf(Pending(msg, 0))) { SentMatch.countOnScreen(it.text, "> $msg\nreply") }.size)
        assertEquals(1, SentMatch.unconfirmed(listOf(Pending(msg, 1))) { SentMatch.countOnScreen(it.text, "> $msg\nreply") }.size)
    }

    @Test fun reviewProbes() {
        assertTrue(SentMatch.same("yes go ahead", "yes go ahead\n[sent with tab]"))
        assertFalse(SentMatch.same("Please look at task 5 now", "Please look at task 5 now and also task 6"))
        assertEquals(1, SentMatch.countOnScreen(msg, "> I still don't get where why an\nd how I can use pi durable"))
    }

    @Test fun slidingWindowStillConfirms() {
        val p = Pending("yes", 3, sentAt = 1_000)
        // An old copy scrolled out as the new one came in: 3 before, 3 now, but never fewer-and-stuck.
        assertEquals(Int.MAX_VALUE, SentMatch.slid(p, 2, now = 2_000))
        assertEquals(4, SentMatch.slid(p, 4, now = 2_000))
        assertEquals(3, SentMatch.slid(p, 3, now = 2_000))
        // Same count a while later (one in, one out): confirmed by age.
        assertEquals(Int.MAX_VALUE, SentMatch.slid(p, 3, now = 40_000))
        assertEquals(0, SentMatch.unconfirmed(listOf(p)) { SentMatch.slid(it, 3, 40_000) }.size)
        assertEquals(1, SentMatch.unconfirmed(listOf(p)) { SentMatch.slid(it, 3, 2_000) }.size)
    }
}
