package com.makefunstuff.herdr.ui.agents

import com.makefunstuff.herdr.core.chat.ChatItem
import com.makefunstuff.herdr.core.chat.ChatTurn
import com.makefunstuff.herdr.core.chat.ChatTurns
import com.makefunstuff.herdr.core.chat.ToolState
import com.makefunstuff.herdr.core.chat.WorkCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRowsTest {
    private val steps = ChatTurns.Steps(emptyList())

    private fun turn(key: String, at: Long, end: Long, answer: Boolean = true, failed: Int = 0) = ChatTurn(
        key = key,
        user = ChatItem.User(key, "Do $key"),
        work = listOf(ChatItem.Agent("$key/n", "Looking."), ChatItem.Tool("$key/t", "Bash", "ls", state = ToolState.Done)),
        answer = if (answer) listOf(ChatItem.Agent("$key/a", "Done.")) else emptyList(),
        at = at,
        end = end,
        counts = WorkCounts(commands = 1),
        failed = failed,
        keepsOpen = !answer,
    )

    private fun rows(turns: List<ChatTurn>, live: ChatRow.Live? = null, opened: Map<String, Boolean> = emptyMap(), thinking: String? = null) =
        chatRows(turns, steps, live, opened, now = 200_000, thinking = thinking) { "t$it" }

    @Test fun changesOpenWithoutOpeningTheToolsAndOnlyIncludeCompletedEdits() {
        val diff = com.makefunstuff.herdr.core.code.UnifiedDiff.parse("diff --git a/file b/file\n--- a/file\n+++ b/file\n@@ -1 +1 @@\n-old\n+new").single()
        val done = ChatItem.Tool("edit", "Edit", "file", state = ToolState.Done, diffs = listOf(diff))
        val turn = turn("u", 1, 2).copy(work = listOf(done, done, done.copy(id = "running", state = ToolState.Running), done.copy(id = "failed", state = ToolState.Failed)))
        val r = rows(listOf(turn), opened = mapOf("changes/u" to true))
        assertEquals(listOf(diff), r.filterIsInstance<ChatRow.Changes>().single().diffs)
        assertEquals(diff, r.filterIsInstance<ChatRow.Diff>().single().diff)
        assertTrue(r.none { it is ChatRow.Tools })
    }

    @Test fun aSettledTurnFoldsItsWorkAndKeepsItsAnswerOut() {
        val r = rows(listOf(turn("u1", 1_000, 157_000, failed = 1)))
        assertEquals(listOf("u1", "head/u1", "u1/a"), r.map { it.key })
        val head = r[1] as ChatRow.Head
        assertEquals("Worked for 2m 36s", head.title)
        assertEquals("1 command", head.summary)
        assertEquals(1, head.failed)
        assertEquals("t1000", head.time)
        assertFalse(head.open)
    }

    @Test fun theRunningTurnIsOpenAndTimedFromNow() {
        val r = rows(listOf(turn("u1", 1_000, 2_000), turn("u2", 20_000, 30_000, answer = false)), live = ChatRow.Live.Working)
        val head = r.filterIsInstance<ChatRow.Head>().last()
        assertEquals("Working for 3m", head.title)
        assertTrue(head.open)
        // Its narration speaks in the agent's voice while it runs.
        // Its call came back and nothing new is written yet: the model is thinking.
        assertEquals(listOf("head/u2", "u2/n", "tools/u2/t", "thinking/u2"), r.dropWhile { it.key != "head/u2" }.map { it.key })
        assertFalse((r.first { it.key == "u2/n" } as ChatRow.Agent).quiet)
        assertEquals("Needs you", rows(listOf(turn("u1", 1_000, 2_000, answer = false)), live = ChatRow.Live.Blocked).filterIsInstance<ChatRow.Head>().single().title)
    }

    @Test fun anAnsweredTurnDoesNotReopenForWorkTheTranscriptHasntShown() {
        // Working, but the prompt it works on (typed in the terminal) isn't in the transcript yet.
        val old = rows(listOf(turn("u1", 1_000, 8_000)), live = ChatRow.Live.Working).filterIsInstance<ChatRow.Head>().single()
        assertEquals("Worked for 7s", old.title)
        assertFalse(old.open)
        // Words just now are narration on the way to its next call.
        assertEquals("Working for 3m 19s", rows(listOf(turn("u1", 1_000, 190_000)), live = ChatRow.Live.Working).filterIsInstance<ChatRow.Head>().single().title)
        // A question waits for you however long ago it came.
        assertEquals("Needs you", rows(listOf(turn("u1", 1_000, 8_000)), live = ChatRow.Live.Blocked).filterIsInstance<ChatRow.Head>().single().title)
    }

    @Test fun aTurnOpenedByHandShowsItsWorkQuietly() {
        val r = rows(listOf(turn("u1", 1_000, 8_000)), opened = mapOf("u1" to true))
        assertEquals(listOf("u1", "head/u1", "u1/n", "tools/u1/t", "u1/a"), r.map { it.key })
        assertTrue((r.first { it.key == "u1/n" } as ChatRow.Agent).quiet)
        assertEquals("Worked for 7s", (r[1] as ChatRow.Head).title)
    }

    @Test fun onlyMessagesCountAsNew() {
        val r = rows(listOf(turn("u1", 1_000, 8_000)), opened = mapOf("u1" to true))
        assertEquals(listOf("u1", "u1/a"), r.filter { it.isMessage }.map { it.key })
    }

    private fun thinking(answered: Boolean) = ChatTurn(
        key = "u1",
        user = ChatItem.User("u1", "Fix it"),
        work = listOf(ChatItem.Reasoning("u1/r0", "Read the file first."), ChatItem.Tool("u1/t", "Read", "/x/a.kt", state = ToolState.Done)),
        answer = listOfNotNull(ChatItem.Reasoning("u1/r1", "**It's a typo**\n\nIn the second line."), ChatItem.Agent("u1/a", "Fixed.").takeIf { answered }),
        at = 1_000,
        end = 190_000,
        counts = WorkCounts(reads = 1),
        failed = 0,
        keepsOpen = false,
    )

    @Test fun aThoughtFoldsAboveTheAnswerItLedTo() {
        val r = rows(listOf(thinking(answered = true)))
        assertEquals(listOf("u1", "head/u1", "thought/u1/r1", "u1/a"), r.map { it.key })
        val thought = r[2] as ChatRow.Thought
        assertFalse(thought.open)
        assertFalse(thought.live)
        assertFalse(thought.isMessage)
        // Opened by hand: its pieces follow it as rows of their own; the one in the work stays folded with it.
        val open = rows(listOf(thinking(answered = true)), opened = mapOf("thought/u1/r1" to true))
        assertEquals(listOf("u1", "head/u1", "thought/u1/r1", "thought/u1/r1/0", "u1/a"), open.map { it.key })
        assertTrue((open[2] as ChatRow.Thought).open)
        assertEquals(listOf("thought/u1/r0", "tools/u1/t"), rows(listOf(thinking(answered = true)), opened = mapOf("u1" to true)).map { it.key }.drop(2).take(2))
    }

    @Test fun theNewestThoughtOfAWorkingTurnIsLive() {
        val r = rows(listOf(thinking(answered = false)), live = ChatRow.Live.Working)
        assertTrue(r.filterIsInstance<ChatRow.Thought>().single { it.item.id == "u1/r1" }.live)
        assertFalse(r.filterIsInstance<ChatRow.Thought>().single { it.item.id == "u1/r0" }.live)
        // The live thought says it thinks; no second line does.
        assertTrue(r.none { it is ChatRow.Thinking })
        // Settled, nothing is live.
        assertTrue(rows(listOf(thinking(answered = false))).filterIsInstance<ChatRow.Thought>().none { it.live })
    }

    @Test fun noThinkingLineUnderItsWords() {
        // Answered 10 s ago and still reported working (a hook runs): its words end the turn.
        val r = rows(listOf(turn("u1", 1_000, 190_000)), live = ChatRow.Live.Working, opened = mapOf("u1" to true))
        assertTrue(r.none { it is ChatRow.Thinking })
        assertEquals("u1/a", r.last().key)
    }

    @Test fun theThoughtOnScreenIsLiveUntilTheTranscriptHasIt() {
        val r = rows(listOf(thinking(answered = false)), live = ChatRow.Live.Working, thinking = "Now the card.")
        val live = r.last() as ChatRow.Thought
        assertEquals("thought/live/u1", live.key)
        assertTrue(live.live && live.partial)
        assertEquals("Now the card.", live.item.text)
        // The transcript's thoughts are settled ones then.
        assertTrue(r.filterIsInstance<ChatRow.Thought>().filter { !it.partial }.none { it.live })
        // Not once the turn settled.
        assertTrue(rows(listOf(thinking(answered = false)), thinking = "Now the card.").none { it is ChatRow.Thought && it.partial })
    }
}
