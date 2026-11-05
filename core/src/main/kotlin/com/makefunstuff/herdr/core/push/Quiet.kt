package com.makefunstuff.herdr.core.push

import kotlinx.serialization.Serializable
import java.time.LocalDateTime

/**
 * Quiet hours, as Puggie keeps them: while the phone is in Do Not Disturb, Bedtime or Driving, or on
 * a schedule, notifications that can wait (an agent finished, a Live Update's progress) are held and
 * come as one summary when quiet hours end. What needs you (an agent asks, a task waits for you)
 * still comes through. Plain data, so the rules are tested without a phone.
 */
@Serializable
data class QuietHours(
    /** Quiet while the phone is: Do Not Disturb or a mode such as Bedtime, or driving (car mode). */
    val followPhone: Boolean = false,
    /** Quiet from [start] to [end] on [days]. */
    val schedule: Boolean = false,
    /** Minutes after midnight, on the phone's clock. Equal to [end]: the whole day. */
    val start: Int = 22 * 60,
    val end: Int = 7 * 60,
    /** The days a quiet stretch starts on, 1 (Monday) to 7 (Sunday): one from 22:00 to 07:00 on Friday ends on Saturday morning. */
    val days: Set<Int> = ALL_DAYS,
) {
    val on: Boolean get() = followPhone || schedule

    /** Whether the schedule makes [at] quiet. */
    fun scheduled(at: LocalDateTime): Boolean {
        if (!schedule || days.isEmpty()) return false
        val m = at.hour * 60 + at.minute
        val day = at.dayOfWeek.value
        val yesterday = if (day == 1) 7 else day - 1
        return when {
            start == end -> day in days
            start < end -> day in days && m >= start && m < end
            else -> (day in days && m >= start) || (yesterday in days && m < end)
        }
    }

    /**
     * Why [at] is quiet, in words for the settings ("Bedtime or Do Not Disturb is on"), or null when
     * it isn't. [phone]: what the phone is in now, null when nothing.
     */
    fun reason(at: LocalDateTime, phone: PhoneQuiet?): String? = when {
        followPhone && phone != null -> phone.words
        scheduled(at) -> if (start == end) "Quiet all day today" else "Quiet until ${clock(end)}"
        else -> null
    }

    /** When the schedule next turns quiet on or off after [at] (to the minute), or null when it never does. */
    fun nextChange(at: LocalDateTime): LocalDateTime? {
        if (!schedule || days.isEmpty()) return null
        val now = scheduled(at)
        val midnight = at.toLocalDate().atStartOfDay()
        return (0L..8L).asSequence()
            .flatMap { d -> sequenceOf(start, end).distinct().map { midnight.plusDays(d).plusMinutes(it.toLong()) } }
            .filter { it.isAfter(at) }
            .sorted()
            .firstOrNull { scheduled(it) != now }
    }

    /** As the settings show it: "Off", "Bedtime, Driving and DND", "22:00 to 07:00", or both. */
    val label: String get() {
        if (!on) return "Off"
        val parts = listOfNotNull(
            if (followPhone) "Bedtime, Driving or Do Not Disturb" else null,
            if (schedule) scheduleLabel else null,
        )
        return parts.joinToString(" · ")
    }

    /** "22:00 to 07:00", "All day", with the days when not every day: "22:00 to 07:00, Mon–Fri". */
    val scheduleLabel: String get() {
        val hours = if (start == end) "All day" else "${clock(start)} to ${clock(end)}"
        val which = daysLabel(days)
        return if (which.isEmpty()) hours else "$hours, $which"
    }

    companion object {
        val ALL_DAYS: Set<Int> = (1..7).toSet()
        val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

        fun clock(minutes: Int): String = "%02d:%02d".format(minutes / 60 % 24, minutes % 60)

        /** "" for every day, "Mon–Fri", "Sat, Sun", "No days". */
        fun daysLabel(days: Set<Int>): String = when {
            days.containsAll(ALL_DAYS) -> ""
            days.isEmpty() -> "No days"
            days == (1..5).toSet() -> "Mon–Fri"
            days == setOf(6, 7) -> "Sat, Sun"
            else -> days.sorted().joinToString(", ") { DAY_NAMES[it - 1] }
        }

        /**
         * Whether [alert] waits while it is quiet: only what can wait (what finished, a limit).
         * What needs you, what went wrong, and a test, come through.
         */
        fun holds(alert: Alert): Boolean = alert.kind == Alert.Kind.Finished || alert.kind == Alert.Kind.Limits
    }
}

/** What the phone is in, as far as the app can tell: Do Not Disturb (Bedtime turns it on), or car mode. */
enum class PhoneQuiet(val words: String) {
    DoNotDisturb("Bedtime or Do Not Disturb is on"),
    Driving("Driving"),
}

/** The one notification held ones come as, when quiet hours end. */
data class QuietSummary(val title: String, val text: String, val lines: List<String>) {
    companion object {
        /** At most this many lines; the rest are "and 3 more". */
        const val LINES = 6

        fun of(held: List<Alert>): QuietSummary {
            val n = held.size
            val title = if (held.all { it.kind == Alert.Kind.Finished && it.paneId.isNotEmpty() }) {
                if (n == 1) "An agent finished while it was quiet" else "$n agents finished while it was quiet"
            } else {
                if (n == 1) "1 notification from quiet hours" else "$n notifications from quiet hours"
            }
            val lines = held.take(if (n > LINES) LINES - 1 else LINES).map { a -> listOf(a.title, a.text).filter { it.isNotBlank() }.joinToString(": ") }
            val more = if (n > LINES) listOf("and ${n - (LINES - 1)} more") else emptyList()
            return QuietSummary(title, held.joinToString(", ") { it.title }, lines + more)
        }
    }
}
