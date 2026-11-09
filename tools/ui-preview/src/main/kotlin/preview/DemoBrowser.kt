// A task's browser on the fake machine: the relay command the app starts there (TaskBrowser.relayCommand), answered
// as 3.1's service relays agent-browser (`service/browser.ts`): its state, then a picture of the page; the first touch
// or key makes it yours, Hand back gives it to the agent again, Open browser opens a closed one.
package preview

import com.makefunstuff.herdr.core.shepherdr.LiveRelay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.Typeface
import java.io.BufferedReader
import java.io.PrintStream
import java.util.Base64

object DemoBrowser {
    /** The page AND-19's agent checks: the jobs list it is speeding up, on its dev server. */
    const val URL = "http://localhost:5173/jobs?tenant=acme"

    /** The page's width, as agent-browser's own (`viewport`); its pictures are drawn at it. */
    const val WIDTH = 1280
    const val HEIGHT = 800

    /** Whether [command] is the app's relay to a task's browser. */
    fun isRelay(command: String) = "/api/browser?task=" in command

    /** The task the relay is for. */
    fun taskOf(command: String) = Regex("""/api/browser\?task=([A-Za-z0-9%_.:#-]+)""").find(command)?.groupValues?.get(1)
        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()

    /** The relay: [open] says whether the task's session is open now. */
    fun relay(open: Boolean, input: BufferedReader, out: PrintStream) {
        var isOpen = open
        var driver = "agent"
        fun say(m: String) {
            out.println(m)
            out.flush()
        }
        fun state() = say(
            if (isOpen) """{"type":"state","driver":"$driver","url":"$URL","title":"Jobs · acme","connected":true,"viewport":{"w":$WIDTH,"h":$HEIGHT}}"""
            else """{"type":"state","driver":"agent","url":"","title":"","connected":false,"viewport":{"w":$WIDTH,"h":$HEIGHT},"closed":true}""",
        )
        fun frame() = say("""{"type":"frame","data":"${Base64.getEncoder().encodeToString(page)}","metadata":{"deviceWidth":$WIDTH,"deviceHeight":$HEIGHT}}""")
        say(LiveRelay.HELLO)
        state()
        if (isOpen) frame()
        while (true) {
            val line = input.readLine() ?: break
            val type = runCatching { Json.parseToJsonElement(line).jsonObject["type"]?.jsonPrimitive?.content }.getOrNull()
            when {
                type == null -> {}
                type.startsWith("input_") && isOpen && driver != "you" -> { driver = "you"; state() }
                type == "handback" -> { driver = "agent"; state() }
                type == "open" && !isOpen -> { isOpen = true; state(); frame() }
            }
        }
    }

    /** The jobs page, drawn: its bar, a heading, the load time the agent is bringing down and the list itself. */
    val page: ByteArray by lazy {
        val s = Surface.makeRasterN32Premul(WIDTH, HEIGHT)
        val c = s.canvas
        val sans = font("geist_regular.ttf")
        val bold = font("geist_semibold.ttf")
        fun paint(color: Long) = Paint().apply { this.color = color.toInt() }
        fun text(t: String, x: Float, y: Float, size: Float, color: Long, face: Typeface = sans) = c.drawString(t, x, y, Font(face, size), paint(color))
        c.clear(0xFFF7F7F5.toInt())
        // The app's own bar.
        c.drawRect(Rect.makeXYWH(0f, 0f, WIDTH.toFloat(), 96f), paint(0xFF1F2937))
        text("Acme Jobs", 48f, 62f, 40f, 0xFFFFFFFF, bold)
        text("Jobs", 760f, 60f, 30f, 0xFFFFFFFF)
        text("Runs", 900f, 60f, 30f, 0xFF9CA3AF)
        text("Settings", 1040f, 60f, 30f, 0xFF9CA3AF)
        text("Jobs", 48f, 190f, 64f, 0xFF111827, bold)
        // How long the list took: what AND-19 brings under 200 ms.
        c.drawRect(Rect.makeXYWH(880f, 136f, 352f, 72f), paint(0xFFDCFCE7))
        text("Loaded in 182 ms", 912f, 186f, 34f, 0xFF166534, bold)
        val rows = listOf(
            Triple("nightly-export", "Done", 0xFF166534L), Triple("invoice-pdf", "Running", 0xFFB45309L),
            Triple("reindex-search", "Done", 0xFF166534L), Triple("sync-crm", "Failed", 0xFFB91C1CL), Triple("thumbnails", "Queued", 0xFF6B7280L),
        )
        c.drawRect(Rect.makeXYWH(48f, 250f, 1184f, 500f), paint(0xFFFFFFFF))
        text("NAME", 80f, 296f, 24f, 0xFF6B7280, bold)
        text("STATUS", 720f, 296f, 24f, 0xFF6B7280, bold)
        text("CREATED", 980f, 296f, 24f, 0xFF6B7280, bold)
        rows.forEachIndexed { i, (name, status, color) ->
            val y = 320f + i * 86f
            c.drawRect(Rect.makeXYWH(48f, y, 1184f, 2f), paint(0xFFE5E7EB))
            text(name, 80f, y + 56f, 34f, 0xFF111827)
            text(status, 720f, y + 56f, 34f, color, bold)
            text("${9 - i}:0${i} today", 980f, y + 56f, 30f, 0xFF6B7280)
        }
        s.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 85)!!.bytes
    }

    private fun font(name: String): Typeface =
        FontMgr.default.makeFromData(Data.makeFromBytes(DemoBrowser::class.java.getResourceAsStream("/$name")!!.readBytes()))!!
}
