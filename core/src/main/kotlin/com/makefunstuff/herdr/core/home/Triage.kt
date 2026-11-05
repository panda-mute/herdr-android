package com.makefunstuff.herdr.core.home

import com.makefunstuff.herdr.core.push.AlertRules
import com.makefunstuff.herdr.core.push.Notify
import com.makefunstuff.herdr.core.push.Watch
import kotlinx.serialization.Serializable

/**
 * The phone's own triage of the Agents list, by [HomeAgent.key]: agents pinned on top (in the order
 * pinned). What reaches the phone is each space's [Notify] instead (with the notification rules); the
 * agents muted before it ([muted]) wait here until [NotifyMove] puts them on their spaces. Seen marks
 * are kept apart (SeenStore), as before. Plain data, so the rules are tested without a phone.
 */
@Serializable
data class Triage(
    val pins: List<String> = emptyList(),
    /** Agents muted before Notify, not moved onto their space yet (their machine wasn't read since): they stay muted till then. */
    val muted: Set<String> = emptySet(),
    /** Whether the person has triaged anything yet: until then the list says how (swipe right, swipe left). */
    val used: Boolean = false,
) {
    fun pinned(key: String): Boolean = key in pins

    /** Pinned on top. */
    fun pin(key: String): Triage = copy(pins = pins - key + key)

    fun unpin(key: String): Triage = copy(pins = pins - key)

    /**
     * What is worth keeping: nothing of a pane that is gone. [agents]: every agent on the machines' last
     * snapshots; [read]: the machines those come from. An agent on a machine not read now (offline since
     * the app started) is kept as it is; one on a machine taken out of the app ([machines], every machine
     * the app has) goes.
     */
    fun keep(agents: List<HomeAgent>, read: Set<String>, machines: Set<String>): Triage {
        val byKey = agents.associateBy { it.key }
        fun there(key: String): Boolean {
            if (key in byKey) return true
            val machine = machines.firstOrNull { key.startsWith("$it/") } ?: return false
            return machine !in read
        }
        return copy(pins = pins.filter(::there), muted = muted.filter(::there).toSet())
    }

    /** Whether notifications about [machineId]'s pane [paneId] are muted from before Notify (until moved). */
    fun mutes(machineId: String, paneId: String): Boolean = paneId.isNotEmpty() && "$machineId/$paneId" in muted
}

/**
 * Moves the phone's alert choices from before Notify onto the agents' spaces, as each agent is found on
 * its machine: a mute becomes its space's Off, a watch ("tell me when it finishes") its space's All; a
 * watch wins over a mute in one space, and a space that already picked its own keeps it. One not found
 * on a machine read now is gone, and goes; one on a machine not read yet waits.
 */
object NotifyMove {
    data class Result(val muted: Set<String>, val watches: List<Watch>, val rules: AlertRules)

    fun of(muted: Set<String>, watches: List<Watch>, rules: AlertRules, agents: List<HomeAgent>, read: Set<String>): Result {
        if (muted.isEmpty() && watches.isEmpty()) return Result(muted, watches, rules)
        val byKey = agents.associateBy { it.key }
        fun waits(key: String, machineId: String) = key !in byKey && machineId !in read
        val picks = LinkedHashMap<String, Notify>()
        val mutedLeft = muted.filter { key ->
            val a = byKey[key]
            if (a != null) picks.putIfAbsent(a.space, Notify.Off)
            a == null && waits(key, key.substringBefore('/'))
        }.toSet()
        val watchesLeft = watches.filter { w ->
            val key = "${w.machineId}/${w.paneId}"
            val a = byKey[key]
            if (a != null) picks[a.space] = Notify.All
            a == null && waits(key, w.machineId)
        }
        val next = picks.entries.fold(rules) { r, (space, n) -> if (space in r.spaces) r else r.withSpace(space, n) }
        return Result(mutedLeft, watchesLeft, next)
    }
}
