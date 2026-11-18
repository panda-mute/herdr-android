package com.makefunstuff.herdr.ui.terminal

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalImeTest {
    @Test
    fun completingAWordReplacesOnlyThePendingText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val sent = mutableListOf<String>()
            val view = TerminalView(instrumentation.targetContext).apply { onInput = { sent.add(it) } }
            val input = view.onCreateInputConnection(EditorInfo())
            input.setComposingText("ter", 1)
            assertEquals("ter", input.getTextBeforeCursor(10, 0).toString())
            input.setComposingText("term🙂", 1)
            input.deleteSurroundingTextInCodePoints(1, 0)
            assertEquals("term", input.getTextBeforeCursor(10, 0).toString())
            assertEquals(emptyList<String>(), sent)
            input.commitText("terminal", 1)
            input.finishComposingText()
            input.commitText(" ", 1)
            assertEquals(listOf("terminal", " "), sent)
        }
    }

    @Test
    fun enterAfterKeyboardRestartSendsThePendingWordFirst() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val sent = mutableListOf<String>()
            val view = TerminalView(instrumentation.targetContext).apply { onInput = { sent.add(it) } }
            val old = view.onCreateInputConnection(EditorInfo())
            old.setComposingText("hello", 1)
            val current = view.onCreateInputConnection(EditorInfo())
            old.closeConnection()
            current.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            current.finishComposingText()
            assertEquals(listOf("hello", "\r"), sent)
        }
    }
}
