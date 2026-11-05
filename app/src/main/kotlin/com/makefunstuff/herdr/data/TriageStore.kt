package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.home.HomeAgent
import com.makefunstuff.herdr.core.home.NotifyMove
import com.makefunstuff.herdr.core.home.Triage
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.core.push.Notify
import com.makefunstuff.herdr.push.PushState
import com.makefunstuff.herdr.push.PushStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The Agents list's triage, on this phone ([Triage]): agents pinned, by `machine/pane`, and the mutes
 * from before Notify until [move] puts them on their spaces.
 */
class TriageStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Triage> = _state.asStateFlow()

    /** Whether notifications about [machineId]'s pane [paneId] are muted from before Notify. */
    fun mutes(machineId: String, paneId: String): Boolean = _state.value.mutes(machineId, paneId)

    @Synchronized
    fun change(f: (Triage) -> Triage) {
        val next = f(_state.value)
        if (next == _state.value) return
        _state.value = next
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(Triage.serializer(), next))
            tmp.renameTo(file)
        }
    }

    /**
     * Drops what is over: the marks of panes gone from the machines read ([hosts]), and those of
     * machines no longer in the app ([machines], every machine saved).
     */
    fun keep(hosts: List<HostState>, machines: Set<String>) {
        change { it.keep(HomeAgent.of(hosts), read(hosts), machines) }
    }

    /**
     * Moves the mutes kept here and the watches kept with the notification settings ([push]) onto their
     * agents' spaces' Notify, for the agents found on the machines read ([hosts]) ([NotifyMove]).
     */
    @Synchronized
    fun move(hosts: List<HostState>, push: PushStore) {
        val st = push.state.value
        if (_state.value.muted.isEmpty() && st.watches.isEmpty()) return
        val moved = NotifyMove.of(_state.value.muted, st.watches, st.rules, HomeAgent.of(hosts), read(hosts))
        // The settings first: a mute that goes from here is its space's Off there by then.
        push.change { it.copy(rules = moved.rules, watches = moved.watches) }
        change { it.copy(muted = moved.muted) }
    }

    /**
     * The agents, by `machine/pane`, nothing should be heard from (Live Updates too): those muted from before
     * Notify, and those in a space whose Notify is Off.
     */
    fun muted(push: StateFlow<PushState>, hosts: StateFlow<List<HostState>>): Flow<Set<String>> =
        combine(state, push, hosts) { t, p, h ->
            t.muted + HomeAgent.of(h).filter { p.rules.notifyOf(it.space) == Notify.Off }.map { it.key }
        }

    // Read once both classic herdr and herdr-core (found a moment later) were: else herdr-core's marks would go.
    private fun read(hosts: List<HostState>): Set<String> = hosts.filter { it.agentsKnown }.map { it.machine.id }.toSet()

    private fun load(): Triage =
        runCatching { if (file.exists()) json.decodeFromString(Triage.serializer(), file.readText()) else null }.getOrNull() ?: Triage()
}
