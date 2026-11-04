package com.makefunstuff.herdr.core.shepherdr

import kotlinx.serialization.Serializable

/** One commit on the Accept card: its short sha and subject. */
@Serializable
data class V2Commit(val sha: String = "", val subject: String = "")

@Serializable
data class V2Commits(val count: Int = 0, val list: List<V2Commit> = emptyList())

@Serializable
data class V2FileChange(val path: String = "", val added: Int = 0, val removed: Int = 0)

@Serializable
data class V2Diff(val files: Int = 0, val added: Int = 0, val removed: Int = 0, val list: List<V2FileChange> = emptyList())

/** GitHub's checks in words; [state] `passed`, `failed`, `running`, `none` or `unknown`, never a green one assumed. */
@Serializable
data class V2Checks(val state: String = "unknown", val text: String = "")

@Serializable
data class V2Proof(val id: String = "", val title: String = "")

/** What the Accept card shows, from Git, GitHub and the task's proof artifact (`herdr-task show --evidence`). */
@Serializable
data class V2EvidenceBody(
    val summary: String? = null,
    val details: String? = null,
    val proof: V2Proof? = null,
    /** False: not a git folder. */
    val git: Boolean? = null,
    val commits: V2Commits? = null,
    /** Null when Git couldn't say. */
    val diff: V2Diff? = null,
    val checks: V2Checks? = null,
)

/** The `evidence` action's answer: the task as herdr-task shows it now, and its [evidence]. */
@Serializable
data class V2Evidence(
    val ref: String? = null,
    val title: String = "",
    /** herdr-task's state: `ready` while it can be accepted. */
    val state: String = "",
    /** Its status as herdr-task says it ("Working"). */
    val status_text: String? = null,
    val accepted_at: Double? = null,
    val evidence: V2EvidenceBody? = null,
) {
    val ready: Boolean get() = state == "ready"
}

/**
 * The Accept card's words and its reading of the evidence, as Shepherdr's own (`core/accept.js`, which the window's
 * card and the web app's share): word for word, so the three can't drift apart.
 */
object Accept {
    fun title(ref: String) = "Accept $ref?"
    const val READING = "Reading the task…"
    const val NO_SUMMARY = "No summary."
    const val CHANGES = "Changes"
    const val CHECKS = "Checks"
    const val PROOF = "Proof"
    const val DETAILS = "Details"
    const val NOT_GIT = "Not a git folder."
    const val NO_COMMITS = "No commits."
    const val NO_FILES = "· Couldn't read the files"
    const val NO_CHECKS = "No GitHub checks here"
    const val NO_PROOF = "No proof artifact."
    const val OPEN = "Open"
    const val CANCEL = "Cancel"
    const val CLOSE = "Close"
    const val ACCEPT = "Accept"
    const val ACCEPTING = "Accepting…"

    /** herdr-task ready's summary limit: an older, longer summary shows its first sentence and folds the rest. */
    const val SUMMARY_MAX = 160

    private fun plural(n: Int, one: String, many: String = "${one}s") = "$n ${if (n == 1) one else many}"
    fun commitsLine(n: Int) = plural(n, "commit")
    fun filesLine(n: Int) = "${plural(n, "file")} changed"
    fun moreLine(n: Int) = "And $n more"

    /** A proof artifact's title without its task's ref ("HERD-4: Login works" → "Login works"). */
    fun proofTitle(title: String) = title.replace(Regex("""^[A-Z]+-\d+:\s*"""), "")

    /** Where a task no longer Ready is now, calmly. */
    fun whereNow(e: V2Evidence): String =
        if (e.state == "done") (if (e.accepted_at != null) "Already accepted." else "Already done.")
        else "Not ready right now: it's in ${e.status_text ?: e.state}."

    /**
     * The summary up top and the details folded under it: a summary written before the limit, too long to read at a
     * glance, keeps its first sentence (or its first words) and the whole text goes under Details.
     */
    fun words(e: V2EvidenceBody?): Pair<String, String> {
        val said = e?.summary.orEmpty().replace(Regex("""\s+"""), " ").trim()
        val details = e?.details.orEmpty().trim()
        if (said.length <= SUMMARY_MAX || details.isNotEmpty()) return said to details
        val head = said.take(SUMMARY_MAX)
        val stop = maxOf(head.lastIndexOf(". "), head.lastIndexOf("! "), head.lastIndexOf("? "))
        val cut = if (stop >= 40) head.take(stop + 1) else head.take(maxOf(head.lastIndexOf(' '), 40)).trim() + "…"
        return cut to e!!.summary!!.trim()
    }

    private val MARKDOWN = Regex("""^\s*(?:[-*+]\s|\d+[.)]\s|#{1,6}\s|```|>\s)""", RegexOption.MULTILINE)
    private val PATH = Regex("""(^|[\s(,;:"'])((?:~|\.{1,2})?/?[\w.@-]+(?:/[\w.@-]+)+/?)(?=$|[\s),;:"'.!?])""")
    private val ABBR = Regex("""(?:^|\s)(?:e\.g|i\.e|etc|vs|cf|approx|incl|[A-Za-z])\.$""", RegexOption.IGNORE_CASE)

    private fun codify(s: String) = PATH.replace(s) { m ->
        val pre = m.groupValues[1]
        val tok = m.groupValues[2]
        val end = Regex("""\.+$""").find(tok)?.value.orEmpty()
        val path = tok.dropLast(end.length)
        val clear = Regex("""^(?:~|\.{1,2})?/""").containsMatchIn(path) || path.endsWith("/") || Regex("""\.[A-Za-z]{1,5}$""").containsMatchIn(path)
        if (clear) "$pre`$path`$end" else m.value
    }

    private fun sentences(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        for (i in line.indices) {
            val c = line[i]
            cur.append(c)
            when {
                c == '(' || c == '[' -> depth++
                c == ')' || c == ']' -> depth = maxOf(0, depth - 1)
                depth == 0 && (c == ';' || (c in ".!?" && line.getOrNull(i + 1)?.isWhitespace() == true && !ABBR.containsMatchIn(cur))) -> {
                    out += cur.toString()
                    cur.clear()
                }
            }
        }
        out += cur.toString()
        return out.map { it.trim().replace(Regex("""[;.,:]+$"""), "").trim() }.filter { it.isNotEmpty() }
    }

    /** Details to read, as Markdown: an agent's own Markdown as written; older plain text as short bullets. */
    fun readable(text: String?): String {
        val s = text.orEmpty().trim()
        if (s.isEmpty() || MARKDOWN.containsMatchIn(s)) return s
        val items = mutableListOf<String>()
        for (x in s.split(Regex("""\n+""")).flatMap { sentences(it.trim()) }) {
            if (items.isNotEmpty() && x.split(Regex("""\s+""")).size < 3) items[items.lastIndex] = items.last() + "; " + x
            else items += x
        }
        return if (items.size < 2) codify(s) else items.joinToString("\n") { "- " + codify(it) }
    }
}
