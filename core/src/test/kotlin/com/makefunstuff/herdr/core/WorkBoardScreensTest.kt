package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.BoardTone
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.board.WorkTasks
import com.makefunstuff.herdr.core.shepherdr.HttpReply
import com.makefunstuff.herdr.core.shepherdr.ShepherdrClient
import com.makefunstuff.herdr.core.shepherdr.ShepherdrTransport
import com.makefunstuff.herdr.core.shepherdr.V2Board
import com.makefunstuff.herdr.core.shepherdr.V2WorkBoard
import com.makefunstuff.herdr.core.shepherdr.WebSocketLink
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What the Board and task screens add to the work board: close, a task through the coordinator, the colours. */
class WorkBoardScreensTest {
    /** Answers `POST /api/action` with [answer] and keeps each body sent. */
    private class Fake(private val answer: (String) -> String) : ShepherdrTransport {
        val sent = mutableListOf<String>()
        override val canStream: Boolean get() = false
        override suspend fun http(method: String, path: String, body: String?, timeoutMs: Int): HttpReply {
            sent += "$method $path ${body.orEmpty()}"
            return HttpReply(200, answer(body.orEmpty()))
        }
        override suspend fun webSocket(path: String): WebSocketLink = throw IOException("none")
        override fun close() {}
    }

    private val ready = WorkTask("HA-2", "HA-2", "Board and task screens on v2", "p:m:herdr-android", WorkStatus.Ready, pr = "https://github.com/o/r/pull/60")

    @Test
    fun closesATaskAndSaysWhatItHoldsWhenRefused() = runBlocking {
        val ok = Fake { """{"ok":true,"kept":[]}""" }
        assertEquals("HA-2 is done, nothing merged", V2WorkBoard(ShepherdrClient(ok)).close(ready))
        assertEquals("""POST /api/action {"type":"close","chat":"HA-2/worker"}""", ok.sent.single())

        val done = Fake { """{"ok":false,"error":"HA-2 is Done already (merged)."}""" }
        assertEquals("HA-2 is Done already (merged).", assertFailsWith<IOException> { V2WorkBoard(ShepherdrClient(done)).close(ready) }.message)
    }

    @Test
    fun addsATaskByAskingTheSpacesAssistant() = runBlocking {
        val fake = Fake { "{}" }
        val said = V2WorkBoard(ShepherdrClient(fake)).addTask("sp-app", "  Dark mode for the brief ", "Follow DESIGN.md.\n")
        assertEquals("Sent to the Assistant: it adds the task", said)
        assertEquals(
            """POST /api/action {"type":"send","chat":"p:sp-app","text":"Please add a task: Dark mode for the brief\n\nFollow DESIGN.md.","human":true}""",
            fake.sent.single(),
        )
        assertEquals("Please add a task: Just a title", V2Board.addRequest("Just a title", " "))
    }

    @Test
    fun eachStatusHasTheBoardsColour() {
        assertEquals(
            listOf(BoardTone.Red, BoardTone.Green, BoardTone.Blue, BoardTone.Amber, BoardTone.Pink, BoardTone.Grey, BoardTone.Teal),
            WorkStatus.entries.map { it.tone },
        )
    }

    @Test
    fun aSpaceWhoseAssistantAsksSaysSo() {
        val s = com.makefunstuff.herdr.core.shepherdr.V2Snapshot(
            places = listOf(com.makefunstuff.herdr.core.shepherdr.V2Place("p:m:a", "a"), com.makefunstuff.herdr.core.shepherdr.V2Place("p:m:b", "b")),
            tasks = listOf(com.makefunstuff.herdr.core.shepherdr.V2Task("A-1", status = com.makefunstuff.herdr.core.shepherdr.V2TaskStatus.Ready, place = "p:m:a")),
            asks = mapOf("p:m:a" to "ask:1"),
        )
        val o = V2Board.overview(s)
        // Its Assistant's question shows even though a task of it waits for you too.
        assertEquals(listOf(true, false), o.spaces.map { it.asks })
        assertEquals(listOf(true, false), o.spaces.map { it.needsYou })
    }
}
