package com.makefunstuff.herdr.core.shepherdr

import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.ssh.Duplex
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import com.makefunstuff.herdr.core.ssh.PortForward
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** How the app reaches Shepherdr's API on a machine: always through its SSH connection, never an open port. */
interface ShepherdrTransport : Closeable {
    /** Whether it can hold the live link ([webSocket]); curl over exec can't. */
    val canStream: Boolean

    suspend fun http(method: String, path: String, body: String? = null, timeoutMs: Int = 30_000): HttpReply

    /** A request whose body ([bytes] of [type]; null: none) or answer is a file, not JSON: an upload, a download. */
    suspend fun bytes(method: String, path: String, bytes: ByteArray? = null, type: String? = null, timeoutMs: Int = 120_000): BytesReply =
        throw IOException("No way to send files here")

    /** Opens the live link (or a voice call) at [path]; fails where [canStream] is false. */
    suspend fun webSocket(path: String): WebSocketLink

    /** The live link (`/api/ws`) as best it can be had; [snapshots] false: one that needn't carry them (a chat's). */
    suspend fun live(snapshots: Boolean): LiveSocket = webSocket("/api/ws")

    /**
     * A task's browser, live (`/api/browser?…` at [path], [TaskBrowser]): its frames, which pile up behind a slow link,
     * best through a relay on the machine that passes on only the newest.
     */
    suspend fun browser(path: String): LiveSocket = webSocket(path)

    /** Where there is no live link ([canStream] false): chat [key] as a `chat` event's JSON, read some other way. */
    suspend fun readChat(key: String, timeoutMs: Long): String = throw IOException("No way to read a chat here")

    /** Where there is no live link: the snapshot event's JSON, read some other way. */
    suspend fun readSnapshot(timeoutMs: Long): String = throw IOException("No way to read the snapshot here")
}

/**
 * Through an SSH port forward (`ssh -L`) to the machine's 127.0.0.1:7788: each request is a fresh
 * loopback socket to the forward's port, with the service's [token] (its file on the machine, read over SSH). The live
 * link is [LiveRelay]'s, started with [relay] (an SSH command on the machine, which reads the token there), and where
 * nothing there can run it a socket to the forward too. Closing it closes the forward.
 */
class ForwardTransport(
    private val connect: (timeoutMs: Int) -> Duplex,
    private val onClose: () -> Unit = {},
    private val relay: (suspend (command: String) -> Duplex)? = null,
    private val token: String? = null,
) : ShepherdrTransport {
    /** False once the machine couldn't run the relay: it won't later on this connection. */
    @Volatile private var relays = relay != null
    @Volatile private var late = 0

    override val canStream: Boolean get() = true

    override suspend fun http(method: String, path: String, body: String?, timeoutMs: Int): HttpReply = coroutineScope {
        val stream = withContext(Dispatchers.IO) { connect(timeoutMs) }
        // Its failure comes back as a result: once cancelled, the closed socket's error is no news.
        val answer = async(Dispatchers.IO) { runCatching { Wire.exchange(stream, method, path, body, token) } }
        try {
            answer.await().getOrThrow()
        } catch (e: CancellationException) {
            // A socket's read can't be interrupted: closing it ends the wait (a merge waiting on checks).
            runCatching { stream.close() }
            throw e
        }
    }

    override suspend fun bytes(method: String, path: String, bytes: ByteArray?, type: String?, timeoutMs: Int): BytesReply = coroutineScope {
        val stream = withContext(Dispatchers.IO) { connect(timeoutMs) }
        val answer = async(Dispatchers.IO) { runCatching { Wire.exchange(stream, method, path, bytes, type, token) } }
        try {
            answer.await().getOrThrow()
        } catch (e: CancellationException) {
            runCatching { stream.close() }
            throw e
        }
    }

    override suspend fun webSocket(path: String): WebSocketLink = withContext(Dispatchers.IO) {
        Wire.webSocket(connect(0), path, token = token)
    }

    override suspend fun live(snapshots: Boolean): LiveSocket {
        val start = relay
        if (start != null && relays) {
            try {
                return LiveRelay.open(start(LiveRelay.command(snapshots)))
            } catch (e: LiveRelay.NoRelay) {
                relays = false
            } catch (e: LiveRelay.Slow) {
                // Late once can be a slow link; late again, a login shell that hangs: not waited for any more.
                if (++late >= 2) relays = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No command channel this time (the server's session limit, a dropped link): Shepherdr's own link, this once.
            }
        }
        return webSocket("/api/ws")
    }

    override suspend fun browser(path: String): LiveSocket {
        val start = relay
        if (start != null && relays) {
            try {
                return LiveRelay.open(start(TaskBrowser.relayCommand(path)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No relay this time: Shepherdr's own socket, every frame it sends.
            }
        }
        return webSocket(path)
    }

    override fun close() = onClose()

    companion object {
        /** Over [forward], which this transport then owns. */
        fun over(forward: PortForward, token: String?, relay: (suspend (String) -> Duplex)? = null): ForwardTransport =
            ForwardTransport({ timeoutMs -> socket(forward.localPort, timeoutMs) }, forward::close, relay, token)

        /** A loopback socket to [port]; [timeoutMs] bounds every read (0: none, for the live link). */
        fun socket(port: Int, timeoutMs: Int): Duplex {
            val s = Socket()
            try {
                s.connect(InetSocketAddress("127.0.0.1", port), 10_000)
                s.soTimeout = timeoutMs
                s.tcpNoDelay = true
            } catch (t: Throwable) {
                runCatching { s.close() }
                throw t
            }
            return object : Duplex {
                override val input: InputStream = s.getInputStream()
                override val output: OutputStream = s.getOutputStream()
                override fun close() = s.close()
            }
        }
    }
}

/**
 * The fallback where the SSH server forbids forwarding: `curl` on the machine, one process a request, with the token
 * read there ([AUTH]). No live link: the screens read the snapshot again every few seconds, through `bun` there.
 */
class CurlTransport(
    /** Starts a command on the machine that stays, both ways: a task's browser through a relay there. */
    private val start: (suspend (command: String) -> Duplex)? = null,
    /** Runs a command on the machine with [stdin] as its input. */
    private val run: suspend (command: String, stdin: String, timeoutSeconds: Long) -> CommandResult,
) : ShepherdrTransport {
    override val canStream: Boolean get() = false

    override suspend fun http(method: String, path: String, body: String?, timeoutMs: Int): HttpReply {
        val seconds = if (timeoutMs <= 0) 600 else (timeoutMs + 999) / 1000
        val r = run(command(method, path, body != null, seconds), body.orEmpty(), seconds + 5L)
        if (r.exitStatus == 127) throw IOException("curl isn't installed on the machine, and its SSH server doesn't forward ports")
        return parse(r)
    }

    /** Both ways as base64 through the command's text: [bytes] decoded on the machine into curl, its answer encoded back. */
    override suspend fun bytes(method: String, path: String, bytes: ByteArray?, type: String?, timeoutMs: Int): BytesReply {
        val seconds = if (timeoutMs <= 0) 600 else (timeoutMs + 999) / 1000
        val stdin = bytes?.let { java.util.Base64.getEncoder().encodeToString(it) }.orEmpty()
        val r = run(bytesCommand(method, path, bytes != null, type, seconds), stdin, seconds + 5L)
        if (r.exitStatus == 127) throw IOException("curl isn't installed on the machine, and its SSH server doesn't forward ports")
        val reply = parse(r)
        return BytesReply(reply.status, java.util.Base64.getMimeDecoder().decode(reply.body.trim()))
    }

    override suspend fun webSocket(path: String): WebSocketLink =
        throw IOException("The machine's SSH server doesn't forward ports: no live link, so the app reads again every few seconds")

    /** Only through a relay on the machine, which `bun` (or `node`) there runs, as [readChat]. */
    override suspend fun browser(path: String): LiveSocket {
        val begin = start ?: throw IOException("The machine's SSH server doesn't forward ports, so its browser can't be watched from here")
        return LiveRelay.open(begin(TaskBrowser.relayCommand(path)))
    }

    /** Shepherdr 3 sends its snapshot only over its live link: one read there by the machine's `bun`, as [readChat]. */
    override suspend fun readSnapshot(timeoutMs: Long): String {
        val seconds = ((timeoutMs + 999) / 1000).toInt().coerceAtLeast(1)
        val r = run(snapshotCommand(seconds), "", seconds + 10L)
        val line = r.stdout.lines().lastOrNull { it.startsWith(CHAT_MARK) }
            ?: throw IOException(r.stderr.trim().lines().lastOrNull { it.isNotBlank() } ?: "Shepherdr sent no snapshot")
        return line.removePrefix(CHAT_MARK)
    }

    /**
     * Shepherdr sends chats only over its live link, which curl can't hold: the machine's `bun` (which
     * the `shepherdr` command itself runs on) opens one there, prints the chat once and quits.
     */
    override suspend fun readChat(key: String, timeoutMs: Long): String {
        val seconds = ((timeoutMs + 999) / 1000).toInt().coerceAtLeast(1)
        val r = run(chatCommand(key, seconds), "", seconds + 10L)
        val line = r.stdout.lines().lastOrNull { it.startsWith(CHAT_MARK) }
            ?: throw IOException(r.stderr.trim().lines().lastOrNull { it.isNotBlank() } ?: "Shepherdr sent no chat $key")
        return line.removePrefix(CHAT_MARK)
    }

    override fun close() {}

    companion object {
        private const val MARK = "@@shepherdr-status@@"
        private const val CHAT_MARK = "@@shepherdr-chat@@"

        /**
         * A few lines of JavaScript for `bun` (or `node`, which has WebSocket too) through a login shell,
         * where PATH finds them: watch [key], print its first chat event after [CHAT_MARK], quit.
         */
        fun chatCommand(key: String, seconds: Int): String {
            val k = kotlinx.serialization.json.JsonPrimitive(key).toString()
            return once(
                "const k=$k;const ws=${LiveRelay.socket("ws://${Wire.HOST}/api/ws")};" +
                    "setTimeout(()=>{console.error('Shepherdr sent no chat '+k);process.exit(3)},${seconds * 1000});" +
                    "ws.onopen=()=>ws.send(JSON.stringify({type:'watch',chats:[k],term:null,visible:false,push:null}));" +
                    "ws.onmessage=(e)=>{const m=JSON.parse(String(e.data));" +
                    "if(m.type==='chat'&&m.chat&&m.chat.key===k)process.stdout.write('$CHAT_MARK'+JSON.stringify(m)+'\\n',()=>process.exit(0))};" +
                    "ws.onerror=()=>{console.error('Shepherdr refused the live link');process.exit(4)}",
            )
        }

        /** [chatCommand] for the snapshot: the first one the live link sends, printed once. */
        fun snapshotCommand(seconds: Int): String = once(
            "const ws=${LiveRelay.socket("ws://${Wire.HOST}/api/ws")};" +
                "setTimeout(()=>{console.error('Shepherdr sent no snapshot');process.exit(3)},${seconds * 1000});" +
                "ws.onmessage=(e)=>{const s=String(e.data);if(s.startsWith('{\"type\":\"snapshot\"'))process.stdout.write('$CHAT_MARK'+s+'\\n',()=>process.exit(0))};" +
                "ws.onerror=()=>{console.error('Shepherdr refused the live link');process.exit(4)}",
        )

        /** [js] by `bun` (else `node`) through a login shell, where PATH finds them. */
        private fun once(js: String): String {
            val run = "if command -v bun >/dev/null 2>&1; then exec bun -e ${quote(js)}; else exec node -e ${quote(js)}; fi"
            return "sh -c ${quote("exec \"\${SHELL:-/bin/sh}\" -lc \"\$1\" </dev/null")} sh ${quote(run)}"
        }

        /**
         * The service's token header for `curl`, read on the machine from its file (`~/.herdr/shepherdr/token`, or
         * under `SHEPHERDR_DATA`): it never crosses the command line the app sends.
         */
        const val AUTH = "Authorization: Bearer \$(cat \"\${SHEPHERDR_DATA:-\$HOME/.herdr/shepherdr}/token\" 2>/dev/null)"

        /** `curl` with the body on its stdin and the status after a mark at the end of its output. */
        fun command(method: String, path: String, hasBody: Boolean, seconds: Int): String {
            val curl = buildList {
                add("curl"); add("-sS"); add("--max-time"); add(seconds.toString())
                add("-X"); add(method)
                if (hasBody) { add("-H"); add("Content-Type: application/json"); add("--data-binary"); add("@-") }
                add("-w"); add("\n$MARK%{http_code}")
                add("http://${Wire.HOST}$path")
            }.joinToString(" ") { quote(it) }
            return "sh -c ${quote("exec $curl -H \"$AUTH\"")}"
        }

        /**
         * [command] for a binary body (base64 on stdin, decoded on the machine) and answer (saved by
         * curl, then printed as base64 before the status mark), in `sh` whatever the login shell is.
         */
        fun bytesCommand(method: String, path: String, hasBody: Boolean, type: String?, seconds: Int): String {
            val curl = buildList {
                add("curl"); add("-sS"); add("--max-time"); add(seconds.toString())
                add("-X"); add(method)
                if (hasBody) { add("-H"); add("Content-Type: ${type ?: "application/octet-stream"}"); add("--data-binary"); add("@-") }
                add("-o"); add("\$t"); add("-w"); add("%{http_code}")
                add("http://${Wire.HOST}$path")
            }.joinToString(" ") { if (it == "\$t") "\"\$t\"" else quote(it) } + " -H \"$AUTH\""
            val script = "t=\$(mktemp) || exit 1; trap 'rm -f \"\$t\"' EXIT; " +
                "code=\$(" + (if (hasBody) "base64 -d | " else "") + curl + "); s=\$?; " +
                "base64 < \"\$t\"; printf '\\n$MARK%s' \"\$code\"; exit \$s"
            return "sh -c ${quote(script)}"
        }

        fun parse(r: CommandResult): HttpReply {
            val at = r.stdout.lastIndexOf("\n$MARK")
            if (at < 0) throw IOException(r.stderr.trim().lines().lastOrNull { it.isNotBlank() } ?: "curl gave no answer")
            val status = r.stdout.substring(at + MARK.length + 1).trim().toIntOrNull() ?: 0
            if (status == 0) throw IOException(r.stderr.trim().lines().lastOrNull { it.isNotBlank() } ?: "Shepherdr didn't answer")
            return HttpReply(status, r.stdout.substring(0, at))
        }

        fun stdin(text: String): InputStream = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
    }
}
