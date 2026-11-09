package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.ApiCaller
import com.makefunstuff.herdr.core.api.PaneInfo
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.api.TabInfo
import com.makefunstuff.herdr.core.api.WorkspaceInfo
import com.makefunstuff.herdr.core.chat.AgentInput
import com.makefunstuff.herdr.core.chat.BlockedPrompt
import com.makefunstuff.herdr.core.chat.HcAgentPort
import com.makefunstuff.herdr.core.hc.HcClient
import com.makefunstuff.herdr.core.hc.HcException
import com.makefunstuff.herdr.core.hc.HcList
import com.makefunstuff.herdr.core.hc.HcMapping
import com.makefunstuff.herdr.core.host.HerdrCoreState
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.core.host.HostStatus
import com.makefunstuff.herdr.core.host.Sessions
import com.makefunstuff.herdr.core.ssh.Machine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the screens do with herdr-core: its agents next to classic herdr's, and their keys and words sent to hc. */
class HcScreensTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun res(name: String) = javaClass.getResource("/hc/$name")!!.readText()
    private fun result(name: String) = json.parseToJsonElement(res(name)).jsonObject["result"]!!.jsonObject
    private val list: HcList get() = json.decodeFromJsonElement(result("list.json"))

    /** A core that answers from the fixtures and notes every call. */
    private class Core(val answers: (String, JsonObject) -> JsonObject) : ApiCaller {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
            calls += method to params
            return answers(method, params)
        }
    }

    private val menu = """
        |● Bash(npm run db:migrate)
        |
        | Bash command
        |
        |   npm run db:migrate
        |   Run the database migrations
        |
        | Do you want to proceed?
        |❯ 1. Yes
        |  2. Yes, and don't ask again for npm run commands
        |  3. No, and tell Claude what to do differently
        |Esc to cancel · Tab to amend
    """.trimMargin()

    private fun read(text: String) = JsonObject(mapOf("text" to JsonPrimitive(text), "complete" to JsonPrimitive(true)))

    @Test
    fun keysAndWordsForAnHcAgentGoToItsTerminal() = runBlocking {
        val core = Core { method, _ ->
            when (method) {
                "terminal.send" -> result("send.json")
                "terminal.keys" -> result("keys.json")
                else -> JsonObject(emptyMap())
            }
        }
        val port = HcAgentPort(HcClient(core))
        AgentInput.keys(port, "hc:t2", listOf("y"), named = true)
        AgentInput.send(port, "hc:t1", "carry on", named = true, blocked = false)
        AgentInput.send(port, "hc:t2", "y", named = true, blocked = true)
        assertEquals(listOf("terminal.keys", "terminal.send", "terminal.send"), core.calls.map { it.first })
        assertEquals("""{"terminal":"t2","keys":["y"]}""", core.calls[0].second.toString())
        // A prompt waits its turn; an answer to a question goes in even though it is blocked.
        assertEquals("""{"terminal":"t1","text":"carry on"}""", core.calls[1].second.toString())
        assertEquals("""{"terminal":"t2","text":"y","force":true}""", core.calls[2].second.toString())
        // Classic herdr's panes aren't hc's.
        assertFailsWith<IOException> { port.keys("w1:p1", listOf("y"), named = true) }
        Unit
    }

    @Test
    fun aPromptRefusedForBeingBlockedIsTypedAsTheAnswer() = runBlocking {
        val core = Core { method, params ->
            if (method == "terminal.send" && params["force"] == null) throw HcException("blocked", "t2 is blocked")
            result("send.json")
        }
        AgentInput.send(HcAgentPort(HcClient(core)), "hc:t2", "yes, go", named = true, blocked = false)
        assertEquals(listOf(null, JsonPrimitive(true)), core.calls.map { it.second["force"] })
    }

    @Test
    fun anHcQuestionIsReadAgainBeforeItsAnswerIsPressed() = runBlocking {
        val shown = BlockedPrompt.parse(menu)!!
        var screen = menu
        val core = Core { method, params ->
            when (method) {
                "terminal.get" -> result("terminal-blocked.json")
                "terminal.read" -> {
                    // Only the screen: a question is read from what shows.
                    assertEquals(0, (params["lines"] as JsonPrimitive).content.toInt())
                    read(screen)
                }
                "terminal.keys" -> result("keys.json")
                else -> JsonObject(emptyMap())
            }
        }
        val port = HcAgentPort(HcClient(core))
        assertEquals(AgentStatus.Blocked, port.status("hc:t2"))
        AgentInput.choose(port, "hc:t2", shown.question, 2, shown.options[2].label, shown.details)
        val keys = core.calls.last { it.first == "terminal.keys" }.second["keys"].toString()
        assertEquals("""["down","down","enter"]""", keys)

        // Another question by now: nothing is pressed.
        screen = menu.replace("Do you want to proceed?", "Do you want to delete the table?")
        val pressed = core.calls.count { it.first == "terminal.keys" }
        assertFailsWith<IOException> { AgentInput.choose(port, "hc:t2", shown.question, 0, "Yes", shown.details) }
        assertEquals(pressed, core.calls.count { it.first == "terminal.keys" })
    }

    @Test
    fun aGoneTerminalHasNoStatusAndAShellIsNoAgent() = runBlocking {
        val gone = Core { _, _ -> throw HcException("not_found", "no terminal \"t9\"") }
        assertNull(HcAgentPort(HcClient(gone)).status("hc:t9"))
        val shell = list.terminals.first { it.address == "t3" }
        val plain = Core { _, _ -> json.parseToJsonElement(json.encodeToString(com.makefunstuff.herdr.core.hc.HcTerminal.serializer(), shell)).jsonObject }
        assertNull(HcAgentPort(HcClient(plain)).status("hc:t3"))
    }

    private val classic = SessionSnapshot(
        version = "0.9.5",
        workspaces = listOf(WorkspaceInfo(workspaceId = "w1", number = 1, label = "api-server"), WorkspaceInfo(workspaceId = "w2", number = 2, label = "web")),
        tabs = listOf(TabInfo(tabId = "w1:t1", workspaceId = "w1", number = 1, label = "main")),
        panes = listOf(PaneInfo(paneId = "w1:p1", workspaceId = "w1", tabId = "w1:t1")),
        agents = listOf(AgentInfo(paneId = "w1:p1", workspaceId = "w1", tabId = "w1:t1", agent = "claude", agentStatusRaw = "working")),
    )

    @Test
    fun aMachineRunningBothShowsBothClassicFirst() {
        val core = HcMapping.snapshot(list.terminals, "0.1.14")
        val both = Sessions.merge(classic, core)!!
        assertEquals(listOf("w1", "w2") + core.workspaces.map { it.workspaceId }, both.workspaces.map { it.workspaceId })
        // herdr-core's spaces are numbered after classic herdr's.
        assertEquals(listOf(1, 2) + core.workspaces.indices.map { it + 3 }, both.workspaces.map { it.number })
        assertEquals(classic.agents + core.agents, both.agents)
        assertEquals("0.9.5", both.version)
        assertEquals("herdr-android › review", both.location(core.agents.first().workspaceId, core.agents.first().tabId))
        // Either alone is just itself; a core with no terminals changes nothing.
        assertEquals(classic, Sessions.merge(classic, null))
        assertEquals(core, Sessions.merge(null, core))
        assertEquals(classic, Sessions.merge(classic, SessionSnapshot()))
        assertNull(Sessions.merge(null, null))
    }

    @Test
    fun theViewKeepsClassicHerdrsSnapshotApart() {
        val machine = Machine(id = "m1", label = "devbox", host = "devbox", user = "dev")
        val core = HerdrCoreState("0.1.14", "devbox", "/home/dev/.local/bin/herdr-core", HcMapping.snapshot(list.terminals, "0.1.14"))
        val both = HostState(machine, status = HostStatus.Online, snapshot = classic, herdrCore = core)
        assertEquals(classic, both.snapshot)
        assertEquals(classic.agents.size + core.snapshot.agents.size, both.view!!.agents.size)
        assertTrue(both.classic)
        // Online for herdr-core alone: its agents show, classic herdr's calls don't apply.
        val alone = HostState(machine, status = HostStatus.Online, herdrCore = core, classicMissing = "herdr isn't installed on devbox")
        assertEquals(core.snapshot, alone.view)
        assertFalse(alone.classic)
        assertNull(alone.snapshot)
        assertTrue(alone.copy(herdrCoreLooked = true).agentsKnown)
        // Classic herdr stopped on a machine running both: its last snapshot isn't shown, and its agents' marks wait for it.
        val stopped = alone.copy(snapshot = classic, herdrCoreLooked = true)
        assertEquals(core.snapshot, stopped.view)
        assertFalse(stopped.agentsKnown)
        assertTrue(both.copy(herdrCoreLooked = true).agentsKnown)
    }

    @Test
    fun aMachineIsSettledOnceItsLookIsOver() {
        val machine = Machine(id = "m1", label = "devbox", host = "devbox", user = "dev")
        val core = HerdrCoreState("0.1.14", "devbox", "/home/dev/.local/bin/herdr-core", HcMapping.snapshot(list.terminals, "0.1.14"))
        val missing = "herdr isn't installed on devbox"
        // Classic herdr answers, or herdr-core alone does.
        assertTrue(HostState(machine, status = HostStatus.Online, snapshot = classic).settled)
        assertTrue(HostState(machine, status = HostStatus.Online, herdrCore = core, classicMissing = missing, herdrCoreLooked = true).settled)
        // herdr-core alone answered for its first session, but not every session was looked at yet.
        assertFalse(HostState(machine, status = HostStatus.Online, herdrCore = core, classicMissing = missing).settled)
        // herdr-core was found but has not answered yet: classic herdr's absence is not the machine's end.
        assertFalse(HostState(machine, status = HostStatus.Attention(missing, HostStatus.Attention.Kind.Herdr), classicMissing = missing).settled)
        // Neither is there, or the key was refused: that won't change by waiting.
        assertTrue(HostState(machine, status = HostStatus.Attention(missing, HostStatus.Attention.Kind.Herdr)).settled)
        assertTrue(HostState(machine, status = HostStatus.Attention("Authentication failed", HostStatus.Attention.Kind.Auth)).settled)
        assertFalse(HostState(machine, status = HostStatus.Connecting).settled)
        assertFalse(HostState(machine, status = HostStatus.Reconnecting("Connection reset", 5)).settled)
    }
}
