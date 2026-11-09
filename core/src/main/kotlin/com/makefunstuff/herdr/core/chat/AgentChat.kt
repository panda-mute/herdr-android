package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.activity.ActivityLog
import com.makefunstuff.herdr.core.activity.Turn
import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.AgentSessionRef
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.host.HostSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The conversation with one agent, kept live while a chat screen is open.
 *
 * The messages come from the agent's own transcript when herdr knows where it is (claude, codex,
 * pi and omp report their sessions through herdr's integrations). Otherwise the chat falls back to
 * the agent's screen text. When the agent is blocked, its screen is read for the question and the
 * choices it offers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentChat(private val host: HostSession, val paneId: String, private val scope: CoroutineScope) {

    enum class Source { Loading, Transcript, Screen }

    /** One read of the transcript, as the chat shows it. */
    private class Read(val items: List<ChatItem>, val truncated: Boolean, val turns: List<Turn>, val use: ModelUse?, val queued: List<String>)

    companion object {
        /** The agents that draw their reasoning on screen as it streams, in italics over the editor. */
        private val STREAMS_THINKING = setOf("pi", "omp")

        /** How long a new read may take to catch up with what is on screen before it is shown anyway. */
        const val CATCH_UP_MS = 2_000L

        /** How long a chat showing a transcript waits for the agent's session to be listed again. */
        const val SESSION_GRACE_MS = 5_000L

        /**
         * Whether a new read of the transcript ([now]) has reached what [shown] ended with: the same
         * last item, by id or, for ids counted from where the read began, by what it says.
         */
        fun caughtUp(shown: List<ChatItem>, now: List<ChatItem>): Boolean {
            val last = shown.lastOrNull() ?: return true
            return now.any { it.id == last.id || sameContent(it, last) }
        }

        private fun sameContent(a: ChatItem, b: ChatItem): Boolean = when (a) {
            is ChatItem.User -> b is ChatItem.User && a.text == b.text && a.images == b.images
            is ChatItem.Agent -> b is ChatItem.Agent && a.text == b.text
            is ChatItem.Reasoning -> b is ChatItem.Reasoning && a.text == b.text
            is ChatItem.Notice -> b is ChatItem.Notice && a.text == b.text
            is ChatItem.Tool -> b is ChatItem.Tool && a.copy(id = b.id) == b
        }
    }

    data class State(
        val source: Source = Source.Loading,
        val items: List<ChatItem> = emptyList(),
        /** True when only the end of a long transcript was loaded. */
        val truncated: Boolean = false,
        /** The agent's recent screen text, in [Source.Screen] mode or while it is blocked. */
        val screen: String = "",
        val prompt: BlockedPrompt? = null,
        /** Prompts sent from the app that the transcript doesn't show yet. */
        val pending: List<SentMatch.Pending> = emptyList(),
        /** The transcript read as timed turns ([ActivityLog]): when each turn and step was, what an edit changed. */
        val turns: List<Turn> = emptyList(),
        /** The model it answers with and how full its context is, as the transcript says. */
        val use: ModelUse? = null,
        /**
         * What the agent's screen shows it thinking right now, while it works: pi and omp draw a thought
         * as it streams but write it to the transcript only with the call or words it led to ([Thinking.onScreen]).
         */
        val thinking: String? = null,
        /** Messages the agent queued while it works, oldest first ([Transcript.queued]). */
        val queued: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var jobs: List<Job> = emptyList()

    /** The session whose transcript the chat shows now. */
    @Volatile private var shownFrom: AgentSessionRef? = null

    private fun agent(): AgentInfo? = host.state.value.view?.agents?.firstOrNull { it.paneId == paneId }

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            scope.launch { followTranscript() },
            scope.launch { watchScreen() },
        )
    }

    fun close() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    /** Shows a prompt the app just sent until the transcript catches up with it. */
    fun addPending(text: String) {
        _state.update { s ->
            val users = s.items.filterIsInstance<ChatItem.User>()
            val shown = when (s.source) {
                Source.Screen -> SentMatch.countOnScreen(text, s.screen)
                else -> SentMatch.count(text, users.map { it.text })
            }
            s.copy(pending = s.pending + SentMatch.Pending(text, shown, users.map { it.id }.toSet(), System.currentTimeMillis()))
        }
    }

    /** Takes back a pending copy when the send failed. */
    fun dropPending(text: String) {
        _state.update { s ->
            val i = s.pending.indexOfLast { it.text == text }
            if (i < 0) s else s.copy(pending = s.pending.toMutableList().also { it.removeAt(i) })
        }
    }

    private suspend fun followTranscript() {
        host.state
            .map { s -> s.view?.agents?.firstOrNull { it.paneId == paneId }?.let { it.agent to it.agentSession } }
            .distinctUntilChanged()
            .collectLatest { pair ->
                val kind = pair?.first
                val ref = pair?.second
                val transcript = kind?.let { Transcript.forAgent(ref?.agent?.ifEmpty { null } ?: it) }
                if (ref == null || transcript == null || ref.value.isEmpty()) {
                    // No transcript for this agent: show its screen. An agent that just started
                    // reports its session a moment later, which restarts this block. Just reconnected,
                    // herdr can list the agent before its session: the conversation on screen stays a
                    // while, so it doesn't flash empty.
                    if (_state.value.source == Source.Transcript) delay(SESSION_GRACE_MS)
                    if (pair != null || host.state.value.view != null) _state.update { it.copy(source = Source.Screen) }
                    return@collectLatest
                }
                followLoop(ref, kind)
            }
    }

    private suspend fun followLoop(ref: AgentSessionRef, kind: String) = coroutineScope {
        var failures = 0
        while (true) {
            val agent = ref.agent.ifEmpty { kind }
            val transcript = Transcript.forAgent(agent) ?: return@coroutineScope
            transcript.activity = ActivityLog.forAgent(agent)
            // Read again after the connection dropped (back from the lock screen, say), the file comes
            // from its start: what is on screen stays until the new read has caught up with it, so the
            // chat doesn't go empty and fill again.
            val shown = _state.value.takeIf { it.source == Source.Transcript && shownFrom == ref }?.items.orEmpty()
            val lock = Any()
            var caught = shown.isEmpty()
            // The read so far, taken on the reading thread; shown once it has caught up.
            var waiting: Read? = null
            fun publish(r: Read) {
                shownFrom = ref
                _state.update { s ->
                    s.copy(source = Source.Transcript, items = r.items, truncated = r.truncated, pending = unseen(s.pending, r.items), turns = r.turns, use = r.use, queued = r.queued)
                }
            }
            // Past CATCH_UP_MS of reading, whatever the new read has is shown, once it has anything.
            var late = false
            var giveUp: Job? = null
            val result = try {
                val connection = host.awaitConnection(timeoutMs = 60_000)
                if (!caught) giveUp = launch {
                    delay(CATCH_UP_MS)
                    synchronized(lock) {
                        late = true
                        waiting?.takeIf { !caught && it.items.isNotEmpty() }?.let { caught = true; publish(it) }
                    }
                }
                TranscriptFollower.follow(connection, ref, transcript) { truncated ->
                    val read = Read(transcript.items(), truncated, transcript.activity?.turns().orEmpty(), transcript.use, transcript.queued)
                    synchronized(lock) {
                        if (!caught && (caughtUp(shown, read.items) || (late && read.items.isNotEmpty()))) caught = true
                        if (caught) publish(read) else waiting = read
                    }
                    failures = 0
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                TranscriptFollower.Result.Ended
            } finally {
                giveUp?.cancel()
            }
            if (result == TranscriptFollower.Result.Missing) {
                _state.update { it.copy(source = Source.Screen) }
                // The file may appear once the agent writes its first turn.
                delay(5_000)
                continue
            }
            failures++
            delay(minOf(30_000L, 1_000L shl minOf(failures, 5)))
        }
    }

    /** Drops pending prompts once the transcript shows another user message like each. */
    private fun unseen(pending: List<SentMatch.Pending>, items: List<ChatItem>): List<SentMatch.Pending> =
        SentMatch.unseen(pending, items.filterIsInstance<ChatItem.User>(), System.currentTimeMillis())

    private suspend fun watchScreen() {
        while (true) {
            val agent = agent()
            val status = agent?.agentStatus ?: host.state.value.view?.pane(paneId)?.agentStatus ?: AgentStatus.Unknown
            val source = _state.value.source
            val blocked = status == AgentStatus.Blocked
            // pi and omp stream their thoughts only to the screen: read it while they work.
            val streams = status == AgentStatus.Working && source == Source.Transcript && agent?.agent in STREAMS_THINKING
            if (streams) {
                val thinking = try {
                    Thinking.onScreen(host.awaitAgent(paneId, 10_000).read(paneId, lines = 200, visible = true, ansi = true))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
                if (thinking != _state.value.thinking) _state.update { it.copy(thinking = thinking) }
            } else if (_state.value.thinking != null) {
                _state.update { it.copy(thinking = null) }
            }
            if (blocked || source == Source.Screen) {
                try {
                    val api = host.awaitAgent(paneId, 10_000)
                    val visible = if (blocked) api.read(paneId, lines = 80, visible = true) else null
                    val recent = if (source == Source.Screen) api.read(paneId, lines = 200, visible = false) else null
                    _state.update { s ->
                        s.copy(
                            screen = (recent ?: visible ?: s.screen).trimEnd(),
                            prompt = visible?.let { BlockedPrompt.parse(it) }?.let { AskedQuestions.fill(it, s.items) },
                            pending = if (source == Source.Screen && recent != null) SentMatch.unconfirmed(s.pending) { p -> SentMatch.slid(p, SentMatch.countOnScreen(p.text, recent), System.currentTimeMillis()) } else s.pending,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                }
            } else if (_state.value.prompt != null) {
                _state.update { it.copy(prompt = null) }
            }
            delay(
                when {
                    blocked -> 1_200L
                    source == Source.Screen && status == AgentStatus.Working -> 1_500L
                    streams -> 1_000L
                    source == Source.Screen -> 3_000L
                    else -> 700L
                },
            )
        }
    }
}
