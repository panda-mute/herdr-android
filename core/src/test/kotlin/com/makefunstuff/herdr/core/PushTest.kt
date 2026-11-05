package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.chat.BlockedPrompt
import com.makefunstuff.herdr.core.brief.Briefs
import com.makefunstuff.herdr.core.brief.JournalEntry
import com.makefunstuff.herdr.core.brief.Journals
import com.makefunstuff.herdr.core.brief.MachineLook
import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.AlertAction
import com.makefunstuff.herdr.core.push.AlertChange
import com.makefunstuff.herdr.core.push.Alerts
import com.makefunstuff.herdr.core.push.HerdrPush
import com.makefunstuff.herdr.core.push.LiveItem
import com.makefunstuff.herdr.core.push.LiveItems
import com.makefunstuff.herdr.core.push.LiveTracker
import com.makefunstuff.herdr.core.push.PushEvent
import com.makefunstuff.herdr.core.push.PushMessage
import com.makefunstuff.herdr.core.push.PushMessages
import com.makefunstuff.herdr.core.push.PushSeal
import com.makefunstuff.herdr.core.push.Presence
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushSealTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun opensWhatItSeals() {
        val sealed = PushSeal.sealText(key, "{\"v\":1,\"events\":[]}")
        assertTrue(sealed.startsWith("hp1."))
        assertEquals("{\"v\":1,\"events\":[]}", PushSeal.openText(key, sealed))
    }

    @Test
    fun refusesAnotherKeyOrAChange() {
        val sealed = PushSeal.sealText(key, "hello")
        assertNull(PushSeal.openText(ByteArray(32), sealed))
        val raw = Base64.getUrlDecoder().decode(sealed.removePrefix("hp1."))
        raw[20] = (raw[20].toInt() xor 1).toByte()
        assertNull(PushSeal.openText(key, "hp1." + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)))
        assertNull(PushSeal.openText(key, "not a push"))
        assertNull(PushSeal.openText(key, "hp1.AAAA"))
    }

    @Test
    fun longTextSpansManyBlocks() {
        val text = "x".repeat(100) + (1..500).joinToString { "line $it" }
        assertEquals(text, PushSeal.openText(key, PushSeal.sealText(key, text)))
    }

    @Test
    fun takesPushesInOrder() {
        val now = 1_760_000_000L
        assertTrue(PushMessages.fresh(now, 0, now))
        assertTrue(PushMessages.fresh(now, now, now), "two pushes in the same second")
        assertTrue(PushMessages.fresh(now + 5, now, now + 5))
        // Sent again after a newer one was taken, or overtaken by a newer state: dropped.
        assertTrue(!PushMessages.fresh(now - 1, now, now))
        // Held back for days: dropped.
        assertTrue(!PushMessages.fresh(now - PushMessages.MAX_AGE_SECONDS - 1, 0, now))
        // A push that doesn't say when: taken.
        assertTrue(PushMessages.fresh(0, now, now))
    }

    @Test
    fun findsTheMachineByItsKey() {
        val other = PushSeal.newKey()
        val sealed = PushSeal.sealText(key, "{\"v\":1,\"host\":\"devbox\",\"at\":5,\"events\":[{\"type\":\"test\"}]}")
        val (id, msg) = assertNotNull(PushMessages.open(sealed, mapOf("a" to other, "b" to key)))
        assertEquals("b", id)
        assertEquals("devbox", msg.host)
        assertEquals(listOf<PushEvent>(PushEvent.Test), msg.events)
    }
}

class AlertsTest {
    private fun screen(name: String) = javaClass.getResource("/screens/$name")!!.readText()

    private fun agent(status: String, name: String? = "api", seq: Long = 7) = AgentInfo(
        name = name, agent = "claude", agentStatusRaw = status, workspaceId = "w1", tabId = "t1", paneId = "w1:p1", stateChangeSeq = seq,
        title = "Speed up /jobs",
    )

    private fun show(c: AlertChange): Alert = assertIs<AlertChange.Show>(c).alert

    @Test
    fun blockedAgentWithAYesNoQuestion() {
        val a = show(Alerts.of("m1", "devbox", PushEvent.Agent(agent("blocked"), "api › agents", screen("claude-permission.txt"))))
        assertEquals("agent:m1:w1:p1", a.tag)
        assertEquals("api needs you", a.title)
        assertEquals("Do you want to proceed?", a.text)
        assertEquals(Alert.Kind.NeedsYou, a.kind)
        assertEquals("w1:p1", a.paneId)
        assertEquals(listOf("Approve", "Deny", "Reply"), a.actions.map { it.label })
        assertEquals(0, a.actions[0].option)
        assertEquals("Yes", a.actions[0].optionLabel)
        assertEquals(2, a.actions[1].option)
        assertEquals("Do you want to proceed?", a.actions[1].question)
        // What it would run must still be the same when the button is pressed.
        assertTrue("touch b.txt" in a.actions[0].details, a.actions[0].details.toString())
        assertTrue(a.detail.endsWith("devbox · api › agents"))
        assertEquals("blocked#7", a.sig)
    }

    @Test
    fun codexAndTrustQuestions() {
        val codex = assertNotNull(BlockedPrompt.parse(screen("codex-permission.txt")))
        assertEquals(0, codex.approveIndex)
        assertEquals(2, codex.denyIndex)
        val trust = assertNotNull(BlockedPrompt.parse(screen("codex-trust.txt")))
        assertEquals(false, trust.yesNo)
        // Two options that aren't yes and no: each one is its own button.
        val a = show(Alerts.of("m1", "devbox", PushEvent.Agent(agent("blocked"), "", screen("codex-trust.txt"))))
        assertEquals(listOf("Trust and continue", "Back to Agent Command C…", "Reply"), a.actions.map { it.label })
        val claudeTrust = assertNotNull(BlockedPrompt.parse(screen("claude-trust.txt")))
        assertTrue(claudeTrust.yesNo)
    }

    @Test
    fun anAlwaysAllowButtonIsGuarded() {
        // pi's two-option menu: each option is a button, and the permanent one asks for the fingerprint.
        val screen = " Allow the extension to run git push?\n\n→ Allow once\n  Always allow\n\n ↑↓ navigate · enter select\n"
        val a = show(Alerts.of("m1", "devbox", PushEvent.Agent(agent("blocked"), "", screen)))
        assertEquals(listOf("Allow once", "Always allow", "Reply"), a.actions.map { it.label })
        assertEquals(listOf("", "always", ""), a.actions.map { it.guard })
        // Approve and Deny never press a permanent allow.
        val yesNo = show(Alerts.of("m1", "devbox", PushEvent.Agent(agent("blocked"), "", screen("claude-permission.txt"))))
        assertEquals(listOf("", "", ""), yesNo.actions.map { it.guard })
    }

    @Test
    fun aClaudeFormIsAnsweredInTheChat() {
        val screen = """
            ←  ☐ Route  ☐ Author  ✔ Submit  →
            Which way should the PR go?
            ❯ 1. Log in as owner
                 Authenticate as the repository owner.
              2. Fork
                 Push the branch to a fork.
              3. Type something.
            ────────────────────────────────────────
              4. Chat about this
            Enter to select · Tab/Arrow keys to navigate · Esc to cancel
        """.trimIndent()
        val a = show(Alerts.of("m1", "devbox", PushEvent.Agent(agent("blocked"), "", screen)))
        assertEquals("Which way should the PR go?", a.text)
        assertEquals(listOf("Reply"), a.actions.map { it.label })
    }

    @Test
    fun blockedWithoutAScreenOnlyReplies() {
        val a = show(Alerts.of("m1", "devbox", PushEvent.Agent(agent("blocked", name = null), "")))
        assertEquals("claude needs you", a.title)
        assertEquals("claude", a.agentName, "its chat's name, for its bubble")
        assertEquals("Waiting for your answer", a.text)
        assertEquals(listOf(AlertAction.Type.ReplyAgent), a.actions.map { it.type })
    }

    @Test
    fun finishedAndBackToWork() {
        val a = show(Alerts.of("m1", "devbox", PushEvent.Agent(agent("done", seq = 9), "api › agents")))
        assertEquals("api finished", a.title)
        assertEquals("api", a.agentName)
        assertEquals("Speed up /jobs", a.text)
        assertEquals(Alert.Kind.Finished, a.kind)
        assertEquals("done#9", a.sig)
        assertEquals(AlertChange.Clear("agent:m1:w1:p1"), Alerts.of("m1", "devbox", PushEvent.Agent(agent("working"), "")))
        assertEquals(AlertChange.Clear("agent:m1:w1:p1"), Alerts.of("m1", "devbox", PushEvent.Agent(agent("gone"), "")))
    }
}

/**
 * The watcher itself (host/herdr-push, Python) against a fake herdr command and a
 * local push server: what it sends must open on the phone's side and read as the right notifications.
 */
class HerdrPushTest {
    private val script = File(System.getProperty("herdrPush") ?: "../host/herdr-push/herdr-push")

    private fun python(): Boolean = runCatching {
        ProcessBuilder("python3", "--version").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
    }.getOrDefault(false)

    private fun screen(name: String) = javaClass.getResource("/screens/$name")!!.readText()

    private class Run(val exit: Int, val out: String)

    private fun sh(dir: File, env: Map<String, String>, command: String, input: String = ""): Run {
        val pb = ProcessBuilder("sh", "-c", command).directory(dir)
        pb.environment().putAll(env)
        pb.environment().remove("HERDR_SOCKET_PATH")
        // This machine's own herdr-core (if one runs) stays out of the tests: a test gives its own.
        pb.environment().putIfAbsent("HC_SOCKET", File(dir, "no-herdr-core.sock").path)
        val p = pb.start()
        p.outputStream.use { it.write(input.toByteArray()) }
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        if (err.isNotBlank()) System.err.println(err)
        return Run(p.exitValue(), out)
    }

    @Test
    fun bundledScriptIsTheOneInHost() {
        assertEquals(script.readText(), HerdrPush.script)
        assertEquals("11", HerdrPush.version)
    }

    @Test
    fun pythonSealsWhatKotlinOpens() {
        assumeTrue(python(), "python3 isn't installed")
        val key = PushSeal.newKey()
        val b64 = Base64.getEncoder().encodeToString(key)
        val msg = """{"v":1,"host":"devbox","at":5,"events":[{"type":"test"},{"type":"agent","agent":{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"t1","agent_status":"done","agent":"claude","name":"ünïcode ✓"},"where":"api › agents"}]}"""
        val p = ProcessBuilder("python3", script.path, "_seal", b64, msg).start()
        val sealed = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        assertTrue(p.waitFor(20, TimeUnit.SECONDS))
        val (_, opened) = assertNotNull(PushMessages.open(sealed, mapOf("m" to key)))
        assertEquals(2, opened.events.size)
        val agent = assertIs<PushEvent.Agent>(opened.events[1])
        assertEquals("ünïcode ✓", agent.agent.name)
        assertEquals("api › agents", agent.where)
    }

    @Test
    fun watchesAndSends() {
        assumeTrue(python(), "python3 isn't installed")
        val dir = Files.createTempDirectory("herdr-push").toFile()
        try {
            watch(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun watch(dir: File) {
        val received = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/up") { ex ->
            received += ex.requestBody.readBytes().toString(Charsets.UTF_8)
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        server.start()
        try {
            val fake = File(dir, "fake").apply { mkdirs() }
            val herdr = File(fake, "herdr").apply {
                writeText("#!/bin/sh\ncase \"\$1 \$2\" in\n\"api snapshot\") cat \"\$FAKE/snapshot.json\";;\n\"pane read\") cat \"\$FAKE/screen.txt\";;\n*) exit 3;;\nesac\n")
                setExecutable(true)
            }
            fun agents(status: String) = """{"result":{"snapshot":{"workspaces":[{"workspace_id":"w1","label":"api"}],"tabs":[{"tab_id":"t1","label":"agents"}],
                "agents":[{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"t1","agent":"claude","name":"api","agent_status":"$status","state_change_seq":4}]}}}"""
            File(fake, "snapshot.json").writeText(agents("working"))
            File(fake, "screen.txt").writeText(screen("claude-permission.txt"))

            // $HOME is the temp folder, so the app's own commands (install, then herdr-push by path) run as on a machine.
            val env = mapOf("HOME" to dir.path, "FAKE" to fake.path, "HERDR_PUSH_HOME" to File(dir, "state").path, "HERDR_PUSH_HEALTH_EVERY" to "0")
            assertEquals(0, sh(dir, env, HerdrPush.installCommand(), HerdrPush.script).exit)
            assertTrue(File(dir, ".local/share/herdr-push/herdr-push").canExecute())

            val key = PushSeal.newKey()
            val sub = HerdrPush.subscription(HerdrPush.subscriberId("phone1", "m1"), "Pixel", "http://127.0.0.1:${server.address.port}/up", key, "default", herdr.path)
            val subscribed = sh(dir, env, HerdrPush.command("subscribe"), sub)
            assertEquals(true, HerdrPush.answer(subscribed.out)?.get("ok")?.toString()?.toBoolean())

            // The first look only learns how things stand.
            assertEquals(0, sh(dir, env, HerdrPush.command("once")).exit)
            assertTrue(received.isEmpty())

            // Then the agent asks: one push.
            File(fake, "snapshot.json").writeText(agents("blocked"))
            assertEquals(0, sh(dir, env, HerdrPush.command("once")).exit)
            assertEquals(1, received.size)
            val (id, msg) = assertNotNull(PushMessages.open(received[0], mapOf("other" to PushSeal.newKey(), "m1" to key)))
            assertEquals("m1", id)
            val changes = msg.events.map { Alerts.of(id, "devbox", it, msg.host) }
            val agent = assertIs<AlertChange.Show>(changes[0]).alert
            assertEquals("api needs you", agent.title)
            assertEquals(listOf("Approve", "Deny", "Reply"), agent.actions.map { it.label })
            assertTrue(agent.detail.endsWith("devbox · api › agents"))

            // Answered on the desktop: the notification comes down.
            File(fake, "snapshot.json").writeText(agents("working"))
            assertEquals(0, sh(dir, env, HerdrPush.command("once")).exit)
            assertEquals(2, received.size)
            val (_, after) = assertNotNull(PushMessages.open(received[1], mapOf("m1" to key)))
            assertEquals(
                listOf<AlertChange>(AlertChange.Clear("agent:m1:w1:p1")),
                after.events.map { Alerts.of("m1", "devbox", it) },
            )

            // A test notification, and the status the app reads.
            val test = sh(dir, env, HerdrPush.command("test", "phone1:m1"))
            assertEquals(0, test.exit)
            val (_, t) = assertNotNull(PushMessages.open(received[2], mapOf("m1" to key)))
            assertEquals(listOf<PushEvent>(PushEvent.Test), t.events)
            val status = HerdrPush.status(assertNotNull(HerdrPush.answer(sh(dir, env, HerdrPush.command("status")).out)), "phone1:m1")
            assertEquals("11", status.version)
            assertTrue(status.subscribed)
            assertNull(status.error)

            assertEquals(0, sh(dir, env, HerdrPush.command("unsubscribe", "phone1:m1")).exit)
            assertTrue(!HerdrPush.status(HerdrPush.answer(sh(dir, env, HerdrPush.command("status")).out)!!, "phone1:m1").subscribed)
        } finally {
            server.stop(0)
        }
    }

    /** The journal the brief reads: what agents did, after the first look learned how things stood. */
    @Test
    fun keepsAJournal() {
        assumeTrue(python(), "python3 isn't installed")
        val dir = Files.createTempDirectory("herdr-push").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/up") { ex -> ex.requestBody.readBytes(); ex.sendResponseHeaders(200, -1); ex.close() }
        server.start()
        try {
            val fake = File(dir, "fake").apply { mkdirs() }
            val herdr = File(fake, "herdr").apply {
                writeText("#!/bin/sh\ncase \"\$1 \$2\" in\n\"api snapshot\") cat \"\$FAKE/snapshot.json\";;\n\"pane read\") echo;;\n*) exit 3;;\nesac\n")
                setExecutable(true)
            }
            fun agents(status: String, seq: Int) = File(fake, "snapshot.json").writeText(
                """{"result":{"snapshot":{"workspaces":[{"workspace_id":"w1","label":"api"}],"tabs":[{"tab_id":"t1","label":"main"}],
                "agents":[{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"t1","agent":"claude","title":"Speed up /jobs","agent_status":"$status","state_change_seq":$seq}]}}}""",
            )
            agents("working", 1)
            val env = mapOf("HOME" to dir.path, "FAKE" to fake.path, "HERDR_PUSH_HOME" to File(dir, "state").path, "HERDR_PUSH_HEALTH_EVERY" to "0")
            assertEquals(0, sh(dir, env, HerdrPush.installCommand(), HerdrPush.script).exit)
            sh(dir, env, HerdrPush.command("subscribe"), HerdrPush.subscription("phone1:m1", "Pixel", "http://127.0.0.1:${server.address.port}/up", PushSeal.newKey(), "default", herdr.path))
            fun journal(since: Long = 0) = assertNotNull(Journals.parse(assertNotNull(HerdrPush.answer(sh(dir, env, HerdrPush.command("journal", "--session", "default", "--since", since.toString())).out))))

            // Before any look there is no journal; the first look only learns how things stand.
            assertNull(journal().from)
            assertEquals(0, sh(dir, env, HerdrPush.command("once")).exit)
            val first = journal()
            assertNotNull(first.from)
            assertTrue(first.entries.isEmpty())

            // The agent finishes; then it finishes again.
            agents("done", 2)
            assertEquals(0, sh(dir, env, HerdrPush.command("once")).exit)
            agents("working", 3)
            assertEquals(0, sh(dir, env, HerdrPush.command("once")).exit)
            agents("done", 4)
            assertEquals(0, sh(dir, env, HerdrPush.command("once")).exit)

            val j = journal()
            assertEquals(2, j.entries.filterIsInstance<JournalEntry.Agent>().size)
            assertTrue(j.upto != null && j.now >= j.upto!!)

            // The brief from it: the agent finished (once, its last time).
            val b = Briefs.build(listOf(MachineLook("m1", "devbox", journal = j)), (j.from!! - 1) * 1000, j.now * 1000)
            assertEquals(listOf("Speed up /jobs"), b.finished.map { it.title })

            // Asked from later on: nothing.
            assertTrue(journal(since = j.now + 60).entries.isEmpty())
        } finally {
            server.stop(0)
            dir.deleteRecursively()
        }
    }

    /** Two machines in the app on one host (herdr sessions default and work): each keeps its subscription, session and key. */
    @Test
    fun twoMachinesOnOneHost() {
        assumeTrue(python(), "python3 isn't installed")
        val dir = Files.createTempDirectory("herdr-push").toFile()
        val received = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/up") { ex ->
            received += ex.requestBody.readBytes().toString(Charsets.UTF_8)
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        server.start()
        try {
            val fake = File(dir, "fake").apply { mkdirs() }
            // Each session answers its own snapshot, as herdr does for HERDR_SESSION.
            val herdr = File(fake, "herdr").apply {
                writeText(
                    """
                    #!/bin/sh
                    s="${'$'}{HERDR_SESSION:-default}"
                    case "${'$'}1 ${'$'}2" in
                    "api snapshot") cat "${'$'}FAKE/snapshot-${'$'}s.json";;
                    "pane read") echo;;
                    *) exit 3;;
                    esac
                    """.trimIndent() + "\n",
                )
                setExecutable(true)
            }
            fun snapshot(session: String, status: String) = File(fake, "snapshot-$session.json").writeText(
                """{"result":{"snapshot":{"agents":[{"pane_id":"$session:p1","workspace_id":"w1","tab_id":"t1","agent":"claude","name":"$session-agent","agent_status":"$status"}]}}}""",
            )
            snapshot("default", "working")
            snapshot("work", "working")
            val env = mapOf("HOME" to dir.path, "FAKE" to fake.path, "HERDR_PUSH_HOME" to File(dir, "state").path, "HERDR_PUSH_HEALTH_EVERY" to "0")
            assertEquals(0, sh(dir, env, HerdrPush.installCommand(), HerdrPush.script).exit)
            val url = "http://127.0.0.1:${server.address.port}/up"
            val keyA = PushSeal.newKey()
            val keyB = PushSeal.newKey()
            val a = HerdrPush.subscriberId("phone1", "m1")
            val b = HerdrPush.subscriberId("phone1", "m2")
            // A board that isn't there: only the agents count here.
            sh(dir, env, HerdrPush.command("subscribe"), HerdrPush.subscription(a, "Pixel", url, keyA, "default", herdr.path))
            sh(dir, env, HerdrPush.command("subscribe"), HerdrPush.subscription(b, "Pixel", url, keyB, "work", herdr.path))

            val status = assertNotNull(HerdrPush.answer(sh(dir, env, HerdrPush.command("status")).out))
            assertTrue(HerdrPush.status(status, a).subscribed)
            assertTrue(HerdrPush.status(status, b).subscribed)

            assertEquals(0, sh(dir, env, HerdrPush.command("once")).exit)
            snapshot("default", "blocked")
            snapshot("work", "done")
            assertEquals(0, sh(dir, env, HerdrPush.command("once")).exit)
            assertEquals(2, received.size)
            // Each push opens only with its own machine's key and tells about its own session.
            val opened = received.map { assertNotNull(PushMessages.open(it, mapOf("m1" to keyA, "m2" to keyB))) }
                .associate { (id, msg) -> id to (msg.events.single() as PushEvent.Agent).agent }
            assertEquals("default-agent", opened["m1"]?.name)
            assertEquals("blocked", opened["m1"]?.agentStatusRaw)
            assertEquals("work-agent", opened["m2"]?.name)
            assertEquals("done", opened["m2"]?.agentStatusRaw)

            // Leaving one out keeps the other.
            sh(dir, env, HerdrPush.command("unsubscribe", a))
            val after = assertNotNull(HerdrPush.answer(sh(dir, env, HerdrPush.command("status")).out))
            assertTrue(!HerdrPush.status(after, a).subscribed)
            assertTrue(HerdrPush.status(after, b).subscribed)
        } finally {
            server.stop(0)
            dir.deleteRecursively()
        }
    }

    /** Live Updates: the working agents go to a phone that asked for them, at once when one starts or stops, and not to one that didn't. */
    @Test
    fun liveUpdates() {
        assumeTrue(python(), "python3 isn't installed")
        val dir = Files.createTempDirectory("herdr-push").toFile()
        val received = CopyOnWriteArrayList<Pair<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            received += ex.requestURI.path to ex.requestBody.readBytes().toString(Charsets.UTF_8)
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        server.start()
        try {
            val fake = File(dir, "fake").apply { mkdirs() }
            val herdr = File(fake, "herdr").apply {
                writeText("#!/bin/sh\ncase \"\$1 \$2\" in\n\"api snapshot\") cat \"\$FAKE/snapshot.json\";;\n\"pane read\") echo;;\n*) exit 3;;\nesac\n")
                setExecutable(true)
            }
            fun agents(status: String, title: String = "Speed up /jobs") = File(fake, "snapshot.json").writeText(
                """{"result":{"snapshot":{"workspaces":[{"workspace_id":"w1","label":"api"}],"tabs":[{"tab_id":"t1","label":"agents"}],
                "agents":[{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"t1","agent":"claude","name":"api","title":"$title","agent_status":"$status","state_change_seq":4}]}}}""",
            )
            agents("working")

            val env = mapOf("HOME" to dir.path, "FAKE" to fake.path, "HERDR_PUSH_HOME" to File(dir, "state").path, "HERDR_PUSH_HEALTH_EVERY" to "0")
            assertEquals(0, sh(dir, env, HerdrPush.installCommand(), HerdrPush.script).exit)
            val base = "http://127.0.0.1:${server.address.port}"
            val key = PushSeal.newKey()
            val plainKey = PushSeal.newKey()
            sh(dir, env, HerdrPush.command("subscribe"), HerdrPush.subscription("phone1:m1", "Pixel", "$base/live", key, "default", herdr.path, live = true))
            sh(dir, env, HerdrPush.command("subscribe"), HerdrPush.subscription("phone2:m1", "Old", "$base/plain", plainKey, "default", herdr.path))
            fun pushes(path: String, k: ByteArray) = received.filter { it.first == path }.map { assertNotNull(PushMessages.open(it.second, mapOf("m1" to k))).second }
            fun live(msg: PushMessage) = msg.events.filterIsInstance<PushEvent.Live>().single()

            // The first look sends what runs (the notifications only learn how things stand).
            assertEquals(0, sh(dir, env, HerdrPush.command("once")).exit)
            val first = pushes("/live", key).single()
            assertEquals(1, first.events.size)
            val agentItems = assertNotNull(LiveItems.of("m1", "devbox", live(first), first.at, System.currentTimeMillis()))
            assertEquals("api", agentItems.single().title)
            assertEquals("Working · Speed up /jobs · api › agents", agentItems.single().text)
            assertTrue(agentItems.single().since > 0 && System.currentTimeMillis() - agentItems.single().since < 60_000)
            assertTrue(pushes("/plain", plainKey).isEmpty(), "a phone without Live Updates gets none")

            // Nothing new, or only its title (sent at most every 90 s): no push.
            assertEquals(0, sh(dir, env, HerdrPush.command("once")).exit)
            agents("working", title = "Speed up /jobs, the index")
            assertEquals(0, sh(dir, env, HerdrPush.command("once")).exit)
            assertEquals(1, received.size)

            // The agent asks: its notification, and nothing runs any more.
            agents("blocked")
            assertEquals(0, sh(dir, env, HerdrPush.command("once")).exit)
            val last = pushes("/live", key).last()
            assertEquals(listOf("api needs you"), Alerts.all("m1", "devbox", last.events).map { (it as AlertChange.Show).alert.title })
            assertEquals(emptyList(), LiveItems.of("m1", "devbox", live(last), last.at, 0))
            // The other phone got the same notification, without the live part.
            val plain = pushes("/plain", plainKey).single()
            assertTrue(plain.events.none { it is PushEvent.Live })
            assertEquals(1, plain.events.size)
        } finally {
            server.stop(0)
            dir.deleteRecursively()
        }
    }

    /**
     * Who is at the machine rides on every push (and answers `presence`), and a phone's watches: a
     * watched agent's every change goes to that phone alone, and fires its watch there.
     */
    @Test
    fun presenceAndWatches() {
        assumeTrue(python(), "python3 isn't installed")
        val dir = Files.createTempDirectory("herdr-push").toFile()
        val received = CopyOnWriteArrayList<Pair<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            received += ex.requestURI.path to ex.requestBody.readBytes().toString(Charsets.UTF_8)
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        server.start()
        try {
            val fake = File(dir, "fake").apply { mkdirs() }
            val herdr = File(fake, "herdr").apply {
                writeText("#!/bin/sh\ncase \"\$1 \$2\" in\n\"api snapshot\") cat \"\$FAKE/snapshot.json\";;\n\"pane read\") echo;;\n*) exit 3;;\nesac\n")
                setExecutable(true)
            }
            fun agents(status: String) = File(fake, "snapshot.json").writeText(
                """{"result":{"snapshot":{"agents":[{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"t1","agent":"claude","name":"api","agent_status":"$status","state_change_seq":4}]}}}""",
            )
            agents("working")
            val state = File(dir, "state")
            val env = mapOf(
                "HOME" to dir.path, "FAKE" to fake.path, "HERDR_PUSH_HOME" to state.path, "HERDR_PUSH_HEALTH_EVERY" to "0",
                "HERDR_PUSH_SCREEN" to "unlocked",
            )
            assertEquals(0, sh(dir, env, HerdrPush.installCommand(), HerdrPush.script).exit)
            val base = "http://127.0.0.1:${server.address.port}"
            val key = PushSeal.newKey()
            val otherKey = PushSeal.newKey()
            sh(dir, env, HerdrPush.command("subscribe"), HerdrPush.subscription("phone1:m1", "Pixel", "$base/watching", key, "default", herdr.path, watchPanes = listOf("w1:p1")))
            sh(dir, env, HerdrPush.command("subscribe"), HerdrPush.subscription("phone2:m1", "Other", "$base/other", otherKey, "default", herdr.path))
            fun pushes(path: String, k: ByteArray) = received.filter { it.first == path }.map { assertNotNull(PushMessages.open(it.second, mapOf("m1" to k))).second }

            // Nobody typed in herdr here yet; the screen is unlocked.
            val nobody = PushMessages.presence(assertNotNull(HerdrPush.answer(sh(dir, env, HerdrPush.command("presence")).out)))
            assertEquals(Presence(typedAgo = null, unlocked = true), nobody)

            assertEquals(0, sh(dir, env, HerdrPush.command("once")).exit)
            assertTrue(received.isEmpty())
            // Someone types (the presence file), and the watched agent goes from working to idle (not done: no
            // notification for anyone else): the watch fires, and the push says they are here.
            File(state, "present").writeText("")
            agents("idle")
            assertEquals(0, sh(dir, env, HerdrPush.command("once")).exit)
            val idle = pushes("/watching", key).last()
            assertEquals("idle", idle.events.filterIsInstance<PushEvent.Agent>().single().agent.agentStatusRaw)
            val here = assertNotNull(idle.presence)
            assertEquals(true, here.unlocked)
            assertTrue(here.typedAgo!! in 0..30)
            assertTrue(pushes("/other", otherKey).isEmpty())
        } finally {
            server.stop(0)
            dir.deleteRecursively()
        }
    }

    @Test
    fun saysWhenItIsMissing() {
        val dir = Files.createTempDirectory("herdr-push").toFile()
        try {
            assertEquals(HerdrPush.MISSING_EXIT, sh(dir, mapOf("HOME" to dir.path), HerdrPush.command("status")).exit)
        } finally {
            dir.deleteRecursively()
        }
    }
}
