package com.makefunstuff.herdr.core.chat

/**
 * Where a chat stays, the same in every chat of the app (an agent's, both Coordinators'): it opens at
 * the newest message. At the end
 * it follows whatever grows there (an answer being written, a picture loading, the working line);
 * scrolled up, new messages are counted without moving it. Sending brings the reader down ([sent]).
 *
 * The rules alone, on message keys: the app wires them to its list (`rememberStickToBottom`).
 */
data class StickToBottom(
    /** Every message seen so far, by key (one folded away and shown again isn't new); null before the first. */
    val known: Set<String>? = null,
    /** The messages there when the reader left the end; null while at it. */
    val since: Set<String>? = null,
    /** The first message that came in while the reader was up: a New line goes above it, until they write. */
    val unread: String? = null,
) {
    /** What the list does after [messages] (their keys, oldest first) came in: whether it goes to the end. */
    class Seen(val next: StickToBottom, val toEnd: Boolean)

    /** [messages] as they are now, the reader at the end (following it) or not. */
    fun seen(messages: List<String>, atEnd: Boolean): Seen {
        val before = known
        if (messages.isEmpty()) return Seen(copy(known = before ?: emptySet()), toEnd = false)
        val now = copy(known = before.orEmpty() + messages)
        // Opened, or the first messages loaded: at the newest.
        if (before.isNullOrEmpty()) return Seen(now, toEnd = true)
        // Anything inserted before the last known message is history, not a new arrival.
        // This also covers replayed/folded rows exposed while loading an earlier page.
        val boundary = messages.indexOfLast { it in before }
        val history = if (boundary >= 0) messages.take(boundary + 1).filterNot { it in before }.toSet() else emptySet()
        val retained = now.copy(since = since?.plus(history))
        val new = messages.drop(boundary + 1).firstOrNull { it !in before }
        return when {
            new == null -> Seen(retained, toEnd = atEnd)
            atEnd -> Seen(retained, toEnd = true)
            // Up the chat: keep reading; mark the first actual arrival.
            // (One already marked since the reader left keeps its line.)
            else -> Seen(retained.copy(unread = unread?.takeIf { u -> since != null && u !in since && u in messages } ?: new), toEnd = false)
        }
    }

    /** The reader got to the end, or left it, with [messages] there. */
    fun moved(atEnd: Boolean, messages: List<String>): StickToBottom =
        if (atEnd) copy(since = null) else if (since == null) copy(since = messages.toSet()) else this

    /** How many of [messages] came in since the reader left the end: the count on the ↓. */
    fun fresh(messages: List<String>): Int = since?.let { s -> messages.count { it !in s } } ?: 0

    /** You wrote: the chat goes to its end, and what was new is behind you. */
    fun sent(): StickToBottom = copy(since = null, unread = null)

    companion object {
        /** About 80 px from the end still counts as at it, as in Shepherdr. */
        const val SLOP_DP = 32

        /**
         * At the end of a reversed list (the newest is item 0): its first item shows, scrolled up no
         * more than [slop] px.
         */
        fun atEnd(firstIndex: Int, firstOffset: Int, slop: Int): Boolean = firstIndex == 0 && firstOffset <= slop
    }
}
