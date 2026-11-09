package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.chat.ChatHeader
import com.makefunstuff.herdr.core.chat.ChatOwner
import com.makefunstuff.herdr.core.hc.HcJson
import com.makefunstuff.herdr.core.hc.HcMapping
import com.makefunstuff.herdr.core.hc.HcTerminal
import com.makefunstuff.herdr.core.shepherdr.ShepherdrJson
import com.makefunstuff.herdr.core.shepherdr.V2Chat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which conversation each chat maps to, on the real shapes of a Shepherdr 3.1 machine (`hc-chat-map.json`: scrubbed
 * `hc get` records, LiveChatMapTest's machine): space Assistants with and without the desktop app's `where`, a
 * task's worker behind a shell wrapper, a plain `claude --resume` in a space's tab, shells.
 */
class ChatMapTest {
    private val terminals: List<HcTerminal> =
        HcJson.decodeFromString(javaClass.getResource("/hc-chat-map.json")!!.readText())

    private fun t(address: String) = terminals.first { it.address == address }

    @Test
    fun everyAgentFollowsItsOwnProcess() {
        val s = HcMapping.snapshot(terminals)
        // Each agent's transcript is its own process's (the foreground's pid, never the wrapper shell's), so two
        // Claude Codes in one folder or space never share one.
        val refs = s.agents.associate { it.terminalId to it.agentSession?.value }
        assertEquals(t("t3k").foreground!!.pid.toString(), refs["t3k"])
        assertTrue(t("t3k").pid != t("t3k").foreground!!.pid, "the fixture's worker runs behind a wrapper")
        assertEquals(t("t2g").foreground!!.pid.toString(), refs["t2g"])
        assertEquals(t("t3m").foreground!!.pid.toString(), refs["t3m"])
        assertEquals(refs.size, refs.values.toSet().size, "no two agents map to one process")
        // Shells aren't chats.
        assertNull(s.agents.firstOrNull { it.terminalId == "t1y" })
    }

    @Test
    fun anAssistantWithoutWhereIsNamedAfterItsSpace() {
        val s = HcMapping.snapshot(terminals)
        val lfm = s.agents.first { it.terminalId == "t2c" }
        // Shepherdr started it before the desktop app showed it: no `where`, only its space's id. Its space's
        // shell names that space: it was a bare "Assistant", seven alike on the owner's machine.
        assertNull(t("t2c").labels["where"])
        assertEquals("lfm-pixel Assistant", lfm.name)
        assertEquals("lfm-pixel", s.workspaces.first { it.workspaceId == lfm.workspaceId }.label)
        assertEquals("herdr-android Assistant", s.agents.first { it.terminalId == "t2g" }.name)
    }

    @Test
    fun aSpaceIsNeverCalledByItsIdWhenATerminalNamesIt() {
        // The Assistant started first: its space was called "sp016af4…" after it.
        val first = terminals.map { if (it.address == "t2c") it.copy(createdMs = 1) else it }
        val s = HcMapping.snapshot(first)
        val lfm = s.agents.first { it.terminalId == "t2c" }
        assertEquals("lfm-pixel", s.workspaces.first { it.workspaceId == lfm.workspaceId }.label)
        assertEquals("lfm-pixel Assistant", lfm.name)
        // Alone in its space, it says nothing it doesn't know.
        val alone = HcMapping.snapshot(listOf(t("t2c")))
        assertEquals("Assistant", alone.agents.single().name)
    }

    @Test
    fun aTaskChatAndAnAssistantChatFindTheirAgents() {
        val agents = HcMapping.snapshot(terminals).agents
        assertEquals("t3k", ChatOwner.taskAgent(agents, "HERD-30")?.terminalId)
        assertEquals("t2g", ChatOwner.assistant(agents, "sp4f13f3f54d43fe5172da0f01afe6c059")?.terminalId)
        assertEquals("t2c", ChatOwner.assistant(agents, "sp016af4b35559de0efc7fc4d5ee9bf2d8")?.terminalId)
        // A plain agent in the Assistant's space is neither.
        assertNull(ChatOwner.taskAgent(agents, "HERD-29"))
        assertTrue(agents.first { it.terminalId == "t3m" }.let { it.shepherdrSpace == null && it.shepherdrTask == null })
    }

    @Test
    fun shepherdrsLostLogIsReadFromTheAgentItself() {
        // 3.1.2's answer for HERD-30's worker while it worked, its log on disk all along.
        val lost = ShepherdrJson.decodeFromString(
            V2Chat.serializer(),
            """{"key":"HERD-30/worker","messages":[],"doing":"Running sed","notice":"none","agent":{"kind":"claude","words":"Opus"},"working":true,"cli":"claude"}""",
        )
        val agents = HcMapping.snapshot(terminals).agents
        val worker = ChatOwner.taskAgent(agents, "HERD-30")
        assertTrue(ChatOwner.readsOwn(lost, worker))
        // Its log found, or no agent the app can read: Shepherdr's chat as it is.
        assertFalse(ChatOwner.readsOwn(lost.copy(notice = null), worker))
        assertFalse(ChatOwner.readsOwn(lost.copy(notice = "unsupported"), worker))
        assertFalse(ChatOwner.readsOwn(lost, null))
        assertFalse(ChatOwner.readsOwn(lost, worker!!.copy(agentSession = null)))
    }

    @Test
    fun aHeaderSaysWhoseChatItIs() {
        assertEquals("~/Work/lfm-pixel", ChatHeader.folder("/home/dev/Work/lfm-pixel"))
        assertEquals("~", ChatHeader.folder("/home/dev"))
        assertEquals("~/x", ChatHeader.folder("/Users/me/x"))
        assertEquals("/data/dev/Work", ChatHeader.folder("/data/dev/Work"))
        assertNull(ChatHeader.folder(" "))
        assertEquals("claude · lfm-pixel › 2 · ~/w", ChatHeader.line("claude", null, "lfm-pixel › 2", "", "~/w"))
        assertEquals("Claude · lfm-pixel", ChatHeader.line("Claude", "claude", "lfm-pixel"))
        assertNull(ChatHeader.machine("devbox", 1))
        assertEquals("devbox", ChatHeader.machine("devbox", 2))
        // Who: an Assistant as 3.1 calls it, a plain agent by what its terminal says it works on, else its space.
        val agents = HcMapping.snapshot(terminals).agents
        assertEquals("Assistant", ChatHeader.agentTitle(agents.first { it.terminalId == "t2c" }))
        assertEquals(terminals.first { it.address == "t3m" }.title, ChatHeader.agentTitle(agents.first { it.terminalId == "t3m" }, space = "voice-computer"))
        assertEquals("voice-computer", ChatHeader.agentTitle(agents.first { it.terminalId == "t3m" }.copy(terminalTitle = null), space = "voice-computer"))
        assertEquals("HERD-30 · Chats show the right conversation", ChatHeader.task("HERD-30", "Chats show the right conversation"))
    }
}
