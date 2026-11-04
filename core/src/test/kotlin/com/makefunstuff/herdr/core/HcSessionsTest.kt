package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.hc.HcDetection
import com.makefunstuff.herdr.core.hc.HcJson
import com.makefunstuff.herdr.core.hc.HcMapping
import com.makefunstuff.herdr.core.hc.HcTerminal
import com.makefunstuff.herdr.core.host.HerdrCoreState
import com.makefunstuff.herdr.core.shepherdr.ShepherdrJson
import com.makefunstuff.herdr.core.shepherdr.HttpReply
import com.makefunstuff.herdr.core.shepherdr.ShepherdrClient
import com.makefunstuff.herdr.core.shepherdr.ShepherdrTransport
import com.makefunstuff.herdr.core.shepherdr.V2Board
import com.makefunstuff.herdr.core.shepherdr.V2WorkBoard
import com.makefunstuff.herdr.core.shepherdr.WebSocketLink
import com.makefunstuff.herdr.core.shepherdr.V2Chat
import com.makefunstuff.herdr.core.shepherdr.V2Snapshot
import com.makefunstuff.herdr.core.shepherdr.V2Version
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** herdr-core sessions besides the default one, and Shepherdr 3's agents as its labels name them. */
class HcSessionsTest {
    /**
     * A space's Assistant and its task's worker as Shepherdr 3.1 labels them (`hc get` on a 3.1.2 machine, ids and
     * names scrubbed): the Assistant by its space, the worker by its task list's key and number.
     */
    private val shepherdrTerminals: List<HcTerminal> = HcJson.decodeFromString(
        ListSerializer(HcTerminal.serializer()),
        """
        [
          {"address":"t17","argv":["claude"],"created_ms":1000,"cwd":"/home/dev/demo","state":"live",
           "labels":{"herdr.chat.slot":"coord-dev/sp0a01-x1","herdr.coordinator.space":"sp0a01","herdr.coordinator.tab":"tb0a01","herdr.role":"coordinator",
             "herdr.space":"sp0a01","herdr.tab":"tb0a01","where":"demo / Assistant"},
           "status":{"app":"claude-code","state":"done"},"status_ms":1500,"turn":3},
          {"address":"t2d","argv":["claude"],"created_ms":2000,"cwd":"/home/dev/worktrees/demo/task-2","name":"demo-2-dark-mode","parent":"t17","state":"live",
           "labels":{"herdr.agent":"claude","herdr.coordinator":"tb0a01","herdr.task":"home-dev-demo#2","herdr.task.space":"sp0a01","where":"demo / Assistant"},
           "status":{"app":"claude-code","state":"blocked","kind":"question"},"status_ms":2500,"turn":0}
        ]
        """,
    )

    @Test
    fun anotherSessionsIdsStayApartFromTheDefaultOnes() {
        assertEquals("hc:t7", HcMapping.paneId("t7"))
        assertEquals("hc:@shepherdr:t7", HcMapping.paneId("t7", "shepherdr"))
        assertEquals("t7", HcMapping.address("hc:t7"))
        assertEquals("t7", HcMapping.address("hc:@shepherdr:t7"))
        assertEquals("rohan/t7", HcMapping.address("hc:@shepherdr:rohan/t7"))
        assertNull(HcMapping.session("hc:t7"))
        assertEquals("shepherdr", HcMapping.session("hc:@shepherdr:t7"))
        assertNull(HcMapping.address("p_3"))
        assertNull(HcMapping.session("p_3"))

        val shown = HcMapping.snapshot(shepherdrTerminals, "0.1.14", session = "shepherdr")
        val ids = shown.workspaces.map { it.workspaceId } + shown.tabs.map { it.tabId } + shown.panes.map { it.paneId }
        assertTrue(ids.all { it.startsWith("hc:@shepherdr:") }, ids.toString())
        // The same terminals in the default session: other ids, so both can be shown side by side.
        val plain = HcMapping.snapshot(shepherdrTerminals, "0.1.14")
        assertTrue(plain.panes.map { it.paneId }.none { it in shown.panes.map { p -> p.paneId } })
        assertEquals(listOf("demo"), shown.workspaces.map { it.label })
    }

    @Test
    fun shepherdrsAgentsAreCalledByTheirTaskOrSpace() {
        val shown = HcMapping.snapshot(shepherdrTerminals, "0.1.14")
        assertEquals(listOf("demo Assistant", "DEMO-2"), shown.agents.map { it.name })
        assertEquals(listOf("demo Assistant", "DEMO-2"), shown.panes.map { it.label })
        assertEquals("DEMO-2", HcMapping.shepherdrTask(shepherdrTerminals[1]))
        assertEquals("sp0a01", HcMapping.shepherdrSpace(shepherdrTerminals[0]))
        // On a machine hc added: the task and space by that machine.
        val there = shepherdrTerminals.map { it.copy(address = "vm/" + it.address) }
        assertEquals("vm:DEMO-2", HcMapping.shepherdrTask(there[1]))
        assertEquals("vm/sp0a01", HcMapping.shepherdrSpace(there[0]))
        // A name whose number isn't its label's says nothing of its ref.
        assertNull(HcMapping.shepherdrTask(shepherdrTerminals[1].copy(name = "demo-3-dark-mode")))
        assertNull(HcMapping.shepherdrTask(shepherdrTerminals[1].copy(name = null)))
        // A terminal Shepherdr didn't start keeps its own name.
        val own = shepherdrTerminals[0].copy(labels = mapOf("where" to "demo / main"), name = "build")
        assertNull(HcMapping.shepherdrName(own))
        assertEquals("build", HcMapping.snapshot(listOf(own)).agents.single().name)
        // Its reviewer carries the task's label too: not called (nor opened as) the task.
        val reviewer = shepherdrTerminals[1].copy(labels = shepherdrTerminals[1].labels + ("herdr.role" to "reviewer"))
        assertEquals("DEMO-2 reviewer", HcMapping.shepherdrName(reviewer))
        assertNull(HcMapping.snapshot(listOf(reviewer)).agents.single().shepherdrTask)
        assertEquals("DEMO-2", shown.agents.single { it.name == "DEMO-2" }.shepherdrTask)
        assertEquals("sp0a01", shown.agents.single { it.name == "demo Assistant" }.shepherdrSpace)
        // The task's agent asks: it needs you, as any blocked agent does.
        assertEquals("question", shown.agents.single { it.name == "DEMO-2" }.stateLabels["blocked"])
    }

    @Test
    fun everyFollowedSessionIsOneState() {
        val default = HerdrCoreState("0.1.14", "dev-box", "/usr/bin/herdr-core", HcMapping.snapshot(shepherdrTerminals.take(1).map { it.copy(labels = mapOf("where" to "home / 1")) }, "0.1.14"), mapOf("hc:t17" to (80 to 24)))
        val shepherdr = HerdrCoreState("0.1.14", "dev-box", "/usr/bin/herdr-core", HcMapping.snapshot(shepherdrTerminals, "0.1.14", session = "shepherdr"), mapOf("hc:@shepherdr:t17" to (200 to 50)))
        // The default session alone is as it was.
        assertEquals(default, HerdrCoreState.merge(mapOf(null to default)))
        assertNull(HerdrCoreState.merge(emptyMap()))

        val both = HerdrCoreState.merge(mapOf("shepherdr" to shepherdr, null to default))!!
        assertEquals(listOf("default", "shepherdr"), both.sessions)
        // The default session's spaces first, numbered on after it; each agent once.
        assertEquals(listOf("home", "demo"), both.snapshot.workspaces.map { it.label })
        assertEquals(listOf(1, 2), both.snapshot.workspaces.map { it.number })
        assertEquals(listOf("hc:t17", "hc:@shepherdr:t17", "hc:@shepherdr:t2d"), both.snapshot.agents.map { it.paneId })
        assertEquals(both.snapshot.agents.map { it.paneId }.distinct(), both.snapshot.agents.map { it.paneId })
        assertEquals(80 to 24, both.sizes["hc:t17"])
        assertEquals(200 to 50, both.sizes["hc:@shepherdr:t17"])

        // The default session and an empty one: nothing to tell apart, none named.
        val preview0 = HerdrCoreState("0.1.14", "dev-box", "/usr/bin/herdr-core", HcMapping.snapshot(emptyList(), session = "preview"))
        assertEquals(emptyList(), HerdrCoreState.merge(mapOf(null to default, "preview" to preview0))!!.sessions)
        // An empty session (a preview's) is followed but not named.
        val preview = HerdrCoreState("0.1.14", "dev-box", "/usr/bin/herdr-core", HcMapping.snapshot(emptyList(), session = "preview"))
        assertEquals(listOf("default", "shepherdr"), HerdrCoreState.merge(mapOf(null to default, "preview" to preview, "shepherdr" to shepherdr))!!.sessions)

        // Shepherdr's session alone (no default core): still shown.
        val alone = HerdrCoreState.merge(mapOf("shepherdr" to shepherdr))!!
        assertEquals(2, alone.snapshot.agents.size)
        assertEquals(listOf("shepherdr"), alone.sessions)
    }

    @Test
    fun theProbeNamesOtherSessionsWhoseCoreRuns() {
        val out = """
            socket	/run/user/1000/herdr-core/default.sock
            lock	4127733
            core	/home/dev/.local/bin/herdr-core	4127733
            session	shepherdr	/run/user/1000/herdr-core/shepherdr.sock	1110204	/home/dev/.local/bin/herdr-core
            session	bad name	/run/user/1000/herdr-core/x.sock	1	/home/dev/.local/bin/herdr-core
            session	nopath	relative.sock	1	/home/dev/.local/bin/herdr-core
            bin	/home/dev/.local/bin/herdr-core	hc 0.1.14	core
        """.trimIndent()
        val p = HcDetection.parse(out)
        assertTrue(p.running)
        assertEquals(listOf("shepherdr"), p.others.map { it.name })
        assertEquals("/run/user/1000/herdr-core/shepherdr.sock", p.others.single().socket)

        // Only another session's core runs: the default one doesn't, its executable is still found to connect with.
        val alone = HcDetection.parse(
            "session\tshepherdr\t/run/user/1000/herdr-core/shepherdr.sock\t1110204\t/home/dev/.local/bin/herdr-core\n" +
                "bin\t/home/dev/.local/bin/herdr-core\thc 0.1.14\tcore\n",
        )
        assertFalse(alone.running)
        assertEquals("/home/dev/.local/bin/herdr-core", alone.binary?.path)
        assertEquals(1, alone.others.size)

        // Connecting to another session names it too: a core started there would be that session's, never the default.
        assertEquals(
            """sh -c '[ -S "${'$'}1" ] || { echo '\''herdr-core stopped'\'' >&2; exit 3; }; HC_SESSION="${'$'}3" HC_SOCKET="${'$'}1" exec "${'$'}2" connect' hc '/run/user/1000/herdr-core/shepherdr.sock' '/home/dev/.local/bin/herdr-core' 'shepherdr'""",
            HcDetection.connectCommand(alone.binary!!, "/run/user/1000/herdr-core/shepherdr.sock", "shepherdr"),
        )
    }

    @Test
    fun theProbeScriptFindsAnotherSessionOnlyWhileItsCoreHoldsItsLock() {
        assumeTrue(File("/proc/locks").canRead() && File("/usr/bin/flock").canExecute(), "needs Linux /proc/locks and flock")
        val home = Files.createTempDirectory("hc-sessions").toFile()
        val holders = mutableListOf<Process>()
        val servers = mutableListOf<java.nio.channels.ServerSocketChannel>()
        try {
            File(home, ".local/bin").mkdirs()
            File(home, ".local/bin/herdr-core").apply { writeText("#!/bin/sh\necho 'hc 0.1.14'\n"); setExecutable(true) }
            fun session(name: String, held: Boolean) {
                val socket = File(home, "run/herdr-core/$name.sock").apply { parentFile.mkdirs() }
                servers += java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)
                    .bind(java.net.UnixDomainSocketAddress.of(socket.toPath()))
                val lock = File(home, "state/herdr-core/$name/core.lock").apply { parentFile.mkdirs(); writeText(socket.path + "\n") }
                // -o: the lock goes with flock itself, not with its sleep.
                if (held) holders += ProcessBuilder("/usr/bin/flock", "-o", lock.path, "sleep", "60").start()
            }
            // No default core; Shepherdr's runs, a preview's stopped (its socket left behind).
            session("shepherdr", held = true)
            session("preview", held = false)
            // A holder's socket in the runtime folder isn't a session.
            File(home, "run/herdr-core/shepherdr.holder.1234.sock").createNewFile()
            Thread.sleep(300)
            val out = probe(home)
            val p = HcDetection.parse(out)
            assertFalse(p.running, out)
            assertEquals(listOf("shepherdr"), p.others.map { it.name }, out)
            assertEquals(File(home, "run/herdr-core/shepherdr.sock").path, p.others.single().socket, out)
            // The executables are looked for once some core runs.
            assertTrue(p.binaries.any { it.path.endsWith("/.local/bin/herdr-core") }, out)

            // Nothing runs: nothing more is looked for.
            holders.forEach { it.destroy(); it.waitFor() }
            assertTrue(HcDetection.parse(probe(home)).others.isEmpty())
            assertTrue(HcDetection.parse(probe(home)).binaries.isEmpty())
        } finally {
            holders.forEach { it.destroy() }
            servers.forEach { it.close() }
            home.deleteRecursively()
        }
    }

    /** [HcDetection.probe] in `sh`, with [home] as HOME and its own runtime and state folders. */
    private fun probe(home: File): String {
        val p = ProcessBuilder("sh", "-c", HcDetection.probe)
            .apply {
                environment().apply {
                    keys.removeAll { it.startsWith("HC_") }
                    put("HOME", home.path)
                    put("SHELL", "/bin/sh")
                    put("PATH", "/usr/bin:/bin")
                    put("XDG_RUNTIME_DIR", File(home, "run").path)
                    put("XDG_STATE_HOME", File(home, "state").path)
                }
            }
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, p.waitFor(), out)
        return out
    }

    /** Shepherdr's API as a list of what was sent: answers each request with [answer], a chat with [chat]. */
    private class Recorder(val chat: String, val answer: (String) -> String = { """{"ok":true}""" }) : ShepherdrTransport {
        val sent = ArrayList<Pair<String, String?>>()
        override val canStream: Boolean get() = false
        override suspend fun http(method: String, path: String, body: String?, timeoutMs: Int): HttpReply {
            sent += path to body
            return HttpReply(200, answer(path))
        }
        override suspend fun webSocket(path: String): WebSocketLink = throw java.io.IOException("none")
        override suspend fun readChat(key: String, timeoutMs: Long): String = """{"type":"chat","chat":$chat}"""
        override fun close() {}
    }

    @Test
    fun acceptAndCloseGoWhere3TakesThem() = kotlinx.coroutines.runBlocking {
        val task = WorkTask("DEMO-2", "DEMO-2", "Demo", "sp0a01", WorkStatus.Ready, chat = "DEMO-2/worker")
        val t = Recorder("""{"key":"DEMO-2/worker","messages":[]}""")
        val board = V2WorkBoard(ShepherdrClient(t), "3.1.2")
        board.approve(task)
        board.close(task)
        // Accept is herdr-task accept, by the task's id; Close names it by its chat, as the task's row menu does.
        assertEquals(listOf("/api/action", "/api/action"), t.sent.map { it.first })
        assertEquals(listOf("""{"type":"accept","task":"DEMO-2"}""", """{"type":"close","chat":"DEMO-2/worker"}"""), t.sent.map { it.second })
        // A task no agent runs for yet (To do): its chat is still its id's.
        val todo = Recorder("{}")
        V2WorkBoard(ShepherdrClient(todo), "3.1.2").close(task.copy(status = WorkStatus.Todo, chat = ""))
        assertEquals("""{"type":"close","chat":"DEMO-2/worker"}""", todo.sent.single().second)
    }
}
