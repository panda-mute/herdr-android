package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.chat.VoiceNote
import com.makefunstuff.herdr.core.chat.VoiceNotes
import com.makefunstuff.herdr.core.chat.VoiceRecorder
import com.makefunstuff.herdr.core.chat.VoiceRecorder.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The voice message's states in the composer, against a phone that records what the test says. */
class VoiceRecorderTest {
    private class FakeMic(var allowed: Boolean = true) : VoiceRecorder.Mic {
        val calls = mutableListOf<String>()
        var starts = true
        var file: String? = "/files/voice/a.m4a"
        var loud = 0.5f
        override fun allowed() = allowed
        override fun ask() { calls += "ask" }
        override fun settings() { calls += "settings" }
        override fun start(): Boolean { calls += "start"; return starts }
        override fun stop(): String? { calls += "stop"; return file }
        override fun cancel() { calls += "cancel" }
        override fun level() = loud
    }

    private var now = 1_000L
    private val mic = FakeMic()
    private val sent = mutableListOf<Pair<String, Long>>()
    private val r = VoiceRecorder(mic, { now }) { file, millis -> sent += file to millis }

    @Test fun holdSpeakAndLetGoSendsTheRecording() {
        r.down()
        val rec = assertIs<State.Recording>(r.state.value)
        assertTrue(rec.held)
        assertEquals(listOf("start"), mic.calls)
        now += 7_000
        r.up(heldFor = 7_000)
        assertEquals(State.Idle, r.state.value)
        assertEquals("stop", mic.calls.last())
        assertEquals(listOf("/files/voice/a.m4a" to 7_000L), sent)
    }

    @Test fun aTapRecordsUntilTheNextTap() {
        r.down()
        r.up(heldFor = 100)
        assertEquals(false, assertIs<State.Recording>(r.state.value).held)
        now += 4_000
        r.down()
        assertEquals(listOf("/files/voice/a.m4a" to 4_000L), sent)
        // The second finger's lift does nothing more.
        r.up(heldFor = 50)
        assertEquals(State.Idle, r.state.value)
    }

    @Test fun withTalkToTypeATapDictatesAndAHoldStillRecords() {
        var taps = 0
        r.tap = { taps++ }
        r.down()
        r.up(heldFor = 100)
        assertEquals(1, taps)
        assertEquals(State.Idle, r.state.value)
        assertEquals(listOf("start", "cancel"), mic.calls)
        assertTrue(sent.isEmpty())
        r.down()
        now += 3_000
        r.up(heldFor = 3_000)
        assertEquals(1, taps)
        assertEquals(listOf("/files/voice/a.m4a" to 3_000L), sent)
    }

    @Test fun withTalkToTypeRecordStartsAHandsFreeVoiceMessage() {
        r.tap = { error("not a tap") }
        r.record()
        assertEquals(false, assertIs<State.Recording>(r.state.value).held)
        now += 2_000
        r.stop()
        assertEquals(listOf("/files/voice/a.m4a" to 2_000L), sent)
    }

    @Test fun allowedAfterATapItDictatesAfterRecordItRecords() {
        var taps = 0
        r.tap = { taps++ }
        mic.allowed = false
        r.down()
        assertEquals(State.Asking, r.state.value)
        r.answered(granted = true, forever = false)
        assertEquals(1, taps)
        assertEquals(State.Idle, r.state.value)
        r.record()
        r.answered(granted = true, forever = false)
        assertEquals(1, taps)
        assertEquals(false, assertIs<State.Recording>(r.state.value).held)
    }

    @Test fun slidingAwayAndLettingGoCancels() {
        r.down()
        r.slide(cancel = true)
        assertTrue(assertIs<State.Recording>(r.state.value).cancelling)
        r.slide(cancel = false)
        assertEquals(false, (r.state.value as State.Recording).cancelling)
        r.slide(cancel = true)
        now += 3_000
        r.up(heldFor = 3_000)
        assertEquals(State.Idle, r.state.value)
        assertEquals("cancel", mic.calls.last())
        assertTrue(sent.isEmpty())
    }

    @Test fun aTappedRecordingIsCancelledByItsX() {
        r.down(); r.up(heldFor = 10)
        r.cancel()
        assertEquals(State.Idle, r.state.value)
        assertEquals(listOf("start", "cancel"), mic.calls)
        assertTrue(sent.isEmpty())
    }

    @Test fun tooShortIsDroppedAndSaysSo() {
        r.down()
        now += 600
        r.up(heldFor = 600)
        assertEquals(State.Failed(VoiceRecorder.TOO_SHORT), r.state.value)
        assertEquals("cancel", mic.calls.last())
        assertTrue(sent.isEmpty())
        r.dismiss()
        assertEquals(State.Idle, r.state.value)
    }

    @Test fun aRecorderThatWroteNothingSaysSo() {
        mic.file = null
        r.down(); now += 5_000; r.up(heldFor = 5_000)
        assertEquals(State.Failed(VoiceRecorder.NOT_RECORDED), r.state.value)
        assertTrue(sent.isEmpty())
    }

    @Test fun aMicrophoneThatWontStartSaysSo() {
        mic.starts = false
        r.down()
        assertEquals(State.Failed(VoiceRecorder.NO_RECORDER), r.state.value)
        // Letting go after that changes nothing.
        r.up(heldFor = 2_000)
        assertIs<State.Failed>(r.state.value)
    }

    @Test fun ticksFollowTheLevelAndEndALongRecording() {
        r.down(); r.up(heldFor = 10)
        mic.loud = 2f
        r.tick()
        assertEquals(1f, (r.state.value as State.Recording).level)
        now += VoiceRecorder.MAX_MS
        r.tick()
        assertEquals(State.Idle, r.state.value)
        assertEquals(listOf("/files/voice/a.m4a" to VoiceRecorder.MAX_MS), sent)
    }

    @Test fun anInterruptedRecordingStillGoes() {
        r.down(); r.up(heldFor = 10)
        now += 2_500
        r.interrupted()
        assertEquals(listOf("/files/voice/a.m4a" to 2_500L), sent)
    }

    @Test fun theMicrophoneIsAskedForFirst() {
        mic.allowed = false
        r.down()
        assertEquals(State.Asking, r.state.value)
        assertEquals(listOf("ask"), mic.calls)
        // The finger long gone, the answer starts a tapped recording.
        r.up(heldFor = 3_000)
        r.answered(granted = true, forever = false)
        assertEquals(false, assertIs<State.Recording>(r.state.value).held)
    }

    @Test fun refusedItSaysSoAndAsksAgainOrOpensSettings() {
        mic.allowed = false
        r.down()
        r.answered(granted = false, forever = false)
        assertEquals(State.NoMic(forever = false), r.state.value)
        r.askAgain()
        assertEquals(State.Asking, r.state.value)
        r.answered(granted = false, forever = true)
        assertEquals(State.NoMic(forever = true), r.state.value)
        r.settings()
        assertEquals(State.Idle, r.state.value)
        assertEquals(listOf("ask", "ask", "settings"), mic.calls)
    }

    @Test fun elapsedReadsAsMinutesAndSeconds() {
        assertEquals("0:00", VoiceRecorder.elapsed(-5))
        assertEquals("0:07", VoiceRecorder.elapsed(7_900))
        assertEquals("1:05", VoiceRecorder.elapsed(65_000))
    }
}

/** Voice messages' file names, the `@path` a herdr agent gets, and the phone's list of them. */
class VoiceNotesTest {
    @Test fun theFileNameSaysWhatItIsAndHowLong() {
        assertEquals("voice-message-0m07s.m4a", VoiceNotes.fileName(7_400))
        assertEquals("voice-message-12m05s.m4a", VoiceNotes.fileName(725_000))
        assertEquals(7_000L, VoiceNotes.millis("voice-message-0m07s.m4a"))
        // As Shepherdr keeps it, with its own prefix, and as a path.
        assertEquals(65_000L, VoiceNotes.millis("/home/u/.shepherdr/inbox/home/lq3x9aab12-voice-message-1m05s.m4a"))
        assertTrue(VoiceNotes.isVoice("lq3x9aab12-voice-message-1m05s.m4a"))
        assertNull(VoiceNotes.millis("screenshot.png"))
        assertNull(VoiceNotes.millis("voice-message-0m07s.m4a.png"))
    }

    @Test fun aMessageThatIsOnlyAVoiceFileIsOne() {
        val p = "/home/u/.cache/herdr-android/uploads/20261029-101200-ab12/voice-message-0m07s.m4a"
        assertEquals(p, VoiceNotes.mentioned("@$p"))
        assertEquals("/home/u/my files/voice-message-0m07s.m4a", VoiceNotes.mentioned("@\"/home/u/my files/voice-message-0m07s.m4a\""))
        assertNull(VoiceNotes.mentioned("@$p and also this"))
        assertNull(VoiceNotes.mentioned("@/tmp/notes.txt"))
    }

    @Test fun onlyTheNewestSentAreKeptPerChat() {
        var notes = emptyList<VoiceNote>()
        // Unsent ones in another chat and in this one never go.
        notes = notes + VoiceNote("x", "other", "/x", 1_000, 0, sent = true) + VoiceNote("u", "a", "/u", 1_000, 0, error = "offline")
        val gone = ArrayList<VoiceNote>()
        repeat(VoiceNotes.KEEP + 2) { i ->
            val (next, dropped) = VoiceNotes.add(notes, VoiceNote("$i", "a", "/$i", 1_000, i.toLong(), remote = "/r/$i", sent = true))
            notes = next
            gone += dropped
        }
        assertEquals(listOf("0", "1"), gone.map { it.id })
        assertEquals(VoiceNotes.KEEP, notes.count { it.chat == "a" && it.sent })
        assertTrue(notes.any { it.id == "u" } && notes.any { it.id == "x" })
        assertEquals("5", VoiceNotes.byRemote(notes, "/r/5")?.id)
        assertEquals("boom", VoiceNotes.update(notes, "u") { it.copy(error = "boom") }.first { it.id == "u" }.error)
    }

    @Test fun aHandOverRefusedLaterIsFoundByItsLastId() {
        val sent = VoiceNote("1", "a", "/1", 1_000, 1, remote = "/r/1", sent = true, cid = "c2")
        val earlier = VoiceNote("2", "a", "/2", 1_000, 2, remote = "/r/2", sent = true, cid = "c1")
        val waiting = VoiceNote("3", "a", "/3", 1_000, 3, cid = "c3")
        val r = VoiceNotes.refused(listOf(sent, earlier, waiting), mapOf("c2" to "", "c3" to "gone", "old" to "x"))
        // Only a sent one, by the id it last went with; no reason given reads as one.
        assertEquals(listOf(sent to "The agent didn't take it"), r)
    }

    @Test fun theOnesAfterAFailedOneWaitBehindIt() {
        val a = VoiceNote("a", "k", "/a", 1_000, 1, error = "offline")
        val b = VoiceNote("b", "k", "/b", 1_000, 2)
        val other = VoiceNote("o", "x", "/o", 1_000, 3)
        val notes = listOf(a, b, other)
        assertTrue(VoiceNotes.behindFailed(notes, b))
        assertEquals(false, VoiceNotes.behindFailed(notes, a))
        assertEquals(false, VoiceNotes.behindFailed(notes, other))
        assertEquals(false, VoiceNotes.behindFailed(listOf(a.copy(error = null), b), b))
    }
}
