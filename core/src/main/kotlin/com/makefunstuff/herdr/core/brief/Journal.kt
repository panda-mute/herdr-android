package com.makefunstuff.herdr.core.brief

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.HerdrJson
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.push.HerdrPush
import com.makefunstuff.herdr.core.ssh.MachineConnection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * herdr-push's journal on a machine (`herdr-push journal`): each time an agent finished or asked. Times
 * are the machine's clock in seconds; [now] is that clock when it answered, so the brief can put them
 * on the phone's.
 */
data class Journal(
    val now: Long,
    /** Since when the journal is complete; null when herdr-push never watched this herdr session. */
    val from: Long?,
    /** Up to when (herdr-push's last look); null when it never looked. */
    val upto: Long?,
    val entries: List<JournalEntry>,
)

sealed interface JournalEntry {
    /** When, on the machine's clock (seconds). */
    val at: Long

    /** An agent that finished or started asking. */
    data class Agent(override val at: Long, val agent: AgentInfo, val where: String, val workspace: String) : JournalEntry
}

object Journals {
    /** What `herdr-push journal` answered; null when it isn't a journal (an older herdr-push, which has none). */
    fun parse(o: JsonObject): Journal? {
        if (o["entries"] !is JsonArray) return null
        val entries = (o["entries"] as JsonArray).mapNotNull { e ->
            val x = e as? JsonObject ?: return@mapNotNull null
            val at = x.long("at") ?: return@mapNotNull null
            when (x.str("type")) {
                "agent" -> (x["agent"] as? JsonObject)
                    ?.let { runCatching { HerdrJson.decodeFromJsonElement(AgentInfo.serializer(), it) }.getOrNull() }
                    ?.let { JournalEntry.Agent(at, it, x.str("where"), x.str("workspace")) }
                else -> null
            }
        }
        return Journal(now = o.long("now") ?: 0, from = o.long("from"), upto = o.long("upto"), entries = entries.sortedBy { it.at })
    }

    /**
     * The journal of [session] on the machine since [sinceSeconds] (its clock; a little earlier is
     * asked, as clocks differ); null where herdr-push isn't there, is too old to keep one, or has none.
     */
    suspend fun read(conn: MachineConnection, label: String, session: String, sinceSeconds: Long): Journal? = runCatching {
        val o = HerdrPush.Client(conn, label).run(
            "journal", "--session", session.ifEmpty { "default" }, "--since", (sinceSeconds - SLACK_SECONDS).coerceAtLeast(0).toString(),
            timeoutSeconds = 30,
        )
        parse(o)?.takeIf { it.from != null }
    }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; null }

    /** Asked for a little before the window, in case the machine's clock is behind the phone's. */
    private const val SLACK_SECONDS = 600L

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
}

/**
 * What the app showed of a machine when the person last looked: each task's status and each agent's
 * state. Where a machine keeps no journal, the brief compares how it stands now with this.
 */
@Serializable
data class Baseline(
    /** Task id → its [com.makefunstuff.herdr.core.board.WorkStatus] name; null when the board was never read. */
    val tasks: Map<String, String>? = null,
    /** When the tasks were seen (the phone's clock, ms). */
    val tasksAt: Long = 0,
    /** Pane id → [mark]; null when the agents were never seen. */
    val agents: Map<String, String>? = null,
    val agentsAt: Long = 0,
) {
    companion object {
        /** An agent's state and its number: one that finished again since counts as new. */
        fun mark(a: AgentInfo): String = "${a.agentStatusRaw}#${a.stateChangeSeq}"

        /** [old] with what was seen at [at]; a part not seen now ([tasks] or [snapshot] null) stays as it was. */
        fun of(old: Baseline?, at: Long, tasks: List<WorkTask>?, snapshot: SessionSnapshot?): Baseline = Baseline(
            tasks = tasks?.associate { it.id to it.status.name } ?: old?.tasks,
            tasksAt = if (tasks != null) at else old?.tasksAt ?: 0,
            agents = snapshot?.agents?.associate { it.paneId to mark(it) } ?: old?.agents,
            agentsAt = if (snapshot != null) at else old?.agentsAt ?: 0,
        )
    }
}
