package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.api.AgentSessionRef
import com.makefunstuff.herdr.core.ssh.Duplex
import com.makefunstuff.herdr.core.ssh.HerdrCommands
import com.makefunstuff.herdr.core.ssh.MachineConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException

/**
 * Follows an agent's transcript file on the machine: finds it from the session herdr reports, sends
 * its last [TAIL_BYTES], then keeps streaming lines as the agent writes them (`tail -F`), all on one
 * SSH channel.
 */
object TranscriptFollower {
    const val TAIL_BYTES = 2_000_000

    /** Lines longer than this (pasted screenshots, mostly) are skipped rather than parsed. */
    private const val MAX_LINE = 4_000_000

    private const val MISSING = "@@missing"
    private const val FILE = "@@file "

    /**
     * The `case` line that sets `f` to a Codex session's newest rollout file. A session resumed later
     * goes on in a new file, `rollout-…-<id>_<new id>.jsonl`, so the one ending in the id is its oldest.
     */
    internal val CODEX = """codex) f=${'$'}(find "${'$'}{CODEX_HOME:-${'$'}HOME/.codex}/sessions" \( -name "*${'$'}value.jsonl" -o -name "*${'$'}{value}_*.jsonl" \) -exec ls -t {} + 2>/dev/null | head -n 1) ;;"""

    /**
     * Sets `f` to the transcript of the session in `kind` (path|id), `value` and `agent`, or leaves it
     * empty; the chat search ([ChatSearch]) finds its files the same way.
     */
    internal val FIND = """
        if [ "${'$'}kind" = path ]; then
          f="${'$'}value"
          case "${'$'}f" in "~/"*) f="${'$'}HOME/${'$'}{f#\~/}" ;; esac
        else
          case "${'$'}agent" in
            claude) for c in "${'$'}{CLAUDE_CONFIG_DIR:-${'$'}HOME/.claude}"/projects/*/"${'$'}value".jsonl; do [ -f "${'$'}c" ] && f="${'$'}c" && break; done ;;
            ${CODEX}
            pi) for c in "${'$'}HOME"/.pi/agent/sessions/*/*_"${'$'}value".jsonl; do [ -f "${'$'}c" ] && f="${'$'}c" && break; done ;;
            omp) for c in "${'$'}HOME"/.omp/agent/sessions/*/*_"${'$'}value".jsonl; do [ -f "${'$'}c" ] && f="${'$'}c" && break; done ;;
          esac
        fi
    """.trimIndent()

    /** With a subagent id in `sub`, moves `f` from the session's file to that subagent's own. */
    internal val SUBAGENT = """
        if [ -n "${'$'}sub" ] && [ -n "${'$'}f" ]; then
          s="${'$'}{f%.jsonl}/subagents/agent-${'$'}sub.jsonl"
          [ -f "${'$'}s" ] || s="${'$'}{f%/*}/agent-${'$'}sub.jsonl"
          f="${'$'}s"
        fi
    """.trimIndent()

    // $1 kind (path|id), $2 value, $3 agent kind, $4 bytes, $5 subagent id or empty. The trailing
    // `cat` waits for the app to close the channel, then stops tail, so no tail outlives the chat screen.
    private val SCRIPT = """
        kind="${'$'}1"; value="${'$'}2"; agent="${'$'}3"; bytes="${'$'}4"; sub="${'$'}5"; f=""
    """.trimIndent() + "\n" + FIND + "\n" + SUBAGENT + "\n" + """
        if [ -z "${'$'}f" ] || [ ! -f "${'$'}f" ]; then echo "$MISSING"; exit 0; fi
        size=${'$'}(wc -c < "${'$'}f" | tr -d ' ')
        echo "$FILE${'$'}size ${'$'}f"
        tail -c "${'$'}bytes" -F "${'$'}f" 2>/dev/null &
        t=${'$'}!
        cat > /dev/null
        kill "${'$'}t" 2>/dev/null
    """.trimIndent()

    /** Follows [ref]'s transcript, or with [subagent] that subagent's own ([Subagents]). */
    fun command(ref: AgentSessionRef, bytes: Int = TAIL_BYTES, subagent: String? = null): String {
        require(subagent == null || Subagents.valid(subagent)) { "not a subagent id: $subagent" }
        return "sh -c ${HerdrCommands.quote(SCRIPT)} follow " +
            listOf(ref.kind, ref.value, ref.agent, bytes.toString(), subagent.orEmpty()).joinToString(" ") { HerdrCommands.quote(it) }
    }

    sealed interface Result {
        /** No transcript the app can find for this session. */
        data object Missing : Result

        /** The stream ended (the connection dropped, or the file went away). */
        data object Ended : Result
    }

    /**
     * Streams [ref]'s transcript into [transcript], calling [onUpdate] after each burst of lines.
     * [onUpdate]'s flag says the start of the file was cut off. Returns when the stream ends.
     * Cancelling the caller closes the channel, which stops the remote tail.
     */
    suspend fun follow(
        connection: MachineConnection,
        ref: AgentSessionRef,
        transcript: Transcript,
        subagent: String? = null,
        onUpdate: (truncated: Boolean) -> Unit,
    ): Result = follow({ connection.start(it) }, ref, transcript, subagent, onUpdate)

    /** [follow] with any way to start the remote command, so tests can run it locally. */
    suspend fun follow(
        start: suspend (command: String) -> Duplex,
        ref: AgentSessionRef,
        transcript: Transcript,
        subagent: String? = null,
        onUpdate: (truncated: Boolean) -> Unit,
    ): Result = coroutineScope {
        val process = start(command(ref, subagent = subagent))
        try {
            val reader = process.input.bufferedReader(Charsets.UTF_8)
            val header = withContext(Dispatchers.IO) { reader.readLine() } ?: throw IOException("transcript stream closed")
            if (header.startsWith(MISSING)) return@coroutineScope Result.Missing
            val size = header.removePrefix(FILE).substringBefore(' ').toLongOrNull() ?: 0
            val truncated = size > TAIL_BYTES
            val job = launch(Dispatchers.IO) { pump(reader, transcript, truncated, onUpdate) }
            job.join()
            Result.Ended
        } finally {
            // NonCancellable: this runs when the chat closes, and a plain withContext would throw at
            // once in a cancelled coroutine, leaving the channel and the remote tail running.
            withContext(NonCancellable + Dispatchers.IO) { runCatching { process.close() } }
        }
    }

    private fun pump(reader: BufferedReader, transcript: Transcript, truncated: Boolean, onUpdate: (Boolean) -> Unit) {
        // tail -c starts mid-line when it cut the file; that first piece is not JSON.
        var skipFirst = truncated
        var published = transcript.revision
        onUpdate(truncated)
        while (true) {
            val line = reader.readLine() ?: break
            if (skipFirst) {
                skipFirst = false
                continue
            }
            if (line.length <= MAX_LINE) transcript.feed(line)
            if (!reader.ready() && transcript.revision != published) {
                published = transcript.revision
                onUpdate(truncated)
            }
        }
    }
}
