package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.chat.AgentChat
import com.makefunstuff.herdr.core.chat.ChatItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatCatchUpTest {
    private val shown = listOf(ChatItem.User("u1", "hi"), ChatItem.Agent("a1", "hello"))

    @Test
    fun nothingShownIsCaughtUp() = assertTrue(AgentChat.caughtUp(emptyList(), emptyList()))

    @Test
    fun anEmptyOrPartialReadIsNot() {
        assertFalse(AgentChat.caughtUp(shown, emptyList()))
        assertFalse(AgentChat.caughtUp(shown, listOf(ChatItem.User("u1", "hi"))))
    }

    @Test
    fun theLastItemByIdOrPastIt() {
        assertTrue(AgentChat.caughtUp(shown, shown))
        assertTrue(AgentChat.caughtUp(shown, shown + ChatItem.User("u2", "more")))
        // A tool that finished since keeps its id.
        val tool = ChatItem.Tool("t1", "Bash", "ls")
        assertTrue(AgentChat.caughtUp(listOf(tool), listOf(tool.copy(output = "a", state = com.makefunstuff.herdr.core.chat.ToolState.Done))))
    }

    @Test
    fun idsCountedFromTheReadMatchByWords() {
        // Older Codex logs number their items from where the read began.
        assertTrue(AgentChat.caughtUp(shown, listOf(ChatItem.User("u7", "hi"), ChatItem.Agent("a8", "hello"))))
        assertFalse(AgentChat.caughtUp(shown, listOf(ChatItem.Agent("a8", "hello again"))))
    }
}
