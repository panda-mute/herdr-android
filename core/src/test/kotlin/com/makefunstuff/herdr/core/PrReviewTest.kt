package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.github.GhCommands
import com.makefunstuff.herdr.core.github.GhRunner
import com.makefunstuff.herdr.core.github.PrRef
import com.makefunstuff.herdr.core.github.ReviewClient
import com.makefunstuff.herdr.core.github.ReviewVerdict
import com.makefunstuff.herdr.core.github.Reviews
import com.makefunstuff.herdr.core.ssh.CommandResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrReviewTest {
    private val ref = PrRef("example", "herdr-android", 142)

    private val answer = """
        {"data":{"viewer":{"login":"sam"},"repository":{"pullRequest":{
          "number":142,"title":"Rate-limit the export endpoint","state":"OPEN","isDraft":false,"url":"https://github.com/example/herdr-android/pull/142",
          "headRefName":"work/9","baseRefName":"main","headRefOid":"abc123","body":"Adds a limit.\n","reviewDecision":"CHANGES_REQUESTED",
          "additions":86,"deletions":21,"changedFiles":4,"author":{"login":"herdr-bot"},
          "commits":{"nodes":[{"commit":{"statusCheckRollup":{"state":"FAILURE","contexts":{"nodes":[
            {"__typename":"CheckRun","name":"unit tests","conclusion":"SUCCESS","status":"COMPLETED","detailsUrl":"https://ci/1","checkSuite":{"workflowRun":{"workflow":{"name":"Check"}}}},
            {"__typename":"CheckRun","name":"build","conclusion":"FAILURE","status":"COMPLETED","detailsUrl":"https://ci/2","checkSuite":{"workflowRun":{"workflow":{"name":"Check"}}}},
            {"__typename":"CheckRun","name":"screens","conclusion":null,"status":"IN_PROGRESS","detailsUrl":null,"checkSuite":null},
            {"__typename":"StatusContext","context":"deploy/preview","state":"PENDING","targetUrl":"https://ci/3"},
            {"__typename":"CheckRun","name":"notes","conclusion":"SKIPPED","status":"COMPLETED","detailsUrl":null,"checkSuite":null}
          ]}}}}]},
          "reviewThreads":{"nodes":[
            {"id":"T2","isResolved":true,"isOutdated":false,"path":"app/Card.kt","line":12,"originalLine":12,"comments":{"nodes":[
              {"databaseId":20,"body":"Typo","createdAt":"2026-11-07T08:00:00Z","url":"u20","author":{"login":"ana"},"diffHunk":"@@ -10,2 +10,2 @@"}]}},
            {"id":"T1","isResolved":false,"isOutdated":true,"path":"core/Limit.kt","line":null,"originalLine":33,"comments":{"nodes":[
              {"databaseId":10,"body":"Why 10?","createdAt":"2026-11-07T09:00:00Z","url":"u10","author":{"login":"ana"},"diffHunk":"@@ -30,3 +30,4 @@\n+val limit = 10"},
              {"databaseId":11,"body":"The plan says so.","createdAt":"2026-11-07T09:05:00Z","url":"u11","author":null,"diffHunk":"x"}]}},
            {"id":"T3","isResolved":false,"isOutdated":false,"path":"empty.kt","line":1,"originalLine":1,"comments":{"nodes":[]}}
          ]},
          "comments":{"nodes":[{"databaseId":30,"body":"Looks close","createdAt":"2026-11-07T10:00:00Z","url":"u30","author":{"login":"ana"}}]},
          "reviews":{"nodes":[
            {"databaseId":40,"state":"COMMENTED","body":"","submittedAt":"2026-11-07T09:00:00Z","url":"u40","author":{"login":"ana"}},
            {"databaseId":41,"state":"CHANGES_REQUESTED","body":"","submittedAt":"2026-11-07T09:30:00Z","url":"u41","author":{"login":"ana"}},
            {"databaseId":42,"state":"COMMENTED","body":"One more thing","submittedAt":"2026-11-07T11:00:00Z","url":"u42","author":{"login":"lee"}}
          ]}
        }}}}
    """.trimIndent()

    @Test
    fun readsALinkToAPullRequest() {
        assertEquals(ref, PrRef.parse("https://github.com/example/herdr-android/pull/142"))
        assertEquals(PrRef("o", "r.js", 7), PrRef.parse("https://github.com/o/r.js/pull/7/files"))
        assertNull(PrRef.parse("https://gitlab.com/o/r/-/merge_requests/7"))
        assertNull(PrRef.parse(""))
    }

    @Test
    fun readsTheWholeReview() {
        val r = Reviews.parse(ref, answer)
        assertEquals("sam", r.viewer)
        assertEquals("herdr-bot", r.author)
        assertFalse(r.mine)
        assertTrue(r.open)
        assertEquals("open", r.pr.state)
        assertEquals("abc123", r.headOid)
        assertEquals("Adds a limit.", r.body)
        assertEquals("Changes requested", Reviews.decision(r.decision))
        // Failed first, then running, passed, skipped.
        assertEquals(listOf("build", "deploy/preview", "screens", "unit tests", "notes"), r.pr.checks.map { it.name })
        assertEquals(listOf("fail", "pending", "pending", "pass", "skip"), r.pr.checks.map { it.state })
        assertEquals("Check", r.pr.checks.first().workflow)
        // Open threads first; an outdated one keeps its original line; one with no comments is left out.
        assertEquals(listOf("T1", "T2"), r.threads.map { it.id })
        val t = r.threads.first()
        assertEquals(33, t.line)
        assertEquals("Limit.kt:33", t.place)
        assertTrue(t.outdated)
        assertEquals("ghost", t.comments[1].author)
        assertEquals("@@ -30,3 +30,4 @@\n+val limit = 10", t.hunk)
        assertEquals("1 open · 1 resolved", Reviews.threadsLine(r.threads))
        // Reviews that only carry thread comments drop out; a change request stays, with or without words.
        assertEquals(listOf("CHANGES_REQUESTED", null, "COMMENTED"), r.activity.map { it.state })
        assertEquals(listOf("ana", "ana", "lee"), r.activity.map { it.author })
    }

    @Test
    fun aDraftAndAMergedOne() {
        val draft = Reviews.parse(ref, answer.replace("\"isDraft\":false", "\"isDraft\":true"))
        assertEquals("draft", draft.pr.state)
        assertTrue(draft.open)
        val merged = Reviews.parse(ref, answer.replace("\"state\":\"OPEN\"", "\"state\":\"MERGED\""))
        assertEquals("merged", merged.pr.state)
        assertFalse(merged.open)
        val own = Reviews.parse(ref, answer.replace("\"login\":\"sam\"", "\"login\":\"Herdr-Bot\""))
        assertTrue(own.mine)
    }

    @Test
    fun saysWhyGhCouldNot() {
        fun why(r: CommandResult) = assertFailsWith<IOException> { Reviews.out(r) }.message
        assertEquals(Reviews.NO_GH, why(CommandResult(127, "", "sh: 1: gh: not found")))
        assertEquals(Reviews.NO_LOGIN, why(CommandResult(4, "", "To get started with GitHub CLI, please run:  gh auth login")))
        assertEquals(Reviews.TOO_LONG, why(CommandResult(null, "", "")))
        assertEquals(
            "Can not approve your own pull request",
            why(CommandResult(1, """{"message":"Unprocessable Entity","errors":["Can not approve your own pull request"]}""", "gh: Unprocessable Entity (HTTP 422)")),
        )
        assertEquals("Not Found", why(CommandResult(1, """{"message":"Not Found"}""", "gh: Not Found (HTTP 404)")))
        assertEquals("Bad credentials (HTTP 401)", why(CommandResult(1, "", "gh: Bad credentials (HTTP 401)")))
        assertEquals("Could not resolve", assertFailsWith<IOException> { Reviews.data("""{"errors":[{"message":"Could not resolve"}]}""") }.message)
    }

    @Test
    fun theLoginShellPassesTheBodyOnToGh() {
        // gh reads its request from standard input; the login shell must not swap it for /dev/null.
        fun shell(command: String): String {
            val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            p.outputStream.use { it.write("""{"body":"hi"}""".toByteArray()) }
            return p.inputStream.bufferedReader().readText().also { p.waitFor() }
        }
        assertEquals("""{"body":"hi"}""", shell(com.makefunstuff.herdr.core.ssh.HerdrCommands.login("cat -", input = true)))
        assertEquals("", shell(com.makefunstuff.herdr.core.ssh.HerdrCommands.login("cat -")))
        assertTrue(GhCommands.graphql().contains("-lc \"\$1\"' sh"), GhCommands.graphql())
    }

    @Test
    fun postsOnlyWhatThePersonWrote() = runTest {
        val sent = mutableListOf<Pair<String, String>>()
        val client = ReviewClient(GhRunner { command, stdin, _ -> sent += command to stdin; CommandResult(0, """{"data":{}}""", "") })
        client.reply(ref, 10, "  Because the plan says so  ")
        client.comment(ref, "Thanks; it's 'fine' \$now")
        client.review(ref, ReviewVerdict.Approve, "", "abc123")
        client.resolve("T1", true)
        assertEquals(GhCommands.post("repos/example/herdr-android/pulls/142/comments/10/replies"), sent[0].first)
        assertEquals("""{"body":"Because the plan says so"}""", sent[0].second)
        assertEquals(GhCommands.post("repos/example/herdr-android/issues/142/comments"), sent[1].first)
        // The words travel on standard input, never in the command.
        assertFalse(sent[1].first.contains("fine"))
        assertEquals("Thanks; it's 'fine' \$now", Json.parseToJsonElement(sent[1].second).jsonObject["body"]!!.jsonPrimitive.content)
        val review = Json.parseToJsonElement(sent[2].second).jsonObject
        assertEquals("APPROVE", review["event"]!!.jsonPrimitive.content)
        assertEquals("abc123", review["commit_id"]!!.jsonPrimitive.content)
        assertEquals(GhCommands.graphql(), sent[3].first)
        assertTrue(sent[3].second.contains("resolveReviewThread"))
        // gh runs in the login shell, with no prompts.
        assertTrue(sent[0].first.startsWith("sh -c "))
        assertTrue(sent[0].first.contains("GH_PROMPT_DISABLED=1"))
        assertTrue(sent[0].first.contains("--input -"))
        // Nothing to send, or a change request with no words: refused before gh.
        assertFailsWith<IOException> { client.comment(ref, "  ") }
        assertEquals(Reviews.SAY_WHAT, assertFailsWith<IOException> { client.review(ref, ReviewVerdict.RequestChanges, " ", "abc") }.message)
        assertEquals(4, sent.size)
    }
}
