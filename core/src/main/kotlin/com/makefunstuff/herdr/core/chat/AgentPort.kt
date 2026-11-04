package com.makefunstuff.herdr.core.chat

import kotlinx.coroutines.delay
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.api.HerdrApiException
import com.makefunstuff.herdr.core.hc.HcClient
import com.makefunstuff.herdr.core.hc.HcException
import com.makefunstuff.herdr.core.hc.HcMapping
import java.io.IOException

/**
 * Where an agent's screen is read and its keys and words go: classic herdr's API for its panes,
 * herdr-core's for its terminals (pane ids that start with [HcMapping.PREFIX]). [AgentInput] and
 * the chat speak to either through this.
 */
interface AgentPort {
    /** Its screen as text: only what shows ([visible]), or with up to [lines] of what came before. */
    suspend fun read(paneId: String, lines: Int, visible: Boolean, ansi: Boolean = false): String

    /** Its status now, read again; null when it isn't an agent (or is gone). */
    suspend fun status(paneId: String): AgentStatus?

    /** Presses [keys]; [named] for an agent herdr started itself (classic herdr's agent.send_keys). */
    suspend fun keys(paneId: String, keys: List<String>, named: Boolean)

    /** Types [text] and Enter, also into a blocked program (it answers the question). */
    suspend fun type(paneId: String, text: String)

    /** Sends [text] as the next prompt to an agent at rest; false when it refused it for being blocked. */
    suspend fun prompt(paneId: String, text: String): Boolean
}

/** Classic herdr's panes and agents. */
class ClassicAgentPort(val api: HerdrApi) : AgentPort {
    override suspend fun read(paneId: String, lines: Int, visible: Boolean, ansi: Boolean): String =
        api.readPane(paneId, source = if (visible) "visible" else "recent_unwrapped", lines = lines, ansi = ansi).text

    override suspend fun status(paneId: String): AgentStatus? = api.snapshot().agents.firstOrNull { it.paneId == paneId }?.agentStatus

    override suspend fun keys(paneId: String, keys: List<String>, named: Boolean) {
        // agent.send_keys reaches agents herdr started itself; one an integration reported answers
        // "not an active named agent", so its keys go to the pane instead.
        val sent = if (named) runCatching { api.agentSendKeys(paneId, keys) } else null
        if (sent == null || sent.isFailure) api.sendKeys(paneId, keys)
    }

    override suspend fun type(paneId: String, text: String) {
        api.sendText(paneId, text)
        // Codex takes text and Enter that arrive together as a paste, and Enter stays in it.
        delay(ENTER_AFTER_MS)
        api.sendKeys(paneId, listOf("enter"))
    }

    private companion object {
        const val ENTER_AFTER_MS = 150L
    }

    override suspend fun prompt(paneId: String, text: String): Boolean = try {
        api.prompt(paneId, text)
        true
    } catch (e: HerdrApiException) {
        if (e.code == "agent_blocked") false else throw e
    }
}

/**
 * herdr-core's terminals, by their pane ids ([HcMapping.paneId]). hc keeps no colours in what it
 * reads back, so [read] is plain text either way; its sends wait until nobody has typed for ~3 s.
 */
class HcAgentPort(val client: HcClient) : AgentPort {
    private fun address(paneId: String): String =
        HcMapping.address(paneId) ?: throw IOException("Not a herdr-core terminal: $paneId")

    override suspend fun read(paneId: String, lines: Int, visible: Boolean, ansi: Boolean): String =
        client.read(address(paneId), if (visible) 0 else lines).text

    override suspend fun status(paneId: String): AgentStatus? = try {
        val t = client.get(address(paneId))
        if (t.live && HcMapping.isAgent(t)) AgentStatus.of(HcMapping.statusWord(t)) else null
    } catch (e: HcException) {
        if (e.kind == "not_found") null else throw e
    }

    override suspend fun keys(paneId: String, keys: List<String>, named: Boolean) {
        client.keys(address(paneId), keys)
    }

    override suspend fun type(paneId: String, text: String) {
        client.send(address(paneId), text, force = true)
    }

    override suspend fun prompt(paneId: String, text: String): Boolean = try {
        client.send(address(paneId), text)
        true
    } catch (e: HcException) {
        if (e.kind == "blocked") false else throw e
    }
}
