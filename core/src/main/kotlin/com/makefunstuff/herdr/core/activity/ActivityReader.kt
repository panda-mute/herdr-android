package com.makefunstuff.herdr.core.activity

import com.makefunstuff.herdr.core.ssh.Duplex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException

/** What [ActivityCommands.sessions] printed, read into one [ActivityLog] a session as it comes. */
class SessionsReader(private val sources: List<SessionSource>) {
    class Read(val source: SessionSource, val log: ActivityLog) {
        /** Its start was left out: only its newest lines came. */
        var cut = false
    }

    val sessions = ArrayList<Read>()

    /** Sessions the board named that aren't on the machine (any more). */
    var missing = 0
        private set

    /** Every session's lines up to now came: from here on only the followed one grows. */
    var done = false
        private set

    /** The session followed live, once the reading got there. */
    var live: Read? = null
        private set

    private var current: Read? = null
    private var skipFirst = false
    private var version = 0L

    val revision: Long get() = version + sessions.sumOf { it.log.revision }

    fun line(l: String) {
        if (l.startsWith(ActivityCommands.MARK + " ")) {
            val rest = l.removePrefix(ActivityCommands.MARK + " ")
            when {
                rest.startsWith("session ") -> {
                    val parts = rest.split(' ', limit = 4)
                    val agent = parts.getOrNull(1).orEmpty()
                    val value = parts.getOrNull(3).orEmpty()
                    val source = sources.firstOrNull { it.value == value && it.agent.substringBefore('/') == agent }
                        ?: sources.firstOrNull { it.value == value } ?: SessionSource(agent, "path", value)
                    current = ActivityLog.forAgent(source.agent, "s${sessions.size}:")?.let { Read(source, it) }?.also { sessions += it }
                }
                rest.startsWith("missing") -> { missing++; current = null }
                rest == "cut" -> current?.cut = true
                rest == "cut raw" -> { current?.cut = true; skipFirst = true }
                rest == "done" -> { done = true; current = null }
                rest.startsWith("live") -> {
                    val value = rest.removePrefix("live").trim()
                    current = sessions.lastOrNull { it.source.value == value } ?: sessions.lastOrNull()
                    live = current
                }
            }
            version++
            return
        }
        if (skipFirst) {
            // tail -c started in the middle of a line.
            skipFirst = false
            return
        }
        if (l.length <= MAX_LINE) current?.log?.feed(l)
    }

    /** The sessions' turns, each in its role (read from its first message, else what the board said, else the agent). */
    fun turns(): List<List<Turn>> = sessions.map { r ->
        // A turn that ended before the agent said anything (handed over at once) names at least its harness.
        r.log.turns(r.source.role ?: Role.Agent).map { t -> if (t.agent.isEmpty()) t.copy(agent = r.source.agent) else t }
    }

    val truncated: Boolean get() = sessions.any { it.cut }

    companion object {
        /** Lines longer than this (a pasted screenshot kept raw, mostly) are skipped rather than parsed. */
        const val MAX_LINE = 4_000_000

        /**
         * Runs [command] ([ActivityCommands.sessions]) and reads it into [reader], calling [onUpdate] once
         * every session has come and then after each burst of new lines. Returns when the output ends.
         *
         * Cancelling the caller closes the channel at once, which ends the remote `tail -F`: the lines
         * are read in a child coroutine, as a blocked read can't be cancelled, and this one only waits
         * for it, so its `finally` runs on cancellation and the close unblocks the read.
         */
        suspend fun stream(start: suspend (String) -> Duplex, command: String, reader: SessionsReader, onUpdate: () -> Unit): Unit = coroutineScope {
            val process = start(command)
            try {
                val pump = async(Dispatchers.IO) {
                    val input = process.input.bufferedReader(Charsets.UTF_8)
                    var published = -1L
                    while (isActive) {
                        val line = input.readLine() ?: break
                        reader.line(line)
                        if (reader.done && !input.ready() && reader.revision != published) {
                            published = reader.revision
                            onUpdate()
                        }
                    }
                    ensureActive()
                    if (!reader.done) throw IOException("The machine stopped sending the sessions")
                    if (reader.revision != published) onUpdate()
                }
                pump.await()
            } finally {
                // NonCancellable: this runs when the screen closes, and the remote tail must stop then too.
                // The command's input ends first (its `cat` returns and it stops the tail), then the channel.
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { process.output.close() }
                    runCatching { process.close() }
                }
            }
        }
    }
}

/** Where one of an agent's sessions is kept: a file, or a session id the harness's own layout turns into one. */
data class SessionSource(
    /** `claude`, `codex`, `pi` or `omp`. */
    val agent: String,
    /** `path` or `id`, as herdr names them. */
    val kind: String,
    val value: String,
    /** Who it is, when known (the role is otherwise read from its first message). */
    val role: Role? = null,
) {
    val key: String get() = "$agent:$value"
}
