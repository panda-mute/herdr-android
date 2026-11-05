package com.makefunstuff.herdr.core.push

import kotlinx.serialization.Serializable

/**
 * What reaches the phone from one space (Shepherdr 3.1's Notify): every finish too, only what needs
 * you (a question), or nothing. A space is one row of the spaces drawer: its machine and its name.
 */
@Serializable
enum class Notify(val label: String) {
    All("All"),
    NeedsMe("Needs me"),
    Off("Off");

    /** Whether an agent's news of [kind] comes from a space set to this. A Ready task waits on you: [Alert.Kind.NeedsYou] ([TaskAlerts]). */
    fun allows(kind: Alert.Kind): Boolean = when (this) {
        All -> true
        NeedsMe -> kind != Alert.Kind.Finished
        Off -> false
    }

    /** As the sheet and the drawer say what it lets through. */
    val hint: String get() = when (this) {
        All -> "Its questions, and each time an agent there finishes"
        NeedsMe -> "When an agent there asks you something, or a task is ready"
        Off -> "Nothing from its agents"
    }

    companion object {
        /** A space's key, as the drawer has it: its machine and its name (any case). */
        fun key(machineId: String, space: String) = "$machineId/${space.lowercase()}"

        /** The space an agent in [where] (`workspace › tab`, as pushes and the app say it) is in. */
        fun spaceOf(where: String): String = where.substringBefore(" › ")
    }
}

/**
 * Which notifications reach the phone: a switch per [Alert.Kind] (what finished is off unless you
 * turn it on: a ping for every finished turn teaches you to ignore the ones that matter), each space's
 * own [Notify] where one was picked, and whether they stay silent while you are at one of your machines.
 * A test always comes. Plain data, so the rules are tested without a phone.
 */
@Serializable
data class AlertRules(
    /** An agent asks something, a machine runs low. */
    val needsYou: Boolean = true,
    /** An agent finished its turn. */
    val finished: Boolean = false,
    /** A review or a check failed, or a task went wrong. */
    val wentWrong: Boolean = true,
    /** An AI provider near (80%, 95%) or at a limit. */
    val limits: Boolean = true,
    /** No sound or pop-up while you are at a machine ([Presence]): you see it there. */
    val holdAtMachine: Boolean = true,
    /** The spaces that picked their own Notify, by [Notify.key]: their agents' questions and finishes follow it, not the switches. */
    val spaces: Map<String, Notify> = emptyMap(),
) {
    fun allows(alert: Alert): Boolean {
        if (alert.space.isNotEmpty() && (alert.kind == Alert.Kind.NeedsYou || alert.kind == Alert.Kind.Finished)) {
            spaces[Notify.key(alert.machineId, alert.space)]?.let { return it.allows(alert.kind) }
        }
        return when (alert.kind) {
            Alert.Kind.NeedsYou -> needsYou
            Alert.Kind.Finished -> finished
            Alert.Kind.WentWrong -> wentWrong
            Alert.Kind.Limits -> limits
            Alert.Kind.Test -> true
        }
    }

    /** What a space that picked nothing gets, from the switches: All with Finished on, Needs me with Needs you alone, else Off. */
    val everywhere: Notify get() = when {
        finished -> Notify.All
        needsYou -> Notify.NeedsMe
        else -> Notify.Off
    }

    /** [space]'s Notify ([Notify.key]): its own, else [everywhere]'s. */
    fun notifyOf(space: String): Notify = spaces[space] ?: everywhere

    /** [space] set to [n]; set to what it would get anyway (or null), it follows the switches again. */
    fun withSpace(space: String, n: Notify?): AlertRules =
        copy(spaces = if (n == null || n == everywhere) spaces - space else spaces + (space to n))

    companion object {
        /** Everything on, nothing held: where no rules apply. */
        val ALL = AlertRules(needsYou = true, finished = true, wentWrong = true, limits = true, holdAtMachine = false)
    }

    /** As the settings show it: "Needs you and Went wrong", "Nothing", "Needs you, Finished and Went wrong". */
    val label: String get() {
        val on = listOfNotNull("Needs you".takeIf { needsYou }, "Finished".takeIf { finished }, "Went wrong".takeIf { wentWrong }, "Limits".takeIf { limits })
        return when (on.size) {
            0 -> "Nothing"
            1, 2 -> on.joinToString(" and ")
            else -> on.dropLast(1).joinToString(", ") + " and " + on.last()
        }
    }
}

/**
 * What a machine says about the person at it, measured as it sent a push (or as the app asked):
 * how long ago someone last typed in a herdr client there (or touched herdr-push's presence file),
 * and whether a desktop session there is unlocked. Null: it couldn't tell (no client, no desktop).
 */
@Serializable
data class Presence(val typedAgo: Long? = null, val unlocked: Boolean? = null)

/**
 * Where the person was last seen at a machine, from what each machine said and when: they are "at
 * the machine" while one typed in herdr in the last two minutes, or said so of its unlocked screen in
 * the last two minutes. Any machine counts, as you are at your desk then, whichever one pings.
 */
class PresenceBook {
    private data class Seen(val label: String, val at: Long, val typedAt: Long?, val unlocked: Boolean)

    private val seen = HashMap<String, Seen>()

    /** [machineId] ([label]) said [p] at [at] (the phone's clock, ms; a push's own time, when it is older than now). */
    @Synchronized
    fun record(machineId: String, label: String, p: Presence, at: Long) {
        seen[machineId] = Seen(label, at, p.typedAgo?.let { at - it.coerceAtLeast(0) * 1000 }, p.unlocked == true)
    }

    /** Whether the person is at a machine at [now], and which: its label, or null when at none. */
    @Synchronized
    fun where(now: Long): String? = seen.values
        .filter { s -> (s.typedAt != null && now - s.typedAt in -FUTURE_MS..WINDOW_MS) || (s.unlocked && now - s.at in -FUTURE_MS..WINDOW_MS) }
        .maxByOrNull { maxOf(it.typedAt ?: 0, if (it.unlocked) it.at else 0) }
        ?.label

    fun present(now: Long): Boolean = where(now) != null

    companion object {
        /** Typed this recently, or a screen unlocked this recently: at the machine. */
        const val WINDOW_MS = 2L * 60 * 1000
        /** A machine's clock a little ahead of the phone's still counts. */
        private const val FUTURE_MS = 30_000L
    }
}

/**
 * A one-shot watch ("tell me when claude finishes") from before the spaces' [Notify]: still read from
 * the settings once, to become its space's Notify: All ([com.makefunstuff.herdr.core.home.NotifyMove]).
 */
@Serializable
data class Watch(
    val machineId: String,
    val what: What,
    /** The agent watched. */
    val paneId: String = "",
    /** As the sheet named it: the agent's name. */
    val name: String,
    /** The agent's state change when the watch was set. */
    val seq: Long = 0,
    /** When it was set (ms). */
    val at: Long = 0,
) {
    enum class What { AgentDone }
}
