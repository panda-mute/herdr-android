package com.makefunstuff.herdr.voice

import com.makefunstuff.herdr.core.voice.CallState
import kotlinx.coroutines.flow.StateFlow

/**
 * Voice calls with an agent's chat, as Shepherdr's web app makes them: the phone's microphone and speaker, the
 * machine's ElevenLabs voice agent between you and the coding agent ([com.makefunstuff.herdr.core.shepherdr.VoiceCall]).
 * One call at a time. On the phone it is [AndroidVoiceCalls]; the previews give a still one.
 */
interface VoiceCalls {
    val state: StateFlow<CallState>

    /** Calls [machineId]'s [space] Assistant, or [task]'s agent there ([who] as people say it); a call on already ends. */
    fun start(machineId: String, space: String, task: String?, who: String)

    /** Hangs up. */
    fun stop()
}
