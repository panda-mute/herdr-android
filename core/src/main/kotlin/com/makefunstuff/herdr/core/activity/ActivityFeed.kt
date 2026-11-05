package com.makefunstuff.herdr.core.activity

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.host.HostSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The timeline of one agent, kept live while its screen is open: its session as herdr reports it,
 * read over the machine's SSH connection, then followed as it grows.
 */
class ActivityFeed(private val host: HostSession, private val target: Target, private val scope: CoroutineScope) {

    sealed interface Target {
        data class Agent(val paneId: String) : Target
    }

    data class State(
        val loading: Boolean = true,
        val timeline: Timeline = Timeline.Empty,
        /** A session is followed now: new steps appear as the agent takes them. */
        val live: Boolean = false,
        /** Its agent is at work now (in the middle of a turn), as herdr says. */
        val working: Boolean = false,
        val problem: String? = null,
        val title: String = "",
        /** Nothing to read: no session the app can read. */
        val empty: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    fun close() {
        job?.cancel()
        job = null
    }

    /** Reads it all again now (a pull to refresh). */
    fun reload() {
        close()
        _state.update { it.copy(loading = true, problem = null) }
        start()
    }

    private fun agent(): AgentInfo? = (target as Target.Agent).let { t -> host.state.value.snapshot?.agents?.firstOrNull { it.paneId == t.paneId } }

    private suspend fun run() {
        var failures = 0
        while (true) {
            try {
                val conn = host.awaitConnection(timeoutMs = 60_000)
                val own = agent()?.let { a ->
                    a.agentSession?.takeIf { it.value.isNotEmpty() }?.let { ref ->
                        SessionSource(ref.agent.ifEmpty { a.agent.orEmpty() }, ref.kind.ifEmpty { "path" }, ref.value)
                    }
                }
                val readable = listOfNotNull(own).filter { ActivityLog.forAgent(it.agent) != null }
                val follow = own != null
                _state.update { it.copy(working = working()) }
                if (readable.isEmpty()) {
                    _state.update { it.copy(loading = false, live = false, timeline = Timeline.of(emptyList(), emptyList()), empty = true, problem = null) }
                    if (!follow) return
                    delay(15_000)
                    continue
                }
                follow(conn, readable, follow)
                failures = 0
                if (!follow) return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Restart) {
                continue
            } catch (t: Throwable) {
                failures++
                _state.update { it.copy(loading = false, live = false, problem = t.message ?: "Couldn't read the activity") }
            }
            delay(minOf(30_000L, 1_000L shl minOf(failures, 5)))
        }
    }

    private class Restart : Exception()

    private fun working(): Boolean = agent()?.agentStatus == com.makefunstuff.herdr.core.api.AgentStatus.Working

    /** Reads [sources] (and follows the newest). */
    private suspend fun follow(
        conn: com.makefunstuff.herdr.core.ssh.MachineConnection,
        sources: List<SessionSource>,
        follow: Boolean,
    ) = coroutineScope {
        val reader = SessionsReader(sources)
        fun publish() {
            val timeline = Timeline.of(reader.turns(), emptyList(), reader.truncated, reader.missing)
            _state.update { it.copy(loading = false, timeline = timeline, live = follow && reader.live != null, problem = null, empty = false) }
        }
        // An agent that starts a new session (after /clear, say) is read again from it.
        val moved = launch {
            host.state.map { agent()?.agentSession?.value }.distinctUntilChanged().drop(1).first()
            throw Restart()
        }
        try {
            SessionsReader.stream({ conn.start(it) }, ActivityCommands.sessions(sources, follow, bytes(sources.size)), reader) { publish() }
        } finally {
            moved.cancel()
        }
        _state.update { it.copy(live = false) }
    }

    /** Each session's share of [ActivityCommands.TOTAL_BYTES], at most [ActivityCommands.SESSION_BYTES]. */
    private fun bytes(sessions: Int): Int = minOf(ActivityCommands.SESSION_BYTES, ActivityCommands.TOTAL_BYTES / maxOf(1, sessions)).coerceAtLeast(300_000)
}
