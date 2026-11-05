package com.makefunstuff.herdr.push

import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.chat.AgentInput
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.push.LiveChanges
import com.makefunstuff.herdr.core.push.LiveItem
import com.makefunstuff.herdr.core.push.LiveItems
import com.makefunstuff.herdr.core.push.LiveTracker
import com.makefunstuff.herdr.core.push.PushEvent
import com.makefunstuff.herdr.data.HostManager
import com.makefunstuff.herdr.data.MachineStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** Where Live Updates go: Android's notifications on the phone, nowhere in the screen previews. */
interface LiveSink {
    /** Puts [item] up, or replaces it. [look]: which buttons it has; [note]: words in place of its text. */
    fun show(item: LiveItem, look: LiveLook = LiveLook.Normal, note: String? = null)

    fun cancel(tag: String)

    /** The Live Updates up now: after a restart the app knows them only from here. */
    fun active(): List<String>
}

enum class LiveLook {
    /** Its Stop button. */
    Normal,
    /** Stopping, or just stopped: no buttons. */
    Busy,
}

/**
 * Live Updates for the working agents on the machines, from pushes (herdr-push's live event), from what
 * the app sees while on screen, and from its own looks while closed. Their Stop button: Esc to the agent,
 * over SSH. During quiet
 * hours ([quiet]) they are taken down and still followed, and come back up when quiet hours end.
 */
class LiveWork(
    private val scope: CoroutineScope,
    private val hosts: HostManager,
    private val machines: MachineStore,
    private val store: PushStore,
    private val sink: LiveSink,
    private val quiet: QuietWork? = null,
    /** The agents muted on the phone, by `machine/pane`: no Live Update for them either. */
    private val muted: kotlinx.coroutines.flow.Flow<Set<String>>? = null,
) {
    private val tracker = LiveTracker()
    @Volatile private var adopted = false
    /** Taken down for quiet hours. */
    @Volatile private var hushed = false
    /** Live Updates being stopped: updates leave them be. */
    private val busy = HashSet<String>()
    /** When the app last posted a machine's agents again while on screen, so they outlast their timeout. */
    private val refreshed = HashMap<String, Long>()

    private val on: Boolean get() = store.state.value.let { it.on && it.live }

    fun start() {
        muted?.let { m -> scope.launch { m.distinctUntilChanged().collect { apply(tracker.mute(it)) } } }
        quiet?.let { q ->
            scope.launch { q.reason.map { it != null }.distinctUntilChanged().collect { if (it) hush() else wake() } }
        }
        // On screen, the app follows its machines' agents itself, also on a machine without herdr-push.
        scope.launch {
            hosts.hosts.collect { states ->
                if (!hosts.isForeground || !on) return@collect
                val now = System.currentTimeMillis()
                for (s in states) {
                    val snap = s.view?.takeIf { s.agentsKnown } ?: continue
                    if (!s.online || store.state.value.machine(s.machine.id).off) continue
                    val id = s.machine.id
                    val again = now - (refreshed[id] ?: 0) > REFRESH_MS
                    if (again) refreshed[id] = now
                    apply(tracker.update(id, LiveItem.Kind.Agent, LiveItems.agents(id, s.machine.label, snap), refresh = again))
                }
            }
        }
        // Turned off, a machine left out, or gone from the app: theirs come down.
        scope.launch {
            store.state.map { st -> (st.on && st.live) to st.machines.filterValues { it.off }.keys }.distinctUntilChanged().collect { (live, off) ->
                if (!live) {
                    adopt()
                    apply(tracker.clear())
                } else {
                    off.forEach { apply(tracker.drop(it)) }
                }
            }
        }
        scope.launch {
            machines.machines.map { list -> list.filter { it.enabled }.map { it.id }.toSet() }.distinctUntilChanged().collect { ids ->
                tracker.items().map { it.machineId }.distinct().filter { it !in ids }.forEach { apply(tracker.drop(it)) }
            }
        }
    }

    /** A push's live event from [machineId], sent at [at] (its clock, seconds). */
    fun received(machineId: String, label: String, event: PushEvent.Live, at: Long, now: Long = System.currentTimeMillis()) {
        if (!on || store.state.value.machine(machineId).off) return
        adopt()
        // The machine still watches: each one is posted again, so it stays up.
        LiveItems.of(machineId, label, event, at, now)?.let { apply(tracker.update(machineId, LiveItem.Kind.Agent, it, refresh = true)) }
    }

    /** The app's own look at [machineId]: its agents. */
    fun looked(machineId: String, label: String, snapshot: SessionSnapshot) {
        if (!on || store.state.value.machine(machineId).off) return
        adopt()
        apply(tracker.update(machineId, LiveItem.Kind.Agent, LiveItems.agents(machineId, label, snapshot), refresh = true))
    }

    /** Stops [item] over SSH, with how it went on the Live Update. It comes down when the machine says it stopped. */
    suspend fun stop(item: LiveItem) {
        val tag = item.tag
        synchronized(busy) { busy += tag }
        sink.show(tracker.item(tag) ?: item, LiveLook.Busy, "Stopping…")
        val problem = try {
            withTimeout(90_000) { hosts.borrow(item.machineId) { s -> act(s, item) } }
            null
        } catch (e: TimeoutCancellationException) {
            "Took too long. Try again, or open the app."
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            t.message ?: "Couldn't stop it"
        } finally {
            synchronized(busy) { busy -= tag }
        }
        val now = tracker.item(tag) ?: if (problem != null) item else return
        if (problem != null) return sink.show(now, LiveLook.Normal, problem)
        sink.show(now, LiveLook.Busy, "Esc sent: it stops after this step")
        // Still running a while later (Esc only interrupts a step): its Stop button again.
        scope.launch {
            delay(AFTER_STOP_MS)
            val held = synchronized(busy) { tag in busy }
            if (!held && !hushed) tracker.item(tag)?.let { sink.show(it) }
        }
    }

    private suspend fun act(s: HostSession, item: LiveItem) {
        val port = s.awaitAgent(item.paneId)
        if (port.status(item.paneId) != AgentStatus.Working) throw IOException("It isn't working any more.")
        AgentInput.keys(port, item.paneId, listOf("esc"), named = true)
        s.refresh()
    }

    /** Live Updates up from before the app started (it was closed by Android): known, so they can come down. */
    private fun adopt() {
        if (adopted) return
        adopted = true
        tracker.adopt(runCatching { sink.active() }.getOrDefault(emptyList()))
    }

    /** Quiet hours began: every Live Update comes down; the tracker still follows what runs. */
    private fun hush() {
        if (hushed) return
        hushed = true
        val up = tracker.items().map { it.tag } + runCatching { sink.active() }.getOrDefault(emptyList())
        up.distinct().forEach(sink::cancel)
    }

    /** Quiet hours ended: what runs now is up again. */
    private fun wake() {
        if (!hushed) return
        hushed = false
        val held = synchronized(busy) { busy.toSet() }
        for (i in tracker.items()) if (i.tag !in held) sink.show(i)
    }

    private fun apply(c: LiveChanges) {
        if (c.isEmpty) return
        if (quiet?.check() == true) {
            hush()
            c.cancel.forEach(sink::cancel)
            return
        }
        val held = synchronized(busy) { busy.toSet() }
        c.cancel.forEach(sink::cancel)
        for (i in c.post) if (i.tag !in held) sink.show(i)
    }

    companion object {
        /** A Live Update nothing confirmed for this long comes down by itself (Android's timeout): its machine may be gone. */
        const val TIMEOUT_MS = 75L * 60 * 1000
        private const val REFRESH_MS = 10L * 60 * 1000
        private const val AFTER_STOP_MS = 20_000L
    }
}
