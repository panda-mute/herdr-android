package com.makefunstuff.herdr.core.ssh

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * `ssh -L`: a port on the phone's own loopback address whose connections each reach [host]:[port] on
 * the machine through a channel of their own ([open], a `direct-tcpip` channel of the app's SSH
 * connection). It takes [preferred] when that is free (the machine's own port number, so a page
 * that names its port, as a dev server's live reload does, still finds it), else any free one.
 * Nothing but the phone itself can connect to it.
 */
class PortForward(
    val host: String,
    val port: Int,
    preferred: Int = port,
    private val open: (host: String, port: Int) -> Duplex,
) : Closeable {
    private val server: ServerSocket = bind(preferred)
    private val live = Collections.synchronizedSet(mutableSetOf<Closeable>())
    @Volatile private var closed = false

    /** The port on the phone: `http://localhost:<localPort>/` is the machine's `<host>:<port>`. */
    val localPort: Int get() = server.localPort

    init {
        thread(name = "forward-$port", isDaemon = true) {
            while (!closed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(name = "forward-$port-conn", isDaemon = true) { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) {
        live += client
        val channel = try {
            open(host, port)
        } catch (_: Exception) {
            runCatching { client.close() }
            live -= client
            return
        }
        live += channel
        val done = {
            runCatching { client.close() }
            runCatching { channel.close() }
            live -= client
            live -= channel
        }
        // Taken here, not in the threads: a channel the machine closes at once (Windows' sshd to a
        // port nothing listens on) closes the client before a thread would, and that would throw there.
        // The client may be closed already too (the forward closing), so its setup is in here as well.
        val up = try {
            client.tcpNoDelay = true
            client.getInputStream() to client.getOutputStream()
        } catch (_: IOException) {
            done()
            return
        }
        thread(name = "forward-$port-up", isDaemon = true) {
            pump(up.first, channel.output)
            done()
        }
        pump(channel.input, up.second)
        done()
    }

    private fun pump(from: InputStream, to: OutputStream) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: Exception) {
            // A dropped side (IOException, or the SSH library's own runtime errors): the forward ends, never the app.
        }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        synchronized(live) { live.toList() }.forEach { runCatching { it.close() } }
        live.clear()
    }

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

        fun bind(preferred: Int): ServerSocket {
            if (preferred in 1024..65535) runCatching { return ServerSocket(preferred, 50, LOOPBACK) }
            return ServerSocket(0, 50, LOOPBACK)
        }
    }
}
