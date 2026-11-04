package com.makefunstuff.herdr.core.voice

import com.makefunstuff.herdr.core.shepherdr.SpaceChatItem

/**
 * What the coordinator's chat sounds like read aloud: short and natural, never code or long paths.
 * A code block is "a code block, 12 lines", a path "a file, Voice.kt", a web address "a link"; no
 * Markdown marks; a long reply stops after its first few sentences with "More in the chat."
 */
object SpeechText {
    /** About this many characters are read of one reply; the rest is "More in the chat." */
    const val MAX_CHARS = 360

    /** What [item] says aloud, or null when it says nothing (your own words, a voice message, a thin line). */
    fun of(item: SpaceChatItem): String? = when (item) {
        is SpaceChatItem.Words -> if (item.you) null else reply(item.text)
        is SpaceChatItem.Update -> sentence(spokenTask(item.task) + " " + plain(item.text)) +
            item.asks.firstOrNull()?.let { " It asks: " + question(it.question.title, it.question.choices.map { c -> c.label }) }.orEmpty()
        is SpaceChatItem.Question -> listOf(
            reply(item.intro),
            question(item.card.question.title, item.card.question.choices.map { it.label }),
        ).filter { it.isNotBlank() }.joinToString(" ")
        is SpaceChatItem.Artifact -> listOf(reply(item.intro), "It shared an artifact.").filter { it.isNotBlank() }.joinToString(" ")
        is SpaceChatItem.File -> if (item.you) null else "It sent a file, ${spokenName(item.name)}."
        // Tool steps are the work, not what it says: never read.
        is SpaceChatItem.Voice, is SpaceChatItem.NewSession, is SpaceChatItem.Note, is SpaceChatItem.Steps, is SpaceChatItem.Unreadable -> null
    }?.takeIf { it.isNotBlank() }

    /** A question and its choices: "Rerun CI on main? Rerun it, skip the flaky test, or leave it for now." */
    fun question(title: String, choices: List<String>): String {
        val q = sentence(plain(title)).let { if (it.endsWith(".")) it.dropLast(1) + "?" else it }
        val c = choices.map { plain(it).trimEnd('.', '!', '?') }.filter { it.isNotBlank() }
        if (c.isEmpty()) return q
        val list = if (c.size == 1) c[0] else c.dropLast(1).joinToString(", ") + ", or " + c.last()
        return "$q ${sentence(list.replaceFirstChar { it.uppercase() })}"
    }

    /** A coordinator's reply (Markdown) as speech, cut short after [MAX_CHARS]. */
    fun reply(markdown: String): String {
        val spoken = plain(markdown)
        if (spoken.length <= MAX_CHARS) return spoken
        val sentences = sentences(spoken)
        val kept = StringBuilder()
        for (s in sentences) {
            if (kept.isNotEmpty() && kept.length + 1 + s.length > MAX_CHARS) break
            if (kept.isNotEmpty()) kept.append(' ')
            kept.append(s)
        }
        // One very long sentence: up to the last word that fits.
        if (kept.length > MAX_CHARS) {
            val cut = kept.substring(0, MAX_CHARS).substringBeforeLast(' ')
            kept.setLength(0)
            kept.append(cut.trimEnd(',', ';', ':')).append('.')
        }
        return "$kept More in the chat."
    }

    /** Markdown as plain spoken sentences: code, tables, paths and links named, not read. */
    fun plain(markdown: String): String {
        var t = markdown.replace("\r\n", "\n")
        // Fenced code: "a code block, 12 lines" (a short one-liner says "a line of code").
        fun block(body: String): String {
            val lines = body.trimEnd('\n').lines().count { it.isNotBlank() }
            return "\n" + (if (lines <= 1) "A line of code." else "A code block, $lines lines.") + "\n"
        }
        t = Regex("(```|~~~)[^\\n]*\\n([\\s\\S]*?)(?:\\1|$)").replace(t) { m -> block(m.groupValues[2]) }
        // Indented code: four spaces (or a tab) after a blank line.
        t = Regex("(?:^|\\n\\n)((?:(?: {4}|\\t)[^\\n]*(?:\\n|$))+)").replace(t) { m -> "\n" + block(m.groupValues[1]) }
        // Tables: "a table, 4 rows" (the header and its rule aren't rows).
        t = Regex("(?m)(^\\s*\\|.*\\|\\s*$\\n?)+").replace(t) { m ->
            val rows = m.value.lines().filter { it.isNotBlank() && !Regex("^\\s*\\|[\\s:|-]+\\|\\s*$").matches(it) }.size - 1
            "\nA table, ${rows.coerceAtLeast(1)} ${if (rows == 1) "row" else "rows"}.\n"
        }
        t = t.replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), " a picture ")
        t = t.replace(Regex("\\[([^\\]]+)]\\(([^)]+)\\)")) { m -> if (looksLikeCode(m.groupValues[1])) spokenToken(m.groupValues[1]) else m.groupValues[1] }
        // Inline code: a short word is read, anything longer named.
        t = t.replace(Regex("`([^`\\n]+)`")) { m -> spokenToken(m.groupValues[1].trim()) }
        t = t.replace(Regex("https?://\\S+"), "a link")
        t = t.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        t = t.replace(Regex("(?m)^\\s*>\\s?"), "")
        t = t.replace(Regex("(?m)^\\s*(?:[-*•+]|\\d+[.)])\\s+(?:\\[[ xX]]\\s+)?"), "")
        t = t.replace(Regex("(?m)^\\s*[-*_]{3,}\\s*$"), "")
        // Paths and long identifiers left in the prose.
        t = t.split(Regex("(?<=\\s)|(?=\\s)")).joinToString("") { w -> if (w.isBlank()) w else spokenWord(w) }
        t = t.replace(Regex("\\*\\*|__|~~|[*`]"), "")
        t = t.replace(Regex("(?<![\\w])_(\\S[^_]*)_(?![\\w])"), "$1")
        t = t.replace("→", " to ").replace("·", ",").replace("&", " and ")
        t = t.replace(Regex("\\s*\\n+\\s*"), "\n").trim()
        // Each line ends as a sentence, so a list isn't read as one run-on sentence.
        t = t.lines().filter { it.isNotBlank() }.joinToString(" ") { sentence(it) }
        return t.replace(Regex(" {2,}"), " ").replace(Regex(" ([,.;:!?])"), "$1").trim()
    }

    /** The task's id as said: "HA-5" stays (the voice reads it as letters and a number). */
    private fun spokenTask(task: String) = task.ifBlank { "A task" }

    /** A word of prose, with the punctuation round it kept: a path or long identifier becomes its name. */
    private fun spokenWord(w: String): String {
        val lead = w.takeWhile { it in "(\"'“‘[" }
        val trail = w.reversed().takeWhile { it in ".,;:!?)\"'”’]" }.reversed()
        val core = w.substring(lead.length, w.length - trail.length)
        if (core.isEmpty() || core == "a link") return w
        if (!looksLikeCode(core)) return w
        return lead + spokenToken(core) + trail
    }

    /** True for a path, a file name with a long body, or a long identifier no one says aloud. */
    private fun looksLikeCode(s: String): Boolean {
        val slashes = s.count { it == '/' }
        if (slashes >= 2 || (slashes == 1 && s.length > 16) || s.startsWith("~/") || s.startsWith("./")) return true
        if (s.contains("::") || s.contains("()") || s.contains("=") || s.contains("{")) return true
        return s.length > 28 && ' ' !in s
    }

    /** Inline code or a path, as said: a short plain word as it is, else what it is. */
    private fun spokenToken(s: String): String {
        if (s.length <= 24 && Regex("[\\w .,'+#-]+").matches(s) && !looksLikeCode(s)) return s
        if ('/' in s && ' ' !in s) {
            val name = s.trimEnd('/').substringAfterLast('/')
            return if (name.isNotEmpty() && name.length <= 32 && '.' in name.drop(1)) "a file, ${spokenName(name)}"
            else if (s.endsWith('/')) "a folder" else "a path"
        }
        if (Regex("[\\w-]+\\.[a-zA-Z]{1,5}").matches(s) && s.length <= 40) return spokenName(s)
        return "some code"
    }

    /** A file's name as said: "VoiceBar.kt" stays; a long one is "a .kt file". */
    private fun spokenName(name: String): String =
        if (name.length <= 32) name else name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 }?.let { "a .$it file" } ?: "a file"

    /** Ends [s] as a sentence. */
    private fun sentence(s: String): String {
        val t = s.trim()
        return if (t.isEmpty() || t.last() in ".!?:;…") t else "$t."
    }

    /** [text] in sentences. */
    fun sentences(text: String): List<String> = Regex("(?<=[.!?…])\\s+").split(text.trim()).filter { it.isNotBlank() }

    /**
     * Pieces to speak one after another: whole sentences, each at most [max] characters (a longer one
     * cut at a comma or a space). The first is short, so the voice starts sooner (Inkling's rule).
     */
    fun chunks(text: String, max: Int = 220, first: Int = 90): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (s in sentences(text).flatMap { split(it, max) }) {
            val limit = if (out.isEmpty()) first else max
            if (cur.isNotEmpty() && cur.length + 1 + s.length > limit) { out.add(cur.toString()); cur.clear() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(s)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun split(sentence: String, max: Int): List<String> {
        if (sentence.length <= max) return listOf(sentence)
        val out = mutableListOf<String>()
        var rest = sentence
        while (rest.length > max) {
            val window = rest.substring(0, max)
            val cut = window.lastIndexOf(", ").takeIf { it > max / 3 }?.plus(1) ?: window.lastIndexOf(' ').takeIf { it > 0 } ?: max
            out.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }
}
