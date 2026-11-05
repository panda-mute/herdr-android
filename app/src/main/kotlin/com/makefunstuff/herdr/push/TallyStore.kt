package com.makefunstuff.herdr.push

import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.push.Counts
import com.makefunstuff.herdr.core.push.PushEvent
import com.makefunstuff.herdr.core.push.Tally
import com.makefunstuff.herdr.core.push.TallyState
import com.makefunstuff.herdr.data.HostManager
import com.makefunstuff.herdr.data.MachineStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The counts the status widget and the Quick Settings tile show ([Tally]): told by what the app sees
 * on screen, the board's list, its own looks while closed and the machines' pushes. Kept in a file,
 * as a widget is drawn again after the app's process is gone.
 */
class TallyStore(private val file: File, private val machines: MachineStore, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }
    private var state: TallyState = load()
    private var savedAt = 0L
    private val _counts = MutableStateFlow(Counts())
    /** The counts across the machines in the app and on, now. */
    val counts: StateFlow<Counts> = _counts.asStateFlow()

    init {
        recount()
    }

    /** What [snapshot] shows of [machineId]'s agents. */
    fun agents(machineId: String, snapshot: SessionSnapshot) = change { Tally.withAgents(it, machineId, snapshot, now()) }

    /** The board's whole task list on [machineId]. */
    fun tasks(machineId: String, tasks: List<WorkTask>) = change { Tally.withTasks(it, machineId, tasks, now()) }

    /** A push from [machineId]. */
    fun pushed(machineId: String, events: List<PushEvent>) = change { Tally.withPush(it, machineId, events, now()) }

    /** Counts again: a machine came or went, or time passed (what works and wasn't confirmed stops counting). */
    @Synchronized
    fun recount() {
        _counts.value = Tally.count(state, ids(), now())
    }

    /** The machine whose board has the most tasks waiting for you, for a tap on the counts; null when none waits. */
    @Synchronized
    fun boardWaiting(): String? = Tally.boardWaiting(state, ids(), now())

    /** Whether an agent (not a task) needs you somewhere: a tap on the counts then opens the agents. */
    @Synchronized
    fun agentWaiting(): Boolean = Tally.agentWaiting(state, ids(), now())

    /** While the app is on screen: what it sees of each machine's agents, so the widget is right after the app was used. */
    fun start(scope: CoroutineScope, hosts: HostManager) {
        scope.launch {
            hosts.hosts.collect { states ->
                if (!hosts.isForeground) return@collect
                for (s in states) if (s.online && s.agentsKnown) s.view?.let { agents(s.machine.id, it) }
            }
        }
        scope.launch {
            machines.machines.collect { list ->
                val ids = list.map { it.id }.toSet()
                // A machine taken out of the app is forgotten; one turned off is kept, and only not counted.
                change { Tally.only(it, ids) }
            }
        }
        scope.launch {
            while (true) {
                if (hosts.isForeground) {
                    // What works and wasn't confirmed stops counting; while closed, the widget's own half-hourly update sees to it.
                    recount()
                }
                delay(30_000)
            }
        }
    }

    private fun ids() = machines.machines.value.filter { it.enabled }.map { it.id }.toSet()

    @Synchronized
    private fun change(block: (TallyState) -> TallyState) {
        val before = state
        state = block(state)
        recount()
        // Written when what is counted changed, and now and then for the times alone.
        val t = now()
        if (shape(before) != shape(state) || t - savedAt > SAVE_MS) save(t)
    }

    private fun shape(s: TallyState) = listOf(s.agents, s.tasks).map { m -> m.mapValues { (_, p) -> p.items.map { it.tag to it.needsYou } } }

    private fun load(): TallyState =
        runCatching { if (file.exists()) json.decodeFromString(TallyState.serializer(), file.readText()) else null }.getOrNull() ?: TallyState()

    private fun save(t: Long) {
        savedAt = t
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(TallyState.serializer(), state))
            tmp.renameTo(file)
        }
    }

    private companion object {
        const val SAVE_MS = 60_000L
    }
}
