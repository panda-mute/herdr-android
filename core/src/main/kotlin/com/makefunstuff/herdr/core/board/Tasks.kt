package com.makefunstuff.herdr.core.board

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException

/** The board's colours for a task's status (docs/colours.md), as 0xRRGGBB. */
enum class BoardTone(val rgb: Long) {
    Amber(0xE2B64A), Red(0xEF7B70), Green(0x6CC993), Blue(0x7AA2F7), Teal(0x4DC4B8), Pink(0xE58FC0), Grey(0x7A847F),
}

/** Where a task stands, in the board's own words (`flow/shown/states.rs`). */
enum class BoardStatus(val label: String, val tone: BoardTone, val needsYou: Boolean = false) {
    Todo("to do", BoardTone.Grey),
    Queued("queued", BoardTone.Pink),
    Working("working", BoardTone.Amber),
    Reviewing("reviewing", BoardTone.Blue),
    Approval("ready to approve", BoardTone.Green, true),
    ReviewFailed("review failed", BoardTone.Red, true),
    PlanReady("plan ready", BoardTone.Red, true),
    PickOne("pick one", BoardTone.Red, true),
    Asks("asks you", BoardTone.Red, true),
    AsksOk("asks for your OK", BoardTone.Red, true),
    NeedsLogin("needs a login", BoardTone.Red, true),
    Stopped("stopped", BoardTone.Red, true),
    Error("went wrong", BoardTone.Red, true),
    Done("done", BoardTone.Teal),
    Archived("archived", BoardTone.Grey),
    ;

    /** Its badge: the one word for its state on every board ([WorkStatus.label]); [label] says it exactly. */
    val word: String get() = WorkTasks.status(this).word

    /** What [word] leaves out, for the line under the title ("Review failed"); null when the word says it all ("asks you" is "needs you"). */
    val detail: String? get() = label.takeIf { it != word && this != Approval && this != Asks && this != Archived }?.replaceFirstChar { it.uppercase() }
}

/** One row of `list --json`. */
data class BoardTask(
    /** `<repo>/<number>`, or `<machine>:<repo>/<number>` for another machine's task. */
    val id: String,
    /** The short key (`AND-12`), when the board's plain list gave it. */
    val key: String,
    val title: String,
    val state: String,
    val stage: String,
    val kind: String,
    val agent: String,
    val msg: String,
    val progress: Int?,
    val eta: Int?,
    val research: Boolean,
    val pr: String,
    val archived: Boolean,
    val pinned: Boolean,
    val reviewRound: Int,
    val reviewPassed: Boolean?,
    val approvalBlocked: Boolean,
) {
    val name: String get() = key.ifEmpty { id }
    val machine: String get() = if (':' in id) id.substringBefore(':') else ""
    val repo: String get() = id.substringAfter(':').substringBefore('/')
    val number: String get() = id.substringAfterLast('/')

    /** Its state's one word: [status]'s, or "merging" for a Shepherdr task being merged (v1 has no such state). */
    val word: String get() = if (state == "working" && stage == "merging") WorkStatus.Merging.word else status.word

    val status: BoardStatus get() = when {
        archived -> BoardStatus.Archived
        stage == "plan" && state == "blocked" -> BoardStatus.PlanReady
        stage == "pick" -> BoardStatus.PickOne
        state == "blocked" && kind == "permission" && stage == "approval" && !approvalBlocked ->
            if (reviewPassed == false) BoardStatus.ReviewFailed else BoardStatus.Approval
        state == "blocked" && stage != "approval" && kind == "auth" -> BoardStatus.NeedsLogin
        state == "blocked" && stage != "approval" && kind == "question" -> BoardStatus.Asks
        state == "blocked" && stage != "approval" && kind == "permission" -> BoardStatus.AsksOk
        state == "blocked" -> BoardStatus.Error
        state == "error" -> BoardStatus.Error
        stage == "stopped" || stage == "nosummary" -> BoardStatus.Stopped
        stage == "queued" || stage == "after" -> BoardStatus.Queued
        state == "working" && (stage == "reviewing" || stage == "reviewneeds") -> BoardStatus.Reviewing
        state == "working" -> BoardStatus.Working
        state == "done" -> BoardStatus.Done
        else -> BoardStatus.Todo
    }

    /** Waiting for you, or ready for your yes. */
    val needsYou: Boolean get() = !archived && (status.needsYou || state == "blocked" || state == "error")

    /** What the task's own pull request is, when [pr] holds one. */
    val prNumber: String get() = pr.substringAfterLast('/').takeIf { pr.contains("/pull/") }.orEmpty()
}

object BoardTasks {
    private val json = Json { ignoreUnknownKeys = true }

    /** The first JSON value of [open]'s kind in [out], wherever a login shell's start-up files printed around it. */
    fun element(out: String, open: Char): JsonElement? {
        val close = if (open == '[') ']' else '}'
        val lines = out.lines()
        // The JSON is pretty printed over many lines: find a line that opens it and read to the matching end.
        for (i in lines.indices) {
            if (lines[i].trimEnd() != open.toString() && !(open == '{' && lines[i].startsWith("{"))) continue
            val text = lines.drop(i).joinToString("\n")
            val end = text.lastIndexOf(close)
            if (end < 0) continue
            runCatching { json.parseToJsonElement(text.substring(0, end + 1)) }.getOrNull()?.let { return it }
        }
        return null
    }

    /** `list --json`. Tasks come as an array; anything else is the board's plain message. */
    fun list(stdout: String, keys: Map<String, String> = emptyMap(), archivedView: Boolean = false): List<BoardTask> {
        val arr = element(stdout, '[') as? JsonArray ?: throw IOException(plain(stdout).ifEmpty { "herdr-projects gave no task list" })
        return arr.mapNotNull { e -> (e as? JsonObject)?.let { task(it, keys[it.str("id")].orEmpty(), archivedView) } }
    }

    /** One task as `list --json` gives it (herdr-push sends the same object); null without an id. */
    fun task(o: JsonObject, key: String = "", archivedView: Boolean = false): BoardTask? {
        val id = o.str("id").ifEmpty { return null }
        val rev = o["review"] as? JsonObject
        return BoardTask(
            id = id,
            key = key,
            title = o.str("title").ifEmpty { id },
            state = o.str("state"),
            stage = o.str("stage"),
            kind = o.str("kind"),
            agent = o["app"].let { a -> (a as? JsonPrimitive)?.takeIf { it.isString }?.content ?: (a as? JsonObject)?.str("name").orEmpty() },
            msg = o.str("msg"),
            progress = o.num("progress")?.toInt(),
            eta = o.num("eta")?.toInt(),
            research = o.bool("research"),
            pr = o.str("pr"),
            archived = archivedView || o.bool("archivedAt") || o.num("archivedAt") != null,
            pinned = o.bool("pinned"),
            reviewRound = o.num("reviewRound")?.toInt() ?: 0,
            reviewPassed = rev?.let { r -> (r["pass"] as? JsonPrimitive)?.booleanOrNull ?: (r["meets"] as? JsonPrimitive)?.booleanOrNull },
            approvalBlocked = o.bool("mergeFailed") || o.bool("syncBlocked") || o.bool("prFailure") || o.str("prState") == "failing",
        )
    }

    /**
     * The board's plain `list` prints each task's short key first, in the order `list --json` gives
     * them. Matched by position, and only where the number agrees with the id's.
     */
    fun keys(plain: String, tasks: List<BoardTask>): Map<String, String> {
        val rows = plain.lines().filter { it.isNotBlank() }.map { it.trim().substringBefore(' ') }
        if (rows.size != tasks.size) return emptyMap()
        val out = HashMap<String, String>()
        for ((k, t) in rows.zip(tasks)) if (k.substringAfterLast('-') == t.number && '-' in k) out[t.id] = k
        return out
    }

    /** What the board said instead of an answer: its last non-empty line. */
    fun plain(stdout: String): String = stdout.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()

    fun details(stdout: String): TaskDetails {
        val o = element(stdout, '{') as? JsonObject
        if (o == null || o["task"] !is JsonObject) throw IOException(plain(stdout).ifEmpty { "herdr-projects gave no details" })
        val t = o["task"] as JsonObject
        val f = o["failure"] as? JsonObject
        return TaskDetails(
            key = t.str("id"),
            title = t.str("title"),
            stage = t.str("stage"),
            msg = t.str("msg"),
            description = t.str("description"),
            failure = f?.let {
                Failure(it.str("title"), it.str("detail"), it.str("next"), it.bool("agentCanFix"), it.bool("auth"), it.num("sentAt") != null)
            },
            reviews = (o["reviews"] as? JsonArray).orEmpty().mapNotNull { r -> (r as? JsonObject)?.let(::review) },
            agent = o.str("agent"),
            handovers = (o["handovers"] as? JsonArray).orEmpty().mapNotNull { h ->
                (h as? JsonObject)?.let { it.str("words").ifEmpty { "${it.str("to")} took over from ${it.str("from")}: ${it.str("why")}" } }
            },
            research = o.bool("research"),
            reportLink = (o["report"] as? JsonObject)?.str("link").orEmpty(),
            plan = when (val p = o["plan"]) {
                is JsonObject -> p.str("text")
                is JsonPrimitive -> if (p.isString) p.content else ""
                else -> ""
            },
            pr = o.str("pr"),
            branch = o.str("branch"),
            worktree = o.str("worktree"),
            time = (o["limits"] as? JsonObject)?.get("time")?.let { usage(it, false) },
            tokens = (o["limits"] as? JsonObject)?.get("tokens")?.let { usage(it, true) },
            queue = ((o["queue"] as? JsonObject)?.str("words")).orEmpty(),
            attempts = (o["attempts"] as? JsonArray).orEmpty().mapNotNull { a ->
                (a as? JsonObject)?.let { Attempt(it.str("letter"), it.str("agent"), it.str("standing"), it.str("stat"), it.str("report"), it.str("diff")) }
            },
            comparison = (o["comparison"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
            waitsFor = ((o["after"] as? JsonObject)?.get("waitsFor") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
        )
    }

    private fun review(r: JsonObject) = Review(
        round = r.num("round")?.toInt() ?: 0,
        by = r.str("by"),
        meets = (r["meets"] as? JsonPrimitive)?.booleanOrNull,
        pass = (r["pass"] as? JsonPrimitive)?.booleanOrNull,
        error = r.str("error"),
        text = r.str("text"),
        findings = (r["findings"] as? JsonArray).orEmpty().mapNotNull { f ->
            (f as? JsonObject)?.let { Finding(it.str("id"), it.str("severity"), it.str("subject"), it.str("where"), it.str("proof")) }
        },
        checks = (r["checks"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
        limitations = (r["limitations"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
    )

    private fun usage(e: JsonElement, tokens: Boolean): Usage? {
        val o = e as? JsonObject ?: return null
        val used = if (tokens) o.num("used")?.toLong()?.toString().orEmpty() else o.str("used")
        return Usage(o.str("limit"), used)
    }
}

internal fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
internal fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
internal fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull

data class Failure(val title: String, val detail: String, val next: String, val agentCanFix: Boolean, val auth: Boolean, val fixSent: Boolean)
data class Finding(val id: String, val severity: String, val subject: String, val where: String, val proof: String)
data class Review(
    val round: Int, val by: String, val meets: Boolean?, val pass: Boolean?, val error: String, val text: String,
    val findings: List<Finding>, val checks: List<String>, val limitations: List<String>,
)
data class Usage(val limit: String, val used: String)
data class Attempt(val letter: String, val agent: String, val standing: String, val stat: String, val report: String, val diff: String)

/** `details <id>`. */
data class TaskDetails(
    val key: String, val title: String, val stage: String, val msg: String, val description: String,
    val failure: Failure?, val reviews: List<Review>, val agent: String, val handovers: List<String>,
    val research: Boolean, val reportLink: String, val plan: String, val pr: String, val branch: String, val worktree: String,
    val time: Usage?, val tokens: Usage?, val queue: String, val attempts: List<Attempt>, val comparison: String, val waitsFor: List<String>,
)

/** A command the board can be given about a task. [destructive] ones ask first: they stop work, throw it away or merge what failed a check. */
enum class BoardAction(val verb: String, val label: String, val destructive: Boolean = false) {
    Delegate("delegate", "Start an agent"),
    Tell("tell", "Tell its agent…"),
    Stop("stop", "Stop its agent", true),
    Retry("retry", "Retry"),
    Fix("fix", "Send failure to agent"),
    Review("review", "Send review findings back"),
    Finish("finish", "Finish anyway", true),
    Approve("approve", "Approve and merge"),
    ApproveAnyway("approve", "Approve anyway…", true),
    ApprovePlan("approve", "Approve plan"),
    SendPlanBack("tell", "Send plan back…"),
    Pick("pick", "Pick this attempt"),
    Archive("archive", "Archive"),
    Restore("restore", "Restore"),
    Clear("clear", "Clear (remove)", true),
    Pin("pin", "Pin"),
}

/** Which actions fit a task as it stands, as the board's own menu offers them (`update/menu.rs`). */
object BoardActions {
    /** The one action a person most likely wants next, or null when the task just needs watching. */
    fun primary(t: BoardTask): BoardAction? {
        if (t.archived) return BoardAction.Restore
        return when (t.status) {
            BoardStatus.Todo -> BoardAction.Delegate
            BoardStatus.Approval -> BoardAction.Approve
            BoardStatus.ReviewFailed -> BoardAction.Review
            BoardStatus.PlanReady -> BoardAction.ApprovePlan
            BoardStatus.Asks, BoardStatus.AsksOk, BoardStatus.NeedsLogin -> BoardAction.Tell
            BoardStatus.Stopped -> BoardAction.Retry
            BoardStatus.Error -> forTask(t).first()
            else -> null
        }
    }

    fun forTask(t: BoardTask): List<BoardAction> {
        if (t.archived) return listOf(BoardAction.Restore, BoardAction.Clear)
        val out = mutableListOf<BoardAction>()
        when (t.status) {
            BoardStatus.Todo -> out += BoardAction.Delegate
            BoardStatus.Queued -> { out += BoardAction.Delegate; out += BoardAction.Stop }
            BoardStatus.Working, BoardStatus.Reviewing -> { out += BoardAction.Tell; out += BoardAction.Stop }
            BoardStatus.Approval -> { out += BoardAction.Approve; out += BoardAction.Tell }
            BoardStatus.ReviewFailed -> { out += BoardAction.Review; out += BoardAction.ApproveAnyway; out += BoardAction.Tell }
            BoardStatus.PlanReady -> { out += BoardAction.ApprovePlan; out += BoardAction.SendPlanBack }
            BoardStatus.PickOne -> {}
            BoardStatus.Asks, BoardStatus.AsksOk, BoardStatus.NeedsLogin -> { out += BoardAction.Tell; out += BoardAction.Stop }
            BoardStatus.Stopped -> { out += BoardAction.Retry; out += BoardAction.Finish; out += BoardAction.Tell }
            BoardStatus.Error -> {
                if (t.stage == "approval") { out += BoardAction.Fix; out += BoardAction.Retry; out += BoardAction.ApproveAnyway }
                else { out += BoardAction.Retry; out += BoardAction.Fix; out += BoardAction.Tell }
                out += BoardAction.Finish
            }
            BoardStatus.Done, BoardStatus.Archived -> {}
        }
        out += BoardAction.Archive
        if (t.status == BoardStatus.Todo || t.status == BoardStatus.Error || t.status == BoardStatus.Done) out += BoardAction.Clear
        return out
    }
}

/** Where `list` looks, and what it asks for. */
data class BoardFilter(val query: String = "", val archived: Boolean = false, val repo: String = "", val machine: String = "") {
    fun matches(t: BoardTask): Boolean =
        (repo.isEmpty() || t.repo == repo) && (machine.isEmpty() || t.machine == machine)
}
