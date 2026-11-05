package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.shepherdr.Autopilot
import com.makefunstuff.herdr.core.shepherdr.BytesReply
import com.makefunstuff.herdr.core.shepherdr.HttpReply
import com.makefunstuff.herdr.core.shepherdr.ShepherdrClient
import com.makefunstuff.herdr.core.shepherdr.ShepherdrJson
import com.makefunstuff.herdr.core.shepherdr.ShepherdrTransport
import com.makefunstuff.herdr.core.shepherdr.V2Actions
import com.makefunstuff.herdr.core.shepherdr.V2Board
import com.makefunstuff.herdr.core.shepherdr.V2Event
import com.makefunstuff.herdr.core.shepherdr.V2Events
import com.makefunstuff.herdr.core.shepherdr.V2Pilot
import com.makefunstuff.herdr.core.shepherdr.V2Snapshot
import com.makefunstuff.herdr.core.shepherdr.V2WorkBoard
import com.makefunstuff.herdr.core.shepherdr.WebSocketLink
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Autopilot as Shepherdr 3.1 has it: each space's switch and Settings › Autopilot (`resources/shepherdr/autopilot.json`). */
class AutopilotTest {
    private fun snapshot(): V2Snapshot =
        (V2Events.parse(javaClass.getResource("/shepherdr/autopilot.json")!!.readText()) as V2Event.Snapshot).snapshot

    @Test
    fun eachSpaceHasItsSwitchAndAnotherMachinesHasNone() {
        val o = V2Board.overview(snapshot())
        val byId = o.spaces.associateBy { it.id }
        assertEquals(true, byId.getValue("sp-a").pilot?.on)
        assertEquals(1793232000000.0, byId.getValue("sp-a").pilot?.nextRun)
        assertEquals(false, byId.getValue("sp-b").pilot?.on)
        // Set on its own machine: no switch here.
        assertNull(byId.getValue("vm/sp-c").pilot)
    }

    @Test
    fun settingsAutopilotIsEverywheresPage() {
        val a = assertNotNull(V2Board.overview(snapshot()).autopilot)
        assertEquals("Direction: keep the demo app green.\n", a.heartbeat)
        assertEquals("~/.config/herdr-core/autopilot/everywhere.md", a.file)
        assertEquals("every 2 hours on weekdays", a.every)
        assertNull(Autopilot.presetOf(a.every))
        assertEquals(listOf("ready", "failed"), a.events)
        assertEquals(Autopilot.ABOUT, a.about)
    }

    @Test
    fun theAddOnOffTakesThePageAndTheSwitchesAway() {
        val off = snapshot().copy(off = listOf("autopilot"))
        val o = V2Board.overview(off)
        assertNull(o.autopilot)
        assertTrue(o.spaces.all { it.pilot == null })
        // An older Shepherdr with no page reads as none too.
        assertNull(V2Board.overview(snapshot().copy(settings = emptyList())).autopilot)
    }

    @Test
    fun aSnapshotWhosePagesDontAllReadStillHasAutopilots() {
        val text = javaClass.getResource("/shepherdr/autopilot.json")!!.readText()
            // A row the app has no shape for, and a place that doesn't read: the rest still does.
            .replace(""""settings":[""", """"settings":[{"name":"Odd","rows":[{"label":1,"key":{"x":1}}]},""")
        val s = (V2Events.parse(text) as V2Event.Snapshot).snapshot
        assertNotNull(Autopilot.settingsOf(s))
        assertEquals(3, s.places.size)
    }

    @Test
    fun theHeadersDetailInShepherdrsWords() {
        val now = 1793228400000L // Wed 2026-10-28 23:00 UTC
        val utc = ZoneOffset.UTC
        assertEquals("", Autopilot.headerDetail(V2Pilot(on = false, nextRun = now + 60_000.0), now, utc))
        assertEquals("", Autopilot.headerDetail(null, now, utc))
        assertEquals("Waking", Autopilot.headerDetail(V2Pilot(on = true, inflight = true, pending = true), now, utc))
        assertEquals("Queued", Autopilot.headerDetail(V2Pilot(on = true, pending = true), now, utc))
        assertEquals("Next 00:00", Autopilot.headerDetail(V2Pilot(on = true, nextRun = now + 3_600_000.0), now, utc))
        assertEquals("Next Sat", Autopilot.headerDetail(V2Pilot(on = true, nextRun = now + 3 * 86_400_000.0), now, utc))
        assertEquals("On events", Autopilot.headerDetail(V2Pilot(on = true, events = listOf("ready")), now, utc))
        assertEquals("Run now only", Autopilot.headerDetail(V2Pilot(on = true), now, utc))
    }

    @Test
    fun picksAndEventsInHerdrTasksOrder() {
        assertEquals("every 60 minutes", Autopilot.presetOf(" Every 60 Minutes "))
        assertEquals("", Autopilot.presetOf(""))
        assertEquals(listOf("added", "needs-you", "stalled"), Autopilot.eventsIn(listOf("stalled", "added", "nope", "needs-you", "added")))
    }

    @Test
    fun theBodiesAreTheWebsOwn() {
        assertEquals("""{"type":"mode","place":"sp-a","mode":"autopilot"}""", V2Actions.autopilot("sp-a", true).toString())
        assertEquals("""{"type":"mode","place":"sp-a","mode":"regular"}""", V2Actions.autopilot("sp-a", false).toString())
        assertEquals(
            """{"type":"setting","key":"autopilot.events","place":"all","value":"ready,failed"}""",
            V2Actions.autopilotSetting(Autopilot.EVENTS, "ready,failed").toString(),
        )
    }

    @Test
    fun aRefusalComesBackInHerdrTasksWords() = runBlocking {
        val sent = ArrayList<JsonObject>()
        val t = object : ShepherdrTransport {
            override val canStream = false
            override suspend fun http(method: String, path: String, body: String?, timeoutMs: Int): HttpReply {
                val b = ShepherdrJson.parseToJsonElement(body!!) as JsonObject
                sent += b
                return when ((b["type"] as JsonPrimitive).content) {
                    // main.ts answers a setting's refusal as JSON; actions.ts throws, and the service answers its words.
                    "setting" -> HttpReply(200, """{"ok":false,"error":"Can't read \"every fortnight\" as a schedule."}""")
                    else -> HttpReply(400, "Autopilot is set on that space's own machine.")
                }
            }
            override suspend fun bytes(method: String, path: String, bytes: ByteArray?, type: String?, timeoutMs: Int) = BytesReply(404, ByteArray(0))
            override suspend fun webSocket(path: String): WebSocketLink = throw IOException("none")
            override fun close() {}
        }
        val board = V2WorkBoard(ShepherdrClient(t), "3.1.2")
        val e1 = runCatching { board.autopilotSetting(Autopilot.EVERY, "every fortnight") }.exceptionOrNull()
        assertEquals("Can't read \"every fortnight\" as a schedule.", e1?.message)
        val e2 = runCatching { board.autopilot("vm/sp-c", true) }.exceptionOrNull()
        assertEquals("Autopilot is set on that space's own machine.", e2?.message)
        assertEquals(listOf("setting", "mode"), sent.map { (it["type"] as JsonPrimitive).content })
        assertFalse(sent.any { it["place"] is JsonArray })
    }
}
