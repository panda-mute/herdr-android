package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentSessionRef
import com.makefunstuff.herdr.core.chat.BlockedPrompt
import com.makefunstuff.herdr.core.chat.HintKey
import com.makefunstuff.herdr.core.chat.OptionRole
import com.makefunstuff.herdr.core.chat.PromptTag
import com.makefunstuff.herdr.core.chat.ChatItem
import com.makefunstuff.herdr.core.chat.ClaudeTranscript
import com.makefunstuff.herdr.core.chat.CodexTranscript
import com.makefunstuff.herdr.core.chat.PiTranscript
import com.makefunstuff.herdr.core.chat.Subagents
import com.makefunstuff.herdr.core.chat.ToolState
import com.makefunstuff.herdr.core.chat.TranscriptFollower
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockedPromptTest {
    private fun screen(name: String) = javaClass.getResource("/screens/$name")!!.readText()

    @Test
    fun claudePermission() {
        val p = assertNotNull(BlockedPrompt.parse(screen("claude-permission.txt")))
        assertEquals("Do you want to proceed?", p.question)
        assertEquals(listOf("Yes", "Yes, and always allow access to /tmp/claude-1000/-home-dev--herdr-worktrees-herdr-android-work-2-replace-agent-terminals-with-n/518745e3-9052-4b3c-81ca-5ddd766812d8/scratchpad/proj from this project", "No"), p.options.map { it.label })
        assertEquals(listOf(1, 2, 3), p.options.map { it.number })
        assertEquals(0, p.selected)
        assertTrue("touch b.txt" in p.details, p.details.toString())
        assertTrue("Bash command" in p.details)
        assertEquals("Esc to cancel · Tab to amend", p.hint)
        assertEquals(listOf("down", "down", "enter"), p.keysFor(2))
        assertEquals(listOf("enter"), p.keysFor(0))
    }

    @Test
    fun claudesTipIsNoDetail() {
        // Claude Code 2.1.3xx puts an ad for auto mode between "Bash command" and the command.
        val p = assertNotNull(BlockedPrompt.parse(screen("claude-permission-tip.txt")))
        assertEquals("Do you want to proceed?", p.question)
        assertEquals(listOf("Bash command", "Create empty file in home directory", "touch ~/herdr-approve-claude2.txt"), p.details)
        assertEquals(4, p.options.size)
    }

    @Test
    fun codexEscalation() {
        val p = assertNotNull(BlockedPrompt.parse(screen("codex-permission-escalated.txt")))
        assertEquals("Reason: May I create ~/herdr-approve-codex.txt outside the workspace as requested?", p.question)
        assertTrue("$ touch ~/herdr-approve-codex.txt" in p.details, p.details.toString())
        assertEquals(listOf("Yes", "Always", "TellInstead"), p.options.indices.map { p.role(it).name })
    }

    @Test
    fun claudeTrustFolder() {
        val p = assertNotNull(BlockedPrompt.parse(screen("claude-trust.txt")))
        assertEquals(listOf("No, exit", "Yes, I trust this folder"), p.options.map { it.label })
        assertEquals(0, p.selected)
        assertTrue(p.question.startsWith("Quick safety check: Is this a project you created or one you trust?"), p.question)
        assertEquals(listOf("down", "enter"), p.keysFor(1))
    }

    @Test
    fun codexPermission() {
        val p = assertNotNull(BlockedPrompt.parse(screen("codex-permission.txt")))
        assertEquals("Reason: Allow me to create c.txt containing hi in the current workspace?", p.question)
        assertEquals(3, p.options.size)
        assertEquals("Yes, proceed (y)", p.options[0].label)
        assertEquals("No, and tell Codex what to do differently (esc)", p.options[2].label)
        assertEquals("Yes, and don't ask again for commands that start with `printf 'hi' > c.txt` (p)", p.options[1].label)
        assertNull(p.options[1].description)
        assertTrue("$ printf 'hi' > c.txt" in p.details, p.details.toString())
        assertTrue(p.details.first().startsWith("Would you like to run"), p.details.toString())
        assertEquals("Press enter to confirm or esc to cancel", p.hint)
    }

    @Test
    fun codexTrust() {
        val p = assertNotNull(BlockedPrompt.parse(screen("codex-trust.txt")))
        assertEquals(listOf("Trust and continue", "Back to Agent Command Center"), p.options.map { it.label })
        assertTrue(p.question.startsWith("Trust this folder?"), p.question)
    }

    @Test
    fun piSelectList() {
        val screen = " Allow the extension to run git push?\n\n  Allow once\n→ Always allow\n  Deny\n\n ↑↓ navigate · enter select\n"
        val p = assertNotNull(BlockedPrompt.parse(screen))
        assertEquals("Allow the extension to run git push?", p.question)
        assertEquals(listOf("Allow once", "Always allow", "Deny"), p.options.map { it.label })
        assertEquals(1, p.selected)
        assertEquals(listOf("up", "enter"), p.keysFor(0))
    }

    @Test
    fun noMenu() {
        assertNull(BlockedPrompt.parse("just some output\nand more\n"))
    }

    @Test
    fun rolesAndShortLabels() {
        val claude = assertNotNull(BlockedPrompt.parse(screen("claude-permission.txt")))
        assertEquals(listOf(OptionRole.Yes, OptionRole.Always, OptionRole.No), claude.options.indices.map { claude.role(it) })
        assertTrue(claude.shortLabel(1).startsWith("Always allow access to /tmp/"), claude.shortLabel(1))
        val codex = assertNotNull(BlockedPrompt.parse(screen("codex-permission.txt")))
        assertEquals(listOf(OptionRole.Yes, OptionRole.Always, OptionRole.TellInstead), codex.options.indices.map { codex.role(it) })
        assertEquals(listOf("Yes, proceed", "Always for `printf 'hi' > c.txt`", "No, tell it…"), codex.options.indices.map { codex.shortLabel(it) })
        val demo = assertNotNull(BlockedPrompt.parse(DEMO))
        // The command stays a line of its own under Claude's tool header.
        assertEquals(listOf("Bash command", "npm run db:migrate", "Apply the new migration"), demo.details)
        assertEquals("Always for npm run", demo.shortLabel(1))
        assertEquals(OptionRole.TellInstead, demo.role(2))
        // The yes is never the permanent one, so a notification's Approve can't press it.
        val pi = assertNotNull(BlockedPrompt.parse(" Allow it?\n\n  Always allow\n→ Allow once\n  Deny\n"))
        assertEquals(1, pi.approveIndex)
        assertEquals(2, pi.denyIndex)
        val trust = assertNotNull(BlockedPrompt.parse(screen("claude-trust.txt")))
        assertEquals(listOf(OptionRole.No, OptionRole.Yes), trust.options.indices.map { trust.role(it) })
        // Claude's edit permission: "during this session" is as permanent as "always" while the session lasts.
        val edits = assertNotNull(BlockedPrompt.parse(" Edit file\n   src/a.kt\n\n Do you want to make this edit to a.kt?\n ❯ 1. Yes\n   2. Yes, allow all edits during this session (shift+tab)\n   3. No, and tell Claude what to do differently (esc)\n"))
        assertEquals(listOf(OptionRole.Yes, OptionRole.Always, OptionRole.TellInstead), edits.options.indices.map { edits.role(it) })
        assertEquals("Yes, for this session", edits.shortLabel(1))
        // Two options, an always and a no: not a yes-or-no question, and the always is never the yes.
        val alwaysOrNo = assertNotNull(BlockedPrompt.parse(" Allow it?\n\n→ Always allow\n  Deny\n"))
        assertEquals(listOf(OptionRole.Always, OptionRole.No), alwaysOrNo.options.indices.map { alwaysOrNo.role(it) })
        assertNull(alwaysOrNo.approveIndex)
    }

    @Test
    fun theFootersKeysBecomeButtons() {
        val claude = assertNotNull(BlockedPrompt.parse(screen("claude-permission.txt")))
        assertEquals(listOf(HintKey("Amend", "tab"), HintKey("Cancel", "esc")), claude.hintKeys)
        val codex = assertNotNull(BlockedPrompt.parse(screen("codex-permission.txt")))
        assertEquals(listOf(HintKey("Cancel", "esc")), codex.hintKeys)
        assertEquals(emptyList(), assertNotNull(BlockedPrompt.parse(" Go?\n\n→ Yes\n  No\n\n ↑↓ navigate · enter select\n")).hintKeys)
        val more = assertNotNull(BlockedPrompt.parse(" Go?\n\n❯ 1. Yes\n  2. No\n\n ctrl+e to explain · shift+tab to approve all · esc to cancel\n"))
        assertEquals(listOf(HintKey("Explain", "ctrl+e"), HintKey("Approve all", "shift+tab"), HintKey("Cancel", "esc")), more.hintKeys)
    }

    @Test
    fun actionTags() {
        fun tag(screen: String) = PromptTag.of(assertNotNull(BlockedPrompt.parse(screen)))
        fun bash(command: String) = " Bash command\n   $command\n\n Do you want to proceed?\n ❯ 1. Yes\n   2. No\n\n Esc to cancel\n"
        assertEquals(PromptTag("runs a migration", PromptTag.Risk.Caution), tag(DEMO))
        assertEquals("pushes", tag(bash("git push origin main"))?.text)
        assertEquals(PromptTag("force-pushes", PromptTag.Risk.Danger), tag(bash("git push --force origin main")))
        assertEquals(PromptTag("deletes files", PromptTag.Risk.Danger), tag(bash("rm -rf build/")))
        assertEquals("deletes data", tag(bash("psql -c 'DROP TABLE jobs'"))?.text)
        assertEquals("discards changes", tag(bash("git reset --hard HEAD~1"))?.text)
        assertEquals(PromptTag("discards changes", PromptTag.Risk.Danger), tag(bash("git branch -D old")))
        // -d deletes only a merged branch: just a command.
        assertEquals(PromptTag("runs a command", PromptTag.Risk.Plain), tag(bash("git branch -d old")))
        assertEquals("installs packages", tag(bash("npm install left-pad"))?.text)
        assertEquals("runs a command", tag(bash("ls -la"))?.text)
        assertEquals("runs a command", tag(screen("claude-permission.txt"))?.text)
        assertEquals("trusts this folder", tag(screen("claude-trust.txt"))?.text)
        // A file the agent writes, even into migrations/, is an edit, not a migration.
        assertEquals("edits a file", tag(" Create file\n migrations/0042_jobs.sql\n\n Do you want to create 0042_jobs.sql?\n ❯ 1. Yes\n   2. No\n")?.text)
        assertNull(tag(CLAUDE_QUESTION))
    }

    @Test
    fun claudeQuestionForm() {
        val p = assertNotNull(BlockedPrompt.parse(CLAUDE_QUESTION))
        assertEquals("Which evaluation dataset should we use?", p.question)
        assertEquals(listOf("LM-O", "YCB-V", "T-LESS", "Type something.", "Chat about this"), p.options.map { it.label })
        assertEquals("Occlusion benchmark.", p.options[0].description)
        assertEquals(null, p.options[3].description)
        assertEquals(listOf(OptionRole.Other, OptionRole.Other, OptionRole.Other, OptionRole.Custom, OptionRole.Chat), p.options.indices.map { p.role(it) })
        assertEquals(3, p.customIndex)
        assertEquals(false, p.multiSelect)
        assertEquals(emptyList(), p.steps)
        assertEquals(listOf(HintKey("Cancel", "esc")), p.hintKeys)
        assertEquals(listOf("down", "enter"), p.keysFor(1))
        assertEquals(listOf("down", "down", "down"), p.keysToType())
        assertEquals(listOf("down", "down", "down", "down", "enter"), p.keysFor(4))
    }

    @Test
    fun claudeFormOfSeveralQuestions() {
        val first = assertNotNull(BlockedPrompt.parse(TABS))
        assertEquals("Which way should the PR go?", first.question)
        assertEquals(listOf("Route", "Author", "Submit"), first.steps.map { it.label })
        assertEquals(listOf(true, false, false), first.steps.map { it.current })
        assertEquals(listOf(false, false, false), first.steps.map { it.answered })
        assertTrue(first.steps.last().submit)

        val review = assertNotNull(BlockedPrompt.parse(REVIEW))
        assertEquals("Ready to submit your answers?", review.question)
        assertEquals(listOf("Submit answers", "Cancel"), review.options.map { it.label })
        assertEquals(listOf(true, true, false), review.steps.map { it.answered })
        assertEquals(listOf(false, false, true), review.steps.map { it.current })
    }

    @Test
    fun claudeMultipleChoice() {
        val sets = assertNotNull(BlockedPrompt.parse(SETS))
        assertTrue(sets.multiSelect)
        assertEquals(listOf("Route", "Sets", "Submit"), sets.steps.map { it.label })
        assertEquals(listOf(false, true, false), sets.steps.map { it.current })
        assertEquals(listOf("LM-O", "YCB-V", "T-LESS", "Type something", "Chat about this"), sets.options.map { it.label })
        assertEquals(listOf(false, false, false, null, null), sets.options.map { it.checked })
        assertEquals("Include the LM-O dataset.", sets.options[0].description)
        // Each wanted box ticked with Enter, then → to the next step (as the web UI answers it).
        assertEquals(listOf("enter", "down", "down", "enter", "right"), sets.keysForTicks(setOf(0, 2)))

        // Claude Code 2.1.296: ticked rows drawn [✔], and a Submit row the cursor stops on.
        fun screen(at: String) = listOf(
            "←  ☒ Pick  ✔ Submit  →",
            "Which ones?",
            "${if (at == "A") "❯" else " "} 1. [✔] Option A",
            "         First description",
            "${if (at == "B") "❯" else " "} 2. [✔] Option B",
            "         Second description",
            "  3. [ ] Type something",
            "${if (at == "Submit") "❯" else " "}    Submit",
            "─".repeat(40),
            "${if (at == "Chat") "❯" else " "} 4. Chat about this",
            "Enter to select · ↑/↓ to navigate · Esc to cancel",
        ).joinToString("\n")
        val b = assertNotNull(BlockedPrompt.parse(screen("B")))
        assertEquals(listOf(true, true, null, null), b.options.map { it.checked })
        assertEquals(listOf("enter", "right"), b.keysForTicks(setOf(0)))
        assertEquals(listOf("right"), b.keysForTicks(setOf(0, 1)))
        val onSubmit = assertNotNull(BlockedPrompt.parse(screen("Submit")))
        assertEquals(-1, onSubmit.selected)
        // Submit → Type something → Option B
        assertEquals(listOf("up", "up", "enter", "right"), onSubmit.keysForTicks(setOf(0)))
        val onChat = assertNotNull(BlockedPrompt.parse(screen("Chat")))
        // Chat about this → Submit → Type something → Option B → Option A
        assertEquals(listOf("up", "up", "up", "up", "enter", "right"), onChat.keysForTicks(setOf(1)))
    }

    @Test
    fun aGenericMultipleChoiceTogglesWithSpace() {
        val p = assertNotNull(BlockedPrompt.parse(" Which checks?\n\n→ [x] Lint\n  [ ] Tests\n  [ ] Build\n\n ↑↓ move · space toggle · enter submit · esc cancel\n"))
        assertTrue(p.multiSelect)
        assertEquals(listOf("Lint", "Tests", "Build"), p.options.map { it.label })
        assertEquals(listOf(true, false, false), p.options.map { it.checked })
        assertEquals(listOf("down", "space", "enter"), p.keysForTicks(setOf(0, 1)))
    }

    @Test
    fun sameQuestion() {
        val a = assertNotNull(BlockedPrompt.parse(DEMO))
        // The cursor moving is still the same question.
        assertTrue(a.same(BlockedPrompt.parse(DEMO.replace("❯ 1. Yes", "  1. Yes").replace("   2. Yes,", " ❯ 2. Yes,"))))
        // Claude asks "Do you want to proceed?" with the same options for every command: another command is another question.
        assertEquals(false, a.same(BlockedPrompt.parse(DEMO.replace("npm run db:migrate", "npm run db:reset --force"))))
        assertEquals(false, a.same(BlockedPrompt.parse(DEMO.replace("Do you want to proceed?", "Do you want to seed?"))))
        assertEquals(false, a.same(null))
    }

    private companion object {
        val DEMO = """
            ────────────────────────────────────────────────────────
             Bash command
               npm run db:migrate
               Apply the new migration
            ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌
             Do you want to proceed?
             ❯ 1. Yes
               2. Yes, and don't ask again for npm run commands
               3. No, and tell Claude what to do differently

             Esc to cancel · Tab to amend
        """.trimIndent()

        // Claude Code's AskUserQuestion screens, as the herdr web UI's tests have them (captured from 2.1.280).
        val CLAUDE_QUESTION = """
            ☐ Dataset

            Which evaluation dataset should we use?

            ❯ 1. LM-O
                 Occlusion benchmark.
              2. YCB-V
                 Household objects.
              3. T-LESS
                 Texture-less objects.
              4. Type something.
            ────────────────────────────
              5. Chat about this

            Enter to select · ↑/↓ to navigate · Esc to cancel
        """.trimIndent()

        val TABS = """
            ←  ☐ Route  ☐ Author  ✔ Submit  →
            Which way should the PR go?
            ❯ 1. Log in as owner
                 Authenticate as the repository owner and open the PR directly on the repo.
              2. Fork
                 Push the branch to a fork and open the PR from there.
              3. Type something.
            ────────────────────────────────────────
              4. Chat about this
            Enter to select · Tab/Arrow keys to navigate · Esc to cancel
        """.trimIndent()

        val SETS = """
            ←  ☒ Route  ☐ Sets  ✔ Submit  →
            Which datasets?
            ❯ 1. [ ] LM-O
                     Include the LM-O dataset.
              2. [ ] YCB-V
                     Include the YCB-V dataset.
              3. [ ] T-LESS
                     Include the T-LESS dataset.
              4. [ ] Type something
                 Next
            ────────────────────────────────────────
              5. Chat about this
            Enter to select · Tab/Arrow keys to navigate · Esc to cancel
        """.trimIndent()

        val REVIEW = """
            ←  ☒ Route  ☒ Author  ✔ Submit  →
            Review your answers
             ● Which way should the PR go?
               → Log in as owner
             ● Who should author the commits?
               → Repo owner
            Ready to submit your answers?
            ❯ 1. Submit answers
              2. Cancel
        """.trimIndent()
    }
}

class TranscriptTest {
    @Test
    fun claude() {
        val t = ClaudeTranscript()
        listOf(
            """{"type":"permission-mode","permissionMode":"auto"}""",
            """{"type":"user","uuid":"u1","isSidechain":false,"message":{"role":"user","content":"inspect my nas"}}""",
            """{"type":"user","uuid":"m1","isMeta":true,"message":{"role":"user","content":"<local-command-caveat>x</local-command-caveat>"}}""",
            """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":[{"type":"thinking","thinking":""}]}}""",
            """{"type":"assistant","uuid":"a2","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"df -h","description":"Check space"}}]}}""",
            """{"type":"user","uuid":"r1","message":{"role":"user","content":[{"type":"tool_result","content":"97% used","is_error":false,"tool_use_id":"toolu_1"}]}}""",
            """{"type":"assistant","uuid":"a3","message":{"role":"assistant","content":[{"type":"text","text":"The volume is **97%** full."}]}}""",
            """{"type":"assistant","uuid":"a4","isSidechain":true,"message":{"role":"assistant","content":[{"type":"text","text":"subagent"}]}}""",
            """{"type":"user","uuid":"u2","message":{"role":"user","content":"<command-name>/model</command-name>\n<command-message>model</command-message>\n<command-args>opus</command-args>"}}""",
            """{"type":"user","uuid":"u3","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]}}""",
            """{"type":"user","uuid":"u4","message":{"role":"user","content":"<task-notification>done</task-notification>"}}""",
            "not json",
        ).forEach(t::feed)
        val items = t.items()
        assertEquals(listOf("inspect my nas"), items.filterIsInstance<ChatItem.User>().take(1).map { it.text })
        val tool = items.filterIsInstance<ChatItem.Tool>().single()
        assertEquals("Check space", tool.summary)
        assertTrue(tool.input!!.contains("command: df -h"))
        assertEquals("97% used", tool.output)
        assertEquals(ToolState.Done, tool.state)
        assertEquals("The volume is **97%** full.", items.filterIsInstance<ChatItem.Agent>().single().text)
        assertEquals("/model opus", (items[3] as ChatItem.User).text)
        assertIs<ChatItem.Notice>(items[4])
        assertEquals(5, items.size)
    }

    @Test
    fun codexItems() {
        val t = CodexTranscript()
        listOf(
            """{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"# AGENTS.md instructions"}]}}""",
            """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"UserMessage","id":"u1","content":[{"type":"text","text":"upgrade pi"}]}}}""",
            """{"type":"response_item","payload":{"type":"function_call","name":"exec_command","arguments":"{\"cmd\":\"ls\"}","call_id":"c1"}}""",
            """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"CommandExecution","id":"e1","command":["/usr/bin/bash","-lc","which pi"],"status":"completed","exit_code":0,"aggregated_output":"/usr/bin/pi\n"}}}""",
            """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"FileChange","id":"f1","changes":{"/a/b.md":{"type":"add","content":"x"}},"status":"completed"}}}""",
            """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"AgentMessage","id":"m1","content":[{"type":"Text","text":"Pi is upgraded."}]}}}""",
            """{"type":"event_msg","payload":{"type":"turn_aborted","reason":"interrupted"}}""",
        ).forEach(t::feed)
        val items = t.items()
        assertEquals("upgrade pi", (items[0] as ChatItem.User).text)
        // Raw function calls are only used by older Codex versions that don't log items.
        assertEquals("which pi", (items[1] as ChatItem.Tool).summary)
        assertEquals("+ /a/b.md", (items[2] as ChatItem.Tool).summary)
        assertEquals("Pi is upgraded.", (items[3] as ChatItem.Agent).text)
        assertIs<ChatItem.Notice>(items[4])
        assertEquals(5, items.size)
    }

    @Test
    fun codexLegacy() {
        val t = CodexTranscript()
        listOf(
            """{"type":"event_msg","payload":{"type":"user_message","message":"fix the build"}}""",
            """{"type":"response_item","payload":{"type":"function_call","name":"shell","arguments":"{\"command\":[\"bash\",\"-lc\",\"make\"]}","call_id":"c1"}}""",
            """{"type":"response_item","payload":{"type":"function_call_output","call_id":"c1","output":"{\"output\":\"ok\",\"metadata\":{}}"}}""",
            """{"type":"event_msg","payload":{"type":"agent_message","message":"Fixed."}}""",
        ).forEach(t::feed)
        val items = t.items()
        assertEquals(listOf("fix the build"), items.filterIsInstance<ChatItem.User>().map { it.text })
        val tool = items.filterIsInstance<ChatItem.Tool>().single()
        assertEquals("make", tool.summary)
        assertEquals("ok", tool.output)
        assertEquals("Fixed.", items.filterIsInstance<ChatItem.Agent>().single().text)
    }

    @Test
    fun pi() {
        val t = PiTranscript()
        listOf(
            """{"type":"session","version":3,"id":"s1","cwd":"/x"}""",
            """{"type":"message","id":"m0","message":{"role":"system","content":"sys"}}""",
            """{"type":"message","id":"m1","message":{"role":"user","content":[{"type":"text","text":"make it nicer"},{"type":"image","data":"blob"}]}}""",
            """{"type":"message","id":"m2","message":{"role":"assistant","content":[{"type":"thinking","thinking":"hm"},{"type":"text","text":"Looking."},{"type":"toolCall","id":"t1","name":"read","arguments":{"path":"/x/game.js"}}]}}""",
            """{"type":"message","id":"m3","message":{"role":"toolResult","toolCallId":"t1","toolName":"read","content":[{"type":"text","text":"code"}],"isError":true}}""",
            """{"type":"message","id":"m4","message":{"role":"assistant","content":[],"stopReason":"aborted"}}""",
        ).forEach(t::feed)
        val items = t.items()
        val user = items[0] as ChatItem.User
        assertEquals("make it nicer", user.text)
        assertEquals(1, user.images)
        assertEquals("hm", (items[1] as ChatItem.Reasoning).text)
        assertEquals("Looking.", (items[2] as ChatItem.Agent).text)
        val tool = items[3] as ChatItem.Tool
        assertEquals("/x/game.js", tool.summary)
        assertEquals(ToolState.Failed, tool.state)
        assertIs<ChatItem.Notice>(items[4])
    }
}

class SubagentTest {
    private val call = """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Agent","input":{"description":"Look around","subagent_type":"Explore","prompt":"List the files"}}]}}"""

    @Test
    fun anAgentCallNamesItsSubagent() {
        val t = ClaudeTranscript()
        t.feed(call)
        t.feed("""{"type":"user","uuid":"u2","toolUseResult":{"status":"completed","agentId":"ac0858c9f3c996ee4"},"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":[{"type":"text","text":"Done."}]}]}}""")
        assertEquals("ac0858c9f3c996ee4", (t.items().single() as ChatItem.Tool).subagent)
    }

    @Test
    fun anOlderResultNamesItInItsText() {
        val t = ClaudeTranscript()
        t.feed(call)
        t.feed("""{"type":"user","uuid":"u2","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"Async agent launched.\nagentId: abc123 (internal)"}]}}""")
        val tool = t.items().single() as ChatItem.Tool
        assertEquals("abc123", tool.subagent)
        assertEquals("Async agent launched.", tool.output)
    }

    @Test
    fun otherCallsAndOddIdsNameNone() {
        val t = ClaudeTranscript()
        t.feed("""{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}""")
        t.feed("""{"type":"user","uuid":"u2","toolUseResult":{"agentId":"x1"},"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"agentId: x1"}]}}""")
        assertEquals(null, (t.items().single() as ChatItem.Tool).subagent)
        assertEquals(null, Subagents.id("../../etc", null))
    }

    @Test
    fun aSubagentsOwnFileReadsAsAChat() {
        val lines = listOf(
            """{"isSidechain":true,"agentId":"x1","type":"user","uuid":"s1","message":{"role":"user","content":"List the files"}}""",
            """{"isSidechain":true,"agentId":"x1","type":"assistant","uuid":"s2","message":{"role":"assistant","content":[{"type":"text","text":"Three files."}]}}""",
        )
        assertEquals(emptyList(), ClaudeTranscript().also { t -> lines.forEach(t::feed) }.items())
        val items = ClaudeTranscript(sidechain = true).also { t -> lines.forEach(t::feed) }.items()
        assertEquals(listOf("List the files", "Three files."), items.map { (it as? ChatItem.User)?.text ?: (it as ChatItem.Agent).text })
    }
}

class QueueTest {
    private fun q(op: String, content: String? = null, reason: String? = null) =
        """{"type":"queue-operation","operation":"$op","sessionId":"s"""" +
            (content?.let { ""","content":"$it"""" } ?: "") + (reason?.let { ""","reason":"$it"""" } ?: "") + "}"

    @Test
    fun followsClaudesQueue() {
        val t = ClaudeTranscript()
        t.feed(q("enqueue", "first"))
        t.feed(q("enqueue"))
        t.feed(q("enqueue", "second"))
        assertEquals(listOf("first", "second"), t.queued)
        // The oldest, as the next turn.
        t.feed(q("dequeue"))
        assertEquals(listOf("second"), t.queued)
        // Its own word-less entry, then the last one read mid-turn.
        t.feed(q("dequeue"))
        t.feed(q("remove", "second", "absorbed_mid_turn"))
        assertEquals(emptyList(), t.queued)
        // A tail-only read can start past an enqueue.
        t.feed(q("dequeue"))
        assertEquals(emptyList(), t.queued)
    }

    @Test
    fun aMessageReadMidTurnShowsAsYours() {
        val t = ClaudeTranscript()
        t.feed("""{"type":"attachment","uuid":"x1","attachment":{"type":"queued_command","prompt":"try the other host","commandMode":"prompt","origin":{"kind":"human"}}}""")
        t.feed("""{"type":"attachment","uuid":"x2","attachment":{"type":"queued_command","prompt":"<task-notification>done</task-notification>","commandMode":"task-notification"}}""")
        assertEquals(listOf(ChatItem.User("x1", "try the other host")), t.items())
    }
}

class TranscriptFollowerTest {
    private fun run(home: File, ref: AgentSessionRef, bytes: Int = 1_000_000, subagent: String? = null, lines: Int = 2): List<String> {
        val pb = ProcessBuilder("sh", "-c", TranscriptFollower.command(ref, bytes, subagent))
        pb.environment()["HOME"] = home.absolutePath
        pb.environment().remove("CLAUDE_CONFIG_DIR")
        pb.environment().remove("CODEX_HOME")
        val p = pb.start()
        val reader = p.inputStream.bufferedReader()
        val count = lines
        val lines = mutableListOf<String>()
        val header = reader.readLine()
        lines += header
        if (!header.startsWith("@@missing")) {
            repeat(count) { lines += reader.readLine() }
        }
        // Closing stdin is how the app ends the stream; tail must stop with it.
        p.outputStream.close()
        assertTrue(p.waitFor(5, TimeUnit.SECONDS), "follower kept running after its input closed")
        return lines
    }

    @Test
    fun findsTranscriptsAndStopsWithTheChannel() {
        val home = kotlin.io.path.createTempDirectory("home").toFile()
        val claude = File(home, ".claude/projects/-data-x").apply { mkdirs() }
        File(claude, "abc-123.jsonl").writeText("{\"a\":1}\n{\"b\":2}\n")
        val lines = run(home, AgentSessionRef("claude", "id", "abc-123"))
        assertTrue(lines[0].startsWith("@@file 16 ") && lines[0].endsWith("abc-123.jsonl"), lines[0])
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), lines.drop(1))

        val codex = File(home, ".codex/sessions/2026/10/16").apply { mkdirs() }
        File(codex, "rollout-2026-10-16T08-00-00-0199-aaa.jsonl").writeText("1\n2\n")
        assertEquals(listOf("1", "2"), run(home, AgentSessionRef("codex", "id", "0199-aaa")).drop(1))
        // Resumed later, it goes on in a newer file named after both ids; the chat reads them in order,
        // the earlier ones only as far as they fit.
        val later = File(home, ".codex/sessions/2026/10/18").apply { mkdirs() }
        val newest = File(later, "rollout-2026-10-18T08-00-00-0199-aaa_0199-ccc.jsonl").apply { writeText("3\n4\n") }
        val resumed = run(home, AgentSessionRef("codex", "id", "0199-aaa"), lines = 4)
        assertEquals("@@file 8 ${newest.absolutePath}", resumed[0])
        assertEquals(listOf("1", "2", "3", "4"), resumed.drop(1))
        assertEquals(listOf("2", "3", "4"), run(home, AgentSessionRef("codex", "id", "0199-aaa"), bytes = 6, lines = 3).drop(1))

        val pi = File(home, ".pi/agent/sessions/--x--").apply { mkdirs() }
        val piFile = File(pi, "2026-10-13T08-18-43-915Z_0199-bbb.jsonl").apply { writeText("p\nq\n") }
        assertEquals(listOf("p", "q"), run(home, AgentSessionRef("pi", "id", "0199-bbb")).drop(1))
        assertEquals(listOf("p", "q"), run(home, AgentSessionRef("pi", "path", piFile.absolutePath)).drop(1))

        assertEquals(listOf("@@missing"), run(home, AgentSessionRef("claude", "id", "nope")))
    }

    @Test
    fun findsASubagentBesideItsSession() {
        val home = kotlin.io.path.createTempDirectory("home").toFile()
        val project = File(home, ".claude/projects/-data-x").apply { mkdirs() }
        File(project, "s1.jsonl").writeText("main\nmain\n")
        File(project, "s1/subagents").mkdirs()
        File(project, "s1/subagents/agent-a1.jsonl").writeText("new\nlayout\n")
        File(project, "agent-b2.jsonl").writeText("old\nlayout\n")
        val ref = AgentSessionRef("claude", "id", "s1")
        assertEquals(listOf("new", "layout"), run(home, ref, subagent = "a1").drop(1))
        assertEquals(listOf("old", "layout"), run(home, ref, subagent = "b2").drop(1))
        assertEquals(listOf("@@missing"), run(home, ref, subagent = "c3"))
        assertFailsWith<IllegalArgumentException> { TranscriptFollower.command(ref, subagent = "../x") }
    }
}

class TranscriptFollowerCancelTest {
    /** A local `sh` standing in for the SSH channel: closing it closes the script's stdin, as sshd does. */
    private class LocalChannel(command: String, home: File) : com.makefunstuff.herdr.core.ssh.Duplex {
        val process: Process = ProcessBuilder("sh", "-c", command).also { it.environment()["HOME"] = home.absolutePath }.start()
        @Volatile var closed = false
        override val input get() = process.inputStream
        override val output get() = process.outputStream
        override fun close() {
            closed = true
            process.outputStream.close()
        }
    }

    @Test
    fun closingTheChatStopsTheRemoteTail(): Unit = kotlinx.coroutines.runBlocking {
        val home = kotlin.io.path.createTempDirectory("home").toFile()
        val dir = File(home, ".claude/projects/-x").apply { mkdirs() }
        File(dir, "s1.jsonl").writeText("""{"type":"user","uuid":"u1","message":{"role":"user","content":"hello"}}""" + "\n")
        var channel: LocalChannel? = null
        val updated = kotlinx.coroutines.CompletableDeferred<Unit>()
        val transcript = ClaudeTranscript()
        // Its own scope, so a follower that ignores cancellation fails the test instead of hanging it.
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        val job = scope.launch {
            TranscriptFollower.follow(
                { cmd -> LocalChannel(cmd, home).also { channel = it } },
                AgentSessionRef("claude", "id", "s1"),
                transcript,
            ) { if (transcript.items().isNotEmpty()) updated.complete(Unit) }
        }
        kotlinx.coroutines.withTimeout(10_000) { updated.await() }
        val ch = channel!!
        val tails = ch.process.descendants().toList()
        try {
            assertTrue(tails.isNotEmpty(), "the follower should be running tail")

            // What leaving the chat screen does.
            job.cancel()
            kotlinx.coroutines.withTimeoutOrNull(10_000) { job.join() }
            assertTrue(ch.closed, "cancelling must close the channel")
            assertTrue(ch.process.waitFor(5, TimeUnit.SECONDS), "the remote script should exit once its channel closes")
            val deadline = System.currentTimeMillis() + 5_000
            while (tails.any { it.isAlive } && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue(tails.none { it.isAlive }, "tail must not outlive the chat")
        } finally {
            tails.forEach { it.destroyForcibly() }
            ch.process.destroyForcibly()
            scope.cancel()
        }
    }
}

class MarkdownTest {
    @Test
    fun blocks() {
        val md = """
            ## Result
            The volume is **97%** full,
            with 165 GB free.

            - first `cmd`
              continued
              - nested
            1. one
            2. two

            ```bash
            df -h
            ```

            | Share | Size |
            |---|---:|
            | **homes** | 1 TB |

            > quoted
            ---
        """.trimIndent()
        val b = com.makefunstuff.herdr.core.chat.Markdown.parse(md)
        val types = b.map { it::class.simpleName }
        assertEquals(listOf("Heading", "Paragraph", "ListItem", "ListItem", "ListItem", "ListItem", "Code", "Table", "Quote", "Rule"), types)
        val para = b[1] as com.makefunstuff.herdr.core.chat.Markdown.Block.Paragraph
        assertEquals("The volume is 97% full, with 165 GB free.", para.text.joinToString("") { it.text })
        assertTrue(para.text.single { it.text == "97%" }.bold)
        val item = b[2] as com.makefunstuff.herdr.core.chat.Markdown.Block.ListItem
        assertEquals("first cmd continued", item.text.joinToString("") { it.text })
        assertEquals(1, (b[3] as com.makefunstuff.herdr.core.chat.Markdown.Block.ListItem).depth)
        assertEquals("2.", (b[5] as com.makefunstuff.herdr.core.chat.Markdown.Block.ListItem).marker)
        assertEquals("df -h", (b[6] as com.makefunstuff.herdr.core.chat.Markdown.Block.Code).text)
        assertEquals(listOf(listOf("Share", "Size"), listOf("homes", "1 TB")), (b[7] as com.makefunstuff.herdr.core.chat.Markdown.Block.Table).rows)
    }

    @Test
    fun inline() {
        val spans = com.makefunstuff.herdr.core.chat.Markdown.inline("see [the launcher](/home/x/pi) and snake_case_name, *really* https://herdr.dev.")
        assertEquals("see the launcher and snake_case_name, really https://herdr.dev.", spans.joinToString("") { it.text })
        assertEquals("/home/x/pi", spans.single { it.text == "the launcher" }.link)
        assertTrue(spans.single { it.text == "really" }.italic)
        assertEquals("https://herdr.dev", spans.single { it.text == "https://herdr.dev" }.link)
        assertEquals("2 * 3 = 6", com.makefunstuff.herdr.core.chat.Markdown.inline("2 * 3 = 6").joinToString("") { it.text })
    }
}
