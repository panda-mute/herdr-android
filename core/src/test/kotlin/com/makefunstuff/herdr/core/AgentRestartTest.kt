package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentSessionRef
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.chat.AgentPort
import com.makefunstuff.herdr.core.chat.AgentRestart
import com.makefunstuff.herdr.core.chat.AgentSettings
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AgentRestartTest {
    /** A pane running an agent: it quits on [quitsOn], and the resume command brings it back at rest. */
    private class Pane(var status: AgentStatus?, val quitsOn: String = "/exit") : AgentPort {
        val log = mutableListOf<String>()
        override suspend fun read(paneId: String, lines: Int, visible: Boolean, ansi: Boolean) = ""
        override suspend fun status(paneId: String) = status
        override suspend fun keys(paneId: String, keys: List<String>, named: Boolean) {
            log += "keys " + keys.joinToString()
            if (keys == listOf("esc") && status == AgentStatus.Working) status = AgentStatus.Idle
        }
        override suspend fun type(paneId: String, text: String) {
            log += "type $text"
            if (text == quitsOn) status = null
            if (status == null && text.contains("--resume") || text.startsWith("codex resume")) status = AgentStatus.Idle
        }
        override suspend fun prompt(paneId: String, text: String): Boolean { log += "prompt $text"; return true }
    }

    private val ref = AgentSessionRef("claude", "id", "6877705b-c46f")

    @Test
    fun quitsReopensAndSends() = runBlocking {
        val pane = Pane(AgentStatus.Working)
        val phases = mutableListOf<AgentRestart.Phase>()
        AgentRestart.run(pane, "w1:p1", "claude", ref, "carry on", { phases += it }, stepMs = 1)
        assertEquals(listOf("keys esc", "type /exit", "type claude --resume 6877705b-c46f", "prompt carry on"), pane.log)
        assertEquals(AgentRestart.Phase.entries.toList(), phases)
    }

    @Test
    fun ctrlCWhenTheQuitIsIgnored() = runBlocking {
        val inner = Pane(AgentStatus.Idle, quitsOn = "never")
        val port = object : AgentPort by inner {
            override suspend fun keys(paneId: String, keys: List<String>, named: Boolean) {
                inner.keys(paneId, keys, named)
                if (inner.log.count { it == "keys C-c" } == 2) inner.status = null
            }
        }
        AgentRestart.run(port, "w1:p1", "claude", ref, null, {}, stepMs = 1, quitMs = 40)
        assertEquals(listOf("type /exit", "keys C-c", "keys C-c", "type claude --resume 6877705b-c46f"), inner.log)
    }

    @Test
    fun givesUpWhenItWontQuit() = runBlocking<Unit> {
        assertFailsWith<IOException> {
            AgentRestart.run(Pane(AgentStatus.Idle, quitsOn = "never"), "w1:p1", "claude", ref, null, {}, stepMs = 1, quitMs = 20)
        }
    }

    @Test
    fun onlyKnownAgentsAndPlainIds() {
        assertEquals("codex resume 0199-a", AgentRestart.resumeCommand("codex", AgentSessionRef("codex", "id", "0199-a")))
        assertNull(AgentRestart.resumeCommand("pi", AgentSessionRef("pi", "path", "/x.jsonl")))
        assertNull(AgentRestart.resumeCommand("claude", AgentSessionRef("claude", "id", "x; rm -rf ~")))
    }

    @Test
    fun claudeSettings() {
        assertEquals("/model sonnet", AgentSettings.modelCommand("sonnet"))
        assertEquals("/effort xhigh", AgentSettings.effortCommand("xhigh"))
        assertFailsWith<IllegalArgumentException> { AgentSettings.modelCommand("opus; /exit") }
        assertEquals("opus", AgentSettings.family("claude-opus-5-5"))
        assertNull(AgentSettings.family("gpt-5.5"))
    }
}
