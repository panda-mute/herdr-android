package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.chat.Dictation
import com.makefunstuff.herdr.core.chat.Dictation.State
import com.makefunstuff.herdr.core.chat.DictationCheck
import com.makefunstuff.herdr.core.chat.MachineEars
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Talk to type in the composer: listening, Done writing it down into the box, cancelling, and what goes wrong. */
@OptIn(ExperimentalCoroutinesApi::class)
class DictationTest {
    private class FakeEars : Dictation.Ears {
        val calls = mutableListOf<String>()
        var starts = true
        var words = CompletableDeferred<String>()
        var soFar = ""
        var stopped: String? = null
        override fun start(): Boolean { calls += "start"; return starts }
        override suspend fun finish(): String { calls += "finish"; return words.await() }
        override fun cancel() { calls += "cancel" }
        override fun level() = 0.4f
        override fun heard() = soFar
        override fun trouble() = stopped
    }

    private var now = 1_000L
    private val ears = FakeEars()
    private var hasEars = true

    private fun TestScope.dictation() = Dictation({ now }, this) { ears.takeIf { hasEars } }

    @Test fun speakDoneAndTheWordsLand() = runTest {
        val d = dictation()
        val landed = mutableListOf<String>()
        d.start { landed += it }
        assertIs<State.Listening>(d.state.value)
        ears.soFar = "run the"
        d.tick()
        assertEquals("run the", assertIs<State.Listening>(d.state.value).heard)
        assertEquals(0.4f, assertIs<State.Listening>(d.state.value).level)
        d.done()
        assertIs<State.Writing>(d.state.value)
        ears.words.complete("  run the tests again ")
        runCurrent()
        assertEquals(State.Idle, d.state.value)
        assertEquals(listOf("run the tests again"), landed)
    }

    @Test fun cancelDropsItEvenWhileWriting() = runTest {
        val d = dictation()
        val landed = mutableListOf<String>()
        d.start { landed += it }
        d.done()
        runCurrent()
        d.cancel()
        ears.words.complete("too late")
        runCurrent()
        assertEquals(State.Idle, d.state.value)
        assertTrue(landed.isEmpty())
        assertEquals("cancel", ears.calls.last())
    }

    @Test fun nothingHeardOrAFailureSaysSo() = runTest {
        val d = dictation()
        d.start { error("nothing to land") }
        d.done()
        ears.words.complete("")
        runCurrent()
        assertEquals(State.Failed(Dictation.NOTHING_HEARD), d.state.value)
        d.dismiss()
        assertEquals(State.Idle, d.state.value)

        ears.words = CompletableDeferred()
        d.start { error("nothing to land") }
        d.done()
        ears.words.completeExceptionally(IOException("whisper.cpp couldn't write it down."))
        runCurrent()
        assertEquals("Couldn't write it down: whisper.cpp couldn't write it down.", assertIs<State.Failed>(d.state.value).message)
    }

    @Test fun noEarsOrNoMicSaySo() = runTest {
        hasEars = false
        val d = dictation()
        d.start {}
        assertEquals(State.Failed(Dictation.NO_EARS), d.state.value)
        hasEars = true
        ears.starts = false
        d.start {}
        assertEquals(State.Failed(Dictation.NO_MIC), d.state.value)
    }

    @Test fun earsThatStoppedEndIt() = runTest {
        val d = dictation()
        d.start {}
        ears.stopped = "The phone's speech recognition isn't reachable."
        d.tick()
        assertEquals(State.Failed("The phone's speech recognition isn't reachable."), d.state.value)
        // With words already heard, they still go in.
        val landed = mutableListOf<String>()
        ears.stopped = null
        d.start { landed += it }
        ears.soFar = "merge it"
        ears.stopped = "busy"
        d.tick()
        assertIs<State.Writing>(d.state.value)
        ears.words.complete("merge it")
        runCurrent()
        assertEquals(listOf("merge it"), landed)
    }

    @Test fun itStopsListeningAfterTheLongest() = runTest {
        val d = dictation()
        d.start {}
        now += Dictation.MAX_MS
        d.tick()
        assertIs<State.Writing>(d.state.value)
        ears.words.complete("long one")
        runCurrent()
    }

    @Test fun theWordsGoWhereTheCursorWas() {
        assertEquals("hello" to 5, Dictation.insert("", 0, 0, "hello"))
        assertEquals("Fix it now" to 10, Dictation.insert("Fix it", 6, 6, "now"))
        assertEquals("Fix the bug please" to 11, Dictation.insert("Fix please", 3, 3, "the bug"))
        // In place of what was selected.
        assertEquals("Fix the tests" to 13, Dictation.insert("Fix it", 4, 6, "the tests"))
        // A cursor past the end (the draft changed meanwhile) goes to the end.
        assertEquals("ab cd" to 5, Dictation.insert("ab", 9, 9, "cd"))
    }

    @Test fun aMachineWritesDownWhatThePhoneRecorded() = runTest {
        var given: Pair<Int, String>? = null
        val tape = object : Dictation.Tape {
            override fun start() = true
            override fun stop() = ByteArray(3)
            override fun cancel() {}
            override fun level() = 0f
            override val type = "audio/mp4"
        }
        val e = MachineEars(tape) { audio, type -> given = audio.size to type; "ship it" }
        assertTrue(e.start())
        assertEquals("ship it", e.finish())
        assertEquals(3 to "audio/mp4", given)
    }

    @Test fun whatAMachineCanIsKeptForAWhile() {
        DictationCheck.forget()
        assertNull(DictationCheck.known("devbox", 0))
        DictationCheck.keep("devbox", true, 0)
        assertEquals(true, DictationCheck.known("devbox", DictationCheck.KEEP_MS - 1))
        assertNull(DictationCheck.known("devbox", DictationCheck.KEEP_MS))
    }
}
