package com.makefunstuff.herdr.core.push

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.SessionSnapshot
import kotlinx.serialization.Serializable

/**
 * A working agent as a Live Update on the phone: an ongoing notification with its
 * progress, which Android 16 also shows as a chip in the status bar. Words, the chip, how far the run
 * got, and what Stop does. Plain data, so the rules are tested without a phone.
 */
@Serializable
data class LiveItem(
    /** One per agent: [LiveItems.agentTag]. */
    val tag: String,
    val kind: Kind,
    val machineId: String,
    val machineLabel: String,
    val title: String,
    val text: String,
    /** The status bar chip's words; empty: the time since [since] shows instead. */
    val chip: String,
    /** When it began working, on the phone's clock (ms); 0 unknown. */
    val since: Long = 0,
    val paneId: String = "",
    /** An agent's name in herdr. */
    val agentName: String = "",
    /** The label of an agent's workspace. */
    val workspace: String = "",
) {
    enum class Kind { Agent }
}

object LiveItems {
    fun agentTag(machineId: String, paneId: String) = "live:agent:$machineId:$paneId"

    /** What every tag of [machineId]'s [kind] starts with. */
    fun prefix(machineId: String, kind: LiveItem.Kind) = "live:${kind.name.lowercase()}:$machineId:"

    fun isLive(tag: String) = tag.startsWith("live:")

    /**
     * [a] as a Live Update while it works, else null. [workspace]: its workspace's label (the part of
     * [where] before its tab, when not given).
     */
    fun agent(machineId: String, machineLabel: String, a: AgentInfo, where: String, since: Long = 0, workspace: String = ""): LiveItem? {
        if (a.agentStatus != AgentStatus.Working) return null
        val raw = a.name?.takeIf { it.isNotBlank() }
        val name = raw ?: a.displayName
        // Each part once: an agent's title is often its workspace's label too.
        val doing = a.subtitle?.takeIf { it != name && !where.contains(it) }
        return LiveItem(
            tag = agentTag(machineId, a.paneId),
            kind = LiveItem.Kind.Agent,
            machineId = machineId,
            machineLabel = machineLabel,
            title = name,
            text = listOfNotNull("Working", doing, where.takeIf { it.isNotBlank() }).joinToString(" · "),
            chip = "",
            since = since,
            paneId = a.paneId,
            agentName = raw.orEmpty(),
            workspace = workspace.ifEmpty { where.substringBefore(" › ", "") },
        )
    }

    /** A time on the machine's clock (seconds), sent at [at] by it, on the phone's clock at [now] (ms); 0 unknown. */
    fun phoneTime(since: Long, at: Long, now: Long): Long =
        if (since <= 0 || at <= 0) 0 else now - (at - since).coerceAtLeast(0) * 1000

    /** A push's live event as Live Updates: its agents, null when the push didn't say. */
    fun of(machineId: String, machineLabel: String, e: PushEvent.Live, at: Long, now: Long): List<LiveItem>? =
        e.agents?.mapNotNull { agent(machineId, machineLabel, it.agent, it.where, phoneTime(it.since, at, now), it.workspace) }

    /** What the app sees on a machine itself (on screen, or a look while closed). */
    fun agents(machineId: String, machineLabel: String, snapshot: SessionSnapshot): List<LiveItem> = snapshot.agents.mapNotNull {
        agent(machineId, machineLabel, it, snapshot.location(it.workspaceId, it.tabId), workspace = snapshot.workspace(it.workspaceId)?.label.orEmpty())
    }
}

/** What a [LiveTracker] change means for the notifications: post (or replace) these, take those down. */
data class LiveChanges(val post: List<LiveItem> = emptyList(), val cancel: List<String> = emptyList()) {
    val isEmpty: Boolean get() = post.isEmpty() && cancel.isEmpty()
}

/**
 * Which Live Updates are up. Each machine tells which of its agents work (a push, or what the app sees
 * itself), and each list replaces the one before. An agent muted on the phone ([mute]) is left out: no
 * notification of any kind from it.
 */
class LiveTracker {
    private val parts = LinkedHashMap<String, List<LiveItem>>()
    private val shown = LinkedHashMap<String, LiveItem>()
    /** Live Updates already up when the tracker started (the app was restarted): down once their list leaves them out. */
    private val leftover = HashSet<String>()
    /** The Live Update tags of the agents muted. */
    private var muted: Set<String> = emptySet()

    private fun muted(i: LiveItem) = i.tag in muted

    /** The agents muted now, by `machine/pane`: theirs come down, and come back up once unmuted. */
    @Synchronized
    fun mute(keys: Set<String>): LiveChanges {
        val tags = keys.map { LiveItems.agentTag(it.substringBeforeLast('/'), it.substringAfterLast('/')) }.toSet()
        if (tags == muted) return LiveChanges()
        muted = tags
        val gone = leftover.filter { it in tags }
        leftover.removeAll(gone.toSet())
        return diff(null, gone)
    }

    @Synchronized
    fun adopt(tags: Collection<String>) {
        leftover += tags.filter { LiveItems.isLive(it) && it !in shown }
    }

    /**
     * [machineId]'s [kind] list is now [items]. [refresh] posts them again even when unchanged: a push
     * says the machine still watches, so they stay up longer.
     */
    @Synchronized
    fun update(machineId: String, kind: LiveItem.Kind, items: List<LiveItem>, refresh: Boolean = false): LiveChanges {
        val key = LiveItems.prefix(machineId, kind)
        val before = parts[key].orEmpty().associateBy { it.tag }
        parts[key] = items.map { keepSince(it, before[it.tag] ?: shown[it.tag]) }
        // A muted agent up from before comes down too.
        val tags = items.filterNot(::muted).map { it.tag }.toSet()
        val gone = leftover.filter { it.startsWith(key) && it !in tags }
        leftover.removeAll(gone.toSet() + tags)
        return diff(if (refresh) key else null, gone)
    }

    /** Takes down everything of [machineId] (it left the app, or was left out). */
    @Synchronized
    fun drop(machineId: String): LiveChanges {
        LiveItem.Kind.entries.forEach { parts.remove(LiveItems.prefix(machineId, it)) }
        val gone = leftover.filter { t -> LiveItem.Kind.entries.any { t.startsWith(LiveItems.prefix(machineId, it)) } }
        leftover.removeAll(gone.toSet())
        return diff(null, gone)
    }

    /** Takes down every Live Update (turned off). */
    @Synchronized
    fun clear(): LiveChanges {
        val all = shown.keys + leftover
        parts.clear()
        shown.clear()
        leftover.clear()
        return LiveChanges(cancel = all.distinct())
    }

    @Synchronized
    fun item(tag: String): LiveItem? = shown[tag]

    @Synchronized
    fun items(): List<LiveItem> = shown.values.toList()

    /** The time it began stays as first known: a push's own (seconds, so a little off each time) or the app's. */
    private fun keepSince(item: LiveItem, prev: LiveItem?): LiveItem {
        if (prev == null || prev.since == 0L) return item
        return if (item.since == 0L || kotlin.math.abs(item.since - prev.since) < 60_000) item.copy(since = prev.since) else item
    }

    private fun diff(refresh: String?, gone: List<String>): LiveChanges {
        val all = parts.values.flatten()
        val next = LinkedHashMap<String, LiveItem>()
        for (i in all) {
            if (muted(i)) continue
            next[i.tag] = i
        }
        val post = next.values.filter { shown[it.tag] != it || (refresh != null && it.tag.startsWith(refresh)) }
        val cancel = shown.keys.filter { it !in next } + gone
        shown.clear()
        shown.putAll(next)
        return LiveChanges(post, cancel.distinct())
    }
}
