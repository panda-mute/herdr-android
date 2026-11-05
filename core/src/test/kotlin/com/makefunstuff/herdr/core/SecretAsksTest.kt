package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.home.Home
import com.makefunstuff.herdr.core.home.HomeBoard
import com.makefunstuff.herdr.core.home.Waiting
import com.makefunstuff.herdr.core.shepherdr.CurlTransport
import com.makefunstuff.herdr.core.shepherdr.HttpReply
import com.makefunstuff.herdr.core.shepherdr.ShepherdrClient
import com.makefunstuff.herdr.core.shepherdr.ShepherdrJson
import com.makefunstuff.herdr.core.shepherdr.ShepherdrTransport
import com.makefunstuff.herdr.core.shepherdr.V2Actions
import com.makefunstuff.herdr.core.shepherdr.V2Board
import com.makefunstuff.herdr.core.shepherdr.V2Snapshot
import com.makefunstuff.herdr.core.shepherdr.V2WorkBoard
import com.makefunstuff.herdr.core.shepherdr.WebSocketLink
import com.makefunstuff.herdr.core.ssh.CommandResult
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** An agent's secret ask on Needs you, and how its value goes: once, to the machine's vault, as the web's card sends it. */
class SecretAsksTest {
    /** A value no real vault holds: the tests only look for where it goes. */
    private val value = "demo-value-not-a-secret"

    // As 3.1's service puts them in its snapshot (service/compat.ts secretAsk): an Assistant's ask and a task's.
    private val snap = ShepherdrJson.decodeFromString<V2Snapshot>(
        """
        {"name":"devbox","host":"devbox",
         "places":[{"id":"sp1","name":"herdr-android","machine":"devbox","chat":"p:sp1"}],
         "tasks":[{"id":"HERD-7","name":"Publish the release","status":"need","place":"sp1"}],
         "needs":[
          {"id":"secret:w1:p2:GITHUB_TOKEN","chat":"p:sp1","place":"sp1","at":1,
           "secret":{"id":"secret:w1:p2:GITHUB_TOKEN","name":"GITHUB_TOKEN","why":"To open the pull request",
            "levels":[{"id":"p::sp1","label":"This space"},{"id":"m:","label":"This computer"},{"id":"all","label":"Everywhere"}]}},
          {"id":"secret:w1:p3:NPM_TOKEN","chat":"HERD-7/worker","place":"sp1","task":"HERD-7","at":2,
           "secret":{"id":"secret:w1:p3:NPM_TOKEN","name":"NPM_TOKEN","levels":[{"id":"all","label":"Everywhere"}]}}
         ]}
        """.trimIndent(),
    )

    @Test
    fun eachSecretAskWaitsOnNeedsYouOnce() {
        val work = V2Board.overview(snap)
        // The Assistant's ask is its own item, not "the Assistant asks you" as well.
        assertFalse(work.spaces.single().asks)
        val home = Home.of(emptyList(), listOf(HomeBoard("devbox", "devbox", work)))
        // The task that waits on a secret is that item, not a second card of its own.
        assertEquals(listOf("secret/devbox/secret:w1:p2:GITHUB_TOKEN", "secret/devbox/secret:w1:p3:NPM_TOKEN"), home.needsYou.map { it.key })
        val assistant = assertIs<Waiting.Secret>(home.needsYou[0])
        assertEquals("GITHUB_TOKEN", assistant.ask.name)
        assertEquals("To open the pull request", assistant.ask.why)
        assertEquals(listOf("This space", "This computer", "Everywhere"), assistant.ask.levels.map { it.label })
        assertEquals("herdr-android", assistant.space?.name)
        assertEquals(null, assistant.task)
        assertEquals("HERD-7", assertIs<Waiting.Secret>(home.needsYou[1]).task?.name)
    }

    @Test
    fun keepsTheValueAsTheWebsCardSendsIt() = runBlocking {
        val fake = Recorder { HttpReply(200, """{"ok":true}""") }
        val board = V2WorkBoard(ShepherdrClient(fake))
        val ask = snap.needs[0].secret!!
        assertEquals("Kept for This space", board.keepSecret("p:sp1", ask, "p::sp1", value))
        assertEquals(
            """POST /api/action {"type":"secret","level":"p::sp1","name":"GITHUB_TOKEN","value":"$value","card":{"chat":"p:sp1","id":"secret:w1:p2:GITHUB_TOKEN"}}""",
            fake.sent.single(),
        )
    }

    @Test
    fun aRefusalSaysTheServicesWordsAndNothingIsSentEmpty() = runBlocking {
        // The service refuses with plain words (status 400), never the value.
        val refused = Recorder { HttpReply(400, "That agent isn't here any more.") }
        val ask = snap.needs[0].secret!!
        val e = assertFailsWith<IOException> { V2WorkBoard(ShepherdrClient(refused)).keepSecret("p:sp1", ask, "all", value) }
        assertEquals("That agent isn't here any more.", e.message)

        val none = Recorder { HttpReply(200, """{"ok":true}""") }
        assertFailsWith<IOException> { V2WorkBoard(ShepherdrClient(none)).keepSecret("p:sp1", ask, "all", "") }
        assertTrue(none.sent.isEmpty())
    }

    @Test
    fun overCurlTheValueGoesOnStdinNeverTheCommandLine() = runBlocking {
        var command = ""
        var stdin = ""
        val curl = CurlTransport { c, input, _ ->
            command = c
            stdin = input
            CommandResult(0, "{\"ok\":true}\n@@shepherdr-status@@200", "")
        }
        ShepherdrClient(curl).act(V2Actions.secret("p:sp1", "secret:w1:p2:GITHUB_TOKEN", "GITHUB_TOKEN", "all", value))
        // A command line shows in the machine's process list and its shell's history: the value is only on stdin.
        assertFalse(value in command)
        assertTrue(value in stdin)
    }

    private class Recorder(private val answer: () -> HttpReply) : ShepherdrTransport {
        val sent = mutableListOf<String>()
        override val canStream: Boolean get() = false
        override suspend fun http(method: String, path: String, body: String?, timeoutMs: Int): HttpReply {
            sent += "$method $path ${body.orEmpty()}"
            return answer()
        }
        override suspend fun webSocket(path: String): WebSocketLink = throw IOException("none")
        override fun close() {}
    }
}
