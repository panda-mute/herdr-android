package com.makefunstuff.herdr.ui.design

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.makefunstuff.herdr.HerdrApp
import com.makefunstuff.herdr.data.DraftStore
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.rememberDraft
import com.makefunstuff.herdr.ui.theme.HerdrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The chat composer: Send, Stop, sending, keys, drafts, attaching. */
@OptIn(ExperimentalTestApi::class)
class ComposerTest {
    @get:Rule val rule = createComposeRule()

    private fun SemanticsNodeInteraction.atLeast48() {
        val size = fetchSemanticsNode().size
        val min = with(rule.density) { 48.dp.roundToPx() }
        assertTrue("target is ${size.width}x${size.height}px, needs $min", size.height >= min && size.width >= min)
    }

    private val field get() = rule.onNode(hasSetTextAction())

    @Test fun agentComposerTakesTheAttachButton() {
        var picks = 0
        rule.setContent {
            HerdrTheme {
                ChatComposer("", {}, "Message claude…", onSend = {}, leading = { ComposerIconButton(HerdrIcons.Plus, "Attach files") { picks++ } })
            }
        }
        rule.onNodeWithContentDescription("Attach files").performClick()
        assertEquals(1, picks)
    }

    @Test fun typingGoesAfterAnAttachedFilesMention() {
        var draft by mutableStateOf("")
        rule.setContent { HerdrTheme { ChatComposer(draft, { draft = it }, "Message claude…", onSend = {}) } }
        field.performTextInput("")
        draft = com.makefunstuff.herdr.core.host.Attachments.withMention(draft, "/h/a.png")
        rule.waitForIdle()
        field.performTextInput("what is this?")
        assertEquals("@/h/a.png what is this?", draft)
    }

    @Test fun sendingKeepsTheTextAndSendsOnce() {
        var sent = 0
        rule.setContent {
            HerdrTheme { ChatComposer("Fix the build", {}, "Message claude…", onSend = { sent++ }, sending = true, status = "Sending…") }
        }
        rule.onNodeWithText("Fix the build").assertIsDisplayed()
        rule.onNodeWithContentDescription("Sending…").assertIsNotEnabled().atLeast48()
        rule.onNodeWithText("Sending…").assertIsDisplayed()
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        assertEquals(0, sent)
    }

    /** A phone that hears "run the tests again" once it is told to stop. */
    private class FakeMic(val allowed: Boolean = true) : com.makefunstuff.herdr.core.chat.VoiceRecorder.Mic {
        var asked = 0
        override fun allowed() = allowed
        override fun ask() { asked++ }
        override fun settings() {}
        override fun start() = true
        override fun stop(): String? = "/files/voice/a.m4a"
        override fun cancel() {}
        override fun level() = 0.5f
    }

    @Test fun aTappedVoiceMessageIsSentAndTheDraftStays() {
        var draft by mutableStateOf("half a thought")
        var sent = 0
        var now = 0L
        val recorded = mutableListOf<Long>()
        val recorder = com.makefunstuff.herdr.core.chat.VoiceRecorder(FakeMic(), { now }) { _, millis -> recorded += millis }
        rule.setContent { HerdrTheme { ChatComposer(draft, { draft = it }, "Message claude…", onSend = { sent++ }, voice = recorder) } }
        // A tap starts it: the box gives way to the recording, and an X cancels.
        rule.onNodeWithContentDescription("Record a voice message").atLeast48()
        rule.onNodeWithContentDescription("Record a voice message").performClick()
        rule.onNodeWithText("Recording a voice message").assertIsDisplayed()
        rule.onNodeWithText("Tap to send").assertIsDisplayed()
        rule.onNodeWithContentDescription("Cancel voice message").assertIsDisplayed()
        // Send sends the recording, not the draft.
        now = 4_000
        rule.onNodeWithContentDescription("Send voice message").performClick()
        rule.waitForIdle()
        assertEquals(listOf(4_000L), recorded)
        assertEquals(0, sent)
        assertEquals("half a thought", draft)
        rule.onNodeWithText("Recording a voice message").assertDoesNotExist()
        // The mic's second tap sends too; the X drops it.
        rule.onNodeWithContentDescription("Record a voice message").performClick()
        now = 9_000
        rule.onNodeWithContentDescription("Send voice message").performClick()
        rule.onNodeWithContentDescription("Record a voice message").performClick()
        rule.onNodeWithContentDescription("Cancel voice message").performClick()
        rule.waitForIdle()
        assertEquals(listOf(4_000L, 5_000L), recorded)
    }

    @Test fun aRefusedMicrophoneIsExplained() {
        val mic = FakeMic(allowed = false)
        val recorder = com.makefunstuff.herdr.core.chat.VoiceRecorder(mic, { 0L }) { _, _ -> }
        rule.setContent { HerdrTheme { ChatComposer("", {}, "Message claude…", onSend = {}, voice = recorder) } }
        rule.onNodeWithContentDescription("Record a voice message").performClick()
        assertEquals(1, mic.asked)
        // While Android asks, the composer says why.
        rule.onNodeWithText("Allow the microphone").assertIsDisplayed()
        recorder.answered(granted = false, forever = true)
        rule.onNodeWithText("The microphone is off").assertIsDisplayed()
        rule.onNodeWithText("Open settings").assertIsDisplayed()
        rule.onNodeWithText("Not now").performClick()
        rule.onNodeWithText("The microphone is off").assertDoesNotExist()
    }

    @Test fun stopOnlyWhileNothingIsTyped() {
        var draft by mutableStateOf("")
        rule.setContent {
            HerdrTheme { ChatComposer(draft, { draft = it }, "Message claude…", onSend = {}, stop = {}) }
        }
        rule.onNodeWithContentDescription("Stop").atLeast48()
        field.performTextInput("wait")
        rule.onNodeWithContentDescription("Send").assertIsEnabled()
    }

    @Test fun enterIsANewLineAndCtrlEnterSends() {
        var draft by mutableStateOf("")
        var sent = 0
        rule.setContent {
            HerdrTheme { ChatComposer(draft, { draft = it }, "Message claude…", onSend = { sent++ }) }
        }
        field.performTextInput("first line")
        field.performKeyInput { pressKey(Key.Enter) }
        field.performTextInput("second line")
        assertEquals("first line\nsecond line", draft)
        assertEquals(0, sent)
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        assertEquals(1, sent)
        assertEquals("first line\nsecond line", draft)
    }

    @Test fun longDraftAtDoubleFontScaleKeepsSendInReach() {
        val long = (1..30).joinToString("\n") { "line $it of a long pasted log" }
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                HerdrTheme { ChatComposer(long, {}, "Message claude…", onSend = {}) }
            }
        }
        rule.onNodeWithContentDescription("Send").assertIsDisplayed().assertIsEnabled().atLeast48()
    }

    @Test fun draftOutlivesLeavingTheChat() {
        val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HerdrApp).container
        val key = DraftStore.agent("test-machine", "w9:p9")
        container.drafts.put(key, "")
        var open by mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                HerdrTheme {
                    if (open) {
                        var draft by rememberDraft(key)
                        ChatComposer(draft, { draft = it }, "Message claude…", onSend = {})
                    }
                }
            }
        }
        field.performTextInput("half a thought")
        open = false
        rule.waitForIdle()
        assertEquals("half a thought", container.drafts.get(key))
        open = true
        rule.onNodeWithText("half a thought").assertIsDisplayed()
        container.drafts.put(key, "")
    }
}
