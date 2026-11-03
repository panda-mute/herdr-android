// The made-up world every scene starts from: the README screenshots' demo host
// (docs/screenshots/demo-host) as fixed data. devbox runs three agents and the work board;
// gpu-box and ci-runner are reached through it; mac-mini refuses the app's key.
package preview

import com.makefunstuff.herdr.brief.BriefState
import com.makefunstuff.herdr.core.brief.Baseline
import com.makefunstuff.herdr.core.brief.BriefSchedule
import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.Alerts
import com.makefunstuff.herdr.core.push.QuietHours
import com.makefunstuff.herdr.push.MachinePush
import com.makefunstuff.herdr.push.PushState
import com.makefunstuff.herdr.core.ssh.ConnectError
import com.makefunstuff.herdr.core.ssh.Machine
import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime
import java.time.ZoneOffset

object Demo {
    const val PUBLIC_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHd2Zm9sZGVkLXByZXZpZXcta2V5LWZvci1oZXJkcg herdr-android@Pixel-7"

    const val DEVBOX = "devbox"
    const val GPU = "gpu-box"
    const val CI = "ci-runner"
    const val MINI = "mac-mini"

    /** Panes the scenes open. */
    const val APP_AGENT = "w1:p1"
    const val APP_SHELL = "w1:p2"
    const val API_AGENT = "w2:p1"
    const val SITE_AGENT = "w3:p1"

    /** The brief's "now": Saturday 17 October 2026, 07:42 (UTC), the morning after. */
    val NOW: Long = LocalDateTime.of(2026, 10, 17, 7, 42).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** 23:10 the night before: when the app was last on screen. */
    val SEEN: Long = LocalDateTime.of(2026, 10, 16, 23, 10).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** A time of that night or morning, on the demo machines' clock (seconds): 12:00 and later is the 16th. */
    private fun at(hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 10, if (hour >= 12) 16 else 17, hour, minute).toEpochSecond(ZoneOffset.UTC)

    /**
     * The brief's part: you last looked at 23:10, a brief comes at 07:30 and 17:30 on weekdays (read
     * aloud in the car), and the app had seen ci-runner's agents then (it keeps no journal).
     */
    fun brief() = BriefState(
        schedule = BriefSchedule(on = true, times = listOf(7 * 60 + 30, 17 * 60 + 30), days = (1..5).toSet()),
        seenAt = SEEN,
        baselines = mapOf(CI to Baseline(tasks = null, agents = emptyMap(), agentsAt = SEEN)),
    )

    /** herdr-push's journal on a machine, as `herdr-push journal` answers: kept since a week ago, up to now. */
    private fun journal(vararg entries: JsonObject) = obj(
        "ok" to true, "version" to "4", "now" to NOW / 1000, "from" to NOW / 1000 - 7 * 24 * 3600, "upto" to NOW / 1000 - 3,
        "running" to true, "entries" to entries.toList(),
    )

    private fun taskEntry(hour: Int, minute: Int, key: String, was: String, vararg task: Pair<String, Any?>) =
        obj("at" to at(hour, minute), "type" to "task", "key" to key, "task" to obj(*task), "was" to was)

    private fun agentEntry(hour: Int, minute: Int, pane: String, kind: String, status: String, title: String, where: String) = obj(
        "at" to at(hour, minute), "type" to "agent", "where" to where, "workspace" to where.substringBefore(" › "),
        "agent" to obj(
            "pane_id" to pane, "workspace_id" to pane.substringBefore(':'), "tab_id" to "${pane.substringBefore(':')}:t1",
            "agent" to kind, "name" to kind, "display_agent" to kind, "title" to title, "agent_status" to status, "state_change_seq" to 2,
        ),
    )

    /** What devbox's tasks did that night: two merged, a research report, a review that failed, two that wait for you, one that started. */
    fun devboxJournal() = journal(
        taskEntry(0, 52, "AND-8", "needs", "id" to "herdr-android/8", "title" to "Swipe triage: seen, pin, snooze, mute", "state" to "done", "stage" to "done",
            "pr" to "https://github.com/demo/herdr-android/pull/8", "msg" to "Merged", "archivedAt" to null),
        taskEntry(1, 20, "AND-2", "needs", "id" to "herdr-android/2", "title" to "Replace agent terminals with native chat views", "state" to "done",
            "stage" to "done", "pr" to "https://github.com/demo/herdr-android/pull/3", "msg" to "Merged"),
        agentEntry(2, 10, SITE_AGENT, "codex", "done", "Rewrite the install page", "website › main"),
        taskEntry(2, 14, "WEB-4", "working", "id" to "website/4", "title" to "Rewrite the install page", "state" to "blocked", "stage" to "approval",
            "kind" to "permission", "msg" to "Review found 2 problems", "review" to obj("pass" to false)),
        taskEntry(4, 30, "EVAL-1", "working", "id" to "llm-eval/1", "title" to "Compare long-context benchmarks", "state" to "done", "stage" to "done",
            "research" to true, "msg" to "The report is ready"),
        taskEntry(5, 40, "API-3", "working", "id" to "api-server/3", "title" to "Speed up /jobs on large tables", "state" to "blocked", "stage" to "working",
            "kind" to "question", "msg" to "Asks whether the migration may lock the jobs table for a few seconds"),
        taskEntry(6, 30, "AND-6", "queued", "id" to "herdr-android/6", "title" to "Add app screenshots to the README", "state" to "working", "stage" to "working"),
        taskEntry(6, 58, "AND-9", "working", "id" to "herdr-android/9", "title" to "Show when each machine last answered", "state" to "blocked",
            "stage" to "approval", "kind" to "permission", "msg" to "Review passed. Ready to merge.", "review" to obj("pass" to true)),
    )

    /** gpu-box has no board: one of your agents finished there in the night. */
    fun gpuJournal() = journal(
        agentEntry(3, 40, "w3:p1", "codex", "done", "Profile the data loader", "notebooks › main"),
    )

    /** A board on gpu-box with one task, whose two attempts wait for a pick (the review cards across machines). */
    fun gpuBoard() = FakeBoard(
        tasks = kotlinx.serialization.json.Json.parseToJsonElement(resource("board/gpu-list.json")) as kotlinx.serialization.json.JsonArray,
        plain = resource("board/gpu-list.txt"),
    )

    /**
     * devbox's routines (the board's automations), as `automations --json` prints them at [NOW]
     * (Saturday 17 October, 07:42): one on a Monday schedule whose last task is ready to approve, one
     * every two hours whose last task asks you, a research one at work, one on a board event that
     * never ran, and a paused one.
     */
    fun routines(): kotlinx.serialization.json.JsonArray = kotlinx.serialization.json.Json.parseToJsonElement(
        """
        [
          {"id": "a7f3c2e1", "name": "Bump dependencies", "on": true, "trigger": "mon 07:00", "repo": "herdr-android", "folder": "/home/demo/src/herdr-android",
           "research": false, "prompt": "Update the outdated dependencies, run the tests and open a pull request with what changed.",
           "settings": {"worker.agent": "claude/claude-opus-5-5"},
           "last": {"at": ${NOW - 5 * DAY - 42 * MIN}, "cause": "schedule", "what": "mon 07:00", "task": "herdr-android/9"}, "next": ${NOW + 2 * DAY - 42 * MIN}},
          {"id": "c1a2b3d4", "name": "Check CI on open PRs", "on": true, "trigger": "every 2h", "repo": "api-server", "folder": "/home/demo/src/api-server",
           "research": false, "prompt": "Look at the CI runs of the open pull requests. Fix what broke, or say why it can't be fixed here.",
           "settings": {},
           "last": {"at": ${NOW - 42 * MIN}, "cause": "schedule", "what": "every 2h", "task": "api-server/3"}, "next": ${NOW + 78 * MIN}},
          {"id": "e5f60718", "name": "Hunt flaky tests", "on": true, "trigger": "daily 02:00", "repo": "llm-eval", "folder": "/home/demo/src/llm-eval",
           "research": true, "prompt": "Find the tests that fail now and then, and say why each one does. Change nothing.",
           "settings": {"research.only": true},
           "last": {"at": ${NOW - 5 * HOUR - 42 * MIN}, "cause": "schedule", "what": "daily 02:00", "task": "llm-eval/2"}, "next": ${NOW + 18 * HOUR + 18 * MIN}},
          {"id": "0b9c8d7e", "name": "Look into failed reviews", "on": true, "trigger": "when a task failed its review, in every repo", "repo": "herdr-android",
           "folder": "/home/demo/src/herdr-android", "research": true, "prompt": "Read the review that failed and say which findings are real.",
           "settings": {"research.only": true}, "last": null, "next": null},
          {"id": "9e8d7c6b", "name": "Weekly changelog draft", "on": false, "trigger": "fri 16:00", "repo": "website", "folder": "/home/demo/src/website",
           "research": false, "prompt": "Draft the week's changelog from the merged pull requests.", "settings": {},
           "last": {"at": ${NOW - 8 * DAY - 15 * HOUR - 42 * MIN}, "cause": "schedule", "what": "fri 16:00", "task": "website/2"}, "next": null}
        ]
        """,
    ) as kotlinx.serialization.json.JsonArray

    /** "Check CI on open PRs"' firings, newest first: the tasks it made, skips while one was at work, one without an agent. */
    fun ciFirings(): kotlinx.serialization.json.JsonArray = kotlinx.serialization.json.Json.parseToJsonElement(
        """
        [
          {"at": ${NOW - 42 * MIN}, "cause": "schedule", "what": "every 2h", "task": "api-server/3"},
          {"at": ${NOW - 2 * HOUR - 42 * MIN}, "cause": "schedule", "what": "every 2h", "task": "api-server/5"},
          {"at": ${NOW - 4 * HOUR - 42 * MIN}, "cause": "schedule", "what": "every 2h", "skipped": "its last task, api-server/1, is still being worked on", "n": 3},
          {"at": ${NOW - 20 * HOUR}, "cause": "run", "what": "run now", "task": "api-server/2", "note": "added, but no agent could be put on it: claude is at its limit"},
          {"at": ${NOW - DAY - 2 * HOUR}, "cause": "schedule", "what": "every 2h", "task": "api-server/1"}
        ]
        """,
    ) as kotlinx.serialization.json.JsonArray

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    /** devbox's board with nothing waiting for a yes: its approvals already merged. */
    fun nothingToReview() = FakeBoard().apply {
        val keep = tasks.indices.filter { i -> (tasks[i] as kotlinx.serialization.json.JsonObject)["stage"]?.toString()?.trim('"') != "approval" }
        plain = plain.lines().filter { it.isNotBlank() }.filterIndexed { i, _ -> i in keep }.joinToString("") { it + "\n" }
        tasks = kotlinx.serialization.json.JsonArray(keep.map { tasks[it] })
    }

    /** devbox's board on a calm morning: nothing waits for you, the rest at work or to do. */
    fun calmBoard() = FakeBoard().apply {
        val keep = tasks.indices.filter { i -> (tasks[i] as kotlinx.serialization.json.JsonObject)["state"]?.toString()?.trim('"') != "blocked" }
        plain = plain.lines().filter { it.isNotBlank() }.filterIndexed { i, _ -> i in keep }.joinToString("") { it + "\n" }
        tasks = kotlinx.serialization.json.JsonArray(keep.map { tasks[it] })
    }

    fun resource(path: String): String =
        Demo::class.java.classLoader.getResourceAsStream("demo/$path")?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("missing demo resource $path")

    /**
     * The code `herdr-push pair` shows on studio, a machine not added yet: its Tailscale name and
     * address and its address at home, two host keys, and a one-time code (fixed bytes, as everything here).
     */
    val PAIR_LINK = "herdr://pair?v=1&n=studio&u=demo&a=studio.tail1234.ts.net,100.84.3.12,192.168.0.20&p=22" +
        "&k=q7LwHn2xVb8rTz4cKe6yMa1sJd9fPu3gWo5iXl0hNcE,mR4tYb7uWq2zXe5cVn8aKs1dLf6gHj3pOi9kUy0wTrA" +
        "&c=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&x=1792222540"

    /** Where studio answers in the pairing scenes: its Tailscale name, with its Ed25519 key. */
    fun pairReachable() = mapOf("studio.tail1234.ts.net" to "SHA256:q7LwHn2xVb8rTz4cKe6yMa1sJd9fPu3gWo5iXl0hNcE")

    /** The saved machines, in the order the app lists them. */
    fun machines(): List<Machine> = listOf(
        machine(DEVBOX, "devbox", port = 2201),
        machine(GPU, "gpu-box", via = DEVBOX),
        machine(CI, "ci-runner", via = DEVBOX),
        machine(MINI, "mac-mini", host = "mac-mini.local"),
    )

    private fun machine(id: String, label: String, host: String = label, port: Int = 22, user: String = "demo", via: String? = null) =
        Machine(id = id, label = label, host = host, port = port, user = user, viaMachineId = via, hostKey = FakeMachine(id).fingerprint)

    /** The Agents list triaged: gpu-box's eval pinned and muted, AND-6's agent snoozed for three hours. */
    fun triage() = com.makefunstuff.herdr.core.home.Triage(
        pins = listOf("$GPU/w1:p1"),
        snoozes = mapOf("$DEVBOX/w4:p1" to com.makefunstuff.herdr.core.home.Snooze(until = NOW + 3 * 3_600_000L)),
        muted = setOf("$GPU/w1:p1"),
        used = true,
    )

    /** Notifications on: devbox and gpu-box send, ci-runner is left out, mac-mini has no python3. */
    fun push(): PushState {
        val endpoint = "https://ntfy.sh/upDemoTopic?up=1"
        return PushState(
            on = true, phoneId = "demo-phone", endpoint = endpoint, distributor = "io.heckel.ntfy",
            machines = mapOf(
                DEVBOX to MachinePush(key = "demo", sentTo = endpoint, version = "1"),
                GPU to MachinePush(key = "demo", sentTo = endpoint, version = "1", note = "it stops when you log out (loginctl enable-linger keeps it running)"),
                CI to MachinePush(off = true),
                MINI to MachinePush(key = "demo", problem = "herdr-push needs python3 on mac-mini, and it isn't installed"),
            ),
        )
    }

    /** Watches set from the long-press sheets: AND-6's checks, the eval on gpu-box finishing. */
    fun watches() = listOf(
        com.makefunstuff.herdr.core.push.Watch(DEVBOX, com.makefunstuff.herdr.core.push.Watch.What.ChecksPass, taskId = "herdr-android/6", name = "AND-6"),
        com.makefunstuff.herdr.core.push.Watch(GPU, com.makefunstuff.herdr.core.push.Watch.What.AgentDone, paneId = "w1:p1", name = "the eval"),
    )

    /** AND-6 past its checks, in review: what fires its watch. */
    fun and6Reviewing() = com.makefunstuff.herdr.core.board.BoardTasks.task(
        kotlinx.serialization.json.Json.parseToJsonElement(
            """{"id":"herdr-android/6","title":"Add app screenshots to the README","state":"working","stage":"reviewing"}""",
        ) as JsonObject,
        "AND-6",
    )!!

    /** Quiet hours as someone might set them: with the phone's modes, and weeknights from 22:00 to 07:00. */
    fun quiet() = QuietHours(followPhone = true, schedule = true, start = 22 * 60, end = 7 * 60, days = (1..5).toSet())

    /** Two agents that finished while it was quiet. */
    fun held() = listOf(
        Alert(Alerts.agentTag(DEVBOX, APP_AGENT), "done#12", Alert.Kind.Finished, DEVBOX, "app finished", "Speed up /jobs", paneId = APP_AGENT),
        Alert(Alerts.agentTag(DEVBOX, SITE_AGENT), "done#7", Alert.Kind.Finished, DEVBOX, "site finished", "Rewrite the install page", paneId = SITE_AGENT),
    )

    /** A failing CI run shared from the browser, with a screenshot of it. */
    fun shared() = com.makefunstuff.herdr.core.share.Shared(
        text = "CI is red on main: test_jobs_pagination times out\nhttps://ci.example.dev/api-server/runs/1482",
        files = listOf(com.makefunstuff.herdr.core.share.SharedFile("Screenshot_20261017_0912.png", "/nonexistent/Screenshot_20261017_0912.png", 482_133)),
    )

    /** Two comments on task AND-9's code, kept to send together: one on the card's text, one on its diff. */
    fun notes() = listOf(
        com.makefunstuff.herdr.core.code.ReviewNote(
            id = "1", machineId = DEVBOX, root = FakeCode.TASK, branch = "work/9-last-answered",
            path = "app/src/main/kotlin/demo/herdr/ui/machines/MachineCard.kt", start = 37, end = 38,
            quote = "        // Offline: the last known state, and how old it is.\n        else -> \"Answered \" + LastAnswered.words(state.answeredAt, now)",
            language = "kotlin", text = "Show this in the notice colour when it is older than an hour, so a stale card stands out.", at = NOW - 600_000,
        ),
        com.makefunstuff.herdr.core.code.ReviewNote(
            id = "2", machineId = DEVBOX, root = FakeCode.TASK, branch = "work/9-last-answered",
            path = "core/src/main/kotlin/demo/herdr/core/host/HostSession.kt", start = 22, end = 22, source = "the diff against main",
            quote = "+        _state.update { it.copy(online = true, agents = agents, answeredAt = clock()) }", language = "diff",
            text = "Also note the time when a snapshot arrives, not only on the first answer.", at = NOW - 300_000,
        ),
    )

    /** A file to share for real: a screenshot the app makes smaller into the Coordinator's composer, then deletes. */
    fun sharedFile(): java.io.File = kotlin.io.path.createTempFile("shared", ".png").toFile().apply { writeBytes(picture("task").readBytes()) }

    /**
     * A screenshot on the phone, for the composer and the chat's pictures: the README's own
     * screenshots of the app (540×1200, `docs/screenshots/<name>.webp`), as a phone's would be.
     */
    fun picture(name: String): java.io.File {
        val start = java.io.File("").absoluteFile
        val repo = generateSequence(start) { it.parentFile }.firstOrNull { java.io.File(it, "docs/screenshots").isDirectory } ?: error("no docs/screenshots above $start")
        return java.io.File(repo, "docs/screenshots/$name.webp")
    }

    /** Where devbox keeps the pictures sent to its agents, as `HostSession.upload` puts them. */
    const val UPLOADS = "/home/demo/.cache/herdr-android/uploads/20261018-101204-3f2a"

    /** devbox's app agent, after you sent it two screenshots and it answered. */
    fun appTranscriptWithPictures(): String = resource("claude-app.jsonl").trimEnd() + "\n" + """
        |{"type": "user", "uuid": "00000000-0000-4000-8000-000000000030", "timestamp": "2026-10-17T07:40:30.000Z", "message": {"role": "user", "content": "@$UPLOADS/Screenshot_20261018_101204.png @$UPLOADS/Screenshot_20261018_101230.png The Board card jumps when a machine reconnects: first shot before, second after. Keep it where it was?"}}
        |{"type": "assistant", "uuid": "00000000-0000-4000-8000-000000000031", "timestamp": "2026-10-17T07:40:41.000Z", "message": {"role": "assistant", "content": [{"type": "text", "text": "I see it: in the second screenshot the **Needs you** group is drawn again from the top when devbox answers, so the list scrolls back. I'll key the rows by task id so the list keeps its place."}]}}
        |""".trimMargin()

    /**
     * What devbox's app agent writes after its second ask's first edit: the card, a core test run that
     * fails, the fix, the tests again, and its answer (`claude-app-more.jsonl`, written by hand in the
     * session's own format). [lines] keeps only the first of them.
     */
    fun appMore(lines: Int = Int.MAX_VALUE): String = resource("claude-app-more.jsonl").trimEnd().lines().take(lines).joinToString("\n") + "\n"

    /** devbox's app agent once its second ask is done, a failed test run on the way. */
    fun appTranscriptDone(): String = resource("claude-app.jsonl").trimEnd() + "\n" + appMore()

    /** A thinking block as Claude Code writes one: its own line, before the words or the call it led to. */
    private fun thinking(uuid: Int, at: String, text: String): String =
        """{"type": "assistant", "uuid": "00000000-0000-4000-8000-${"%012d".format(uuid)}", "timestamp": "$at", "cwd": "/home/demo/src/herdr-android", """ +
            """"message": {"role": "assistant", "model": "claude-opus-5-5", "content": [{"type": "thinking", "thinking": ${kotlinx.serialization.json.JsonPrimitive(text)}, "signature": "sig"}]}}"""

    private val THOUGHT_FIX = "The test failed because `HostSessionTest` still builds `HostState` with the old constructor. " +
        "I could give `updatedAt` a default, but then a snapshot without a time would look fresh, which is the bug the user wants to see."

    /** What it thought before its answer: what it checked, and why the card counts from the snapshot. */
    val THOUGHT_ANSWER = """
        |**Checking the result before I answer**
        |
        |The second run passes: 52 tests, none failed. The card reads `state.updatedAt` and formats it with `ago()`, so it says "2 min ago" and moves on by itself while the list recomposes every minute.
        |
        |Where does the time come from? From the moment the snapshot *arrives* on the phone, not from the machine's clock. That's the right one here: the user asked when each machine last answered, and a machine whose clock runs fast would otherwise look fresher than it is. I should say that in one line.
        |
        |Things I didn't change, worth knowing:
        |
        |- a machine that never answered has no `updatedAt`, so its card shows nothing rather than "never";
        |- the jump host's machines get their own time, since each has its own session.
        |
        |I won't mention the failed run in detail; it was my own missing parameter, fixed in the test. Keep the answer short: what it shows, where the time comes from, that the tests pass.
        |""".trimMargin().trim()

    /** devbox's app agent once its second ask is done, with its thinking on the way and before its answer. */
    fun appTranscriptReasoning(): String {
        val more = appMore().trimEnd().lines()
        val fix = more.indexOfFirst { "One test still builds" in it }
        val lines = more.take(fix) + thinking(90, "2026-10-17T07:40:50.000Z", THOUGHT_FIX) + more.subList(fix, more.lastIndex) +
            thinking(91, "2026-10-17T07:41:44.000Z", THOUGHT_ANSWER) + more.last()
        return resource("claude-app.jsonl").trimEnd() + "\n" + lines.joinToString("\n") + "\n"
    }

    /** The subagent devbox's app agent sent to check its change (`claude-app-subagent.jsonl`). */
    const val APP_SUBAGENT = "a7f3c2e10b9d4e58"

    /** devbox's app agent after a second ask it handed to a subagent, whose own steps are [appSubagentSteps]. */
    fun appTranscriptSubagent(): String = appTranscriptDone().trimEnd() + "\n" + resource("claude-app-subagent.jsonl")

    /** What that subagent did, as Claude keeps it in `<session>/subagents/agent-<id>.jsonl`. */
    fun appSubagentSteps(): String = resource("claude-app-subagent-steps.jsonl")

    /** devbox's app agent once its edit came back, before it wrote anything more: the model is at work. */
    fun appTranscriptWaiting(): String = resource("claude-app.jsonl").trimEnd() + "\n" + appMore(1)

    /** devbox's app agent thinking right now: its edit came back, and the newest line is a thought. */
    fun appTranscriptThinking(): String = resource("claude-app.jsonl").trimEnd() + "\n" + appMore(1) +
        thinking(92, "2026-10-17T07:41:50.000Z", "`HostState` has `updatedAt` now. Next the card: it should read the time and say it the way the rest of the app does.\n\nThere's `ago()` in `ui/common/Time.kt`, which says \"2 min ago\". I'll use that rather than a new formatter, and only show it when the snapshot has a time.") + "\n"

    /** devbox's app agent writing a whole new file once its edit came back: a long edit, folded in the chat. */
    fun appTranscriptWrite(): String {
        val file = """
            |package com.makefunstuff.herdr.core.host
            |
            |/**
            | * When a machine last answered, as its card says it: "just now", "2 min ago", "3 h ago".
            | * Counted from when its snapshot arrived on the phone, not from the machine's own clock.
            | */
            |object LastAnswered {
            |    private const val MINUTE = 60_000L
            |    private const val HOUR = 60 * MINUTE
            |
            |    fun of(updatedAt: Long?, now: Long): String? {
            |        val at = updatedAt ?: return null
            |        val ago = (now - at).coerceAtLeast(0)
            |        return when {
            |            ago < MINUTE -> "just now"
            |            ago < HOUR -> "${'$'}{ago / MINUTE} min ago"
            |            else -> "${'$'}{ago / HOUR} h ago"
            |        }
            |    }
            |
            |    /** An hour without an answer: the card turns amber. */
            |    fun stale(updatedAt: Long?, now: Long): Boolean =
            |        updatedAt != null && now - updatedAt > HOUR
            |}
            |""".trimMargin()
        val input = kotlinx.serialization.json.buildJsonObject {
            put("file_path", kotlinx.serialization.json.JsonPrimitive("/home/demo/src/herdr-android/core/src/main/kotlin/com/makefunstuff/herdr/core/host/LastAnswered.kt"))
            put("content", kotlinx.serialization.json.JsonPrimitive(file))
        }
        return resource("claude-app.jsonl").trimEnd() + "\n" + appMore(1) +
            """{"type": "assistant", "uuid": "00000000-0000-4000-8000-000000000050", "timestamp": "2026-10-17T07:40:20.000Z", "cwd": "/home/demo/src/herdr-android", """ +
            """"message": {"role": "assistant", "content": [{"type": "tool_use", "id": "toolu_00000050", "name": "Write", "input": $input}]}}""" + "\n"
    }

    /**
     * devbox's website agent in an older Codex, whose rollout keeps raw calls: one `exec` script that
     * applies a patch and then checks the links, as Codex's code mode writes it.
     */
    fun siteTranscriptExec(): String = """
        |{"timestamp": "2026-10-16T09:20:00.000Z", "type": "turn_context", "payload": {"cwd": "/home/demo/src/website", "model": "gpt-5.5", "effort": "medium"}}
        |{"timestamp": "2026-10-16T09:20:00.000Z", "type": "event_msg", "payload": {"type": "user_message", "message": "Say where to unpack it in Manual install, then check the links again."}}
        |{"timestamp": "2026-10-16T09:20:09.000Z", "type": "response_item", "payload": {"type": "custom_tool_call", "status": "completed", "call_id": "call_exec", "name": "exec", "input": "text(await tools.apply_patch(\"*** Begin Patch\\n*** Update File: /home/demo/src/website/content/docs/install.md\\n@@ ## Manual install\\n-Unpack the archive anywhere on your PATH.\\n+Unpack the archive into `~/.local/bin`, which is on your PATH.\\n*** End Patch\"));\ntext(await tools.exec_command({cmd:\"npm run check-links\"}));\n"}}
        |{"timestamp": "2026-10-16T09:20:14.000Z", "type": "response_item", "payload": {"type": "custom_tool_call_output", "call_id": "call_exec", "output": "{\"output\": \"Success. Updated the following files:\\nM content/docs/install.md\\n\\n> check-links\\nChecked 38 links, 0 broken\\n\", \"metadata\": {\"exit_code\": 0, \"duration_seconds\": 4.2}}"}}
        |{"timestamp": "2026-10-16T09:20:20.000Z", "type": "event_msg", "payload": {"type": "agent_message", "message": "Manual install now says to unpack into `~/.local/bin`. All 38 links still resolve."}}
        |""".trimMargin()

    /** Then a third ask, typed at the desk, and the agent starting on it: two new messages for a chat scrolled up. */
    val APP_NEXT = """
        |{"type": "user", "uuid": "00000000-0000-4000-8000-000000000040", "timestamp": "2026-10-17T07:41:56.000Z", "cwd": "/home/demo/src/herdr-android", "message": {"role": "user", "content": "Make it amber when a machine hasn't answered for an hour."}}
        |{"type": "assistant", "uuid": "00000000-0000-4000-8000-000000000041", "timestamp": "2026-10-17T07:41:59.000Z", "cwd": "/home/demo/src/herdr-android", "message": {"role": "assistant", "content": [{"type": "text", "text": "I'll use the notice colour for that, so a stale card stands out."}]}}
        |{"type": "assistant", "uuid": "00000000-0000-4000-8000-000000000043", "timestamp": "2026-10-17T07:42:00.000Z", "cwd": "/home/demo/src/herdr-android", "message": {"role": "assistant", "content": [{"type": "tool_use", "id": "toolu_00000042", "name": "Edit", "input": {"file_path": "/home/demo/src/herdr-android/app/src/main/kotlin/com/makefunstuff/herdr/ui/machines/MachineCard.kt", "old_string": "Text(ago(it)", "new_string": "Text(ago(it), color = if (stale) Herdr.colors.notice else Herdr.colors.muted"}}]}}
        |""".trimMargin()

    /** The pictures in that conversation, as devbox has them. */
    fun appPictures(): Map<String, java.io.File> = mapOf(
        "$UPLOADS/Screenshot_20261018_101204.png" to picture("board"),
        "$UPLOADS/Screenshot_20261018_101230.png" to picture("board-light"),
    )

    /** What each machine has and answers. */
    fun hosts(): Map<String, FakeMachine> = mapOf(
        DEVBOX to FakeMachine(
            DEVBOX,
            snapshot = devbox(),
            screens = mapOf(
                APP_AGENT to resource("screen-claude-app.txt"),
                APP_SHELL to SHELL,
                API_AGENT to resource("screen-claude-api.txt"),
                SITE_AGENT to resource("screen-codex.txt"),
            ),
            transcripts = mapOf(
                APP_SESSION to resource("claude-app.jsonl"),
                API_SESSION to resource("claude-api.jsonl"),
                SITE_SESSION to resource("codex-site.jsonl"),
            ),
            board = FakeBoard(automations = routines(), firings = mapOf("c1a2b3d4" to ciFirings())),
            // AND-9's timeline: its agent's session (Claude Code) and its reviewer's (codex), with their times, and the board's record of it.
            // The app agent's own timeline (from its chat) is that session while it still works.
            activity = mapOf(
                "/home/demo/.claude/projects/-home-demo-src-herdr-android-9/$APP_SESSION.jsonl" to resource("activity/claude-and9.jsonl"),
                APP_SESSION to resource("activity/claude-and9-live.jsonl"),
                "0199a3d0-9e8f-7a10-b2c3-d4e5f6a7b8c9" to resource("activity/codex-and9-review.jsonl"),
            ),
            records = mapOf("herdr-android/9" to resource("activity/task-herdr-android-9.json")),
            said = mapOf("herdr-android/9" to resource("activity/said-herdr-android-9.jsonl")),
            workspaceTokens = mapOf("w1" to mapOf("branch" to "work/9-last-answered", "pr" to "#9")),
            journal = devboxJournal(),
            code = FakeCode(),
            quick = FakeQuick(),
            usage = FakeUsage(),
            logins = FakeLogins(),
            agentCommands = AGENT_COMMANDS,
            ports = DEVBOX_PORTS,
        ),
        GPU to FakeMachine(
            GPU,
            logins = FakeLogins(gemini = false, profile = false),
            snapshot = gpu(),
            screens = mapOf("w1:p1" to resource("screen-codex-eval.txt")),
            transcripts = mapOf(EVAL_SESSION to resource("codex-eval.jsonl")),
            journal = gpuJournal(),
        ),
        CI to FakeMachine(
            CI,
            snapshot = snapshot(
                Ws("w1", "runners", "/home/demo/runners", listOf(Pane("w1:p1", label = "shell"))),
                Ws("w2", "deploy", "/home/demo/deploy", listOf(Pane("w2:p1", label = "shell"))),
            ),
        ),
        MINI to FakeMachine(MINI, refuse = ConnectError.Auth(null)),
    )

    /**
     * What listens on devbox: the website's vite and storybook, the API's uvicorn (started outside
     * herdr, in its folder), the herdr-android workspace's Gradle daemon, and sshd (not the account's).
     */
    val DEVBOX_PORTS = listOf(
        "L\t22\t0.0.0.0\t\t\t\t\t\t\t",
        "L\t5173\t127.0.0.1\t4101\tnode\tw3\tw3:p1\t/home/demo/src/website\t1\tnode /home/demo/src/website/node_modules/.bin/vite --port 5173",
        "L\t6006\t127.0.0.1\t4133\tnode\tw3\tw3:p1\t/home/demo/src/website\t1\tnode /home/demo/src/website/node_modules/.bin/storybook dev -p 6006",
        "L\t8000\t0.0.0.0\t4120\tpython3\t\t\t/home/demo/src/api-server\t1\t/usr/bin/python3 -m uvicorn app:api --reload",
        "L\t41234\t127.0.0.1\t4200\tjava\tw1\t\t/home/demo/src/herdr-android\t0\tjava -cp gradle-launcher.jar org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.10",
    ).joinToString("\n") + "\n"

    /** The own commands and skills on devbox, as `herdr-agent-commands` prints them: herdr-android's `/ship`, and a skill of the person's. */
    private val AGENT_COMMANDS = listOf(
        "@@herdr-command\tproject\tcommand\tship\n---\ndescription: Run the checks, commit and open a pull request\n---\nRun ./gradlew check, then commit and open a PR.\n",
        "@@herdr-command\tskill\tskill\tscreen-previews\n---\nname: screen-previews\ndescription: Render the changed screens and look at them\n---\n",
    ).joinToString("\n")

    const val APP_SESSION = "3f6c2a1e-8b7d-4c2a-9e51-0a1b2c3d4e5f"
    private const val API_SESSION = "9d2e4b7a-1c3f-4e6a-8b90-5f4e3d2c1b0a"
    const val SITE_SESSION = "0199a3b2-7c41-7e10-9a2b-3c4d5e6f7a8b"
    private const val EVAL_SESSION = "0199a3c4-1d2e-7f30-8a4b-5c6d7e8f9a0b"

    /**
     * devbox's session. [appKind]: the agent at work in herdr-android (pi, for `chat-pi-thinking`). [home]: as herdr-projects runs its agents, each named for its task (`sw-<repo>-<n>`),
     * so the Agents screen ties them to the board's tasks; and one more, at work on AND-6 in the
     * workspace the board opened for it.
     */
    fun devbox(appStatus: String = "working", home: Boolean = false, apiStatus: String = "blocked", reviewer: Boolean = false, appKind: String = "claude"): JsonObject = snapshot(
        *listOfNotNull(
            Ws(
                "w1", "herdr-android", "/home/demo/src/herdr-android",
                listOf(
                    Pane(APP_AGENT, agent = Agent(appKind, appStatus, APP_SESSION, "Show when each machine last answered", name = "sw-herdr-android-9".takeIf { home })),
                    Pane(APP_SHELL, label = "shell"),
                ) + listOfNotNull(if (reviewer) Pane("w1:p3", agent = Agent("codex", "idle", "0199a3e1-4f5a-7b60-8c7d-9e0f1a2b3c4d", "Review the last-answered change")) else null),
                repo = "herdr-android",
            ),
            Ws("w2", "api-server", "/home/demo/src/api-server", listOf(Pane(API_AGENT, agent = Agent("claude", apiStatus, API_SESSION, "Speed up /jobs on large tables", name = "sw-api-server-3".takeIf { home }, seq = 41))), repo = "api-server"),
            Ws("w3", "website", "/home/demo/src/website", listOf(Pane(SITE_AGENT, agent = Agent("codex", "done", SITE_SESSION, "Rewrite the install page", name = "sw-website-4".takeIf { home }, seq = 37, completion = 37))), repo = "website"),
            if (home) Ws(
                "w4", "AND-6 Add app screenshots to the README", "/home/demo/.herdr/worktrees/herdr-android/work-6-screenshots",
                listOf(Pane("w4:p1", agent = Agent("claude", "working", "4b1d2e3f-5a6b-4c7d-8e9f-0a1b2c3d4e5f", "Take the README screenshots", name = "sw-herdr-android-6", seq = 44))),
            ) else null,
        ).toTypedArray(),
    )

    /**
     * The Coordinator asked what needs you: it answers with a list of tasks, each with its own words,
     * which stay under the task's row.
     */
    fun whatNeedsMe(): JsonObject {
        val r = FakeBoard().remote
        val c = r.getValue("coordinator") as JsonObject
        val talk = (c.getValue("talk") as kotlinx.serialization.json.JsonArray) + listOf(
            obj("you" to true, "text" to "What needs me?"),
            obj(
                "you" to false,
                "text" to "Three tasks wait for you:\n\n" +
                    "- **API-3** Speed up /jobs on large tables\n" +
                    "  Asks whether the migration may lock the jobs table for a few seconds.\n" +
                    "- **AND-9:** ready to merge, review passed\n" +
                    "- **WEB-4**: the review found 2 problems\n" +
                    "  - the Homebrew step names the old tap\n" +
                    "  - no step for Linux arm64\n\n" +
                    "AND-6 and EVAL-2 are still working.",
            ),
        )
        return JsonObject(r + ("coordinator" to JsonObject(c + mapOf("talk" to json(talk), "said" to json(talk.last().let { (it as JsonObject).getValue("text") })))))
    }

    /**
     * devbox with a second tab in herdr-android, "tests", where a shell runs the tests: a workspace
     * with several tabs keeps its tab chips and layout thumbnail on the machine screen.
     */
    fun devboxTabs(): JsonObject {
        val s = devbox()
        fun list(k: String) = s.getValue(k) as kotlinx.serialization.json.JsonArray
        val workspaces = list("workspaces").map { w ->
            val o = w as JsonObject
            if ((o["workspace_id"] as kotlinx.serialization.json.JsonPrimitive).content == "w1") JsonObject(o + mapOf("tab_count" to json(2), "pane_count" to json(3))) else o
        }
        val tab = obj("tab_id" to "w1:t2", "workspace_id" to "w1", "number" to 2, "label" to "tests", "focused" to false, "pane_count" to 1, "agent_status" to "unknown")
        val pane = obj(
            "pane_id" to "w1:p3", "terminal_id" to "t-w1:p3", "workspace_id" to "w1", "tab_id" to "w1:t2", "focused" to false,
            "cwd" to "/home/demo/src/herdr-android", "label" to "tests", "agent_status" to "unknown", "terminal_title_stripped" to "gradle",
        )
        val layout = obj(
            "workspace_id" to "w1", "tab_id" to "w1:t2", "area" to rect(0, 0, 200, 50), "focused_pane_id" to "w1:p3",
            "panes" to listOf(obj("pane_id" to "w1:p3", "focused" to true, "rect" to rect(0, 0, 200, 50))), "splits" to emptyList<Any>(),
        )
        return JsonObject(
            s + mapOf(
                "workspaces" to json(workspaces), "tabs" to json(list("tabs") + tab),
                "panes" to json(list("panes") + pane), "layouts" to json(list("layouts") + layout),
            ),
        )
    }

    /**
     * gpu-box's session: the eval at work, and with [home] the data loader's profile it finished in the
     * night (not looked at since), and an idle shell agent.
     */
    fun gpu(home: Boolean = false): JsonObject = snapshot(
        Ws("w1", "llm-eval", "/home/demo/llm-eval", listOf(Pane("w1:p1", agent = Agent("codex", "working", EVAL_SESSION, "Long-context eval on ckpt-0612")))),
        Ws(
            "w2", "notebooks", "/home/demo/notebooks",
            listOf(
                if (home) Pane("w2:p1", agent = Agent("codex", "done", "0199a3c5-2e3f-7a40-9b5c-6d7e8f9a0b1c", "Profile the data loader", seq = 12, completion = 12))
                else Pane("w2:p1", label = "shell"),
            ),
        ),
    )

    class Agent(
        val kind: String, val status: String, val session: String, val title: String,
        /** herdr's agent name: the kind, unless herdr-projects named it for its task. */
        val name: String? = null,
        val seq: Long = 1,
        /** The finish herdr last saw ([com.makefunstuff.herdr.core.api.AgentInfo.completionSeq]). */
        val completion: Long? = null,
    )
    class Pane(val id: String, val label: String? = null, val agent: Agent? = null)
    class Ws(val id: String, val label: String, val cwd: String, val panes: List<Pane>, val repo: String? = null)

    /** A herdr `session.snapshot`: one tab per workspace, its panes side by side. */
    fun snapshot(vararg workspaces: Ws): JsonObject {
        val all = workspaces.toList()
        fun status(list: List<Pane>) = list.mapNotNull { it.agent?.status }.maxByOrNull { listOf("idle", "done", "working", "blocked").indexOf(it) } ?: "unknown"
        return obj(
            "version" to "0.9.0",
            "protocol" to 4,
            "workspaces" to all.mapIndexed { i, ws ->
                obj(
                    "workspace_id" to ws.id, "number" to i + 1, "label" to ws.label, "focused" to (i == 0),
                    "pane_count" to ws.panes.size, "tab_count" to 1, "active_tab_id" to "${ws.id}:t1", "agent_status" to status(ws.panes),
                    "worktree" to ws.repo?.let { obj("repo_key" to it, "repo_name" to it, "repo_root" to ws.cwd, "checkout_path" to ws.cwd) },
                )
            },
            "tabs" to all.map { ws ->
                obj(
                    "tab_id" to "${ws.id}:t1", "workspace_id" to ws.id, "number" to 1, "label" to "main", "focused" to true,
                    "pane_count" to ws.panes.size, "agent_status" to status(ws.panes),
                )
            },
            "panes" to all.flatMap { ws ->
                ws.panes.mapIndexed { i, p ->
                    obj(
                        "pane_id" to p.id, "terminal_id" to "t-${p.id}", "workspace_id" to ws.id, "tab_id" to "${ws.id}:t1",
                        "focused" to (i == 0), "cwd" to ws.cwd, "label" to p.label, "agent" to p.agent?.kind,
                        "display_agent" to p.agent?.kind, "agent_status" to (p.agent?.status ?: "unknown"),
                        "terminal_title_stripped" to (p.agent?.title ?: "bash"),
                    )
                }
            },
            "layouts" to all.map { ws ->
                val w = 200 / ws.panes.size
                obj(
                    "workspace_id" to ws.id, "tab_id" to "${ws.id}:t1", "area" to rect(0, 0, 200, 50), "focused_pane_id" to ws.panes.first().id,
                    "panes" to ws.panes.mapIndexed { i, p -> obj("pane_id" to p.id, "focused" to (i == 0), "rect" to rect(i * w, 0, w, 50)) },
                    "splits" to if (ws.panes.size > 1) listOf(obj("id" to "s1", "direction" to "right", "ratio" to 0.5, "rect" to rect(0, 0, 200, 50))) else emptyList<Any>(),
                )
            },
            "agents" to all.flatMap { ws ->
                ws.panes.mapNotNull { p ->
                    val a = p.agent ?: return@mapNotNull null
                    obj(
                        "terminal_id" to "t-${p.id}", "name" to (a.name ?: a.kind), "agent" to a.kind, "display_agent" to a.kind, "title" to a.title,
                        "agent_status" to a.status, "workspace_id" to ws.id, "tab_id" to "${ws.id}:t1", "pane_id" to p.id,
                        "state_change_seq" to a.seq, "completion_seq" to a.completion, "cwd" to ws.cwd,
                        "agent_session" to obj("agent" to a.kind, "kind" to "id", "value" to a.session, "source" to "herdr:${a.kind}"),
                    )
                }
            },
        )
    }

    private fun rect(x: Int, y: Int, w: Int, h: Int) = obj("x" to x, "y" to y, "width" to w, "height" to h)

    /** The shell beside the herdr-android agent, as `demo-session` leaves it. */
    /** devbox's shell after `sudo` asked for the password, the cursor after the question. */
    fun shellAsking(): String = SHELL + "sudo systemctl restart herdr-push\n[sudo] password for demo: "

    /** What "Why this state?" says of the blocked api-server agent (herdr's `agent.explain`). */
    fun explain() = obj(
        "explain" to obj(
            "state" to "blocked", "agent" to "claude", "matched_rule" to "claude_permission_prompt", "visible_blocker" to true,
            "manifest_version" to "3", "manifest_source" to "builtin",
            "evaluated_rules" to arr(
                obj("id" to "claude_permission_prompt", "state" to "blocked", "matched" to true, "region" to "bottom 12 lines", "evidence" to obj("region_preview" to "Do you want to proceed?")),
                obj("id" to "claude_spinner", "state" to "working", "matched" to false, "region" to "bottom 6 lines"),
            ),
        ),
    )

    private val SHELL = run {
        val p = "\u001b[1;32mdemo@devbox\u001b[0m:\u001b[1;34m~/src/herdr-android\u001b[0m$ "
        val y = "\u001b[33m"; val g = "\u001b[32m"; val c = "\u001b[36m"; val r = "\u001b[31m"; val b = "\u001b[1m"; val o = "\u001b[0m"
        """
        |${p}git log --oneline --graph -7
        |* ${y}ae3c0e4$o ($c${b}HEAD -> $o${g}${b}main$o) Better chat composer
        |* ${y}cda5a86$o Adopt the herdr web UI look
        |* ${y}acfc414$o Visual design pass
        |* ${y}355be7c$o Replace native popups with a designed UI
        |* ${y}f0abe66$o Better Markdown for the native board
        |* ${y}2c4e1b9$o Coordinator chat: attach files
        |* ${y}9a07d3e$o Board: approve from the task screen
        |${p}git status -sb
        |## ${g}main$o...${r}origin/main$o
        | ${r}M$o app/src/main/kotlin/com/makefunstuff/herdr/ui/machines/MachinesScreen.kt
        |${p}./gradlew -p core test
        |> Task :compileKotlin UP-TO-DATE
        |> Task :test
        |
        |BoardTest > coordinatorChatMerges ${g}PASSED$o
        |ChatTest > claudeTranscriptReadsTools ${g}PASSED$o
        |ChatTest > blockedPromptParsesMenu ${g}PASSED$o
        |PainterTest > drawsWideCharacters ${g}PASSED$o
        |
        |$b${g}BUILD SUCCESSFUL$o in 21s
        |4 actionable tasks: 1 executed, 3 up-to-date
        |$p
        """.trimMargin()
    }
}
