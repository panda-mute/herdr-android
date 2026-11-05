package com.makefunstuff.herdr.push

import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.AlertChange
import com.makefunstuff.herdr.core.push.AlertRules
import com.makefunstuff.herdr.core.push.Notify
import com.makefunstuff.herdr.core.push.QuietSummary
import com.makefunstuff.herdr.core.board.WorkOverview
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** Each state is shown once, whether a push or the app's own look reports it; what is over comes down. */
class InboxTest {
    private class Recorder : AlertSink {
        val log = mutableListOf<String>()
        override fun show(alert: Alert, quiet: Boolean, note: String?) { log += "show ${alert.tag} ${alert.sig}${if (quiet) " quiet" else ""}" }
        override fun working(alert: Alert, words: String) { log += "working ${alert.tag}" }
        override fun finished(alert: Alert, words: String) { log += "finished ${alert.tag}" }
        override fun summary(held: List<Alert>, summary: QuietSummary, quiet: Boolean) { log += "summary ${held.joinToString { it.tag }}: ${summary.title}" }
        override fun cancel(tag: String) { log += "cancel $tag" }
    }

    private fun alert(tag: String, sig: String) = Alert(tag, sig, Alert.Kind.NeedsYou, "m1", "api needs you", "Do you want to proceed?")

    private val file = Files.createTempDirectory("inbox").resolve("alerts.json").toFile()

    @Test fun sameStateOnce() {
        val sink = Recorder()
        val inbox = Inbox(file, sink)
        inbox.apply(listOf(AlertChange.Show(alert("agent:m1:p1", "blocked#4"))), quiet = false)
        inbox.apply(listOf(AlertChange.Show(alert("agent:m1:p1", "blocked#4"))), quiet = false)
        inbox.apply(listOf(AlertChange.Show(alert("agent:m1:p1", "done#5"))), quiet = true)
        assertEquals(listOf("show agent:m1:p1 blocked#4", "show agent:m1:p1 done#5 quiet"), sink.log)
    }

    @Test fun overComesDownOnlyWhenShown() {
        val sink = Recorder()
        val inbox = Inbox(file, sink)
        inbox.apply(listOf(AlertChange.Clear("agent:m1:p2")), quiet = false)
        inbox.apply(listOf(AlertChange.Show(alert("task:m1:r/7", "blocked|approval"))), quiet = false)
        inbox.apply(listOf(AlertChange.Clear("task:m1:r/7")), quiet = false)
        // Waiting again in the same way, later: a new notification.
        inbox.apply(listOf(AlertChange.Show(alert("task:m1:r/7", "blocked|approval"))), quiet = false)
        assertEquals(listOf("show task:m1:r/7 blocked|approval", "cancel task:m1:r/7", "show task:m1:r/7 blocked|approval"), sink.log)
    }

    @Test fun rememberedAcrossRestartsAndSeenInTheApp() {
        Inbox(file, Recorder()).apply(listOf(AlertChange.Show(alert("agent:m1:p1", "blocked#4"))), quiet = false)
        val sink = Recorder()
        val again = Inbox(file, sink)
        again.markSeen(listOf(AlertChange.Show(alert("agent:m1:p3", "done#9"))))
        again.apply(listOf(AlertChange.Show(alert("agent:m1:p1", "blocked#4")), AlertChange.Show(alert("agent:m1:p3", "done#9"))), quiet = false)
        assertEquals(emptyList<String>(), sink.log)
    }

    @Test fun testsAlwaysShow() {
        val sink = Recorder()
        val inbox = Inbox(file, sink)
        repeat(2) { inbox.apply(listOf(AlertChange.Show(alert("test:m1", ""))), quiet = false) }
        assertEquals(2, sink.log.size)
    }

    private fun finished(tag: String, sig: String) = Alert(tag, sig, Alert.Kind.Finished, "m1", "api finished", "Fixed the build", paneId = "p1")

    @Test fun quietHoursHoldWhatCanWait() {
        val sink = Recorder()
        val inbox = Inbox(file, sink)
        inbox.apply(listOf(AlertChange.Show(finished("agent:m1:p1", "done#4")), AlertChange.Show(alert("agent:m1:p2", "blocked#2"))), quiet = false, hold = true)
        // What needs you comes through; the finished one waits, and is not shown again by a later look.
        assertEquals(listOf("show agent:m1:p2 blocked#2"), sink.log)
        inbox.apply(listOf(AlertChange.Show(finished("agent:m1:p1", "done#4"))), quiet = false, hold = false)
        assertEquals(1, inbox.heldCount.value)
        inbox.apply(listOf(AlertChange.Show(finished("agent:m1:p3", "done#9"))), quiet = false, hold = true)
        assertEquals(true, inbox.release(quiet = false))
        assertEquals("summary agent:m1:p1, agent:m1:p3: 2 agents finished while it was quiet", sink.log.last())
        assertEquals(false, inbox.release(quiet = false))
        assertEquals(0, inbox.heldCount.value)
    }

    @Test fun oneHeldShowsAsItself() {
        val sink = Recorder()
        val inbox = Inbox(file, sink)
        inbox.apply(listOf(AlertChange.Show(finished("agent:m1:p1", "done#4"))), quiet = false, hold = true)
        inbox.release(quiet = true)
        assertEquals(listOf("show agent:m1:p1 done#4 quiet"), sink.log)
    }

    @Test fun overBeforeQuietEndsIsDropped() {
        val sink = Recorder()
        val inbox = Inbox(file, sink)
        inbox.apply(listOf(AlertChange.Show(finished("agent:m1:p1", "done#4"))), quiet = false, hold = true)
        // At work again: the finished one is old news.
        inbox.apply(listOf(AlertChange.Clear("agent:m1:p1")), quiet = false, hold = true)
        assertEquals(false, inbox.release(quiet = false))
        // Finished, then it asks: the question comes through and replaces the held one.
        inbox.apply(listOf(AlertChange.Show(finished("agent:m1:p2", "done#5"))), quiet = false, hold = true)
        inbox.apply(listOf(AlertChange.Show(alert("agent:m1:p2", "blocked#6"))), quiet = false, hold = true)
        assertEquals(0, inbox.heldCount.value)
        assertEquals(listOf("cancel agent:m1:p1", "show agent:m1:p2 blocked#6"), sink.log)
    }

    @Test fun heldOutlastsARestart() {
        Inbox(file, Recorder()).apply(listOf(AlertChange.Show(finished("agent:m1:p1", "done#4"))), quiet = false, hold = true)
        val sink = Recorder()
        val again = Inbox(file, sink)
        assertEquals(1, again.heldCount.value)
        again.release(quiet = false)
        assertEquals(listOf("show agent:m1:p1 done#4"), sink.log)
    }

    @Test fun rulesLeaveOutWhatIsSwitchedOff() {
        val sink = Recorder()
        val inbox = Inbox(file, sink)
        // Finished is off by default: not shown (and one up from before comes down), what needs you shows.
        inbox.apply(listOf(AlertChange.Show(finished("agent:m1:p1", "done#4")), AlertChange.Show(alert("agent:m1:p2", "blocked#2"))), quiet = false, rules = AlertRules())
        assertEquals(listOf("cancel agent:m1:p1", "show agent:m1:p2 blocked#2"), sink.log)
        // Turned on later, the same finish isn't news; the next one is.
        inbox.apply(listOf(AlertChange.Show(finished("agent:m1:p1", "done#4"))), quiet = false, rules = AlertRules(finished = true))
        inbox.apply(listOf(AlertChange.Show(finished("agent:m1:p1", "done#6"))), quiet = false, rules = AlertRules(finished = true))
        assertEquals("show agent:m1:p1 done#6", sink.log.last())
        // A task that waited for your yes and then failed its review, with Went wrong off: its notification comes down.
        inbox.apply(listOf(AlertChange.Show(alert("task:m1:r/7", "approval"))), quiet = false, rules = AlertRules(wentWrong = false))
        inbox.apply(listOf(AlertChange.Show(alert("task:m1:r/7", "failed").copy(kind = Alert.Kind.WentWrong))), quiet = false, rules = AlertRules(wentWrong = false))
        assertEquals(listOf("show task:m1:r/7 approval", "cancel task:m1:r/7"), sink.log.takeLast(2))
        // A space set to Notify: All tells each finish of its agents, with Finished off everywhere; one set to Off, nothing.
        val spaces = AlertRules().withSpace("m1/api", Notify.All).withSpace("m1/web", Notify.Off)
        inbox.apply(listOf(AlertChange.Show(finished("agent:m1:p7", "done#1").copy(space = "api"))), quiet = false, rules = spaces)
        assertEquals("show agent:m1:p7 done#1", sink.log.last())
        inbox.apply(listOf(AlertChange.Show(alert("agent:m1:p8", "blocked#1").copy(space = "web"))), quiet = false, rules = spaces)
        assertEquals("cancel agent:m1:p8", sink.log.last())
    }

    @Test fun aMutedAgentStaysQuiet() {
        val sink = Recorder()
        val muted = mutableSetOf("m1/p1")
        val inbox = Inbox(file, sink, muted = { m, p -> "$m/$p" in muted })
        val asks = alert("agent:m1:p1", "blocked#4").copy(paneId = "p1")
        // Muted: it asks, and nothing shows (one up from before comes down); another agent still comes through.
        inbox.apply(listOf(AlertChange.Show(asks), AlertChange.Show(alert("agent:m1:p2", "blocked#2").copy(paneId = "p2"))), quiet = false)
        assertEquals(listOf("cancel agent:m1:p1", "show agent:m1:p2 blocked#2"), sink.log)
        // Unmuted, its next news shows.
        muted.clear()
        inbox.apply(listOf(AlertChange.Show(alert("agent:m1:p1", "blocked#9").copy(paneId = "p1"))), quiet = false)
        assertEquals("show agent:m1:p1 blocked#9", sink.log.last())
    }

    @Test fun waitingListsWhatWaitsUntilItIsOver() {
        val inbox = Inbox(file, Recorder())
        inbox.apply(listOf(AlertChange.Show(alert("agent:m1:p1", "blocked#4"))), quiet = false, now = 1)
        inbox.apply(listOf(AlertChange.Show(alert("task:m1:HERD-4", "news:HERD-4 · Finished"))), quiet = false, now = 2)
        // What only tells (an agent that finished) isn't listed; newest first.
        inbox.apply(listOf(AlertChange.Show(finished("agent:m1:p2", "done#2"))), quiet = false, now = 3)
        assertEquals(listOf("task:m1:HERD-4", "agent:m1:p1"), inbox.waiting.value.map { it.alert.tag })
        // Answered at the desk: it finished, or the service took it down.
        inbox.apply(listOf(AlertChange.Show(finished("agent:m1:p1", "done#5"))), quiet = false, now = 4)
        inbox.apply(listOf(AlertChange.Clear("task:m1:HERD-4")), quiet = false, now = 5)
        assertEquals(emptyList<String>(), inbox.waiting.value.map { it.alert.tag })
    }

    @Test fun waitingThroughQuietHoursAndRestarts() {
        Inbox(file, Recorder()).apply(listOf(AlertChange.Show(alert("agent:m1:p1", "blocked#4"))), quiet = false, hold = true, now = 1)
        // Held for the night, swiped away or not: it waits, in the widget too.
        assertEquals(listOf("agent:m1:p1"), Inbox(file, Recorder()).waiting.value.map { it.alert.tag })
        // Switched off: not listed.
        val inbox = Inbox(file, Recorder())
        inbox.apply(listOf(AlertChange.Show(alert("agent:m1:p1", "blocked#5"))), quiet = false, rules = AlertRules(needsYou = false), now = 2)
        assertEquals(emptyList<String>(), inbox.waiting.value.map { it.alert.tag })
    }

    @Test fun aButtonUnderWayAndHowItWent() {
        val sink = Recorder()
        val inbox = Inbox(file, sink)
        val a = alert("agent:m1:p1", "blocked#4")
        inbox.apply(listOf(AlertChange.Show(a)), quiet = false)
        inbox.working(a, "Approve…")
        assertEquals("Approve…", inbox.waiting.value.single().busy)
        inbox.show(a, quiet = true, note = "That agent is gone.")
        assertEquals("" to "That agent is gone.", inbox.waiting.value.single().let { it.busy to it.note })
        inbox.working(a, "Approve…")
        inbox.finished(a, "Answered: Yes")
        assertEquals(emptyList<String>(), inbox.waiting.value.map { it.alert.tag })
    }

    @Test fun seenInTheAppAsOver() {
        val inbox = Inbox(file, Recorder())
        inbox.apply(listOf(AlertChange.Show(alert("agent:m1:p1", "blocked#4")), AlertChange.Show(alert("agent:m1:p2", "blocked#2"))), quiet = false, now = 1)
        // The app showed p1 at work again and p2 still asking.
        inbox.markSeen(listOf(AlertChange.Clear("agent:m1:p1"), AlertChange.Show(alert("agent:m1:p2", "blocked#2"))), now = 2)
        assertEquals(listOf("agent:m1:p2"), inbox.waiting.value.map { it.alert.tag })
    }

    @Test fun aBoardReadTakesOffWhatWasAcceptedElsewhere() {
        val inbox = Inbox(file, Recorder())
        val ready = alert("task:m1:HERD-4", "news:HERD-4 · Finished").copy(task = "HERD-4", accept = true)
        inbox.apply(listOf(AlertChange.Show(ready)), quiet = false, now = 1)
        inbox.looked("m1", WorkOverview(spaces = emptyList(), tasks = listOf(WorkTask("HERD-4", "HERD-4", "", "s1", WorkStatus.Ready))), now = 2)
        assertEquals(1, inbox.waiting.value.size)
        inbox.looked("m1", WorkOverview(spaces = emptyList(), tasks = listOf(WorkTask("HERD-4", "HERD-4", "", "s1", WorkStatus.Done))), now = 3)
        assertEquals(emptyList<String>(), inbox.waiting.value.map { it.alert.tag })
    }
}
