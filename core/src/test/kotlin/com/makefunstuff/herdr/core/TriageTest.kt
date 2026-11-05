package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.home.Home
import com.makefunstuff.herdr.core.home.HomeAgent
import com.makefunstuff.herdr.core.home.NotifyMove
import com.makefunstuff.herdr.core.home.Triage
import com.makefunstuff.herdr.core.push.AlertRules
import com.makefunstuff.herdr.core.push.Notify
import com.makefunstuff.herdr.core.push.Watch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TriageTest {
    private fun agent(pane: String, status: String, seq: Long = 1, completion: Long? = null, machine: String = "devbox") = HomeAgent(
        machine, machine,
        AgentInfo(name = "claude", agent = "claude", agentStatusRaw = status, workspaceId = pane.substringBefore(':'), tabId = "t", paneId = pane, stateChangeSeq = seq, completionSeq = completion),
        "${pane.substringBefore(':')} › main",
    )

    private val eval = agent("w1:p1", "working", seq = 3)
    private val done = agent("w2:p1", "done", seq = 5, completion = 5)
    private val idle = agent("w3:p1", "idle", seq = 2)
    private val asks = agent("w4:p1", "blocked", seq = 8)
    private val all = listOf(eval, done, idle, asks)

    @Test
    fun pinnedStayOnTopInTheOrderPinned() {
        val t = Triage().pin(idle.key).pin(eval.key)
        val home = Home.of(all, emptyList(), triage = t)
        assertEquals(listOf("w3:p1", "w1:p1"), home.pinned.map { it.agent.agent.paneId })
        assertTrue(home.pinned.all { it.pinned })
        assertTrue(home.working.isEmpty())
        assertTrue(home.idle.isEmpty())
        // Pinning again moves it last; unpinning puts it back with its state.
        assertEquals(listOf(eval.key, idle.key), t.pin(idle.key).pins)
        assertEquals(listOf("w1:p1"), Home.of(all, emptyList(), triage = t.unpin(eval.key)).working.map { it.agent.agent.paneId })
    }

    @Test
    fun aQuestionShowsWhateverItsTriage() {
        val t = Triage(muted = setOf(asks.key)).pin(asks.key)
        val home = Home.of(all, emptyList(), triage = t)
        assertEquals(listOf("agent/${asks.key}"), home.needsYou.map { it.key })
        assertTrue(home.pinned.isEmpty())
    }

    @Test
    fun aSpaceSetToOffShowsItsAgentsMuted() {
        // The four agents' workspaces are w1..w4 (no labels): each its own space.
        val rules = AlertRules().withSpace(done.space, Notify.Off)
        assertEquals("devbox/w2", done.space)
        val home = Home.of(all, emptyList(), rules = rules)
        assertTrue(home.done.single().muted)
        assertFalse(home.working.single().muted)
        // A mute from before Notify, not moved yet, shows the same.
        assertTrue(Home.of(all, emptyList(), triage = Triage(muted = setOf(eval.key))).working.single().muted)
        assertTrue(Triage(muted = setOf(done.key)).mutes("devbox", "w2:p1"))
        assertFalse(Triage(muted = setOf(done.key)).mutes("devbox", ""))
    }

    @Test
    fun mutesAndWatchesMoveOntoTheirSpaces() {
        val gpu = agent("w1:p1", "working", machine = "gpu")
        val watch = { m: String, pane: String -> Watch(m, Watch.What.AgentDone, paneId = pane, name = "claude") }
        val moved = NotifyMove.of(
            muted = setOf(done.key, idle.key, "devbox/w9:p1", gpu.key),
            // The watch on idle's agent wins over its mute; the one on gpu waits; w8 is gone.
            watches = listOf(watch("devbox", idle.agent.paneId), watch("devbox", eval.agent.paneId), watch("gpu", "w1:p1"), watch("devbox", "w8:p1")),
            rules = AlertRules(),
            agents = all,
            read = setOf("devbox"),
        )
        assertEquals(mapOf(done.space to Notify.Off, idle.space to Notify.All, eval.space to Notify.All), moved.rules.spaces)
        // gpu wasn't read: its mute and watch wait; devbox's gone pane is dropped.
        assertEquals(setOf(gpu.key), moved.muted)
        assertEquals(listOf("gpu"), moved.watches.map { it.machineId })
        // A space that already picked its own keeps it.
        val own = AlertRules().withSpace(done.space, Notify.All)
        assertEquals(Notify.All, NotifyMove.of(setOf(done.key), emptyList(), own, all, setOf("devbox")).rules.spaces[done.space])
        // With Finished on everywhere, a watch's All is what the space gets anyway: nothing to set.
        val everyFinish = AlertRules(finished = true)
        assertTrue(NotifyMove.of(emptySet(), listOf(watch("devbox", eval.agent.paneId)), everyFinish, all, setOf("devbox")).rules.spaces.isEmpty())
    }

    @Test
    fun keepDropsWhatIsGone() {
        val gpu = agent("w1:p1", "working", machine = "gpu")
        val t = Triage(
            pins = listOf(eval.key, "devbox/w9:p1", gpu.key, "old/w1:p1"),
            muted = setOf("devbox/w9:p1", gpu.key),
        )
        // devbox was read (w9 is gone); gpu wasn't (offline): its marks stay; "old" is out of the app.
        val kept = t.keep(listOf(eval, done, idle), read = setOf("devbox"), machines = setOf("devbox", "gpu"))
        assertEquals(listOf(eval.key, gpu.key), kept.pins)
        assertEquals(setOf(gpu.key), kept.muted)
    }
}
