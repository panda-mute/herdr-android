package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.board.BoardStatus
import com.makefunstuff.herdr.core.board.WorkOverview
import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.board.WorkTasks
import com.makefunstuff.herdr.core.home.Home
import com.makefunstuff.herdr.core.home.HomeAgent
import com.makefunstuff.herdr.core.home.HomeBoard
import com.makefunstuff.herdr.core.home.Waiting
import com.makefunstuff.herdr.core.push.LiveItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One Needs you list for both boards, and one word per state on every badge. */
class OneListTest {
    private val asks = WorkTask("AND-21", "AND-21", "Show when each machine last answered", "p:app", WorkStatus.Need, line = "Asks which wording to use")
    private val ready = WorkTask("AND-20", "AND-20", "Rate-limit the export endpoint", "p:app", WorkStatus.Ready, pr = "https://github.com/x/y/pull/142")
    private val working = WorkTask("AND-19", "AND-19", "Speed up /jobs", "p:app", WorkStatus.Work, progress = 60, eta = 15, age = "now")
    private val work = WorkOverview(listOf(WorkSpace("p:app", "herdr-android")), listOf(asks, ready, working))
    private val v2 = HomeBoard("devbox", "devbox", work.tasks.map(WorkTasks::row), work)

    private fun agent(name: String, workspace: String, status: String) = HomeAgent(
        "devbox", "devbox",
        AgentInfo(name = name, agent = "claude", agentStatusRaw = status, workspaceId = "w1", tabId = "t", paneId = "w1:p1"),
        "$workspace › main",
    )

    @Test
    fun shepherdr2TasksWaitOnNeedsYouAsThemselves() {
        val home = Home.of(emptyList(), listOf(v2))
        assertEquals(listOf("AND-20", "AND-21"), home.needsYou.map { it.task?.name })
        val card = assertIs<Waiting.Task>(home.needsYou[1])
        assertEquals(asks, card.work)
        assertEquals(WorkStatus.Ready, assertIs<Waiting.Task>(home.needsYou[0]).work?.status)
    }

    @Test
    fun aShepherdr2TasksAgentIsFoundByItsWorkspace() {
        // Shepherdr 3 opens a task's workspace as "<id> <title>": the task's agent, and its card shows once.
        assertTrue(LiveItems.belongs("", "AND-21 Show when each machine last answered", "AND-21", "AND-21"))
        assertFalse(LiveItems.belongs("", "AND-210 Something else", "AND-21", "AND-21"))
        // A v1 task without a short key never matches its id as a workspace.
        assertFalse(LiveItems.belongs("", "herdr-android/3", "herdr-android/3", "herdr-android/3"))
        val blocked = agent("claude", "AND-21 Show when each machine last answered", "blocked")
        val home = Home.of(listOf(blocked), listOf(v2))
        assertEquals(listOf("agent/devbox/w1:p1", "task/devbox/AND-20"), home.needsYou.map { it.key })
        assertEquals("AND-21", home.needsYou[0].task?.name)
    }

    @Test
    fun aShepherdrTasksAgentIsFoundByItsTerminalsLabel() {
        // Shepherdr on herdr-core: a terminal name that says little, its task from its labels.
        val blocked = HomeAgent(
            "devbox", "devbox",
            AgentInfo(name = "shep-1a2b3c4d5e6f7a8b", agent = "claude", agentStatusRaw = "blocked", workspaceId = "w1", tabId = "t", paneId = "w1:p1", shepherdrTask = "AND-21"),
            "Other terminals › Terminals",
        )
        val home = Home.of(listOf(blocked), listOf(v2))
        assertEquals(listOf("agent/devbox/w1:p1", "task/devbox/AND-20"), home.needsYou.map { it.key })
        assertEquals("AND-21", home.needsYou[0].task?.name)
        assertTrue(Home.isBareName(blocked.agent, "shep-1a2b3c4d5e6f7a8b"))
        assertTrue(Home.isBareName(blocked.agent, "Claude"))
        assertFalse(Home.isBareName(blocked.agent, "api-fixer"))
    }

    @Test
    fun aCoordinatorThatAsksWaitsOnTheOneList() {
        val asking = work.copy(spaces = listOf(WorkSpace("p:app", "herdr-android", asks = true)))
        val home = Home.of(emptyList(), listOf(v2.copy(work = asking)))
        assertEquals(listOf("task/devbox/AND-20", "task/devbox/AND-21", "asks/devbox/p:app"), home.needsYou.map { it.key })
        assertEquals("herdr-android", assertIs<Waiting.Coordinator>(home.needsYou[2]).space.name)
    }

    @Test
    fun everyBoardSaysEachStateInOneWord() {
        assertEquals("Ready", WorkStatus.Ready.label)
        assertEquals("ready", WorkStatus.Ready.word)
        assertEquals("ready", BoardStatus.Approval.word)
        assertNull(BoardStatus.Approval.detail)
        assertNull(BoardStatus.Asks.detail)
        for (s in listOf(BoardStatus.ReviewFailed, BoardStatus.PlanReady, BoardStatus.PickOne, BoardStatus.Asks, BoardStatus.AsksOk, BoardStatus.NeedsLogin, BoardStatus.Stopped, BoardStatus.Error)) {
            assertEquals("needs you", s.word, s.name)
        }
        assertEquals("Review failed", BoardStatus.ReviewFailed.detail)
        assertEquals("working", BoardStatus.Reviewing.word)
        assertEquals("Reviewing", BoardStatus.Reviewing.detail)
        assertEquals("done", BoardStatus.Archived.word)
        assertNull(BoardStatus.Working.detail)
    }
}
