package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.chat.TranscriptFollower
import com.makefunstuff.herdr.core.hc.HcClient
import com.makefunstuff.herdr.core.hc.HcConnection
import com.makefunstuff.herdr.core.hc.HcMapping
import com.makefunstuff.herdr.core.shepherdr.CurlTransport
import com.makefunstuff.herdr.core.shepherdr.ShepherdrClient
import com.makefunstuff.herdr.core.shepherdr.V2Board
import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.ssh.Duplex
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * Every chat the app would open on THIS machine, and what it maps each to: each herdr-core agent's transcript
 * (found from its process as the app finds it), each Shepherdr space's Assistant chat and each task's chat. It
 * reads only (`hc connect`'s snapshot, Shepherdr's snapshot and chats, transcript files) and prints a table to
 * check by hand against `hc read`. Runs only with HERDR_LIVE_LOCAL=1, on a machine running hc (and Shepherdr).
 */
class LiveChatMapTest {
    private fun local(command: String): Duplex {
        val p = ProcessBuilder("sh", "-c", command).start()
        return object : Duplex {
            override val input: InputStream = p.inputStream
            override val output: OutputStream = p.outputStream
            override fun close() { p.destroy() }
        }
    }

    private fun run(command: String, stdin: String = "", seconds: Long = 30): CommandResult {
        val p = ProcessBuilder("sh", "-c", command).start()
        p.outputStream.use { it.write(stdin.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor(seconds, TimeUnit.SECONDS)
        return CommandResult(p.exitValue(), out, err)
    }

    /** The transcript file the chat would follow for [kind], [value] and [agent], as [TranscriptFollower] finds it. */
    private fun transcript(kind: String, value: String, agent: String): String =
        run("sh -c ${quote("kind=\"\$1\"; value=\"\$2\"; agent=\"\$3\"; f=\"\"\n" + TranscriptFollower.FIND + "\necho \"\$f\"")} find " +
            listOf(kind, value, agent).joinToString(" ") { quote(it) }).stdout.trim()

    /** What a Claude Code transcript says it is: its session, folder and first words of its first prompt. */
    private fun about(path: String): String {
        val f = File(path).takeIf { it.isFile } ?: return "(none)"
        val first = f.useLines { lines ->
            lines.mapNotNull { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject }.getOrNull() }
                .firstOrNull { (it["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content in setOf("user", "session_meta") }
        }
        return first?.toString()?.replace(Regex("\\s+"), " ")?.take(160) ?: "(no first line)"
    }

    @Test
    fun everyChatAndWhatItShows() {
        assumeTrue(System.getenv("HERDR_LIVE_LOCAL") == "1", "set HERDR_LIVE_LOCAL=1 on a machine running hc")
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val conn = HcConnection.open(local("hc connect"), scope)
                val sub = HcClient(conn).subscribe()
                val terminals = sub.snapshot.terminals
                val snap = HcMapping.snapshot(terminals)
                println("== herdr-core: ${terminals.size} terminals, ${snap.agents.size} agents")
                println("== workspaces (what the drawer and the agent rows name)")
                snap.workspaces.forEach { w -> println("  ${w.workspaceId}  \"${w.label}\"  panes=${w.paneCount}") }
                println("== agents → chat")
                for (a in snap.agents) {
                    val t = terminals.first { it.address == a.terminalId }
                    val ref = a.agentSession
                    val file = ref?.let { transcript(it.kind, it.value, it.agent) }.orEmpty()
                    val ws = snap.workspaces.firstOrNull { it.workspaceId == a.workspaceId }?.label
                    println(
                        "${a.terminalId} name=${a.name} agent=${a.agent} space=\"$ws\" task=${a.shepherdrTask} assistant=${a.shepherdrSpace} " +
                            "fg=${t.foreground?.name}/${t.foreground?.pid} pid=${t.pid} cwd=${a.cwd} labels=${t.labels.keys.sorted()}",
                    )
                    println("    session=$ref → ${file.ifEmpty { "(screen)" }}")
                    if (file.isNotEmpty()) println("    first: ${about(file)}")
                }

                val client = ShepherdrClient(CurlTransport { c, stdin, s -> run(c, stdin, s) })
                val v2 = runCatching { client.snapshot() }.getOrNull()
                if (v2 == null) {
                    println("== no Shepherdr here")
                    return@runBlocking
                }
                val o = V2Board.overview(v2)
                println("== Shepherdr spaces → Assistant chat")
                for (s in o.spaces) {
                    val chat = runCatching { client.chat(s.chat) }
                    val c = chat.getOrNull()
                    println(
                        "  ${s.id} \"${s.name}\" machine=${s.machine} key=${s.chat} cli=${c?.cli} notice=${c?.notice} messages=${c?.messages?.size} " +
                            "assistantTerminal=${snap.agents.firstOrNull { it.shepherdrSpace == s.id }?.terminalId}" + (chat.exceptionOrNull()?.let { " ERROR ${it.message}" } ?: ""),
                    )
                    c?.messages?.lastOrNull { it.text?.isNotBlank() == true }?.let { m -> println("    last ${m.who}: ${m.text!!.replace('\n', ' ').take(120)}") }
                }
                println("== Shepherdr tasks with an agent → task chat")
                for (t in o.tasks.filter { it.chat.isNotEmpty() && it.status.name != "Done" }) {
                    val c = runCatching { client.chat(t.chat) }.getOrNull()
                    val term = snap.agents.firstOrNull { it.shepherdrTask == t.name }
                    println("  ${t.name} \"${t.title}\" space=${t.space} key=${t.chat} cli=${c?.cli} notice=${c?.notice} messages=${c?.messages?.size} terminal=${term?.terminalId}")
                    c?.messages?.firstOrNull { it.text?.isNotBlank() == true }?.let { m -> println("    first ${m.who}: ${m.text!!.replace('\n', ' ').take(120)}") }
                }
            } finally {
                scope.cancel()
            }
        }
    }
}
