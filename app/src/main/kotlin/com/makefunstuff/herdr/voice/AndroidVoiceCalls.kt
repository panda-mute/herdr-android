package com.makefunstuff.herdr.voice

import android.util.Log
import com.makefunstuff.herdr.core.shepherdr.VoiceCall
import com.makefunstuff.herdr.core.voice.CallState
import com.makefunstuff.herdr.core.voice.CallState.Phase
import com.makefunstuff.herdr.data.HostManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * [VoiceCalls] on the phone: [MicIn] (the voice-call source, with the phone's echo canceller, so you can talk over
 * it) goes up as 16 kHz PCM, and what comes back plays through [AudioOut] at the same rate; an interruption stops
 * what is playing at once. It holds the machine's connection while it lasts; the call ends with the app's screen.
 */
class AndroidVoiceCalls(private val scope: CoroutineScope, private val hosts: HostManager) : VoiceCalls {
    private val _state = MutableStateFlow(CallState())
    override val state: StateFlow<CallState> = _state.asStateFlow()

    private var job: Job? = null
    @Volatile private var call: VoiceCall? = null
    private var mic: MicIn? = null
    private var out: AudioOut? = null

    override fun start(machineId: String, space: String, task: String?, who: String) {
        stop()
        _state.value = CallState(on = true, machineId = machineId, space = space, task = task, who = who)
        job = scope.launch(Dispatchers.IO) {
            val speaker = AudioOut(rate = VoiceCall.RATE).also { out = it }
            try {
                val session = hosts.session(machineId) ?: throw java.io.IOException("No such machine")
                val board = session.awaitWorkBoard()
                val t = task?.let { id -> board.overview().tasks.firstOrNull { it.id == id } ?: throw java.io.IOException("$id isn't on the board any more") }
                val c = board.call(space, t)
                call = c
                speaker.start()
                val m = MicIn { block ->
                    // Never on the audio thread's own time: a slow link only delays what goes up.
                    runCatching { runBlocking { c.send(CallState.pcm(block)) } }
                }
                mic = m
                if (!m.start()) throw java.io.IOException("No microphone: allow it for Herdr in Android's settings")
                while (true) {
                    when (val e = c.next() ?: break) {
                        is VoiceCall.Event.Audio -> {
                            speaker.write(CallState.floats(e.pcm))
                            _state.update { if (it.on) it.copy(phase = Phase.Speaking) else it }
                        }
                        is VoiceCall.Event.State -> _state.update {
                            if (!it.on) it else it.copy(phase = if (e.state == "thinking") Phase.Thinking else Phase.Listening)
                        }
                        VoiceCall.Event.Interrupt -> {
                            speaker.stop()
                            speaker.start()
                        }
                        is VoiceCall.Event.Error -> _state.update { it.copy(problem = e.message) }
                    }
                }
                _state.update { it.copy(on = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(AudioOut.TAG, "call failed", e)
                _state.update { it.copy(on = false, problem = it.problem ?: e.message ?: "The call ended.") }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) { end() }
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        end()
        _state.update { it.copy(on = false) }
    }

    private fun end() {
        mic?.stop()
        mic = null
        out?.stop()
        out = null
        call?.let { c -> Thread { runCatching { c.close() } }.start() }
        call = null
    }
}
