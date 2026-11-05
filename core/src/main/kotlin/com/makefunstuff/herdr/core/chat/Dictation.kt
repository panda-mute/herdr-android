package com.makefunstuff.herdr.core.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Talk to type in a chat's composer: you speak, and on Done the words land in the box where the
 * cursor was, to edit and send. A tap on the mic starts it (holding the mic is a voice message, see
 * [VoiceRecorder]); X drops what was said.
 *
 * Who writes it down is [ears]'s choice, made each time it starts: Shepherdr's service where the
 * space's machine has whisper.cpp ([MachineEars], as 3.1's web app dictates), else the phone's own
 * recogniser. Every call comes from the main thread, as the composer's.
 */
class Dictation(
    /** The phone's clock, in milliseconds: how long you spoke. */
    val clock: () -> Long,
    private val scope: CoroutineScope,
    /** What listens this time; null when nothing can (no recogniser, no service). */
    private val ears: () -> Ears?,
) {
    /** One way of hearing you: started, then finished with the words, or cancelled. */
    interface Ears {
        /** Starts listening; false when the microphone couldn't be started. */
        fun start(): Boolean
        /** Stops listening and writes down what was said ("" for nothing); throws with what went wrong. */
        suspend fun finish(): String
        /** Stops listening and drops what was heard. */
        fun cancel()
        /** How loud it is now, 0 to 1. */
        fun level(): Float
        /** What was heard so far, where it is written down as you speak (the phone's recogniser); else "". */
        fun heard(): String = ""
        /** What went wrong while it listened (it stopped by itself), or null. */
        fun trouble(): String? = null
    }

    /** A recording of what you say, as audio: for a machine that writes it down. */
    interface Tape {
        /** Starts recording; false when the microphone couldn't be started. */
        fun start(): Boolean
        /** Stops recording: the audio, or null when nothing usable was recorded. */
        fun stop(): ByteArray?
        fun cancel()
        fun level(): Float
        /** The audio's media type. */
        val type: String
    }

    sealed interface State {
        data object Idle : State
        /** Listening since [startedAt]; [level] how loud, 0 to 1; [heard] the words so far, where the phone has them. */
        data class Listening(val startedAt: Long, val level: Float = 0f, val heard: String = "") : State
        /** Done was pressed: the words are being written down (by the machine, or the phone's last ones). */
        data class Writing(val startedAt: Long) : State
        /** It went wrong; [message] says what to do. */
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var current: Ears? = null
    private var job: Job? = null

    /** Whether it listens or writes now: the box gives way to it. */
    val on: Boolean get() = _state.value.let { it is State.Listening || it is State.Writing }

    /** Starts listening; [land] gets the words once Done wrote them down (never blank). */
    fun start(land: (String) -> Unit) {
        if (on) return
        val e = ears() ?: run { _state.value = State.Failed(NO_EARS); return }
        val at = clock()
        if (!e.start()) {
            _state.value = State.Failed(NO_MIC)
            return
        }
        current = e
        landing = land
        _state.value = State.Listening(at)
    }

    private var landing: ((String) -> Unit)? = null

    /** Done: stops listening, writes it down and puts it in the box. */
    fun done() {
        val s = _state.value as? State.Listening ?: return
        val e = current ?: return
        val land = landing
        _state.value = State.Writing(s.startedAt)
        job = scope.launch {
            val result = try {
                Result.success(e.finish())
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Result.failure(t)
            }
            if (current !== e) return@launch
            current = null
            landing = null
            val words = result.getOrNull()?.trim()
            _state.value = when {
                words == null -> State.Failed(result.exceptionOrNull()?.message?.let { "Couldn't write it down: $it" } ?: NOT_WRITTEN)
                words.isEmpty() -> State.Failed(NOTHING_HEARD)
                else -> State.Idle
            }
            if (!words.isNullOrEmpty()) land?.invoke(words)
        }
    }

    /** Drops what was said (also while it is being written down). */
    fun cancel() {
        job?.cancel()
        job = null
        current?.cancel()
        current = null
        landing = null
        if (_state.value !is State.Idle) _state.value = State.Idle
    }

    /** Puts away what [State.Failed] said. */
    fun dismiss() {
        if (_state.value is State.Failed) _state.value = State.Idle
    }

    /**
     * Called a few times a second while it listens: how loud and what was heard; ends it after
     * [MAX_MS], or once the ears stopped by themselves (what they heard still goes in).
     */
    fun tick() {
        val s = _state.value as? State.Listening ?: return
        val e = current ?: return
        val trouble = e.trouble()
        if (clock() - s.startedAt >= MAX_MS || (trouble != null && e.heard().isNotBlank())) return done()
        if (trouble != null) {
            e.cancel()
            current = null
            landing = null
            _state.value = State.Failed(trouble)
            return
        }
        _state.value = s.copy(level = e.level().coerceIn(0f, 1f), heard = e.heard())
    }

    companion object {
        /** The longest it listens: Done is pressed for you there. */
        const val MAX_MS = 5 * 60_000L

        const val NO_EARS = "Talk to type needs the phone's speech recognition, or whisper.cpp on the machine (shepherdr whisper)."
        const val NO_MIC = "The microphone didn't start. Another app may be using it."
        const val NOTHING_HEARD = "Nothing was heard. Tap the mic and speak, then Done."
        const val NOT_WRITTEN = "Couldn't write it down. Try again."

        /**
         * [said] put into [text] in place of what [from]..[to] selects (the cursor where you were typing), with a
         * space either side where the words would touch: the new text and the cursor after the words. As 3.1's web.
         */
        fun insert(text: String, from: Int, to: Int, said: String): Pair<String, Int> {
            val a = from.coerceIn(0, text.length)
            val b = to.coerceIn(a, text.length)
            val before = text.substring(0, a)
            val after = text.substring(b)
            val words = (if (before.isNotEmpty() && !before.last().isWhitespace()) " " else "") + said.trim()
            val gap = if (after.isNotEmpty() && !after.first().isWhitespace()) " " else ""
            return (before + words + gap + after) to (before.length + words.length)
        }
    }
}

/**
 * Ears on a machine: the phone records ([tape]) and [write] has it written down there, Shepherdr's
 * `POST /api/dictate` (whisper.cpp), as 3.1's web app does on a phone.
 */
class MachineEars(private val tape: Dictation.Tape, private val write: suspend (audio: ByteArray, type: String) -> String) : Dictation.Ears {
    override fun start() = tape.start()
    override suspend fun finish(): String {
        val audio = tape.stop() ?: throw IOException("nothing was recorded")
        return withContext(Dispatchers.IO) { write(audio, tape.type) }
    }
    override fun cancel() = tape.cancel()
    override fun level() = tape.level()
}

/**
 * Which machines write speech down (Shepherdr's service with whisper.cpp), by the machine's id, as last asked: a
 * yes or no is kept for [KEEP_MS], so a chat opened again doesn't ask again.
 */
object DictationCheck {
    const val KEEP_MS = 10 * 60_000L
    private val known = ConcurrentHashMap<String, Pair<Boolean, Long>>()

    /** What is known of [machine] at [now], or null when it must be asked. */
    fun known(machine: String, now: Long): Boolean? = known[machine]?.takeIf { now - it.second < KEEP_MS }?.first

    fun keep(machine: String, can: Boolean, now: Long) { known[machine] = can to now }

    fun forget() = known.clear()
}
