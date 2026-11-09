package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.BoardApi
import com.makefunstuff.herdr.core.board.CardAnswer
import com.makefunstuff.herdr.core.board.WorkCard
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.shepherdr.BoardDetection
import com.makefunstuff.herdr.core.shepherdr.V2Question
import com.makefunstuff.herdr.core.shepherdr.V2Version
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.core.ssh.Machine
import com.makefunstuff.herdr.core.shp.ShpBoard
import com.makefunstuff.herdr.core.shp.ShpClient
import com.makefunstuff.herdr.core.shp.ShpCommands
import com.makefunstuff.herdr.core.shp.ShpRunner
import com.makefunstuff.herdr.core.ssh.CommandResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.io.IOException

/** The app's side of simple-herdr-projects: what it runs, and what it makes of shp's answers. */
class ShpTest {
    private val asks = """[{"id":"a1","text":"Which branch?","time":"2026-11-07T10:00:00+00:00"}]"""

    private fun head(id: String, status: String, asks: String = "[]", title: String = "Do the thing") =
        """{"id":"$id","title":"$title","status":"$status","branch":"task/x-$id","worktree":"~/w/$id","asks":$asks,"progress":40,"step":"Writing it","left":"6m"}"""

    private val appList = "[${head("APP-1", "needs", asks)},${head("APP-2", "ready", title = "Ship it")}]"
    private val webList = "[${head("WEB-1", "working", title = "Fix it")}]"

    private fun listOutput() = buildString {
        append("${ShpCommands.SPACE_MARK}app\n")
        append("${ShpCommands.SPACE_MARK}web\n")
        append("${ShpCommands.TASK_MARK}app APP-1\n")
        append("${ShpCommands.TASK_MARK}app APP-2\n")
        append("${ShpCommands.TASK_MARK}web WEB-1\n")
        append("${ShpCommands.LIST_MARK}\n")
        append("[${appList.trim('[', ']')},${webList.trim('[', ']')}]")
    }

    private val taskFile = "---\nid: APP-1\nstatus: needs\n---\n\nDo it carefully.\n"

    private class Ran(val out: String = "", val err: String = "", val exit: Int = 0)

    /** Answers the commands the client runs, and remembers them. */
    private class Fake(val answer: (String) -> Ran) : ShpRunner {
        val commands = mutableListOf<String>()
        override suspend fun run(command: String, stdin: String?, timeoutSeconds: Long): CommandResult {
            commands += command
            val a = answer(command)
            return CommandResult(a.exit, a.out, a.err)
        }
    }

    /** A board whose list comes from [listOutput], with [rest] answering its other commands. */
    private fun board(rest: (String) -> Ran = { Ran(exit = 2, err = "unexpected") }): Pair<ShpBoard, Fake> {
        val fake = Fake { command -> if (command == ShpCommands.list()) Ran(listOutput()) else rest(command) }
        return ShpBoard(ShpClient(fake), "devbox", "devbox") to fake
    }

    @Test
    fun `the board lists each space's tasks, needs you first`() = runTest {
        val (board, _) = board()
        val o = board.overview()
        assertEquals(listOf("APP-1", "APP-2", "WEB-1"), o.tasks.map { it.id })
        assertEquals(listOf("app", "web"), o.spaces.map { it.id })
        assertEquals(listOf("APP-1", "APP-2"), o.needsYou.map { it.id })
        assertEquals(WorkStatus.Need, o.tasks.first().status)
        assertEquals("Which branch?", o.tasks.first().line)
        assertEquals("6m", o.tasks.first().left)
        assertEquals(40, o.tasks.first().progress)
        assertTrue(o.spaces.first().needsYou)
        assertEquals("APP-1", o.tasks.first().chat)
        assertEquals("", o.tasks.first { it.id == "APP-2" }.chat)
    }

    @Test
    fun `show gives the brief and the ask as a card`() = runTest {
        val (board, fake) = board { command ->
            if (command.contains("shp show --json")) Ran(head("APP-1", "needs", asks) + "\n@@shp-file@@\n" + taskFile)
            else Ran(exit = 2, err = "unexpected")
        }
        val t = board.overview().tasks.first()
        val d = board.details(t)
        assertEquals("Do it carefully.", d.brief)
        assertEquals("task/x-APP-1", d.branch)
        assertEquals(1, d.cards.size)
        assertEquals("a1", d.cards.single().question.id)
        assertEquals("Which branch?", d.cards.single().question.title)
        assertEquals("a1", d.cards.single().replyTo)
        assertTrue(fake.commands.any { it.contains("shp show --json") && it.contains("APP-1") })
    }

    @Test
    fun `accept runs shp accept --yes with the task`() = runTest {
        val (board, fake) = board { command ->
            if (command == ShpCommands.accept("APP-2")) Ran("APP-2 accepted\n") else Ran(exit = 1, err = "nope")
        }
        val t = board.overview().tasks.first { it.id == "APP-2" }
        assertEquals("APP-2 accepted", board.approve(t))
        assertTrue(fake.commands.last().contains("shp accept --yes") && fake.commands.last().contains("APP-2"))
    }

    @Test
    fun `an ask is answered with shp say, and stays when the send is refused`() = runTest {
        val (board, fake) = board { command ->
            if (command.contains("shp say ")) Ran(exit = 1, err = "the message did not go in: no live worker; APP-1 still needs you")
            else Ran(exit = 2, err = "unexpected")
        }
        val card = WorkCard(chat = "APP-1", question = V2Question(id = "a1", title = "Which branch?"), task = "APP-1", replyTo = "a1")
        val e = assertFailsWith<IOException> { board.answer(CardAnswer(card, -1, other = "main")) }
        assertTrue(e.message!!.contains("still needs you"))
        val sent = fake.commands.last()
        assertTrue(sent.contains("shp say") && sent.contains("APP-1") && sent.contains("main") && sent.contains("--ask"))
    }

    @Test
    fun `a chat is its live worker's terminal, read with hc`() = runTest {
        val (board, fake) = board { command ->
            when {
                command.contains("hc ls ") -> Ran("""{"terminals":[{"address":"t7","state":"live","labels":{"herdr.task":"APP-1"}}]}""")
                command.contains("hc read ") -> Ran("""{"text":"I am writing it.\nOne more line.\n"}""")
                else -> Ran(exit = 2, err = "unexpected")
            }
        }
        val task = board.overview().tasks.first()
        val thread = board.taskThreads(task, everyMs = 60_000).first()
        assertEquals(listOf("I am writing it.", "One more line."), thread.lines.map { it.text })
        assertTrue(fake.commands.any { it.contains("hc read --json") && it.contains("t7") })
    }

    @Test
    fun `a task with no live worker has no chat and no transcript command`() = runTest {
        val fake = Fake { command -> if (command.contains("hc ls ")) Ran("""{"terminals":[]}""") else Ran(exit = 2, err = "unexpected") }
        assertEquals(emptyList<String>(), ShpClient(fake).chatLines("APP-1"))
        assertTrue(fake.commands.none { it.contains("hc read") })
    }

    @Test
    fun `the commands are shp's own`() {
        assertTrue(ShpCommands.list().contains("shp list --json"))
        assertTrue(ShpCommands.show("APP-1").contains("shp show --json") && ShpCommands.show("APP-1").contains("APP-1"))
        assertTrue(ShpCommands.accept("APP-2").contains("shp accept --yes") && ShpCommands.accept("APP-2").contains("APP-2"))
        assertTrue(ShpCommands.say("APP-1", "main", "a1").contains("shp say") && ShpCommands.say("APP-1", "main", "a1").contains("--ask"))
        assertTrue(ShpCommands.worker("APP-1").contains("hc ls --json --label herdr.role=worker") && ShpCommands.worker("APP-1").contains("herdr.task="))
        // Every command goes through the machine's login shell: sshd's PATH lacks ~/.local/bin.
        assertTrue(ShpCommands.present().startsWith("sh -c "))
        assertTrue(ShpCommands.present().contains("-lc"))
        assertTrue(ShpCommands.transcript("t7").contains("hc read --json --lines 200") && ShpCommands.transcript("t7").contains("t7"))
    }

    @Test
    fun `a machine with shp has a board, and it is not Shepherdr's`() {
        val machine = Machine(id = "m", label = "devbox", host = "h", user = "u", taskSource = Machine.TASK_SHP)
        val shp = HostState(machine, shpPresent = true, boardChecked = true)
        assertEquals(BoardApi.Shp, shp.boardApi)
        assertTrue(shp.hasBoard)
        assertTrue(!shp.onShepherdr)
        val auto = HostState(machine.copy(taskSource = null), shpPresent = true, boardChecked = true)
        assertEquals(BoardApi.Shp, auto.boardApi)
        val shepherdr = HostState(machine.copy(taskSource = Machine.TASK_SHEPHERDR), shpPresent = true, shepherdrVersion = "3.1.2")
        assertEquals(BoardApi.V2("3.1.2"), shepherdr.boardApi)
    }

    @Test
    fun `the machine choice picks the board, and shp wins auto where it is there`() {
        val v3 = V2Version(version = "3.1.2")
        assertEquals(BoardApi.Shp, BoardDetection.decide(v3, shp = true))
        assertEquals(BoardApi.V2("3.1.2"), BoardDetection.decide(v3, shp = false))
        assertEquals(BoardApi.V2("3.1.2"), BoardDetection.decide(v3, shp = true, choice = "shepherdr"))
        assertEquals(BoardApi.Shp, BoardDetection.decide(null, shp = true, choice = "shp"))
        assertEquals(BoardApi.None, BoardDetection.decide(v3, shp = false, choice = "shp"))
    }

    @Test
    fun `a message that starts with a dash stays the message`() {
        val sent = ShpCommands.say("APP-1", "--help", "a1")
        // Options, then `--`, then the task and the words (the login shell quotes them once more).
        val at = listOf("shp say --ask", "a1", " -- ", "APP-1", "--help").map { sent.indexOf(it) }
        assertTrue(at.all { it >= 0 } && at == at.sorted(), sent)
    }

    @Test
    fun `a shp that fails is the board's problem, not an empty board`() = runTest {
        val fake = Fake { command ->
            if (command == ShpCommands.list()) Ran("${ShpCommands.SPACE_MARK}app\n${ShpCommands.LIST_MARK}\n", exit = 127, err = "sh: shp: not found")
            else Ran(exit = 2, err = "unexpected")
        }
        val e = assertFailsWith<IOException> { ShpClient(fake).spaces() }
        assertEquals("sh: shp: not found", e.message)
    }
}
