package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.api.AgentSessionRef
import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.api.HerdrApiException
import com.makefunstuff.herdr.core.api.PaneInfo
import com.makefunstuff.herdr.core.api.PaneProcessInfo
import com.makefunstuff.herdr.core.ssh.CommandResult
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * The model and reasoning effort an agent can be switched to from the phone, as its own `/`
 * commands take them as words: Claude Code's `/model opus` and `/effort high`, and Codex's
 * `/model <slug> --reasoning=high` ([CodexModels]). The others open a picker, which a phone can't drive.
 */
object AgentSettings {
    data class Choice(val value: String, val label: String)

    /** Claude Code's model aliases: each is the newest model of its family. */
    val CLAUDE_MODELS = listOf(Choice("opus", "Opus"), Choice("sonnet", "Sonnet"), Choice("haiku", "Haiku"), Choice("fable", "Fable"))

    /** Claude Code's effort levels; it lowers one a model doesn't take to the highest it does. */
    val CLAUDE_EFFORTS = listOf(
        Choice("auto", "Auto"), Choice("low", "Low"), Choice("medium", "Medium"),
        Choice("high", "High"), Choice("xhigh", "Extra high"), Choice("max", "Max"),
    )

    fun canSwitch(agent: String?): Boolean = agent == "claude" || agent == "codex"

    fun modelCommand(model: String): String {
        require(CLAUDE_MODELS.any { it.value == model }) { "not a model: $model" }
        return "/model $model"
    }

    fun effortCommand(effort: String): String {
        require(CLAUDE_EFFORTS.any { it.value == effort }) { "not an effort: $effort" }
        return "/effort $effort"
    }

    /** Which of [CLAUDE_MODELS] a model id is (`claude-opus-5-5` is `opus`), or null. */
    fun family(model: String?): String? {
        val id = model?.lowercase()?.removePrefix("anthropic/") ?: return null
        return CLAUDE_MODELS.firstOrNull { id == it.value || id.startsWith("claude-${it.value}") }?.value
    }
}

/**
 * An agent's own command line, kept for a restart: the options it was started with, without what
 * named its old session (`--resume`, `--continue`, `codex resume <id>`) and without any that skip
 * its permission prompts, which the phone never passes ([dropped] says which went).
 */
object AgentFlags {
    private class Rules(
        val value: Set<String>,
        val variadic: Set<String> = emptySet(),
        val bool: Set<String> = emptySet(),
        val dropBool: Set<String> = emptySet(),
        val dropValue: Set<String> = emptySet(),
        val dropSub: Set<String> = emptySet(),
    )

    // As Alma's desktop server keeps them for its own restart.
    private val RULES = mapOf(
        "claude" to Rules(
            value = setOf(
                "--model", "--permission-mode", "--settings", "--agent", "--agents", "--append-system-prompt", "--system-prompt",
                "--fallback-model", "--setting-sources", "--plugin-dir", "--output-format", "--input-format", "--max-turns", "--name",
            ),
            variadic = setOf("--add-dir", "--mcp-config", "--allowedTools", "--allowed-tools", "--disallowedTools", "--disallowed-tools", "--betas", "--tools"),
            bool = setOf(
                "--dangerously-skip-permissions", "--allow-dangerously-skip-permissions", "--verbose", "--ide", "--chrome", "--no-chrome",
                "--strict-mcp-config", "--debug",
            ),
            dropBool = setOf("-c", "--continue", "--fork-session", "-p", "--print"),
            dropValue = setOf("--session-id"),
        ),
        "codex" to Rules(
            value = setOf("-m", "--model", "-c", "--config", "-p", "--profile", "-s", "--sandbox", "-a", "--ask-for-approval", "-C", "--cd", "--local-provider"),
            variadic = setOf("--add-dir", "-i", "--image", "--enable", "--disable"),
            bool = setOf("--yolo", "--dangerously-bypass-approvals-and-sandbox", "--full-auto", "--search", "--oss", "--no-alt-screen"),
            dropBool = setOf("--last", "--all"),
            dropSub = setOf("resume", "fork"),
        ),
        "pi" to Rules(
            value = setOf(
                "--provider", "--model", "--api-key", "--system-prompt", "--append-system-prompt", "--thinking", "--session-dir", "--mode",
                "-n", "--name", "--tools", "--extension", "-e",
            ),
            variadic = setOf("--models"),
            bool = setOf("--no-tools", "--no-extensions", "--offline", "--verbose"),
            dropBool = setOf("-c", "--continue", "--no-session"),
            dropValue = setOf("--session", "--session-id", "--fork"),
        ),
    )

    /** The switches that let an agent act without asking, as flags alone or as a flag's value. */
    private val UNASKED = setOf(
        "--dangerously-skip-permissions", "--allow-dangerously-skip-permissions", "--yolo", "--dangerously-bypass-approvals-and-sandbox",
    )
    private val UNASKED_VALUES = mapOf(
        "--permission-mode" to setOf("bypassPermissions"),
        "-s" to setOf("danger-full-access"), "--sandbox" to setOf("danger-full-access"),
    )

    data class Kept(val flags: List<String>, val dropped: List<String>)

    private fun isFlag(a: String) = a.startsWith("-") && a != "-"

    /** [kind]'s options in [argv] (its executable first, as `pane.process_info` gives it). */
    fun keep(kind: String, argv: List<String>): Kept {
        val r = RULES[kind] ?: return Kept(emptyList(), emptyList())
        val at = argv.indexOfFirst { it.substringAfterLast('/') == kind }
        val args = if (at >= 0) argv.drop(at + 1) else emptyList()
        val out = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a == "--") break
            if (!isFlag(a)) {
                // A subcommand that names a session (`codex resume <id>`) goes with its words.
                if (a in r.dropSub) while (i + 1 < args.size && !isFlag(args[i + 1])) i++
                i++
                continue
            }
            val inline = '=' in a
            val name = if (inline) a.substringBefore('=') else a
            // A known option's value is taken whatever it looks like; an unknown one's only when it isn't a flag.
            val next = args.getOrNull(i + 1)?.takeIf { name in r.value || !isFlag(it) }
            when {
                name == "-r" || name == "--resume" -> if (!inline && next != null) i++
                name in r.dropBool -> {}
                name in r.dropValue -> if (!inline) i++
                name in UNASKED -> dropped += a
                name in r.variadic && !inline -> {
                    out += a
                    while (i + 1 < args.size && !isFlag(args[i + 1])) out += args[++i]
                }
                inline || name in r.bool -> {
                    if (inline && a.substringAfter('=') in UNASKED_VALUES[name].orEmpty()) dropped += a else out += a
                }
                next != null -> {
                    if (next in UNASKED_VALUES[name].orEmpty()) dropped += "$a $next" else { out += a; out += next }
                    i++
                }
                else -> out += a
            }
            i++
        }
        return Kept(out, dropped)
    }
}

/**
 * Quits an agent and opens the same conversation again in its pane, as Alma's desktop app does:
 * its options are read from its own command line ([AgentFlags]), it is stopped with keys (Esc,
 * Ctrl+C) and, failing those, a signal, and herdr starts it again on its session in its folder,
 * then sends the words given, if any. For an agent that hangs, or one that needs a restart to pick
 * up an update or new settings.
 */
object AgentRestart {
    enum class Phase { Stopping, Starting, Waiting, Sending, Done }

    /** What Alma's one-tap "Restart & Continue" sends once it is back. */
    const val CONTINUE = "continue"

    /** The calls a restart makes: classic herdr's API, and a signal sent on the machine. */
    interface Port {
        suspend fun processInfo(paneId: String): PaneProcessInfo
        suspend fun pane(paneId: String): PaneInfo
        suspend fun keys(paneId: String, keys: List<String>)
        suspend fun type(paneId: String, text: String)
        suspend fun start(name: String, kind: String, paneId: String, args: List<String>, timeoutMs: Long)
        suspend fun prompt(target: String, text: String)
        /** Sends [signal] (TERM, KILL) to process group [group]. */
        suspend fun kill(group: Long, signal: String)
    }

    /** [Port] on classic herdr, with [run] running a shell command on the machine. */
    class HerdrPort(private val api: HerdrApi, private val run: suspend (String) -> CommandResult) : Port {
        override suspend fun processInfo(paneId: String) = api.processInfo(paneId)
        override suspend fun pane(paneId: String) = api.pane(paneId)
        override suspend fun keys(paneId: String, keys: List<String>) = api.sendKeys(paneId, keys)
        override suspend fun type(paneId: String, text: String) {
            api.sendText(paneId, text)
            api.sendKeys(paneId, listOf("enter"))
        }
        override suspend fun start(name: String, kind: String, paneId: String, args: List<String>, timeoutMs: Long) =
            api.startAgent(name, kind, paneId, args, timeoutMs)
        override suspend fun prompt(target: String, text: String) = api.prompt(target, text)
        override suspend fun kill(group: Long, signal: String) {
            require(group > 1 && signal in setOf("TERM", "KILL"))
            run("kill -$signal -- -$group")
        }
    }

    private val ID = Regex("^[A-Za-z0-9_-]{1,128}$")
    private val SUPPORTED = setOf("claude", "codex", "pi")

    /** The agents a restart can reopen: its session is known, as an id (or pi's file). */
    fun canRestart(agent: String?, ref: AgentSessionRef?): Boolean = session(agent, ref) != null

    private fun session(agent: String?, ref: AgentSessionRef?): String? {
        val kind = ref?.agent?.ifEmpty { null } ?: agent
        if (ref == null || kind !in SUPPORTED) return null
        val v = ref.value
        return when {
            ref.kind == "id" && ID.matches(v) -> v
            kind == "pi" && ref.kind == "path" && v.startsWith("/") && v.length < 4096 && v.none { it.isISOControl() } -> v
            else -> null
        }
    }

    /** The arguments the agent starts again with: its kept [flags], then its session. */
    fun resumeArgs(agent: String, session: String, flags: List<String>): List<String> = when (agent) {
        "codex" -> flags + listOf("resume", session)
        "pi" -> flags + listOf("--session", session)
        else -> flags + listOf("--resume", session)
    }

    /** What a restart of the agent in [paneId] would leave out of its options; read before asking. */
    suspend fun preview(port: Port, paneId: String, agent: String): AgentFlags.Kept {
        val info = port.processInfo(paneId)
        val fg = info.processes.firstOrNull { it.pid == info.foregroundGroupId } ?: info.processes.firstOrNull()
        return AgentFlags.keep(agent, fg?.argv.orEmpty())
    }

    /**
     * Restarts the [agent] in [paneId] on [ref]'s conversation, reporting each step to [onPhase],
     * and sends [then] once it is back. Throws [IOException] when a step fails: the pane is then
     * left as it is, for the person to look at.
     */
    suspend fun run(
        port: Port,
        paneId: String,
        agent: String,
        ref: AgentSessionRef,
        then: String?,
        onPhase: (Phase) -> Unit,
        stepMs: Long = 250,
        quitMs: Long = 5_000,
        killMs: Long = 3_000,
        goneMs: Long = 5_000,
        startMs: Long = 60_000,
    ) {
        val session = session(agent, ref) ?: throw IOException("$agent can't be reopened from the phone.")
        onPhase(Phase.Stopping)
        val info = port.processInfo(paneId)
        val group = info.foregroundGroupId
        val fg = info.processes.firstOrNull { it.pid == group } ?: info.processes.firstOrNull()
        val kept = AgentFlags.keep(agent, fg?.argv.orEmpty())
        val cwd = fg?.cwd
        if (group != null && group != info.shellPid) stop(port, paneId, group, info.shellPid, stepMs, quitMs, killMs)
        // herdr notices the agent left a moment after its process did.
        var waited = 0L
        while (waited < goneMs && port.pane(paneId).agent != null) {
            delay(stepMs)
            waited += stepMs
        }
        onPhase(Phase.Starting)
        val pane = port.pane(paneId)
        if (cwd != null && pane.foregroundCwd != null && pane.foregroundCwd != cwd) {
            port.type(paneId, "cd -- " + com.makefunstuff.herdr.core.ssh.HerdrCommands.quote(cwd))
        }
        onPhase(Phase.Waiting)
        val name = "$agent-" + java.lang.Long.toHexString(System.nanoTime()).takeLast(8)
        var attempt = 1
        while (true) {
            try {
                port.start(name, agent, paneId, resumeArgs(agent, session, kept.flags), startMs)
                break
            } catch (e: HerdrApiException) {
                // The shell's prompt may not be back yet.
                if (attempt < 4 && "not an available shell" in e.message.orEmpty()) {
                    attempt++
                    delay(1_000)
                    continue
                }
                throw IOException("It didn't start again: ${e.message}")
            }
        }
        val prompt = then?.trim()?.takeIf { it.isNotEmpty() }
        if (prompt != null) {
            onPhase(Phase.Sending)
            var tried = 0L
            while (true) {
                try {
                    port.prompt(name, prompt)
                    break
                } catch (e: HerdrApiException) {
                    if (e.code != "agent_not_ready" || tried >= goneMs) throw e
                    delay(500)
                    tried += 500
                }
            }
        }
        onPhase(Phase.Done)
    }

    /** Esc and Ctrl+C twice, twice over; then TERM, then KILL to its process group. */
    private suspend fun stop(port: Port, paneId: String, group: Long, shell: Long?, stepMs: Long, quitMs: Long, killMs: Long) {
        suspend fun gone(): Boolean {
            val now = runCatching { port.processInfo(paneId) }.getOrNull() ?: return false
            return now.foregroundGroupId == (now.shellPid ?: shell) || now.foregroundGroupId != group
        }
        suspend fun waitGone(ms: Long): Boolean {
            var t = 0L
            while (t < ms) {
                if (gone()) return true
                delay(stepMs)
                t += stepMs
            }
            return gone()
        }
        suspend fun keys(k: String) = runCatching { port.keys(paneId, listOf(k)) }
        keys("esc")
        delay(300)
        keys("ctrl+c")
        delay(200)
        keys("ctrl+c")
        if (waitGone(quitMs / 2)) return
        keys("ctrl+c")
        delay(200)
        keys("ctrl+c")
        if (waitGone(quitMs / 2)) return
        runCatching { port.kill(group, "TERM") }
        if (waitGone(killMs)) return
        runCatching { port.kill(group, "KILL") }
        if (!waitGone(killMs)) throw IOException("It didn't quit. Look at its terminal.")
    }
}
