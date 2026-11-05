package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.hc.HcClient
import com.makefunstuff.herdr.core.hc.HcConnection
import com.makefunstuff.herdr.core.hc.HcTerminalStream
import com.makefunstuff.herdr.core.ssh.Duplex
import com.makefunstuff.herdr.core.terminal.TerminalFind
import com.makefunstuff.herdr.core.terminal.TerminalStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Terminal screen's history on a real herdr-core, this machine's own (`hc connect`): a terminal
 * the test starts, 400 numbered lines and then more after attaching, scrolled back to its first
 * line and found in. Only its own terminal is touched, and removed after. Runs only with
 * HERDR_LIVE_HC_LOCAL set (and `hc` on the PATH).
 */
class LiveHcHistoryTest {
    private class ProcessDuplex(private val process: Process) : Duplex {
        override val input = process.inputStream
        override val output = process.outputStream
        override fun close() { process.destroy() }
    }

    private fun hc(vararg args: String): String {
        val p = ProcessBuilder(listOf("hc") + args).redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readText().also { p.waitFor() }
    }

    @Test
    fun aTerminalsHistoryBeforeAndAfterAttachingIsScrolledToAndFound() {
        assumeTrue(System.getenv("HERDR_LIVE_HC_LOCAL") != null, "set HERDR_LIVE_HC_LOCAL to run")
        val name = "ha15-history"
        hc("kill", name); hc("rm", name)
        val script = "for i in \$(seq 1 400); do echo \"before \$i\"; done; read go; for i in \$(seq 1 30); do echo \"after \$i\"; done; sleep 100000"
        println(hc("run", "--bg", "--no-parent", "--no-notify", "--name", name, "--", "sh", "-c", script))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runBlocking {
            val conn = HcConnection.open(ProcessDuplex(ProcessBuilder("hc", "connect").start()), scope)
            try {
                delay(1_000)
                val stream = HcTerminalStream(HcClient(conn), name, 60, 20, scope)
                stream.open()
                withTimeout(20_000) { stream.status.first { it == TerminalStream.Status.Live } }
                val emu = stream.emulator
                // The read's history goes in above the screen once the first paint is in.
                withTimeout(20_000) { while (synchronized(emu.lock) { emu.historySize } < 380) delay(100) }
                stream.send("\r")
                withTimeout(20_000) { while (synchronized(emu.lock) { emu.allLines().none { it == "after 30" } }) delay(100) }
                synchronized(emu.lock) {
                    val all = emu.allLines().filter { it.isNotBlank() }
                    println("history ${emu.historySize}, first ${all.first()}, last ${all.last()}")
                    // Every line once, in order: the read's and the stream's meet without a gap or a repeat.
                    val numbered = all.filter { it.startsWith("before ") }
                    assertEquals((1..400).map { "before $it" }, numbered)
                    assertEquals((1..30).map { "after $it" }, all.filter { it.startsWith("after ") })

                    // Scrolled back all the way: the first line shows at the top, and the cursor is hidden.
                    emu.scrollBack(emu.historySize)
                    println("top: " + (0 until 3).joinToString(" | ") { emu.lineText(it) })
                    assertTrue((0 until emu.rows).any { emu.lineText(it) == "before 1" })

                    // Find: the newest match first, then older.
                    val find = TerminalFind(emu)
                    val newest = find.find("before 39")
                    println("find 'before 39': ${newest.place?.plus(1)} of ${newest.total}, shown: " + (0 until emu.rows).map(emu::lineText).filter { "before 39" in it })
                    assertEquals(11, newest.total) // 39, and 390 to 399
                    assertTrue((0 until emu.rows).any { emu.lineText(it) == "before 399" })
                }
            } finally {
                conn.close()
                scope.cancel()
                hc("kill", name); hc("rm", name)
            }
        }
    }
}
