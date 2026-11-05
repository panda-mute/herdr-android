package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.api.WorkspaceInfo
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.push.Counts
import com.makefunstuff.herdr.core.push.LiveAgent
import com.makefunstuff.herdr.core.push.PushEvent
import com.makefunstuff.herdr.core.push.Tally
import com.makefunstuff.herdr.core.push.TallyState
import com.makefunstuff.herdr.core.share.AskTargets
import com.makefunstuff.herdr.core.share.AskTargets.Board
import com.makefunstuff.herdr.core.share.AskTargets.Candidate
import com.makefunstuff.herdr.core.share.ShareText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TallyTest {
    private val ids = setOf("m1", "m2")

    private fun agent(status: String, pane: String, name: String? = "api", ws: String = "w1") =
        AgentInfo(name = name, agent = "claude", agentStatusRaw = status, workspaceId = ws, tabId = "t1", paneId = pane)

    private fun snapshot(vararg agents: AgentInfo, workspaces: List<WorkspaceInfo> = listOf(WorkspaceInfo("w1", label = "api"))) =
        SessionSnapshot(workspaces = workspaces, agents = agents.toList())

    private fun task(id: String, status: WorkStatus) = WorkTask(id, id, id, "s1", status)

    private val asks = task("HA-7", WorkStatus.Need)
    private val running = task("HA-8", WorkStatus.Work)

    @Test
    fun agentsAndTasksAcrossMachines() {
        var s = TallyState()
        s = Tally.withAgents(s, "m1", snapshot(agent("blocked", "p1"), agent("working", "p2"), agent("idle", "p3"), agent("done", "p4")), at = 1000)
        s = Tally.withAgents(s, "m2", snapshot(agent("working", "p1")), at = 2000)
        s = Tally.withTasks(s, "m1", listOf(asks, running, task("HA-9", WorkStatus.Done)), at = 1500)
        assertEquals(Counts(needYou = 2, working = 3, machines = 2, at = 2000), Tally.count(s, ids, now = 3000))
        assertEquals("2 need you · 3 working", Tally.count(s, ids, now = 3000).words)
        // A machine taken out of the app, or turned off, isn't counted.
        assertEquals(Counts(needYou = 2, working = 2, machines = 1, at = 1500), Tally.count(s, setOf("m1"), now = 3000))
        assertEquals(Tally.count(s, setOf("m1"), 3000), Tally.count(Tally.only(s, setOf("m1")), ids, 3000))
    }

    @Test
    fun aTasksOwnAgentIsTheTask() {
        // The task's agent, in the workspace Shepherdr opens for it, while the task runs: one working, not two.
        val worker = agent("working", "p1", name = "claude", ws = "w2")
        val ws = listOf(WorkspaceInfo("w2", label = "HA-8 Run"))
        var s = Tally.withAgents(TallyState(), "m1", snapshot(worker, workspaces = ws), at = 1)
        s = Tally.withTasks(s, "m1", listOf(running), at = 1)
        assertEquals(Counts(0, 1, 1, 1), Tally.count(s, ids, now = 2))
        // The task asks you and its agent is blocked on that question: one that needs you.
        val asking = agent("blocked", "p1", name = "claude", ws = "w2")
        s = Tally.withAgents(TallyState(), "m1", snapshot(asking, workspaces = listOf(WorkspaceInfo("w2", label = "HA-7 Ask"))), at = 1)
        s = Tally.withTasks(s, "m1", listOf(asks), at = 1)
        assertEquals(Counts(1, 0, 1, 1), Tally.count(s, ids, now = 2))
    }

    @Test
    fun pushesChangeOneThingOrWhatWorks() {
        var s = Tally.withAgents(TallyState(), "m1", snapshot(agent("working", "p1"), agent("working", "p2")), at = 1000)
        // An agent asks: it needs you now, the other still works.
        s = Tally.withPush(s, "m1", listOf(PushEvent.Agent(agent("blocked", "p1"), "api › agents")), at = 2000)
        assertEquals(Counts(1, 1, 1, 2000), Tally.count(s, ids, now = 2500))
        // What works there now: a third agent. The one that asks still asks.
        s = Tally.withPush(s, "m1", listOf(PushEvent.Live(listOf(LiveAgent(agent("working", "p3"), "api › agents")))), at = 3000)
        assertEquals(Counts(1, 1, 1, 3000), Tally.count(s, ids, now = 3500))
        // The agent that asked is answered and works again: it moves over.
        s = Tally.withPush(s, "m1", listOf(PushEvent.Live(listOf(LiveAgent(agent("working", "p1"), ""), LiveAgent(agent("working", "p3"), "")))), at = 4000)
        assertEquals(Counts(0, 2, 1, 4000), Tally.count(s, ids, now = 4500))
        assertEquals(s, Tally.withPush(s, "m1", listOf(PushEvent.Test), at = 7000))
    }

    @Test
    fun oldWordIsNoLongerCounted() {
        val s = Tally.withAgents(TallyState(), "m1", snapshot(agent("blocked", "p1"), agent("working", "p2")), at = 0 + 1)
        assertEquals(Counts(1, 1, 1, 1), Tally.count(s, ids, now = Tally.WORK_MS))
        assertEquals(Counts(1, 0, 1, 1), Tally.count(s, ids, now = Tally.WORK_MS + 1))
        assertEquals(Counts(0, 0, 1, 1), Tally.count(s, ids, now = Tally.NEED_MS + 1))
    }

    @Test
    fun words() {
        assertEquals("1 needs you", Counts(needYou = 1, at = 1).words)
        assertEquals("1 needs you · 1 working", Counts(needYou = 1, working = 1, at = 1).words)
        assertEquals("3 working", Counts(working = 3, at = 1).words)
        assertEquals(false, Counts().known)
    }
}

class ShareTest {
    @Test
    fun sharedWords() {
        // A browser shares a page's title as the subject and its link as the text.
        assertEquals("Flaky test in CI\nhttps://example.com/run/7", ShareText.of(" Flaky test in CI ", "https://example.com/run/7"))
        assertEquals("https://example.com", ShareText.of("https://example.com", "https://example.com"))
        assertEquals("Only a subject", ShareText.of("Only a subject", null))
        assertEquals("text", ShareText.of(null, " text\n"))
        assertEquals("", ShareText.of(null, null))
    }

    @Test
    fun sharedTextJoinsTheDraft() {
        assertEquals("error: boom", ShareText.intoDraft("", "error: boom\n"))
        assertEquals("Look at this:\n\nerror: boom", ShareText.intoDraft("Look at this:\n", "error: boom"))
        assertEquals("keep", ShareText.intoDraft("keep", "  "))
    }

    @Test
    fun whichCoordinator() {
        val two = listOf(Candidate("m1", Board.Here), Candidate("m2", Board.Unknown), Candidate("m3", Board.None))
        // The tile opens the one used last; with none, it asks.
        assertEquals("m2", AskTargets.choose(two, last = "m2", share = false))
        assertNull(AskTargets.choose(two, last = null, share = false))
        // Last used on a machine since found to have no board: it asks.
        assertNull(AskTargets.choose(two, last = "m3", share = false))
        // A share asks whenever there are several, with the last one first.
        assertNull(AskTargets.choose(two, last = "m2", share = true))
        assertEquals(listOf("m2", "m1"), AskTargets.candidates(two, last = "m2").map { it.machineId })
        // Only one can have a board: straight there, both ways.
        val one = listOf(Candidate("m1", Board.Unknown), Candidate("m3", Board.None))
        assertEquals("m1", AskTargets.choose(one, last = null, share = true))
        assertEquals("m1", AskTargets.choose(one, last = "m3", share = false))
        assertNull(AskTargets.choose(emptyList(), last = null, share = false))
    }
}

class TallyTargetTest {
    private fun asks(n: Int) = WorkTask("R-$n", "R-$n", "Ask", "s1", WorkStatus.Need)

    @Test
    fun aTapOnTheCountsGoesWhereTasksWait() {
        var s = Tally.withTasks(TallyState(), "m1", listOf(asks(1)), at = 1)
        s = Tally.withTasks(s, "m2", listOf(asks(2), asks(3)), at = 1)
        assertEquals("m2", Tally.boardWaiting(s, listOf("m1", "m2"), now = 2))
        assertEquals("m1", Tally.boardWaiting(s, listOf("m1"), now = 2))
        assertNull(Tally.boardWaiting(TallyState(), listOf("m1"), now = 2))
        assertEquals(false, Tally.agentWaiting(s, listOf("m1", "m2"), now = 2))
        val blocked = AgentInfo(name = "api", agentStatusRaw = "blocked", workspaceId = "w1", tabId = "t1", paneId = "p1")
        s = Tally.withAgents(s, "m1", SessionSnapshot(agents = listOf(blocked)), at = 1)
        assertEquals(true, Tally.agentWaiting(s, listOf("m1"), now = 2))
    }
}
