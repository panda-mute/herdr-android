package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.chat.AskedQuestions
import com.makefunstuff.herdr.core.chat.BlockedPrompt
import com.makefunstuff.herdr.core.chat.ChatItem
import com.makefunstuff.herdr.core.chat.ToolState
import com.makefunstuff.herdr.core.chat.ClaudeTranscript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AskedQuestionsTest {
    // A form of two questions on a phone-wide pane: the question's start is off the top, the descriptions cut.
    private val screen = """
        ←  ☐ Route  ☐ Author  ✔ Submit  →
        the PR go once it's ready?
        ❯ 1. Log in as owner
             Authenticate as the repository…
          2. Fork
             Push the branch to a fork.
          3. Type something.
        ────────────────────────────────────────
          4. Chat about this
        Enter to select · Tab/Arrow keys to navigate · Esc to cancel
    """.trimIndent()

    private val call = """{"type":"assistant","uuid":"u1","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"AskUserQuestion","input":{"questions":[
        {"question":"Which way should the PR go once it's ready?","header":"Route","multiSelect":false,"options":[
          {"label":"Log in as owner","description":"Authenticate as the repository owner, then push to main."},
          {"label":"Fork","description":"Push the branch to a fork."}]},
        {"question":"Who should author it?","header":"Author name","options":[{"label":"Me"},{"label":"Claude"}]}]}}]}}"""

    private fun items(json: String) = ClaudeTranscript().apply { feed(json.replace("\n", "")) }.items()

    @Test
    fun theTranscriptsWordsFillTheCard() {
        val items = items(call)
        assertEquals(listOf("Route", "Author name"), (items.single() as ChatItem.Tool).asks.map { it.header })
        val p = AskedQuestions.fill(BlockedPrompt.parse(screen)!!, items)
        assertEquals("Which way should the PR go once it's ready?", p.question)
        assertEquals("Authenticate as the repository owner, then push to main.", p.options[0].description)
        assertEquals(listOf("Log in as owner", "Fork", "Type something.", "Chat about this"), p.options.map { it.label })
        assertEquals(listOf("Route", "Author name", "Submit"), p.steps.map { it.label })
    }

    @Test
    fun theScreenStaysWhereTheyDiffer() {
        val prompt = BlockedPrompt.parse(screen)!!
        // Other options, an answered call, no call: as read.
        assertSame(prompt, AskedQuestions.fill(prompt, items(call.replace("\"Fork\"", "\"Branch\""))))
        val answered = items(call).map { (it as ChatItem.Tool).copy(state = ToolState.Done) }
        assertSame(prompt, AskedQuestions.fill(prompt, answered))
        assertSame(prompt, AskedQuestions.fill(prompt, emptyList()))
    }
}
