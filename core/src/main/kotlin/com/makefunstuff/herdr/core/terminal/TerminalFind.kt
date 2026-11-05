package com.makefunstuff.herdr.core.terminal

/**
 * Find in a terminal the phone keeps the history of (herdr-core's: [TerminalEmulator.historyLimit]),
 * over its history and screen, as classic herdr's copy search does on the host: a new query finds
 * the match nearest the bottom, then each find the next older ([backward]) or newer one, round to
 * the other end. Case doesn't matter. The match found is [TerminalEmulator.mark]ed and scrolled to.
 *
 * Caller holds [TerminalEmulator.lock].
 */
class TerminalFind(private val emu: TerminalEmulator) {
    /** The match found: [place] among all [total] (0 the oldest). */
    data class Found(val total: Int, val place: Int?)

    private var query = ""

    fun find(query: String, backward: Boolean = true): Found {
        val again = query == this.query && emu.mark != null
        this.query = query
        val matches = matches(query)
        if (matches.isEmpty()) {
            emu.mark = null
            return Found(0, null)
        }
        val current = emu.mark?.takeIf { again }
        val place = when {
            current == null -> matches.lastIndex
            backward -> matches.indexOfLast { it.before(current) }.takeIf { it >= 0 } ?: matches.lastIndex
            else -> matches.indexOfFirst { current.before(it) }.takeIf { it >= 0 } ?: 0
        }
        val found = matches[place]
        emu.mark = found
        emu.reveal(found.line)
        return Found(matches.size, place)
    }

    fun reset() {
        query = ""
        emu.mark = null
    }

    /** Every match, oldest first, its columns the cells it covers. */
    private fun matches(query: String): List<TerminalEmulator.Mark> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<TerminalEmulator.Mark>()
        emu.allLines().forEachIndexed { i, text ->
            var at = text.indexOf(query, ignoreCase = true)
            if (at < 0) return@forEachIndexed
            val cols = columns(text)
            while (at >= 0) {
                out += TerminalEmulator.Mark(emu.absolute(i), cols[at], cols[at + query.length] - 1)
                at = text.indexOf(query, at + query.length, ignoreCase = true)
            }
        }
        return out
    }

    private fun TerminalEmulator.Mark.before(other: TerminalEmulator.Mark) =
        line < other.line || line == other.line && from < other.from

    companion object {
        /** The cell each char of a line's [text] starts in, and one past its end: wide chars take two. */
        internal fun columns(text: String): IntArray {
            val cols = IntArray(text.length + 1)
            var x = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val n = Character.charCount(cp)
                for (k in 0 until n) cols[i + k] = x
                x += CharWidth.of(cp)
                i += n
            }
            cols[text.length] = x
            return cols
        }
    }
}

/** Where `terminal.read`'s text meets the screen the attach drew, so its history goes above it once. */
object TerminalHistory {
    /**
     * The lines of [read] (history then screen, oldest first, as `terminal.read` gives them) that
     * come before [shown] (the attached terminal's lines from its first paint on): [read] without
     * its longest tail that [shown] starts with (its last line may have been typed on since). With
     * no such tail, its last [rows] (its screen) go.
     */
    fun above(read: List<String>, shown: List<String>, rows: Int): List<String> {
        val have = shown.map { it.trimEnd() }.dropLastWhile { it.isEmpty() }
        val lines = read.map { it.trimEnd() }.dropLastWhile { it.isEmpty() }
        for (k in minOf(lines.size, have.size) downTo 1) {
            val from = lines.size - k
            val same = (0 until k).all { if (it == k - 1) have[it].startsWith(lines[from + it]) else lines[from + it] == have[it] }
            if (same) return lines.subList(0, from)
        }
        return lines.dropLast(rows)
    }
}
