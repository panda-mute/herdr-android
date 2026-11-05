package com.makefunstuff.herdr.core.push

import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.chat.BlockedPrompt
import com.makefunstuff.herdr.core.chat.OptionRole
import com.makefunstuff.herdr.core.guard.Guarded
import com.makefunstuff.herdr.core.health.Disk
import com.makefunstuff.herdr.core.health.HealthReport
import com.makefunstuff.herdr.core.health.Sizes
import kotlinx.serialization.Serializable

/**
 * A notification on the phone, worked out from what a machine said (a push, or the app's own look
 * while it is closed): words, where a tap goes, and the buttons. The app turns it into an Android
 * notification; everything here is plain data, so the rules are tested without a phone.
 */
@Serializable
data class Alert(
    /** One notification per agent: a newer state replaces the older one under the same tag. */
    val tag: String,
    /** The state it shows; the same signature again is not shown twice. Empty: always shown. */
    val sig: String,
    val kind: Kind,
    val machineId: String,
    val title: String,
    val text: String,
    /** The longer text when the notification is opened up. */
    val detail: String = "",
    /** What a tap opens: the agent's chat. */
    val paneId: String = "",
    val actions: List<AlertAction> = emptyList(),
    /** An agent's name, as its chat shows it: what its chat is called when it floats as a bubble. */
    val agentName: String = "",
    /** A tap opens the machine's health screen (a machine low on RAM or disk). */
    val health: Boolean = false,
    /** A tap opens the machine's usage page (a provider near or at a limit). */
    val usage: Boolean = false,
    /** The space an agent's news comes from (its workspace's name), whose [Notify] it follows; empty for the rest. */
    val space: String = "",
    /** A Shepherdr 3 task's id ([TaskAlerts]): a tap opens the task, its buttons act on it. */
    val task: String = "",
    /** A Shepherdr 3 Assistant's chat that waits on you, for its Answer. */
    val chat: String = "",
    /** A tap opens [task]'s Accept card: it is ready to accept. */
    val accept: Boolean = false,
) {
    /**
     * What it is about, which the notification rules ([AlertRules]) switch on and off: something waits
     * for you, something finished, something went wrong (a review or a check failed), a test, or an AI
     * provider near or at a limit.
     */
    enum class Kind { NeedsYou, Finished, WentWrong, Test, Limits }
}

/** A notification's button. */
@Serializable
data class AlertAction(
    val type: Type,
    val label: String,
    /** [Type.Choose]: the menu option to pick, and what the screen must still show for it to be picked. */
    val option: Int = -1,
    val optionLabel: String = "",
    val question: String = "",
    /** [Type.Choose]: what the question showed of what it would do (the command), which must still be the same. */
    val details: List<String> = emptyList(),
    /** The reply box's hint, for the reply types. */
    val hint: String = "",
    /** A [com.makefunstuff.herdr.core.guard.Guarded] key: the button asks for the fingerprint first, when that is on. */
    val guard: String = "",
) {
    enum class Type {
        /** Picks an option of the agent's question. */
        Choose,
        /** Words to the agent: its answer when blocked, its next prompt when done. */
        ReplyAgent,
        /** Accepts a Ready Shepherdr 3 task ([Alert.task]): it merges. */
        Accept,
        /** Words to what a Shepherdr 3 task (or Assistant) waits on you for: its question's answer. */
        AnswerTask,
    }

    val reply: Boolean get() = type == Type.ReplyAgent || type == Type.AnswerTask
}

/** What an event does to the notifications: show (or replace) one, or take one away. */
sealed interface AlertChange {
    val tag: String

    data class Show(val alert: Alert) : AlertChange {
        override val tag: String get() = alert.tag
    }

    data class Clear(override val tag: String) : AlertChange
}

object Alerts {
    fun agentTag(machineId: String, paneId: String) = "agent:$machineId:$paneId"
    fun testTag(machineId: String) = "test:$machineId"
    fun healthTag(machineId: String) = "health:$machineId"
    fun usageTag(machineId: String, provider: String, limit: String) = "usage:$machineId:$provider:$limit"

    /**
     * The notifications [events] call for; what runs ([PushEvent.Live]) is a Live Update instead (LiveItems).
     * [taskAlerts]: Shepherdr 3 tells the phone of its tasks itself ([TaskAlerts]), so a task's agent alerts as its task, once.
     */
    fun all(machineId: String, machineLabel: String, events: List<PushEvent>, host: String = "", taskAlerts: Boolean = false): List<AlertChange> =
        events.filter { it !is PushEvent.Live }.map { of(machineId, machineLabel, it, host, taskAlerts) }

    fun of(machineId: String, machineLabel: String, event: PushEvent, host: String = "", taskAlerts: Boolean = false): AlertChange = when (event) {
        is PushEvent.Live -> throw IllegalArgumentException("What runs is a Live Update, not an alert")
        is PushEvent.Agent ->
            if (taskAlerts && event.agent.shepherdrTask != null) AlertChange.Clear(agentTag(machineId, event.agent.paneId))
            else agent(machineId, machineLabel, event)
        is PushEvent.Health -> health(machineId, machineLabel, event.report)
        is PushEvent.Usage -> usage(machineId, machineLabel, event)
        PushEvent.Test -> AlertChange.Show(
            Alert(
                tag = testTag(machineId),
                sig = "",
                kind = Alert.Kind.Test,
                machineId = machineId,
                title = "Notifications from $machineLabel work",
                text = if (host.isNotEmpty()) "herdr-push on $host reached this phone." else "herdr-push reached this phone.",
            ),
        )
    }

    private fun agent(machineId: String, machineLabel: String, e: PushEvent.Agent): AlertChange {
        val a = e.agent
        val tag = agentTag(machineId, a.paneId)
        val name = a.name?.takeIf { it.isNotBlank() } ?: a.displayName
        val where = listOf(machineLabel, e.where).filter { it.isNotBlank() }.joinToString(" · ")
        val space = Notify.spaceOf(e.where)
        return when (a.agentStatus) {
            AgentStatus.Blocked -> {
                val prompt = e.screen?.let { BlockedPrompt.parse(it) }
                val question = prompt?.question ?: "Waiting for your answer"
                AlertChange.Show(
                    Alert(
                        tag = tag,
                        sig = "blocked#${a.stateChangeSeq}",
                        kind = Alert.Kind.NeedsYou,
                        machineId = machineId,
                        title = "$name needs you",
                        text = question,
                        detail = (listOf(question) + prompt?.details.orEmpty().take(4) + where).joinToString("\n"),
                        paneId = a.paneId,
                        agentName = name,
                        actions = choices(prompt) + AlertAction(AlertAction.Type.ReplyAgent, "Reply", hint = "Answer $name"),
                        space = space,
                    ),
                )
            }
            AgentStatus.Done -> {
                val did = a.subtitle ?: "Ready for the next prompt"
                AlertChange.Show(
                    Alert(
                        tag = tag,
                        sig = "done#${a.stateChangeSeq}",
                        kind = Alert.Kind.Finished,
                        machineId = machineId,
                        title = "$name finished",
                        text = did,
                        detail = "$did\n$where",
                        paneId = a.paneId,
                        agentName = name,
                        actions = listOf(AlertAction(AlertAction.Type.ReplyAgent, "Reply", hint = "Next prompt for $name")),
                        space = space,
                    ),
                )
            }
            else -> AlertChange.Clear(tag)
        }
    }

    /** Approve and Deny for a yes-or-no question; a short menu's own options; nothing for a longer one (a tap opens it). */
    private fun choices(prompt: BlockedPrompt?): List<AlertAction> {
        if (prompt == null) return emptyList()
        fun choose(i: Int, label: String) = AlertAction(
            AlertAction.Type.Choose, label, option = i, optionLabel = prompt.options[i].label, question = prompt.question, details = prompt.details,
            // A permanent "don't ask again" changes what the agent may do for good.
            guard = if (prompt.role(i) == OptionRole.Always) Guarded.AlwaysAllow.key else "",
        )
        // A multiple choice or a form of several questions is answered in the chat.
        if (prompt.multiSelect || prompt.steps.isNotEmpty()) return emptyList()
        if (prompt.yesNo) return listOf(choose(prompt.approveIndex!!, "Approve"), choose(prompt.denyIndex!!, "Deny"))
        if (prompt.options.size <= 2) return prompt.options.indices.map { choose(it, short(prompt.shortLabel(it))) }
        return emptyList()
    }

    private fun short(label: String) = if (label.length <= 24) label else label.take(23).trimEnd() + "…"

    /**
     * A machine low on RAM or disk: what is low, and what uses the most memory. It needs you (a full
     * machine stops agents), so it comes through quiet hours; it comes down once there is room again.
     */
    fun health(machineId: String, machineLabel: String, r: HealthReport): AlertChange {
        val tag = healthTag(machineId)
        if (r.low.isEmpty()) return AlertChange.Clear(tag)
        val words = HealthWords.words(machineLabel, r)
        return AlertChange.Show(
            Alert(
                tag = tag,
                sig = "low:" + r.low.sorted().joinToString(","),
                kind = Alert.Kind.NeedsYou,
                machineId = machineId,
                title = words.first,
                text = words.second,
                detail = words.third,
                health = true,
            ),
        )
    }

    /**
     * A provider near a limit (80%), almost out (95%) or out (100%); each mark once per window (a new
     * window resets [UsageLimit.resets], and with it the signature). It can wait: quiet hours hold it.
     * Its kind is [Alert.Kind.Limits], which the notification rules switch on and off.
     * Back under the marks (level 0), it comes down.
     */
    fun usage(machineId: String, machineLabel: String, e: PushEvent.Usage, now: Long = System.currentTimeMillis() / 1000): AlertChange {
        val tag = usageTag(machineId, e.provider, e.limit.id)
        if (e.level <= 0) return AlertChange.Clear(tag)
        val (title, text) = com.makefunstuff.herdr.core.usage.UsageWords.warning(e.name, e.limit, e.level, machineLabel, now)
        return AlertChange.Show(
            Alert(
                tag = tag,
                sig = "usage:${e.level}:${e.limit.resets ?: 0}",
                kind = Alert.Kind.Limits,
                machineId = machineId,
                title = title,
                text = text,
                detail = "$title\n$text",
                usage = true,
            ),
        )
    }

}

/** How a machine running low is put in words, for the notification and the health screen's warning. */
object HealthWords {
    /** Title, one line, and the longer text. */
    fun words(machineLabel: String, r: HealthReport): Triple<String, String, String> {
        val disks = r.disks.ifEmpty { r.low.filter { it.startsWith("disk:") }.map { Disk(it.removePrefix("disk:"), "", "", 0, 0, 0) } }
        val title = when {
            r.ram && disks.isNotEmpty() -> "$machineLabel is low on memory and disk"
            r.ram -> "$machineLabel is low on memory"
            else -> "$machineLabel is low on disk space"
        }
        val parts = buildList {
            if (r.ram) add(r.memory?.let { "${Sizes.percent(it.availablePercent)} of ${Sizes.kb(it.totalKb)} RAM free" } ?: "RAM almost full")
            disks.forEach { d -> add(if (d.totalKb > 0) "${Sizes.kb(d.availKb)} free on ${d.mount}" else "${d.mount} almost full") }
        }
        val top = r.top.take(3).joinToString(", ") { "${it.first} (${Sizes.kb(it.second)})" }
        val line = parts.joinToString(" · ")
        val detail = listOfNotNull(line, top.ifEmpty { null }?.let { "Most memory: $it" }).joinToString("\n")
        return Triple(title, if (top.isNotEmpty() && r.ram) "$line · most: ${r.top.first().first}" else line, detail)
    }
}
