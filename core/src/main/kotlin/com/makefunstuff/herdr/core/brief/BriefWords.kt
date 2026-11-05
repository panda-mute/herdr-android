package com.makefunstuff.herdr.core.brief

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** One sentence of the brief read aloud; [ref] is the [BriefItem.ref] it is about, to show which one is being read. */
data class BriefLine(val text: String, val ref: String? = null)

/** How the brief is put in words: its title, its one line, the notification's lines, and what is read aloud. */
object BriefWords {
    /** At most this many items of a section are read aloud; the rest are "and 3 more". */
    const val SPOKEN = 5

    /** At most this many lines in the notification. */
    const val LINES = 6

    private fun local(ms: Long, zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)

    fun clock(ms: Long, zone: ZoneId): String = local(ms, zone).let { "%02d:%02d".format(it.hour, it.minute) }

    /** "Morning brief" before noon, "Afternoon brief", "Evening brief"; "Brief" in the night. */
    fun title(at: Long, zone: ZoneId): String = when (local(at, zone).hour) {
        in 4..11 -> "Morning brief"
        in 12..16 -> "Afternoon brief"
        in 17..22 -> "Evening brief"
        else -> "Brief"
    }

    /** When, as seen from [now]: "07:42", "23:10 yesterday", "Mon 18:00", "12 Oct 18:00". */
    fun time(ms: Long, now: Long, zone: ZoneId): String {
        val t = local(ms, zone)
        val days = ChronoUnit.DAYS.between(t.toLocalDate(), local(now, zone).toLocalDate())
        val clock = clock(ms, zone)
        return when {
            days <= 0L -> clock
            days == 1L -> "$clock yesterday"
            days < 7L -> "${t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} $clock"
            else -> "${t.dayOfMonth} ${t.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} $clock"
        }
    }

    /** "Since 23:10 yesterday". */
    fun since(b: Brief, zone: ZoneId): String = "Since ${time(b.since, b.at, zone)}"

    /** The brief in one line: "2 wait for you · 3 merged · 1 done", or "Nothing new · 4 at work". */
    fun headline(b: Brief): String {
        val others = b.done.size - b.merged
        val parts = listOfNotNull(
            b.waiting.size.takeIf { it > 0 }?.let { "$it wait${if (it == 1) "s" else ""} for you" },
            b.merged.takeIf { it > 0 }?.let { "$it merged" },
            others.takeIf { it > 0 }?.let { "$it done" },
            b.finished.size.takeIf { it > 0 }?.let { "${it} agent${if (it == 1) "" else "s"} finished" },
        )
        if (parts.isNotEmpty()) return parts.joinToString(" · ")
        return if (b.working.isEmpty()) "Nothing new" else "Nothing new · ${b.working.size} at work"
    }

    /** An item's one line in the notification. */
    private fun line(i: BriefItem): String = when (i.kind) {
        BriefItem.Kind.Waiting -> if (i.isTask) "Waits: ${i.title} (${i.status})" else "Asks: ${i.name} · ${i.title}"
        BriefItem.Kind.Merged -> "Merged: ${i.title}"
        BriefItem.Kind.Done -> "Done: ${i.title}"
        BriefItem.Kind.Finished -> "Finished: ${i.name} · ${i.title}"
        BriefItem.Kind.Working -> "Working: ${i.title}"
    }

    /** The notification's lines: what needs you first, then what was done; at most [LINES]. */
    fun lines(b: Brief): List<String> {
        val all = (b.waiting + b.done + b.finished).map(::line)
        val shown = all.take(if (all.size > LINES) LINES - 1 else LINES)
        val more = all.size - shown.size
        return shown + listOfNotNull(
            if (more > 0) "and $more more" else null,
            if (all.size < LINES && b.working.isNotEmpty()) "At work: ${count(b.working.count { it.isTask }, "task")}${agentsAtWork(b)}" else null,
        )
    }

    private fun agentsAtWork(b: Brief): String = b.working.count { !it.isTask }.let { if (it == 0) "" else ", ${count(it, "agent")}" }

    private fun count(n: Int, what: String) = "$n $what${if (n == 1) "" else "s"}"

    /** "One task", "3 tasks", spoken at the start of a sentence. */
    private fun many(n: Int, what: String) = if (n == 1) "One $what" else "$n ${what}s"

    private fun greeting(at: Long, zone: ZoneId): String = when (local(at, zone).hour) {
        in 4..11 -> "Good morning."
        in 12..17 -> "Good afternoon."
        else -> "Good evening."
    }

    /** "devbox", "devbox and gpu-box", "devbox, gpu-box and ci-runner", "4 machines". */
    private fun names(labels: List<String>): String = when {
        labels.size > 3 -> "${labels.size} machines"
        labels.size <= 1 -> labels.firstOrNull().orEmpty()
        else -> labels.dropLast(1).joinToString(", ") + " and " + labels.last()
    }

    /** Words a voice reads well: no Markdown marks, arrows or ellipses. */
    fun speakable(text: String): String = text
        .replace(Regex("\\s*›\\s*"), ", ")
        .replace(Regex("\\s*→\\s*"), " to ")
        .replace("…", "")
        .replace(Regex("https?://[^\\s,;)]+"), "a link")
        .replace(Regex("[`*_#>|]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** A sentence, ending in one stop. */
    private fun sentence(text: String): String {
        val t = speakable(text).trimEnd('.', ' ', ':')
        return if (t.endsWith("?") || t.endsWith("!")) t else "$t."
    }

    /**
     * The brief read aloud, as when driving: a greeting and what it covers, what waits for you, what was done, the agents that finished, what is at work, and the machines it couldn't
     * reach. Titles rather than task keys (a voice reads `AND-7` as "and 7"), and at most [SPOKEN]
     * items of a section.
     */
    fun spoken(b: Brief, zone: ZoneId): List<BriefLine> {
        val reached = b.reached
        val out = mutableListOf<BriefLine>()
        val where = names(reached.map { it.label }).let { if (it.isEmpty()) "" else ", on $it" }
        out += BriefLine("${greeting(b.at, zone)} Here is what happened since ${time(b.since, b.at, zone)}$where.")
        if (reached.isEmpty()) {
            out += BriefLine("None of your machines could be reached.")
            return out + BriefLine("That's all.")
        }

        fun section(header: String, items: List<BriefItem>, say: (BriefItem) -> String) {
            if (items.isEmpty()) return
            out += BriefLine(header)
            // Which machine, where the section has more than one.
            val several = items.map { it.machineId }.distinct().size > 1
            items.take(SPOKEN).forEach { out += BriefLine(sentence(say(it)) + if (several) " On ${it.machineLabel}." else "", it.ref) }
            if (items.size > SPOKEN) out += BriefLine("And ${items.size - SPOKEN} more.")
        }

        if (b.quiet) out += BriefLine("Nothing new happened.")
        section(if (b.waiting.size == 1) "One thing waits for you:" else "${b.waiting.size} things wait for you:", b.waiting) {
            if (it.isTask) "${it.title}: ${it.status}"
            else listOf("${it.name} asks", if (it.title != it.name) " about ${it.title}" else "", it.detail.takeIf { d -> d.isNotBlank() }?.let { d -> ": $d" }.orEmpty()).joinToString("")
        }
        val mixed = b.done.map { it.kind }.distinct().size > 1
        val doneHeader = when {
            b.done.all { it.kind == BriefItem.Kind.Merged } -> "${many(b.done.size, "task")} merged:"
            else -> "${many(b.done.size, "task")} done:"
        }
        section(doneHeader, b.done) { if (mixed) "${it.title}, ${it.status}" else it.title }
        section("${many(b.finished.size, "agent")} of yours finished:", b.finished) { "${it.name}: ${it.title}" }

        val news = listOfNotNull(
            b.started.takeIf { it > 0 }?.let { if (it == 1) "one task started" else "$it tasks started" },
            b.added.takeIf { it > 0 }?.let { if (it == 1) "one was added" else "$it were added" },
        )
        if (news.isNotEmpty()) out += BriefLine(sentence(news.joinToString(" and ").replaceFirstChar { it.uppercase() }))
        val tasks = b.working.count { it.isTask }
        val agents = b.working.size - tasks
        if (b.working.isNotEmpty()) {
            val parts = listOfNotNull(
                tasks.takeIf { it > 0 }?.let { count(it, "task") },
                agents.takeIf { it > 0 }?.let { count(it, "agent") },
            )
            out += BriefLine("Still at work: ${parts.joinToString(" and ")}.")
        }
        val away = b.machines.filter { it.coverage == BriefMachine.Coverage.Unreached }.map { it.label }
        if (away.isNotEmpty()) out += BriefLine("${names(away)} couldn't be reached.")
        return out + BriefLine("That's all.")
    }

    /** How a machine came into the brief, in words for under it; null when nothing needs saying. */
    fun coverage(m: BriefMachine, b: Brief, zone: ZoneId): String? {
        val note = when (m.coverage) {
            BriefMachine.Coverage.Journal -> null
            BriefMachine.Coverage.Partial -> when {
                m.from > 0 && m.upto > 0 -> "herdr-push kept track from ${time(m.from, b.at, zone)} to ${time(m.upto, b.at, zone)} only"
                m.from > 0 -> "herdr-push keeps track from ${time(m.from, b.at, zone)} on"
                else -> "herdr-push stopped keeping track at ${time(m.upto, b.at, zone)}, so what changed after shows as it stands now"
            }
            BriefMachine.Coverage.Compared ->
                "No herdr-push journal there: compared with what the app showed" + if (m.upto > 0) " at ${time(m.upto, b.at, zone)}" else ""
            BriefMachine.Coverage.NowOnly -> "No herdr-push journal there and nothing seen before: how it stands now"
            BriefMachine.Coverage.Unreached -> "Couldn't be reached" + (m.problem?.let { ": $it" } ?: "")
        }
        val missing = m.problem?.takeIf { m.coverage != BriefMachine.Coverage.Unreached }
        return listOfNotNull(note, missing).joinToString(". ").ifEmpty { null }
    }
}
