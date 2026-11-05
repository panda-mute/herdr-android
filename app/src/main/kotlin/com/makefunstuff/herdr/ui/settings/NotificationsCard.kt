package com.makefunstuff.herdr.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.core.ssh.Machine
import com.makefunstuff.herdr.push.MachinePush
import com.makefunstuff.herdr.push.PushState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.InfoLine
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.SegmentedPills
import com.makefunstuff.herdr.ui.design.SwitchRow
import com.makefunstuff.herdr.ui.design.switchColors
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.launch

private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
private val CHECKS = listOf(0 to "Never", 15 to "15 min", 60 to "1 hour")

/**
 * Notifications while the app is closed: the switch, what reaches the phone (and the watches), the
 * push app that carries them, each machine's herdr-push, Live Updates, the way to quiet hours, and
 * how often the app looks at the machines itself.
 */
@Composable
fun NotificationsCard(onQuietHours: () -> Unit, onRules: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by container.push.state.collectAsStateWithLifecycle()
    val machines by container.machines.machines.collectAsStateWithLifecycle()
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val busy by container.pushHosts.busy.collectAsStateWithLifecycle()
    val phone = container.phone
    var allowed by remember { mutableStateOf(phone.notificationsAllowed()) }
    var liveAllowed by remember { mutableStateOf(phone.liveAllowed()) }
    // Back from Android's settings or the store: the permission or the push app may have changed.
    var pushApps by remember { mutableStateOf(phone.distributors()) }
    LifecycleResumeEffect(Unit) {
        allowed = phone.notificationsAllowed()
        liveAllowed = phone.liveAllowed()
        pushApps = phone.distributors()
        onPauseOrDispose {}
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = phone.notificationsAllowed() }
    fun allow() = if (Build.VERSION.SDK_INT >= 33) ask.launch(POST_NOTIFICATIONS) else phone.openNotificationSettings()

    val c = Herdr.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text("Notifications", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        SwitchRow(
            "While the app is closed",
            detail = "When an agent or a board task needs you, something went wrong, or a watch you set fires. Approve, deny or reply from the notification.",
            checked = st.on,
        ) { on ->
            if (on) {
                container.pushHosts.turnOn()
                pushApps = phone.distributors()
                if (!allowed) allow()
            } else {
                container.pushHosts.turnOff()
            }
        }
        if (!st.on) return@Column

        if (!allowed) {
            Notice("Android blocks this app's notifications, so none can show.", color = c.red, icon = HerdrIcons.BellOff)
            SecondaryButton("Allow notifications") { allow() }
        }

        LinkRow("What reaches the phone", rulesLabel(st.rules), onRules)

        PushAppLine(st, pushApps, phone::label, onGet = phone::getPushApp, onAsk = { container.pushHosts.turnOn() })

        Text(
            "Each machine runs herdr-push, which the app sets up over SSH as the machine connects. It needs python3 there.",
            style = MaterialTheme.typography.bodySmall,
            color = c.muted,
        )
        Column {
            machines.forEachIndexed { i, m ->
                if (i > 0) RowDivider(inset = 0.dp)
                MachinePushRow(
                    machine = m,
                    push = st.machine(m.id),
                    host = hosts.firstOrNull { it.machine.id == m.id },
                    state = st,
                    busy = m.id in busy,
                    onLeaveOut = { off -> container.pushHosts.leaveOut(m.id, off) },
                    onTest = {
                        scope.launch {
                            runCatching { container.pushHosts.test(m.id) }
                                .onSuccess { context.toast("Sent. It shows in a moment.") }
                                .onFailure { context.toast(it.message ?: "Couldn't send a test") }
                        }
                    },
                )
            }
        }

        SwitchRow(
            "Live Updates",
            detail = "Board tasks and agents at work stay in the notifications with their stage and progress, and a Stop button. " +
                "On Android 16 they also show as a chip in the status bar, such as “Rev 2/4”.",
            checked = st.live,
        ) { on -> container.push.change { it.copy(live = on) } }
        if (st.live && liveAllowed == false) {
            Notice("Live Updates are off for this app in Android's settings, so they show as ordinary notifications.", icon = HerdrIcons.Zap)
            SecondaryButton("Turn on Live Updates") { phone.openLiveSettings() }
        }

        Column {
            RowDivider(inset = 0.dp)
            LinkRow("Quiet hours", st.quiet.label, onQuietHours)
            RowDivider(inset = 0.dp)
        }

        Text("The app also looks", style = MaterialTheme.typography.titleSmall)
        SegmentedPills(
            CHECKS.map { it.second },
            // The nearest choice, also for a value saved by hand.
            CHECKS.indices.minBy { kotlin.math.abs(CHECKS[it].first - st.checkMinutes) + if ((CHECKS[it].first == 0) != (st.checkMinutes <= 0)) 10_000 else 0 },
            onSelect = { i ->
                container.push.change { it.copy(checkMinutes = CHECKS[i].first) }
                phone.planChecks()
            },
        )
        Text(
            "It connects to each machine now and then while closed, for a machine without herdr-push, or a push that didn't arrive.",
            style = MaterialTheme.typography.bodySmall,
            color = c.muted,
        )
    }
}

/** The way to a page of the notification settings (quiet hours, what reaches the phone), with how it is set. */
@Composable
private fun LinkRow(title: String, label: String, onClick: () -> Unit) {
    val c = Herdr.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.strong)
            Text(label, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Spacer(Modifier.width(Space.s))
        Icon(HerdrIcons.ChevronRight, null, tint = c.muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun PushAppLine(st: PushState, pushApps: List<String>, label: (String) -> String, onGet: () -> Unit, onAsk: () -> Unit) {
    val c = Herdr.colors
    when {
        pushApps.isEmpty() -> {
            Notice("Machines reach this phone through a push app. Install ntfy (free, no account needed), then come back here.")
            SecondaryButton(onClick = onGet) {
                Icon(HerdrIcons.Download, null, Modifier.size(18.dp)); Text("Get ntfy")
            }
        }
        st.endpoint == null -> {
            InfoLine("Push app", (st.distributor ?: pushApps.first()).let(label) + " · asking for an address…")
            if (st.pushProblem != null) Notice(st.pushProblem, color = c.red, icon = HerdrIcons.CircleAlert)
            Row { QuietButton(onClick = onAsk) { Text("Ask again") } }
        }
        else -> InfoLine("Push app", label(st.distributor ?: pushApps.first()))
    }
}

/** One machine: whether its herdr-push sends to this phone, and the switch to leave it out. */
@Composable
private fun MachinePushRow(
    machine: Machine,
    push: MachinePush,
    host: HostState?,
    state: PushState,
    busy: Boolean,
    onLeaveOut: (Boolean) -> Unit,
    onTest: () -> Unit,
) {
    val c = Herdr.colors
    val ready = !push.off && state.endpoint != null && push.sentTo == state.endpoint
    val (words, bad) = when {
        !machine.enabled -> "The machine is turned off in the app" to false
        push.off -> "Left out" to false
        state.endpoint == null -> "Waits for the push app" to false
        busy -> "Setting up herdr-push…" to false
        push.problem != null -> push.problem to true
        ready -> (push.note?.let { "Sends notifications; $it" } ?: "Sends notifications") to false
        host?.online == true -> "Setting up herdr-push…" to false
        else -> "Set up when it connects" to false
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(machine.label, style = MaterialTheme.typography.titleMedium, color = if (machine.enabled) c.strong else c.off)
            Text(words, style = MaterialTheme.typography.bodySmall, color = if (bad) c.dangerText else c.muted, maxLines = 3)
        }
        if (ready && host?.online == true) {
            QuietButton(onClick = onTest) { Text("Test") }
        }
        Spacer(Modifier.width(Space.s))
        Switch(checked = !push.off, onCheckedChange = { onLeaveOut(!it) }, enabled = machine.enabled, colors = switchColors())
    }
}
