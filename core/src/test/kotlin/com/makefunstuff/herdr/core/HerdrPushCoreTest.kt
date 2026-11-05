package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.push.AlertChange
import com.makefunstuff.herdr.core.push.Alerts
import com.makefunstuff.herdr.core.push.HerdrPush
import com.makefunstuff.herdr.core.push.PushEvent
import com.makefunstuff.herdr.core.push.PushMessages
import com.makefunstuff.herdr.core.push.PushSeal
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * herdr-push on a machine with only the Herdr desktop app: herdr-core's agents, read on its socket
 * (here a fake core answering from the fixtures of a real `hc` 0.1.14), are pushed as classic
 * herdr's are, with `hc:` pane ids the app's buttons answer through herdr-core.
 */
class HerdrPushCoreTest {
    private val script = File(System.getProperty("herdrPush") ?: "../host/herdr-push/herdr-push")
    private val json = Json { ignoreUnknownKeys = true }

    private fun python(): Boolean = runCatching {
        ProcessBuilder("python3", "--version").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
    }.getOrDefault(false)

    private fun sh(dir: File, env: Map<String, String>, command: String, input: String = ""): Int {
        val pb = ProcessBuilder("sh", "-c", command).directory(dir)
        pb.environment().putAll(env)
        pb.environment().remove("HERDR_SOCKET_PATH")
        val p = pb.start()
        p.outputStream.use { it.write(input.toByteArray()) }
        p.inputStream.readBytes()
        val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        if (err.isNotBlank()) System.err.println(err)
        return p.exitValue()
    }

    /** A herdr-core on a Unix socket: hello, then `terminal.list` and `terminal.read` from [terminals] and [screen], one JSON line each. */
    private class FakeCore(socket: File, @Volatile var terminals: JsonArray, private val screen: String) {
        val methods = CopyOnWriteArrayList<String>()
        private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(socket.toPath())) }
        private val hello = javaClass.getResource("/hc/hello.json")!!.readText().trim()

        init {
            thread(isDaemon = true) {
                while (true) {
                    val ch = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        val input = Channels.newInputStream(ch).bufferedReader()
                        val out = Channels.newOutputStream(ch)
                        runCatching {
                            while (true) {
                                val line = input.readLine() ?: break
                                val req = Json.parseToJsonElement(line).jsonObject
                                val method = req["method"]!!.jsonPrimitive.content
                                methods += method
                                val result = when (method) {
                                    "hello" -> Json.parseToJsonElement(hello).jsonObject["result"]!!
                                    "terminal.list" -> JsonObject(mapOf("terminals" to terminals))
                                    "terminal.read" -> JsonObject(mapOf("text" to JsonPrimitive(screen)))
                                    else -> JsonObject(emptyMap())
                                }
                                val reply = JsonObject(mapOf("jsonrpc" to JsonPrimitive("2.0"), "id" to req["id"]!!, "result" to result))
                                out.write((reply.toString() + "\n").toByteArray())
                                out.flush()
                            }
                        }
                        ch.close()
                    }
                }
            }
        }

        fun close() = server.close()
    }

    @Test
    fun herdrCoresAgentsArePushedOnAMachineWithoutClassicHerdr() = pushes(classic = null)

    /** Classic herdr installed, its server not running: herdr-core's agents are told all the same. */
    @Test
    fun herdrCoresAgentsArePushedWhileClassicHerdrIsntRunning() = pushes(classic = "#!/bin/sh\necho 'herdr: no server running' >&2\nexit 1\n")

    private fun pushes(classic: String?) {
        assumeTrue(python(), "python3 isn't installed")
        val dir = Files.createTempDirectory("herdr-push-core").toFile()
        val herdr = classic?.let { File(dir, "herdr").apply { writeText(it); setExecutable(true) } }
        val received = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/up") { ex ->
            received += ex.requestBody.readBytes().toString(Charsets.UTF_8)
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        server.start()
        val listed = json.parseToJsonElement(javaClass.getResource("/hc/list.json")!!.readText()).jsonObject["result"]!!.jsonObject["terminals"]!!.jsonArray
        fun withT2(change: (JsonObject) -> JsonObject?) = JsonArray(listed.mapNotNull { t -> if (t.jsonObject["address"]!!.jsonPrimitive.content == "t2") change(t.jsonObject) else t })
        val working = withT2 { t -> JsonObject(t + ("status" to JsonObject(mapOf("app" to JsonPrimitive("codex"), "state" to JsonPrimitive("working"))))) }
        val socket = File("/tmp", "hc-push-${ProcessHandle.current().pid()}.sock").apply { delete() }
        val core = FakeCore(socket, working, javaClass.getResource("/screens/claude-permission.txt")!!.readText())
        try {
            // No classic herdr anywhere: not on the PATH, not in this $HOME.
            val env = mapOf(
                "HOME" to dir.path, "PATH" to "/usr/bin:/bin", "HERDR_PUSH_HOME" to File(dir, "state").path,
                "HERDR_PUSH_HEALTH_EVERY" to "0", "HC_SOCKET" to socket.path,
            )
            assertEquals(0, sh(dir, env, HerdrPush.installCommand(), HerdrPush.script))
            val key = PushSeal.newKey()
            val sub = HerdrPush.subscription(HerdrPush.subscriberId("phone1", "m1"), "Pixel", "http://127.0.0.1:${server.address.port}/up", key, "default", herdr?.path)
            assertEquals(0, sh(dir, env, HerdrPush.command("subscribe"), sub))

            // The first look only learns how things stand.
            assertEquals(0, sh(dir, env, HerdrPush.command("once")))
            assertTrue(received.isEmpty())

            // The desktop app's codex asks: a push with its question, Approve and Deny, and where the app shows it.
            core.terminals = listed
            assertEquals(0, sh(dir, env, HerdrPush.command("once")))
            assertEquals(1, received.size)
            val (id, msg) = assertNotNull(PushMessages.open(received[0], mapOf("m1" to key)))
            val event = assertIs<PushEvent.Agent>(msg.events.single())
            assertEquals("hc:t2", event.agent.paneId)
            assertEquals("Run ./gradlew test?", event.agent.title)
            assertEquals("herdr-android › build", event.where)
            val alert = assertIs<AlertChange.Show>(Alerts.of(id, "devbox", event, msg.host)).alert
            assertEquals("ha5-blocked needs you", alert.title)
            assertEquals(listOf("Approve", "Deny", "Reply"), alert.actions.map { it.label })
            assertTrue("terminal.read" in core.methods)

            // Answered at the desk and gone: the notification comes down.
            core.terminals = withT2 { null }
            assertEquals(0, sh(dir, env, HerdrPush.command("once")))
            assertEquals(2, received.size)
            val (_, after) = assertNotNull(PushMessages.open(received[1], mapOf("m1" to key)))
            assertEquals(listOf<AlertChange>(AlertChange.Clear("agent:m1:hc:t2")), after.events.map { Alerts.of("m1", "devbox", it) })

            // The core stops (and the watcher kept nothing of it, as after its own restart): its agents are gone,
            // the done one's notification comes down, and nothing starts a core.
            core.close()
            socket.delete()
            assertEquals(0, sh(dir, env, HerdrPush.command("once")))
            assertEquals(3, received.size)
            val (_, stopped) = assertNotNull(PushMessages.open(received[2], mapOf("m1" to key)))
            assertEquals(listOf<AlertChange>(AlertChange.Clear("agent:m1:hc:t4")), stopped.events.map { Alerts.of("m1", "devbox", it) })
            assertTrue(!socket.exists())
        } finally {
            core.close()
            socket.delete()
            server.stop(0)
            dir.deleteRecursively()
        }
    }
}
