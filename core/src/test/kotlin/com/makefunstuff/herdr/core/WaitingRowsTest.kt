package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.board.WorkOverview
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.AlertChange
import com.makefunstuff.herdr.core.push.Alerts
import com.makefunstuff.herdr.core.push.PushEvent
import com.makefunstuff.herdr.core.push.TaskAlerts
import com.makefunstuff.herdr.core.push.TaskPush
import com.makefunstuff.herdr.core.push.Waiting
import com.makefunstuff.herdr.core.push.WaitingRows
import com.makefunstuff.herdr.core.shepherdr.V2Need
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Needs you widget's rows: what waits on you, with the buttons of its notification that need no words. */
class WaitingRowsTest {
    private val ready = TaskAlerts.alert("m1", "desk", TaskPush("HERD-4 · Finished", "Dark mode for the board", "HERD-4", url = "./#accept=HERD-4"), "")
    private val asks = TaskAlerts.alert("m1", "desk", TaskPush("HERD-7 · Needs you", "Asks which branch to use", "HERD-7", waits = true), "")
    private val assistant = TaskAlerts.alert("m1", "desk", TaskPush("tt-repo Assistant · Needs you", "Asks to go on", "p:tt/chat", waits = true), "")
    private val news = TaskAlerts.alert("m1", "desk", TaskPush("HERD-4 · Deploy done", "", "alert:9"), "")

    private fun screen(name: String) = javaClass.getResource("/screens/$name")!!.readText()

    private fun agent(status: String, screen: String?) = (
        Alerts.of(
            "m1", "desk",
            PushEvent.Agent(AgentInfo(name = "api", agent = "claude", agentStatusRaw = status, workspaceId = "w1", tabId = "t1", paneId = "w1:p1", stateChangeSeq = 3), "api", screen),
        ) as AlertChange.Show
        ).alert

    private val approve = agent("blocked", screen("claude-permission.txt"))

    @Test fun acceptAndYesNoAreButtons() {
        assertEquals(listOf("Accept"), WaitingRows.buttons(ready).map { ready.actions[it].label })
        assertEquals(listOf("Approve", "Deny"), WaitingRows.buttons(approve).map { approve.actions[it].label })
        // Both have buttons of their own: no Answer that opens the app.
        assertNull(WaitingRows.opens(ready))
        assertNull(WaitingRows.opens(approve))
    }

    @Test fun answeredInWordsOpensTheApp() {
        assertEquals(emptyList(), WaitingRows.buttons(asks))
        assertEquals("Answer", WaitingRows.opens(asks))
        // An agent asking with no menu: its Reply opens its chat.
        val free = agent("blocked", null)
        assertEquals("Reply", WaitingRows.opens(free))
    }

    @Test fun onlyWhatWaitsOnYou() {
        assertTrue(WaitingRows.waits(ready))
        assertTrue(WaitingRows.waits(approve))
        assertFalse(WaitingRows.waits(agent("done", null)))
        assertFalse(WaitingRows.waits(Alert("usage:m1:claude:5h", "usage:1:0", Alert.Kind.Limits, "m1", "Claude 82%", "")))
    }

    @Test fun newestFirst() {
        val rows = WaitingRows.order(listOf(Waiting(ready, at = 1), Waiting(asks, at = 3), Waiting(approve, at = 2)))
        assertEquals(listOf(asks.tag, approve.tag, ready.tag), rows.map { it.alert.tag })
    }

    @Test fun theBoardTellsWhatWaitsNoMore() {
        fun task(id: String, status: WorkStatus) = WorkTask(id, id, "", "s1", status)
        val accepted = WorkOverview(spaces = emptyList(), tasks = listOf(task("HERD-4", WorkStatus.Merging), task("HERD-7", WorkStatus.Need)))
        // Accepted elsewhere: the service says nothing of it, the board does.
        assertFalse(WaitingRows.stillWaits(ready, "m1", accepted))
        assertTrue(WaitingRows.stillWaits(ready, "m1", accepted.copy(tasks = listOf(task("HERD-4", WorkStatus.Ready)))))
        // A question is open while the board lists its need.
        assertFalse(WaitingRows.stillWaits(asks, "m1", accepted))
        assertTrue(WaitingRows.stillWaits(asks, "m1", accepted.copy(needs = listOf(V2Need("q1", "HERD-7/a", task = "HERD-7")))))
        assertFalse(WaitingRows.stillWaits(assistant, "m1", accepted.copy(needs = listOf(V2Need("q1", "HERD-7/a", task = "HERD-7")))))
        assertTrue(WaitingRows.stillWaits(assistant, "m1", accepted.copy(needs = listOf(V2Need("q2", "p:tt/chat")))))
        // What the board can't tell of: another machine's, an agent's own, news.
        assertTrue(WaitingRows.stillWaits(ready, "m2", accepted))
        assertTrue(WaitingRows.stillWaits(approve, "m1", accepted))
        assertTrue(WaitingRows.stillWaits(news, "m1", accepted))
    }
}
