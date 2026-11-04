package com.makefunstuff.herdr.core.shepherdr

import com.makefunstuff.herdr.core.ssh.Duplex
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** An HTTP answer: its status and its body as text. */
data class HttpReply(val status: Int, val body: String)

/** An HTTP answer with its body as bytes (a file). */
class BytesReply(val status: Int, val body: ByteArray)

/**
 * Just enough HTTP/1.1 and WebSocket (RFC 6455) for Shepherdr's loopback API, over any byte stream
 * (a socket to the SSH port forward). Android has no `java.net.http`, and core has no HTTP library:
 * these few requests don't need one. A request is sent without `Origin` and with the service's token, as a program
 * on its machine sends it, which is what its gate lets through on 127.0.0.1 (`service/auth.ts`).
 */
object Wire {
    /** The `Host` the service sees: the address it listens on, as the forward reaches it. */
    const val HOST = "127.0.0.1:${BoardDetection.PORT}"

    fun request(method: String, path: String, body: String?, token: String? = null): ByteArray =
        request(method, path, body?.toByteArray(Charsets.UTF_8), "application/json", token)

    /** A request with [bytes] of [type] as its body (none: no body), with the service's [token] where there is one. */
    fun request(method: String, path: String, bytes: ByteArray?, type: String?, token: String? = null): ByteArray {
        val head = buildString {
            append("$method $path HTTP/1.1\r\n")
            append("Host: $HOST\r\n")
            token?.let { append("Authorization: Bearer $it\r\n") }
            append("Accept: application/json\r\n")
            append("Connection: close\r\n")
            if (bytes != null) {
                append("Content-Type: ${type ?: "application/octet-stream"}\r\n")
                append("Content-Length: ${bytes.size}\r\n")
            }
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        return if (bytes == null) head else head + bytes
    }

    /** One request on a fresh stream, closed after the answer. */
    fun exchange(stream: Duplex, method: String, path: String, body: String?, token: String? = null): HttpReply {
        val r = exchange(stream, method, path, body?.toByteArray(Charsets.UTF_8), "application/json", token)
        return HttpReply(r.status, r.body.toString(Charsets.UTF_8))
    }

    /** One request with a binary body (or none), its answer as bytes; the stream is closed after it. */
    fun exchange(stream: Duplex, method: String, path: String, bytes: ByteArray?, type: String?, token: String? = null): BytesReply = stream.use { s ->
        s.output.write(request(method, path, bytes, type, token))
        s.output.flush()
        val input = BufferedInputStream(s.input)
        val (status, headers) = readHead(input)
        BytesReply(status, readBody(input, headers))
    }

    /**
     * A `multipart/form-data` body: [fields] as text, then one file, [name] its file name. The
     * second is its `Content-Type`, with the boundary.
     */
    fun form(fields: Map<String, String>, fileField: String, name: String, type: String, data: ByteArray, boundary: String = "herdr" + java.util.UUID.randomUUID().toString().replace("-", "")): Pair<ByteArray, String> {
        val out = ByteArrayOutputStream()
        fun text(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        fields.forEach { (k, v) -> text("--$boundary\r\nContent-Disposition: form-data; name=\"${quoted(k)}\"\r\n\r\n$v\r\n") }
        text("--$boundary\r\nContent-Disposition: form-data; name=\"${quoted(fileField)}\"; filename=\"${quoted(name)}\"\r\nContent-Type: $type\r\n\r\n")
        out.write(data)
        text("\r\n--$boundary--\r\n")
        return out.toByteArray() to "multipart/form-data; boundary=$boundary"
    }

    private fun quoted(s: String) = s.replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_")

    /** The status line and the headers (names lowercased). */
    fun readHead(input: InputStream): Pair<Int, Map<String, String>> {
        val status = readLine(input) ?: throw EOFException("Shepherdr closed the connection without an answer")
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Shepherdr gave no HTTP answer: $status")
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val at = line.indexOf(':')
            if (at > 0) headers[line.substring(0, at).trim().lowercase()] = line.substring(at + 1).trim()
        }
        return code to headers
    }

    fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            val out = ByteArrayOutputStream()
            while (true) {
                val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: throw EOFException("cut-off chunk")
                if (size == 0) break
                out.write(readExactly(input, size))
                readLine(input)
            }
            return out.toByteArray()
        }
        val length = headers["content-length"]?.toIntOrNull()
        return if (length != null) readExactly(input, length) else input.readBytes()
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) break
            out.write(b)
        }
        val s = out.toString(Charsets.UTF_8.name())
        return if (s.endsWith('\r')) s.dropLast(1) else s
    }

    internal fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException("Shepherdr's answer was cut off")
            off += r
        }
        return buf
    }

    /** What the server must answer to [key] (`Sec-WebSocket-Accept`). */
    fun acceptFor(key: String): String {
        val sha = MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(sha)
    }

    /** Opens a WebSocket on [stream] at [path]; the stream belongs to it from here on. */
    fun webSocket(stream: Duplex, path: String, random: SecureRandom = SecureRandom(), token: String? = null): WebSocketLink {
        try {
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
            val req = "GET $path HTTP/1.1\r\nHost: $HOST\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                (token?.let { "Authorization: Bearer $it\r\n" } ?: "") +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
            stream.output.write(req.toByteArray(Charsets.US_ASCII))
            stream.output.flush()
            val input = BufferedInputStream(stream.input)
            val (status, headers) = readHead(input)
            if (status != 101) throw IOException("Shepherdr refused the live link ($status)")
            if (headers["sec-websocket-accept"] != acceptFor(key)) throw IOException("Shepherdr's live link answered the wrong key")
            return WebSocketLink(input, stream.output, stream, random)
        } catch (t: Throwable) {
            runCatching { stream.close() }
            throw t
        }
    }
}

/**
 * A client's WebSocket: text messages both ways, and binary ones for a voice call. [receive] blocks until a whole
 * text message is in (pings are answered on the way) and gives null once the server closed it.
 */
class WebSocketLink internal constructor(
    private val input: InputStream,
    private val output: OutputStream,
    private val stream: Closeable,
    private val random: SecureRandom = SecureRandom(),
) : LiveSocket {
    @Volatile var closed = false
        private set

    /** The next whole text message; binary ones are skipped. */
    override fun receive(): String? {
        while (true) {
            when (val m = receiveAny() ?: return null) {
                is String -> return m
                else -> continue
            }
        }
    }

    /** The next whole message: a [String] (text) or a [ByteArray] (binary); null once closed. */
    fun receiveAny(): Any? {
        val message = ByteArrayOutputStream()
        var kind = 0
        while (true) {
            val b0 = input.read()
            if (b0 < 0) return end()
            val b1 = input.read()
            if (b1 < 0) return end()
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0F
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = Wire.readExactly(input, 2).let { (it[0].toLong() and 0xFF shl 8) or (it[1].toLong() and 0xFF) }
            else if (len == 127L) len = Wire.readExactly(input, 8).fold(0L) { acc, x -> (acc shl 8) or (x.toLong() and 0xFF) }
            if (len > MAX_MESSAGE) throw IOException("Shepherdr sent a message too large to take ($len bytes)")
            val mask = if (b1 and 0x80 != 0) Wire.readExactly(input, 4) else null
            val payload = Wire.readExactly(input, len.toInt())
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (opcode) {
                0x8 -> return end()
                0x9 -> frame(0xA, payload)
                0xA -> {}
                0x0, 0x1, 0x2 -> {
                    if (opcode != 0x0) kind = opcode
                    message.write(payload)
                    if (message.size() > MAX_MESSAGE) throw IOException("Shepherdr sent a message too large to take")
                    if (fin) return if (kind == 0x2) message.toByteArray() else message.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    override fun send(text: String) = frame(0x1, text.toByteArray(Charsets.UTF_8))

    /** A binary message (a voice call's audio). */
    fun send(bytes: ByteArray) = frame(0x2, bytes)

    @Synchronized
    private fun frame(opcode: Int, payload: ByteArray) {
        if (closed) throw IOException("The live link is closed")
        output.write(encode(opcode, payload, ByteArray(4).also(random::nextBytes)))
        output.flush()
    }

    private fun end(): String? {
        close()
        return null
    }

    override fun close() {
        if (closed) return
        runCatching { frame(0x8, ByteArray(0)) }
        closed = true
        runCatching { stream.close() }
    }

    companion object {
        /** A snapshot of many tasks with their briefs is a few hundred KB; this leaves room. */
        const val MAX_MESSAGE = 32L * 1024 * 1024

        /** A client's frame: final, masked with [mask]. */
        fun encode(opcode: Int, payload: ByteArray, mask: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(payload.size + 14)
            out.write(0x80 or opcode)
            when {
                payload.size < 126 -> out.write(0x80 or payload.size)
                payload.size <= 0xFFFF -> { out.write(0x80 or 126); out.write(payload.size shr 8); out.write(payload.size and 0xFF) }
                else -> { out.write(0x80 or 127); for (i in 7 downTo 0) out.write(((payload.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
            }
            out.write(mask)
            for (i in payload.indices) out.write(payload[i].toInt() xor mask[i % 4].toInt())
            return out.toByteArray()
        }
    }
}
