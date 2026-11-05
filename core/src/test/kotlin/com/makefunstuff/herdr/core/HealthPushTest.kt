package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.health.HealthClient
import com.makefunstuff.herdr.core.health.HealthReport
import com.makefunstuff.herdr.core.health.Memory
import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.AlertChange
import com.makefunstuff.herdr.core.push.Alerts
import com.makefunstuff.herdr.core.push.HerdrPush
import com.makefunstuff.herdr.core.push.PushEvent
import com.makefunstuff.herdr.core.push.PushMessages
import com.makefunstuff.herdr.core.push.PushSeal
import com.makefunstuff.herdr.core.push.QuietHours
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A machine running low, as herdr-push tells the phone and the phone shows it. */
class HealthPushTest {
    private val script = File(System.getProperty("herdrPush") ?: "../host/herdr-push/herdr-push")

    private fun python(): Boolean = runCatching {
        ProcessBuilder("python3", "--version").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
    }.getOrDefault(false)

    @Test
    fun readsTheEventAndShowsIt() {
        val msg = PushMessages.parse(
            """{"v":1,"host":"devbox","at":5,"events":[{"type":"health","low":["ram","disk:/"],
              "memory":{"total":65536000,"available":3276800,"swap_total":0,"swap_free":0},
              "disks":[{"mount":"/","device":"/dev/nvme0n1p2","total":500000000,"used":495000000,"avail":5000000}],
              "top":[["Android emulator · Pixel_8",5872000],["Gradle daemon",3250000]]},
              {"type":"health","low":[]}]}""",
        )
        val (low, fine) = assertNotNull(msg).events.map { assertIs<PushEvent.Health>(it).report }
        assertEquals(listOf("ram", "disk:/"), low.low)
        assertEquals(Memory(65536000, 3276800), low.memory)
        assertEquals("/", low.disks.single().mount)
        val alert = assertIs<AlertChange.Show>(Alerts.of("m1", "devbox", PushEvent.Health(low))).alert
        assertEquals(Alerts.healthTag("m1"), alert.tag)
        assertEquals("devbox is low on memory and disk", alert.title)
        assertEquals("5% of 62.5 GB RAM free · 4.8 GB free on / · most: Android emulator · Pixel_8", alert.text)
        assertTrue(alert.detail.endsWith("Most memory: Android emulator · Pixel_8 (5.6 GB), Gradle daemon (3.1 GB)"), alert.detail)
        assertTrue(alert.health)
        assertEquals(Alert.Kind.NeedsYou, alert.kind)
        // It needs you: quiet hours let it through.
        assertFalse(QuietHours.holds(alert))
        assertEquals(AlertChange.Clear("health:m1"), Alerts.of("m1", "devbox", PushEvent.Health(fine)))
        assertEquals("devbox is low on disk space", Alerts.health("m1", "devbox", HealthReport(listOf("disk:/data"))).let { (it as AlertChange.Show).alert.title })
    }

    @Test
    fun herdrPushWarnsOnceAndWhenItIsOver() {
        assumeTrue(python(), "python3 isn't installed")
        val dir = Files.createTempDirectory("herdr-push-health").toFile()
        val received = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/up") { ex ->
            received += ex.requestBody.readBytes().toString(Charsets.UTF_8)
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        server.start()
        try {
            val fake = File(dir, "fake").apply { mkdirs() }
            val herdr = File(fake, "herdr").apply {
                writeText("#!/bin/sh\necho '{\"result\":{\"snapshot\":{\"agents\":[]}}}'\n")
                setExecutable(true)
            }
            val board = File(fake, "herdr-projects").apply { writeText("#!/bin/sh\necho '[]'\n"); setExecutable(true) }
            val meminfo = File(fake, "meminfo")
            fun available(percent: Int) = meminfo.writeText("MemTotal: 1000000 kB\nMemAvailable: ${percent * 10000} kB\nSwapTotal: 0 kB\nSwapFree: 0 kB\n")
            val df = File(fake, "df").apply {
                writeText("Filesystem Type 1024-blocks Used Available Capacity Mounted on\n/dev/x ext4 100000000 50000000 50000000 50% /\n")
            }
            val env = mapOf(
                "HOME" to dir.path, "HERDR_PUSH_HOME" to File(dir, "state").path,
                "HERDR_PUSH_HEALTH_EVERY" to "0.01", "HERDR_PUSH_MEMINFO" to meminfo.path, "HERDR_PUSH_DF" to df.path,
            )
            fun sh(command: String, input: String = ""): Int {
                val pb = ProcessBuilder("sh", "-c", command).directory(dir)
                pb.environment().putAll(env)
                pb.environment().remove("HERDR_SOCKET_PATH")
                val p = pb.start()
                p.outputStream.use { it.write(input.toByteArray()) }
                p.inputStream.readBytes()
                val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
                assertTrue(p.waitFor(60, TimeUnit.SECONDS))
                if (err.isNotBlank()) System.err.println(err)
                return p.exitValue()
            }
            assertEquals(0, sh(HerdrPush.installCommand(), HerdrPush.script))
            val key = PushSeal.newKey()
            val sub = HerdrPush.subscription(HerdrPush.subscriberId("phone1", "m1"), "Pixel", "http://127.0.0.1:${server.address.port}/up", key, "default", herdr.path)
            assertEquals(0, sh(HerdrPush.command("subscribe"), sub))

            fun once() { Thread.sleep(30); assertEquals(0, sh(HerdrPush.command("once"))) }
            fun health(i: Int) = assertIs<PushEvent.Health>(assertNotNull(PushMessages.open(received[i], mapOf("m1" to key))).second.events.single()).report

            // 40% free: nothing to say.
            available(40); once()
            assertTrue(received.isEmpty())
            // 5% free: a warning, naming what uses the most (this machine's own processes).
            available(5); once()
            assertEquals(1, received.size)
            val low = health(0)
            assertEquals(listOf("ram"), low.low)
            assertEquals(50000L, low.memory?.availableKb)
            assertTrue(low.top.isNotEmpty() && low.top.all { it.second > 0 }, "${low.top}")
            // Still low, then in between (12%): said once.
            once(); available(12); once()
            assertEquals(1, received.size)
            // Room again: the warning comes down.
            available(20); once()
            assertEquals(2, received.size)
            assertTrue(health(1).low.isEmpty())
            // And the hour's readings are there for the chart.
            val points = HealthClient.parseHistory(File(dir, "state/health.json").readText())
            assertEquals(listOf(60.0, 95.0, 95.0, 88.0, 80.0), points.map { it.memUsed })
        } finally {
            server.stop(0)
            dir.deleteRecursively()
        }
    }
}
