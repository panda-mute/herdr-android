package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.ApiCaller
import com.makefunstuff.herdr.core.api.ApiConnection
import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.board.WorkOverview
import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.chat.AgentInput
import com.makefunstuff.herdr.core.chat.BlockedPrompt
import com.makefunstuff.herdr.core.chat.ClassicAgentPort
import com.makefunstuff.herdr.core.home.Home
import com.makefunstuff.herdr.core.home.HomeAgent
import com.makefunstuff.herdr.core.home.HomeBoard
import com.makefunstuff.herdr.core.home.Waiting
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeTest {
    private fun task(id: String, title: String, status: WorkStatus) = WorkTask(id = id, name = id, title = title, space = "s1", status = status)

    private val ready = task("HA-9", "Last answered", WorkStatus.Ready)
    private val asks = task("HA-3", "Speed up /jobs", WorkStatus.Need)
    private val working = task("HA-6", "Screenshots", WorkStatus.Work)
    private val todo = task("HA-10", "Haptics", WorkStatus.Todo)
    private val done = task("HA-2", "Native chat", WorkStatus.Done)

    private fun agent(
        pane: String, status: String, name: String = "claude", workspace: String = "src", seq: Long = 1, completion: Long? = null, machine: String = "devbox",
        task: String? = null,
    ) = HomeAgent(
        machine, machine,
        AgentInfo(
            name = name, agent = "claude", agentStatusRaw = status, workspaceId = pane.substringBefore(':'), tabId = "t", paneId = pane, stateChangeSeq = seq,
            completionSeq = completion, shepherdrTask = task,
        ),
        "$workspace › main",
    )

    private fun board(vararg tasks: WorkTask, machine: String = "devbox", asks: Boolean = false) =
        HomeBoard(machine, machine, WorkOverview(listOf(WorkSpace("s1", "herdr-android", asks = asks)), tasks.toList()))

    @Test
    fun anAgentAndItsTaskShowOnce() {
        val home = Home.of(
            listOf(
                agent("w2:p1", "blocked", seq = 5, task = "HA-3"),
                agent("w1:p1", "idle", task = "HA-9"),
                agent("w4:p1", "working", task = "HA-6"),
            ),
            listOf(board(ready, asks, working, todo, done)),
        )
        // The blocked agent stands for its asking task; the ready one carries its (idle) agent, which isn't listed again.
        assertEquals(listOf("agent/devbox/w2:p1", "task/devbox/HA-9"), home.needsYou.map { it.key })
        val question = assertIs<Waiting.Question>(home.needsYou[0])
        assertEquals("HA-3", question.task?.name)
        assertEquals("w1:p1", assertIs<Waiting.Task>(home.needsYou[1]).agent?.agent?.paneId)
        assertTrue(home.idle.isEmpty())
        // A working agent keeps its task, for its name and progress.
        assertEquals("HA-6", home.working.single().task?.name)
    }

    @Test
    fun aTasksWorkspaceTiesItsAgentToIt() {
        val home = Home.of(listOf(agent("w1:p1", "blocked", name = "claude", workspace = "HA-3 Speed up /jobs")), listOf(board(asks)))
        assertEquals("HA-3", home.needsYou.single().task?.name)
        // Another task's id at the start of a longer word isn't this one.
        assertNull(Home.taskOf(agent("w1:p1", "blocked", workspace = "HA-30 Other"), listOf(asks)))
    }

    @Test
    fun readyTasksWaitFirst() {
        val home = Home.of(emptyList(), listOf(board(asks, ready, working)))
        assertEquals(listOf(WorkStatus.Ready, WorkStatus.Need), home.needsYou.map { it.task?.status })
    }

    @Test
    fun anAssistantThatAsksWaitsAfterTheTasks() {
        val home = Home.of(emptyList(), listOf(board(ready, asks = true)))
        assertEquals(listOf("task/devbox/HA-9", "asks/devbox/s1"), home.needsYou.map { it.key })
    }

    @Test
    fun finishedIsNewUntilSeen() {
        val agents = listOf(
            agent("w1:p1", "done", seq = 7, completion = 7),
            agent("w2:p1", "done", seq = 3, completion = 3),
            // Seen at herdr's terminal: herdr says idle again.
            agent("w3:p1", "idle", seq = 9, completion = 9),
        )
        val home = Home.of(agents, emptyList(), seen = mapOf("devbox/w2:p1" to 3L))
        // Done holds what wasn't looked at; a finish seen is at rest with the idle ones.
        assertEquals(listOf("w1:p1" to true), home.done.map { it.agent.agent.paneId to it.new })
        assertEquals(1, home.fresh)
        assertEquals(listOf("w3:p1", "w2:p1"), home.idle.map { it.agent.agent.paneId })
        // A later finish of a pane seen before is new again.
        val again = Home.of(listOf(agent("w2:p1", "done", seq = 12, completion = 12)), emptyList(), seen = mapOf("devbox/w2:p1" to 3L))
        assertTrue(again.done.single().new)
        // Mark all seen: every finish marked, panes gone dropped, and the Done section empty.
        val marks = Home.seenAll(mapOf("devbox/gone" to 1L, "devbox/w2:p1" to 3L), home, agents)
        assertEquals(mapOf("devbox/w1:p1" to 7L, "devbox/w2:p1" to 3L), marks)
        assertEquals(0, Home.of(agents, emptyList(), marks).fresh)
        assertTrue(Home.of(agents, emptyList(), marks).done.isEmpty())
    }

    @Test
    fun anOlderHerdrUsesTheStateChange() {
        val a = agent("w1:p1", "done", seq = 4, completion = null)
        assertEquals(4L, a.finish)
        assertFalse(Home.of(listOf(a), emptyList(), mapOf(a.key to 4L)).idle.single().new)
    }

    @Test
    fun completionSeqIsRead() {
        val a = Json { ignoreUnknownKeys = true }.decodeFromString(
            AgentInfo.serializer(),
            """{"agent_status":"done","workspace_id":"w","tab_id":"t","pane_id":"p","state_change_seq":1227,"completion_seq":1227,"revision":3,"terminal_id":"x","focused":false}""",
        )
        assertEquals(1227L, a.completionSeq)
    }

    /** A machine whose agent asks [screen]'s question while [status]; keys pressed are kept. */
    private class Screen(var status: String, var screen: String) : ApiCaller {
        val keys = mutableListOf<String>()
        override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
            val result = when (method) {
                "session.snapshot" -> """{"type":"session_snapshot","snapshot":{"version":"0.9.5","protocol":22,"workspaces":[],"tabs":[],"panes":[],"layouts":[],"agents":[
                    {"terminal_id":"t","agent":"claude","agent_status":"$status","workspace_id":"w1","tab_id":"w1:t1","pane_id":"w1:p1","focused":false,"revision":1}]}}"""
                "pane.read" -> """{"type":"pane_read","read":{"pane_id":"w1:p1","source":"visible","text":${JsonPrimitive(screen)},"revision":1}}"""
                "agent.send_keys", "pane.send_keys" -> {
                    keys += params.toString(); """{"type":"ok"}"""
                }
                else -> """{"type":"ok"}"""
            }
            return ApiConnection.unwrap(Json.parseToJsonElement("""{"id":"1","result":$result}""").jsonObject)
        }
    }

    private val menu = """
        |Bash command
        |  npm run db:migrate
        |Do you want to proceed?
        |❯ 1. Yes
        |  2. Yes, and don't ask again for npm run commands
        |  3. No, and tell Claude what to do differently
        |Esc to cancel · Tab to amend
    """.trimMargin()

    @Test
    fun anAnswerReadsTheScreenAgainFirst() = runBlocking {
        val shown = BlockedPrompt.parse(menu)!!.details
        val fake = Screen("blocked", menu)
        AgentInput.choose(ClassicAgentPort(HerdrApi(fake)), "w1:p1", "Do you want to proceed?", 2, "No, and tell Claude what to do differently", shown)
        assertTrue(fake.keys.single().contains("down") && fake.keys.single().contains("enter"), fake.keys.toString())

        // Another question by now: nothing is pressed.
        val changed = Screen("blocked", menu.replace("Do you want to proceed?", "Do you want to delete the table?"))
        val e = assertFailsWith<IOException> { AgentInput.choose(ClassicAgentPort(HerdrApi(changed)), "w1:p1", "Do you want to proceed?", 0, "Yes", shown) }
        assertEquals("The question changed. Open the chat to answer it.", e.message)
        assertTrue(changed.keys.isEmpty())

        // The same question with the same options, about another command: nothing is pressed either.
        val other = Screen("blocked", menu.replace("npm run db:migrate", "npm run db:reset --force"))
        assertFailsWith<IOException> { AgentInput.choose(ClassicAgentPort(HerdrApi(other)), "w1:p1", "Do you want to proceed?", 0, "Yes", shown) }
        assertTrue(other.keys.isEmpty())

        // Answered meanwhile at the desk.
        val gone = Screen("working", menu)
        assertFailsWith<IOException> { AgentInput.choose(ClassicAgentPort(HerdrApi(gone)), "w1:p1", "Do you want to proceed?", 0, "Yes", shown) }
        assertTrue(gone.keys.isEmpty())
    }
}
