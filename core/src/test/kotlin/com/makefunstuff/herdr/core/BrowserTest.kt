package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.shepherdr.BrowserState
import com.makefunstuff.herdr.core.shepherdr.BrowserView
import com.makefunstuff.herdr.core.shepherdr.ForwardTransport
import com.makefunstuff.herdr.core.shepherdr.ShepherdrClient
import com.makefunstuff.herdr.core.shepherdr.ShepherdrJson
import com.makefunstuff.herdr.core.shepherdr.TaskBrowser
import com.makefunstuff.herdr.core.shepherdr.V2Board
import com.makefunstuff.herdr.core.shepherdr.V2Browser
import com.makefunstuff.herdr.core.shepherdr.V2Task
import com.makefunstuff.herdr.core.ssh.Duplex
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A task's browser through 3.1's relay (`service/browser.ts`): what comes down, what goes up, and the tab's words. */
class BrowserTest {
    private val jpeg = byteArrayOf(-1, -40, -1, -32, 1, 2, 3)
    private val frame = """{"type":"frame","data":"${Base64.getEncoder().encodeToString(jpeg)}","metadata":{"deviceWidth":1280,"deviceHeight":800}}"""
    private val state = """{"type":"state","driver":"agent","url":"http://localhost:5173/jobs","title":"Jobs","connected":true,"viewport":{"w":1280,"h":800}}"""

    @Test
    fun readsWhatTheRelaySends() {
        val f = assertIs<TaskBrowser.Event.Frame>(TaskBrowser.parse(frame))
        assertContentEquals(jpeg, f.jpeg)
        assertEquals(1280, f.width)
        val s = assertIs<TaskBrowser.Event.State>(TaskBrowser.parse(state)).state
        assertEquals(BrowserState(driving = false, url = "http://localhost:5173/jobs", title = "Jobs", connected = true), s)
        assertTrue((TaskBrowser.parse("""{"type":"state","driver":"you","connected":true}""") as TaskBrowser.Event.State).state.driving)
        // No session, or agent-browser silent: the view says so.
        assertTrue((TaskBrowser.parse("""{"type":"state","driver":"agent","connected":false,"closed":true}""") as TaskBrowser.Event.State).state.closed)
        assertEquals("agent-browser didn't answer.", (TaskBrowser.parse("""{"type":"state","connected":false,"error":"agent-browser didn't answer."}""") as TaskBrowser.Event.State).state.error)
        assertEquals(TaskBrowser.Event.Gone("closed"), TaskBrowser.parse("""{"type":"error","message":"closed","gone":true}"""))
        // What it doesn't know, or can't read, is skipped.
        assertNull(TaskBrowser.parse("""{"type":"hover","box":null}"""))
        assertNull(TaskBrowser.parse("""{"type":"frame","data":"not base64!"}"""))
        assertNull(TaskBrowser.parse("not json"))
    }

    @Test
    fun aTasksBrowserComesFromItsSnapshotRow() {
        val local = ShepherdrJson.decodeFromString(V2Task.serializer(), """{"id":"HA-1","agent":{"chat":"HA-1/worker","running":true},"browser":{"ready":true}}""")
        assertEquals(V2Browser(ready = true), V2Board.task(local).browser)
        assertTrue(V2Board.task(local).running)
        val there = ShepherdrJson.decodeFromString(V2Task.serializer(), """{"id":"vm:HA-2","browser":{"ready":false,"hint":"This task runs on vm."}}""")
        assertEquals("This task runs on vm.", V2Board.task(there).browser?.hint)
        // An older service, or the Browser add-on off: no tab.
        assertNull(V2Board.task(ShepherdrJson.decodeFromString(V2Task.serializer(), """{"id":"HA-3"}""")).browser)
    }

    /** Shepherdr's own socket, where nothing on the machine relays: the task asked by its id, input up as 3.1's web sends it. */
    @Test
    fun watchesAndDrivesOverShepherdrsSocket() = runBlocking {
        MiniShepherdr().use { fake ->
            val up = LinkedBlockingQueue<String>()
            fake.script = { ws ->
                ws.send(state)
                ws.send(frame)
                while (true) up += ws.read().takeIf { it.isNotEmpty() } ?: break
            }
            val browser = ShepherdrClient(ForwardTransport({ ForwardTransport.socket(fake.port, it) })).browser("home-demo#3")
            assertEquals("/api/browser?task=home-demo%233", fake.path)
            assertIs<TaskBrowser.Event.State>(browser.next())
            assertIs<TaskBrowser.Event.Frame>(browser.next())
            browser.touch("touchStart", listOf(640 to 120))
            browser.text("hi")
            browser.key("Enter")
            browser.key("Shift")
            browser.handBack()
            val sent = List(8) { ShepherdrJson.parseToJsonElement(up.poll(5, TimeUnit.SECONDS)!!).jsonObject }
            assertEquals("input_touch", sent[0].type)
            assertEquals("""[{"x":640,"y":120,"id":0}]""", sent[0]["touchPoints"].toString())
            // Text goes as keys that type themselves, a character each: the relay passes on only input.
            val typed = sent.slice(1..4)
            assertTrue(typed.all { it.type == "input_keyboard" && it["code"] == null && it["windowsVirtualKeyCode"] == null })
            assertEquals(listOf("keyDown" to "h", "keyUp" to "h", "keyDown" to "i", "keyUp" to "i"), typed.map { it["eventType"]!!.jsonPrimitive.content to it["key"]!!.jsonPrimitive.content })
            assertEquals(listOf("h", null, "i", null), typed.map { it["text"]?.jsonPrimitive?.content })
            // Enter types a new line on its way down, as a browser's own; a key it doesn't know goes nowhere.
            assertEquals(listOf("keyDown", "keyUp"), sent.slice(5..6).map { it["eventType"]!!.jsonPrimitive.content })
            assertEquals("\r", sent[5]["text"]!!.jsonPrimitive.content)
            assertNull(sent[6]["text"])
            assertEquals("handback", sent[7].type)
            browser.close()
        }
    }

    /** The relay on the machine (this machine's `bun` or `node`): the browser's socket by its path, its frames passed on. */
    @Test
    fun theRelayCarriesTheBrowser() = runBlocking {
        assumeTrue(runtime(), "needs bun, or node with WebSocket")
        MiniShepherdr().use { fake ->
            val up = LinkedBlockingQueue<String>()
            fake.script = { ws ->
                ws.send(state)
                ws.send(frame)
                ws.send(frame)
                up += ws.read()
                ws.read()
            }
            val transport = ForwardTransport({ ForwardTransport.socket(fake.port, it) }, relay = { command ->
                // The service's own address on the machine, at the stand-in's port.
                processDuplex(ProcessBuilder("sh", "-c", command.replace("127.0.0.1:7788", "127.0.0.1:${fake.port}")).start())
            })
            val browser = ShepherdrClient(transport).browser("HA-1")
            withTimeout(20_000) {
                assertIs<TaskBrowser.Event.State>(browser.next())
                assertIs<TaskBrowser.Event.Frame>(browser.next())
            }
            assertEquals("/api/browser?task=HA-1", fake.path)
            browser.reload()
            assertEquals("reload", ShepherdrJson.parseToJsonElement(up.poll(10, TimeUnit.SECONDS)!!).jsonObject.type)
            browser.close()
        }
    }

    @Test
    fun saysWhatTheViewIsDoing() {
        assertEquals(BrowserView.STARTING, BrowserView.note(null, stopped = false, opening = false, live = false)?.text)
        assertEquals(BrowserView.STOPPED, BrowserView.note(BrowserState(connected = true), stopped = true, opening = false, live = true)?.text)
        val closed = assertNotNull(BrowserView.note(BrowserState(closed = true), stopped = false, opening = false, live = false))
        assertEquals(BrowserView.Note("${BrowserView.CLOSED} ${BrowserView.CLOSED_MORE}", BrowserView.NOT_OPEN, open = true), closed)
        // A wait has no title: it shows as one.
        assertNull(BrowserView.note(null, stopped = false, opening = false, live = false)?.title)
        assertEquals(BrowserView.OPENING, BrowserView.note(BrowserState(closed = true), stopped = false, opening = true, live = false)?.text)
        assertTrue(BrowserView.note(BrowserState(error = "agent-browser didn't answer."), stopped = false, opening = false, live = false)!!.error)
        assertEquals(BrowserView.LOOKING, BrowserView.note(BrowserState(connected = true), stopped = false, opening = false, live = false)?.text)
        assertNull(BrowserView.note(BrowserState(connected = true), stopped = false, opening = false, live = true))
        assertEquals("You're driving", BrowserView.mark(live = true, driving = true))
        assertEquals("Off", BrowserView.mark(live = false, driving = true))
        assertEquals("localhost:5173", BrowserView.shortUrl("http://localhost:5173/"))
        assertEquals("example.com/a/", BrowserView.shortUrl("https://example.com/a/"))
        assertEquals("HA-1 is done: its browser was closed.", BrowserView.noAgent("HA-1", running = false, done = true))
        assertNull(BrowserView.noAgent("HA-1", running = true, done = false))
    }

    @Test
    fun aTouchLandsOnThePage() {
        // A 1280×800 page on a 400×600 phone box: 400×250, 175 down.
        val r = BrowserView.fit(400f, 600f, 1280f, 800f)
        assertContentEquals(floatArrayOf(0f, 175f, 400f, 250f), r)
        assertEquals(640 to 400, BrowserView.toPage(200f, 300f, r, 1280, 800))
        assertNull(BrowserView.toPage(200f, 100f, r, 1280, 800))
        assertContentEquals(floatArrayOf(0f, 0f, 400f, 250f), BrowserView.fit(400f, 600f, 1280f, 800f, top = true))
        assertContentEquals(floatArrayOf(0f, 0f, 0f, 0f), BrowserView.fit(0f, 600f, 1280f, 800f))
    }

    private val JsonObject.type get() = this["type"]!!.jsonPrimitive.content

    private fun runtime(): Boolean = runCatching {
        ProcessBuilder("sh", "-c", "exec \"\${SHELL:-/bin/sh}\" -lc 'command -v bun >/dev/null || node -e \"process.exit(typeof WebSocket===\\\"undefined\\\"?1:0)\"'")
            .redirectErrorStream(true).start().let { it.waitFor(20, TimeUnit.SECONDS) && it.exitValue() == 0 }
    }.getOrDefault(false)

    private fun processDuplex(p: Process): Duplex = object : Duplex {
        override val input: InputStream = p.inputStream
        override val output: OutputStream = p.outputStream
        override fun close() {
            runCatching { p.outputStream.close() }
        }
    }
}
