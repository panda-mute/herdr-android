package com.makefunstuff.herdr.push

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.host.Sessions
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.chat.AgentInput
import com.makefunstuff.herdr.core.health.HealthClient
import com.makefunstuff.herdr.core.health.HealthReport
import com.makefunstuff.herdr.core.health.ProcLabels
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.AlertAction
import com.makefunstuff.herdr.core.push.Alerts
import com.makefunstuff.herdr.core.push.HerdrPush
import com.makefunstuff.herdr.core.push.PushEvent
import com.makefunstuff.herdr.core.push.PushLook
import com.makefunstuff.herdr.core.push.PushMessages
import com.makefunstuff.herdr.core.push.PushSeal
import com.makefunstuff.herdr.core.push.TaskAlerts
import com.makefunstuff.herdr.core.push.WebPush
import com.makefunstuff.herdr.core.board.CardAnswer
import com.makefunstuff.herdr.core.shepherdr.V2Board
import com.makefunstuff.herdr.core.usage.HerdrUsage
import com.makefunstuff.herdr.core.usage.UsageRules
import com.makefunstuff.herdr.data.HostManager
import com.makefunstuff.herdr.data.MachineStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * The work behind notifications: what a push says, what a button does (over the machine's SSH
 * connection, made for it while the app is closed), and the app's own look at its machines.
 */
class AlertWork(
    private val hosts: HostManager,
    private val machines: MachineStore,
    private val store: PushStore,
    private val inbox: Inbox,
    private val live: LiveWork,
    private val quiet: QuietWork,
    private val tally: TallyStore,
    private val usage: com.makefunstuff.herdr.data.UsageStore,
    private val taskSpaces: TaskSpaces,
) {
    private val rules get() = store.state.value.rules

    /**
     * A push as the push app handed it over: herdr-push's sealed text, or a Shepherdr 3 service's task alert
     * (Web Push). False when it isn't one of ours.
     */
    fun received(body: ByteArray, now: Long = System.currentTimeMillis()): Boolean {
        val text = body.toString(Charsets.UTF_8)
        return if (text.trimStart().startsWith(PushSeal.PREFIX)) received(text, now) else receivedTask(body, now)
    }

    /**
     * A Shepherdr 3 service's task alert, opened with the Web Push keys the phone subscribed there with: shown with
     * its buttons, by its space's Notify, and what no longer waits on you taken down.
     */
    private fun receivedTask(body: ByteArray, now: Long): Boolean {
        for ((id, m) in store.state.value.machines) {
            val plain = WebPush.open(body, m.webPush ?: continue) ?: continue
            val label = machines.get(id)?.label ?: return true
            // Left out: the service has no way to unsubscribe, so what it still sends is dropped here.
            if (m.off) return true
            val p = TaskAlerts.parse(plain) ?: return true
            val space = taskSpaces.of(id, p.task ?: p.tag).orEmpty()
            val changes = TaskAlerts.changes(id, label, p, space, inbox.seen(TaskAlerts.prefix(id)))
            inbox.apply(changes, quiet = quiet.silent(now), hold = quiet.check(now), rules = rules)
            return true
        }
        return false
    }

    /** A push from a push app: opened with the machine's key and shown. False when it isn't one of ours. */
    fun received(text: String, now: Long = System.currentTimeMillis()): Boolean {
        val (machineId, msg) = PushMessages.open(text, store.keys().filterKeys { machines.get(it) != null }) ?: return false
        val label = machines.get(machineId)?.label ?: return false
        // Days old, or older than one already taken (sent again, or overtaken by a newer state): ours, but not shown.
        val last = store.state.value.machine(machineId).lastAt
        if (!PushMessages.fresh(msg.at, last, now / 1000)) return true
        if (msg.at > last) store.machine(machineId) { it.copy(lastAt = msg.at) }
        val changes = Alerts.all(machineId, label, msg.events, msg.host, taskAlerts = store.taskAlerts)
        tally.pushed(machineId, msg.events)
        msg.events.filterIsInstance<com.makefunstuff.herdr.core.push.PushEvent.Live>().forEach { live.received(machineId, label, it, msg.at, now) }
        if (msg.events.any { it is com.makefunstuff.herdr.core.push.PushEvent.Test }) {
            store.machine(machineId) { it.copy(testedAt = now, problem = null) }
        }
        // Who is at the machine as it sent this (a push that took long says so of then, not of now).
        msg.presence?.let { quiet.presence.record(machineId, label, it, minOf(now, msg.at * 1000).takeIf { msg.at > 0 } ?: now) }
        inbox.apply(changes, quiet = quiet.silent(now), hold = quiet.check(now), rules = rules)
        // A limit's warning carries its reading: the glance and the widget show it at once.
        msg.events.filterIsInstance<PushEvent.Usage>().forEach { usage.putLimit(machineId, it.provider, it.limit, msg.at) }
        return true
    }

    /** Carries out [action] of [alert] ([text]: what was typed in its reply box), with the outcome on the notification. */
    suspend fun perform(alert: Alert, action: AlertAction, text: String?) {
        if (action.reply && text.isNullOrBlank()) return inbox.show(alert, quiet = true, note = null)
        inbox.working(alert, if (action.reply) "Sending…" else "${action.label}…")
        try {
            val said = withTimeout(90_000) { hosts.borrow(alert.machineId) { s -> act(s, alert, action, text?.trim().orEmpty()) } }
            inbox.finished(alert, said)
        } catch (e: CancellationException) {
            inbox.show(alert, quiet = true, note = "Took too long. Try again, or open the app.")
            if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
        } catch (t: Throwable) {
            inbox.show(alert, quiet = true, note = t.message ?: "Couldn't do that")
        }
    }

    private suspend fun act(s: HostSession, alert: Alert, action: AlertAction, text: String): String = when (action.type) {
        AlertAction.Type.Choose -> {
            // Only press the option the notification showed, on the question it showed.
            AgentInput.choose(s.awaitAgent(alert.paneId), alert.paneId, action.question, action.option, action.optionLabel, action.details)
            s.refresh()
            "Answered: ${action.optionLabel}"
        }
        AlertAction.Type.ReplyAgent -> {
            val port = s.awaitAgent(alert.paneId)
            val status = port.status(alert.paneId) ?: throw IOException("That agent is gone.")
            AgentInput.send(port, alert.paneId, text, named = true, blocked = status == AgentStatus.Blocked)
            s.refresh()
            "Sent: $text"
        }
        AlertAction.Type.Accept -> {
            val client = s.awaitShepherdr()
            val ref = alert.title.substringBefore(" · ")
            // Accepted or sent back from elsewhere meanwhile: nothing merges.
            if (!client.evidence(alert.task).ready) throw IOException("$ref isn't waiting for Accept any more")
            client.accept(alert.task)
            "$ref accepted"
        }
        AlertAction.Type.AnswerTask -> {
            val board = s.awaitWorkBoard()
            val need = board.overview().needs.firstOrNull { n ->
                if (alert.task.isNotEmpty()) n.task == alert.task else n.task == null && n.chat == alert.chat
            } ?: throw IOException("Nothing waits on you there any more")
            if (need.secret != null) throw IOException("It asks for a secret: open the app to keep it")
            val card = V2Board.cards(listOf(need)).firstOrNull() ?: throw IOException("Open the app to answer it")
            board.answer(CardAnswer(card, choice = -1, other = text))
            "Sent: $text"
        }
    }

    /** The app's own look at every machine (an alarm while the app is closed): what needs you there now. */
    suspend fun checkAll() {
        val st = store.state.value
        if (!st.on) return
        coroutineScope {
            for (m in machines.machines.value) {
                if (!m.enabled || st.machine(m.id).off) continue
                launch {
                    try {
                        withTimeout(60_000) { check(m.id) }
                    } catch (e: CancellationException) {
                        if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                    } catch (_: Throwable) {
                        // Unreachable now: the next look, or the machine's own pushes, catch up.
                    }
                }
            }
        }
    }

    private suspend fun check(machineId: String) = hosts.borrow(machineId) { s ->
        // Online for classic herdr, or for herdr-core alone (the desktop app's); herdr-core looked for too.
        s.awaitConnection(30_000)
        withTimeoutOrNull(15_000) { s.state.first { it.herdrCoreLooked || !it.online } }
        val st = s.state.value
        if (!st.online) throw IOException("${s.machine.label} went offline")
        val api = if (st.classicMissing == null) s.awaitApi(30_000) else null
        val core = st.herdrCore?.snapshot
        if (api == null && core == null) throw IOException("Nothing answers on ${s.machine.label}")
        // Classic herdr's agents and herdr-core's.
        val snapshot = Sessions.merge(api?.snapshot(), core) ?: SessionSnapshot()
        val events = PushLook.events({ pane -> s.awaitAgent(pane).read(pane, lines = 60, visible = true) }, snapshot)
        // Only once both were read (herdr-core looked for, classic herdr not stopped): else agents would seem gone.
        if (st.agentsKnown) tally.agents(machineId, snapshot)
        val conn = s.awaitConnection()
        // Whether someone is at the machine, from its herdr-push (a machine without one can't tell).
        if (rules.holdAtMachine) HerdrPush.Client(conn, s.machine.label).presence()?.let { quiet.presence.record(machineId, s.machine.label, it, System.currentTimeMillis()) }
        inbox.apply(Alerts.all(machineId, s.machine.label, events, taskAlerts = store.taskAlerts), quiet = quiet.silent(), hold = quiet.check(), rules = rules)
        if (st.agentsKnown) {
            live.looked(machineId, s.machine.label, snapshot)
        }
        // Low on RAM or disk, for a machine without herdr-push (where it runs, the push said so already: shown once).
        runCatching { HealthClient { cmd, timeout -> conn.run(cmd, timeout) }.sample() }.getOrNull()?.let { sample ->
            val report = HealthReport.of(sample, ProcLabels.label(sample, snapshot)) ?: return@let
            inbox.apply(listOf(Alerts.health(machineId, s.machine.label, report)), quiet = quiet.silent(), hold = quiet.check(), rules = rules)
        }
        // Providers near or at a limit (where herdr-push runs, it told already: shown once per mark and window).
        runCatching {
            HerdrUsage.Client({ cmd, t -> conn.run(cmd, t) }, { cmd, input, t -> conn.runWithInput(cmd, input.byteInputStream(), t) }, s.machine.label).limits()
        }.getOrNull()?.let { r ->
            // The limits as they are now, for the glance and the status widget while the app is closed.
            usage.putLimits(machineId, r)
            val now = usage.nowSeconds()
            val changes = r.providers.filter { !it.stale && it.error.isEmpty() }.flatMap { p ->
                p.limits.mapNotNull { l ->
                    val level = UsageRules.level(l.percent)
                    // Under the marks a warning comes down only after its window, or well under them for a rolling one (UsageRules.CLEAR).
                    if (level == 0 && l.resets == null && l.percent >= UsageRules.CLEAR) null
                    else Alerts.usage(machineId, s.machine.label, PushEvent.Usage(p.id, p.name, l, level), now)
                }
            }
            if (changes.isNotEmpty()) inbox.apply(changes, quiet = quiet.silent(), hold = quiet.check(), rules = rules)
        }
    }

    /** As the app leaves the screen: what its machines show now was seen, so a later look stays quiet about it. */
    fun leaving() {
        val changes = hosts.hosts.value.flatMap { h ->
            val snap = h.view ?: return@flatMap emptyList()
            if (!h.online) return@flatMap emptyList()
            snap.agents.map { a ->
                Alerts.of(h.machine.id, h.machine.label, com.makefunstuff.herdr.core.push.PushEvent.Agent(a, snap.location(a.workspaceId, a.tabId)))
            }
        }
        if (changes.isNotEmpty()) inbox.markSeen(changes)
    }
}
