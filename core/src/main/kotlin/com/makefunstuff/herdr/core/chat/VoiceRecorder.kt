package com.makefunstuff.herdr.core.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A voice message in a chat's composer: the phone records what you say as an audio file, which is
 * sent to the agent as it is (the phone turns nothing into text). The agent's own program on the
 * machine (Claude Code, Codex, pi) gets the file.
 *
 * The mic button is held while you speak (let go to send, slide away to cancel) or tapped to start
 * and tapped again to send. Where the composer also talks to type ([tap] set, [Dictation]), a tap is
 * that instead, and a hands-free voice message is started with [record]. Every call comes from the
 * main thread: the UI's and the recorder's.
 */
class VoiceRecorder(
    private val mic: Mic,
    /** The phone's clock, in milliseconds: how long you spoke. */
    val clock: () -> Long,
    /** A recording finished: the file [Mic.stop] wrote and how long it is. */
    private val onRecorded: (file: String, millis: Long) -> Unit,
) {
    /** The phone's side: its microphone permission and its recorder. */
    interface Mic {
        /** Whether the app may use the microphone. */
        fun allowed(): Boolean
        /** Asks for the microphone; the answer comes back through [answered]. */
        fun ask()
        /** Opens the app's page in the phone's settings, where a microphone refused for good is allowed. */
        fun settings()
        /** Starts recording into a new file; false when the microphone couldn't be started. */
        fun start(): Boolean
        /** Stops recording: the file it wrote, or null when nothing usable was recorded. */
        fun stop(): String?
        /** Stops recording and throws the file away. */
        fun cancel()
        /** How loud it is now, 0 to 1. */
        fun level(): Float
    }

    sealed interface State {
        data object Idle : State
        /** The phone asks you for the microphone. */
        data object Asking : State
        /**
         * Recording since [startedAt]. [held] while a finger holds the mic (else it was tapped and goes
         * until tapped again); [cancelling] once that finger slid far enough that letting go cancels.
         * [level] is how loud, 0 to 1.
         */
        data class Recording(
            val startedAt: Long,
            val held: Boolean,
            val cancelling: Boolean = false,
            val level: Float = 0f,
        ) : State
        /** No microphone: refused, [forever] when the phone won't ask again (only its settings can allow it). */
        data class NoMic(val forever: Boolean) : State
        /** It went wrong; [message] says what to do. */
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** What a quick tap on the mic does instead of recording on until the next tap (talk to type); null: it records. */
    var tap: (() -> Unit)? = null

    /** The microphone was asked for by [record], so once allowed it records, never [tap]s. */
    private var askedToRecord = false

    /** A finger down on the mic: starts recording (asking for the microphone first), or sends a tapped recording. */
    fun down() {
        when (val s = _state.value) {
            is State.Recording -> if (!s.held) stop()
            State.Asking -> {}
            else -> if (mic.allowed()) start(held = true) else ask(record = false)
        }
    }

    /**
     * The finger up again after [heldFor] ms: a quick tap leaves it recording until the next tap; after
     * a hold it sends, or cancels when the finger slid away.
     */
    fun up(heldFor: Long) {
        val s = _state.value as? State.Recording ?: return
        if (!s.held) return
        when {
            s.cancelling -> cancel()
            heldFor < TAP_MS -> tap?.let { t ->
                mic.cancel()
                _state.value = State.Idle
                t()
            } ?: run { _state.value = s.copy(held = false) }
            else -> stop()
        }
    }

    /** The holding finger is far enough away that letting go cancels ([cancel] true), or back again. */
    fun slide(cancel: Boolean) {
        val s = _state.value as? State.Recording ?: return
        if (s.held && s.cancelling != cancel) _state.value = s.copy(cancelling = cancel)
    }

    /** Finishes: the recording goes, unless it is too short to hold any words. */
    fun stop() {
        val s = _state.value as? State.Recording ?: return
        val millis = (clock() - s.startedAt).coerceAtLeast(0)
        if (millis < MIN_MS) {
            mic.cancel()
            _state.value = State.Failed(TOO_SHORT)
            return
        }
        val file = mic.stop()
        if (file == null) {
            _state.value = State.Failed(NOT_RECORDED)
            return
        }
        _state.value = State.Idle
        onRecorded(file, millis)
    }

    /** Drops the recording (or the question for the microphone). */
    fun cancel() {
        val s = _state.value
        if (s is State.Recording) mic.cancel()
        if (s !is State.Idle) _state.value = State.Idle
    }

    /** Puts away what [State.NoMic] or [State.Failed] said. */
    fun dismiss() {
        val s = _state.value
        if (s is State.NoMic || s is State.Failed) _state.value = State.Idle
    }

    /** Opens the phone's settings for the microphone (from [State.NoMic]). */
    fun settings() {
        dismiss()
        mic.settings()
    }

    /** A voice message hands-free, as a tap does without [tap]: it records until [stop] (or a tap) sends it. */
    fun record() {
        when (_state.value) {
            is State.Recording, State.Asking -> {}
            else -> if (mic.allowed()) start(held = false) else ask(record = true)
        }
    }

    /**
     * The phone's answer to [Mic.ask]: allowed, it starts recording at once (the finger has long left the button),
     * or talks to type where a tap does that and a tap is what asked.
     */
    fun answered(granted: Boolean, forever: Boolean) {
        if (_state.value != State.Asking) return
        val t = tap
        when {
            !granted -> _state.value = State.NoMic(forever)
            t != null && !askedToRecord -> { _state.value = State.Idle; t() }
            else -> start(held = false)
        }
    }

    private fun ask(record: Boolean) {
        askedToRecord = record
        _state.value = State.Asking
        mic.ask()
    }

    /** Asks for the microphone again, from [State.NoMic]. */
    fun askAgain() {
        if (_state.value !is State.NoMic) return
        ask(askedToRecord)
    }

    /** The recorder stopped by itself (the microphone taken by a call): what it has goes, as on [stop]. */
    fun interrupted() = stop()

    /** Called a few times a second while it records: how loud, and ends a recording that went on too long. */
    fun tick() {
        val s = _state.value as? State.Recording ?: return
        if (clock() - s.startedAt >= MAX_MS) {
            stop()
            return
        }
        _state.value = s.copy(level = mic.level().coerceIn(0f, 1f))
    }

    private fun start(held: Boolean) {
        // Timed from the press: the recorder may take a moment to start.
        val at = clock()
        if (!mic.start()) {
            _state.value = State.Failed(NO_RECORDER)
            return
        }
        _state.value = State.Recording(at, held = held)
    }

    companion object {
        /** Shorter than this, a press on the mic is a tap: it keeps recording until the next tap. */
        const val TAP_MS = 400L
        /** Shorter than this, a recording holds no words: it is dropped. */
        const val MIN_MS = 1_000L
        /** The longest voice message: it is sent when it gets there. */
        const val MAX_MS = 10 * 60_000L

        const val TOO_SHORT = "Too short to send. Hold the mic while you speak, or tap it to start and again to send."
        const val NOT_RECORDED = "Nothing was recorded. Try again."
        const val NO_RECORDER = "The microphone didn't start. Another app may be using it."

        /** How long you spoke: `0:07`, `1:05`. */
        fun elapsed(millis: Long): String {
            val s = (millis / 1000).coerceAtLeast(0)
            return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        }
    }
}
