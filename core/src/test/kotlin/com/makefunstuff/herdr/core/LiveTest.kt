package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.push.LiveItem
import com.makefunstuff.herdr.core.push.LiveItems
import com.makefunstuff.herdr.core.push.LiveTracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveItemsTest {
    private fun agent(status: String = "working", name: String? = "api", title: String? = "Speed up /jobs", pane: String = "w1:p1") = AgentInfo(
        name = name, agent = "claude", agentStatusRaw = status, workspaceId = "w1", tabId = "t1", paneId = pane, title = title,
    )

    @Test
    fun workingAgents() {
        val i = assertNotNull(LiveItems.agent("m1", "devbox", agent(), "api › agents", since = 9))
        assertEquals("live:agent:m1:w1:p1", i.tag)
        assertEquals("api", i.title)
        assertEquals("Working · Speed up /jobs · api › agents", i.text)
        assertEquals("", i.chip, "the time it has worked shows instead")
        assertEquals("api", i.agentName)
        assertEquals("api", i.workspace, "from the location, when not given")
        assertNull(LiveItems.agent("m1", "devbox", agent("done"), ""))
        assertNull(LiveItems.agent("m1", "devbox", agent("blocked"), ""))
        // Its title is its workspace's label too: said once.
        val reviewer = assertNotNull(
            LiveItems.agent(
                "m1", "devbox", agent(name = "reviewer", title = "AND-24 Live Updates"),
                "AND-24 Live Updates › Reviewer #1", workspace = "AND-24 Live Updates",
            ),
        )
        assertEquals("Working · AND-24 Live Updates › Reviewer #1", reviewer.text)
    }

    @Test
    fun machineTimeOnThePhone() {
        // Started 60 s before the push was sent: 60 s before it arrived, whatever the machine's clock says.
        assertEquals(1_000_000L - 60_000, LiveItems.phoneTime(since = 5_000, at = 5_060, now = 1_000_000))
        assertEquals(0, LiveItems.phoneTime(since = 0, at = 5_060, now = 1_000_000))
        assertEquals(1_000_000L, LiveItems.phoneTime(since = 5_070, at = 5_060, now = 1_000_000))
    }
}

class LiveTrackerTest {
    private fun item(
        tag: String, kind: LiveItem.Kind = LiveItem.Kind.Agent, machine: String = "m1", text: String = "Working", since: Long = 0,
        agentName: String = "", workspace: String = "",
    ) = LiveItem(
        tag = tag, kind = kind, machineId = machine, machineLabel = machine, title = tag, text = text, chip = "", since = since,
        agentName = agentName, workspace = workspace,
    )

    private val a1 = item(LiveItems.agentTag("m1", "p1"))
    private val a2 = item(LiveItems.agentTag("m1", "p2"))

    @Test
    fun eachListReplacesTheOneBefore() {
        val t = LiveTracker()
        assertEquals(listOf(a1, a2), t.update("m1", LiveItem.Kind.Agent, listOf(a1, a2)).post)
        // Nothing new: nothing to post, unless a push asks to post again.
        assertTrue(t.update("m1", LiveItem.Kind.Agent, listOf(a1, a2)).isEmpty)
        assertEquals(listOf(a1, a2), t.update("m1", LiveItem.Kind.Agent, listOf(a1, a2), refresh = true).post)
        val changed = a1.copy(text = "Working · tests")
        val c = t.update("m1", LiveItem.Kind.Agent, listOf(changed))
        assertEquals(listOf(changed), c.post)
        assertEquals(listOf(a2.tag), c.cancel)
        // Another machine's list leaves this one's alone.
        val b = item(LiveItems.agentTag("m2", "p1"), machine = "m2")
        assertEquals(LiveItem.Kind.Agent, t.update("m2", LiveItem.Kind.Agent, listOf(b)).post.single().kind)
        assertEquals(2, t.items().size)
        assertEquals(listOf(b.tag), t.drop("m2").cancel)
        assertEquals(listOf(changed.tag), t.clear().cancel)
    }

    @Test
    fun keepsTheFirstKnownStart() {
        val t = LiveTracker()
        t.update("m1", LiveItem.Kind.Agent, listOf(a1.copy(since = 100_000)))
        // A push a moment later rounds differently, and the app's own look doesn't know: the first start stays.
        assertTrue(t.update("m1", LiveItem.Kind.Agent, listOf(a1.copy(since = 100_900))).isEmpty)
        assertTrue(t.update("m1", LiveItem.Kind.Agent, listOf(a1)).isEmpty)
        assertEquals(100_000, t.item(a1.tag)?.since)
    }

    @Test
    fun takesDownWhatARestartLeftUp() {
        val t = LiveTracker()
        t.adopt(listOf(a1.tag, a2.tag, "agent:m1:p1"))
        // The agents' list arrives: p1 still works and is posted again, p2 doesn't and comes down.
        val c = t.update("m1", LiveItem.Kind.Agent, listOf(a1))
        assertEquals(listOf(a1), c.post)
        assertEquals(listOf(a2.tag), c.cancel)
    }

    @Test
    fun aMutedAgentHasNoLiveUpdate() {
        val t = LiveTracker()
        assertEquals(listOf(a1, a2), t.update("m1", LiveItem.Kind.Agent, listOf(a1, a2)).post)
        // Muted while it works: it comes down, and stays down while it still works; the other stays.
        assertEquals(listOf(a1.tag), t.mute(setOf("m1/p1")).cancel)
        assertTrue(t.update("m1", LiveItem.Kind.Agent, listOf(a1, a2)).post.isEmpty())
        assertEquals(listOf(a2), t.items())
        // Unmuted: it is up again.
        assertEquals(listOf(a1), t.mute(emptySet()).post)
    }

    @Test
    fun aMutedAgentUpFromBeforeComesDown() {
        // Up when the app started, then muted: down at once.
        val t = LiveTracker()
        t.adopt(listOf(a1.tag, a2.tag))
        assertEquals(listOf(a2.tag), t.mute(setOf("m1/p2")).cancel)
        // Muted first, found up later: its list still names it, and it comes down rather than being posted.
        val later = LiveTracker()
        later.mute(setOf("m1/p1"))
        later.adopt(listOf(a1.tag))
        val c = later.update("m1", LiveItem.Kind.Agent, listOf(a1))
        assertTrue(c.post.isEmpty())
        assertEquals(listOf(a1.tag), c.cancel)
    }
}
