package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.chat.*
import com.makefunstuff.herdr.core.activity.ActivityLog
import com.makefunstuff.herdr.core.api.AgentSessionRef
import java.nio.file.Files
import kotlin.test.*

class AgyTest {
    private fun lines() = javaClass.getResource("/agy/transcript.jsonl")!!.readText().lines()
    @Test fun messagesToolsAndTimelineMatch() {
        val t = Transcript.forAgent("agy")!!
        t.activity = ActivityLog.forAgent("agy")
        lines().forEach(t::feed)
        assertEquals("Check the build.", t.items().filterIsInstance<ChatItem.User>().single().text)
        val tool = t.items().filterIsInstance<ChatItem.Tool>().single()
        assertEquals("Run project tests", tool.summary)
        assertEquals(ToolState.Done, tool.state)
        assertTrue(tool.output!!.contains("BUILD SUCCESSFUL"))
        assertTrue(t.items().none { it is ChatItem.Notice })
        assertEquals("The build passed. No changes were needed.", t.items().filterIsInstance<ChatItem.Agent>().single().text)
        assertEquals(1, ChatTurns.of(t.items(), t.activity!!.turns()).single().counts.commands)
        assertEquals(3000, ChatTurns.of(t.items(), t.activity!!.turns()).single().took)
    }
    @Test fun failedCommandAndRunningPlannerAreNotSuccessfulEdits() {
        val t = AgyTranscript()
        lines().take(2).forEach(t::feed)
        assertEquals(ToolState.Running, t.items().filterIsInstance<ChatItem.Tool>().single().state)
        t.feed(lines()[2].replace("code 0", "code 1"))
        assertEquals(ToolState.Failed, t.items().filterIsInstance<ChatItem.Tool>().single().state)
    }
    @Test fun resolvesClassicPaneUsingOnlyItsOwnProcessLog() {
        if (!java.io.File("/proc/self/comm").exists()) return
        val home = Files.createTempDirectory("agy-pane").toFile()
        val base = home.resolve(".gemini/antigravity-cli")
        val log = base.resolve("log/cli-test.log").apply { parentFile.mkdirs(); writeText("cascade_id:abc-123 other=value\n") }
        val file = base.resolve("brain/abc-123/.system_generated/logs/transcript.jsonl").apply { parentFile.mkdirs(); writeText(lines().first()+"\n") }
        val agent = ProcessBuilder("python3", "-c", "import ctypes,time; ctypes.CDLL(None).prctl(15,b'agy',0,0,0); time.sleep(30)")
            .redirectOutput(log).also { it.environment()["HERDR_PANE_ID"] = "test-agy-pane" }.start()
        try {
            log.writeText("cascade_id:abc-123 other=value\n")
            Thread.sleep(150)
            val p = ProcessBuilder("sh", "-c", TranscriptFollower.command(AgentSessionRef("agy","pane","test-agy-pane")))
                .also { it.environment()["HOME"] = home.path }.start()
            try { assertTrue(p.inputStream.bufferedReader().readLine().endsWith(file.path)) }
            finally { p.outputStream.close(); p.waitFor(5,java.util.concurrent.TimeUnit.SECONDS); p.destroyForcibly() }
        } finally { agent.destroyForcibly(); agent.waitFor(); home.deleteRecursively() }
    }

    @Test fun findsSessionByIdWithoutReadingOtherConversations() {
        val home = Files.createTempDirectory("agy-reader").toFile()
        val file = home.resolve(".gemini/antigravity-cli/brain/test-id/.system_generated/logs/transcript.jsonl")
        file.parentFile.mkdirs(); file.writeText(lines().joinToString("\n"))
        val p = ProcessBuilder("sh", "-c", TranscriptFollower.command(AgentSessionRef("agy","id","test-id"))).also { it.environment()["HOME"] = home.path }.start()
        try {
            val reader = p.inputStream.bufferedReader()
            assertTrue(reader.readLine().endsWith(file.path))
            assertEquals(lines().first(), reader.readLine())
        } finally { p.outputStream.close(); p.waitFor(5,java.util.concurrent.TimeUnit.SECONDS); p.destroyForcibly();home.deleteRecursively() }
    }
}
