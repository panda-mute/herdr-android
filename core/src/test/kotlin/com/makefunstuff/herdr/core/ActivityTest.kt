package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.activity.ActivityCommands
import com.makefunstuff.herdr.core.activity.ClaudeActivity
import com.makefunstuff.herdr.core.activity.CodexActivity
import com.makefunstuff.herdr.core.activity.Diffs
import com.makefunstuff.herdr.core.activity.Durations
import com.makefunstuff.herdr.core.activity.Entry
import com.makefunstuff.herdr.core.activity.Origin
import com.makefunstuff.herdr.core.activity.PiActivity
import com.makefunstuff.herdr.core.activity.Prompts
import com.makefunstuff.herdr.core.activity.Role
import com.makefunstuff.herdr.core.activity.SessionSource
import com.makefunstuff.herdr.core.activity.SessionsReader
import com.makefunstuff.herdr.core.activity.StepFilter
import com.makefunstuff.herdr.core.activity.StepKind
import com.makefunstuff.herdr.core.activity.StepState
import com.makefunstuff.herdr.core.activity.Timeline
import com.makefunstuff.herdr.core.activity.Tools
import com.makefunstuff.herdr.core.code.DiffLine
import com.makefunstuff.herdr.core.code.FileChange
import com.makefunstuff.herdr.core.ssh.Duplex
import com.makefunstuff.herdr.core.ssh.Machine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityTest {
    private val t0 = "2026-10-18T01:00:00.000Z"
    private fun ts(seconds: Int) = java.time.Instant.parse(t0).plusSeconds(seconds.toLong()).toString()
    private fun ms(seconds: Int) = java.time.Instant.parse(t0).toEpochMilli() + seconds * 1000L

    // ---- Claude Code ----

    private val claude = listOf(
        """{"type":"user","uuid":"u1","timestamp":"${ts(0)}","cwd":"/w/repo","message":{"role":"user","content":"\n\n<pasted_content id=\"ab\">\nWork board task app/3, role: agent.\n\nDo the thing\n</pasted_content>"}}""",
        """{"type":"assistant","uuid":"a1","timestamp":"${ts(2)}","message":{"model":"claude-opus-5-5","content":[{"type":"text","text":"Reading the code first.\nThen more."},{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"cd /w/repo && ./gradlew -p core test","description":"Run core tests"}}]}}""",
        """{"type":"user","uuid":"r1","timestamp":"${ts(40)}","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"Exit code 1\n> Task :test FAILED\n3 tests completed, 1 failed\nBUILD FAILED in 37s","is_error":true}]},"toolUseResult":{"stdout":"","stderr":""}}""",
        """{"type":"assistant","uuid":"a2","timestamp":"${ts(41)}","message":{"content":[{"type":"tool_use","id":"t2","name":"Read","input":{"file_path":"/w/repo/src/A.kt","offset":10,"limit":20}},{"type":"tool_use","id":"t3","name":"Edit","input":{"file_path":"/w/repo/src/A.kt","old_string":"a","new_string":"b"}}]}}""",
        """{"type":"user","uuid":"r2","timestamp":"${ts(42)}","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t2","content":"10 fun a()"}]},"toolUseResult":{"type":"text","file":{"filePath":"/w/repo/src/A.kt"}}}""",
        """{"type":"user","uuid":"r3","timestamp":"${ts(44)}","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t3","content":"The file was updated."}]},"toolUseResult":{"filePath":"/w/repo/src/A.kt","oldString":"a","newString":"b","structuredPatch":[{"oldStart":12,"oldLines":3,"newStart":12,"newLines":3,"lines":[" fun a() {","-    a","+    b"," }"]}]}}""",
        """{"type":"assistant","uuid":"a3","timestamp":"${ts(45)}","message":{"content":[{"type":"tool_use","id":"t4","name":"Bash","input":{"command":"./gradlew -p core test"}},{"type":"tool_use","id":"t5","name":"WebSearch","input":{"query":"kotlin flows"}},{"type":"tool_use","id":"t6","name":"TodoWrite","input":{"todos":[{"content":"Fix it","status":"completed"},{"content":"Test it","status":"in_progress"}]}}]}}""",
        """{"type":"user","uuid":"r4","timestamp":"${ts(80)}","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t4","content":"BUILD SUCCESSFUL in 30s\n3 tests completed"}]},"toolUseResult":{"stdout":"BUILD SUCCESSFUL in 30s"}}""",
        """{"type":"user","uuid":"r5","isMeta":true,"timestamp":"${ts(81)}","message":{"role":"user","content":"<system-reminder>ignore me</system-reminder>"}}""",
        """{"type":"system","subtype":"turn_duration","uuid":"s1","timestamp":"${ts(90)}","durationMs":90000}""",
        """{"type":"user","uuid":"u2","timestamp":"${ts(200)}","message":{"role":"user","content":"Looks good, also update the README"}}""",
        """{"type":"assistant","uuid":"a4","timestamp":"${ts(203)}","message":{"content":[{"type":"tool_use","id":"t7","name":"Write","input":{"file_path":"/w/repo/README.md","content":"# Hi\nthere\n"}}]}}""",
        """{"type":"user","uuid":"r7","timestamp":"${ts(204)}","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t7","content":"File created"}]},"toolUseResult":{"type":"create","filePath":"/w/repo/README.md","content":"# Hi\nthere\n","structuredPatch":[]}}""",
        """{"type":"assistant","uuid":"a5","timestamp":"${ts(205)}","message":{"content":[{"type":"tool_use","id":"t8","name":"Bash","input":{"command":"sleep 100"}}]}}""",
    )

    @Test
    fun claudeSessionBecomesTurnsOfTimedSteps() {
        val log = ClaudeActivity("s0:")
        claude.forEach(log::feed)
        val turns = log.turns()
        assertEquals(2, turns.size)
        assertEquals(Role("agent"), log.role)

        val first = turns[0]
        assertEquals(Origin.Board, first.from)
        assertEquals("Started the agent", first.prompt)
        assertTrue(first.text.startsWith("Work board task app/3"), first.text)
        assertEquals("claude/claude-opus-5-5", first.agent)
        assertEquals(ms(0), first.at)
        assertEquals(ms(90), first.end)
        assertEquals(90_000L, first.took)

        val kinds = first.steps.map { it.kind }
        assertEquals(listOf(StepKind.Message, StepKind.Test, StepKind.Read, StepKind.Edit, StepKind.Test, StepKind.Web, StepKind.Plan), kinds)

        val failedTest = first.steps[1]
        assertEquals("Run core tests", failedTest.title)
        assertEquals(StepState.Failed, failedTest.state)
        assertEquals(1, failedTest.exit)
        assertEquals(38_000L, failedTest.took)
        assertEquals("BUILD FAILED in 37s", failedTest.note)
        assertEquals("s0:t1", failedTest.id)

        assertEquals("src/A.kt · lines 10–29", first.steps[2].title)
        val edit = first.steps[3]
        assertEquals("src/A.kt", edit.path)
        val diff = assertNotNull(edit.diff)
        assertEquals(1, diff.added)
        assertEquals(1, diff.removed)
        assertEquals(listOf(12, null, 13, 14), diff.hunks[0].lines.map { it.new })
        assertEquals("+1 −1", edit.note)

        val passed = first.steps[4]
        assertEquals(StepState.Done, passed.state)
        assertEquals(0, passed.exit)
        assertEquals("./gradlew -p core test", passed.title)
        assertEquals("☑ Fix it\n▸ Test it", first.steps[6].detail)
        // A tool call that never came back is over once the turn moved on.
        assertEquals(StepState.Done, first.steps[5].state)

        val second = turns[1]
        assertEquals(Origin.You, second.from)
        assertEquals("Looks good, also update the README", second.prompt)
        val write = second.steps[0]
        assertEquals(FileChange.Added, write.diff?.change)
        assertEquals(listOf("# Hi", "there"), write.diff?.hunks?.single()?.lines?.map { it.text })
        assertEquals(StepState.Running, second.steps[1].state)
        assertTrue(second.running)
    }

    @Test
    fun claudeInterruptionsAndShellEscapes() {
        val log = ClaudeActivity()
        listOf(
            """{"type":"user","uuid":"u1","timestamp":"${ts(0)}","message":{"content":"<bash-input>ls -la</bash-input>"}}""",
            """{"type":"user","uuid":"u2","timestamp":"${ts(1)}","message":{"content":"<bash-stdout>a\nb</bash-stdout><bash-stderr></bash-stderr>"}}""",
            """{"type":"user","uuid":"u3","timestamp":"${ts(5)}","message":{"content":"<command-name>/review</command-name><command-args>now</command-args>"}}""",
            """{"type":"assistant","uuid":"a1","timestamp":"${ts(6)}","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"sleep 9"}}]}}""",
            """{"type":"user","uuid":"u4","timestamp":"${ts(7)}","message":{"content":[{"type":"text","text":"[Request interrupted by user]"}]}}""",
            """{"type":"assistant","uuid":"x","isSidechain":true,"timestamp":"${ts(8)}","message":{"content":[{"type":"text","text":"subagent"}]}}""",
        ).forEach(log::feed)
        val turns = log.turns()
        assertEquals(listOf("! ls -la", "/review now"), turns.map { it.prompt })
        assertEquals("a\nb", turns[0].steps.single().output)
        assertEquals(StepState.Done, turns[1].steps[0].state)
        assertEquals("Interrupted", turns[1].steps[1].title)
        assertEquals(2, turns[1].steps.size)
    }

    // ---- codex ----

    @Test
    fun codexItemsCarryTheirStartEndAndExit() {
        val log = CodexActivity()
        listOf(
            """{"timestamp":"${ts(0)}","type":"session_meta","payload":{"cwd":"/w/repo"}}""",
            """{"timestamp":"${ts(0)}","type":"turn_context","payload":{"model":"gpt-6.1-sol","cwd":"/w/repo"}}""",
            """{"timestamp":"${ts(1)}","type":"event_msg","payload":{"type":"item_completed","item":{"type":"UserMessage","id":"u1","content":[{"type":"text","text":"Work board task app/3, role: reviewer, round 2.\nYou are an adversarial reviewer."}]},"started_at_ms":${ms(1)},"completed_at_ms":${ms(1)}}}""",
            """{"timestamp":"${ts(9)}","type":"event_msg","payload":{"type":"item_completed","item":{"type":"CommandExecution","id":"c1","command":["/usr/bin/bash","-lc","cat src/A.kt"],"parsed_cmd":[{"type":"read","cmd":"cat src/A.kt","name":"A.kt","path":"/w/repo/src/A.kt"}],"status":"completed","aggregated_output":"fun a()","exit_code":0},"started_at_ms":${ms(5)},"completed_at_ms":${ms(6)}}}""",
            """{"timestamp":"${ts(30)}","type":"event_msg","payload":{"type":"item_completed","item":{"type":"CommandExecution","id":"c2","command":["/usr/bin/bash","-lc","pytest -q"],"parsed_cmd":[{"type":"unknown","cmd":"pytest -q"}],"status":"failed","aggregated_output":"F.\n1 failed, 1 passed in 0.2s","exit_code":1},"started_at_ms":${ms(10)},"completed_at_ms":${ms(30)}}}""",
            """{"timestamp":"${ts(31)}","type":"event_msg","payload":{"type":"item_completed","item":{"type":"FileChange","id":"f1","changes":{"/w/repo/src/A.kt":{"type":"update","unified_diff":"@@ -1,2 +1,2 @@\n fun a()\n-old\n+new\n"},"/w/repo/src/B.kt":{"type":"add","content":"x\ny\n"}},"status":"completed"},"started_at_ms":${ms(31)},"completed_at_ms":${ms(31)}}}""",
            """{"timestamp":"${ts(32)}","type":"event_msg","payload":{"type":"item_completed","item":{"type":"WebSearch","id":"w1","query":"herdr api"},"started_at_ms":${ms(32)},"completed_at_ms":${ms(33)}}}""",
            """{"timestamp":"${ts(34)}","type":"event_msg","payload":{"type":"item_completed","item":{"type":"AgentMessage","id":"m1","content":[{"type":"Text","text":"Done reviewing."}]},"started_at_ms":${ms(34)},"completed_at_ms":${ms(34)}}}""",
            """{"timestamp":"${ts(35)}","type":"event_msg","payload":{"type":"task_complete","error":{"message":"You've hit your usage limit."}}}""",
        ).forEach(log::feed)
        assertEquals(Role("reviewer", 2), log.role)
        val turn = log.turns().single()
        assertEquals("Started the review, round 2", turn.prompt)
        assertEquals("codex/gpt-6.1-sol", turn.agent)
        val (read, test, editA, editB) = turn.steps
        assertEquals(StepKind.Read, read.kind)
        assertEquals("src/A.kt", read.title)
        assertEquals(1000L, read.took)
        assertEquals(StepKind.Test, test.kind)
        assertEquals(StepState.Failed, test.state)
        assertEquals(1, test.exit)
        assertEquals(20_000L, test.took)
        assertEquals("1 failed, 1 passed in 0.2s", test.note)
        assertEquals("src/A.kt", editA.title)
        assertEquals(listOf(DiffLine.Kind.Context, DiffLine.Kind.Removed, DiffLine.Kind.Added), editA.diff?.hunks?.single()?.lines?.map { it.kind })
        assertEquals("Added src/B.kt", editB.title)
        assertEquals(FileChange.Added, editB.diff?.change)
        assertEquals(StepKind.Web, turn.steps[4].kind)
        assertEquals(StepKind.Message, turn.steps[5].kind)
        val limit = turn.steps[6]
        assertEquals(StepKind.Notice, limit.kind)
        assertTrue(limit.failed)
    }

    @Test
    fun codexLegacyFunctionCalls() {
        val log = CodexActivity()
        listOf(
            """{"timestamp":"${ts(0)}","type":"event_msg","payload":{"type":"user_message","message":"Fix the bug"}}""",
            """{"timestamp":"${ts(1)}","type":"response_item","payload":{"type":"function_call","name":"shell","call_id":"c1","arguments":"{\"command\":[\"bash\",\"-lc\",\"cargo test\"]}"}}""",
            """{"timestamp":"${ts(9)}","type":"response_item","payload":{"type":"function_call_output","call_id":"c1","output":"{\"output\":\"test result: FAILED. 1 passed; 1 failed\",\"metadata\":{\"exit_code\":101,\"duration_seconds\":7.5}}"}}""",
        ).forEach(log::feed)
        val step = log.turns().single().steps.single()
        assertEquals(StepKind.Test, step.kind)
        assertEquals(101, step.exit)
        assertTrue(step.failed)
        assertEquals(7_500L, step.took)
    }

    // ---- pi ----

    @Test
    fun piToolCallsWithTheirStartResultAndDiff() {
        val log = PiActivity()
        listOf(
            """{"type":"session","id":"s","timestamp":"${ts(0)}","cwd":"/w/repo"}""",
            """{"type":"model_change","id":"m","timestamp":"${ts(0)}","model":"anthropic/claude-sonnet-5-5"}""",
            """{"type":"message","id":"u1","timestamp":"${ts(1)}","message":{"role":"user","content":[{"type":"text","text":"Continue this task. Before you stop, write your closing summary."}],"timestamp":${ms(1)}}}""",
            """{"type":"message","id":"a1","timestamp":"${ts(2)}","message":{"role":"assistant","content":[{"type":"toolCall","id":"c1","name":"bash","arguments":{"command":"cd /w/repo && go test ./...","i":"Running the tests"}},{"type":"toolCall","id":"c2","name":"edit","arguments":{"path":"/w/repo/a.go"}},{"type":"toolCall","id":"c3","name":"write","arguments":{"path":"/w/repo/n.md","content":"new\n"}}],"timestamp":${ms(2)}}}""",
            """{"type":"custom","customType":"tool_execution_start","data":{"toolCallId":"c1","startedAt":"${ts(3)}"},"timestamp":"${ts(3)}"}""",
            """{"type":"message","id":"r1","timestamp":"${ts(13)}","message":{"role":"toolResult","toolCallId":"c1","toolName":"bash","content":[{"type":"text","text":"--- FAIL: TestA\nFAIL\tpkg\nCommand exited with code 1"}],"details":{"exitCode":1},"isError":true,"timestamp":${ms(13)}}}""",
            """{"type":"message","id":"r2","timestamp":"${ts(15)}","message":{"role":"toolResult","toolCallId":"c2","toolName":"edit","content":[{"type":"text","text":"ok"}],"details":{"diff":"     ...\n  9 ctx\n-10 old\n+10 new\n     ...\n 40 end"},"isError":false,"timestamp":${ms(15)}}}""",
            """{"type":"message","id":"r3","timestamp":"${ts(16)}","message":{"role":"toolResult","toolCallId":"c3","toolName":"write","content":[{"type":"text","text":"ok"}],"details":{"wallTimeMs":500},"isError":false,"timestamp":${ms(16)}}}""",
            """{"type":"message","id":"a2","timestamp":"${ts(17)}","message":{"role":"assistant","content":[],"stopReason":"error","errorMessage":"Overloaded","timestamp":${ms(17)}}}""",
        ).forEach(log::feed)
        val turn = log.turns().single()
        assertEquals(Origin.Board, turn.from)
        assertEquals("Asked the agent to continue", turn.prompt)
        assertEquals("pi/anthropic/claude-sonnet-5-5", turn.agent)
        val (test, edit, write, error) = turn.steps
        assertEquals("Running the tests", test.title)
        assertEquals(StepKind.Test, test.kind)
        assertEquals(1, test.exit)
        assertEquals(10_000L, test.took)
        assertEquals("a.go", edit.title)
        assertEquals(2, edit.diff?.hunks?.size)
        assertEquals(listOf(9, 10, null), edit.diff?.hunks?.first()?.lines?.map { it.old })
        assertEquals(FileChange.Added, write.diff?.change)
        assertEquals(500L, write.took)
        assertTrue(error.failed)
        assertEquals("Overloaded", error.title)
    }

    @Test
    fun timelineCountsWhatTheTurnsDid() {
        val log = ClaudeActivity("s0:")
        claude.forEach(log::feed)
        val timeline = Timeline.of(listOf(log.turns()), emptyList())
        assertEquals(listOf("Started the agent", "Looks good, also update the README"), timeline.turns.map { it.prompt })
        assertEquals(1, timeline.failures)
        assertEquals(listOf("Run core tests"), timeline.filtered(StepFilter.Failures).flatMap { e -> (e as Entry.TurnEntry).turn.steps.map { it.title } })
        assertEquals(2, timeline.count(StepFilter.Tests))
        assertEquals(2, timeline.count(StepFilter.Edits))
        val edits = timeline.filtered(StepFilter.Edits)
        assertEquals(2, edits.size)
        assertTrue(edits.all { it is Entry.TurnEntry })
    }

    @Test
    fun aCutSessionNamesWhereItStarted() {
        // The agent's session was cut: its first turn isn't read.
        val log = ClaudeActivity()
        claude.drop(1).take(2).forEach(log::feed)
        val reader = log.turns()
        assertEquals("Earlier in this session", reader.single().prompt)
        assertEquals(Origin.Agent, reader.single().from)
    }

    @Test
    fun aTurnThatEndedAtOnceNamesItsHarness() = runBlocking {
        val dir = Files.createTempDirectory("activity").toFile()
        try {
            val f = sessionFile(dir, "r.jsonl", listOf("""{"timestamp":"${ts(0)}","type":"event_msg","payload":{"type":"user_message","message":"Work board task app/3, role: reviewer, round 1."}}"""))
            val sources = listOf(SessionSource("codex", "path", f.path))
            val reader = SessionsReader(sources)
            SessionsReader.stream({ local(it) }, ActivityCommands.sessions(sources, follow = false), reader) {}
            assertEquals("codex", reader.turns().single().single().agent)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- words ----

    @Test
    fun promptsAreNamedForWhatTheyDo() {
        assertEquals(Prompts.Reading(Origin.Board, "Started the review, round 3", Role("reviewer", 3)), Prompts.read("Work board task x/1, role: reviewer, round 3.\n\nYou are…"))
        assertEquals("Sent the review back: Fail · 2 major", Prompts.read("A reviewer went through your change. Result: Fail · 2 major. Fix them").title)
        assertEquals(Origin.Board, Prompts.read("You stopped before the work was finished. Continue").origin)
        assertEquals(Origin.You, Prompts.read("make it blue").origin)
        assertEquals(Prompts.key("Work  board\n task x/1"), Prompts.key("<pasted_content id=\"q\">Work board task x/1</pasted_content>"))
    }

    @Test
    fun testsCommandsAndTheirResults() {
        assertTrue(Tools.isTest("./gradlew -p core test"))
        assertTrue(Tools.isTest("export A=1; cd x && ./gradlew :app:testDebugUnitTest"))
        assertTrue(Tools.isTest("mise exec java -- ./gradlew :core:test --offline -q"))
        assertTrue(Tools.isTest("npm run test -- --watch=false"))
        assertTrue(Tools.isTest("cargo clippy"))
        assertTrue(Tools.isTest("python3 -m pytest tests/"))
        assertFalse(Tools.isTest("./gradlew assembleDebug"))
        assertFalse(Tools.isTest("git commit -m 'add pytest'"))
        assertFalse(Tools.isTest("python3 - <<'EOF'\nimport pytest\nEOF"))
        assertFalse(Tools.isTest("cat test.txt"))
        assertEquals(StepKind.Test, Tools.kind("Bash", "go test ./..."))
        assertEquals(StepKind.Command, Tools.kind("Bash", "ls"))
        assertEquals(StepKind.Read, Tools.kind("Grep"))
        assertEquals(StepKind.Edit, Tools.kind("MultiEdit"))
        assertEquals(StepKind.Web, Tools.kind("WebFetch"))
        assertEquals(StepKind.Plan, Tools.kind("update_plan"))
        assertEquals(StepKind.Tool, Tools.kind("mcp__hindsight__recall"))

        assertEquals(2, Tools.exitIn("Exit code 2\nboom"))
        assertEquals(127, Tools.exitIn("x\nCommand exited with code 127"))
        assertNull(Tools.exitIn("all good"))
        assertEquals("==== 3 passed, 1 failed in 2.1s".trim('=', ' '), Tools.testSummary("collected\n..F.\n==== 3 passed, 1 failed in 2.1s ===="))
        assertEquals("BUILD SUCCESSFUL in 5s", Tools.testSummary("> Task :test\nBUILD SUCCESSFUL in 5s\n"))
        assertEquals("git status", Tools.commandTitle("cd /w/repo && git status\nmore"))
        assertEquals("go vet", Tools.commandTitle("cd \"/a b\"; go vet"))
        assertEquals("0.4s", Durations.short(400))
        assertEquals("12s", Durations.short(12_400))
        assertEquals("3m 05s", Durations.short(185_000))
        assertEquals("1h 02m", Durations.short(3_720_000))
    }

    @Test
    fun diffsFromEachShape() {
        val a = Diffs.unified("x.kt", "@@ -3,2 +3,3 @@ fun a()\n ctx\n+one\n+two\n-gone\n")
        assertNotNull(a)
        assertEquals(2, a.added)
        assertEquals(1, a.removed)
        val big = Diffs.added("big.txt", (1..1000).joinToString("\n") { "l$it" })
        assertEquals(601, big.hunks.single().lines.size)
        assertEquals("400 more lines", big.hunks.single().lines.last().text)
        val r = Diffs.replaced("y", "a\nb", "c")
        assertEquals(2, r?.removed)
        assertNull(Diffs.pi("z", "nothing here"))
    }

    // ---- the commands, run here as the machine would ----

    /** Every process started, stopped after each test: a reader blocked on one would keep the test waiting. */
    private val started = java.util.concurrent.CopyOnWriteArrayList<Process>()

    @kotlin.test.AfterTest
    fun stopProcesses() {
        started.forEach { it.destroy() }
        started.forEach { it.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
    }

    private fun local(cmd: String, env: Map<String, String> = emptyMap()): Duplex {
        val pb = ProcessBuilder("sh", "-c", cmd)
        pb.environment().putAll(env)
        val p = pb.start().also { started += it }
        return object : Duplex {
            override val input: InputStream = p.inputStream
            override val output: OutputStream = p.outputStream
            // As SSH closes a channel: the command's input ends.
            override fun close() {
                runCatching { p.outputStream.close() }
                if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroy()
            }
        }
    }

    private fun sessionFile(dir: File, name: String, lines: List<String>): File = File(dir, name).also { it.writeText(lines.joinToString("\n") + "\n") }

    @Test
    fun sessionsCommandPrunesReadsAndSaysWhatIsMissing() = runBlocking {
        val dir = Files.createTempDirectory("activity").toFile()
        try {
            // Lines the timeline never shows, and an Edit result carrying a whole file, go before the phone sees them.
            val junk = """{"type":"api-request-blob","hash":"h","message":"${"x".repeat(50_000)}"}"""
            val big = claude[5].replace(""""oldString":"a"""", """"originalFile":"${"y".repeat(80_000)}","oldString":"a"""")
            val f = sessionFile(dir, "s1.jsonl", claude.take(5) + junk + big + claude.drop(6))
            val sources = listOf(SessionSource("claude", "path", f.path), SessionSource("claude", "id", "not-there"), SessionSource("aider", "path", f.path))
            val command = ActivityCommands.sessions(sources, follow = false)
            val raw = local(command).input.readBytes().toString(Charsets.UTF_8)
            assertFalse("api-request-blob" in raw)
            assertFalse("yyyyyyyyyy" in raw)
            assertTrue(raw.length < 20_000, "pruned to ${raw.length}")

            val reader = SessionsReader(sources)
            SessionsReader.stream({ local(it) }, command, reader) {}
            // The aider session has no reader; the id isn't on this machine.
            assertEquals(1, reader.missing)
            assertEquals(1, reader.sessions.size)
            assertFalse(reader.truncated)
            val turns = reader.turns().single()
            assertEquals(2, turns.size)
            assertEquals("+1 −1", turns[0].steps.first { it.kind == StepKind.Edit }.note)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sessionsCommandKeepsTheNewestLinesWithAndWithoutPython() = runBlocking {
        val dir = Files.createTempDirectory("activity").toFile()
        try {
            val lines = (0 until 400).map { i ->
                """{"type":"user","uuid":"u$i","timestamp":"${ts(i)}","message":{"role":"user","content":"message number $i ${"z".repeat(200)}"}}"""
            }
            val f = sessionFile(dir, "long.jsonl", lines)
            val sources = listOf(SessionSource("claude", "path", f.path))
            // A PATH with the shell's tools but no python3: the plain tail is used instead.
            val bin = File(dir, "bin").also { it.mkdirs() }
            for (tool in listOf("sh", "head", "tail", "wc", "tr", "cat", "find", "kill")) {
                val real = listOf("/bin", "/usr/bin").map { File(it, tool) }.firstOrNull { it.exists() } ?: continue
                Files.createSymbolicLink(File(bin, tool).toPath(), real.toPath())
            }
            for (env in listOf(emptyMap(), mapOf("PATH" to bin.path))) {
                val reader = SessionsReader(sources)
                SessionsReader.stream({ local(it, env) }, ActivityCommands.sessions(sources, follow = false, bytes = 30_000), reader) {}
                assertTrue(reader.truncated, "cut with $env")
                val turns = reader.turns().single()
                assertEquals("message number 399", turns.last().prompt.substringBefore(" z"), "with $env")
                assertTrue(turns.size in 80..140, "${turns.size} turns with $env")
                // Every line read whole: no turn from a line cut in the middle.
                assertTrue(turns.all { it.prompt.startsWith("message number") })
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun followsTheSessionWrittenLastAsItGrows() = runBlocking {
        val dir = Files.createTempDirectory("activity").toFile()
        try {
            val old = sessionFile(dir, "old.jsonl", claude.take(3))
            Thread.sleep(1100)
            val new = sessionFile(dir, "new.jsonl", listOf(claude[10]))
            val sources = listOf(SessionSource("claude", "path", new.path), SessionSource("claude", "path", old.path))
            val reader = SessionsReader(sources)
            var updates = 0
            val job = launch(Dispatchers.IO) {
                SessionsReader.stream({ local(it) }, ActivityCommands.sessions(sources, follow = true), reader) { updates++ }
            }
            try {
                withTimeout(10_000) { while (reader.live == null || updates == 0) delay(50) }
                assertEquals(new.path, reader.live?.source?.value)
                new.appendText(claude[11] + "\n" + claude[12] + "\n")
                withTimeout(10_000) {
                    while (reader.turns()[0].lastOrNull()?.steps?.firstOrNull()?.diff == null) delay(50)
                }
                    assertEquals("/w/repo/README.md", reader.turns()[0].last().steps.single().path)
            } finally {
                // Leaving the screen: cancelling alone stops the reading, the channel and the tail, at once.
                val stopped = withTimeoutOrNull(5_000) { job.cancelAndJoin(); true }
                if (stopped == null) stopProcesses()
                assertEquals(true, stopped, "cancelling didn't stop the reading")
            }
            // The tail on the machine stops with the reading.
            val left = ProcessBuilder("sh", "-c", "pgrep -f '[t]ail -c .* -F ${new.path}' || true").start().inputStream.readBytes().toString(Charsets.UTF_8).trim()
            assertEquals("", left)
        } finally {
            dir.deleteRecursively()
        }
    }
}
