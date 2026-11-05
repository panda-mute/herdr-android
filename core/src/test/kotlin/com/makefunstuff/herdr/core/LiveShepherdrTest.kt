package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.BoardApi
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.hc.HcMapping
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.keys.KeyImport
import com.makefunstuff.herdr.core.shepherdr.CurlTransport
import com.makefunstuff.herdr.core.shepherdr.ShepherdrClient
import com.makefunstuff.herdr.core.shepherdr.SpaceChat
import com.makefunstuff.herdr.core.shepherdr.V2WorkBoard
import com.makefunstuff.herdr.core.ssh.Machine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shepherdr 3 on a real machine, over SSH as the app reaches it: detection, the token, the port forward, the
 * snapshot, a chat through the live link. Read only unless HERDR_LIVE_SEND=1. Runs only with HERDR_LIVE_KEY and
 * HERDR_LIVE_SHEPHERDR (the machine, `user@host[:port]`) set.
 */
class LiveShepherdrTest {
    private val keyPath = System.getenv("HERDR_LIVE_KEY")
    private val target = System.getenv("HERDR_LIVE_SHEPHERDR")

    private fun machine(): Machine {
        val (user, host, port) = Machine.parseTarget(target!!)!!
        return Machine("live", "live", host, port, user ?: "root", session = System.getenv("HERDR_LIVE_SESSION") ?: "default")
    }

    private fun live(block: suspend (HostSession) -> Unit) {
        val identity = KeyImport.fromPrivateKey(File(keyPath!!).readText(), "test")
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val session = HostSession(machine(), { identity }, { null }, scope)
            try {
                session.start()
                block(session)
            } finally {
                session.stop()
                scope.cancel()
            }
        }
    }

    /**
     * Chatting with a space's Assistant as the Assistant screen does: a message sent goes in and comes back with its
     * id, and the Assistant replies. Writes into that space's chat, so it runs only with HERDR_LIVE_SEND=1 as well
     * (HERDR_LIVE_SPACE: the space's name; the first space else).
     */
    @Test
    fun chatsWithAnAssistantOverSsh() {
        assumeTrue(keyPath != null && target != null && System.getenv("HERDR_LIVE_SEND") == "1", "set HERDR_LIVE_KEY, HERDR_LIVE_SHEPHERDR and HERDR_LIVE_SEND=1 to run")
        live { session ->
            withTimeout(60_000) { session.state.first { it.online && it.boardChecked } }
            val board = session.awaitWorkBoard()
            val spaces = board.overview().spaces
            val space = spaces.firstOrNull { it.name == System.getenv("HERDR_LIVE_SPACE") } ?: spaces.first()
            val cid = "herd8-live-${System.currentTimeMillis()}"
            board.say(space.id, "This is an automated check from the herdr-android app's tests, not the person. Reply with exactly: ok $cid", cid = cid)
            val seen = mutableSetOf<String>()
            withTimeout(240_000) {
                board.coordinatorThreads(space.id).first { t ->
                    if (t.v2!!.messages.any { it.cid == cid }) seen += "in"
                    if (t.working) seen += "working"
                    SpaceChat.items(space.id, t.v2!!).any { it is com.makefunstuff.herdr.core.shepherdr.SpaceChatItem.Words && !it.you && it.text.contains(cid) }
                }
            }
            println("seen before the reply: $seen")
        }
    }

    /** Shepherdr 3's agents on the machine's agents list once each, called by their task or space, beside its board. Read only. */
    @Test
    fun seesShepherdrsAgentsBesideItsBoardOverSsh() {
        assumeTrue(keyPath != null && target != null, "set HERDR_LIVE_KEY and HERDR_LIVE_SHEPHERDR to run")
        live { session ->
            val s = withTimeout(60_000) { session.state.first { it.online && it.boardChecked && it.agentsKnown } }
            assumeTrue(s.boardApi is BoardApi.V2, "Shepherdr 3 runs there")
            val agents = s.view!!.agents
            val ids = agents.map { it.paneId }
            assertEquals(ids.distinct(), ids, "each agent once")
            val ours = agents.filter { it.shepherdrTask != null || it.shepherdrSpace != null }
            println("its agents: " + ours.joinToString { "${it.name}:${it.agentStatusRaw}" })
            assertTrue(ours.isNotEmpty())
            val board = session.awaitWorkBoard().overview()
            val working = board.tasks.filter { it.status == WorkStatus.Work && it.id.none { c -> c == ':' } }.map { it.id }
            println("working tasks: $working")
            // Every working task's agent here is on the list, by its task.
            assertTrue(working.all { id -> ours.any { it.shepherdrTask == id } }, "working tasks' agents: $working")
            // Every space's Assistant that runs is too.
            println("assistants: " + ours.filter { it.shepherdrSpace != null }.joinToString { "${it.name}→${it.shepherdrSpace}" })
            assertTrue(ours.filter { it.shepherdrSpace != null }.all { a -> board.spaces.any { it.id == a.shepherdrSpace } })
            val port = session.awaitAgent(ours.first().paneId)
            assertTrue(port.read(ours.first().paneId, 20, visible = true, ansi = false).isNotEmpty())
            assertTrue(HcMapping.address(ours.first().paneId) != null)
        }
    }

    @Test
    fun readsARealShepherdrOverSsh() {
        assumeTrue(keyPath != null && target != null, "set HERDR_LIVE_KEY and HERDR_LIVE_SHEPHERDR to run")
        live { session ->
            val s = withTimeout(60_000) { session.state.first { it.online && it.boardChecked } }
            println("shepherdr: ${s.shepherdrVersion} · api: ${s.boardApi}")
            assertTrue(s.boardApi is BoardApi.V2, "Shepherdr 3 answers there")

            val client = session.awaitShepherdr()
            assertTrue(client.canStream, "reached through the port forward, not curl")
            assertEquals(3, client.version().major)
            // The same forward is kept for the next call.
            assertTrue(session.awaitShepherdr().canStream)

            val board = session.awaitWorkBoard()
            assertTrue(board is V2WorkBoard)
            val o = board.overview()
            println("spaces: " + o.spaces.joinToString { "${it.name}${if (it.needsYou) "!" else ""}" })
            println("tasks: " + o.tasks.filter { it.status != WorkStatus.Done }.joinToString { "${it.name}:${it.status.label}${it.left?.let { l -> " $l" }.orEmpty()}${it.progress?.let { p -> " $p%" }.orEmpty()}" })
            println("needs you: ${o.needs.size}, artifacts: ${o.artifacts?.size}")
            assertTrue(o.spaces.isNotEmpty())
            assertTrue(o.spaces.all { it.chat.startsWith("p:") })

            val space = o.spaces.first()
            val thread = board.coordinator(space.id)
            println("${space.name} chat: ${thread.lines.size} lines, working ${thread.working}, ${thread.cards.size} cards, notice ${thread.v2?.notice}")
            assertEquals(space.chat, thread.v2!!.key)

            o.tasks.firstOrNull { it.chat.isNotEmpty() }?.let { t ->
                val d = board.details(t)
                println("${t.name}: brief ${d.brief.length} chars, ${d.cards.size} open cards, branch ${d.branch}")
                assertEquals(t.id, d.task.id)
                val chat = board.taskThreads(t).first()
                println("${t.name} chat: ${chat.lines.size} lines, doing ${chat.v2?.doing}, notice ${chat.v2?.notice}")
            }

            // Where the SSH server won't forward: curl for the API, the machine's bun for the snapshot and a chat.
            val conn = session.awaitConnection()
            val curl = V2WorkBoard(ShepherdrClient(CurlTransport { c, stdin, t -> conn.runWithInput(c, CurlTransport.stdin(stdin), t) }))
            assertEquals(o.tasks.map { it.id }.toSet(), curl.overview().tasks.map { it.id }.toSet())
            val viaBun = curl.coordinator(space.id)
            println("${space.name} chat without a forward: ${viaBun.lines.size} lines")
            assertEquals(space.chat, viaBun.v2!!.key)
        }
    }
}
