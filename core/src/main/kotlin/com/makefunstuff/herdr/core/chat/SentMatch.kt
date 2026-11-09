package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.host.Images

/**
 * Matching a message the app sent to the copy the transcript, screen or board shows later.
 * Matching is per message: a sent message is confirmed by the n-th matching message beyond those
 * already there when it was sent, so identical messages sent on purpose stay separate.
 */
object SentMatch {
    private const val HEAD = 200
    private const val TRUNCATED = 100

    /** Whitespace collapsed and Markdown emphasis marks removed. */
    fun normalize(s: String): String =
        s.replace(Regex("[*_`>]+"), "").replace(Regex("\\s+"), " ").trim()

    /**
     * Whether [seen] is [sent] as the channel recorded it: equal once normalised, or one a prefix of
     * the other (the board appends lines, a channel truncates).
     */
    fun same(sent: String, seen: String): Boolean {
        if (sameText(sent, seen)) return true
        // A message with pictures goes as `@path` mentions in front of the words; the agent may record
        // them as its own attachments instead (Claude Code's `[Image #1]`, Codex's and pi's image blocks
        // next to the words), so it is the same message when the words are.
        val pictures = Images.split(sent)
        val seenWords = withoutPictures(seen)
        // Both ways: words sent alone are those of a message the agent shows with pictures, as they count up together.
        if (pictures.paths.isEmpty()) return seenWords != seen.trim() && sameText(sent, seenWords)
        val words = withoutPictures(sent)
        if (words.isBlank()) return seenWords.isBlank() || pictures.paths.any { it in seen }
        return sameText(words, seenWords)
    }

    // Claude Code's `[Image #1]`; Codex's `[image 1024x768 PNG]`.
    private val placeholder = Regex("\\[Image #\\d+]|\\[image (\\d+ )?\\d+x\\d+[^\\]\\n]*]")
    private val spaces = Regex("[ \\t]{2,}")

    /** [text] without the agent's own marks for its pictures, and nothing else changed: for showing it. */
    fun withoutMarks(text: String): String {
        val rest = text.replace(markWithSpace, "")
        return if (rest == text) text else rest.trimEnd()
    }
    private val markWithSpace = Regex("(?:${placeholder.pattern})(?: |\\r?\\n)?")

    /** [text]'s words, without the pictures it names (`@path`) or the agent's own marks for them. */
    fun withoutPictures(text: String): String =
        Images.split(text).text.replace(placeholder, " ").lines().joinToString("\n") { it.replace(spaces, " ").trim() }.trim()

    private fun sameText(sent: String, seen: String): Boolean {
        val a = normalize(sent)
        val b = normalize(seen)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.take(HEAD) == b.take(HEAD) && (a.length >= HEAD || b.length >= HEAD || a == b)) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (!long.startsWith(short)) return false
        val rest = long.substring(short.length).trim()
        // Lines the board appends, in brackets; or a long message cut short.
        return (rest.startsWith("[") && rest.endsWith("]")) || short.length >= TRUNCATED
    }

    /** How many of [seen] are [sent]. */
    fun count(sent: String, seen: List<String>): Int = seen.count { same(sent, it) }

    /** Occurrences of the start of [sent] in a screen's text. */
    fun countOnScreen(sent: String, screen: String): Int {
        val pictures = Images.split(sent)
        val words = if (pictures.paths.isEmpty()) sent else withoutPictures(sent)
        // Pictures alone: the agent may show them as its own marks (Claude Code's `[Image #1]`) rather than their paths.
        // Every mark counts, also one still in its input box or of a message with words; the baseline takes those
        // already there at the send. Good enough for the screen, the fallback until the transcript is there.
        if (pictures.paths.isNotEmpty() && words.isBlank()) return occurrences(sent, screen) + occurrences("[Image #", screen)
        // A message with pictures is found by its words: the agent may show its pictures its own way.
        return occurrences(words, screen)
    }

    private fun occurrences(sent: String, screen: String): Int {
        // A terminal can wrap in the middle of a word: compare without any whitespace.
        val head = normalize(sent).filterNot { it.isWhitespace() }.take(40)
        if (head.isEmpty()) return 0
        val text = screen.filterNot { it.isWhitespace() || it in "*_`>" }
        var n = 0
        var i = text.indexOf(head)
        while (i >= 0) { n++; i = text.indexOf(head, i + head.length) }
        return n
    }

    /** A sent message waiting to be seen; [baseline] is how many like it were already shown when it was sent. */
    data class Pending(
        val text: String,
        val baseline: Int,
        /** Ids of the user messages in the transcript when it was sent: one not among them is new. */
        val known: Set<String> = emptySet(),
        val sentAt: Long = 0,
    )

    /**
     * What to report as the shown count for [p] against a window that can slide: it is taken as
     * confirmed ([Int.MAX_VALUE]) when the matches fell in number (older copies scrolled out as
     * new text came in), or when a copy has stood there a while after the send.
     */
    fun slid(p: Pending, count: Int, now: Long, graceMs: Long = 30_000): Int = when {
        count > p.baseline -> count
        count in 1 until p.baseline -> Int.MAX_VALUE
        count >= 1 && p.sentAt > 0 && now - p.sentAt > graceMs -> Int.MAX_VALUE
        else -> count
    }

    /** The pending messages not yet confirmed, where [counted] says how many matching messages are shown. */
    fun unconfirmed(pending: List<Pending>, counted: (Pending) -> Int): List<Pending> {
        val nth = HashMap<String, Int>()
        return pending.filter { p ->
            // Picture messages are matched by their words, so they count up with the messages of those words
            // (pictures alone, with each other): the n-th needs the n-th match.
            val key = if (Images.split(p.text).paths.isEmpty()) normalize(p.text).take(HEAD)
            else normalize(withoutPictures(p.text)).take(HEAD).ifEmpty { "\u0000pictures" }
            val k = (nth[key] ?: 0) + 1
            nth[key] = k
            counted(p) < p.baseline + k
        }
    }

    /**
     * The pending messages the transcript's [users] messages don't show yet. A matching message that
     * wasn't there at send time ([Pending.known]) is the new one, even when older copies slid out of a
     * tail-only read; the count covers ids that repeat.
     */
    fun unseen(pending: List<Pending>, users: List<ChatItem.User>, now: Long): List<Pending> {
        if (pending.isEmpty()) return pending
        val texts = users.map { it.text }
        return unconfirmed(pending) { p ->
            val fresh = users.count { it.id !in p.known && same(p.text, it.text) }
            maxOf(p.baseline + fresh, slid(p, count(p.text, texts), now))
        }
    }
}
