package com.makefunstuff.herdr.brief

import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.brief.Baseline
import com.makefunstuff.herdr.core.brief.Brief
import com.makefunstuff.herdr.core.brief.BriefLine
import com.makefunstuff.herdr.core.brief.BriefSchedule
import com.makefunstuff.herdr.core.brief.BriefWords
import com.makefunstuff.herdr.core.brief.Briefs
import com.makefunstuff.herdr.core.brief.Journals
import com.makefunstuff.herdr.core.brief.MachineLook
import com.makefunstuff.herdr.core.chat.BlockedPrompt
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.host.HostStatus
import com.makefunstuff.herdr.core.ssh.Machine
import com.makefunstuff.herdr.data.HostManager
import com.makefunstuff.herdr.data.MachineStore
import com.makefunstuff.herdr.ui.agents.PromptGuess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Where the brief goes: Android's notifications and alarms on the phone, nowhere in the screen previews. */
interface BriefSink {
    /** Posts [brief] as the brief's notification, replacing the one before. [quiet]: no sound (quiet hours). */
    fun show(brief: Brief, quiet: Boolean)

    fun cancel()

    /** Sets the alarm for the next brief at [at] (ms), or clears it when null. */
    fun plan(at: Long?)

    /** Whether the phone is in car mode now (Android Auto, a car dock). */
    fun inCar(): Boolean
}

/** What is being read aloud: the brief's sentences ([BriefWords.spoken]) and the one being read. */
data class Reading(val lines: List<BriefLine>, val index: Int, val briefAt: Long) {
    val line: BriefLine? get() = lines.getOrNull(index)
}

/** The brief read aloud: text to speech on the phone, nothing in the screen previews. */
interface BriefVoice {
    /** What is being read now; null when nothing is. */
    val reading: StateFlow<Reading?>

    /** Reads [lines] aloud from the start ([briefAt]: the brief they are, which counts as seen once read to the end). */
    fun read(lines: List<BriefLine>, briefAt: Long)

    /** Skips to the next sentence. */
    fun next()

    fun stop()
}

/** Which stretch the brief on screen covers. */
enum class BriefWindow(val label: String) {
    /** Since the person last looked (the app left the screen, or a brief was read). */
    SinceLooked("Since you looked"),
    Day("Last 24 hours"),
}

/** The brief on screen: the newest one made, and whether a new one is being put together. */
data class BriefView(
    val brief: Brief? = null,
    val loading: Boolean = false,
    val window: BriefWindow = BriefWindow.SinceLooked,
)

/**
 * The brief ([com.makefunstuff.herdr.core.brief.Brief]): reads every machine the app knows over its
 * SSH connection (made for it while the app is closed), herdr-push's journal there and how the board
 * and the agents stand, and puts it together. On screen, at the times the person chose (an alarm,
 * then a notification, read aloud in the car if they want), and on demand.
 *
 * "Since you last looked" is kept as [BriefState.seenAt]: when the app last left the screen, or a
 * brief was read aloud to its end. For a machine without a journal, what the app showed then
 * ([BriefState.baselines]) is what the brief compares with.
 */
class BriefWork(
    private val scope: CoroutineScope,
    private val hosts: HostManager,
    private val machines: MachineStore,
    val store: BriefStore,
    private val sink: BriefSink,
    val voice: BriefVoice,
    /** Whether quiet hours hold notifications now. */
    private val quiet: () -> Boolean = { false },
    /** The phone's time zone, for the brief's times. */
    val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _view = MutableStateFlow(BriefView(brief = store.state.value.last))
    val view: StateFlow<BriefView> = _view.asStateFlow()
    private val building = Mutex()

    /** What the app saw of each machine while on screen: the person's view, kept as [BriefState.baselines] when they leave. */
    private val looking = HashMap<String, Baseline>()

    fun start() {
        // A changed schedule (or the brief turned on): the alarm for the next one.
        scope.launch { store.state.map { it.schedule }.distinctUntilChanged().collect { plan() } }
        // While on screen, the agents each machine shows are what the person sees.
        scope.launch {
            hosts.hosts.collect { states ->
                if (!hosts.isForeground) return@collect
                for (s in states) if (s.online) s.snapshot?.let { looked(s.machine.id, null, it) }
            }
        }
    }

    /** While on screen: [machineId]'s tasks ([tasks]) or agents ([snapshot]) as the app shows them. */
    fun looked(machineId: String, tasks: List<WorkTask>?, snapshot: SessionSnapshot?) {
        if (!hosts.isForeground) return
        synchronized(looking) { looking[machineId] = Baseline.of(looking[machineId] ?: store.state.value.baselines[machineId], clock(), tasks, snapshot) }
    }

    /** The app leaves the screen: the person looked until now. */
    fun leaving() {
        val now = clock()
        val seen = synchronized(looking) { looking.toMap().also { looking.clear() } }
        store.change { it.left(now, seen) }
    }

    /** The brief read at [briefAt] was taken in (read aloud to its end): the person knows what it said. */
    fun seen(briefAt: Long) {
        store.change { it.read(briefAt) }
    }

    /** Where a brief made now starts, for [window]. */
    fun since(window: BriefWindow, now: Long = clock()): Long = when (window) {
        BriefWindow.SinceLooked -> BriefSchedule.since(store.state.value.seenAt, now)
        BriefWindow.Day -> now - BriefSchedule.FIRST_WINDOW_MS
    }

    /** The screen asks for a fresh brief over [window] (the one shown so far stays until it is ready). */
    fun refresh(window: BriefWindow = _view.value.window) {
        _view.update { it.copy(window = window, loading = true) }
        scope.launch {
            try {
                build(since(window))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Each machine's own trouble is in the brief; nothing else is expected to fail.
            } finally {
                _view.update { it.copy(loading = false) }
            }
        }
    }

    /** Reads every machine in the app that is turned on and puts the brief together from [since]. */
    suspend fun build(since: Long): Brief = building.withLock {
        val list = machines.machines.value.filter { it.enabled }
        val looks = coroutineScope { list.map { m -> async { look(m, since) } }.awaitAll() }
        val now = clock()
        val brief = Briefs.build(looks.map { it.first }, since, now)
        val told = looks.associate { (look, tasks) -> look.id to Baseline.of(store.state.value.baselines[look.id], now, tasks, look.snapshot) }
        store.change { it.made(brief, told) }
        if (hosts.isForeground) looks.forEach { (look, tasks) -> looked(look.id, tasks, look.snapshot) }
        _view.update { it.copy(brief = brief) }
        brief
    }

    /** One machine: how it stands and what its journal says; the reason when it can't be reached. With its full task list. */
    private suspend fun look(m: Machine, since: Long): Pair<MachineLook, List<WorkTask>?> {
        val baseline = store.state.value.baselines[m.id]
        return try {
            withTimeout(LOOK_MS) { hosts.borrow(m.id) { s -> read(s, m, since, baseline) } }
        } catch (e: TimeoutCancellationException) {
            MachineLook(m.id, m.label, problem = "It didn't answer in time") to null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            MachineLook(m.id, m.label, problem = t.message ?: "It couldn't be reached") to null
        }
    }

    private suspend fun read(s: HostSession, m: Machine, since: Long, baseline: Baseline?): Pair<MachineLook, List<WorkTask>?> {
        // Online, or needing the person (a refused key, a changed host key): that won't change by waiting.
        val state = withTimeoutOrNull(CONNECT_MS) { s.state.first { it.online || it.status is HostStatus.Attention } }
        (state?.status as? HostStatus.Attention)?.let { throw IOException(it.message) }
        val api = s.awaitApi(5_000)
        val conn = s.awaitConnection(5_000)
        val snapshot = runCatching { api.snapshot() }.getOrElse { if (it is CancellationException) throw it; null }
        if (!s.state.value.boardChecked) s.checkBoard()
        val hasBoard = s.state.value.onShepherdr
        val tasks = if (hasBoard) runCatching { s.awaitWorkBoard().overview().tasks }.getOrNull() else null
        // What each agent that asks wants, from its screen.
        val questions = snapshot?.agents.orEmpty().filter { it.agentStatus == AgentStatus.Blocked }.mapNotNull { a ->
            val text = runCatching { api.readPane(a.paneId, source = "visible", lines = 60).text }.getOrNull() ?: return@mapNotNull null
            (BlockedPrompt.parse(text)?.question ?: PromptGuess.question(text))?.let { a.paneId to it }
        }.toMap()
        val journal = Journals.read(conn, m.label, s.machine.session, since / 1000)
        val look = MachineLook(
            id = m.id, label = m.label, tasks = tasks, boardFailed = hasBoard && tasks == null,
            snapshot = snapshot, questions = questions, journal = journal, baseline = baseline,
        )
        return look to tasks
    }

    /** What is read aloud for [brief]. */
    fun spoken(brief: Brief): List<BriefLine> = BriefWords.spoken(brief, zone())

    /** Reads [brief] aloud (from the screen, or the notification's Read aloud). */
    fun read(brief: Brief) = voice.read(spoken(brief), brief.at)

    /**
     * The alarm for a scheduled brief rang: makes it, posts it, and tells whether to read it aloud now
     * (the setting, and car mode). Sets the next alarm. Null when nothing was posted (turned off since,
     * or already posted for this minute).
     */
    suspend fun scheduled(): Brief? {
        val st = store.state.value
        val now = clock()
        val slot = now / 60_000 * 60_000
        if (!st.schedule.on || st.postedFor == slot) return null.also { plan() }
        store.change { it.copy(postedFor = slot) }
        val brief = try {
            build(BriefSchedule.since(st.seenAt, now))
        } finally {
            plan()
        }
        sink.show(brief, quiet = quiet())
        return brief
    }

    /** Whether a scheduled brief is read aloud as it comes. */
    fun readsAloud(): Boolean = when (store.state.value.schedule.readAloud) {
        BriefSchedule.ReadAloud.Never -> false
        BriefSchedule.ReadAloud.InCar -> sink.inCar()
        BriefSchedule.ReadAloud.Always -> true
    }

    /** Sets the alarm for the next scheduled brief, or clears it. */
    fun plan() {
        val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(clock()), zone())
        sink.plan(store.state.value.schedule.next(local)?.atZone(zone())?.toInstant()?.toEpochMilli())
    }

    private companion object {
        const val LOOK_MS = 60_000L
        const val CONNECT_MS = 30_000L
    }
}
