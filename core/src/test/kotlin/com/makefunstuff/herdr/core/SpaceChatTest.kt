package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.chat.VoiceNote
import com.makefunstuff.herdr.core.shepherdr.OutgoingLine
import com.makefunstuff.herdr.core.shepherdr.ShepherdrText
import com.makefunstuff.herdr.core.shepherdr.SpaceChat
import com.makefunstuff.herdr.core.shepherdr.SpaceChatItem
import com.makefunstuff.herdr.core.shepherdr.V2Board
import com.makefunstuff.herdr.core.shepherdr.V2Event
import com.makefunstuff.herdr.core.shepherdr.V2Events
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpaceChatTest {
    private fun fixture(name: String) = javaClass.getResource("/shepherdr/$name")!!.readText()

    private fun chat(json: String) = (V2Events.parse("""{"type":"chat","chat":$json}""") as V2Event.Chat).chat

    /** A task's chat as Shepherdr 3's service sends it (`service/compat.ts` chat()): words, then a run of tool calls. */
    private val live = """{"key":"DEMO-1/worker","working":false,"cli":"claude","top":true,"messages":[
          {"id":"u1","who":"you","at":1793820000000,"text":"Read the v3 API and list its routes."},
          {"id":"a1","who":"agent","at":1793820005000,"text":"Reading it now."},
          {"id":"t1","who":"agent","at":1793820080605,"activity":{"id":"t1","at":1793820000000,"end":1793820080605,"steps":7,"summary":"Read 5 files, ran 2 commands",
            "rows":[{"key":"r1","kind":"read","text":"Inspected v3 API routes and types"},{"key":"r2","kind":"run","text":"Ran bun test"}]}},
          {"id":"a2","who":"agent","at":1793820090000,"text":"Fourteen routes: `/api/health`, `/api/action`…"}
        ]}"""

    @Test
    fun shepherdrsLinesAndAgentTagsAreNoOnesWords() {
        assertEquals("", ShepherdrText.clean("""<shepherdr><news task="HA-1" event="merged">HA-1 merged</news></shepherdr>"""))
        assertEquals("Merged it.", ShepherdrText.clean("""<shepherdr><go>Go</go></shepherdr>""" + "\nMerged it."))
        assertEquals("", ShepherdrText.clean("<no-reply>heartbeat: nothing to do</no-reply>"))
        assertEquals("", ShepherdrText.clean("`<no-reply>x</no-reply>`"))
        assertEquals("Yes, on it.", ShepherdrText.clean("""<react emoji="👀" to="Can you?"/>""" + "\n" + """<reply to="Can you?"/>""" + "\nYes, on it."))
        // Words that only mention a tag stay.
        assertEquals("Use <reply to=\"…\"/> to quote", ShepherdrText.clean("Use <reply to=\"…\"/> to quote"))
    }

    @Test
    fun theLiveChatAsTheScreenShowsIt() {
        val c = chat(live)
        val items = SpaceChat.items(c.key, c)
        // The turn's tool steps show nothing.
        assertEquals(3, items.size)
        assertTrue(items.all { it is SpaceChatItem.Words })
        assertEquals(listOf(true, false, false), items.map { (it as SpaceChatItem.Words).you })
    }

    @Test
    fun cardsQuestionsFilesAndArtifacts() {
        val c = chat(
            """{"key":"p:m:~","messages":[
              {"id":"c1","who":"agent","text":"HA-1 is ready to merge","card":{"id":"c1","task":"HA-1","status":"ready","text":"is ready to merge","pr":{"number":59,"url":"https://x/59"}}},
              {"id":"c2","who":"agent","text":"HA-9 needs you","card":{"id":"c2","task":"HA-9","status":"need","text":"needs you: Plan to approve",
                "ask":{"chat":"HA-9/Agent","open":true,"questions":[{"id":"q9","title":"Plan for HA-9","kind":"Plan","steps":["a","b"],"choices":[{"label":"Approve"},{"label":"Send back"}],"noteRequiredFor":1,"to":"HA-9"}]}}},
              {"id":"c3","who":"agent","text":"main is red","card":{"id":"c3","red":"main","text":"main is red on GitHub"}},
              {"id":"m4","who":"agent","text":"CI failed once.","question":{"id":"f1","title":"Rerun CI?","choices":[{"label":"Rerun it"},{"label":"Skip"}],"to":"x"}},
              {"id":"m5","who":"agent","question":{"id":"f0","title":"Old?","choices":[{"label":"Yes"},{"label":"No"}],"to":"x","sent":1}},
              {"id":"m6","who":"you","answered":"Answered “Old?”"},
              {"id":"m7","who":"you","text":"Here's the log","file":{"name":"log.txt","path":"~/.shepherdr/inbox/log.txt"},"newSession":true},
              {"id":"m8","who":"agent","text":"The board","artifact":{"id":"wk3n","v":2}},
              {"id":"m9","who":"agent","text":"<no-reply>news</no-reply>"},
              {"id":"m10","who":"you","text":"Yes","reply":{"id":"m4","who":"agent","text":"CI failed once."},"reactions":[{"emoji":"👍","by":"Coordinator"}]}
            ]}""",
        )
        val items = SpaceChat.items(c.key, c)
        val ready = items[0] as SpaceChatItem.Update
        assertEquals("HA-1", ready.task)
        assertEquals(WorkStatus.Ready, ready.status)
        assertEquals("is ready to merge", ready.text)
        assertEquals(59, ready.pr!!.number)
        val need = items[1] as SpaceChatItem.Update
        // Its task's plan, answered from this space.
        assertEquals("q9", need.asks.single().question.id)
        assertEquals("HA-9/Agent", need.asks.single().chat)
        assertEquals("p:m:~", need.asks.single().space)
        val red = items[2] as SpaceChatItem.Update
        assertEquals("main", red.red)
        assertNull(red.status)
        val q = items[3] as SpaceChatItem.Question
        assertEquals("CI failed once.", q.intro)
        assertEquals("p:m:~", q.card.chat)
        assertNull(q.answer)
        assertEquals("No", (items[4] as SpaceChatItem.Question).answer)
        assertEquals("Answered “Old?”", (items[5] as SpaceChatItem.Note).text)
        assertTrue(items[6] is SpaceChatItem.NewSession)
        assertEquals("Here's the log", (items[7] as SpaceChatItem.Words).text)
        assertEquals("log.txt", (items[8] as SpaceChatItem.File).name)
        assertEquals("wk3n", (items[9] as SpaceChatItem.Artifact).artifact)
        // m9 says nothing.
        val yes = items[10] as SpaceChatItem.Words
        assertEquals("m4", yes.reply!!.id)
        assertEquals("👍", yes.reactions.single().emoji)
        assertEquals(11, items.size)
        // The thread's lines leave the hidden reply out too.
        assertTrue(V2Board.thread(c.key, c).lines.none { it.text.contains("no-reply") })
    }

    @Test
    fun whatYouSentWaitsUntilItsMessageComesBack() {
        val before = chat("""{"key":"k","messages":[{"id":"m1","who":"agent","text":"Hi"}]}""")
        val out = listOf(OutgoingLine("c1", "Add a task"))
        val waiting = SpaceChat.items("k", before, out).last() as SpaceChatItem.Words
        assertEquals("cid:c1", waiting.id)
        assertTrue(waiting.waiting && waiting.you)
        assertEquals(out, SpaceChat.stillOutgoing(before, out))

        val after = chat("""{"key":"k","messages":[{"id":"m1","who":"agent","text":"Hi"},{"id":"m2","who":"you","text":"Add a task","cid":"c1"}]}""")
        assertEquals(2, SpaceChat.items("k", after, out).size)
        assertTrue(SpaceChat.stillOutgoing(after, out).isEmpty())

        val failed = chat("""{"key":"k","messages":[],"failed":[{"cid":"c1","error":"The coordinator isn't running"}]}""")
        val f = SpaceChat.items("k", failed, out).single() as SpaceChatItem.Words
        assertEquals("The coordinator isn't running", f.failed)
        assertTrue(!f.waiting)
    }

    @Test
    fun voiceFilesAreVoiceMessagesAndYoursOnThePhoneComeLast() {
        val c = chat(
            """{"key":"home","messages":[
              {"id":"m1","who":"you","file":{"name":"voice-message-0m07s.m4a","path":"/inbox/a-voice-message-0m07s.m4a"}},
              {"id":"m2","who":"you","file":{"name":"shot.png","path":"/inbox/b-shot.png"}}
            ]}""",
        )
        val sent = VoiceNote("n1", "k", "/phone/1.m4a", 7_000, 1, remote = "/inbox/a-voice-message-0m07s.m4a", sent = true)
        val going = VoiceNote("n2", "k", "/phone/2.m4a", 3_000, 2)
        val failed = VoiceNote("n3", "k", "/phone/3.m4a", 4_000, 3, remote = "/inbox/c-voice-message-0m04s.m4a", error = "offline")
        // Sent a while ago and never shown (the chat moved on): no longer waits at the end.
        val old = VoiceNote("n4", "k", "/phone/4.m4a", 4_000, 4, remote = "/inbox/d-voice-message-0m04s.m4a", sent = true, sentAt = 10)
        val arriving = VoiceNote("n5", "k", "/phone/5.m4a", 4_000, 5, remote = "/inbox/e-voice-message-0m04s.m4a", sent = true, sentAt = 100_000)
        val items = SpaceChat.items("home", c, voice = listOf(sent, going, failed, old, arriving), now = 120_000)
        assertEquals(listOf("m1#file", "m2#file", "voice:n2", "voice:n3", "voice:n5"), items.map { it.id })
        val v = items[0] as SpaceChatItem.Voice
        assertEquals(7_000L, v.millis)
        // Played from the phone: the one it was copied as.
        assertEquals(sent, v.note)
        assertTrue(items[1] is SpaceChatItem.File)
        assertEquals(going, (items[2] as SpaceChatItem.Voice).note)
    }

    @Test
    fun aTasksChatFoldsEachTurnsStepsToOneLine() {
        val c = chat(live)
        // The Assistant's chat leaves them out; a task's agent chat shows each turn's as one line.
        assertEquals(3, SpaceChat.items(c.key, c).size)
        val items = SpaceChat.items(c.key, c, steps = true)
        assertEquals(4, items.size)
        val s = items[2] as SpaceChatItem.Steps
        assertEquals("Inspected v3 API routes and types", s.rows.first())
        assertEquals(80_605L, s.millis)
        assertEquals("1m", SpaceChat.took(s.millis!!))
        assertTrue(s.steps >= s.rows.size)
        // A turn still at work has no length yet; one without steps shows nothing.
        val live = chat("""{"key":"HA-1/Agent","messages":[
              {"id":"a1","who":"agent","activity":{"id":"t1","at":1,"rows":[{"key":"k","kind":"edit","text":"Edited app.ts","steps":["s1"]}],"steps":3,"more":1}},
              {"id":"a2","who":"agent","activity":{"id":"t2","at":1,"rows":[],"steps":0}}
            ]}""")
        val one = SpaceChat.items(live.key, live, steps = true).single() as SpaceChatItem.Steps
        assertNull(one.millis)
        assertEquals(listOf("Edited app.ts"), one.rows)
        assertEquals(1, one.more)
        assertEquals("40s", SpaceChat.took(40_000))
        assertEquals("1h 5m", SpaceChat.took(3_900_000))
    }
}
