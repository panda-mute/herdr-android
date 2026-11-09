package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentSessionRef
import com.makefunstuff.herdr.core.chat.ChatSearch
import com.makefunstuff.herdr.core.chat.TranscriptFollower
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Finding a transcript from a pane's pid on a Windows machine, where `pgrep` doesn't exist. */
class TranscriptWindowsTest {
    private val session = "00000000-0000-4000-8000-000000000001"

    /**
     * Sets up a Windows machine in a temp home: Git Bash's `uname`, a `powershell.exe` that prints
     * [table], the transcript with [jsonl] in it, and [sessionPid]'s Claude session file, if any. Runs
     * [block] with the home and its `bin` (first on the PATH), then removes the home.
     */
    private fun <T> onWindows(
        table: String,
        sessionPid: String?,
        crlf: Boolean = false,
        jsonl: String = "{\"a\":1}",
        block: (File, File) -> T,
    ): T {
        val home = Files.createTempDirectory("transcript-win").toFile()
        try {
            val bin = File(home, "bin").apply { mkdirs() }
            File(bin, "uname").apply { writeText("#!/bin/sh\necho MINGW64_NT-10.0-26100\n"); setExecutable(true) }
            // The real one prints CRLF line endings, and reads nothing from its stdin.
            val eol = if (crlf) "\r\n" else "\n"
            val rows = table.trim().lines().joinToString("") { "$it$eol" }
            File(bin, "powershell.exe").apply { writeText("#!/bin/sh\necho x >> \"\$HOME/powershell.calls\"\nprintf '$rows'\n"); setExecutable(true) }
            File(home, ".claude/projects/C--Users-dev-proj").apply { mkdirs() }.resolve("$session.jsonl").writeText(jsonl + "\n")
            File(home, ".claude/sessions").mkdirs()
            if (sessionPid != null) File(home, ".claude/sessions/$sessionPid.json").writeText("""{"pid":$sessionPid,"sessionId":"$session"}""")
            return block(home, bin)
        } finally {
            home.deleteRecursively()
        }
    }

    /** Runs [command] with `sh`, as the app does over SSH, and returns what it prints, giving up after 10 seconds. */
    private fun sh(home: File, bin: File, command: String): String {
        // Written to a file and run with `sh <file>`: on Windows, Java breaks the quotes of `sh -c`.
        val file = File(home, "run.sh").apply { writeText(command + "\n") }
        val out = File(home, "run.out")
        val p = ProcessBuilder("sh", file.path)
            .apply { environment().apply { put("HOME", home.path); put("PATH", "${bin.path}:/usr/bin:/bin"); remove("CLAUDE_CONFIG_DIR") } }
            .redirectErrorStream(true)
            .redirectOutput(out)
            .start()
        // Closing stdin is how the app ends the stream.
        p.outputStream.close()
        if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly().waitFor()
        return out.readText()
    }

    /**
     * Runs [TranscriptFollower.command] for the pane's pid 16744 on a Windows machine whose
     * `powershell.exe` prints [table]; [sessionPid] has the Claude session file, if any.
     * Returns the first line it prints.
     */
    private fun run(table: String, sessionPid: String?, crlf: Boolean = false): String =
        onWindows(table, sessionPid, crlf) { home, bin ->
            val ref = AgentSessionRef(kind = "pid", value = "16744", agent = "claude")
            sh(home, bin, TranscriptFollower.command(ref, 100)).lineSequence().firstOrNull() ?: ""
        }

    private fun assertFound(first: String) {
        assertTrue(first.startsWith("@@file ") && first.endsWith("/$session.jsonl"), first)
    }

    @Test
    fun findsClaudeUnderTheWindowsShell() {
        assertFound(run("16744 900\n27492 16744\n31000 27492\n", sessionPid = "27492"))
    }

    @Test
    fun findsAGrandchild() {
        assertFound(run("16744 900\n27492 16744\n31000 27492\n", sessionPid = "31000"))
    }

    @Test
    fun readsPowershellsCrlfTable() {
        assertFound(run("16744 900\n27492 16744\n", sessionPid = "27492", crlf = true))
    }

    @Test
    fun missingWithoutASessionFile() {
        assertEquals("@@missing", run("16744 900\n27492 16744\n31000 27492\n", sessionPid = null))
    }

    @Test
    fun ignoresProcessesOutsideThePanesTree() {
        // 27492 belongs to another shell, so its session isn't this pane's.
        assertEquals("@@missing", run("16744 900\n27492 5000\n", sessionPid = "27492"))
    }

    @Test
    fun aStaleParentIdCannotLoopTheWalk() {
        // Windows reuses pids: 16744's own parent id points back down at its child.
        assertFound(run("16744 31000\n27492 16744\n31000 27492\n40000 31000\n", sessionPid = "40000"))
        assertEquals("@@missing", run("16744 16744\n27492 31000\n31000 27492\n", sessionPid = null))
    }

    private val said = """{"type":"user","message":{"role":"user","content":"The Flaky upload test"},"timestamp":"2026-11-07T10:00:00Z","uuid":"u1"}"""

    /** The chat search of word [query] on a Windows machine, with [setup] run on its `bin` first. */
    private fun search(query: String, setup: (File) -> Unit = {}): List<ChatSearch.Hit> =
        onWindows("16744 900\n27492 16744\n", sessionPid = "27492", jsonl = said) { home, bin ->
            setup(bin)
            val chat = ChatSearch.Chat("p1", AgentSessionRef(kind = "pid", value = "16744", agent = "claude"))
            val stdout = sh(home, bin, ChatSearch.commands(query, listOf(chat)).single())
            ChatSearch.parse(stdout, query, mapOf("p1" to "claude"))
        }

    private fun assertFlaky(hits: List<ChatSearch.Hit>) {
        assertEquals(listOf("p1"), hits.map { it.paneId })
        assertTrue("Flaky" in hits.single().snippet, hits.single().snippet)
    }

    @Test
    fun searchFindsWordsWhenGrepAborts() {
        // Git Bash's grep aborts on -i with -F: it is killed and prints nothing.
        assertFlaky(search("flaky") { bin -> File(bin, "grep").apply { writeText("#!/bin/sh\nkill -ABRT \$\$\n"); setExecutable(true) } })
    }

    @Test
    fun searchUsesTheGrepOfTheMachine() {
        // With a grep that works the words are found by it, and the fallback isn't needed.
        assertFlaky(search("flaky"))
    }

    @Test
    fun aSearchReadsTheProcessTableOnceForAllItsChats() {
        // Three chats, each with its own pane pid: one powershell for the machine, not one per chat.
        val table = "16744 900\n27492 16744\n17000 900\n17001 17000\n18000 900\n18001 18000\n"
        onWindows(table, sessionPid = "27492", jsonl = said) { home, bin ->
            for (pid in listOf("17001", "18001")) File(home, ".claude/sessions/$pid.json").writeText("""{"pid":$pid,"sessionId":"$session"}""")
            val chats = listOf("16744" to "p1", "17000" to "p2", "18000" to "p3").map { (pid, pane) ->
                ChatSearch.Chat(pane, AgentSessionRef(kind = "pid", value = pid, agent = "claude"))
            }
            val stdout = sh(home, bin, ChatSearch.commands("flaky", chats).single())
            assertEquals(listOf("p1", "p2", "p3"), ChatSearch.parse(stdout, "flaky", chats.associate { it.paneId to "claude" }).map { it.paneId })
            assertEquals(1, File(home, "powershell.calls").readLines().size)
        }
    }

    @Test
    fun theSearchFitsTheWindowsCommandLine() {
        // Git Bash gets the command as `bash.exe -c "<command>"` with each quote written \" and the line is cut at 8191 characters.
        val chats = (1..12).map { ChatSearch.Chat("p$it", AgentSessionRef(kind = "pid", value = "${16000 + it}", agent = "claude")) }
        val line = ChatSearch.commands("flaky", chats).single().replace("\"", "\\\"")
        val room = 8191 - "\"C:\\Program Files\\Git\\bin\\bash.exe\" -c \"".length - 1
        assertTrue(line.length <= room, "the search is ${line.length} characters, the room is $room")
    }

    @Test
    fun aSearchOfManyChatsIsSplitToFitWindowsLine() {
        // A machine with a few hundred agents: several commands, each on Windows' line, and every chat in one of them.
        val chats = (1..300).map { ChatSearch.Chat("p$it", AgentSessionRef(kind = "path", value = "~/.claude/projects/C--Users-dev-proj/$session.jsonl", agent = "claude")) }
        val commands = ChatSearch.commands("flaky", chats)
        assertTrue(commands.size > 1, "${commands.size} commands")
        val room = 8191 - "\"C:\\Program Files\\Git\\bin\\bash.exe\" -c \"".length - 1
        for (command in commands) {
            val line = command.replace("\"", "\\\"")
            assertTrue(line.length <= room, "a command is ${line.length} characters, the room is $room")
        }
        val panes = commands.flatMap { c -> Regex("'(p\\d+)' 'path'").findAll(c).map { it.groupValues[1] }.toList() }
        assertEquals(chats.map { it.paneId }, panes)
    }

    @Test
    fun searchWithoutAMatchStaysEmpty() {
        assertEquals(emptyList(), search("missing"))
    }
}
