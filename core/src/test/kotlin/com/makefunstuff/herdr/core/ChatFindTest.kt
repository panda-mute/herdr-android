package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.shepherdr.ChatFind
import com.makefunstuff.herdr.core.shepherdr.Reactions
import com.makefunstuff.herdr.core.shepherdr.Reactions.Pill
import com.makefunstuff.herdr.core.shepherdr.SpaceChat
import com.makefunstuff.herdr.core.shepherdr.SpaceChatItem
import com.makefunstuff.herdr.core.shepherdr.V2Actions
import com.makefunstuff.herdr.core.shepherdr.V2Event
import com.makefunstuff.herdr.core.shepherdr.V2Events
import com.makefunstuff.herdr.core.shepherdr.V2Reaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatFindTest {
    private fun chat(json: String) = (V2Events.parse("""{"type":"chat","chat":$json}""") as V2Event.Chat).chat

    private val items = SpaceChat.items(
        "sp", chat(
            """{"key":"p:sp","messages":[
              {"id":"m1","who":"you","text":"The upload test is flaky again"},
              {"id":"n1","who":"agent","news":{"task":"AND-14","ref":"AND-14","mark":"started","what":"started","text":"Fix the flaky upload test"}},
              {"id":"m2","who":"agent","text":"On it.","reactions":[{"emoji":"👍","by":"me"},{"emoji":"👀","by":"both"}]},
              {"id":"n2","who":"agent","news":{"task":"AND-13","ref":"AND-13","mark":"ready","what":"is ready","text":"Card stays put","accept":true}}
            ]}""",
        ),
    )

    @Test
    fun findsWordsInAnyCaseOldestFirst() {
        assertEquals(listOf("m1", "n1"), ChatFind.matches(items, "FLAKY"))
        assertEquals(listOf("n2"), ChatFind.matches(items, "and-13"))
        assertEquals(emptyList(), ChatFind.matches(items, "  "))
        assertEquals("2 of 7", ChatFind.count(1, 7))
        assertEquals("No matches", ChatFind.count(0, 0))
    }

    @Test
    fun newsLinesKeepTheirParts() {
        val n = items.filterIsInstance<SpaceChatItem.Update>()
        assertEquals(listOf("AND-14", "AND-13"), n.map { it.task })
        assertTrue(n.all { it.news })
        assertEquals("is ready" to "Card stays put", n[1].what to n[1].detail)
        assertEquals(WorkStatus.Ready, n[1].status)
        assertTrue(n[1].accept)
        // What the voice and older screens read is still the whole line.
        assertEquals("started · Fix the flaky upload test", n[0].text)
    }

    @Test
    fun pillsCountYoursAndTheAgents() {
        val w = items.filterIsInstance<SpaceChatItem.Words>().first { it.id == "m2" }
        assertEquals(listOf(Pill("👍", 1, true), Pill("👀", 2, true)), Reactions.pills(w.reactions))
        assertEquals(listOf(Pill("👍", 1, false)), Reactions.pills(listOf(V2Reaction("👍", "them"))))
    }

    @Test
    fun aReactionSentShowsUntilTheChatHasIt() {
        val agents = listOf(V2Reaction("👍", "them"))
        // Added: faded until it comes back; taken back: gone at once.
        assertEquals(listOf(Pill("👍", 2, true, pending = true), Pill("✅", 1, true, pending = true)), Reactions.pills(agents, mapOf("👍" to true, "✅" to true)))
        assertEquals(emptyList(), Reactions.pills(listOf(V2Reaction("👍", "me")), mapOf("👍" to false)))
        assertEquals(listOf(Pill("👍", 1, false)), Reactions.pills(listOf(V2Reaction("👍", "both")), mapOf("👍" to false)))
        // Once the chat shows it, it no longer waits.
        assertEquals(mapOf("✅" to true), Reactions.stillSent(listOf(V2Reaction("👍", "both")), mapOf("👍" to true, "✅" to true)))
        assertEquals(emptyMap(), Reactions.stillSent(agents, mapOf("👍" to false)))
    }

    @Test
    fun reactAndEarlierAreShepherdrsActions() {
        assertEquals("""{"type":"react","chat":"p:sp","message":"m2","emoji":"👍"}""", V2Actions.react("p:sp", "m2", "👍").toString())
        assertEquals("""{"type":"earlier","chat":"AND-14/worker"}""", V2Actions.earlier("AND-14/worker").toString())
    }
}
