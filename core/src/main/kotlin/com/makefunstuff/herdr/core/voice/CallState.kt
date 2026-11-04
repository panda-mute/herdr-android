package com.makefunstuff.herdr.core.voice

/**
 * A voice call with an Assistant or a task's agent (Shepherdr's Voice add-on, `core/voice.js`): who it is with and
 * what it does now, as the chat's call bar says it. One call at a time.
 */
data class CallState(
    val on: Boolean = false,
    val machineId: String = "",
    /** The space whose Assistant it is with, and the task when it is with a task's agent. */
    val space: String = "",
    val task: String? = null,
    /** As people say it: "the demo-app Assistant", "DEMO-5". */
    val who: String = "",
    val phase: Phase = Phase.Connecting,
    /** Why it ended, when it didn't end by your hand: no ElevenLabs key, the machine went. */
    val problem: String? = null,
) {
    enum class Phase { Connecting, Listening, Thinking, Speaking }

    /** The bar's words for it. */
    val words: String
        get() = when (phase) {
            Phase.Connecting -> "Calling…"
            Phase.Listening -> "Listening"
            Phase.Thinking -> "Thinking…"
            Phase.Speaking -> "Speaking"
        }

    /** It is this chat's: [machineId]'s [space], and [task] (null for the Assistant's). */
    fun isFor(machineId: String, space: String?, task: String?): Boolean =
        on && this.machineId == machineId && this.space == space && this.task == task

    companion object {
        /** The call's audio as the service sends it: 16-bit little-endian samples, as floats -1..1. */
        fun floats(pcm: ByteArray): FloatArray = FloatArray(pcm.size / 2) { i ->
            ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toShort() / 32768f
        }

        /** The microphone's floats as the service takes them: 16-bit little-endian samples. */
        fun pcm(samples: FloatArray): ByteArray {
            val out = ByteArray(samples.size * 2)
            for ((i, f) in samples.withIndex()) {
                val v = (f.coerceIn(-1f, 1f) * 32767f).toInt()
                out[2 * i] = (v and 0xFF).toByte()
                out[2 * i + 1] = (v shr 8 and 0xFF).toByte()
            }
            return out
        }
    }
}
