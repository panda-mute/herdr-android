package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.core.shepherdr.Artifacts
import com.makefunstuff.herdr.core.shepherdr.BytesReply
import com.makefunstuff.herdr.core.shepherdr.HttpReply
import com.makefunstuff.herdr.core.shepherdr.RecipeBlock
import com.makefunstuff.herdr.core.shepherdr.Recipes
import com.makefunstuff.herdr.core.shepherdr.ShepherdrClient
import com.makefunstuff.herdr.core.shepherdr.ShepherdrJson
import com.makefunstuff.herdr.core.shepherdr.ShepherdrTransport
import com.makefunstuff.herdr.core.shepherdr.SpaceChat
import com.makefunstuff.herdr.core.shepherdr.SpaceChatItem
import com.makefunstuff.herdr.core.shepherdr.V2Board
import com.makefunstuff.herdr.core.shepherdr.V2Chat
import com.makefunstuff.herdr.core.shepherdr.V2Event
import com.makefunstuff.herdr.core.shepherdr.V2Events
import com.makefunstuff.herdr.core.shepherdr.V2Snapshot
import com.makefunstuff.herdr.core.shepherdr.V2WorkBoard
import com.makefunstuff.herdr.core.shepherdr.WebSocketLink
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactsTest {
    private fun fixture(name: String) = javaClass.getResource("/shepherdr/$name")!!.readText()

    private val snapshot: V2Snapshot = (ShepherdrJson.parseToJsonElement(fixture("waiting.json")) as JsonArray)
        .map { V2Events.parse(it.toString()) }.filterIsInstance<V2Event.Snapshot>().single().snapshot

    @Test
    fun aSpaceListsTheArtifactsInItsStoreNewestFirst() {
        val all = snapshot.artifacts!!
        val o = V2Board.overview(snapshot)
        val app = o.spaces.first { it.name == "demo-app" }
        assertEquals(listOf("demo-coverage"), Artifacts.inSpace(all, app.id, o.spaces).map { it.id })
        assertEquals(listOf("api-load"), Artifacts.inSpace(all, "vm/sp0000000000000000000000000000b001", o.spaces).map { it.id })
        // A worktree space shares its repo's store: its artifacts too.
        val worktree = WorkSpace("sp-wt", "demo-app-wt", store = app.store)
        assertEquals(listOf("demo-coverage"), Artifacts.inSpace(all, "sp-wt", o.spaces + worktree).map { it.id })
        assertEquals(emptyList(), Artifacts.inSpace(all, "sp-none", o.spaces))
        // The overview carries them; a board that keeps none has none, so the app shows no artifacts.
        assertEquals(2, o.artifacts!!.size)
        assertNull(V2Board.overview(V2Snapshot()).artifacts)
    }

    @Test
    fun aRecipeIsDrawnAndAPanelOnlyTheWindowRunsIsNot() {
        val a = snapshot.artifacts!!.first { it.id == "demo-coverage" }
        assertEquals("recipe", a.kind)
        assertTrue(a.drawn(2))
        // Converted: its first version was a GPUI panel.
        assertFalse(a.drawn(1))
        assertEquals("v2 · latest", Artifacts.versionLine(a, a.versions[0]))
        assertEquals("DEMO-4", a.task)
        // A version that says no kind of its own takes the artifact's.
        assertTrue(snapshot.artifacts!!.first { it.id == "api-load" }.drawn(1))
    }

    @Test
    fun readsEveryKindOfBlock() {
        val r = Recipes.parse(fixture("recipe.json"))
        assertEquals(
            listOf("Heading", "Text", "Stats", "Table", "Chart", "Chart", "Callout", "Pair", "Columns", "Details", "Divider", "Choice", "Form", "Button", "Unknown"),
            r.blocks.map { it::class.simpleName },
        )
        // Ids as core/recipe.js withIds gives them: its own, else b1, b2… in order, a block before what it holds.
        assertEquals(listOf("b1", "b2", "b3", "b4", "b5", "b6", "b7", "pics", "b8", "b11", "b14", "b15", "b16", "b17", "b18"), r.blocks.map { it.id })
        val cols = r.blocks[8] as RecipeBlock.Columns
        assertEquals(listOf("b9", "b10"), cols.columns.map { it.single().id })
        val stats = r.blocks[2] as RecipeBlock.Stats
        // A number as it was written.
        assertEquals(listOf("92", "41"), stats.items.map { it.value })
        assertEquals("+7", stats.items[0].note)
        val table = r.blocks[3] as RecipeBlock.Table
        assertEquals(listOf(null, "right"), table.columns.map { it.align })
        assertEquals(listOf("app", "81"), table.rows[1])
        assertEquals("warn", (r.blocks[6] as RecipeBlock.Callout).tone)
        val form = r.blocks[12] as RecipeBlock.Form
        assertEquals(listOf("text", "checkbox", "select"), form.fields.map { it.kind })
        assertEquals(listOf("fast", "slow"), form.fields[2].options)
        assertEquals("hologram", (r.blocks.last() as RecipeBlock.Unknown).type)
        assertIs<RecipeBlock.Heading>(r.blocks[0]).let { assertEquals(1, it.level) }
        // Only pictures beside it are read: never one outside its folder.
        assertEquals(listOf("before.png", "after.png"), Recipes.pictures(r.blocks))
    }

    @Test
    fun answersAndNotesInRecipesWords() {
        val r = Recipes.parse(fixture("recipe.json"))
        val choice = r.blocks.filterIsInstance<RecipeBlock.Choice>().single()
        assertEquals("Ship it?: Not yet", Recipes.choiceReply(choice, "Not yet"))
        val form = r.blocks.filterIsInstance<RecipeBlock.Form>().single()
        assertEquals("Feedback\nYour name: Ada\nLooks good: yes\nhow: (empty)", Recipes.formReply(form, mapOf("who" to " Ada ", "ok" to true)))
        assertEquals("the heading “Coverage”", Recipes.blockName(r.blocks[0]))
        assertEquals("the numbers b3", Recipes.blockName(r.blocks[2]))
        assertEquals("the choice “Ship it?”", Recipes.blockName(choice))
        val bars = Recipes.bars(r.blocks[4] as RecipeBlock.Chart)
        assertEquals(listOf(80.0 / 92, 85.0 / 92, 1.0), bars.map { it.h })
        assertEquals(listOf(0.92, 0.08), Recipes.slices(r.blocks[5] as RecipeBlock.Chart).map { it.third })
        assertEquals("x".repeat(4000), Artifacts.line("x".repeat(5000)))
    }

    @Test
    fun anArtifactPostedAgainFoldsItsEarlierCard() {
        val chat = ShepherdrJson.decodeFromString(
            V2Chat.serializer(),
            """{"key":"p:a","messages":[
              {"id":"m1","who":"agent","text":"First go","artifact":{"id":"wk3n","v":1}},
              {"id":"m2","who":"agent","artifact":{"id":"rn09","v":1}},
              {"id":"m3","who":"agent","text":"With bars by week","artifact":{"id":"wk3n","v":2}}
            ]}""",
        )
        val cards = SpaceChat.items("p:a", chat).filterIsInstance<SpaceChatItem.Artifact>()
        assertEquals(listOf(true, false, false), cards.map { it.later })
    }

    @Test
    fun theClientReadsRecipesAndSendsToTheKeeper() = runBlocking {
        val seen = ArrayList<String>()
        val bodies = ArrayList<String>()
        val t = object : ShepherdrTransport {
            override val canStream = false
            override suspend fun http(method: String, path: String, body: String?, timeoutMs: Int): HttpReply {
                seen += "$method $path"
                body?.let { bodies += it }
                return when {
                    path.startsWith("/api/artifacts/gone") -> HttpReply(404, """{"error":"No recipe here."}""")
                    path.startsWith("/api/artifacts/demo-coverage/recipe") -> HttpReply(200, fixture("recipe.json"))
                    path == "/api/action" -> HttpReply(200, """{"to":"DEMO-4 agent"}""")
                    else -> HttpReply(404, """{"error":"Not found"}""")
                }
            }
            override suspend fun bytes(method: String, path: String, bytes: ByteArray?, type: String?, timeoutMs: Int): BytesReply {
                seen += "$method $path"
                return if (path.contains("before.png")) BytesReply(200, byteArrayOf(1, 2, 3)) else BytesReply(404, ByteArray(0))
            }
            override suspend fun webSocket(path: String): WebSocketLink = throw IOException("none")
            override fun close() {}
        }
        val board = V2WorkBoard(ShepherdrClient(t), "3.1.2")
        val a = snapshot.artifacts!!.first { it.id == "demo-coverage" }
        assertEquals(15, board.recipe(a, 2).blocks.size)
        assertEquals(3, board.artifactFile(a, 2, "before.png")!!.size)
        assertNull(board.artifactFile(a, 2, "after.png"))
        val err = runCatching { board.recipe(a.copy(id = "gone"), 1) }.exceptionOrNull()
        assertIs<com.makefunstuff.herdr.core.shepherdr.ArtifactGone>(err)
        assertEquals(
            listOf(
                "GET /api/artifacts/demo-coverage/recipe?v=2",
                "GET /api/artifacts/demo-coverage/file/before.png?v=2",
                "GET /api/artifacts/demo-coverage/file/after.png?v=2",
                "GET /api/artifacts/gone/recipe?v=1",
            ),
            seen,
        )
        // What you send reaches its keeper, as the web app's actions do.
        assertEquals("DEMO-4 agent", board.artifactSay(a, 2, "Ship it?: Yes", answer = true))
        board.artifactSay(a, 2, "Make the bars blue")
        board.artifactSay(a, 2, "Round these", block = "b3")
        val sent = bodies.map { ShepherdrJson.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("artifact-send", "artifact-say", "artifact-note"), sent.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("demo-coverage", sent[0]["artifact"]!!.jsonPrimitive.content)
        assertEquals("Ship it?: Yes", sent[0]["text"]!!.jsonPrimitive.content)
        assertEquals("2", sent[1]["v"]!!.jsonPrimitive.content)
        assertEquals("Round these", sent[2]["note"]!!.jsonPrimitive.content)
        assertEquals("b3", sent[2]["target"]!!.jsonPrimitive.content)
    }
}
