package com.makefunstuff.herdr.core.shepherdr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.Closeable

/**
 * A voice call with a chat's agent, as Shepherdr's web app makes it (`service/voice.ts`, the words in
 * `core/voice.js`): the phone's microphone goes up as 16 kHz mono PCM (16-bit little-endian) in binary messages, and
 * the voice agent's speech comes back the same way. ElevenLabs' voice agent runs on the machine with the person's key;
 * it passes on only what is meant for the coding agent, and says its replies and status. Nothing is recorded or kept.
 */
class VoiceCall internal constructor(private val socket: WebSocketLink) : Closeable {
    /** What comes from the call. */
    sealed interface Event {
        /** Speech to play: 16 kHz mono PCM. */
        class Audio(val pcm: ByteArray) : Event

        /** `listening` or `thinking`. */
        data class State(val state: String) : Event

        /** The person talked over it: what is still playing stops. */
        data object Interrupt : Event

        /** Why the call ended (no key, ElevenLabs refused); the call closes after it. */
        data class Error(val message: String) : Event
    }

    /** The next event, or null once the call closed. Blocks: read it on a thread of its own. */
    fun next(): Event? {
        while (true) {
            when (val m = socket.receiveAny() ?: return null) {
                is ByteArray -> if (m.isNotEmpty() && m.size % 2 == 0) return Event.Audio(m)
                is String -> parse(m)?.let { return it }
            }
        }
    }

    /** A piece of the microphone's recording: 16 kHz mono PCM, at most [MAX_CHUNK] bytes (longer is split). */
    suspend fun send(pcm: ByteArray) = withContext(Dispatchers.IO) {
        var at = 0
        while (at < pcm.size) {
            val end = minOf(pcm.size, at + MAX_CHUNK)
            // Whole samples only: the service drops an odd-length piece.
            val even = end - ((end - at) % 2)
            if (even <= at) break
            socket.send(pcm.copyOfRange(at, even))
            at = even
        }
    }

    override fun close() = socket.close()

    companion object {
        /** The rate both ways, as the voice agent is made (`pcm_16000`). */
        const val RATE = 16_000

        /** The service takes at most 256 KB a message. */
        const val MAX_CHUNK = 64 * 1024

        /** One text message of the call; null for one it doesn't know. */
        fun parse(text: String): Event? {
            val o = runCatching { ShepherdrJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            return when (str("type")) {
                "state" -> str("state")?.let { Event.State(it) }
                "interrupt" -> Event.Interrupt
                "error" -> Event.Error(str("message") ?: "The call ended.")
                else -> null
            }
        }
    }
}
