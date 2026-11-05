package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.home.HomeAgent
import com.makefunstuff.herdr.core.home.Waiting
import com.makefunstuff.herdr.core.home.WaitingNews
import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.AlertChange
import com.makefunstuff.herdr.core.push.AlertRules
import com.makefunstuff.herdr.core.push.Alerts
import com.makefunstuff.herdr.core.push.Presence
import com.makefunstuff.herdr.core.push.PresenceBook
import com.makefunstuff.herdr.core.push.PushEvent
import com.makefunstuff.herdr.core.push.PushMessages
import com.makefunstuff.herdr.core.push.QuietHours
import com.makefunstuff.herdr.core.push.Notify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The notification rules: kinds switched on and off, keeping quiet at the machine, one-shot watches, and the in-app alert's news. */
class RulesTest {
    private fun agent(status: String, seq: Long = 7, pane: String = "w1:p1") = AgentInfo(
        name = "api", agent = "claude", agentStatusRaw = status, workspaceId = "w1", tabId = "t1", paneId = pane, stateChangeSeq = seq,
        title = "Speed up /jobs",
    )

    private fun show(c: AlertChange): Alert = assertIs<AlertChange.Show>(c).alert

    @Test
    fun eachAlertHasItsKind() {
        assertEquals(Alert.Kind.Finished, show(Alerts.of("m1", "devbox", PushEvent.Agent(agent("done"), "api"))).kind)
        assertEquals(Alert.Kind.NeedsYou, show(Alerts.of("m1", "devbox", PushEvent.Agent(agent("blocked"), "api"))).kind)
    }

    @Test
    fun finishedIsOffByDefault() {
        val r = AlertRules()
        fun a(kind: Alert.Kind) = Alert("t", "", kind, "m1", "", "")
        assertTrue(r.allows(a(Alert.Kind.NeedsYou)))
        assertFalse(r.allows(a(Alert.Kind.Finished)))
        assertTrue(r.allows(a(Alert.Kind.WentWrong)))
        // A provider near its limit comes by default, and can be turned off.
        assertTrue(r.allows(a(Alert.Kind.Limits)))
        assertFalse(r.copy(limits = false).allows(a(Alert.Kind.Limits)))
        // A test always comes.
        val none = AlertRules(needsYou = false, finished = false, wentWrong = false, limits = false)
        assertTrue(none.allows(a(Alert.Kind.Test)))
        assertFalse(none.allows(a(Alert.Kind.NeedsYou)))
        assertEquals("Needs you, Went wrong and Limits", r.label)
        assertEquals("Needs you and Went wrong", r.copy(limits = false).label)
        assertEquals("Needs you, Finished, Went wrong and Limits", AlertRules.ALL.label)
        assertEquals("Nothing", none.label)
        // Quiet hours hold what finished and a limit; what went wrong comes through, as what needs you does.
        assertTrue(QuietHours.holds(a(Alert.Kind.Finished)))
        assertFalse(QuietHours.holds(a(Alert.Kind.WentWrong)))
        assertTrue(QuietHours.holds(a(Alert.Kind.Limits)))
    }

    @Test
    fun atTheMachineForTwoMinutes() {
        val book = PresenceBook()
        val t0 = 1_000_000_000L
        assertFalse(book.present(t0))
        // Typed 30 s before devbox sent its push.
        book.record("m1", "devbox", Presence(typedAgo = 30, unlocked = null), t0)
        assertEquals("devbox", book.where(t0))
        assertTrue(book.present(t0 + 89_000))
        assertFalse(book.present(t0 + 91_000))
        // A machine whose screen is unlocked counts while what it said is fresh, whichever machine pings.
        book.record("m2", "gpu-box", Presence(typedAgo = null, unlocked = true), t0 + 100_000)
        assertEquals("gpu-box", book.where(t0 + 200_000))
        assertFalse(book.present(t0 + 221_000))
        // Locked, and nobody typed for long: away.
        book.record("m2", "gpu-box", Presence(typedAgo = 3600, unlocked = false), t0 + 300_000)
        assertNull(book.where(t0 + 300_000))
    }

    @Test
    fun aPushSaysWhoIsAtTheMachine() {
        val msg = assertNotNull(PushMessages.parse("""{"v":1,"host":"devbox","at":5,"events":[{"type":"test"}],"present":{"typed":12,"unlocked":false}}"""))
        assertEquals(Presence(typedAgo = 12, unlocked = false), msg.presence)
        assertEquals(Presence(null, null), assertNotNull(PushMessages.parse("""{"v":1,"at":5,"events":[],"present":{"typed":null,"unlocked":null}}""")).presence)
        // An older herdr-push says nothing about it.
        assertNull(assertNotNull(PushMessages.parse("""{"v":1,"at":5,"events":[]}""")).presence)
    }

    @Test
    fun aSpacesNotifyDecidesForItsAgents() {
        fun agentAlert(kind: Alert.Kind, space: String = "api") = Alert("agent:m1:w1:p1", "s", kind, "m1", "", "", paneId = "w1:p1", space = space)
        val r = AlertRules()
        // Picking nothing follows the switches: Needs you on, Finished off by default.
        assertEquals(Notify.NeedsMe, r.everywhere)
        assertEquals(Notify.NeedsMe, r.notifyOf(Notify.key("m1", "API")))
        assertFalse(r.allows(agentAlert(Alert.Kind.Finished)))
        // All: its finishes come too (a watch, for good); Off: nothing, not even a question.
        val all = r.withSpace(Notify.key("m1", "API"), Notify.All)
        assertTrue(all.allows(agentAlert(Alert.Kind.Finished)))
        assertTrue(all.allows(agentAlert(Alert.Kind.NeedsYou)))
        assertFalse(all.allows(agentAlert(Alert.Kind.Finished, space = "web")))
        val off = r.withSpace("m1/api", Notify.Off)
        assertFalse(off.allows(agentAlert(Alert.Kind.NeedsYou)))
        // What isn't an agent's news follows the switches whatever its space.
        assertTrue(off.allows(Alert("health:m1", "low", Alert.Kind.NeedsYou, "m1", "", "")))
        assertTrue(off.allows(agentAlert(Alert.Kind.Test)))
        // Needs me: questions, no finishes, even with Finished on everywhere.
        val needs = AlertRules(finished = true).withSpace("m1/api", Notify.NeedsMe)
        assertEquals(Notify.All, needs.everywhere)
        assertTrue(needs.allows(agentAlert(Alert.Kind.NeedsYou)))
        assertFalse(needs.allows(agentAlert(Alert.Kind.Finished)))
        // Picking what it would get anyway, or nothing, follows the switches again.
        assertTrue(all.withSpace("m1/api", Notify.NeedsMe).spaces.isEmpty())
        assertTrue(off.withSpace("m1/api", null).spaces.isEmpty())
    }

    @Test
    fun anAgentsAlertSaysItsSpace() {
        val a = agent("done", seq = 9)
        val shown = Alerts.of("m1", "devbox", PushEvent.Agent(a, "herdr-android › main")) as AlertChange.Show
        assertEquals("herdr-android", shown.alert.space)
        assertEquals("api", Notify.spaceOf("api"))
    }

    @Test
    fun theInAppAlertTellsOnlyWhatIsNew() {
        fun asking(seq: Long, pane: String = "w1:p1") = Waiting.Question(HomeAgent("m1", "devbox", agent("blocked", seq, pane), "api › main"), null)
        val news = WaitingNews()
        // The machine's first read: what already waited isn't news.
        assertTrue(news.update(listOf(asking(4)), agentsRead = setOf("m1"), boardsRead = emptySet()).isEmpty())
        assertTrue(news.update(listOf(asking(4)), setOf("m1"), emptySet()).isEmpty())
        // Another agent asks, and the first asks again (a new question): both are news.
        val fresh = news.update(listOf(asking(6), asking(5, "w1:p2")), setOf("m1"), emptySet())
        assertEquals(listOf("agent/m1/w1:p1", "agent/m1/w1:p2"), fresh.map { it.key })
        // A task from a board read for the first time isn't news either.
        val t = Waiting.Task("m1", "devbox", WorkTask("HA-3", "HA-3", "Jobs", "s1", WorkStatus.Ready), null)
        assertTrue(news.update(listOf(t), setOf("m1"), setOf("m1")).isEmpty())
    }
}
