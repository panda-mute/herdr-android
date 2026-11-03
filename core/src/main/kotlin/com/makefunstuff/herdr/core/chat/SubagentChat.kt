package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.activity.ClaudeActivity
import com.makefunstuff.herdr.core.activity.Turn
import com.makefunstuff.herdr.core.host.HostSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The steps of one Claude subagent ([Subagents]), read from its own transcript and kept live while
 * it runs. Its session is the one the agent in [paneId] reports.
 */
class SubagentChat(private val host: HostSession, private val paneId: String, private val agentId: String) {

    data class State(
        val items: List<ChatItem> = emptyList(),
        val turns: List<Turn> = emptyList(),
        val truncated: Boolean = false,
        /** Read at least once (or found missing). */
        val loaded: Boolean = false,
        /** Its Claude Code version keeps no transcript of its own for the subagent. */
        val missing: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Follows the transcript until cancelled, reconnecting when the stream drops. */
    suspend fun run() {
        val ref = host.state
            .map { s -> s.view?.agents?.firstOrNull { it.paneId == paneId }?.agentSession?.takeIf { it.value.isNotEmpty() } }
            .filterNotNull()
            .first()
        var failures = 0
        while (true) {
            val transcript = ClaudeTranscript(sidechain = true)
            transcript.activity = ClaudeActivity(sidechain = true)
            val result = try {
                TranscriptFollower.follow(host.awaitConnection(timeoutMs = 60_000), ref, transcript, agentId) { truncated ->
                    _state.value = State(transcript.items(), transcript.activity?.turns().orEmpty(), truncated, loaded = true)
                    failures = 0
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                TranscriptFollower.Result.Ended
            }
            if (result == TranscriptFollower.Result.Missing) {
                _state.value = State(loaded = true, missing = true)
                // A subagent that just started writes its file a moment later.
                delay(5_000)
                continue
            }
            failures++
            delay(minOf(30_000L, 1_000L shl minOf(failures, 5)))
        }
    }
}
