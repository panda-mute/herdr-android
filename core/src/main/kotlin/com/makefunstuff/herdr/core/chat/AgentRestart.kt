package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.api.AgentSessionRef
import com.makefunstuff.herdr.core.api.AgentStatus
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * The model and reasoning effort an agent can be switched to from the phone, as its own `/`
 * commands take them. Only Claude Code takes both as words (`/model opus`, `/effort high`); the
 * others open a picker, which a phone can't drive.
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

    fun canSwitch(agent: String?): Boolean = agent == "claude"

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
 * Quits an agent and opens the same conversation again in its pane, as herdr itself resumes one
 * after a restart (`claude --resume <id>`, `codex resume <id>`), then optionally sends a prompt.
 * For an agent that hangs, or one that needs a restart to pick up an update or new settings.
 */
object AgentRestart {
    enum class Phase { Stopping, Starting, Waiting, Sending, Done }

    private val ID = Regex("^[A-Za-z0-9_-]{1,128}$")

    /** What the pane's shell runs to open [ref]'s conversation again; null when the app can't. */
    fun resumeCommand(agent: String?, ref: AgentSessionRef?): String? {
        if (ref == null || ref.kind != "id" || !ID.matches(ref.value)) return null
        return when (ref.agent.ifEmpty { agent }) {
            "claude" -> "claude --resume ${ref.value}"
            "codex" -> "codex resume ${ref.value}"
            else -> null
        }
    }

    private fun quitCommand(agent: String): String = if (agent == "codex") "/quit" else "/exit"

    /**
     * Restarts the [agent] in [paneId] on [ref]'s conversation, reporting each step to [onPhase],
     * and sends [then] once it is back at rest. Throws [IOException] when a step doesn't happen in
     * time: the pane is then left as it is, for the person to look at.
     */
    suspend fun run(
        api: AgentPort,
        paneId: String,
        agent: String,
        ref: AgentSessionRef,
        then: String?,
        onPhase: (Phase) -> Unit,
        stepMs: Long = 250,
        quitMs: Long = 15_000,
        backMs: Long = 90_000,
    ) {
        val resume = resumeCommand(agent, ref) ?: throw IOException("This agent can't be reopened from the phone.")
        onPhase(Phase.Stopping)
        // Stop what it does or asks first: a quit typed then would be taken as words.
        if (api.status(paneId).let { it == AgentStatus.Working || it == AgentStatus.Blocked }) {
            api.keys(paneId, listOf("esc"), named = false)
            delay(stepMs * 3)
        }
        api.type(paneId, quitCommand(agent))
        if (!waitFor(quitMs / 2, stepMs) { api.status(paneId) == null }) {
            // Its input held other words, or it ignored the command: Ctrl+C twice quits both.
            api.keys(paneId, listOf("C-c"), named = false)
            delay(stepMs)
            api.keys(paneId, listOf("C-c"), named = false)
            if (!waitFor(quitMs / 2, stepMs) { api.status(paneId) == null }) throw IOException("It didn't quit. Look at its terminal.")
        }
        onPhase(Phase.Starting)
        // The shell's prompt comes back a moment after the agent leaves.
        delay(stepMs * 2)
        api.type(paneId, resume)
        onPhase(Phase.Waiting)
        if (!waitFor(backMs, stepMs * 2) { api.status(paneId).let { it == AgentStatus.Idle || it == AgentStatus.Done } }) {
            throw IOException("It didn't come back in time. Look at its terminal.")
        }
        val prompt = then?.trim()?.takeIf { it.isNotEmpty() }
        if (prompt != null) {
            onPhase(Phase.Sending)
            AgentInput.send(api, paneId, prompt, named = true, blocked = false)
        }
        onPhase(Phase.Done)
    }

    private suspend fun waitFor(ms: Long, stepMs: Long, done: suspend () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + ms
        while (true) {
            if (done()) return true
            if (System.currentTimeMillis() >= until) return false
            delay(stepMs)
        }
    }
}
