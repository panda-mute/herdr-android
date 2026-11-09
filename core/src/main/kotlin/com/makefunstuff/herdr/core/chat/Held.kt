package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.api.AgentStatus
import kotlinx.serialization.Serializable

/**
 * A message written while the agent worked: it waits above the composer, held on the phone, until
 * you send it (Send now) or take it back (Discard). Nothing held goes by itself, so a thought half
 * typed during the agent's work never lands in the middle of it, and one left behind while you
 * were away never goes later without you. One exception: the sentence Start work holds for an agent it
 * just started on herdr-core goes by itself once that agent reports it waits (within 20 s,
 * [com.makefunstuff.herdr.core.hc.HcStart.deliver]), unless you sent or discarded it first.
 */
@Serializable
data class HeldMessage(
    val id: String,
    val text: String,
    val at: Long,
    /** Ids of the user messages the chat showed when it was held: a copy among them is older. */
    val known: List<String> = emptyList(),
)

object Held {
    /** What Send does now: hold the message while a named agent works (its words would land mid-turn). */
    fun holds(status: AgentStatus, named: Boolean): Boolean = named && status == AgentStatus.Working

    /**
     * Whether Send now can go: not while the agent asks something (the words would answer it) and
     * not while another message is on its way.
     */
    fun canSend(status: AgentStatus, sending: Boolean): Boolean = !sending && status != AgentStatus.Blocked

    /** The caption over the held rows. */
    fun caption(status: AgentStatus, count: Int, agent: String): String {
        val n = if (count > 1) " · $count messages" else ""
        return when (status) {
            AgentStatus.Working -> "Held until $agent is ready$n"
            AgentStatus.Blocked -> "Held: $agent asks you something first$n"
            else -> "$agent is ready: review and send$n"
        }
    }

    /**
     * The held messages the agent already has, by any way (sent from another phone or the
     * computer, or typed although the send reported a failure): those whose words are a user
     * message the chat didn't show when they were held, or a message it queued. Each copy counts once.
     */
    fun delivered(held: List<HeldMessage>, users: List<ChatItem.User>, queued: List<String>): Set<String> {
        val usedUsers = HashSet<String>()
        val usedQueued = HashSet<Int>()
        return held.filter { m ->
            val user = users.firstOrNull { it.id !in m.known && it.id !in usedUsers && SentMatch.same(m.text, it.text) }
            if (user != null) { usedUsers += user.id; return@filter true }
            val q = queued.indices.firstOrNull { it !in usedQueued && SentMatch.same(m.text, queued[it]) }
            if (q != null) { usedQueued += q; return@filter true }
            false
        }.map { it.id }.toSet()
    }

    /** True once the agent is ready for the held words: the caption takes the accent. */
    fun ready(status: AgentStatus): Boolean = status == AgentStatus.Idle || status == AgentStatus.Done
}

/** The quick replies over the composer: one tap sends one. Off until turned on in Settings. */
@Serializable
data class QuickReplies(val on: Boolean = false, val replies: List<String> = DEFAULT) {
    /** The replies to show: trimmed, none empty, each once. */
    val shown: List<String> get() = clean(replies)

    companion object {
        val DEFAULT = listOf("continue", "commit and push", "run the tests")

        /** A reply is one short line. */
        const val MAX_LENGTH = 200

        fun clean(replies: List<String>): List<String> =
            replies.map { it.replace(Regex("\\s+"), " ").trim().take(MAX_LENGTH) }.filter { it.isNotEmpty() }.distinct()
    }
}
