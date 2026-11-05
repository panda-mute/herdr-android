package com.makefunstuff.herdr.core.share

import kotlinx.serialization.Serializable

/**
 * Something shared to the app from elsewhere on the phone (the share sheet, or "Send to
 * Coordinator" in the text-selection menu), on its way to a Coordinator chat: its words, and its
 * files already copied into the app (a screenshot, a log).
 */
@Serializable
data class Shared(val text: String, val files: List<SharedFile> = emptyList()) {
    val isEmpty: Boolean get() = text.isBlank() && files.isEmpty()
}

/** A shared file, copied to [path] on the phone; [name] as the app it came from called it. */
@Serializable
data class SharedFile(val name: String, val path: String, val size: Long)

object ShareText {
    /**
     * The words of a share: a link with its page's title above it (as a browser shares one), plain
     * text as it is. A subject the text already holds isn't said twice.
     */
    fun of(subject: String?, text: String?): String {
        val s = subject?.trim().orEmpty()
        val t = text?.trim().orEmpty()
        return when {
            s.isEmpty() -> t
            t.isEmpty() -> s
            t.contains(s) -> t
            else -> "$s\n$t"
        }
    }

    /** A chat's [draft] with [text] added at its end, after a blank line when the draft has words already. */
    fun intoDraft(draft: String, text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return draft
        val d = draft.trimEnd()
        return if (d.isEmpty()) t else "$d\n\n$t"
    }
}

/**
 * Which machine's Coordinator a tap from outside the app goes to: the tile, the widgets, a share.
 * Asked only when it isn't clear.
 */
object AskTargets {
    /** What the app knows of a machine right now, for this. */
    enum class Board {
        /** Connected, and the work board is there. */
        Here,
        /** Not connected yet (or not reachable): it may have one. */
        Unknown,
        /** Connected, and Shepherdr isn't running there. */
        None,
    }

    data class Candidate(val machineId: String, val board: Board)

    /** The machines a Coordinator can be on: not those known to have no board. [last] first, then as saved. */
    fun candidates(all: List<Candidate>, last: String?): List<Candidate> =
        all.filter { it.board != Board.None }.sortedBy { if (it.machineId == last) 0 else 1 }

    /**
     * The machine to open at once, or null to ask. The tile and the widget ([share] false) open the
     * Coordinator used last when it can still be one, or the only machine that can; a share asks
     * whenever there are several, as what is shared may belong to any of them.
     */
    fun choose(all: List<Candidate>, last: String?, share: Boolean): String? {
        val can = candidates(all, last)
        if (can.size == 1) return can.single().machineId
        if (share) return null
        return last?.takeIf { l -> can.any { it.machineId == l } }
    }
}
