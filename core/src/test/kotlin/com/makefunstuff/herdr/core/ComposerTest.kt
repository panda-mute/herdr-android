package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.chat.AgentCommand
import com.makefunstuff.herdr.core.chat.AgentCommands
import com.makefunstuff.herdr.core.chat.ClaudeTranscript
import com.makefunstuff.herdr.core.chat.CodexTranscript
import com.makefunstuff.herdr.core.chat.Completions
import com.makefunstuff.herdr.core.chat.Held
import com.makefunstuff.herdr.core.chat.ModelPill
import com.makefunstuff.herdr.core.chat.ModelUse
import com.makefunstuff.herdr.core.chat.PiTranscript
import com.makefunstuff.herdr.core.chat.QuickReplies
import com.makefunstuff.herdr.core.ssh.CommandResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelUseTest {
    @Test
    fun namesOnlyRegularIds() {
        assertEquals("Opus 5.5", ModelUse.name("claude-opus-5-5"))
        assertEquals("Sonnet 5", ModelUse.name("claude-sonnet-5"))
        assertEquals("Fable 5.1", ModelUse.name("anthropic/claude-fable-5-1"))
        assertEquals("GPT-5.5", ModelUse.name("gpt-5.5"))
        assertEquals("GPT-5.5-Sol", ModelUse.name("gpt-5.5-sol"))
        assertEquals("GLM-4.6", ModelUse.name("glm-4.6"))
        // Never guessed: a dated snapshot, a leading zero, a suffix, another vendor stay ids.
        assertNull(ModelUse.name("claude-opus-4-20250514"))
        assertNull(ModelUse.name("claude-sonnet-5-05"))
        assertNull(ModelUse.name("claude-opus-5-5[1m]"))
        assertNull(ModelUse.name("deepseek-chat"))
        assertEquals("deepseek-chat", ModelUse(model = "deepseek-chat").title)
    }

    @Test
    fun claudeWindowIsAssumedThenGrows() {
        val a = ModelUse.filled(124_000, "claude-opus-5-5", null, null)
        assertEquals(200_000L, a.window)
        assertTrue(a.windowAssumed)
        assertEquals(62, a.percent)
        // Past 200k it can only be the 1M window, and it stays so after a compaction.
        val b = ModelUse.filled(260_000, null, null, a)
        assertEquals(1_000_000L, b.window)
        assertEquals(1_000_000L, ModelUse.filled(40_000, null, null, b).window)
        // A stated window wins; another vendor without one has no percent.
        assertEquals(20, ModelUse.filled(51_800, "gpt-5.5", 258_400, null).percent)
        assertNull(ModelUse.filled(9_000, "deepseek-chat", null, null).percent)
    }

    @Test
    fun tokensReadShort() {
        assertEquals("950", ModelUse.tokens(950))
        assertEquals("124k", ModelUse.tokens(124_000))
        assertEquals("1M", ModelUse.tokens(1_000_000))
        assertEquals("1.2M", ModelUse.tokens(1_200_000))
    }

    @Test
    fun pillTakesHerdrsFieldsFirst() {
        val use = ModelUse.filled(124_000, "claude-opus-5-5", null, null)
        assertEquals(ModelPill("Opus 5.5", 62, use), ModelPill.of(emptyMap(), use))
        val reported = assertNotNull(ModelPill.of(mapOf("model" to "claude-sonnet-5", "context" to "41%"), use))
        assertEquals("Sonnet 5", reported.title)
        assertEquals(41, reported.percent)
        assertNull(ModelPill.of(mapOf("summary" to "x"), null))
    }

    @Test
    fun claudeTranscriptRecordsModelUseAndEffort() {
        val t = ClaudeTranscript()
        t.feed("""{"type":"assistant","uuid":"a1","effort":"high","message":{"role":"assistant","model":"claude-opus-5-5","content":[{"type":"text","text":"hi"}],"usage":{"input_tokens":4,"cache_creation_input_tokens":1200,"cache_read_input_tokens":122796,"output_tokens":9}}}""")
        // A subagent's request fills its own context, not this one's.
        t.feed("""{"type":"assistant","uuid":"a2","isSidechain":true,"message":{"role":"assistant","model":"claude-haiku-5-5","content":[],"usage":{"input_tokens":190000}}}""")
        val use = assertNotNull(t.use)
        assertEquals("claude-opus-5-5", use.model)
        assertEquals("high", use.effort)
        assertEquals(124_000L, use.used)
        assertEquals(62, use.percent)
        assertEquals(listOf("hi"), t.items().map { (it as com.makefunstuff.herdr.core.chat.ChatItem.Agent).text })
    }

    @Test
    fun codexTranscriptStatesItsWindow() {
        val t = CodexTranscript()
        t.feed("""{"type":"turn_context","payload":{"model":"gpt-5.5","effort":"medium"}}""")
        t.feed("""{"type":"event_msg","payload":{"type":"token_count","info":{"last_token_usage":{"total_tokens":51800},"model_context_window":258400}}}""")
        val use = assertNotNull(t.use)
        assertEquals("GPT-5.5", use.title)
        assertEquals("medium", use.effort)
        assertEquals(20, use.percent)
        assertFalse(use.windowAssumed)
    }

    @Test
    fun piTranscriptCountsTokensWithoutAWindow() {
        val t = PiTranscript()
        t.feed("""{"type":"message","id":"m1","message":{"role":"assistant","model":"deepseek-chat","content":[{"type":"text","text":"ok"}],"usage":{"totalTokens":12000}}}""")
        assertEquals(12_000L, t.use?.used)
        assertNull(t.use?.percent)
    }

    @Test
    fun agentTokensAcceptAnyPrimitive() {
        val a = Json { ignoreUnknownKeys = true }.decodeFromString(
            AgentInfo.serializer(),
            """{"agent_status":"working","workspace_id":"w","tab_id":"t","pane_id":"p","tokens":{"progress":"0%","context":62,"done":false,"nested":{"x":1}}}""",
        )
        assertEquals(mapOf("progress" to "0%", "context" to "62", "done" to "false"), a.tokens)
    }
}

class CompletionsTest {
    @Test
    fun slashOnlyAtTheStart() {
        assertEquals(Completions.Query(Completions.Kind.Command, "co", 0, 3), Completions.at("/co", 3))
        assertEquals(Completions.Query(Completions.Kind.Command, "", 0, 1), Completions.at("/", 1))
        // Past the command's name, mid-message, or a path: no menu.
        assertNull(Completions.at("/compact now", 12))
        assertNull(Completions.at("run /co", 7))
        assertNull(Completions.at("/home/demo", 10))
        assertNull(Completions.at("", 0))
    }

    @Test
    fun atAnywhereAtAWordsStart() {
        val q = assertNotNull(Completions.at("Look at @mach", 13))
        assertEquals(Completions.Kind.File, q.kind)
        assertEquals("mach", q.text)
        assertEquals(8, q.start)
        // The cursor inside the word: the whole word is replaced.
        assertEquals(Completions.Query(Completions.Kind.File, "ma", 0, 5), Completions.at("@mach it", 3))
        assertNull(Completions.at("mail@example.com", 16))
        // A selection completes nothing.
        assertNull(Completions.at("@mach", 1, 5))
    }

    @Test
    fun acceptReplacesTheWordAndMovesTheCursor() {
        val q = assertNotNull(Completions.at("Look at @mach please", 13))
        val (text, cursor) = Completions.accept("Look at @mach please", q, "@app/MachineCard.kt")
        assertEquals("Look at @app/MachineCard.kt please", text)
        assertEquals("Look at @app/MachineCard.kt ".length, cursor)
        val (cmd, at) = Completions.accept("/co", assertNotNull(Completions.at("/co", 3)), "/compact")
        assertEquals("/compact ", cmd)
        assertEquals(9, at)
    }

    @Test
    fun filesRankAsTheWebUiDoes() {
        val files = listOf("README.md", "app/ui/machines/MachineCard.kt", "app/ui/machines/MachinesScreen.kt", "core/host/HostSession.kt", "docs/DESIGN.md")
        assertEquals(listOf("app/ui/machines/MachineCard.kt", "app/ui/machines/MachinesScreen.kt"), Completions.files(files, "machine").take(2))
        // Letters in order in the file's name.
        assertEquals("core/host/HostSession.kt", Completions.files(files, "hss").first())
        assertTrue(Completions.files(files, "zzz").isEmpty())
        assertEquals(files.take(2), Completions.files(files, "", limit = 2))
    }

    @Test
    fun commandsStartingFirst() {
        val all = AgentCommands.merge("claude", listOf(AgentCommand("recompile", "x", AgentCommand.Source.User)))
        val found = Completions.commands(all, "com")
        assertEquals("compact", found.first().name)
        assertTrue(found.any { it.name == "recompile" })
        assertTrue(found.indexOfFirst { it.name == "recompile" } > found.indexOfFirst { it.name == "compact" })
    }
}

class AgentCommandsTest {
    @Test
    fun builtinsPerAgent() {
        assertTrue(AgentCommands.builtins("claude").any { it.name == "compact" && it.source == AgentCommand.Source.Builtin })
        assertTrue(AgentCommands.builtins("codex").any { it.name == "diff" })
        assertTrue(AgentCommands.builtins("gemini").isEmpty())
        assertFalse(AgentCommands.knows("gemini"))
    }

    @Test
    fun parsesTheListing() {
        val out = """
            |${AgentCommands.MARK}	project	command	ship
            |---
            |description: "Run the checks, commit and open a PR"
            |---
            |body
            |
            |${AgentCommands.MARK}	user	command	git/sync
            |# Pull and rebase on main
            |
            |${AgentCommands.MARK}	skill	skill	screens
            |---
            |name: screen-previews
            |description: Render the screens
            |---
        """.trimMargin()
        val found = AgentCommands.parse("claude", out)
        assertEquals(
            listOf(
                AgentCommand("ship", "Run the checks, commit and open a PR", AgentCommand.Source.Project),
                AgentCommand("git:sync", "Pull and rebase on main", AgentCommand.Source.User),
                AgentCommand("screen-previews", "Render the screens", AgentCommand.Source.Skill),
            ),
            found,
        )
        assertEquals("prompts:ship", AgentCommands.parse("codex", "${AgentCommands.MARK}\tuser\tprompt\tship\nx\n").single().name)
        assertEquals("skill:screens", AgentCommands.parse("pi", "${AgentCommands.MARK}\tskill\tskill\tscreens\n").single().name)
    }

    /** Runs a command as the SSH connection would, in a real shell, with [home] as `$HOME`. */
    private fun sh(command: String, home: File): CommandResult {
        val p = ProcessBuilder("sh", "-c", command).apply { environment()["HOME"] = home.path; environment().remove("PI_CODING_AGENT_DIR") }.start()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor(20, TimeUnit.SECONDS)
        return CommandResult(p.exitValue(), out, err)
    }

    @Test
    fun listsTheMachinesOwnCommandsInAShell() {
        val home = Files.createTempDirectory("home").toFile()
        val project = File(home, "proj").apply { mkdirs() }
        File(home, ".claude/commands/git").mkdirs()
        File(home, ".claude/commands/git/sync.md").writeText("# Pull and rebase\n")
        File(project, ".claude/commands").mkdirs()
        File(project, ".claude/commands/ship.md").writeText("---\ndescription: Ship it\n---\nGo.\n")
        File(home, ".claude/skills/previews").mkdirs()
        File(home, ".claude/skills/previews/SKILL.md").writeText("---\nname: screen-previews\ndescription: Look at the screens\n---\n")
        try {
            val all = runBlocking { AgentCommands.Client { c, _ -> sh(c, home) }.commands("claude", project.path) }
            assertTrue(all.any { it.name == "compact" })
            assertEquals("Pull and rebase", all.single { it.name == "git:sync" }.description)
            assertEquals(AgentCommand.Source.Project, all.single { it.name == "ship" }.source)
            assertEquals("Look at the screens", all.single { it.name == "screen-previews" }.description)
            assertEquals(all.map { it.name }.sorted(), all.map { it.name })
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun listsTheFolderInAShell() {
        val dir = Files.createTempDirectory("folder").toFile()
        File(dir, "src").mkdirs()
        File(dir, "src/Main.kt").writeText("x")
        File(dir, "node_modules/pkg").mkdirs()
        File(dir, "node_modules/pkg/index.js").writeText("x")
        try {
            val client = AgentCommands.Client { c, _ -> sh(c, dir) }
            // Not a checkout: walked, leaving node_modules out.
            assertEquals(listOf("src/Main.kt"), runBlocking { client.files(dir.path) })
            // A checkout: what Git knows, new files too, ignored ones not.
            val git = ProcessBuilder("git", "init", "-q", dir.path).start()
            if (git.waitFor() == 0) {
                File(dir, ".gitignore").writeText("node_modules/\n")
                assertEquals(listOf(".gitignore", "src/Main.kt"), runBlocking { client.files(dir.path) }.sorted())
            }
            assertTrue(runBlocking { client.files(File(dir, "missing").path) }.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}

class HeldTest {
    @Test
    fun holdsOnlyWhileANamedAgentWorks() {
        assertTrue(Held.holds(AgentStatus.Working, named = true))
        assertFalse(Held.holds(AgentStatus.Working, named = false))
        assertFalse(Held.holds(AgentStatus.Idle, named = true))
        assertFalse(Held.holds(AgentStatus.Blocked, named = true))
    }

    @Test
    fun sendNowWaitsForAQuestion() {
        assertTrue(Held.canSend(AgentStatus.Working, sending = false))
        assertFalse(Held.canSend(AgentStatus.Blocked, sending = false))
        assertFalse(Held.canSend(AgentStatus.Done, sending = true))
    }

    @Test
    fun captionSaysWhatItWaitsFor() {
        assertEquals("Held until claude is ready", Held.caption(AgentStatus.Working, 1, "claude"))
        assertEquals("Held until claude is ready · 2 messages", Held.caption(AgentStatus.Working, 2, "claude"))
        assertEquals("claude is ready: review and send", Held.caption(AgentStatus.Done, 1, "claude"))
        assertTrue(Held.ready(AgentStatus.Idle))
        assertFalse(Held.ready(AgentStatus.Working))
    }

    @Test
    fun quickRepliesAreCleaned() {
        assertFalse(QuickReplies().on)
        assertEquals(QuickReplies.DEFAULT, QuickReplies().shown)
        assertEquals(listOf("go on", "test"), QuickReplies(replies = listOf("  go   on ", "", "test", "go on")).shown)
    }
}

class HeldDeliveredTest {
    private fun user(id: String, text: String) = com.makefunstuff.herdr.core.chat.ChatItem.User(id, text)
    private fun held(id: String, text: String, known: List<String> = emptyList()) = com.makefunstuff.herdr.core.chat.HeldMessage(id, text, 0, known)

    @Test
    fun heldWordsTheAgentAlreadyHasStopWaiting() {
        // Shown as a new user message, or queued by the agent: delivered.
        assertEquals(setOf("h1"), Held.delivered(listOf(held("h1", "加入支持", known = listOf("u1"))), listOf(user("u1", "hi"), user("u2", "加入支持")), emptyList()))
        assertEquals(setOf("h1"), Held.delivered(listOf(held("h1", "run the tests")), emptyList(), listOf("run the tests")))
        // A copy the chat already showed when it was held is an older message.
        assertEquals(emptySet(), Held.delivered(listOf(held("h1", "continue", known = listOf("u1"))), listOf(user("u1", "continue")), emptyList()))
        // Two held alike, one copy seen: only one goes.
        assertEquals(setOf("h1"), Held.delivered(listOf(held("h1", "continue"), held("h2", "continue")), listOf(user("u2", "continue")), emptyList()))
    }
}
