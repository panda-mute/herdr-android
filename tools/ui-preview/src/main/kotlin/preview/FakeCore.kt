// herdr-core (`hc`, the Herdr desktop app's runtime) on a fake machine: what HcDetection's probe finds,
// and `hc connect`'s framed JSON-RPC, answered from the scene's terminals, so the app's real HcConnection,
// HcClient and HcTerminalStream run against it.
package preview

import com.makefunstuff.herdr.core.hc.HcFrames
import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.ssh.RemoteProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * What one machine's herdr-core has: its terminals (hc's records, `terminal.list`'s), each one's
 * screen (ANSI) and the history above it (`terminal.read` gives both, the attach draws the screen).
 */
class FakeCore(
    var terminals: List<JsonObject>,
    var screens: Map<String, String> = emptyMap(),
    var histories: Map<String, String> = emptyMap(),
    val version: String = "0.1.14",
    val name: String = "devbox",
) {
    /** Keys and words the app sent, as (method, terminal, what): what a scene's answer pressed. */
    val sent = java.util.Collections.synchronizedList(ArrayList<Triple<String, String, String>>())

    /** The probe's answer (HcDetection.probe): the default socket, the core holding its lock, and its executable. */
    fun probe(): CommandResult = CommandResult(
        0,
        "socket\t$SOCKET\nlock\t4242\ncore\t$BIN\t4242\nbin\t$BIN\therdr-core $version\tcore\nbin\t$BIN\therdr-core $version\tpath\n",
        "",
    )

    /** `herdr-core connect`: hello as one line, then framed mode until the app closes it. */
    fun connect(): RemoteProcess {
        val toApp = Pipe()
        val fromApp = Pipe()
        val task = Threads.pool.submit {
            runCatching { serve(fromApp.input, toApp.output) }
            toApp.close()
        }
        return FakeMachines.track(RemoteProcess(
            stdin = fromApp.output,
            stdout = toApp.input,
            stderr = InputStream.nullInputStream(),
            onClose = {
                fromApp.close()
                toApp.close()
                task.cancel(true)
            },
        ))
    }

    private fun serve(input: InputStream, out: java.io.OutputStream) {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return
            if (b == '\n'.code) break
            line.write(b)
        }
        val hello = obj(
            "core" to obj("id" to "5f0c1e2d3a4b5c6d7e8f901234567890", "name" to name),
            "features" to arr("framed", "attach", "events", "status", "shell"),
            "framed" to true, "generation" to 1, "version" to version, "methods" to arr(),
        )
        out.write((obj("id" to 0, "jsonrpc" to "2.0", "result" to hello).toString() + "\n").toByteArray())
        out.flush()
        var nextChannel = 2
        fun send(channel: Int, kind: Int, payload: ByteArray = ByteArray(0)) = synchronized(out) {
            out.write(HcFrames.encode(channel, kind, payload))
            out.flush()
        }
        while (true) {
            val frame = HcFrames.read(input) ?: return
            if (frame.channel != 0) {
                // A viewer detaching: the core answers with an empty CLOSE.
                if (frame.kind == HcFrames.CLOSE) send(frame.channel, HcFrames.CLOSE)
                continue
            }
            if (frame.kind != HcFrames.DATA) continue
            val request = Json.parseToJsonElement(frame.payload.toString(Charsets.UTF_8)).jsonObject
            val id = request["id"] ?: continue
            val method = request["method"]?.jsonPrimitive?.content.orEmpty()
            val p = request["params"] as? JsonObject ?: obj()
            val address = p["terminal"]?.jsonPrimitive?.content
            val terminal = terminals.firstOrNull { it["address"]?.jsonPrimitive?.content == address }
            var attached: Int? = null
            val result: JsonElement? = when (method) {
                "events.subscribe" -> obj("channel" to 1, "seq" to 40, "terminals" to JsonArray(terminals))
                "terminal.list" -> obj("terminals" to JsonArray(terminals), "machines" to arr(), "hidden_finished" to 0)
                "terminal.get" -> terminal
                "terminal.read" -> terminal?.let { obj("text" to Shell.plain(histories[address].orEmpty() + screens[address].orEmpty()), "complete" to true, "how" to "history") }
                "terminal.attach" -> terminal?.let { attached = nextChannel++; obj("channel" to attached, "terminal" to it) }
                "terminal.keys" -> terminal?.let {
                    sent += Triple(method, address!!, p["keys"].toString())
                    obj("status" to it.status(), "turn" to 1)
                }
                "terminal.send" -> terminal?.let {
                    sent += Triple(method, address!!, p["text"]?.jsonPrimitive?.content.orEmpty())
                    obj("started" to true, "status" to it.status(), "turn" to 1)
                }
                else -> obj()
            }
            val reply = if (result != null) obj("id" to id, "jsonrpc" to "2.0", "result" to result)
            else obj("id" to id, "jsonrpc" to "2.0", "error" to obj("code" to -32000, "message" to "no terminal \"$address\"", "data" to obj("kind" to "not_found")))
            send(0, HcFrames.DATA, reply.toString().toByteArray())
            // The attached terminal's first paint: its screen, as the program drew it.
            attached?.let { ch ->
                val screen = "\u001b[H\u001b[2J" + screens[address].orEmpty().replace("\n", "\r\n")
                send(ch, HcFrames.DATA, screen.toByteArray())
            }
        }
    }

    private fun JsonObject.status(): String? = (this["status"] as? JsonObject)?.get("state")?.jsonPrimitive?.content

    companion object {
        const val SOCKET = "/run/user/1000/herdr-core/default.sock"

        /** The probe's answer for a machine running [default] (or not) and [others], other sessions by name. */
        fun probe(default: FakeCore?, others: Map<String, FakeCore>): CommandResult {
            if (others.isEmpty()) return default?.probe() ?: CommandResult(0, "", "")
            val version = (default ?: others.values.first()).version
            val lines = buildString {
                if (default != null) append("socket\t$SOCKET\nlock\t4242\ncore\t$BIN\t4242\n")
                others.keys.forEachIndexed { i, name -> append("session\t$name\t/run/user/1000/herdr-core/$name.sock\t${4300 + i}\t$BIN\n") }
                append("bin\t$BIN\therdr-core $version\tcore\nbin\t$BIN\therdr-core $version\tpath\n")
            }
            return CommandResult(0, lines, "")
        }
        const val BIN = "/home/demo/.local/bin/herdr-core"

        /** A terminal as hc lists it: in the desktop app's [space] and [tab] (null: one `hc run` started), with its 7501 [status]. */
        fun terminal(
            address: String,
            argv: List<String>,
            cwd: String,
            created: Long,
            space: Pair<String, String>? = null,
            tab: Pair<String, String>? = null,
            name: String? = null,
            status: JsonObject? = null,
            statusMs: Long? = null,
            title: String = "",
            turn: Int = 0,
            foreground: String = argv.first(),
            /** Its labels as they are, in place of the desktop app's (Shepherdr's own). */
            labels: Map<String, String>? = null,
            parent: String? = null,
            /** Its program's process: each terminal its own, as the app finds an agent's transcript from it. */
            pid: Int = 4300 + (address.drop(1).toIntOrNull() ?: 0),
        ): JsonObject = obj(
            "address" to address, "argv" to JsonArray(argv.map(::JsonPrimitive)), "cols" to 120, "rows" to 40,
            "created_ms" to created, "cwd" to cwd, "exit_code" to null,
            "foreground" to obj("argv" to arr(foreground), "cwd" to cwd, "name" to foreground, "pid" to pid),
            "labels" to if (labels != null) JsonObject(labels.mapValues { JsonPrimitive(it.value) }) else if (space != null && tab != null) obj(
                "herdr.space" to space.first, "herdr.tab" to tab.first, "where" to "${space.second} / ${tab.second}",
            ) else obj(),
            "name" to name, "parent" to parent, "pid" to pid, "reported" to (status != null), "state" to "live",
            "status" to status, "status_ms" to statusMs, "title" to title, "turn" to turn,
        )
    }
}
