package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.chat.StickToBottom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StickToBottomTest {
    private val three = listOf("a", "b", "c")

    @Test fun opensAtTheNewest() {
        val first = StickToBottom().seen(three, atEnd = false)
        assertTrue(first.toEnd)
        assertNull(first.next.unread)
    }

    @Test fun anEmptyChatWaitsForItsFirstMessages() {
        val empty = StickToBottom().seen(emptyList(), atEnd = true)
        assertFalse(empty.toEnd)
        // Loaded a moment later (or a space switched to): still opens at the newest, with no New line.
        val loaded = empty.next.seen(three, atEnd = false)
        assertTrue(loaded.toEnd)
        assertNull(loaded.next.unread)
    }

    @Test fun aNewMessageBringsYouDownFromTheEnd() {
        val s = StickToBottom().seen(three, true).next
        val r = s.seen(three + "d", atEnd = true)
        assertTrue(r.toEnd)
        assertNull(r.next.unread)
    }

    @Test fun aNewMessageLeavesTheReaderFurtherUp() {
        val up = StickToBottom().seen(three, true).next.moved(atEnd = false, three)
        val r = up.seen(three + "d" + "e", atEnd = false)
        assertFalse(r.toEnd)
        // The New line goes above the first one that came while you read.
        assertEquals("d", r.next.unread)
    }

    @Test fun theNewLineMarksTheFirstUnseenAcrossSeveralArrivals() {
        val up = StickToBottom().seen(three, true).next.moved(atEnd = false, three)
        // "d" came while up but the jump down didn't happen yet (the reader dragged on): "e" keeps "d" as the first new.
        val once = up.seen(three + "d", atEnd = false).next
        val twice = once.seen(three + "d" + "e", atEnd = false)
        assertFalse(twice.toEnd)
        assertEquals("d", twice.next.unread)
    }

    @Test fun growthIsFollowedOnlyAtTheEnd() {
        val s = StickToBottom().seen(three, true).next
        // The same messages, the newest longer (or the working line, a picture): followed at the end…
        assertTrue(s.seen(three, atEnd = true).toEnd)
        // …and left alone while you read further up.
        assertFalse(s.moved(atEnd = false, three).seen(three, atEnd = false).toEnd)
    }

    @Test fun aMessageGoneIsNoReasonToMove() {
        val up = StickToBottom().seen(three, true).next.moved(atEnd = false, three)
        assertFalse(up.seen(listOf("a", "b"), atEnd = false).toEnd)
    }

    @Test fun aMessageFoldedAwayAndShownAgainIsNotNew() {
        val up = StickToBottom().seen(three, true).next.moved(atEnd = false, three)
        val folded = up.seen(listOf("a"), atEnd = false).next
        val again = folded.seen(three, atEnd = false)
        assertFalse(again.toEnd)
        assertNull(again.next.unread)
    }

    @Test fun theCountIsWhatCameSinceYouLeftTheEnd() {
        val s = StickToBottom().seen(three, true).next
        assertEquals(0, s.fresh(three + "d"))
        val up = s.moved(atEnd = false, three)
        assertEquals(2, up.fresh(three + "d" + "e"))
        // Leaving again further up keeps what was there when you first left.
        assertEquals(2, up.moved(atEnd = false, three + "d").fresh(three + "d" + "e"))
        assertEquals(0, up.moved(atEnd = true, three + "d" + "e").fresh(three + "d" + "e"))
    }

    @Test fun sendingClearsTheNewLine() {
        val up = StickToBottom().seen(three, true).next.moved(atEnd = false, three)
        val down = up.seen(three + "d", atEnd = false).next
        assertEquals("d", down.unread)
        val sent = down.sent()
        assertNull(sent.unread)
        assertEquals(0, sent.fresh(three + "d"))
    }

    @Test fun prependedHistoryDoesNotJumpOrCountAsUnread() {
        val up = StickToBottom().seen(three, true).next.moved(false, three)
        val expanded = listOf("older1", "older2") + three
        val loaded = up.seen(expanded, false)
        assertFalse(loaded.toEnd)
        assertEquals(0, loaded.next.fresh(expanded))
        assertNull(loaded.next.unread)
        val arriving = loaded.next.seen(expanded + "d", false)
        assertFalse(arriving.toEnd)
        assertEquals(1, arriving.next.fresh(expanded + "d"))
        assertEquals("d", arriving.next.unread)
    }

    @Test fun historyAndNewReplyInOneUpdateKeepPosition() {
        val up = StickToBottom().seen(three, true).next.moved(false, three)
        val expanded = listOf("older") + three + "d"
        val result = up.seen(expanded, false)
        assertFalse(result.toEnd)
        assertEquals("d", result.next.unread)
        assertEquals(1, result.next.fresh(expanded))
    }

    @Test fun nearTheEndCountsAsAtIt() {
        assertTrue(StickToBottom.atEnd(0, 0, slop = 80))
        assertTrue(StickToBottom.atEnd(0, 80, slop = 80))
        assertFalse(StickToBottom.atEnd(0, 81, slop = 80))
        assertFalse(StickToBottom.atEnd(1, 0, slop = 80))
    }
}
