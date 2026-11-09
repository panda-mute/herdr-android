package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.api.AgentSessionRef
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import kotlinx.serialization.json.jsonObject
import java.time.Instant

/**
 * Words in the recent chats: the conversations of the agents running on a machine, searched where
 * they are. One command per machine (a few for many agents) finds each agent's transcript as the chat does
 * ([TranscriptFollower.FIND]) and prints its newest lines holding the words, which the agent's own
 * reader then turns into what was said. Only what was said counts, not tool calls or their output.
 * As in the rest of the search, every word has to be in it, anywhere: the machine looks for the
 * longest word, and what it finds is kept when it holds the others too. Words are matched in the
 * file as written, so ones the transcript stores escaped (quotes, some non-ASCII) may not be found.
 */
object ChatSearch {
    const val MARK = "@@herdr-chat "

    /** Lines read back per chat, newest last; and the longest line read (pasted pictures are skipped). */
    private const val LINES = 12
    private const val MAX_LINE = 200_000

    /** Fewer letters than this find too much to be worth the trip to the machines. */
    const val MIN_QUERY = 3

    // $1 the word looked for, then pane, kind, value and agent for each chat.
    // Git Bash's grep aborts on -i with -F, so when grep fails (status above 1) awk matches the lines instead.
    private val SCRIPT = """
        q="${'$'}1"; shift
        while [ ${'$'}# -ge 4 ]; do
          pane="${'$'}1"; kind="${'$'}2"; value="${'$'}3"; agent="${'$'}4"; shift 4; f=""
    """.trimIndent() + "\n" + TranscriptFollower.FIND + "\n" + """
          if [ -n "${'$'}f" ] && [ -f "${'$'}f" ]; then
            echo "$MARK${'$'}pane"
            { grep -i -F -- "${'$'}q" "${'$'}f" 2>/dev/null; [ ${'$'}? -le 1 ] || Q="${'$'}q" awk 'BEGIN { q = tolower(ENVIRON["Q"]) } index(tolower(${'$'}0), q)' "${'$'}f"; } | awk 'length(${'$'}0) < $MAX_LINE' | tail -n $LINES
          fi
        done
    """.trimIndent()

    /** One chat to look in: its agent's pane and where its conversation is. */
    data class Chat(val paneId: String, val session: AgentSessionRef)

    /** Something said in a chat that holds the words: a piece of it around them, and when, when the transcript says. */
    data class Hit(val paneId: String, val you: Boolean, val snippet: String, val at: Long?)

    /**
     * The longest command sent, counting each double quote twice: Windows hands it to Git's bash on a
     * command line cut at 8191 characters, with every `"` written `\"`, and a cut command fails whole.
     */
    private const val MAX_COMMAND = 7_800

    /**
     * The commands for [chats] on one machine, run one after another; none when there is nothing to look
     * in. Each stays within what Windows takes, so a machine with many agents gets more than one.
     */
    fun commands(query: String, chats: List<Chat>): List<String> {
        val q = words(query).maxByOrNull { it.length } ?: return emptyList()
        val readable = chats.filter { Transcript.forAgent(it.session.agent) != null && it.session.value.isNotEmpty() }
        if (query.trim().length < MIN_QUERY || readable.isEmpty()) return emptyList()
        val head = "sh -c ${quote(SCRIPT)} herdr-chat-search ${quote(q)}"
        val out = ArrayList<String>()
        val command = StringBuilder(head)
        var length = windowsLength(head)
        for (chat in readable) {
            val part = listOf(chat.paneId, chat.session.kind, chat.session.value, chat.session.agent).joinToString("") { " " + quote(it) }
            if (command.length > head.length && length + windowsLength(part) > MAX_COMMAND) {
                out += command.toString()
                command.setLength(0)
                command.append(head)
                length = windowsLength(head)
            }
            command.append(part)
            length += windowsLength(part)
        }
        out += command.toString()
        return out
    }

    /** How long [s] is on a Windows command line, where each double quote is written `\"`. */
    private fun windowsLength(s: CharSequence): Int = s.length + s.count { it == '"' }

    /**
     * The hits in what [command] printed: the newest first within each chat, at most [perChat] of
     * them. [agents] gives each pane's agent kind, whose reader understands its lines.
     */
    fun parse(stdout: String, query: String, agents: Map<String, String>, perChat: Int = 2): List<Hit> {
        val words = words(query)
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<Hit>()
        var pane: String? = null
        val lines = ArrayList<String>()
        fun flush() {
            val p = pane ?: return
            val reader = agents[p]?.let { Transcript.forAgent(it) } ?: return
            val times = HashMap<String, Long>()
            for (line in lines) {
                reader.feed(line)
                // The newest item fed is the one this line made; its time, when the line has one.
                val at = timeOf(line) ?: continue
                reader.items().lastOrNull()?.let { times.putIfAbsent(it.id, at) }
            }
            reader.items().asReversed().asSequence()
                .mapNotNull { item ->
                    when (item) {
                        is ChatItem.User -> Triple(true, item.text, item.id)
                        is ChatItem.Agent -> Triple(false, item.text, item.id)
                        else -> null
                    }
                }
                .filter { (_, text, _) -> !isInjected(text) && text.lowercase().let { t -> words.all { it in t } } }
                .take(perChat)
                .forEach { (you, text, id) -> out += Hit(p, you, snippet(text, words.first()), times[id]) }
        }
        for (line in stdout.lines()) {
            if (line.startsWith(MARK)) {
                flush()
                pane = line.removePrefix(MARK).trim()
                lines.clear()
            } else if (pane != null && line.startsWith("{")) lines += line
        }
        flush()
        return out
    }

    /** The query's words, in lower case, as the rest of the search splits them. */
    private fun words(query: String): List<String> = query.lowercase().split(SPACES).filter { it.isNotEmpty() }

    /** About [width] characters of [text] around the first [query], on one line, cut at words, with … where it was cut. */
    fun snippet(text: String, query: String, width: Int = 90): String {
        val flat = text.replace(MARKS, "").replace(SPACES, " ").trim()
        val at = flat.lowercase().indexOf(query.lowercase()).coerceAtLeast(0)
        // A little of what comes before the words, from the start of a word.
        var start = (at - width / 3).coerceAtLeast(0)
        if (start > 0) start = flat.indexOf(' ', start).let { if (it < 0 || it >= at) start else it + 1 }
        var end = (start + width).coerceAtMost(flat.length)
        if (end < flat.length) end = flat.lastIndexOf(' ', end).let { if (it <= at + query.length) end else it }
        return (if (start > 0) "…" else "") + flat.substring(start, end).trim() + if (end < flat.length) "…" else ""
    }

    /** A transcript line's own time (Claude Code's and codex's `timestamp`), in epoch milliseconds. */
    private fun timeOf(line: String): Long? = runCatching {
        TranscriptJson.parseToJsonElement(line).jsonObject.str("timestamp")?.let { Instant.parse(it).toEpochMilli() }
    }.getOrNull()

    /** Markdown's marks, which a one-line piece reads better without. */
    private val MARKS = Regex("\\*\\*|__|`")
    private val SPACES = Regex("\\s+")
}
