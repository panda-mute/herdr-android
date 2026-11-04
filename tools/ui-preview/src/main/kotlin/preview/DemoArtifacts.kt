// Shepherdr 3.1's artifacts on devbox: three recipes agents made in herdr-android and api-server (recipe.json, as
// core/recipe.js describes it), the pictures beside one (drawn here as blocks, so every run gives the same pixels),
// a GPUI panel only the Herdr window runs, and a post of one in a task's chat. FakeShepherdr serves them as
// `/api/artifacts/<id>/recipe` and `/file/<path>` do.
package preview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

object DemoArtifacts {
    /** The snapshot's `artifacts`: newest first by `at`, the release notes posted in herdr-android's Assistant chat (`m7`). */
    val LIST = """
        [{"id":"rn09","title":"Release notes for 0.9","kind":"recipe","place":"${DemoV2.APP}","store":"|/home/demo/src/herdr-android","folder":"/home/demo/src/herdr-android","folderName":"herdr-android",
          "v":2,"at":1793229000000,"when":"3m","by":"herdr-android Assistant","keeper":"herdr-android Assistant",
          "chats":["${DemoV2.chat(DemoV2.APP)}"],"versions":[{"n":2,"when":"3m","by":"herdr-android Assistant","html":2,"note":"grouped by area"},{"n":1,"when":"1h","by":"herdr-android Assistant","html":1}]},
         {"id":"bl3x","title":"AND-19: Board load times","kind":"recipe","place":"${DemoV2.APP}","store":"|/home/demo/src/herdr-android","task":"AND-19",
          "v":3,"at":1793228000000,"when":"20m","by":"AND-19 agent","keeper":"AND-19 agent",
          "chats":["AND-19/worker"],"versions":[{"n":3,"when":"20m","by":"AND-19 agent","html":3,"note":"before and after"},{"n":2,"when":"1h","by":"AND-19 agent","html":2,"note":"by screen"},{"n":1,"when":"2h","by":"AND-19 agent","html":1,"kind":"gpui"}]},
         {"id":"sq7k","title":"Slow queries on /jobs","kind":"gpui","place":"${DemoV2.API}","store":"|/home/demo/src/api-server",
          "v":1,"at":1793227000000,"when":"40m","by":"API-7 agent","keeper":"API-7 agent",
          "chats":["API-7/worker"],"versions":[{"n":1,"when":"40m","by":"API-7 agent","html":1,"kind":"gpui"}]}]
    """.trimIndent()

    /** Each recipe's `recipe.json` (its latest version's; the previews serve the same for every version). */
    val RECIPES = mapOf(
        "rn09" to """
            {"recipe":1,"blocks":[
             {"type":"heading","text":"Herdr for Android 0.9","level":1},
             {"type":"text","md":"Out on **Friday**. Every build installs over the last one and keeps its machines and SSH key."},
             {"type":"heading","text":"Chats"},
             {"type":"list","items":["Artifacts drawn natively, in your light or dark","Voice calls with an Assistant","A task chat keeps its whole composer"]},
             {"type":"heading","text":"Board"},
             {"type":"list","items":["Shepherdr 3's spaces, tasks and states","Accept from the task's own card"]},
             {"type":"callout","tone":"good","title":"Fixed","md":"The Board card no longer jumps on reconnect."},
             {"type":"choice","question":"Ship it on Friday?","options":[{"label":"Yes"},{"label":"Wait a week","description":"Give the voice call more testing"}]}
            ]}
        """.trimIndent(),
        "bl3x" to """
            {"recipe":1,"blocks":[
             {"type":"heading","text":"Board load times","level":1},
             {"type":"text","md":"Median time to the first card, by screen, this week."},
             {"type":"stats","items":[{"label":"Board","value":"420 ms","note":"−35%"},{"label":"Agents","value":"310 ms"},{"label":"Task","value":"260 ms"}]},
             {"type":"chart","kind":"bar","labels":["Board","Agents","Task"],"series":[{"name":"Before","values":[650,420,300]},{"name":"After","values":[420,310,260]}]},
             {"type":"table","columns":["Screen",{"label":"Before","align":"right"},{"label":"After","align":"right"}],"rows":[["Board","650","420"],["Agents","420","310"],["Task","300","260"]]},
             {"type":"pair","before":{"src":"before.png","caption":"Before: the card jumps"},"after":{"src":"after.png","caption":"After: it stays"}},
             {"type":"details","summary":"How it was measured","blocks":[{"type":"code","text":"./gradlew -p tools/ui-preview render -Ponly=board","lang":"sh"}]},
             {"type":"button","label":"Measure again","reply":"Please measure the load times again"}
            ]}
        """.trimIndent(),
    )

    /** A picture beside [id]'s recipe ([name]), or null where there is none. */
    fun picture(id: String, name: String): ByteArray? = if (id == "bl3x" && name in setOf("before.png", "after.png")) screen(name == "after.png") else null

    /** A phone's Board drawn as blocks: its cards, the first one moved down in [after]'s "before". */
    private fun screen(after: Boolean): ByteArray {
        val w = 540
        val h = 360
        val surface = Surface.makeRasterN32Premul(w, h)
        val c = surface.canvas
        c.clear(0xFF1C1A17.toInt())
        val card = Paint().apply { color = 0xFF2A2621.toInt() }
        val dim = Paint().apply { color = 0xFF57504A.toInt() }
        val accent = Paint().apply { color = 0xFFE0A33A.toInt() }
        listOf(0, 1, 2).forEach { i ->
            val y = 40f + i * 100f + if (!after && i == 0) 36f else 0f
            c.drawRRect(RRect.makeXYWH(30f, y, 480f, 80f, 12f), card)
            c.drawCircle(60f, y + 40f, 8f, if (i == 0) accent else dim)
            c.drawRRect(RRect.makeXYWH(84f, y + 22f, 260f, 14f, 7f), dim)
            c.drawRRect(RRect.makeXYWH(84f, y + 46f, 180f, 12f, 6f), dim)
        }
        if (!after) c.drawRect(Rect.makeXYWH(30f, 40f, 480f, 4f), accent)
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }
}

/** devbox on Shepherdr 3.1 with artifacts: the release notes in herdr-android's chat, and the load times posted in AND-19's. */
fun SceneData.artifacts() {
    val v2 = DemoV2.board()
    v2.snapshot = JsonObject(v2.snapshot + ("artifacts" to Json.parseToJsonElement(DemoArtifacts.LIST).jsonArray))
    v2.chats["AND-19/worker"]?.let { c ->
        val post = obj("id" to "art1", "who" to "agent", "at" to 1793229180869L, "artifact" to obj("id" to "bl3x", "v" to 3))
        val words = obj("id" to "art0", "who" to "agent", "at" to 1793229180869L, "text" to "The load times by screen, before and after:")
        v2.chats = v2.chats + ("AND-19/worker" to JsonObject(c + ("messages" to JsonArray(c.getValue("messages").jsonArray + words + post))))
    }
    host(Demo.DEVBOX).shepherdr = v2
    host(Demo.DEVBOX).core = DemoCore.shepherdr()
}

/** [artifacts], and herdr-android's Assistant posted the release notes' first version earlier too: that post folds to a line. */
fun SceneData.artifactPostedTwice() {
    artifacts()
    val v2 = host(Demo.DEVBOX).shepherdr!!
    val key = DemoV2.chat(DemoV2.APP)
    v2.chats[key]?.let { c ->
        val msgs = c.getValue("messages").jsonArray
        val first = obj("id" to "m0", "who" to "agent", "at" to 1, "artifact" to obj("id" to "rn09", "v" to 1))
        v2.chats = v2.chats + (key to JsonObject(c + ("messages" to JsonArray(msgs.take(4) + first + msgs.drop(4)))))
    }
}
