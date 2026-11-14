package com.makefunstuff.herdr.core.chat

/**
 * Bringing back what you sent before into the message box, as a shell's history does (Alma 4.5's
 * swipe on the box, its desktop's Up and Down arrows): [older] steps back from the newest, [newer]
 * forward, and past the newest the box empties again. Only an empty box or one that still holds
 * what was brought back moves: a draft you are writing stays as it is.
 *
 * [sent] is the chat's messages of yours, oldest first. [at] counts back from the newest (0 is the
 * last one sent); null when nothing is brought back.
 */
object SentRecall {
    data class Step(val at: Int?, val text: String)

    /** [sent] once, without blank lines or a message repeated right after itself. */
    fun history(sent: List<String>): List<String> =
        sent.map { it.trim() }.filter { it.isNotEmpty() }.fold(ArrayList()) { acc, s -> acc.apply { if (lastOrNull() != s) add(s) } }

    /** Where [draft] stands in [history]: the message [at] still unchanged, or nothing brought back. */
    private fun place(history: List<String>, draft: String, at: Int?): Int? =
        at?.takeIf { it in history.indices && history[history.size - 1 - it] == draft }

    /** Whether the box may move: empty, or still the message brought back. */
    fun free(history: List<String>, draft: String, at: Int?): Boolean = draft.isBlank() || place(history, draft, at) != null

    /** One message further back; null when the box keeps what it has. */
    fun older(history: List<String>, draft: String, at: Int?): Step? {
        if (!free(history, draft, at)) return null
        val next = (place(history, draft, at) ?: -1) + 1
        if (next >= history.size) return null
        return Step(next, history[history.size - 1 - next])
    }

    /** One message towards the newest, then the empty box; null when the box keeps what it has. */
    fun newer(history: List<String>, draft: String, at: Int?): Step? {
        if (!free(history, draft, at)) return null
        val now = place(history, draft, at) ?: return null
        return if (now == 0) Step(null, "") else Step(now - 1, history[history.size - now])
    }
}
