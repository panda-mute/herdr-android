package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.agents.AgentKinds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentKindsTest {
    @Test
    fun namesAndAliases() {
        assertEquals("claude", AgentKinds.normalize("claude-code"))
        assertEquals("cursor", AgentKinds.normalize("cursor-agent"))
        assertEquals("agy", AgentKinds.normalize("antigravity"))
        assertEquals("qodercli", AgentKinds.normalize("qoder"))
        assertEquals("crush", AgentKinds.normalize("Crush"))
        assertNull(AgentKinds.normalize("bash"))
        assertEquals("claude", AgentKinds.fromApp("Claude Code"))
        assertEquals("gemini", AgentKinds.fromApp("gemini-cli"))
        assertEquals("kimi", AgentKinds.fromApp("kimi_cli"))
    }

    @Test
    fun commandLines() {
        assertEquals("codex", AgentKinds.fromArgv(listOf("codex", "resume", "--last")))
        assertEquals("codex", AgentKinds.fromArgv(listOf("node", "/usr/lib/node_modules/@openai/codex/bin/codex.js")))
        assertEquals("codex", AgentKinds.fromArgv(listOf("npx", "-y", "@openai/codex@0.166.0")))
        assertEquals("gemini", AgentKinds.fromArgv(listOf("bunx", "@google/gemini-cli")))
        assertEquals("claude", AgentKinds.fromArgv(listOf("/home/me/.local/share/claude/versions/2.3.1")))
        assertEquals("claude", AgentKinds.fromArgv(listOf("happy")))
        assertEquals("codex", AgentKinds.fromArgv(listOf("happy", "codex")))
        assertNull(AgentKinds.fromArgv(listOf("happy", "doctor")))
        assertEquals("claude", AgentKinds.fromArgv(listOf("ccr", "code")))
        assertEquals("kilo", AgentKinds.fromArgv(listOf("/opt/bin/kilocode")))
        assertNull(AgentKinds.fromArgv(listOf("python3", "server.py")))
        assertNull(AgentKinds.fromArgv(listOf("vim", "codex.md")))
    }
}
