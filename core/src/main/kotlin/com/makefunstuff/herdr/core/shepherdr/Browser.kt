package com.makefunstuff.herdr.core.shepherdr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.io.Closeable
import java.util.Base64
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A task's browser, live, as 3.1's task view Browser tab has it (`web/plugins/browser/client.tsx` over the service's
 * relay, `service/browser.ts`): the agent-browser session its agent drives, its pictures and its address down, the
 * person's touches and keys up. The first touch or key takes it over ("You're driving": the agent is told to hold off);
 * [handBack] gives it back. Only this machine's tasks: another machine's says why in its [V2Browser.hint].
 */
class TaskBrowser internal constructor(private val socket: LiveSocket) : Closeable {
    /** What comes from the relay. */
    sealed interface Event {
        /** A picture of the page ([jpeg]) as wide as the page's own [width] in CSS px. */
        class Frame(val jpeg: ByteArray, val width: Int) : Event

        data class State(val state: BrowserState) : Event

        /** It closed with its task (stopped or merged): no view comes back for it. */
        data class Gone(val message: String) : Event
    }

    /** The next event, or null once the view stopped. Blocks: read it on a thread of its own. */
    fun next(): Event? {
        while (true) parse(socket.receive() ?: return null)?.let { return it }
    }

    /** The page touched at [points] (page px): `touchStart`, `touchMove`, `touchEnd`, as a phone's browser sends them. */
    fun touch(eventType: String, points: List<Pair<Int, Int>>) = say(
        buildJsonObject {
            put("type", "input_touch")
            put("eventType", eventType)
            put("touchPoints", JsonArray(points.mapIndexed { i, (x, y) -> buildJsonObject { put("x", x); put("y", y); put("id", i) } }))
        },
    )

    /**
     * What the phone's keyboard typed, into the page: each character a key that types itself, with no key code (as
     * 3.1's `keyEvent`; the relay passes on only `input_*`, and a code would make "." act as Delete).
     */
    fun text(text: String) {
        var i = 0
        while (i < text.length) {
            val ch = String(Character.toChars(text.codePointAt(i)))
            i += ch.length
            for (type in listOf("keyDown", "keyUp")) say(
                buildJsonObject {
                    put("type", "input_keyboard"); put("eventType", type); put("key", ch); put("modifiers", 0)
                    if (type == "keyDown") put("text", ch)
                },
            )
        }
    }

    /** A key that types nothing ([KEYS]: Backspace, Enter…), pressed and let go. */
    fun key(key: String) {
        val code = KEYS[key] ?: return
        for (type in listOf("keyDown", "keyUp")) say(
            buildJsonObject {
                put("type", "input_keyboard"); put("eventType", type); put("key", key); put("code", key)
                put("windowsVirtualKeyCode", code); put("modifiers", 0)
                if (type == "keyDown" && key == "Enter") put("text", "\r")
            },
        )
    }

    fun back() = say("back")
    fun reload() = say("reload")
    fun handBack() = say("handback")

    /** Open browser: the task's own session opened, where it isn't. */
    fun open() = say("open")

    private fun say(type: String) = say(buildJsonObject { put("type", type) })

    private fun say(m: JsonObject) {
        runCatching { socket.send(m.toString()) }
    }

    override fun close() = socket.close()

    companion object {
        /** The relay's socket for task [id] (`<list slug>#<id>` or `HA-1`, as the service names it). */
        fun path(id: String) = "/api/browser?task=" + java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")

        /** The relay on the machine for the socket at [path]: of its pictures and its state, only the newest waiting. */
        fun relayCommand(path: String) = LiveRelay.command(snapshots = true, url = "ws://${Wire.HOST}$path", newest = listOf("frame", "state"))

        /** Chrome's key codes for the keys that type nothing, as the web app sends them. */
        val KEYS = mapOf("Backspace" to 8, "Tab" to 9, "Enter" to 13, "Escape" to 27, "ArrowLeft" to 37, "ArrowUp" to 38, "ArrowRight" to 39, "ArrowDown" to 40)

        /** One message of the relay; null for one it doesn't know or can't read. */
        fun parse(text: String): Event? {
            val o = runCatching { ShepherdrJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun num(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.doubleOrNull
            return when (str(o, "type")) {
                "frame" -> {
                    val data = str(o, "data") ?: return null
                    val jpeg = runCatching { Base64.getDecoder().decode(data) }.getOrNull() ?: return null
                    Event.Frame(jpeg, num(o["metadata"] as? JsonObject, "deviceWidth")?.roundToInt() ?: 0)
                }
                "state" -> {
                    val view = o["viewport"] as? JsonObject
                    Event.State(
                        BrowserState(
                            driving = str(o, "driver") == "you",
                            url = str(o, "url").orEmpty(),
                            title = str(o, "title").orEmpty(),
                            connected = (o["connected"] as? JsonPrimitive)?.booleanOrNull == true,
                            closed = (o["closed"] as? JsonPrimitive)?.booleanOrNull == true,
                            error = str(o, "error")?.takeIf { it.isNotBlank() },
                            width = num(view, "w")?.roundToInt() ?: 1280,
                        ),
                    )
                }
                "error" -> if ((o["gone"] as? JsonPrimitive)?.booleanOrNull == true) Event.Gone(str(o, "message").orEmpty()) else null
                else -> null
            }
        }
    }
}

/** Where the task's browser is, as the relay says it. */
data class BrowserState(
    /** The person drives it ("You're driving"); else its agent does. */
    val driving: Boolean = false,
    val url: String = "",
    val title: String = "",
    /** Its session runs and the view has its stream. */
    val connected: Boolean = false,
    /** No session: the task's browser isn't open. */
    val closed: Boolean = false,
    /** agent-browser didn't answer: why. */
    val error: String? = null,
    /** agent-browser's page width (CSS px), until a picture says otherwise. */
    val width: Int = 1280,
)

/** The Browser tab's words and arithmetic, as 3.1's (`web/plugins/browser`, the window's `bin/herdr-browser-view`). */
object BrowserView {
    const val STARTING = "Starting the live view…"
    const val STOPPED = "The live view stopped. Starting it again…"
    const val LOOKING = "Looking for this task's browser…"
    const val OPENING = "Opening the browser…"
    const val CLOSED = "This task's browser isn't open."
    const val CLOSED_MORE = "Its agent opens it when it checks a page; it closes after a while unused."
    const val OPEN = "Open browser"
    const val NOT_OPEN = "Not open"
    const val NO_ANSWER = "No answer"
    const val DRIVING = "You're driving"
    const val LIVE = "Live"
    const val OFF = "Off"
    const val HAND_BACK = "Hand back"
    const val BACK = "Back"
    const val RELOAD = "Reload"
    const val KEYBOARD = "Keyboard"

    /** The view stopped: started again, not more than every 10 s (the window's). */
    const val RETRY_MS = 10_000L

    /** Open browser unanswered this long: its note shows again, with Open browser. */
    const val OPENING_MS = 20_000L

    /** Quiet this long while you drive: handed back (the window's IDLE_DRIVE_MS). */
    const val IDLE_DRIVE_MS = 60_000L

    /** Why task [name] has no browser to watch: its agent isn't [running] (never started, ended, [done]); null when it is. */
    fun noAgent(name: String, running: Boolean, done: Boolean): String? = when {
        running -> null
        done -> "$name is done: its browser was closed."
        else -> "$name has no agent running, so it has no browser to watch."
    }

    /**
     * What shows in place of the page, in 3.1's words; null while the page shows. One with a [title] is a state to
     * read (with Open browser where [open]); one without, a wait.
     */
    data class Note(val text: String, val title: String? = null, val error: Boolean = false, val open: Boolean = false)

    fun note(state: BrowserState?, stopped: Boolean, opening: Boolean, live: Boolean): Note? = when {
        stopped -> Note(STOPPED)
        state == null -> Note(STARTING)
        opening -> Note(OPENING)
        state.error != null -> Note(state.error, NO_ANSWER, error = true, open = true)
        state.closed -> Note("$CLOSED $CLOSED_MORE", NOT_OPEN, open = true)
        !live -> Note(LOOKING)
        else -> null
    }

    /** The bar's word by its light: who drives, or that it's off. */
    fun mark(live: Boolean, driving: Boolean) = if (!live) OFF else if (driving) DRIVING else LIVE

    /** A page address without its scheme and a lone trailing slash, as the bar shows it. */
    fun shortUrl(u: String) = u.replace(Regex("^https?://"), "").replace(Regex("^([^/?#]+)/$"), "$1")

    /**
     * The page's picture fitted whole into a box [boxW]×[boxH], across its middle and at its [top] (on a phone, whose
     * box is taller than a desktop page; else its middle): its left, top, width, height.
     */
    fun fit(boxW: Float, boxH: Float, pageW: Float, pageH: Float, top: Boolean = false): FloatArray {
        if (pageW <= 0f || pageH <= 0f || boxW <= 0f || boxH <= 0f) return floatArrayOf(0f, 0f, 0f, 0f)
        val s = min(boxW / pageW, boxH / pageH)
        val w = pageW * s
        val h = pageH * s
        return floatArrayOf((boxW - w) / 2, if (top) 0f else (boxH - h) / 2, w, h)
    }

    /** A point in the box as a point on the page (CSS px), for a page [pageW]×[pageH] shown at [r] ([fit]); none outside it. */
    fun toPage(x: Float, y: Float, r: FloatArray, pageW: Int, pageH: Int): Pair<Int, Int>? {
        val (rx, ry, rw, rh) = r
        if (rw <= 0f || x < rx || y < ry || x > rx + rw || y > ry + rh) return null
        return ((x - rx) / rw * pageW).roundToInt() to ((y - ry) / rh * pageH).roundToInt()
    }
}
