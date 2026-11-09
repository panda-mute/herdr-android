package com.makefunstuff.herdr.ui.health

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.makefunstuff.herdr.core.api.plainMessage
import com.makefunstuff.herdr.core.health.HealthClient
import com.makefunstuff.herdr.core.health.HealthReader
import com.makefunstuff.herdr.core.health.HealthReport
import com.makefunstuff.herdr.core.health.HealthSample
import com.makefunstuff.herdr.core.health.LabeledProc
import com.makefunstuff.herdr.core.health.ProcLabels
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.data.HealthStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A machine's health screen: what it reads (every few seconds while the screen is in front), who
 * each process belongs to (its worktree, herdr's panes), and what it stops.
 */
class HealthModel(private val session: HostSession, private val store: HealthStore, private val scope: CoroutineScope) {
    var sample by mutableStateOf<HealthSample?>(null)
        private set
    /** Why the last look failed, while it does. */
    var problem by mutableStateOf<String?>(null)
        private set
    var procs by mutableStateOf<List<LabeledProc>>(emptyList())
        private set

    /** What is being done now (shown on the action), or null. */
    var busy by mutableStateOf<String?>(null)
        private set

    /** One-off messages: results and refusals. */
    var message by mutableStateOf<String?>(null)

    private suspend fun client(): HealthClient {
        val conn = session.awaitConnection()
        return HealthClient { cmd, timeout -> conn.run(cmd, timeout) }
    }

    /** One look. */
    suspend fun look() {
        try {
            val c = client()
            val id = session.machine.id
            // The machine's clock, not the phone's: the hour ends at its newest reading.
            if (store.needsHostHistory(id)) launch { c.history().let { h -> h.maxOfOrNull { it.at }?.let { store.addHistory(id, h, it) } } }
            val s = c.sample()
            store.add(id, s)
            sample = s
            problem = null
            relabel()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            problem = t.plainMessage()
        }
    }

    private fun launch(block: suspend () -> Unit) = scope.launch { runCatching { block() } }

    private fun relabel() {
        val s = sample ?: return
        val state = session.state.value
        procs = ProcLabels.label(s, state.snapshot)
    }

    /** What is low now, with what uses the most; null when nothing is. */
    val report: HealthReport? get() = sample?.let { s -> HealthReport.of(s, procs)?.takeIf { it.low.isNotEmpty() } }

    val gradle: List<LabeledProc> get() = procs.filter { it.own && it.kind.gradle }

    /** Whether the machine last read is a Windows one; false until it has been read. */
    val windows: Boolean get() = sample?.windows == true

    /** Stops [targets] (SIGTERM then SIGKILL after 5 s; on Windows `taskkill` then `taskkill /F`), [what] naming them in the outcome. */
    fun stop(targets: List<LabeledProc>, what: String) = act("Stopping $what…") {
        val k = client().kill(ProcLabels.targets(targets))
        look()
        outcome(k, what)
    }

    fun stopGradle() = stop(gradle, if (gradle.size == 1) "the Gradle daemon" else "${gradle.size} Gradle daemons")

    private fun outcome(k: HealthReader.Killed, what: String): String {
        val stopped = k.stopped.size
        return when {
            stopped == 0 && k.failed.isNotEmpty() -> "Couldn't stop $what: not allowed"
            stopped == 0 -> "Nothing to stop: $what had already ended"
            k.failed.isNotEmpty() -> "Stopped $stopped; ${k.failed.size} couldn't be stopped"
            k.forced.isNotEmpty() -> "Stopped $what (${k.forced.size} had to be killed)"
            else -> "Stopped $what"
        }
    }

    private fun act(words: String, block: suspend () -> String) {
        if (busy != null) return
        busy = words
        scope.launch {
            try {
                message = block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                message = t.plainMessage()
            } finally {
                busy = null
            }
        }
    }
}

/** What the stop dialog says happens to the processes on [machine]. */
fun stopNote(machine: String, windows: Boolean): String =
    if (windows) "Each is asked to close on $machine, and ended by force if it refuses or still runs after 5 seconds. Unsaved work in them is lost."
    else "Each gets SIGTERM on $machine, and SIGKILL if it still runs after 5 seconds. Unsaved work in them is lost."
