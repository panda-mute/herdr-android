package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.shepherdr.ForwardTransport
import com.makefunstuff.herdr.core.shepherdr.LiveRelay
import com.makefunstuff.herdr.core.shepherdr.ShepherdrClient
import com.makefunstuff.herdr.core.shepherdr.SpaceChat
import com.makefunstuff.herdr.core.shepherdr.SpaceChatItem
import com.makefunstuff.herdr.core.shepherdr.V2Actions
import com.makefunstuff.herdr.core.shepherdr.V2Board
import com.makefunstuff.herdr.core.shepherdr.V2Event
import com.makefunstuff.herdr.core.shepherdr.V2Events
import com.makefunstuff.herdr.core.shepherdr.Wire
import com.makefunstuff.herdr.core.ssh.Duplex
import com.makefunstuff.herdr.core.voice.SpeechText
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the app can't read never hides the rest, and the relay that keeps the live link's snapshots (and the web app's
 * own views) off the phone's connection, with the service's token read on the machine.
 */
class ShepherdrRelayTest {
    private fun fixture(name: String) = javaClass.getResource("/shepherdr/$name")!!.readText()

    /** The first chat event 3.1.2 sent (`chats.json`), as its live link sends it. */
    private fun chatEvent() = (kotlinx.serialization.json.Json.parseToJsonElement(fixture("chats.json")) as kotlinx.serialization.json.JsonArray)[1].toString()

    @Test
    fun eventsTheAppCantReadNeverEndTheLink() {
        assertEquals("", (V2Events.parse("not json") as V2Event.Other).type)
        assertEquals("news", (V2Events.parse("""{"type":"news","news":{"x":1}}""") as V2Event.Other).type)
        // A chat without even a key is no chat.
        assertEquals("chat", (V2Events.parse("""{"type":"chat","chat":{"messages":[]}}""") as V2Event.Other).type)
        // A snapshot with a task in a newer shape keeps the rest.
        val s = (V2Events.parse(
            """{"type":"snapshot","snapshot":{"places":[{"id":"p1","name":"a"},{"name":"no id"}],"tasks":[{"id":"HA-1","status":"work"},{"id":"HA-2","report":"soon"}],"asks":{"p1":"q1","p2":{"id":"q2"}}}}""",
        ) as V2Event.Snapshot).snapshot
        assertEquals(listOf("p1"), s.places.map { it.id })
        assertEquals(listOf("HA-1"), s.tasks.map { it.id })
        assertEquals(mapOf("p1" to "q1"), s.asks)
        // A chat's screen doesn't read snapshots at all.
        assertEquals("snapshot", (V2Events.parse("""{"type":"snapshot","snapshot":{}}""", snapshots = false) as V2Event.Other).type)
        assertIs<V2Event.Chat>(V2Events.parse(chatEvent(), snapshots = false))
        // The web app's own views are never read.
        assertEquals("view", (V2Events.parse("""{"type":"view","name":"tasks","value":[{"x":1}]}""") as V2Event.Other).type)
    }

    @Test
    fun theRelayStartsAfterItsHelloAndEndsWithItsOutput() = runBlocking {
        val p = FakeProcess("Welcome to box\n${LiveRelay.HELLO}\n{\"type\":\"chat\",\"chat\":{\"key\":\"k\"}}\n")
        val link = LiveRelay.open(p)
        assertEquals("""{"type":"chat","chat":{"key":"k"}}""", link.receive())
        link.send("""{"type":"ping"}""")
        assertEquals("{\"type\":\"ping\"}\n", p.written.toString(Charsets.UTF_8))
        assertNull(link.receive())
        closes(p)
    }

    /** Closing goes off the caller's thread: it is closed soon after. */
    private fun closes(p: FakeProcess) {
        val until = System.currentTimeMillis() + 5_000
        while (!p.closed && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue(p.closed)
    }

    @Test
    fun noRelayWhereNothingRunsIt() = runBlocking {
        val p = FakeProcess("")
        assertFailsWith<LiveRelay.NoRelay> { LiveRelay.open(p) }
        closes(p)
        // Cancelled while it waits for the hello: closed, and the wait ends at once.
        val waiting = FakeProcess(BlockingInput())
        val job = launch { LiveRelay.open(waiting) }
        kotlinx.coroutines.delay(100)
        withTimeout(2_000) { job.cancelAndJoin() }
        closes(waiting)
        // No hello in time: a slow start, tried again next time, not "no relay here".
        val slow = FakeProcess(BlockingInput())
        val e = assertFailsWith<IOException> { LiveRelay.open(slow, timeoutMs = 200) }
        assertTrue(e !is LiveRelay.NoRelay)
        closes(slow)
        // A relay that ends before Shepherdr sent anything: Shepherdr isn't there.
        val gone = LiveRelay.open(FakeProcess("${LiveRelay.HELLO}\n"))
        assertFailsWith<IOException> { gone.receive() }
    }

    @Test
    fun withoutARelayTheLinkIsShepherdrsOwn() = runBlocking {
        MiniShepherdr().use { fake ->
            fake.script = { ws -> ws.send("""{"type":"build","id":"b1"}"""); ws.read() }
            var started = 0
            val transport = ForwardTransport({ ForwardTransport.socket(fake.port, it) }, relay = { started++; FakeProcess("bash: bun: not found\n") })
            val link = ShepherdrClient(transport).live(snapshots = false)
            assertEquals("build", (link.next() as V2Event.Other).type)
            link.close()
            // Not tried again on this connection.
            ShepherdrClient(transport).live(snapshots = false).close()
            assertEquals(1, started)
        }
    }

    /**
     * The relay script itself, run by this machine's `bun` (or a `node` with WebSocket) against a stand-in Shepherdr:
     * a chat's link gets no snapshots, nothing twice, and what the app sends goes to Shepherdr.
     */
    @Test
    fun theRelayPassesOnOnlyWhatChanged() = runBlocking {
        assumeTrue(runtime(), "needs bun, or node with WebSocket")
        MiniShepherdr().use { fake ->
            val chat1 = """{"type":"chat","chat":{"key":"k","messages":[{"id":"1","text":"one"}]}}"""
            val chat2 = """{"type":"chat","chat":{"key":"k","messages":[{"id":"1","text":"one"},{"id":"2","text":"two"}]}}"""
            val watched = LinkedBlockingQueue<String>()
            fake.script = { ws ->
                ws.send("""{"type":"hello","version":"3.1.2"}""")
                ws.send("""{"type":"view","name":"tasks","value":[]}""")
                ws.send("""{"type":"snapshot","snapshot":{"places":[]}}""")
                ws.send("""{"type":"build","id":"b1"}""")
                watched += ws.read()
                ws.send(chat1)
                ws.send("""{"type":"snapshot","snapshot":{"places":[]}}""")
                ws.send(chat1)
                ws.send(chat2)
                ws.send("""{"type":"pong"}""")
                ws.read()
            }
            // The service's token, where the relay reads it on its machine.
            val data = kotlin.io.path.createTempDirectory("shepherdr").toFile()
            java.io.File(data, "token").writeText("s3cret\n")
            val proc = ProcessBuilder("sh", "-c", LiveRelay.command(snapshots = false, url = "ws://127.0.0.1:${fake.port}/api/ws"))
                .apply { environment()["SHEPHERDR_DATA"] = data.path }.start()
            val link = LiveRelay.open(processDuplex(proc))
            link.send(V2Actions.watch(listOf("k")).toString())
            val got = withTimeout(20_000) { List(5) { link.receive() } }
            assertEquals(listOf("""{"type":"hello","version":"3.1.2"}""", """{"type":"build","id":"b1"}""", chat1, chat2, """{"type":"pong"}"""), got)
            assertEquals("Bearer s3cret", fake.authorization)
            data.deleteRecursively()
            assertTrue(watched.poll(5, TimeUnit.SECONDS)!!.contains("\"watch\""))
            link.close()
            assertTrue(proc.waitFor(10, TimeUnit.SECONDS), "the relay ends with its link")
        }
    }

    private fun runtime(): Boolean = runCatching {
        ProcessBuilder("sh", "-c", "exec \"\${SHELL:-/bin/sh}\" -lc 'command -v bun >/dev/null || node -e \"process.exit(typeof WebSocket===\\\"undefined\\\"?1:0)\"'")
            .redirectErrorStream(true).start().let { it.waitFor(20, TimeUnit.SECONDS) && it.exitValue() == 0 }
    }.getOrDefault(false)

    private fun processDuplex(p: Process): Duplex = object : Duplex {
        override val input: InputStream = p.inputStream
        override val output: OutputStream = p.outputStream
        // As the phone going: its stdin ends, nothing kills it.
        override fun close() {
            runCatching { p.outputStream.close() }
        }
    }

    private class FakeProcess(override val input: InputStream) : Duplex {
        constructor(out: String) : this(out.byteInputStream())
        val written = ByteArrayOutputStream()
        @Volatile var closed = false
        override val output: OutputStream = written
        override fun close() {
            closed = true
            (input as? BlockingInput)?.close()
        }
    }

    /** An output that never comes, until closed. */
    private class BlockingInput : InputStream() {
        private val gate = java.util.concurrent.CountDownLatch(1)
        override fun read(): Int { gate.await(); return -1 }
        override fun close() = gate.countDown()
    }
}

/** A stand-in for Shepherdr's live link on a loopback port, whatever its Host header: one scripted socket at a time. */
private class MiniShepherdr : AutoCloseable {
    private val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    @Volatile var script: (Ws) -> Unit = {}
    /** The last link's Authorization header. */
    @Volatile var authorization: String? = null
    val errors = CopyOnWriteArrayList<Throwable>()

    init {
        thread(isDaemon = true) {
            while (true) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    try {
                        val input = BufferedInputStream(s.getInputStream())
                        val headers = generateSequence { line(input) }.takeWhile { it.isNotEmpty() }.drop(1)
                            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                        authorization = headers["authorization"]
                        val accept = Wire.acceptFor(headers.getValue("sec-websocket-key"))
                        s.getOutputStream().write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                        script(Ws(input, s.getOutputStream()))
                    } catch (t: Throwable) {
                        errors += t
                    } finally {
                        runCatching { s.close() }
                    }
                }
            }
        }
    }

    override fun close() { server.close() }

    private fun line(input: InputStream): String {
        val b = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) return b.toString().trimEnd('\r')
            b.append(c.toChar())
        }
    }

    class Ws(private val input: InputStream, private val output: OutputStream) {
        fun send(text: String) {
            val p = text.toByteArray()
            val out = ByteArrayOutputStream()
            out.write(0x81)
            when {
                p.size < 126 -> out.write(p.size)
                p.size <= 0xFFFF -> { out.write(126); out.write(p.size shr 8); out.write(p.size and 0xFF) }
                else -> { out.write(127); for (i in 7 downTo 0) out.write(((p.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
            }
            out.write(p)
            output.write(out.toByteArray())
            output.flush()
        }

        /** The client's next text message; "" once it closed. */
        fun read(): String {
            while (true) {
                val b0 = input.read()
                if (b0 < 0) return ""
                val b1 = input.read()
                var len = b1 and 0x7F
                if (len == 126) len = (input.read() shl 8) or input.read()
                val mask = if (b1 and 0x80 != 0) Wire.readExactly(input, 4) else null
                val p = Wire.readExactly(input, len)
                if (mask != null) for (i in p.indices) p[i] = (p[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (b0 and 0x0F) {
                    0x8 -> return ""
                    0x1 -> return String(p)
                }
            }
        }
    }
}
