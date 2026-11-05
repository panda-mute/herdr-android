package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.api.WorkspaceInfo
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.core.host.HostStatus
import com.makefunstuff.herdr.core.push.Notify
import com.makefunstuff.herdr.core.ssh.Machine
import com.makefunstuff.herdr.push.PushStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The triage and push settings an app from before Notify saved: their mutes and watches become spaces' Notify. */
class TriageStoreTest {
    private val dir = Files.createTempDirectory("triage").toFile()

    private fun host(id: String, vararg agents: Pair<String, String>) = HostState(
        Machine(id = id, label = id, host = id, user = "me"),
        status = HostStatus.Online,
        snapshot = SessionSnapshot(
            workspaces = agents.map { it.second }.distinct().map { WorkspaceInfo(it.lowercase(), label = it) },
            agents = agents.map { (pane, ws) -> AgentInfo(name = "claude", agent = "claude", agentStatusRaw = "working", workspaceId = ws.lowercase(), tabId = "t", paneId = pane) },
        ),
        herdrCoreLooked = true,
    )

    @Test fun oldMutesAndWatchesMoveOntoTheirSpaces() {
        val triageFile = java.io.File(dir, "triage.json").apply {
            writeText("""{"pins":["m1/w1:p1"],"snoozes":{"m1/w2:p1":{"until":99}},"muted":["m1/w2:p1","m2/w1:p1"],"used":true}""")
        }
        val pushFile = java.io.File(dir, "push.json").apply {
            writeText("""{"on":true,"phoneId":"phone-1","watches":[{"machineId":"m1","what":"AgentDone","paneId":"w1:p1","name":"api"}]}""")
        }
        val triage = TriageStore(triageFile)
        val push = PushStore(pushFile)
        // Loaded as before: the pin and both mutes (the snooze is gone with Snooze).
        assertEquals(listOf("m1/w1:p1"), triage.state.value.pins)
        assertTrue(triage.mutes("m2", "w1:p1"))

        // m1 read: its agents in Api (watched) and Web (muted); m2 not yet.
        val hosts = listOf(host("m1", "w1:p1" to "Api", "w2:p1" to "Web"))
        triage.move(hosts, push)
        assertEquals(mapOf("m1/api" to Notify.All, "m1/web" to Notify.Off), push.state.value.rules.spaces)
        assertTrue(push.state.value.watches.isEmpty())
        assertEquals(setOf("m2/w1:p1"), triage.state.value.muted)

        // Kept on disk, so a restart doesn't move them again or lose them.
        assertEquals(setOf("m2/w1:p1"), TriageStore(triageFile).state.value.muted)
        assertEquals(Notify.Off, PushStore(pushFile).state.value.rules.notifyOf("m1/web"))
        assertEquals(listOf("m1/w1:p1"), TriageStore(triageFile).state.value.pins)
    }
}
