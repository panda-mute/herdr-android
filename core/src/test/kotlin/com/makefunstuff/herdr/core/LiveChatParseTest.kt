package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.shepherdr.ForwardTransport
import com.makefunstuff.herdr.core.shepherdr.ShepherdrClient
import com.makefunstuff.herdr.core.shepherdr.ShepherdrText
import com.makefunstuff.herdr.core.shepherdr.SpaceChat
import com.makefunstuff.herdr.core.shepherdr.SpaceChatItem
import com.makefunstuff.herdr.core.shepherdr.V2Board
import com.makefunstuff.herdr.core.shepherdr.V2Event
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The app's client against the Shepherdr 3 service running on this machine, read as the app reads it over SSH (the
 * same token command, the same live link and relay), only without the SSH: every space, task and Assistant chat
 * reads, and each agent message with words shows. Read only. Runs only with HERDR_PROBE_SHEPHERDR_PORT set (the
 * loopback port the service listens on: 7788).
 */
class LiveChatParseTest {
    private val port = System.getenv("HERDR_PROBE_SHEPHERDR_PORT")?.toIntOrNull()

    /** The service's token as the app reads it on a machine ([com.makefunstuff.herdr.core.shepherdr.BoardDetection.TOKEN_COMMAND]). */
    private val token: String? by lazy {
        val p = ProcessBuilder("sh", "-c", com.makefunstuff.herdr.core.shepherdr.BoardDetection.TOKEN_COMMAND).start()
        com.makefunstuff.herdr.core.shepherdr.BoardDetection.token(p.inputStream.readBytes().toString(Charsets.UTF_8).also { p.waitFor() })
    }

    private fun client() = ShepherdrClient(ForwardTransport({ ForwardTransport.socket(port!!, it) }, token = token))

    /** What the Board, Assistant and Needs you screens get from this machine's service: printed, so a person can compare. */
    @Test
    fun theBoardAsTheAppReadsIt() {
        assumeTrue(port != null, "set HERDR_PROBE_SHEPHERDR_PORT to run")
        runBlocking {
            val c = client()
            println("health: ${c.version()}")
            val board = com.makefunstuff.herdr.core.shepherdr.V2WorkBoard(c, c.version().version)
            val o = board.overview()
            println("spaces (${o.spaces.size}): " + o.spaces.joinToString { "${it.name}${if (it.needsYou) " (needs you)" else ""}" })
            for (t in o.tasks.filter { it.status != com.makefunstuff.herdr.core.board.WorkStatus.Done }) {
                println("  ${t.name.padEnd(9)} ${t.status.label.padEnd(9)} ${(t.progress?.let { "$it%" } ?: "").padEnd(4)} ${(t.left ?: "").padEnd(4)} ${t.title} · ${t.line}")
            }
            println("done: ${o.tasks.count { it.status == com.makefunstuff.herdr.core.board.WorkStatus.Done }}, needs you: ${o.needs.size}, artifacts: ${o.artifacts?.size}")
            assertTrue(o.spaces.isNotEmpty() && o.spaces.all { it.chat.startsWith("p:") })
            // Each recipe artifact as the artifact screen reads it: its blocks, and how many it doesn't draw.
            for (art in o.artifacts.orEmpty().filter { it.drawn(it.v) }) {
                val r = board.recipe(art, art.v)
                val kinds = r.blocks.groupingBy { it::class.simpleName }.eachCount()
                println("artifact ${art.title} v${art.v}: ${r.blocks.size} blocks $kinds, pictures ${com.makefunstuff.herdr.core.shepherdr.Recipes.pictures(r.blocks).size}")
                assertTrue(r.blocks.none { it is com.makefunstuff.herdr.core.shepherdr.RecipeBlock.Unknown })
            }
        }
    }

    @Test
    fun everyCoordinatorReplyShows() {
        assumeTrue(port != null, "set HERDR_PROBE_SHEPHERDR_PORT to run")
        val client = client()
        runBlocking {
            val snapshot = client.snapshot()
            val keys = snapshot.places.map { it.chat }.filter { it.isNotEmpty() }
            val chats = client.live().use { link ->
                link.watch(keys)
                val got = HashMap<String, com.makefunstuff.herdr.core.shepherdr.V2Chat>()
                withTimeout(30_000) {
                    while (got.size < keys.size) {
                        val e = link.next() ?: break
                        if (e is V2Event.Chat && e.chat.key in keys) got.putIfAbsent(e.chat.key, e.chat)
                    }
                }
                got
            }
            val problems = ArrayList<String>()
            for ((key, chat) in chats) {
                val place = snapshot.places.first { it.chat == key }
                val items = SpaceChat.items(place.id, chat)
                val thread = V2Board.thread(place.id, chat)
                val shown = items.filterIsInstance<SpaceChatItem.Words>().map { it.id }.toSet()
                val replies = chat.messages.filter { !it.you && it.card == null && it.question == null && it.artifact == null && it.ended == null && it.brief == null && ShepherdrText.clean(it.text.orEmpty()).isNotEmpty() }
                val missing = replies.filter { it.id !in shown }
                println("${place.name}: notice ${chat.notice}, ${chat.messages.size} messages, ${items.size} items, ${replies.size} replies, ${missing.size} missing, last: ${thread.lines.lastOrNull()?.text?.take(60)}")
                for (m in chat.messages.takeLast(6)) println("   ${m.who} ${m.id.take(24)} ${items.filter { it.id.startsWith(m.id) }.joinToString { it::class.simpleName.orEmpty() }}")
                missing.forEach { problems += "${place.name} ${it.id}" }
            }
            assertTrue(problems.isEmpty(), "replies not shown: $problems")
        }
    }

    /**
     * The relay as the app starts it over SSH, run here on Shepherdr's own machine (it reads the token there): every
     * Assistant chat comes through it whole, and no snapshot or view does.
     */
    @Test
    fun theRelayCarriesEveryChatAndNoSnapshot() {
        assumeTrue(port == 7788, "set HERDR_PROBE_SHEPHERDR_PORT=7788 to run")
        val client = client()
        val keys = runBlocking { client.snapshot() }.places.map { it.chat }.filter { it.isNotEmpty() }
        val proc = ProcessBuilder("sh", "-c", com.makefunstuff.herdr.core.shepherdr.LiveRelay.command(snapshots = false)).start()
        val duplex = object : com.makefunstuff.herdr.core.ssh.Duplex {
            override val input = proc.inputStream
            override val output = proc.outputStream
            override fun close() { proc.destroy() }
        }
        runBlocking {
            val link = com.makefunstuff.herdr.core.shepherdr.LiveRelay.open(duplex)
            link.send(com.makefunstuff.herdr.core.shepherdr.V2Actions.watch(keys).toString())
            val chats = HashMap<String, Int>()
            var snapshots = 0
            var bytes = 0L
            val end = System.currentTimeMillis() + 20_000
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                while (System.currentTimeMillis() < end && chats.size < keys.size) {
                    val text = link.receive() ?: break
                    bytes += text.length
                    when (val e = com.makefunstuff.herdr.core.shepherdr.V2Events.parse(text)) {
                        is V2Event.Chat -> chats[e.chat.key] = SpaceChat.items(e.chat.key, e.chat).size
                        is V2Event.Snapshot -> snapshots++
                        is V2Event.Other -> if (e.type == "view") snapshots++
                    }
                }
            }
            link.close()
            println("relay: ${chats.size}/${keys.size} chats, $snapshots snapshots, $bytes bytes: $chats")
            assertTrue(chats.keys == keys.toSet() && snapshots == 0, "chats $chats, snapshots $snapshots")
        }
    }

    /** Every event the live link sends for a while, Assistant chats watched, parses. */
    @Test
    fun everyLiveEventParses() {
        assumeTrue(port != null, "set HERDR_PROBE_SHEPHERDR_PORT to run")
        val seconds = System.getenv("HERDR_PROBE_SECONDS")?.toLongOrNull() ?: 20
        val socket = com.makefunstuff.herdr.core.shepherdr.Wire.webSocket(ForwardTransport.socket(port!!, 0), "/api/ws", token = token)
        val client = client()
        val keys = runBlocking { client.snapshot() }.places.map { it.chat }
        socket.send(com.makefunstuff.herdr.core.shepherdr.V2Actions.watch(keys).toString())
        val failures = ArrayList<String>()
        val types = HashMap<String, Int>()
        val end = System.currentTimeMillis() + seconds * 1000
        val reader = Thread {
            while (true) {
                val text = runCatching { socket.receive() }.getOrNull() ?: break
                val type = Regex(""""type":"([^"]+)"""").find(text)?.groupValues?.get(1) ?: "?"
                synchronized(types) { types.merge(type, 1, Int::plus) }
                // Strictly, as before the lenient reading: a shape Shepherdr changed shows here, not hidden by it.
                runCatching {
                    val o = com.makefunstuff.herdr.core.shepherdr.ShepherdrJson.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
                    val json = com.makefunstuff.herdr.core.shepherdr.ShepherdrJson
                    when (type) {
                        "chat" -> json.decodeFromJsonElement(com.makefunstuff.herdr.core.shepherdr.V2Chat.serializer(), o.getValue("chat"))
                        "snapshot" -> json.decodeFromJsonElement(com.makefunstuff.herdr.core.shepherdr.V2Snapshot.serializer(), o.getValue("snapshot"))
                        else -> Unit
                    }
                }.onFailure { e ->
                    synchronized(failures) { failures += "$type: ${e::class.simpleName}: ${e.message?.take(300)}" }
                }
            }
        }.apply { isDaemon = true; start() }
        while (System.currentTimeMillis() < end) Thread.sleep(500)
        socket.close()
        reader.join(2_000)
        println("events: $types")
        failures.forEach { println("FAILED $it") }
        assertTrue(failures.isEmpty(), "events that don't parse: $failures")
    }
}
