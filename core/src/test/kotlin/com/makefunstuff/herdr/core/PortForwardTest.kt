package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.ssh.Duplex
import com.makefunstuff.herdr.core.ssh.PortForward
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The port forward: a port of the phone that reaches one of the machine's through SSH channels. */
class PortForwardTest {
    /** A port of the phone reaches the machine's: here a little HTTP server, through plain sockets for SSH channels. */
    @Test
    fun forwardsAPort() {
        ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { web ->
            thread(isDaemon = true) {
                while (true) {
                    val s = runCatching { web.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        s.use {
                            val reader = it.getInputStream().bufferedReader()
                            val first = reader.readLine()
                            while (reader.readLine()?.isNotEmpty() == true) Unit
                            val body = "you asked for $first"
                            it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                        }
                    }
                }
            }
            val opened = mutableListOf<String>()
            val opener = { host: String, port: Int ->
                synchronized(opened) { opened += "$host:$port" }
                val s = Socket(host, port)
                object : Duplex {
                    override val input: InputStream = s.getInputStream()
                    override val output: OutputStream = s.getOutputStream()
                    override fun close() = s.close()
                }
            }
            PortForward("127.0.0.1", web.localPort, preferred = 0, open = opener).use { fwd ->
                assertNotEquals(web.localPort, fwd.localPort)
                repeat(3) { i ->
                    val c = URI("http://localhost:${fwd.localPort}/page$i").toURL().openConnection() as HttpURLConnection
                    assertEquals(200, c.responseCode)
                    assertEquals("you asked for GET /page$i HTTP/1.1", c.inputStream.bufferedReader().readText())
                }
                assertEquals(List(3) { "127.0.0.1:${web.localPort}" }, opened, "one channel a connection")
            }
            // The machine's own port number when it is free on the phone.
            val free = ServerSocket(0).use { it.localPort }
            PortForward("127.0.0.1", web.localPort, preferred = free, open = opener).use { assertEquals(free, it.localPort) }
            // Taken: any other.
            ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { taken ->
                PortForward("127.0.0.1", web.localPort, preferred = taken.localPort, open = opener).use { assertNotEquals(taken.localPort, it.localPort) }
            }
        }
    }

    /** The same through a real sshd (one started for the test): `direct-tcpip` channels of one connection. */
    @Test
    fun forwardsThroughSsh() = runBlocking {
        val sshd = listOf("/usr/sbin/sshd", "/usr/bin/sshd").firstOrNull { java.io.File(it).canExecute() }
        assumeTrue(sshd != null, "sshd is needed")
        val dir = java.nio.file.Files.createTempDirectory("herdr-forward-sshd").toFile()
        var server: Process? = null
        try {
            val hostKey = java.io.File(dir, "ssh_host_ed25519_key")
            assertEquals(0, ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", hostKey.path).start().waitFor())
            val phone = com.makefunstuff.herdr.core.keys.KeyGen.ed25519("herdr-android@test")
            val keys = java.io.File(dir, "authorized_keys").apply { writeText(phone.publicKeyLine + "\n") }
            val port = ServerSocket(0).use { it.localPort }
            java.io.File(dir, "sshd_config").writeText(
                """
                Port $port
                ListenAddress 127.0.0.1
                HostKey ${hostKey.path}
                PidFile ${dir.path}/sshd.pid
                AuthorizedKeysFile ${keys.path}
                StrictModes no
                PubkeyAuthentication yes
                PasswordAuthentication no
                KbdInteractiveAuthentication no
                UsePAM no
                AllowTcpForwarding yes
                """.trimIndent() + "\n",
            )
            server = ProcessBuilder(sshd, "-D", "-e", "-f", java.io.File(dir, "sshd_config").path).redirectErrorStream(true)
                .redirectOutput(java.io.File(dir, "sshd.log")).start()
            val up = (1..50).any { runCatching { Socket("127.0.0.1", port).close() }.isSuccess || run { Thread.sleep(100); false } }
            assumeTrue(up, "sshd didn't start: " + java.io.File(dir, "sshd.log").readText())
            val machine = com.makefunstuff.herdr.core.ssh.Machine("m1", "devbox", "127.0.0.1", port, System.getProperty("user.name"))
            com.makefunstuff.herdr.core.ssh.SshConnector.connect(machine, phone).use { conn ->
                val web = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
                web.createContext("/") { ex ->
                    val body = "page ${ex.requestURI} for ${ex.requestHeaders.getFirst("Host")}".toByteArray()
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                web.start()
                try {
                    conn.forward("127.0.0.1", web.address.port).use { fwd ->
                        // The machine is this computer here, so its port number is taken: another one.
                        assertNotEquals(web.address.port, fwd.localPort)
                        repeat(2) { i ->
                            val c = URI("http://localhost:${fwd.localPort}/a$i").toURL().openConnection() as HttpURLConnection
                            assertEquals("page /a$i for localhost:${fwd.localPort}", c.inputStream.bufferedReader().readText())
                        }
                    }
                } finally {
                    web.stop(0)
                }
            }
        } finally {
            server?.destroy()
            server?.waitFor(5, TimeUnit.SECONDS)
            dir.deleteRecursively()
        }
    }

    @Test
    fun aRefusedChannelClosesTheConnection() {
        PortForward("127.0.0.1", 9, preferred = 0, open = { _, _ -> throw java.io.IOException("refused") }).use { fwd ->
            Socket("127.0.0.1", fwd.localPort).use { s ->
                s.soTimeout = 5000
                assertEquals(-1, s.getInputStream().read())
            }
        }
    }

    @Test
    fun aChannelClosedAtOnceEndsTheConnectionNotTheApp() {
        // Windows' sshd forwarding to a port nothing listens on: the channel opens, then ends at once.
        val crashes = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> crashes += e }
        try {
            val closed = { _: String, _: Int ->
                object : Duplex {
                    override val input: InputStream = java.io.ByteArrayInputStream(ByteArray(0))
                    override val output: OutputStream = OutputStream.nullOutputStream()
                    override fun close() = Unit
                }
            }
            PortForward("127.0.0.1", 7788, preferred = 0, open = closed).use { fwd ->
                repeat(200) {
                    Socket("127.0.0.1", fwd.localPort).use { s ->
                        s.soTimeout = 5000
                        assertEquals(-1, s.getInputStream().read())
                    }
                }
            }
            Thread.sleep(500)
            assertEquals(emptyList(), crashes.toList())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }
}
