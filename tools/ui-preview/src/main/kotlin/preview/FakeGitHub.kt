// GitHub as gh answers it on a fake machine: the app's real ReviewClient reads a pull request's review over
// the fake SSH connection and posts replies, comments and reviews, which show on its next read.
package preview

import com.makefunstuff.herdr.core.ssh.CommandResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/** A comment, [ago] minutes before the demo's clock ([Demo.NOW]). */
class GhComment(val id: Long, val author: String, val body: String, val ago: Int, val hunk: String = "")

class GhThread(val id: String, val path: String, val line: Int, var resolved: Boolean = false, val outdated: Boolean = false, val comments: MutableList<GhComment>)

class GhReviewNote(val author: String, val state: String, val body: String, val ago: Int)

/** One pull request on the fake GitHub. */
class GhPr(
    val repo: String,
    val number: Int,
    val title: String,
    val author: String,
    val body: String,
    val head: String,
    var state: String = "OPEN",
    var decision: String? = "REVIEW_REQUIRED",
    val checks: List<Triple<String, String, String>> = emptyList(),
    val threads: MutableList<GhThread> = mutableListOf(),
    val comments: MutableList<GhComment> = mutableListOf(),
    val reviews: MutableList<GhReviewNote> = mutableListOf(),
)

/** GitHub with [prs], gh signed in there as [viewer]; [refuseReview] makes GitHub refuse a review with that reason. */
class FakeGitHub(val viewer: String, prs: List<GhPr>, var refuseReview: String? = null) {
    private val prs = prs.associateBy { "${it.repo}#${it.number}" }
    private val json = Json { ignoreUnknownKeys = true }
    private val start = Demo.NOW / 1000
    private var nextId = 9000L

    /** The answer to a gh command (the login shell already unwrapped), or null when it isn't gh's. */
    fun run(command: String, stdin: String): CommandResult? {
        if (!command.contains(" gh api ")) return null
        val args = Shell.words(command.substringAfter(" gh api "))
        if (args.first() == "graphql") return graphql(json.parseToJsonElement(stdin).jsonObject)
        val path = args[args.indexOf("POST") + 1]
        val body = json.parseToJsonElement(stdin).jsonObject["body"]?.jsonPrimitive?.content.orEmpty()
        val m = Regex("""repos/([^/]+/[^/]+)/(pulls|issues)/(\d+)/(.*)""").find(path) ?: return CommandResult(1, """{"message":"Not Found"}""", "gh: Not Found (HTTP 404)")
        val pr = prs["${m.groupValues[1]}#${m.groupValues[3]}"] ?: return CommandResult(1, """{"message":"Not Found"}""", "gh: Not Found (HTTP 404)")
        val rest = m.groupValues[4]
        when {
            rest.endsWith("/replies") -> {
                val to = rest.removePrefix("comments/").removeSuffix("/replies").toLong()
                pr.threads.first { t -> t.comments.any { it.id == to } }.comments += GhComment(nextId++, viewer, body, 0)
            }
            rest == "comments" -> pr.comments += GhComment(nextId++, viewer, body, 0)
            rest == "reviews" -> {
                refuseReview?.let { why -> return CommandResult(1, """{"message":"Unprocessable Entity","errors":[${JsonPrimitive(why)}]}""", "gh: Unprocessable Entity (HTTP 422)") }
                val event = json.parseToJsonElement(stdin).jsonObject["event"]!!.jsonPrimitive.content
                val state = if (event == "APPROVE") "APPROVED" else if (event == "REQUEST_CHANGES") "CHANGES_REQUESTED" else "COMMENTED"
                pr.reviews += GhReviewNote(viewer, state, body, 0)
                if (state != "COMMENTED") pr.decision = state
            }
        }
        return CommandResult(0, "{}", "")
    }

    private fun graphql(req: JsonObject): CommandResult {
        val q = req["query"]!!.jsonPrimitive.content
        val v = req["variables"]!!.jsonObject
        if ("ReviewThread" in q) {
            val id = v["id"]!!.jsonPrimitive.content
            prs.values.flatMap { it.threads }.firstOrNull { it.id == id }?.resolved = q.contains("{resolveReviewThread")
            return CommandResult(0, """{"data":{}}""", "")
        }
        val pr = prs["${v["o"]!!.jsonPrimitive.content}/${v["n"]!!.jsonPrimitive.content}#${v["num"]!!.jsonPrimitive.int}"]
            ?: return CommandResult(1, """{"errors":[{"message":"Could not resolve to a PullRequest"}]}""", "gh: Could not resolve to a PullRequest")
        return CommandResult(0, buildJsonObject { put("data", data(pr)) }.toString(), "")
    }

    private fun at(ago: Int) = JsonPrimitive(Instant.ofEpochSecond(start - ago * 60L).toString())
    private fun who(login: String) = obj("login" to JsonPrimitive(login))
    private fun nodes(list: List<JsonElement>) = obj("nodes" to JsonArray(list))
    private fun obj(vararg kv: Pair<String, JsonElement>) = JsonObject(kv.toMap())
    private fun s(v: String?) = v?.let { JsonPrimitive(it) } ?: JsonNull

    private fun comment(c: GhComment, url: String) = obj(
        "databaseId" to JsonPrimitive(c.id), "body" to s(c.body), "createdAt" to at(c.ago), "url" to s("$url#discussion_r${c.id}"),
        "author" to who(c.author), "diffHunk" to s(c.hunk),
    )

    private fun data(pr: GhPr): JsonObject {
        val url = "https://github.com/${pr.repo}/pull/${pr.number}"
        val checks = pr.checks.map { (name, conclusion, workflow) ->
            obj(
                "__typename" to s("CheckRun"), "name" to s(name), "conclusion" to s(conclusion.takeIf { it != "RUNNING" }),
                "status" to s(if (conclusion == "RUNNING") "IN_PROGRESS" else "COMPLETED"), "detailsUrl" to s("$url/checks"),
                "checkSuite" to obj("workflowRun" to obj("workflow" to obj("name" to s(workflow)))),
            )
        }
        val p = obj(
            "number" to JsonPrimitive(pr.number), "title" to s(pr.title), "state" to s(pr.state), "isDraft" to JsonPrimitive(false), "url" to s(url),
            "headRefName" to s(pr.head), "baseRefName" to s("main"), "headRefOid" to s("9f2c1ab"), "body" to s(pr.body),
            "reviewDecision" to s(pr.decision), "additions" to JsonPrimitive(86), "deletions" to JsonPrimitive(21), "changedFiles" to JsonPrimitive(4),
            "author" to who(pr.author),
            "commits" to nodes(listOf(obj("commit" to obj("statusCheckRollup" to obj("state" to s("SUCCESS"), "contexts" to nodes(checks)))))),
            "reviewThreads" to nodes(pr.threads.map { t ->
                obj(
                    "id" to s(t.id), "isResolved" to JsonPrimitive(t.resolved), "isOutdated" to JsonPrimitive(t.outdated), "path" to s(t.path),
                    "line" to JsonPrimitive(t.line), "originalLine" to JsonPrimitive(t.line), "comments" to nodes(t.comments.map { comment(it, url) }),
                )
            }),
            "comments" to nodes(pr.comments.map { comment(it, url) }),
            "reviews" to nodes(pr.reviews.mapIndexed { i, r ->
                obj("databaseId" to JsonPrimitive(800 + i), "state" to s(r.state), "body" to s(r.body), "submittedAt" to at(r.ago), "url" to s("$url#review-$i"), "author" to who(r.author))
            }),
        )
        return obj("viewer" to who(viewer), "repository" to obj("pullRequest" to p))
    }
}

/** The demo's pull requests: api-server #142 (AND-20's), reviewed once, with two open threads and one resolved. */
object DemoGitHub {
    fun board(viewer: String = "sam"): FakeGitHub = FakeGitHub(
        viewer,
        listOf(
            GhPr(
                repo = "demo/api-server", number = 142, title = "Rate-limit the export endpoint per tenant", author = "herdr-bot",
                head = "work/9-last-answered", decision = "CHANGES_REQUESTED",
                body = "Limits `/export` to **10 requests a minute per tenant**. A request over the limit gets a 429 with `Retry-After`.\n\n" +
                    "- The limit is a setting (`export.rate_per_minute`)\n- Counted per tenant, in memory, per instance",
                checks = listOf(
                    Triple("go test ./...", "SUCCESS", "Check"), Triple("lint", "SUCCESS", "Check"),
                    Triple("integration", "SUCCESS", "Check"), Triple("release notes", "SKIPPED", "Release"),
                ),
                threads = mutableListOf(
                    GhThread(
                        "PRRT_1", "internal/export/limit.go", 41,
                        comments = mutableListOf(
                            GhComment(
                                501, "ana", "Counting in memory means each instance has its own limit. With three instances a tenant gets 30 a minute. Is that what we want?", 150,
                                hunk = "@@ -36,6 +36,12 @@ func (l *Limiter) Allow(tenant string) bool {\n \tl.mu.Lock()\n \tdefer l.mu.Unlock()\n+\tw := l.window[tenant]\n+\tif w.count >= l.perMinute {\n+\t\treturn false\n+\t}",
                            ),
                            GhComment(502, "herdr-bot", "It is, for now: the brief says per instance. I'll note it in the setting's help.", 120, hunk = "x"),
                        ),
                    ),
                    GhThread(
                        "PRRT_2", "internal/export/handler.go", 88,
                        comments = mutableListOf(
                            GhComment(
                                503, "ana", "`Retry-After` should be whole seconds, rounded up.", 140,
                                hunk = "@@ -80,4 +80,9 @@ func (h *Handler) Export(w http.ResponseWriter, r *http.Request) {\n+\tif !h.limit.Allow(tenant) {\n+\t\tw.Header().Set(\"Retry-After\", wait.String())\n+\t\thttp.Error(w, \"too many exports\", 429)",
                            ),
                        ),
                    ),
                    GhThread(
                        "PRRT_3", "internal/export/limit_test.go", 12, resolved = true,
                        comments = mutableListOf(GhComment(504, "ana", "Use the fake clock here, not time.Sleep.", 160, hunk = "@@ -1,3 +1,14 @@\n+func TestLimit(t *testing.T) {")),
                    ),
                ),
                reviews = mutableListOf(GhReviewNote("ana", "CHANGES_REQUESTED", "Close. Two things in the handler and the limiter; the rest reads well.", 135)),
                comments = mutableListOf(GhComment(505, "herdr-bot", "Fixed the test's clock in 3e1d0f2.", 90)),
            ),
            // simple-herdr-projects' APP-2: nobody has reviewed it yet.
            GhPr(
                repo = "demo/app", number = 57, title = "Ship the smaller board rows", author = "herdr-bot", head = "task/app-2",
                body = "Each board row is two lines at most, and the status word shows once.",
                checks = listOf(Triple("build", "SUCCESS", "CI"), Triple("screens", "RUNNING", "CI")),
            ),
        ),
    )
}
