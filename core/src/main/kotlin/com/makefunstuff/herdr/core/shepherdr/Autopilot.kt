package com.makefunstuff.herdr.core.shepherdr

import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Autopilot as Shepherdr 3.1 has it (`core/autopilot.js` in its checkout, `docs/parity/autopilot.md`): one setting for
 * every space of a machine, its heartbeat, schedule and events (Settings › Autopilot, Everywhere's page), and each space's
 * own switch in its Assistant's header. herdr-task keeps it on the machine; the app reads it from the snapshot
 * ([V2Place.pilot], the Autopilot settings page) and sets it as the web does ([V2Actions.autopilot],
 * [V2Actions.autopilotSetting]). The words are 3.1's own.
 */
object Autopilot {
    const val TITLE = "Autopilot"
    const val ABOUT = "How Autopilot works in every space. Turn it on in a space with the switch in its Assistant's header: " +
        "its Assistant then wakes on a schedule or when something happens to its tasks, and works from the heartbeat."

    /** The rows' keys, as the service takes them. */
    const val HEARTBEAT = "autopilot.heartbeat"
    const val EVERY = "autopilot.every"
    const val EVENTS = "autopilot.events"

    /** Each row's label and its line under it. */
    val ROWS = mapOf(
        HEARTBEAT to ("Heartbeat" to "What it works from: direction, priorities, limits and when to stop."),
        EVERY to ("Schedule" to "When it wakes by itself."),
        EVENTS to ("Events" to "These wake a space's Assistant at once when they happen to its tasks."),
    )

    /** The schedule's picks (herdr-task's `--every` words) and their labels; anything else is [CUSTOM]. */
    val PRESETS = listOf("" to "Off", "every 30 minutes" to "Every 30 min", "every 60 minutes" to "Every hour", "weekdays 9:00" to "Weekdays 9:00")
    const val CUSTOM = "Custom…"
    const val CUSTOM_PLACEHOLDER = "Every 2 hours on weekdays"
    const val CUSTOM_HINT = "Like \"every 2 hours on weekdays from 8:00 to 18:00\". Saved when you leave the box."

    /** The task events that wake it, in herdr-task's order. */
    val EVENT_PICKS = listOf("added" to "Added", "ready" to "Ready", "failed" to "Failed", "needs-you" to "Needs you", "merged" to "Merged", "stalled" to "Stalled")

    const val SAVED = "Saved"
    const val NO_HEARTBEAT = "Couldn't make the heartbeat file."
    /** The heartbeat is saved this long after typing stops (and on leaving its box). */
    const val SAVE_AFTER_MS = 600L
    const val WRITE_HEARTBEAT = "Write a heartbeat"

    /** Everywhere's until set (herdr-task's `everywhere_default`). */
    const val DEFAULT_EVERY = "every 60 minutes"

    /** A schedule's pick: its preset's value, else null (Custom). */
    fun presetOf(every: String): String? = every.trim().lowercase().let { v -> PRESETS.firstOrNull { it.first == v }?.first }

    /** Events in herdr-task's order, each once. */
    fun eventsIn(list: Collection<String>): List<String> = EVENT_PICKS.map { it.first }.filter { it in list }

    /**
     * What follows "Autopilot" in an Assistant's header while on: waking (a wake's turn runs), queued (waits for an idle
     * Assistant), the next scheduled wake ("Next 10:00" within a day, else "Next Wed"), else what wakes it. Empty while off.
     */
    fun headerDetail(p: V2Pilot?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (p == null || !p.on) return ""
        if (p.inflight) return "Waking"
        if (p.pending) return "Queued"
        p.nextRun?.let { next ->
            val at = Instant.ofEpochMilli(next.toLong()).atZone(zone)
            return if (next - nowMs < 86_400_000) "Next %02d:%02d".format(at.hour, at.minute)
            else "Next " + at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        }
        return if (p.events.isNotEmpty()) "On events" else "Run now only"
    }

    /**
     * Settings › Autopilot as the snapshot holds it, Everywhere's page; null where there is none (the Autopilot
     * add-on is off, or a Shepherdr without it).
     */
    fun settingsOf(s: V2Snapshot): AutopilotSettings? {
        if ("autopilot" in s.off) return null
        val page = s.settings.firstOrNull { it.name == TITLE && (it.level == null || it.level == "all") } ?: return null
        fun row(key: String) = page.rows.firstOrNull { it.key == key }
        val beat = row(HEARTBEAT)
        return AutopilotSettings(
            heartbeat = beat?.value.orEmpty(),
            file = beat?.file,
            every = row(EVERY)?.value ?: DEFAULT_EVERY,
            events = eventsIn(row(EVENTS)?.value.orEmpty().split(',').map { it.trim() }),
            about = page.about ?: ABOUT,
            warns = page.rows.mapNotNull { r -> r.warn?.takeIf { it.isNotBlank() }?.let { r.key to it } }.toMap(),
        )
    }

    /** [p]'s switch as the header shows it: none where the add-on is off or the space is another machine's. */
    fun pilotOf(s: V2Snapshot, p: V2Place): V2Pilot? = p.pilot?.takeIf { "autopilot" !in s.off }
}

/** Settings › Autopilot: the one heartbeat (its words and its [file]), the schedule's words and the events. */
data class AutopilotSettings(
    val heartbeat: String = "",
    val file: String? = null,
    /** herdr-task's `--every` words; empty is Off. */
    val every: String = Autopilot.DEFAULT_EVERY,
    val events: List<String> = emptyList(),
    val about: String = Autopilot.ABOUT,
    /** A refusal or fault under a row, by its key. */
    val warns: Map<String, String> = emptyMap(),
)
