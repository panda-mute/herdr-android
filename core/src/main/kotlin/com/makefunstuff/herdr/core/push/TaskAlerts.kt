package com.makefunstuff.herdr.core.push

import com.makefunstuff.herdr.core.api.HerdrJson
import com.makefunstuff.herdr.core.board.WorkOverview
import com.makefunstuff.herdr.core.guard.Guarded
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URLDecoder

/**
 * One alert as a Shepherdr 3 service pushes it to the pages that subscribed (service/push.ts `alertsOf`), the
 * app among them: "HERD-4 · Needs you" over why, or "HERD-4 · Finished" over its title with a [url] to its Accept
 * card. [tag] is the task's id (or an Assistant's chat, or `alert:<id>` for an agent's own alert); [waits]: it
 * waits on you until answered; [open]: the tags of everything that still waits as it was sent.
 */
data class TaskPush(
    val title: String,
    val body: String,
    val tag: String,
    val waits: Boolean = false,
    val url: String = "",
    val open: List<String>? = null,
) {
    /** The task whose Accept card a tap opens (`./#accept=<id>`): it is ready to accept. */
    val accept: String? get() = url.substringAfter("#accept=", "").takeIf { it.isNotEmpty() }?.let { URLDecoder.decode(it, "UTF-8") }

    /** The task it is about: the one to accept, else its tag where that is a task's id (not a chat, not an agent's alert). */
    val task: String? get() = accept ?: tag.takeIf { it.isNotEmpty() && '/' !in it && !it.startsWith("p:") && !it.startsWith("alert:") }
}

/**
 * Shepherdr 3's task alerts on the phone. The service decides what alerts and says it in its own words, as the
 * window's toasts and the web app's alerts (one per task, a Ready one taking its Needs you's place); the app adds
 * the buttons (Accept, Answer), opens a tap on the task, and holds back what the space's [Notify] says to.
 */
object TaskAlerts {
    fun tag(machineId: String, tag: String) = "task:$machineId:$tag"

    /** The prefix of every task alert from [machineId]. */
    fun prefix(machineId: String) = "task:$machineId:"

    private const val WAITS = "waits:"

    fun parse(json: ByteArray): TaskPush? {
        val o = runCatching { HerdrJson.parseToJsonElement(json.toString(Charsets.UTF_8)).jsonObject }.getOrNull() ?: return null
        val title = o.str("title").takeIf { it.isNotEmpty() } ?: return null
        return TaskPush(
            title = title,
            body = o.str("body"),
            tag = o.str("tag"),
            waits = (o["waits"] as? JsonPrimitive)?.booleanOrNull == true,
            url = o.str("url"),
            open = (o["open"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
        )
    }

    /**
     * What [p] from [machineId] ([machineLabel]) does: first take down what no longer waits on you ([shown]: the
     * notifications up now, tag to signature), then show it. [space]: the name of the space it is from, as the
     * app last read the board ([spaces]); empty when not known, so it follows the notification settings.
     */
    fun changes(machineId: String, machineLabel: String, p: TaskPush, space: String, shown: Map<String, String>): List<AlertChange> {
        val keep = p.open?.map { tag(machineId, it) }?.toSet()
        val gone = if (keep == null) emptyList() else shown.filter { (t, sig) -> t.startsWith(prefix(machineId)) && sig.startsWith(WAITS) && t !in keep }
            .keys.map { AlertChange.Clear(it) }
        return gone + AlertChange.Show(alert(machineId, machineLabel, p, space))
    }

    fun alert(machineId: String, machineLabel: String, p: TaskPush, space: String): Alert {
        val accept = p.accept
        val actions = when {
            accept != null -> listOf(AlertAction(AlertAction.Type.Accept, "Accept", guard = Guarded.Merge.key))
            p.waits -> listOf(AlertAction(AlertAction.Type.AnswerTask, "Answer", hint = "Answer ${p.title.substringBefore(" · ")}"))
            else -> emptyList()
        }
        return Alert(
            tag = tag(machineId, p.tag.ifEmpty { p.title }),
            // Each alert comes once from the service; one sent again (a push app's retry) is the same words.
            sig = (if (p.waits) WAITS else "news:") + p.title + "\n" + p.body,
            // A Ready task waits on your Accept as a question does: both reach you on Needs me.
            kind = Alert.Kind.NeedsYou,
            machineId = machineId,
            title = p.title,
            text = p.body,
            detail = listOf(p.body, machineLabel).filter { it.isNotBlank() }.joinToString("\n"),
            actions = actions,
            space = space,
            task = p.task.orEmpty(),
            chat = if (p.task == null && p.waits) p.tag else "",
            accept = accept != null,
        )
    }

    /**
     * Each task's and each Assistant's space name on one board, by the tags its alerts carry (a task's id, an
     * Assistant's chat), for the space's [Notify] when one of them alerts.
     */
    fun spaces(o: WorkOverview): Map<String, String> {
        val names = o.spaces.associate { it.id to it.name }
        return o.tasks.mapNotNull { t -> names[t.space]?.let { t.id to it } }.toMap() +
            o.spaces.filter { it.chat.isNotEmpty() }.associate { it.chat to it.name }
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
}
