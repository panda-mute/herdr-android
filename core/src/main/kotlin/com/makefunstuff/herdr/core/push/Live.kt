package com.makefunstuff.herdr.core.push

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.board.BoardStatus
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.BoardTone
import kotlinx.serialization.Serializable

/**
 * A running board task or agent as a Live Update on the phone: an ongoing notification with its
 * progress, which Android 16 also shows as a chip in the status bar. Words, the chip, how far the run
 * got, and what Stop does. Plain data, so the rules are tested without a phone.
 */
@Serializable
data class LiveItem(
    /** One per task or agent: [LiveItems.taskTag] or [LiveItems.agentTag]. */
    val tag: String,
    val kind: Kind,
    val machineId: String,
    val machineLabel: String,
    val title: String,
    val text: String,
    /** The status bar chip's words ("42%", "Rev 2/4"); empty: the time since [since] shows instead. */
    val chip: String,
    /** A task's part of its run, a [LiveItems.Part] ordinal; -1 for an agent. */
    val part: Int = -1,
    /** How far along the whole run, 0 to [LiveItems.MAX]; null: going, with no end known (an agent). */
    val progress: Int? = null,
    /** When this part of the run began, on the phone's clock (ms); 0 unknown. */
    val since: Long = 0,
    val paneId: String = "",
    val taskId: String = "",
    /** The task's short key (`AND-7`), or its id where it has none. */
    val taskName: String = "",
    /** An agent's name in herdr: the board's agents have names made from their task (see [LiveItems.belongs]). */
    val agentName: String = "",
    /** The label of an agent's workspace: the board names a task's workspace after the task. */
    val workspace: String = "",
) {
    enum class Kind { Task, Agent }

    /** The board's Coordinator at work (its agent is `sw-coordinator`, or `sw-coordinator-2` when that name was taken). */
    val coordinator: Boolean get() = kind == Kind.Agent && LiveItems.isCoordinator(agentName)

    /** Stop asks first for a task (the board's `stop` ends its agent's run); an agent's Stop is Esc, as in its chat. */
    val stopAsks: Boolean get() = kind == Kind.Task
}

object LiveItems {
    /** The whole bar. */
    const val MAX = 100

    /** A task's run in the board's order, with each part's share of the bar and its colour. */
    enum class Part(val words: String, val length: Int, val tone: BoardTone) {
        Work("Working", 55, BoardTone.Amber),
        Check("Checking", 15, BoardTone.Teal),
        Review("In review", 20, BoardTone.Blue),
        Land("Merging", 10, BoardTone.Green),
    }

    fun taskTag(machineId: String, taskId: String) = "live:task:$machineId:$taskId"
    fun agentTag(machineId: String, paneId: String) = "live:agent:$machineId:$paneId"

    /** What every tag of [machineId]'s [kind] starts with. */
    fun prefix(machineId: String, kind: LiveItem.Kind) = "live:${kind.name.lowercase()}:$machineId:"

    fun isLive(tag: String) = tag.startsWith("live:")

    /** The part of the run a board stage belongs to (`flow` names them; the ones not listed are work). */
    fun part(stage: String): Part = when (stage) {
        "checking", "pushing" -> Part.Check
        "reviewing", "reviewneeds" -> Part.Review
        "merging" -> Part.Land
        else -> Part.Work
    }

    /**
     * Whether a task's agent works on it now: not waiting for you (that is a notification of its own),
     * not queued, and this machine's own task (another machine's shows from that machine).
     */
    fun running(t: BoardTask): Boolean =
        !t.needsYou && t.machine.isEmpty() && (t.status == BoardStatus.Working || t.status == BoardStatus.Reviewing)

    /** [t] as a Live Update, or null when it isn't running. [reviews]: the most reviews it gets, when known. */
    fun task(machineId: String, machineLabel: String, t: BoardTask, reviews: Int? = null, since: Long = 0): LiveItem? {
        if (!running(t)) return null
        val part = part(t.stage)
        val pct = t.progress?.coerceIn(0, 100)
        val eta = t.eta?.takeIf { it > 0 }
        val round = t.reviewRound
        // The round of a review that can still be counted against the most there are.
        val of = reviews?.takeIf { it > 0 && round in 1..it }
        val start = Part.entries.takeWhile { it != part }.sumOf { it.length }
        val within = when (part) {
            Part.Work -> (pct ?: 0) * part.length / 100
            Part.Review -> if (of != null) (round - 1) * part.length / of else 0
            else -> 0
        }
        val chip = when (part) {
            Part.Work -> pct?.let { "$it%" } ?: eta?.let { "${it}m" } ?: "Work"
            Part.Check -> "Check"
            Part.Review -> when {
                round <= 0 -> "Review"
                of != null -> "Rev $round/$of"
                else -> "Rev $round"
            }
            Part.Land -> "Merge"
        }
        val stage = when (part) {
            Part.Work -> listOfNotNull("Working", pct?.let { "$it%" }, eta?.let { "about $it min left" }).joinToString(" · ")
            Part.Review -> when {
                round <= 0 -> part.words
                of != null -> "Review $round of $of"
                else -> "Review $round"
            }
            else -> part.words
        }
        return LiveItem(
            tag = taskTag(machineId, t.id),
            kind = LiveItem.Kind.Task,
            machineId = machineId,
            machineLabel = machineLabel,
            title = "${t.name} ${t.title}",
            text = listOf(stage, t.msg).filter { it.isNotBlank() }.joinToString(" · "),
            chip = chip,
            part = part.ordinal,
            progress = start + within,
            since = since,
            taskId = t.id,
            taskName = t.name,
        )
    }

    /**
     * [a] as a Live Update while it works, else null. [workspace]: its workspace's label (the part of
     * [where] before its tab, when not given).
     */
    /** The name the board gives its Coordinator's agent: `sw-coordinator`, or `sw-coordinator-2` when that name was taken. */
    fun isCoordinator(agentName: String): Boolean = agentName == "sw-coordinator" || agentName.startsWith("sw-coordinator-")

    fun agent(machineId: String, machineLabel: String, a: AgentInfo, where: String, since: Long = 0, workspace: String = ""): LiveItem? {
        if (a.agentStatus != AgentStatus.Working) return null
        val raw = a.name?.takeIf { it.isNotBlank() }
        val title = a.title?.takeIf { it.isNotBlank() }
        val name = when {
            raw != null && isCoordinator(raw) -> "Coordinator"
            // A board task's agent: its name is made up, its title says which task.
            raw != null && raw.startsWith("sw-") && title != null -> title
            else -> raw ?: a.displayName
        }
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

    /**
     * Whether [agent] works on [task] (its worker, its reviewer, the one picking among attempts), so
     * the task's Live Update stands for it. herdr-projects names them `sw-<repo>-<n>` (the repo in
     * lower case, other characters as `-`, at most 16), with `-<hash>` when that name is taken, `-r`
     * for the reviewer and `-pick` for the pick, cut to 32 characters, with `-NNNN` when taken. A
     * reviewer can also be `sw-task-<n>-r`. And it opens the task's workspace under the task's key and
     * title, which ties every one of them to it, whatever the name.
     */
    fun belongs(agent: LiveItem, task: LiveItem): Boolean {
        if (agent.kind != LiveItem.Kind.Agent || task.kind != LiveItem.Kind.Task || agent.machineId != task.machineId) return false
        return belongs(agent.agentName, agent.workspace, task.taskId, task.taskName)
    }

    /** [belongs] by the parts it looks at: the agent's name and workspace, the task's id and short key (both on one machine). */
    fun belongs(agentName: String, workspace: String, taskId: String, taskName: String): Boolean {
        // A short key (`AND-12`; on Shepherdr the id itself, `HA-10`), never a v1 id (`herdr-android/3`).
        val key = taskName.takeIf { '-' in it && '/' !in it }
        if (key != null && (workspace == key || workspace.startsWith("$key "))) return true
        val name = agentName.ifEmpty { return false }
        val repo = taskId.substringBeforeLast('/').lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("").take(16)
        val base = "sw-$repo-${taskId.substringAfterLast('/')}"
        return name == base || name.startsWith("$base-")
    }

    /** A time on the machine's clock (seconds), sent at [at] by it, on the phone's clock at [now] (ms); 0 unknown. */
    fun phoneTime(since: Long, at: Long, now: Long): Long =
        if (since <= 0 || at <= 0) 0 else now - (at - since).coerceAtLeast(0) * 1000

    /** A push's live event as Live Updates: its agents and its tasks, each null when the push didn't say. */
    fun of(machineId: String, machineLabel: String, e: PushEvent.Live, at: Long, now: Long): Pair<List<LiveItem>?, List<LiveItem>?> =
        e.agents?.mapNotNull { agent(machineId, machineLabel, it.agent, it.where, phoneTime(it.since, at, now), it.workspace) } to
            e.tasks?.mapNotNull { task(machineId, machineLabel, it.task, it.reviews, phoneTime(it.since, at, now)) }

    /** What the app sees on a machine itself (on screen, or a look while closed). */
    fun agents(machineId: String, machineLabel: String, snapshot: SessionSnapshot): List<LiveItem> = snapshot.agents.mapNotNull {
        agent(machineId, machineLabel, it, snapshot.location(it.workspaceId, it.tabId), workspace = snapshot.workspace(it.workspaceId)?.label.orEmpty())
    }

    fun tasks(machineId: String, machineLabel: String, tasks: List<BoardTask>): List<LiveItem> =
        tasks.mapNotNull { task(machineId, machineLabel, it) }
}

/** What a [LiveTracker] change means for the notifications: post (or replace) these, take those down. */
data class LiveChanges(val post: List<LiveItem> = emptyList(), val cancel: List<String> = emptyList()) {
    val isEmpty: Boolean get() = post.isEmpty() && cancel.isEmpty()
}

/**
 * Which Live Updates are up. Each machine tells what runs there, its agents and its tasks apart (a
 * push, or what the app sees itself), and each list replaces the one before. An agent working on a
 * board task that is up is left out: the task stands for it. So is an agent muted on the phone
 * ([mute]): no notification of any kind from it.
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

    /** The time a part began stays as first known: a push's own (seconds, so a little off each time) or the app's. */
    private fun keepSince(item: LiveItem, prev: LiveItem?): LiveItem {
        if (prev == null || prev.part != item.part || prev.since == 0L) return item
        return if (item.since == 0L || kotlin.math.abs(item.since - prev.since) < 60_000) item.copy(since = prev.since) else item
    }

    private fun diff(refresh: String?, gone: List<String>): LiveChanges {
        val all = parts.values.flatten()
        val tasks = all.filter { it.kind == LiveItem.Kind.Task }
        val next = LinkedHashMap<String, LiveItem>()
        for (i in all) {
            if (i.kind == LiveItem.Kind.Agent && tasks.any { LiveItems.belongs(i, it) }) continue
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
