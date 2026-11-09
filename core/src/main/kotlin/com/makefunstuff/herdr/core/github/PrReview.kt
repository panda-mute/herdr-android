package com.makefunstuff.herdr.core.github

import com.makefunstuff.herdr.core.shepherdr.V2PrCheck
import com.makefunstuff.herdr.core.shepherdr.V2PullRequest
import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.ssh.HerdrCommands
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import com.makefunstuff.herdr.core.ssh.MachineConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.time.Instant

/** A pull request on GitHub, as its link names it: `https://github.com/<owner>/<name>/pull/<number>`. */
data class PrRef(val owner: String, val name: String, val number: Int) {
    val repo: String get() = "$owner/$name"

    companion object {
        private val LINK = Regex("""github\.com/([^/\s]+)/([^/\s]+)/pull/(\d+)""")

        fun parse(url: String): PrRef? = LINK.find(url)?.let { m -> PrRef(m.groupValues[1], m.groupValues[2], m.groupValues[3].toInt()) }
    }
}

/** One comment, in a review thread or on the conversation; [at] in seconds since the epoch. */
data class PrComment(val id: Long, val author: String, val body: String, val at: Long, val url: String)

/** A review thread: where it is ([path], [line]), the code it is on ([hunk]) and its [comments], the first one opening it. */
data class PrThread(
    val id: String,
    val path: String,
    val line: Int?,
    val resolved: Boolean,
    val outdated: Boolean,
    val hunk: String,
    val comments: List<PrComment>,
) {
    /** "Card.kt:33", the file's name and its line. */
    val place: String get() = path.substringAfterLast('/') + (line?.let { ":$it" } ?: "")
}

/** A review with words or a verdict, or a comment on the conversation ([state] null); oldest first in [PrReview.activity]. */
data class PrNote(val author: String, val body: String, val at: Long, val url: String, val state: String? = null)

/**
 * A pull request as its reviewer reads it (the desktop's PR panel): [pr] for its head and checks, its
 * description, the review [decision], every review thread, and the reviews and comments.
 */
data class PrReview(
    val ref: PrRef,
    /** Who gh is signed in as on the machine: the one who reviews. */
    val viewer: String,
    val pr: V2PullRequest,
    val author: String,
    val body: String,
    val headOid: String,
    /** `APPROVED`, `CHANGES_REQUESTED`, `REVIEW_REQUIRED`, or null where the repository asks for no review. */
    val decision: String?,
    /** Open first, then by file and line. */
    val threads: List<PrThread>,
    val activity: List<PrNote>,
) {
    /** The viewer's own pull request: GitHub takes no approval or change request from its author. */
    val mine: Boolean get() = viewer.isNotEmpty() && viewer.equals(author, ignoreCase = true)

    /** Still open to review: not merged, not closed. */
    val open: Boolean get() = pr.state == "open" || pr.state == "draft"
}

/** A review's verdict, as GitHub's API names it ([api]), what its button says and what the phone says once it's in. */
enum class ReviewVerdict(val api: String, val label: String, val done: String) {
    Approve("APPROVE", "Approve", "Approved"),
    RequestChanges("REQUEST_CHANGES", "Request changes", "Changes requested"),
}

/**
 * gh on a machine, as the desktop's PR panel runs it: no prompts, no update notice, no pager or colour,
 * in the login shell (where gh usually is), and every request's body on standard input (`--input -`), so
 * nothing the person wrote is ever part of the command.
 */
object GhCommands {
    private const val ENV = "GH_PROMPT_DISABLED=1 GH_NO_UPDATE_NOTIFIER=1 GH_PAGER=cat NO_COLOR=1"

    /** `gh api <args> --input -`. */
    fun api(vararg args: String): String = HerdrCommands.login("env $ENV gh api " + args.joinToString(" ") { quote(it) } + " --input -", input = true)

    /** A GraphQL query or mutation; its body is `{"query": …, "variables": …}`. */
    fun graphql(): String = api("graphql")

    /** A REST POST to [path] (`repos/<owner>/<name>/…`). */
    fun post(path: String): String = api("-X", "POST", path)
}

/** Runs one of [GhCommands] with [stdin]; the app's is its SSH connection, tests give their own. */
fun interface GhRunner {
    suspend fun run(command: String, stdin: String, timeoutSeconds: Long): CommandResult
}

/**
 * Reviewing a pull request from the phone, with gh on a machine and the person's own login there: [load]
 * reads it whole, then [reply] to a thread, [comment] on the conversation, [review] with a verdict, and
 * [resolve] a thread. Nothing reaches GitHub but what the person wrote and sent.
 */
class ReviewClient(private val runner: GhRunner) {
    /** The app's client: every call on the machine's current SSH connection. */
    constructor(connect: suspend () -> MachineConnection) : this(
        GhRunner { command, stdin, seconds -> connect().runWithInput(command, stdin.byteInputStream(), seconds) },
    )

    /** [ref] whole, in one GraphQL call (as the desktop's panel reads it). */
    suspend fun load(ref: PrRef): PrReview {
        val body = buildJsonObject {
            put("query", QUERY)
            putJsonObject("variables") {
                put("o", ref.owner)
                put("n", ref.name)
                put("num", ref.number)
            }
        }
        return Reviews.parse(ref, call(GhCommands.graphql(), body.toString()))
    }

    /** [text] as a reply in the thread [comment] opened (the first comment's id). */
    suspend fun reply(ref: PrRef, comment: Long, text: String) {
        call(GhCommands.post("repos/${ref.repo}/pulls/${ref.number}/comments/$comment/replies"), words(text))
    }

    /** [text] as a comment on the pull request's conversation. */
    suspend fun comment(ref: PrRef, text: String) {
        call(GhCommands.post("repos/${ref.repo}/issues/${ref.number}/comments"), words(text))
    }

    /** A review with [verdict] and [text] (which a change request needs), on the commit the person read ([commit]). */
    suspend fun review(ref: PrRef, verdict: ReviewVerdict, text: String, commit: String) {
        if (verdict == ReviewVerdict.RequestChanges && text.isBlank()) throw IOException(Reviews.SAY_WHAT)
        val body = buildJsonObject {
            if (commit.isNotEmpty()) put("commit_id", commit)
            put("event", verdict.api)
            put("body", text.trim())
        }
        call(GhCommands.post("repos/${ref.repo}/pulls/${ref.number}/reviews"), body.toString())
    }

    /** Marks thread [id] resolved, or open again. */
    suspend fun resolve(id: String, resolved: Boolean) {
        val mutation = if (resolved) "resolveReviewThread" else "unresolveReviewThread"
        val body = buildJsonObject {
            put("query", "mutation(\$id:ID!){$mutation(input:{threadId:\$id}){thread{isResolved}}}")
            putJsonObject("variables") { put("id", id) }
        }
        Reviews.data(call(GhCommands.graphql(), body.toString()))
    }

    private fun words(text: String): String {
        if (text.isBlank()) throw IOException(Reviews.WRITE_FIRST)
        return buildJsonObject { put("body", text.trim()) }.toString()
    }

    private suspend fun call(command: String, stdin: String): String = Reviews.out(runner.run(command, stdin, TIMEOUT))

    companion object {
        const val TIMEOUT = 40L

        /**
         * The desktop panel's query (`mods/px-pr`), less reactions: the PR, its checks, threads, reviews and comments
         * (the latest hundred of each, on a busy one).
         */
        val QUERY = """
            query(${'$'}o:String!,${'$'}n:String!,${'$'}num:Int!){viewer{login} repository(owner:${'$'}o,name:${'$'}n){pullRequest(number:${'$'}num){
            number title state isDraft url headRefName baseRefName headRefOid body reviewDecision additions deletions changedFiles author{login}
            commits(last:1){nodes{commit{statusCheckRollup{state contexts(first:100){nodes{__typename
            ... on CheckRun{name conclusion status detailsUrl checkSuite{workflowRun{workflow{name}}}} ... on StatusContext{context state targetUrl}}}}}}}
            reviewThreads(first:100){nodes{id isResolved isOutdated path line originalLine comments(first:60){nodes{databaseId body createdAt url author{login} diffHunk}}}}
            comments(last:100){nodes{databaseId body createdAt url author{login}}}
            reviews(last:100){nodes{databaseId state body submittedAt url author{login}}}}}}
        """.trimIndent().replace("\n", " ")
    }
}

/** Reading gh's answers, and the review's words. */
object Reviews {
    const val WRITE_FIRST = "Write something first."
    const val SAY_WHAT = "Say what needs changing first."
    const val NO_GH = "gh isn't installed on this machine, so the phone can't read the review."
    const val NO_LOGIN = "gh isn't signed in to GitHub on this machine (gh auth login)."
    const val TOO_LONG = "GitHub took too long to answer."
    const val OWN_PR = "It's your own pull request: GitHub takes comments on it, not an approval."
    const val OPEN_THREADS = "Open threads"
    const val RESOLVED = "Resolved"
    const val CONVERSATION = "Conversation"
    const val NO_THREADS = "No review threads."
    const val ALL_RESOLVED = "Every thread is resolved."
    const val COMMENT = "Comment"
    const val REPLY = "Reply"
    const val RESOLVE = "Resolve"
    const val REOPEN = "Reopen"

    private val json = Json { ignoreUnknownKeys = true }

    /** The review decision in words, or null where there is none. */
    fun decision(d: String?): String? = when (d) {
        "APPROVED" -> "Approved"
        "CHANGES_REQUESTED" -> "Changes requested"
        "REVIEW_REQUIRED" -> "Review required"
        else -> null
    }

    /** What a review did, after its author's name ("approved"), or null for a comment. */
    fun verdict(state: String?): String? = when (state) {
        null -> null
        "APPROVED" -> "approved"
        "CHANGES_REQUESTED" -> "requested changes"
        "DISMISSED" -> "dismissed"
        else -> "reviewed"
    }

    /** "2 open · 1 resolved". */
    fun threadsLine(threads: List<PrThread>): String {
        val open = threads.count { !it.resolved }
        val done = threads.size - open
        return listOfNotNull(open.takeIf { it > 0 }?.let { "$it open" }, done.takeIf { it > 0 }?.let { "$it resolved" }).joinToString(" · ")
    }

    /** What gh printed, or why it couldn't: not installed, not signed in, GitHub's own message. */
    fun out(r: CommandResult): String {
        if (r.exitStatus == 0) return r.stdout
        if (r.exitStatus == null && r.stdout.isEmpty()) throw IOException(TOO_LONG)
        val err = r.stderr.trim()
        if (r.exitStatus == 127 || err.contains("gh: not found") || err.contains("gh: command not found")) throw IOException(NO_GH)
        if (err.contains("gh auth login") || err.contains("not logged in", ignoreCase = true)) throw IOException(NO_LOGIN)
        throw IOException(githubSays(r.stdout) ?: err.lines().lastOrNull { it.isNotBlank() }?.removePrefix("gh: ") ?: "gh failed")
    }

    /** GitHub's own reason in a refused call's answer: its errors' words first, then its message. */
    private fun githubSays(stdout: String): String? {
        val o = runCatching { json.parseToJsonElement(stdout).jsonObject }.getOrNull() ?: return null
        val errors = (o["errors"] as? JsonArray).orEmpty().mapNotNull { e ->
            (e as? JsonPrimitive)?.contentOrNull ?: (e as? JsonObject)?.str("message")
        }
        return errors.firstOrNull() ?: o.str("message")
    }

    /** A GraphQL answer's data, or its first error. */
    fun data(text: String): JsonObject {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { throw IOException("GitHub's answer wasn't readable") }
        githubSays(text)?.takeIf { o["errors"] != null }?.let { throw IOException(it) }
        return o["data"] as? JsonObject ?: throw IOException("GitHub's answer had no data")
    }

    /** The query's answer as a [PrReview]. */
    fun parse(ref: PrRef, text: String): PrReview {
        val data = data(text)
        val p = data.obj("repository")?.obj("pullRequest") ?: throw IOException("No pull request #${ref.number} in ${ref.repo}")
        val rollup = p.obj("commits")?.list("nodes")?.firstOrNull()?.jsonObject?.obj("commit")?.obj("statusCheckRollup")
        val checks = rollup?.obj("contexts")?.list("nodes").orEmpty().mapNotNull { (it as? JsonObject)?.let(::prCheck) }
            .sortedWith(compareBy({ ORDER.indexOf(it.state) }, { it.name.lowercase() }))
        val draft = p.bool("isDraft")
        val pr = V2PullRequest(
            number = p.int("number") ?: ref.number,
            title = p.str("title").orEmpty(),
            state = when (p.str("state")) {
                "MERGED" -> "merged"
                "CLOSED" -> "closed"
                else -> if (draft) "draft" else "open"
            },
            url = p.str("url").orEmpty(),
            head = p.str("headRefName"),
            base = p.str("baseRefName"),
            add = p.int("additions") ?: 0,
            del = p.int("deletions") ?: 0,
            files = p.int("changedFiles"),
            checks = checks,
        )
        val threads = p.obj("reviewThreads")?.list("nodes").orEmpty().mapNotNull { n ->
            val t = n as? JsonObject ?: return@mapNotNull null
            val nodes = t.obj("comments")?.list("nodes").orEmpty().mapNotNull { it as? JsonObject }
            val comments = nodes.map(::comment)
            if (comments.isEmpty()) return@mapNotNull null
            PrThread(
                id = t.str("id").orEmpty(),
                path = t.str("path").orEmpty(),
                line = t.int("line") ?: t.int("originalLine"),
                resolved = t.bool("isResolved"),
                outdated = t.bool("isOutdated"),
                hunk = nodes.first().str("diffHunk").orEmpty(),
                comments = comments,
            )
        }.sortedWith(compareBy({ it.resolved }, { it.path }, { it.line ?: 0 }))
        val reviews = p.obj("reviews")?.list("nodes").orEmpty().mapNotNull { n ->
            val r = n as? JsonObject ?: return@mapNotNull null
            val state = r.str("state") ?: "COMMENTED"
            val body = r.str("body").orEmpty().trim()
            // A review that only holds thread comments says nothing of its own; an approval or a change request does.
            if (body.isEmpty() && state != "APPROVED" && state != "CHANGES_REQUESTED") return@mapNotNull null
            PrNote(login(r), body, time(r.str("submittedAt")), r.str("url").orEmpty(), state)
        }
        val comments = p.obj("comments")?.list("nodes").orEmpty().mapNotNull { n ->
            (n as? JsonObject)?.let { c -> comment(c).let { PrNote(it.author, it.body, it.at, it.url) } }
        }
        return PrReview(
            ref = ref,
            viewer = data.obj("viewer")?.str("login").orEmpty(),
            pr = pr,
            author = login(p),
            body = p.str("body").orEmpty().trim(),
            headOid = p.str("headRefOid").orEmpty(),
            decision = p.str("reviewDecision"),
            threads = threads,
            activity = (reviews + comments).sortedBy { it.at },
        )
    }

    private val ORDER = listOf("fail", "pending", "pass", "skip")
    private val FAILED = setOf("FAILURE", "ERROR", "TIMED_OUT", "CANCELLED", "ACTION_REQUIRED", "STARTUP_FAILURE")

    /** A check run or a status, as Shepherdr sorts them: `fail`, `pending`, `pass` or `skip`. */
    private fun prCheck(c: JsonObject): V2PrCheck {
        val run = c.str("__typename") == "CheckRun"
        val v = (c.str("conclusion") ?: c.str("state")).orEmpty().uppercase()
        val state = when {
            v in FAILED -> "fail"
            run && c.str("status")?.uppercase() != "COMPLETED" -> "pending"
            !run && v in setOf("PENDING", "EXPECTED") -> "pending"
            v == "SKIPPED" || v == "NEUTRAL" -> "skip"
            else -> "pass"
        }
        return V2PrCheck(
            name = (c.str("name") ?: c.str("context")).orEmpty(),
            state = state,
            url = c.str("detailsUrl") ?: c.str("targetUrl"),
            workflow = c.obj("checkSuite")?.obj("workflowRun")?.obj("workflow")?.str("name"),
        )
    }

    private fun comment(c: JsonObject) = PrComment(
        id = (c["databaseId"] as? JsonPrimitive)?.longOrNull ?: 0,
        author = login(c),
        body = c.str("body").orEmpty().trim(),
        at = time(c.str("createdAt")),
        url = c.str("url").orEmpty(),
    )

    private fun login(o: JsonObject) = o.obj("author")?.str("login") ?: "ghost"

    private fun time(iso: String?): Long = iso?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() } ?: 0

    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.list(k: String): List<JsonElement>? = (this[k] as? JsonArray)
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.contentOrNull == "true"
}
