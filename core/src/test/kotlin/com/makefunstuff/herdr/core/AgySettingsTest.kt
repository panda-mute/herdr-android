package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.chat.*
import kotlin.test.*

class AgySettingsTest {
    @Test fun readsCatalogAndRejectsMultilineCommands() {
        val models = AgySettings.models("Fetching available models...\ngemini-4-neon-high\tGemini 4 Neon (High)\n")
        assertEquals("gemini-4-neon-high", models.single().id)
        assertEquals("/model gemini-4-neon-high", AgySettings.modelCommand(models.single().id))
        assertFailsWith<IllegalArgumentException> { AgySettings.modelCommand("test\n/exit") }
    }
    @Test fun recognizesActualEditApprovalWithoutServerBlockedStatus() {
        val screen = """
Create file
──────────────────────────────────────────
/tmp/test.txt  +1
  1  +  TEST

Allow creation of this file?
> 1. Yes, allow creation
  2. No, deny creation

  ↑/↓ Navigate · f full diff
esc to cancel                           Gemini 4 Neon · high
""".trimIndent()
        val p = assertNotNull(AgySettings.prompt(screen))
        assertEquals("Allow creation of this file?", p.question)
        assertEquals(2, p.options.size)
        assertEquals(listOf("down", "enter"), p.keysFor(1))
        assertNull(AgySettings.prompt("> Explain this code\nSome output\n? for shortcuts"))
    }
    @Test fun readsAvailableModesWithoutInventingAuto() {
        val screen = """
Settings
  Agent Mode
  > default (current)
    accept-edits
    plan
  Animation Speed               medium

Keyboard: ↑/↓ Navigate  enter Save  esc Cancel
""".trimIndent()
        val modes = assertNotNull(AgySettings.modes(screen))
        assertEquals(listOf("default (current)", "accept-edits", "plan"), modes.options.map { it.label })
        assertNull(AgySettings.prompt(screen))
    }
    @Test fun modelPickerIsNotAnApprovalAndKeepsItsRows() {
        val screen = "Switch Model\n\n> Gemini 4 Neon (current)\n  Claude Sonnet 5.5\n\nKeyboard: ↑/↓ Navigate  ←/→ Effort  enter Select  esc Go Back"
        assertNull(AgySettings.prompt(screen))
        assertEquals(listOf("down", "enter"), BlockedPrompt.parse(screen)!!.keysFor(1))
    }
    @Test fun requestCountsDoNotInventContextWindowOrAccumulateReplays() {
        val t = AgyTranscript()
        val record = """{"step_index":1,"type":"PLANNER_RESPONSE","model":"claude-sonnet-5-5","input_tokens":10000,"cache_read_tokens":9000,"output_tokens":120}"""
        t.feed(record); t.feed(record)
        assertEquals(10000L, t.use?.used)
        assertEquals(9000L, t.use?.cacheReadTokens)
        assertEquals(120L, t.use?.outputTokens)
        assertNull(t.use?.window)
        assertNull(t.use?.percent)
    }
}
