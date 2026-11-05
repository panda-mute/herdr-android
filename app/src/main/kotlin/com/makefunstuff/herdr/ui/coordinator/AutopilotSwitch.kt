package com.makefunstuff.herdr.ui.coordinator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.board.WorkBoard
import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.core.shepherdr.Autopilot
import com.makefunstuff.herdr.core.shepherdr.V2Pilot
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.HerdrSwitch
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A space's Autopilot switch, as 3.1's Assistant header has it: it flips at once and herdr-task's answer says the rest
 * (a refusal flips it back, with a toast). [shown] is what the header shows meanwhile.
 */
class AutopilotFlip internal constructor(val shown: V2Pilot?, val flip: () -> Unit)

@Composable
fun rememberAutopilotFlip(board: WorkBoard?, space: WorkSpace?): AutopilotFlip {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pilot = space?.pilot
    var want by remember(space?.id) { mutableStateOf<Boolean?>(null) }
    // Until the machine's next snapshot says the same.
    LaunchedEffect(pilot?.on, want) { if (want != null && pilot?.on == want) want = null }
    val shown = pilot?.let { p ->
        when (want) {
            null -> p
            true -> p.copy(on = true)
            false -> p.copy(on = false, nextRun = null, pending = false, inflight = false)
        }
    }
    return AutopilotFlip(shown) {
        val b = board ?: return@AutopilotFlip
        val s = space ?: return@AutopilotFlip
        if (want != null || shown == null) return@AutopilotFlip
        val next = !shown.on
        want = next
        scope.launch {
            try {
                b.autopilot(s.id, next)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                want = null
                context.toast("Autopilot didn't turn ${if (next) "on" else "off"}: ${t.message ?: "Shepherdr refused it"}")
            }
        }
    }
}

/** The switch in the bar: the workbench's square switch, 48dp tall, said as "Autopilot" with its state. */
@Composable
fun AutopilotBarSwitch(on: Boolean, enabled: Boolean, onFlip: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).padding(horizontal = Space.xs)
            .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = { onFlip() })
            .semantics { contentDescription = Autopilot.TITLE },
        contentAlignment = Alignment.Center,
    ) {
        HerdrSwitch(on, enabled, Modifier.padding(horizontal = Space.xs))
    }
}

/**
 * Under the bar while on: "Autopilot" and what it waits for (3.1's header detail: "Next 10:00", "Queued", "Waking",
 * "On events", "Run now only"), and "Write a heartbeat" while the heartbeat is still herdr-task's starter.
 */
@Composable
fun AutopilotLine(p: V2Pilot, onWrite: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val c = Herdr.colors
    // "Next 10:00" becomes a weekday further off than a day, and back: looked at again now and then.
    var now by remember { mutableLongStateOf(container.clock()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = container.clock()
        }
    }
    val detail = Autopilot.headerDetail(p, now, container.zone())
    Row(
        modifier.fillMaxWidth().padding(start = Space.screen, end = Space.xs).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Box(Modifier.size(7.dp).background(if (p.inflight) c.accent else c.primary))
        Text(
            listOf(Autopilot.TITLE, detail).filter { it.isNotEmpty() }.joinToString(" · "),
            style = MaterialTheme.typography.labelLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (p.blank) QuietButton(onClick = onWrite, color = c.accent) { Text(Autopilot.WRITE_HEARTBEAT) }
    }
}
