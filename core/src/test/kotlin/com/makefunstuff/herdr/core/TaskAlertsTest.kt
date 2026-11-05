package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.board.WorkOverview
import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.guard.Guarded
import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.AlertAction
import com.makefunstuff.herdr.core.push.AlertChange
import com.makefunstuff.herdr.core.push.AlertRules
import com.makefunstuff.herdr.core.push.Alerts
import com.makefunstuff.herdr.core.push.Notify
import com.makefunstuff.herdr.core.push.PushEvent
import com.makefunstuff.herdr.core.push.TaskAlerts
import com.makefunstuff.herdr.core.push.TaskPush
import com.makefunstuff.herdr.core.push.WebPush
import com.makefunstuff.herdr.core.push.WebPushKeys
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebPushTest {
    private fun unb64(s: String) = Base64.getUrlDecoder().decode(s)

    /** Encrypted by the 3.1 service's own `encrypt` (service/push.ts, run with bun) for a fresh subscription. */
    private val fromService = WebPushKeys(
        private = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQggCAP_wWnT3AEYZh4OaT9WEE804Wp4c64bA3sxo3Hu-WhRANCAASfvWhzd41x1zexic4NgtekNINvytw8fcJRz_FQTOHjTYnRA3RUcvMULQGdYJv9BWwyNWgTtPuY7XB-lwq1Bh9p",
        public = "BJ-9aHN3jXHXN7GJzg2C16Q0g2_K3Dx9wlHP8VBM4eNNidEDdFRy8xQtAZ1gm_0FbDI1aBO0-5jtcH6XCrUGH2k",
        auth = "KKIwmA4vqG2OQenaUsb_Hw",
    )
    private val serviceBody = "DL-T7hDBH3RZ2e_vkDGehgAAEABBBH1X-iMMXz2Edv0LDOXIaBQ6UCwFhjeKOVnxlgtYKczr_ASdcrbsGNMXGyDCiFBclo8zIifQl1iPZyvf1OFgXL4m_pSrhO_Huo5fBQsBlKOmU1KQ0Bg0cYb2VvhE5nxIEaMLsEipokbTM2992kCUu22nYgM5psVUQJiKV7OYRCN4joQqc9EUqIdxd0MJ_U-oTfPtut6FOxF0eO2bLCrn4FeZ-WcCAGIpPnzHu8Gf6fPg5mGyYA95KlZOrhU6AKDNe6aYJ-rIZxvnYtCqq-gdj6tUoTEF"

    @Test
    fun opensWhatTheServiceSent() {
        val plain = WebPush.open(unb64(serviceBody), fromService)!!.toString(Charsets.UTF_8)
        assertEquals("""{"title":"HERD-4 · Finished","body":"Dark mode for the board","tag":"HERD-4","url":"./#accept=HERD-4","badge":2,"open":["HERD-7"]}""", plain)
    }

    @Test
    fun anotherSubscriptionsKeysOpenNothing() {
        assertNull(WebPush.open(unb64(serviceBody), WebPush.newKeys()))
        assertNull(WebPush.open(unb64(serviceBody), fromService.copy(auth = WebPush.newKeys().auth)))
        // A herdr-push message on the same address is not a Web Push one.
        assertNull(WebPush.open("hp1.abc".toByteArray(), fromService))
        val damaged = unb64(serviceBody).also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }
        assertNull(WebPush.open(damaged, fromService))
    }

    @Test
    fun newKeysAreWhatAPushSubscriptionHolds() {
        val k = WebPush.newKeys()
        assertEquals(65, unb64(k.public).size)
        assertEquals(4, unb64(k.public)[0].toInt())
        assertEquals(16, unb64(k.auth).size)
    }

    /** The app's own keys, encrypted for by the installed 3.1 service with bun, where both are on this machine. */
    @Test
    fun theInstalledServiceEncryptsForTheAppsKeys() {
        val service = File(System.getProperty("user.home"), ".local/share/shepherdr/service/push.ts")
        val bun = listOf(System.getenv("BUN") ?: "", System.getProperty("user.home") + "/.local/share/mise/installs/bun/latest/bin/bun", "/usr/bin/bun")
            .firstOrNull { it.isNotEmpty() && File(it).canExecute() }
        assumeTrue(service.exists() && bun != null, "needs the 3.1 service and bun on this machine")
        val k = WebPush.newKeys()
        val script = File.createTempFile("webpush", ".ts").apply {
            writeText(
                """
                import { encrypt } from ${"\"" + service.absolutePath + "\""};
                const b = await encrypt(new TextEncoder().encode(JSON.stringify({ title: "HERD-4 · Needs you", body: "Asks which branch", tag: "HERD-4", waits: true })), "${k.public}", "${k.auth}");
                console.log(Buffer.from(b).toString("base64url"));
                """.trimIndent(),
            )
            deleteOnExit()
        }
        val p = ProcessBuilder(bun, script.absolutePath).redirectErrorStream(true).start()
        p.waitFor(60, TimeUnit.SECONDS)
        val out = p.inputStream.bufferedReader().readText().trim().lines().last()
        val push = TaskAlerts.parse(WebPush.open(unb64(out), k)!!)!!
        assertEquals("HERD-4 · Needs you", push.title)
        assertTrue(push.waits)
    }
}

class TaskAlertsTest {
    private val ready = TaskPush("HERD-4 · Finished", "Dark mode for the board", "HERD-4", url = "./#accept=HERD-4", open = listOf("HERD-7"))
    private val needs = TaskPush("HERD-7 · Needs you", "Asks which branch to use", "HERD-7", waits = true, open = listOf("HERD-7"))

    @Test
    fun readsTheServicesAlert() {
        val p = TaskAlerts.parse("""{"title":"HERD-4 on Mac mini · Finished","body":"Dark mode","tag":"mini:HERD-4","url":"./#accept=mini%3AHERD-4","badge":2,"open":[]}""".toByteArray())!!
        assertEquals("mini:HERD-4", p.accept)
        assertEquals("mini:HERD-4", p.task)
        assertEquals(emptyList(), p.open)
        assertNull(TaskAlerts.parse("""{"body":"no title"}""".toByteArray()))
        assertNull(TaskAlerts.parse("not json".toByteArray()))
    }

    @Test
    fun aReadyTaskGetsAcceptAndOpensItsCard() {
        val a = TaskAlerts.alert("m1", "devbox", ready, space = "herdr-android")
        assertEquals("HERD-4 · Finished", a.title)
        assertEquals("Dark mode for the board", a.text)
        assertEquals("HERD-4", a.task)
        assertTrue(a.accept)
        assertEquals(listOf(AlertAction.Type.Accept), a.actions.map { it.type })
        // Accept merges: the fingerprint first where that is on, as on the Accept card.
        assertEquals(Guarded.Merge.key, a.actions.single().guard)
        assertEquals(Alert.Kind.NeedsYou, a.kind)
    }

    @Test
    fun aQuestionGetsAnAnswerBox() {
        val a = TaskAlerts.alert("m1", "devbox", needs, space = "")
        assertEquals("HERD-7", a.task)
        assertEquals(listOf(AlertAction.Type.AnswerTask), a.actions.map { it.type })
        assertTrue(a.actions.single().reply)
        assertEquals("Answer HERD-7", a.actions.single().hint)
        // An Assistant's question: its chat, no task.
        val asks = TaskAlerts.alert("m1", "devbox", TaskPush("app Assistant · Needs you", "Which one?", "p:sp1", waits = true), "")
        assertEquals("", asks.task)
        assertEquals("p:sp1", asks.chat)
        // An agent's own alert: words only.
        assertEquals(emptyList(), TaskAlerts.alert("m1", "devbox", TaskPush("HERD-7 · Login expired", "", "alert:7"), "").actions)
    }

    @Test
    fun oneNotificationPerTaskAndWhatNoLongerWaitsComesDown() {
        val q = TaskAlerts.alert("m1", "devbox", needs, "")
        val shown = mapOf(q.tag to q.sig, TaskAlerts.tag("m1", "HERD-9") to "waits:HERD-9 · Needs you\nold", TaskAlerts.tag("m1", "HERD-2") to "news:HERD-2 · Finished\n")
        // HERD-4 is Ready now; HERD-7 still waits, HERD-9 was answered, HERD-2's Finished stays.
        val changes = TaskAlerts.changes("m1", "devbox", ready, "", shown)
        assertEquals(listOf(TaskAlerts.tag("m1", "HERD-9")), changes.filterIsInstance<AlertChange.Clear>().map { it.tag })
        assertEquals(TaskAlerts.tag("m1", "HERD-4"), assertIs<AlertChange.Show>(changes.last()).tag)
        // Its Ready takes its Needs you's place: one tag per task.
        assertEquals(TaskAlerts.tag("m1", "HERD-7"), TaskAlerts.alert("m1", "devbox", ready.copy(tag = "HERD-7", url = "./#accept=HERD-7"), "").tag)
        // Another machine's are left alone.
        assertEquals(1, TaskAlerts.changes("m2", "mini", ready, "", shown).size)
    }

    @Test
    fun theSpacesNotifyDecides() {
        val a = TaskAlerts.alert("m1", "devbox", ready, space = "herdr-android")
        val q = TaskAlerts.alert("m1", "devbox", needs, space = "herdr-android")
        val off = AlertRules().withSpace(Notify.key("m1", "herdr-android"), Notify.Off)
        assertTrue(!off.allows(a) && !off.allows(q))
        val needsMe = AlertRules(finished = true).withSpace(Notify.key("m1", "herdr-android"), Notify.NeedsMe)
        // Ready waits on your Accept: it comes on Needs me too, as 3.1 alerts every task.
        assertTrue(needsMe.allows(a) && needsMe.allows(q))
        // A space nobody set follows Settings' Needs you switch.
        assertTrue(AlertRules().allows(a))
        assertTrue(!AlertRules(needsYou = false).allows(TaskAlerts.alert("m1", "devbox", ready, "")))
    }

    @Test
    fun spacesByTagFromTheBoard() {
        val o = WorkOverview(
            spaces = listOf(WorkSpace("sp1", "herdr-android", chat = "p:sp1"), WorkSpace("sp2", "website")),
            tasks = listOf(WorkTask("HERD-4", "HERD-4", "Dark mode", "sp1", WorkStatus.Ready), WorkTask("mini:WEB-2", "WEB-2", "Logo", "sp2", WorkStatus.Need)),
        )
        assertEquals(mapOf("HERD-4" to "herdr-android", "mini:WEB-2" to "website", "p:sp1" to "herdr-android"), TaskAlerts.spaces(o))
    }

    @Test
    fun aTasksAgentAlertsAsItsTaskOnce() {
        val agent = AgentInfo(paneId = "hc:a1", workspaceId = "w", tabId = "t", agentStatusRaw = "blocked", shepherdrTask = "HERD-7")
        val e = PushEvent.Agent(agent, "app › HERD-7")
        assertIs<AlertChange.Clear>(Alerts.of("m1", "devbox", e, taskAlerts = true))
        assertIs<AlertChange.Show>(Alerts.of("m1", "devbox", e))
        assertIs<AlertChange.Show>(Alerts.of("m1", "devbox", PushEvent.Agent(agent.copy(shepherdrTask = null), "app › claude"), taskAlerts = true))
    }
}
