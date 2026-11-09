package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.ApiCaller
import com.makefunstuff.herdr.core.hc.HcClient
import com.makefunstuff.herdr.core.hc.HcException
import com.makefunstuff.herdr.core.hc.HcList
import com.makefunstuff.herdr.core.hc.HcMapping
import com.makefunstuff.herdr.core.hc.HcStart
import com.makefunstuff.herdr.core.hc.HcTerminal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Starting and closing herdr-core terminals from the phone (issue #99): `hc run` into a space, `hc kill`. */
class HcStartTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun res(name: String) = javaClass.getResource("/hc/$name")!!.readText()
    private val list: HcList get() = json.decodeFromJsonElement(json.parseToJsonElement(res("list.json")).jsonObject["result"]!!.jsonObject)
    private val snapshot get() = HcMapping.snapshot(list.terminals)

    private class Core(val answers: (String, JsonObject) -> JsonObject) : ApiCaller {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
            calls += method to params
            return answers(method, params)
        }
    }

    private fun terminal(address: String, status: String? = null): JsonObject {
        val t = HcTerminal(address = address)
        val obj = json.encodeToJsonElement(t).jsonObject
        return if (status == null) obj else JsonObject(obj + ("status" to JsonObject(mapOf("state" to JsonPrimitive(status)))))
    }

    @Test
    fun aSpacesTerminalIsLabelledIntoANewTabOfIt() {
        val p = HcStart.place("hc:sp1", "herdr-android", "Terminal", tabId = "tbnew")!!
        assertNull(p.session)
        assertNull(p.machine)
        assertEquals(mapOf("herdr.space" to "sp1", "herdr.tab" to "tbnew", "where" to "herdr-android / Terminal"), p.labels)
        // The app maps it back into the same space, as a tab of its own.
        val t = HcTerminal(address = "t9", labels = p.labels, cwd = "/home/dev/src/demo")
        val snap = HcMapping.snapshot(list.terminals + t)
        val pane = snap.panes.single { it.paneId == "hc:t9" }
        assertEquals("hc:sp1", pane.workspaceId)
        assertEquals("Terminal", snap.tabs.single { it.tabId == pane.tabId }.label)
    }

    @Test
    fun placesInOtherSessionsMachinesAndOtherTerminals() {
        assertNull(HcStart.place("w1", "classic", "Terminal"))
        val other = HcStart.place("hc:@shepherdr:sp7", "app", "claude", tabId = "tb1")!!
        assertEquals("shepherdr", other.session)
        assertEquals("sp7", other.labels["herdr.space"])
        val remote = HcStart.place("hc:rohan/sp2", "scratch", "Terminal", tabId = "tb1")!!
        assertEquals("rohan", remote.machine)
        assertEquals("sp2", remote.labels["herdr.space"])
        val named = HcStart.place("hc:where:a/b", "a/b", "Terminal")!!
        assertNull(named.machine)
        // Its own tab too: a second terminal there doesn't land in the first one's.
        assertEquals("a/b / Terminal", named.labels["where"])
        assertTrue(named.labels["herdr.tab"]!!.startsWith("tb"))
        assertTrue(HcStart.place("hc:where:a/b", "a/b", "Terminal")!!.labels["herdr.tab"] != named.labels["herdr.tab"])
        assertEquals(HcStart.Place(null, null, emptyMap()), HcStart.place(HcMapping.OTHER, "Other terminals", "Terminal"))
        assertEquals(HcStart.Place(null, "rohan", emptyMap()), HcStart.place("${HcMapping.OTHER}@rohan", "Other terminals on rohan", "Terminal"))
    }

    @Test
    fun anAgentStartsInTheSpaceThatSitsInItsFolder() {
        assertEquals("hc:sp1", HcStart.spaceIn(snapshot, "/home/dev/src/demo/"))
        assertNull(HcStart.spaceIn(snapshot, "/elsewhere"))
        // Only the desktop app's own (default) session: not Shepherdr's session, not Other terminals.
        val shepherdr = HcMapping.snapshot(listOf(HcTerminal(address = "t5", cwd = "/srv/app", labels = mapOf("herdr.space" to "sp9", "where" to "app / 1"))), session = "shepherdr")
        val other = HcMapping.snapshot(listOf(HcTerminal(address = "t6", cwd = "/srv/loose")))
        assertNull(HcStart.spaceIn(shepherdr, "/srv/app"))
        assertNull(HcStart.spaceIn(other, "/srv/loose"))
    }

    @Test
    fun namesAndLines() {
        assertEquals("add-last-answered", HcStart.hcName("Add last answered"))
        assertEquals(32, HcStart.hcName("a".repeat(40))!!.length)
        assertNull(HcStart.hcName("!!"))
        assertEquals("exec \"\${SHELL:-/bin/sh}\" -lc 'exec '\\''claude'\\'''", HcStart.programLine("claude"))
        assertTrue(HcStart.newTabId().matches(Regex("tb[0-9a-f]{32}")))
    }

    @Test
    fun aShellRunsWithItsLabelsAndNoParent() = runBlocking {
        val core = Core { method, _ -> if (method == "terminal.run") terminal("t9") else JsonObject(emptyMap()) }
        val place = HcStart.place("hc:@shepherdr:sp1", "herdr-android", "Terminal", tabId = "tbnew")!!
        val pane = HcStart.shell(HcClient(core), place, "/home/dev/src/demo")
        assertEquals("hc:@shepherdr:t9", pane)
        val (method, params) = core.calls.single()
        assertEquals("terminal.run", method)
        assertEquals("/home/dev/src/demo", params["cwd"]!!.jsonPrimitive.content)
        assertEquals("true", params["no_parent"]!!.jsonPrimitive.content)
        assertNull(params["line"], "no line: the machine's own shell")
        assertEquals("xterm-256color", params["env"]!!.jsonObject["TERM"]!!.jsonPrimitive.content)
        assertEquals("sp1", params["labels"]!!.jsonObject["herdr.space"]!!.jsonPrimitive.content)
    }

    private val nowhere = HcStart.Place(null, null, emptyMap())

    @Test
    fun anAgentRunsThroughTheLoginShellUnderItsName() = runBlocking {
        val core = Core { method, _ -> if (method == "terminal.run") terminal("t9") else JsonObject(emptyMap()) }
        assertEquals("hc:t9", HcStart.launch(HcClient(core), nowhere, "/src", "claude", "Fix the login"))
        val run = core.calls.single().second
        assertEquals("fix-the-login", run["name"]!!.jsonPrimitive.content)
        assertEquals("/src", run["cwd"]!!.jsonPrimitive.content)
        assertEquals(HcStart.programLine("claude"), run["line"]!!.jsonPrimitive.content)
    }

    @Test
    fun onWindowsTheProgramRunsAsArgv() = runBlocking {
        // hc runs a line with cmd.exe there: no POSIX login shell, the program itself.
        val core = Core { _, _ -> terminal("t9") }
        HcStart.launch(HcClient(core), nowhere, "/c/src", "claude", "fix", windows = true)
        val run = core.calls.single().second
        assertNull(run["line"])
        assertEquals("claude", run["argv"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(HcStart.windows("MINGW64_NT-10.0-26100\n"))
        assertTrue(!HcStart.windows("Linux"))
    }

    @Test
    fun aTakenNameStartsItWithoutOne() = runBlocking {
        val core = Core { method, params ->
            when {
                method == "terminal.run" && params["name"] != null -> throw HcException("name_taken", "name taken")
                else -> terminal("t9")
            }
        }
        assertEquals("hc:t9", HcStart.launch(HcClient(core), nowhere, "/src", "codex", "fix"))
        assertEquals(listOf(true, false), core.calls.map { it.second["name"] != null })
    }

    @Test
    fun theWordsGoInOnceItSaysItWaits() = runBlocking {
        var gets = 0
        val core = Core { method, _ ->
            when (method) {
                "terminal.get" -> terminal("t9", status = when (++gets) { 1 -> null; 2 -> "working"; else -> "idle" })
                else -> JsonObject(mapOf("sent" to JsonPrimitive(true)))
            }
        }
        assertNull(HcStart.deliver(HcClient(core), "hc:t9", " fix the login "))
        assertEquals(listOf("terminal.get", "terminal.get", "terminal.get", "terminal.send"), core.calls.map { it.first })
        assertEquals("fix the login", core.calls.last().second["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun aQuestionGetsNoEnter() = runBlocking {
        val core = Core { _, _ -> terminal("t9", status = "blocked") }
        assertEquals("it asks something first", HcStart.deliver(HcClient(core), "hc:t9", "fix it"))
        assertTrue(core.calls.none { it.first == "terminal.send" }, "an Enter could answer its question")
    }

    @Test
    fun anAgentThatNeverSaysItIsReadyIsTypedNothing() = runBlocking {
        val core = Core { _, _ -> terminal("t9") }
        assertEquals("it didn't say it was ready", HcStart.deliver(HcClient(core), "hc:t9", "fix it", readyMs = 1_200))
        assertTrue(core.calls.none { it.first == "terminal.send" })
    }

    @Test
    fun anErrorIsSaidAsOne() = runBlocking {
        val core = Core { _, _ -> terminal("t9", status = "error") }
        assertEquals("it reports an error", HcStart.deliver(HcClient(core), "hc:t9", "fix it"))
        assertTrue(core.calls.none { it.first == "terminal.send" })
    }

    @Test
    fun wordsThePersonTookOverAreNotSent() = runBlocking {
        // Ready, but the person sent (or discarded) the held words meanwhile: nothing goes twice.
        val ready = Core { _, _ -> terminal("t9", status = "idle") }
        assertNull(HcStart.deliver(HcClient(ready), "hc:t9", "fix it", wanted = { false }))
        assertTrue(ready.calls.none { it.first == "terminal.send" })
        // Taken over while it still starts: the wait ends there, with nothing to say.
        var asks = 0
        val starting = Core { _, _ -> terminal("t9") }
        assertNull(HcStart.deliver(HcClient(starting), "hc:t9", "fix it", readyMs = 10_000, wanted = { ++asks < 3 }))
        assertTrue(starting.calls.none { it.first == "terminal.send" })
    }

    @Test
    fun wordsClaimedElsewhereAsTheyGoAreNotSentTwice() = runBlocking {
        // Still wanted while it waits, but Send now took them in the moment it said it was ready.
        val core = Core { _, _ -> terminal("t9", status = "idle") }
        assertNull(HcStart.deliver(HcClient(core), "hc:t9", "fix it", wanted = { true }, claim = { false }))
        assertTrue(core.calls.none { it.first == "terminal.send" })
    }

    @Test
    fun aRefusedSendIsSaid() = runBlocking {
        val core = Core { method, _ ->
            when (method) {
                "terminal.send" -> throw HcException("blocked", "it waits for an answer")
                else -> terminal("t9", status = "idle")
            }
        }
        assertEquals("It waits for an answer.", HcStart.deliver(HcClient(core), "hc:t9", "fix it"))
    }

    @Test
    fun closeKillsTheTerminal() = runBlocking {
        val core = Core { _, _ -> terminal("t9") }
        HcClient(core).kill("t9")
        assertEquals("terminal.kill" to JsonObject(mapOf("terminal" to JsonPrimitive("t9"))), core.calls.single())
    }
}
