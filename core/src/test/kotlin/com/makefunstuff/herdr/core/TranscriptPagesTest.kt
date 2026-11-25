package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentSessionRef
import com.makefunstuff.herdr.core.chat.*
import kotlin.test.*
import java.io.File

class TranscriptPagesTest {
    @Test fun byteRangesAcrossRolloutsWhileFollowingNewOutput() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val files = File(dir, ".codex/sessions").apply { mkdirs() }
        File(files,"rollout-1-id.jsonl").writeText("old甲")
        val live = File(files,"rollout-2-id_resumed.jsonl").apply { writeText("new乙\n") }
        val full = "old甲\nnew乙\n".encodeToByteArray()
        fun start(command: String) = ProcessBuilder("sh","-c",command).also {
            it.environment()["CODEX_HOME"] = File(dir,".codex").path
        }.start()
        val follow = start(TranscriptPages.openCommand(AgentSessionRef("codex","id","id"), 5))
        try {
            val output = follow.inputStream
            val header = generateSequence { output.read().takeIf { it >= 0 && it != 10 }?.toChar() }.joinToString("")
            assertEquals(full.size.toString(), header.substringBefore(' '))
            val tail = output.readNBytes(5)
            live.appendText("live\n")
            val end = full.size - tail.size
            val older = start(TranscriptPages.pageCommand(header.substringAfter(' '),end.toLong(),end))
            val page = older.inputStream.readBytes(); assertEquals(0,older.waitFor())
            assertContentEquals(full, page + tail)
            assertEquals("live\n", output.readNBytes(5).decodeToString())
        } finally {
            val children = follow.descendants().toList()
            follow.outputStream.close()
            assertTrue(follow.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            children.forEach { child -> if (child.isAlive) child.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS) }
            dir.deleteRecursively()
        }
    }

    @Test fun modelOutsideInitialPageIsReadWithoutChatHistory() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val file = File(dir, "rollout.jsonl")
        file.writeText("""{"type":"turn_context","payload":{"model":"old"}}
{"type":"turn_context","payload":{"model":"gpt-test","effort":"high"}}
""" + "x".repeat(600_000) + "\n")
        try {
            val tokenProcess = ProcessBuilder("python3", "-c", "import os,json,base64,sys; p=sys.argv[1]; s=os.stat(p); print(base64.urlsafe_b64encode(json.dumps([[p,s.st_dev,s.st_ino,s.st_size,0]]).encode()).decode())", file.path).start()
            val token = tokenProcess.inputStream.bufferedReader().readText().trim()
            assertEquals(0, tokenProcess.waitFor())
            val read = ProcessBuilder("sh", "-c", TranscriptPages.modelCommand(token)).start()
            val result = read.inputStream.bufferedReader().readText()
            assertEquals(0, read.waitFor())
            assertTrue(result.length < 300)
            val t = CodexTranscript().also { it.feed(result.trim()) }
            assertEquals("gpt-test", t.use?.model)
            assertEquals("high", t.use?.effort)
            assertTrue(t.items().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun toolOnlyTailRequiresMoreHistoryAndKeepsStableMessageIds() {
        val older = """{"type":"event_msg","payload":{"type":"user_message","message":"inspect"}}""" + "\n"
        val tail = """{"type":"response_item","payload":{"type":"function_call","call_id":"tool","name":"exec_command","arguments":"{}"}}""" + "\n"
        val w = TranscriptPages.Window("test", older.toByteArray().size.toLong(), ::CodexTranscript)
        w.append(tail.toByteArray())
        assertFalse(w.hasConversation())
        w.prepend(older.toByteArray())
        assertTrue(w.hasConversation())
        assertEquals("u@0", w.transcript.items().filterIsInstance<ChatItem.User>().single().id)
    }

    @Test fun splitUnicodeToolResultAndLiveAppendMatchFullTranscript() {
        val lines = listOf(
            """{"type":"assistant","uuid":"a","message":{"content":[{"type":"tool_use","id":"t","name":"Read","input":{"path":"甲"}}]}}""",
            """{"type":"user","uuid":"result","message":{"content":[{"type":"tool_result","tool_use_id":"t","content":"done"}]}}""",
            """{"type":"user","uuid":"live","message":{"role":"user","content":"next"}}""",
        )
        val initial = (lines.take(2).joinToString("\n") + "\n").encodeToByteArray()
        val cut = initial.indexOf(0xe7.toByte()) + 1
        val w = TranscriptPages.Window("token",cut.toLong(),::ClaudeTranscript)
        w.append(initial.copyOfRange(cut,initial.size)); w.append((lines.last()+"\n").encodeToByteArray()); w.prepend(initial.copyOfRange(0,cut))
        val expected = ClaudeTranscript().also { lines.forEach(it::feed) }.items()
        assertEquals(expected,w.transcript.items()); assertEquals("done",expected.filterIsInstance<ChatItem.Tool>().single().output)
    }
}
