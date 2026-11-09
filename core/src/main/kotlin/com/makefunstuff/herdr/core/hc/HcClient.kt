package com.makefunstuff.herdr.core.hc

import com.makefunstuff.herdr.core.api.ApiCaller
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * herdr-core's API, typed: what the Agents, chat and Terminal screens need. Requests go to
 * [caller] (an [HcConnection]); [subscribe] and [attach] need one, as they open a stream channel.
 */
class HcClient(private val caller: ApiCaller) {
    private val connection: HcConnection
        get() = caller as? HcConnection ?: throw IOException("herdr-core streams need a framed connection")

    /** Live terminals here and on machines this core added; with [all], finished ones too. */
    suspend fun list(all: Boolean = false): HcList = caller.call("terminal.list", Requests.list(all)).decode()

    suspend fun get(terminal: String): HcTerminal = caller.call("terminal.get", Requests.terminal(terminal)).decode()

    /** The screen plus [lines] of history as text. */
    suspend fun read(terminal: String, lines: Int = 200): HcRead = caller.call("terminal.read", Requests.read(terminal, lines)).decode()

    /**
     * Pastes [text] and presses Enter once nobody has typed for ~3 s. A blocked program refuses it
     * (kind `blocked`) unless [force].
     */
    suspend fun send(terminal: String, text: String, force: Boolean = false): HcSent =
        caller.call("terminal.send", Requests.send(terminal, text, force), timeoutMs = 30_000).decode()

    /** Presses named keys (`enter`, `esc`, `up`, `ctrl+c`, `y`), encoded for the program's keyboard modes. */
    suspend fun keys(terminal: String, keys: List<String>): HcKeysSent =
        caller.call("terminal.keys", Requests.keys(terminal, keys), timeoutMs = 30_000).decode()

    /** The stop key (esc for a program that reports status, else ctrl+c), and what came of it. */
    suspend fun interrupt(terminal: String): HcInterrupted =
        caller.call("terminal.interrupt", Requests.terminal(terminal), timeoutMs = 30_000).decode()

    /**
     * Starts a new terminal (`hc run --bg`): [argv], or [line] through the machine's shell, else that machine's own
     * shell; in [cwd], with [labels] and [env] (its terminal identity, `TERM`), on another machine hc added when [on]. No parent: the phone isn't
     * a terminal, and it must outlive nothing. Returns it once it runs.
     */
    suspend fun run(
        line: String? = null, cwd: String? = null, labels: Map<String, String> = emptyMap(), name: String? = null, on: String? = null,
        env: Map<String, String> = emptyMap(), argv: List<String> = emptyList(),
    ): HcTerminal = caller.call("terminal.run", Requests.run(line, cwd, labels, name, on, env, argv), timeoutMs = 30_000).decode()

    /** Ends [terminal]'s program (`hc kill`: hangup, then terminate, then kill); returns what was seen. */
    suspend fun kill(terminal: String): HcTerminal =
        caller.call("terminal.kill", Requests.terminal(terminal), timeoutMs = 30_000).decode()

    /**
     * Subscribes to every change: [HcSubscription.snapshot] first, then [HcSubscription.events] in
     * order. Subscribe before reading anything else, so no change slips between them.
     */
    suspend fun subscribe(): HcSubscription {
        val conn = connection
        val result = conn.call("events.subscribe", JsonObject(emptyMap())).decode<HcSubscribed>()
        val channel = result.channel ?: throw IOException("herdr-core gave the subscription no channel")
        return HcSubscription(result, conn.stream(channel))
    }

    /**
     * Opens a raw stream to a live [terminal] at [cols]×[rows]: its first paint (the screen, [history]
     * lines, the latest status and title), then its output. With [observe] it never types or resizes.
     */
    suspend fun attach(terminal: String, cols: Int, rows: Int, history: Int = 0, observe: Boolean = false): HcAttach {
        val conn = connection
        val result = conn.call("terminal.attach", Requests.attach(terminal, cols, rows, history, observe)).decode<HcAttached>()
        return HcAttach(result.terminal, conn.stream(result.channel))
    }

    /** The request bodies, as hc's schema names them. */
    object Requests {
        fun list(all: Boolean): JsonObject = obj("all" to all.takeIf { it })
        fun terminal(terminal: String): JsonObject = obj("terminal" to terminal)
        fun read(terminal: String, lines: Int): JsonObject = obj("terminal" to terminal, "lines" to lines)
        fun send(terminal: String, text: String, force: Boolean): JsonObject =
            obj("terminal" to terminal, "text" to text, "force" to force.takeIf { it })
        fun keys(terminal: String, keys: List<String>): JsonObject =
            obj("terminal" to terminal, "keys" to JsonArray(keys.map { JsonPrimitive(it) }))
        fun run(
            line: String?, cwd: String?, labels: Map<String, String>, name: String?, on: String?,
            env: Map<String, String> = emptyMap(), argv: List<String> = emptyList(),
        ): JsonObject = obj(
            "argv" to argv.takeIf { it.isNotEmpty() }?.let { a -> JsonArray(a.map { JsonPrimitive(it) }) },
            "line" to line,
            "cwd" to cwd,
            "labels" to labels.takeIf { it.isNotEmpty() }?.let { l -> JsonObject(l.mapValues { JsonPrimitive(it.value) }) },
            "name" to name,
            "on" to on,
            "env" to env.takeIf { it.isNotEmpty() }?.let { e -> JsonObject(e.mapValues { JsonPrimitive(it.value) }) },
            "no_parent" to true,
        )
        fun attach(terminal: String, cols: Int, rows: Int, history: Int, observe: Boolean): JsonObject = obj(
            "terminal" to terminal,
            "cols" to cols,
            "rows" to rows,
            "history" to history.takeIf { it > 0 },
            "observe" to observe.takeIf { it },
        )

        private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
            for ((key, value) in pairs) {
                when (value) {
                    null -> {}
                    is JsonElement -> put(key, value)
                    is String -> put(key, JsonPrimitive(value))
                    is Number -> put(key, JsonPrimitive(value))
                    is Boolean -> put(key, JsonPrimitive(value))
                    else -> error("unsupported param type for $key")
                }
            }
        }
    }
}

/** The event subscription: its [snapshot], then each notification as it comes ([events], one collector). */
class HcSubscription internal constructor(val snapshot: HcSubscribed, private val stream: HcStream) {
    val events: Flow<HcEvent> = flow {
        val lines = LineSplitter()
        while (true) {
            val bytes = stream.read() ?: break
            for (line in lines.feed(bytes)) HcEvents.parse(line)?.let { emit(it) }
        }
    }

    fun close() = stream.close()
}

/** An attached terminal: [output] its bytes, [write] keys, [resize] the viewer; [close] detaches. */
class HcAttach internal constructor(val terminal: HcTerminal, private val stream: HcStream) {
    /** The next bytes the terminal wrote, or null once the stream is over (see [closeReason]). */
    suspend fun output(): ByteArray? = stream.read()

    suspend fun write(bytes: ByteArray) = stream.write(bytes)

    suspend fun resize(cols: Int, rows: Int) = stream.resize(cols, rows)

    /** Why the core ended the stream: `exited`, `killed`, a lost program; null when detached here. */
    suspend fun closeReason(): String? =
        (stream.closeReason.await()?.get("reason") as? JsonPrimitive)?.contentOrNull

    fun close() = stream.close()
}

object HcEvents {
    /** One notification line from the event channel; null for anything else. */
    fun parse(line: String): HcEvent? {
        val obj = runCatching { HcJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        val method = (obj["method"] as? JsonPrimitive)?.contentOrNull ?: return null
        val p = obj["params"] as? JsonObject ?: return null
        fun str(key: String) = (p[key] as? JsonPrimitive)?.contentOrNull
        return HcEvent(
            method = method,
            seq = (p["seq"] as? JsonPrimitive)?.longOrNull ?: 0,
            type = str("type"),
            terminal = str("terminal"),
            machine = str("machine"),
            data = p["data"],
            terminals = (p["terminals"] as? JsonArray)?.let { a -> runCatching { HcJson.decodeFromJsonElement<List<HcTerminal>>(a) }.getOrNull() },
        )
    }
}

/** Cuts a byte stream into lines; a line split across reads waits for its end. */
internal class LineSplitter {
    private val partial = ByteArrayOutputStream()

    fun feed(bytes: ByteArray): List<String> {
        val lines = mutableListOf<String>()
        var start = 0
        for (i in bytes.indices) {
            if (bytes[i] == '\n'.code.toByte()) {
                partial.write(bytes, start, i - start)
                lines += partial.toString(Charsets.UTF_8)
                partial.reset()
                start = i + 1
            }
        }
        partial.write(bytes, start, bytes.size - start)
        return lines.filter { it.isNotBlank() }
    }
}
