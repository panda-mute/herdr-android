package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentSessionRef
import com.makefunstuff.herdr.core.api.HerdrApiException
import com.makefunstuff.herdr.core.api.PaneInfo
import com.makefunstuff.herdr.core.api.PaneProcess
import com.makefunstuff.herdr.core.api.PaneProcessInfo
import com.makefunstuff.herdr.core.chat.AgentFlags
import com.makefunstuff.herdr.core.chat.AgentRestart
import com.makefunstuff.herdr.core.chat.AgentSettings
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentRestartTest {
    /**
     * A pane whose shell (pid 10) runs an agent (group 20, started as [argv] in [agentCwd]); it
     * quits on the [quitsOn]th Ctrl+C, or never, and comes back when herdr starts it.
     */
    private class Pane(
        val argv: List<String>,
        val quitsOn: Int = 2,
        val agentCwd: String = "/home/me/app",
        var shellCwd: String = "/home/me/app",
        val notReady: Int = 0,
    ) : AgentRestart.Port {
        val log = mutableListOf<String>()
        var running = true
        private var ctrlC = 0
        private var unready = notReady

        override suspend fun processInfo(paneId: String) = PaneProcessInfo(
            paneId = paneId, shellPid = 10, foregroundGroupId = if (running) 20 else 10,
            processes = if (running) listOf(PaneProcess(20, argv.first(), argv, cwd = agentCwd)) else listOf(PaneProcess(10, "bash")),
        )
        override suspend fun pane(paneId: String) = PaneInfo(paneId = paneId, workspaceId = "w1", tabId = "w1:t1", agent = if (running) "claude" else null, foregroundCwd = shellCwd)
        override suspend fun keys(paneId: String, keys: List<String>) {
            log += "keys " + keys.joinToString()
            if (keys == listOf("ctrl+c") && ++ctrlC == quitsOn) running = false
        }
        override suspend fun type(paneId: String, text: String) {
            log += "type $text"
            if (text.startsWith("cd -- ")) shellCwd = agentCwd
        }
        override suspend fun start(name: String, kind: String, paneId: String, args: List<String>, timeoutMs: Long) {
            log += "start $kind " + args.joinToString(" ")
            running = true
        }
        override suspend fun prompt(target: String, text: String) {
            if (unready-- > 0) throw HerdrApiException("agent_not_ready", "")
            log += "prompt $text"
        }
        override suspend fun kill(group: Long, signal: String) {
            log += "kill $signal $group"
            if (signal == "KILL") running = false
        }
    }

    private val ref = AgentSessionRef("claude", "id", "6877705b-c46f")

    @Test
    fun keysQuitItAndHerdrStartsItAgainWithItsOptions() = runBlocking {
        val pane = Pane(listOf("claude", "--model", "opus", "--add-dir", "/a", "/b", "--continue", "--dangerously-skip-permissions"))
        val phases = mutableListOf<AgentRestart.Phase>()
        AgentRestart.run(pane, "w1:p1", "claude", ref, "carry on", { phases += it }, stepMs = 1)
        assertEquals(
            listOf("keys esc", "keys ctrl+c", "keys ctrl+c", "start claude --model opus --add-dir /a /b --resume 6877705b-c46f", "prompt carry on"),
            pane.log,
        )
        assertEquals(AgentRestart.Phase.entries.toList(), phases)
    }

    @Test
    fun aSignalWhenKeysDontQuitIt() = runBlocking {
        val pane = Pane(listOf("claude"), quitsOn = 99)
        AgentRestart.run(pane, "w1:p1", "claude", ref, null, {}, stepMs = 1, quitMs = 20, killMs = 20)
        assertEquals(listOf("kill TERM 20", "kill KILL 20"), pane.log.filter { it.startsWith("kill") })
        assertTrue(pane.log.last().startsWith("start claude"))
    }

    @Test
    fun backToItsFolderAndWaitsUntilItTakesWords() = runBlocking {
        val pane = Pane(listOf("codex", "-c", "model=x", "resume", "old-id"), agentCwd = "/srv/it's", shellCwd = "/home/me", notReady = 2)
        AgentRestart.run(pane, "w1:p1", "codex", AgentSessionRef("codex", "id", "0199-a"), "go", {}, stepMs = 1)
        assertTrue("type cd -- '/srv/it'\\''s'" in pane.log, pane.log.toString())
        assertTrue("start codex -c model=x resume 0199-a" in pane.log, pane.log.toString())
        assertEquals("prompt go", pane.log.last())
    }

    /** A pane whose herdr refuses to start an agent with [why], [times] times (or always). */
    private class Refusing(val why: String, val times: Int = Int.MAX_VALUE, val pane: Pane = Pane(listOf("claude"))) : AgentRestart.Port by pane {
        var refused = 0
        override suspend fun start(name: String, kind: String, paneId: String, args: List<String>, timeoutMs: Long) {
            if (refused++ < times) throw HerdrApiException("invalid", why)
            pane.start(name, kind, paneId, args, timeoutMs)
        }
        override suspend fun type(paneId: String, text: String) {
            pane.type(paneId, text)
            if (text.startsWith("claude ")) pane.running = true
        }
    }

    @Test
    fun waitsForASlowShellAsAlmaDoes() = runBlocking {
        val pane = Refusing("pane w1:p1 is not an available shell", times = 10)
        AgentRestart.run(pane, "w1:p1", "claude", ref, "go", {}, stepMs = 1, startRetryMs = 1)
        assertEquals(11, pane.refused)
        assertEquals("start claude --resume 6877705b-c46f", pane.pane.log.dropLast(1).last())
        assertEquals("prompt go", pane.pane.log.last())
    }

    @Test
    fun typesTheCommandWhereHerdrCantStartIt() = runBlocking {
        for (why in listOf("unknown variant `pi`", "not available from the phone", "pane w1:p1 is not an available shell")) {
            val pane = Refusing(why, pane = Pane(listOf("claude", "--add-dir", "/my dir")))
            AgentRestart.run(pane, "w1:p1", "claude", ref, "carry on", {}, stepMs = 1, startRetryMs = 1, typedSettleMs = 1)
            assertEquals(listOf("type claude --add-dir '/my dir' --resume 6877705b-c46f", "type carry on"), pane.pane.log.filter { it.startsWith("type") }, why)
        }
    }

    @Test
    fun otherRefusalsStillFail() = runBlocking<Unit> {
        assertFailsWith<IOException> { AgentRestart.run(Refusing("agent name taken"), "w1:p1", "claude", ref, null, {}, stepMs = 1) }
    }

    @Test
    fun givesUpWhenNothingStopsIt() = runBlocking<Unit> {
        val pane = object : AgentRestart.Port by Pane(listOf("claude"), quitsOn = 99) {
            override suspend fun kill(group: Long, signal: String) {}
        }
        assertFailsWith<IOException> { AgentRestart.run(pane, "w1:p1", "claude", ref, null, {}, stepMs = 1, quitMs = 10, killMs = 10) }
    }

    @Test
    fun keepsOptionsButNotSessionsOrUnaskedOnes() {
        assertEquals(
            AgentFlags.Kept(listOf("--model", "sonnet", "--verbose"), listOf("--dangerously-skip-permissions", "--permission-mode bypassPermissions")),
            AgentFlags.keep("claude", listOf("/usr/bin/claude", "--model", "sonnet", "-r", "abc", "--dangerously-skip-permissions", "--permission-mode", "bypassPermissions", "--verbose", "--print", "hello")),
        )
        assertEquals(
            AgentFlags.Kept(listOf("-m", "gpt-5.5", "--profile", "work"), listOf("--yolo", "--sandbox=danger-full-access")),
            AgentFlags.keep("codex", listOf("codex", "-m", "gpt-5.5", "--yolo", "--sandbox=danger-full-access", "--profile", "work", "resume", "0199-a", "--last")),
        )
        assertEquals(AgentFlags.Kept(emptyList(), emptyList()), AgentFlags.keep("claude", listOf("node", "/x/cli.js")))
    }

    @Test
    fun onlyAgentsWithASessionToReopen() {
        assertTrue(AgentRestart.canRestart("codex", AgentSessionRef("codex", "id", "0199-a")))
        assertTrue(AgentRestart.canRestart("pi", AgentSessionRef("pi", "path", "/home/me/.pi/agent/sessions/x.jsonl")))
        assertFalse(AgentRestart.canRestart("claude", AgentSessionRef("claude", "id", "x; rm -rf ~")))
        assertFalse(AgentRestart.canRestart("omp", AgentSessionRef("omp", "id", "a")))
        assertEquals(listOf("--model", "x", "--session", "/s.jsonl"), AgentRestart.resumeArgs("pi", "/s.jsonl", listOf("--model", "x")))
    }

    @Test
    fun claudeSettings() {
        assertEquals("/model sonnet", AgentSettings.modelCommand("sonnet"))
        assertEquals("/effort xhigh", AgentSettings.effortCommand("xhigh"))
        assertFailsWith<IllegalArgumentException> { AgentSettings.modelCommand("opus; /exit") }
        assertEquals("opus", AgentSettings.family("claude-opus-5-5"))
    }
}

class CodexModelsTest {
    @Test
    fun readsTheCatalogAndBuildsItsCommand() {
        val models = com.makefunstuff.herdr.core.chat.CodexModels.parse(
            """{"models":[
              {"slug":"remote-magpie/pipio/gpt-6.1-sol","display_name":"GPT-6.1 Sol · Pipio","visibility":"list",
               "supported_reasoning_levels":[{"effort":"low"},{"effort":"xhigh"}],"default_reasoning_level":"high","base_instructions":"…"},
              {"slug":"hidden","visibility":"hide"},
              {"slug":"bad slug; rm"},
              {"slug":"gpt-5.5"}]}""",
        )
        assertEquals(listOf("remote-magpie/pipio/gpt-6.1-sol", "gpt-5.5"), models.map { it.slug })
        assertEquals(listOf("low", "xhigh"), models[0].efforts)
        assertEquals("GPT-6.1 Sol · Pipio", models[0].name)
        assertEquals("gpt-5.5", models[1].name)
        assertEquals(emptyList(), com.makefunstuff.herdr.core.chat.CodexModels.parse("codex not found"))

        val c = com.makefunstuff.herdr.core.chat.CodexModels
        assertEquals("/model gpt-5.5 --reasoning=high", c.command("gpt-5.5", "high"))
        assertEquals("/model --reasoning=low", c.command(null, "low"))
        assertEquals("/model gpt-5.5", c.command("gpt-5.5", null))
        assertFailsWith<IllegalArgumentException> { c.command("gpt-5.5 --yolo", null) }
        assertTrue(AgentSettings.canSwitch("codex"))
    }
}
