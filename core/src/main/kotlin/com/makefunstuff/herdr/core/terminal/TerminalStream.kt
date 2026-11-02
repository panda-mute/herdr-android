package com.makefunstuff.herdr.core.terminal

import com.makefunstuff.herdr.core.api.HerdrJson
import com.makefunstuff.herdr.core.ssh.HerdrCommands
import com.makefunstuff.herdr.core.ssh.Machine
import com.makefunstuff.herdr.core.ssh.MachineConnection
import com.makefunstuff.herdr.core.ssh.RemoteProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.util.Base64

/** A decoded `terminal.frame` record. */
data class TerminalFrame(val seq: Long, val width: Int, val height: Int, val full: Boolean, val bytes: ByteArray)

object FrameCodec {
    /** Parses one line of `herdr terminal session control` output. Returns null for other records. */
    fun parse(line: String): TerminalFrame? {
        val obj = runCatching { HerdrJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "terminal.frame") return null
        val data = obj["bytes"]?.jsonPrimitive?.contentOrNull ?: return null
        return TerminalFrame(
            seq = obj["seq"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0,
            width = obj["width"]?.jsonPrimitive?.intOrNull ?: 0,
            height = obj["height"]?.jsonPrimitive?.intOrNull ?: 0,
            full = obj["full"]?.jsonPrimitive?.booleanOrNull ?: false,
            bytes = Base64.getDecoder().decode(data),
        )
    }

    fun isClosed(line: String): String? {
        val obj = runCatching { HerdrJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "terminal.closed") return null
        return obj["reason"]?.jsonPrimitive?.contentOrNull ?: "closed"
    }

    fun input(bytes: ByteArray): String = obj(
        "type" to JsonPrimitive("terminal.input"),
        "bytes" to JsonPrimitive(Base64.getEncoder().encodeToString(bytes)),
    )

    fun resize(cols: Int, rows: Int): String = obj(
        "type" to JsonPrimitive("terminal.resize"),
        "cols" to JsonPrimitive(cols),
        "rows" to JsonPrimitive(rows),
    )

    fun scroll(up: Boolean, lines: Int): String = obj(
        "type" to JsonPrimitive("terminal.scroll"),
        "direction" to JsonPrimitive(if (up) "up" else "down"),
        "lines" to JsonPrimitive(lines),
    )

    fun release(): String = obj("type" to JsonPrimitive("terminal.release"))

    private fun obj(vararg pairs: Pair<String, JsonPrimitive>): String =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }.toString()
}

/**
 * A live, writable view of one pane. Frames are applied to [emulator]; [revision] ticks after each one.
 *
 * With [typeInto] null it runs `herdr terminal session control --takeover`: the pane takes the
 * phone's size (and keeps it while attached), and input, resizes and scrolls go down the stream.
 * With [typeInto] set it only observes (`herdr terminal session observe`): the pane keeps the
 * computer's size, the desktop window is left alone, and typed bytes go to [typeInto] (the API's
 * `pane.send_text`); resizes and scrolls are ignored.
 */
class TerminalStream(
    private val connection: MachineConnection,
    private val machine: Machine,
    private val target: String,
    cols: Int,
    rows: Int,
    private val scope: CoroutineScope,
    private val typeInto: (suspend (ByteArray) -> Unit)? = null,
) : LiveTerminal {
    /** True when the stream only watches the pane: see [typeInto]. */
    val observing: Boolean get() = typeInto != null

    sealed interface Status {
        data object Opening : Status
        data object Live : Status
        data class Closed(val reason: String, val isRemoteExit: Boolean = false) : Status
    }

    override val emulator = TerminalEmulator(cols, rows)
    private val _revision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = _revision.asStateFlow()
    private val _status = MutableStateFlow<Status>(Status.Opening)
    override val status: StateFlow<Status> = _status.asStateFlow()

    private var process: RemoteProcess? = null
    private var job: Job? = null
    private val outbound = Channel<String>(Channel.UNLIMITED)
    private val typed = Channel<ByteArray>(Channel.UNLIMITED)
    private var writerJob: Job? = null
    private var requestedCols = cols
    private var requestedRows = rows

    override fun open() {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            try {
                val p = connection.start(
                    if (observing) HerdrCommands.terminalObserve(machine, target, requestedCols, requestedRows)
                    else HerdrCommands.terminalControl(machine, target, requestedCols, requestedRows),
                )
                process = p
                writerJob = if (observing) scope.launch(Dispatchers.IO) {
                    // Typed bytes, in order, through the API: the observer's stream takes no input.
                    val type = typeInto!!
                    try {
                        for (bytes in typed) runCatching { type(bytes) }
                    } catch (_: Throwable) {
                    }
                } else scope.launch(Dispatchers.IO) {
                    try {
                        for (msg in outbound) {
                            p.stdin.write((msg + "\n").toByteArray(Charsets.UTF_8))
                            while (true) {
                                val next = outbound.tryReceive().getOrNull() ?: break
                                p.stdin.write((next + "\n").toByteArray(Charsets.UTF_8))
                            }
                            p.stdin.flush()
                        }
                    } catch (_: Throwable) {
                    }
                }
                p.stdout.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        val frame = FrameCodec.parse(line)
                        if (frame != null) {
                            synchronized(emulator.lock) {
                                if (frame.width > 0 && frame.height > 0) emulator.resize(frame.width, frame.height)
                                emulator.feed(frame.bytes)
                            }
                            if (_status.value != Status.Live) _status.value = Status.Live
                            _revision.update { it + 1 }
                            continue
                        }
                        FrameCodec.isClosed(line)?.let { reason ->
                            _status.value = Status.Closed(reason, isRemoteExit = true)
                            return@useLines
                        }
                    }
                }
                if (_status.value !is Status.Closed) {
                    val err = runCatching { p.stderr.readBytes().toString(Charsets.UTF_8).trim() }.getOrDefault("")
                    _status.value = Status.Closed(err.lines().lastOrNull { it.isNotBlank() } ?: "The terminal stream ended", isRemoteExit = false)
                }
            } catch (t: Throwable) {
                _status.value = Status.Closed(t.message ?: "The terminal stream failed", isRemoteExit = false)
            } finally {
                writerJob?.cancel()
                writerJob = null
            }
        }
    }

    override fun send(bytes: ByteArray) {
        if (observing) typed.trySend(bytes) else write(FrameCodec.input(bytes))
    }

    override fun send(text: String) = send(text.toByteArray(Charsets.UTF_8))

    override fun resize(cols: Int, rows: Int) {
        if (observing) return
        if (cols == requestedCols && rows == requestedRows) return
        requestedCols = cols
        requestedRows = rows
        write(FrameCodec.resize(cols, rows))
    }

    override fun scroll(up: Boolean, lines: Int) {
        if (observing) return
        write(FrameCodec.scroll(up, lines))
    }

    private fun write(line: String) {
        outbound.trySend(line)
    }

    override fun close() {
        writerJob?.cancel()
        writerJob = null
        outbound.close()
        typed.close()
        val p = process
        process = null
        job?.cancel()
        job = null
        if (p != null) {
            scope.launch(Dispatchers.IO) {
                if (!observing) runCatching {
                    p.stdin.write((FrameCodec.release() + "\n").toByteArray(Charsets.UTF_8))
                    p.stdin.flush()
                }
                withContext(Dispatchers.IO) { p.close() }
            }
        }
    }
}

/** Byte sequences for keys a phone keyboard doesn't have. */
object Keys {
    const val ESC = "\u001b"
    val ENTER = "\r"
    val TAB = "\t"
    val BACKSPACE = "\u007f"
    val UP = "\u001b[A"
    val DOWN = "\u001b[B"
    val RIGHT = "\u001b[C"
    val LEFT = "\u001b[D"
    val HOME = "\u001b[H"
    val END = "\u001b[F"
    val PAGE_UP = "\u001b[5~"
    val PAGE_DOWN = "\u001b[6~"
    val SHIFT_TAB = "\u001b[Z"

    /** Applies sticky Ctrl/Alt modifiers to typed text. */
    fun withModifiers(text: String, ctrl: Boolean, alt: Boolean): String {
        var out = text
        if (ctrl && out.length == 1) {
            val c = out[0]
            out = when (c) {
                in 'a'..'z' -> (c.code - 'a'.code + 1).toChar().toString()
                in 'A'..'Z' -> (c.code - 'A'.code + 1).toChar().toString()
                ' ', '@', '2' -> "\u0000"
                '[', '3' -> ESC
                '\\', '4' -> "\u001c"
                ']', '5' -> "\u001d"
                '^', '6' -> "\u001e"
                '_', '7', '-' -> "\u001f"
                '?', '8' -> "\u007f"
                else -> out
            }
        }
        if (alt) out = ESC + out
        return out
    }
}
