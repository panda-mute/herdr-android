package com.makefunstuff.herdr.ui.agents

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.makefunstuff.herdr.core.home.AgentRow
import com.makefunstuff.herdr.core.home.HomeAgent
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.manage.ManageRow
import com.makefunstuff.herdr.ui.spaces.NotifyPicker
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Each action under a row swiped left. */
private val ACTION = 76.dp

/** How far right a row goes before letting go marks it seen. */
private val SEEN_AT = 96.dp

/**
 * What the Agents list does to one agent from its swipes, its long-press sheet and TalkBack's
 * actions: mark a finish seen, pin it on top. Kept on this phone
 * ([com.makefunstuff.herdr.data.TriageStore], [com.makefunstuff.herdr.data.SeenStore]); what reaches
 * the phone from it is its space's Notify, in the same sheet ([NotifyPicker]).
 */
class TriageActions(
    private val seen: (HomeAgent) -> Unit,
    private val pin: (HomeAgent, Boolean) -> Unit,
) {
    fun seen(row: AgentRow) = seen(row.agent)
    fun pin(row: AgentRow) = pin(row.agent, !row.pinned)

    /** The same, as TalkBack offers them on the row. */
    fun accessibility(row: AgentRow): List<CustomAccessibilityAction> = listOfNotNull(
        CustomAccessibilityAction("Mark seen") { seen(row); true }.takeIf { row.new },
        CustomAccessibilityAction(if (row.pinned) "Unpin" else "Pin to the top") { pin(row); true },
    )
}

/** The actions on the home list, kept in the phone's stores, with a toast for each. */
@Composable
fun rememberTriageActions(): TriageActions {
    val container = LocalContainer.current
    val context = LocalContext.current
    return remember(container, context) {
        TriageActions(
            seen = { a ->
                container.seen.mark(a.machineId, a.agent.paneId, a.finish)
                container.triage.change { it.copy(used = true) }
            },
            pin = { a, on ->
                container.triage.change { (if (on) it.pin(a.key) else it.unpin(a.key)).copy(used = true) }
                context.toast(if (on) "Pinned to the top" else "Unpinned")
            },
        )
    }
}

/**
 * A row that swipes: right marks a finish seen (when [AgentRow.new]), left shows Pin under it. [open]: whether it stays open on the left, so that only one row is open at a time.
 */
@Composable
fun SwipeRow(
    row: AgentRow,
    actions: TriageActions,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val c = Herdr.colors
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val reveal = with(density) { ACTION.toPx() }
    val seenAt = with(density) { SEEN_AT.toPx() }
    var x by remember { mutableFloatStateOf(0f) }
    suspend fun settle(to: Float) = animate(x, to) { v, _ -> x = v }
    LaunchedEffect(open) { settle(if (open) -reveal else 0f) }
    val drag = rememberDraggableState { d -> x = (x + d).coerceIn(-reveal * 1.1f, if (row.new) seenAt * 1.5f else 0f) }
    Box(Modifier.fillMaxWidth()) {
        if (x > 0f) {
            val past = x >= seenAt
            Row(
                Modifier.matchParentSize().background(if (past) c.tint(c.done) else c.card).clearAndSetSemantics {}.padding(start = Space.screen + Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HerdrIcons.CheckCheck, null, tint = if (past) c.done else c.muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Space.s))
                Text("Seen", style = MaterialTheme.typography.labelLarge, color = if (past) c.done else c.muted)
            }
        }
        if (x < 0f) {
            Row(Modifier.matchParentSize().background(c.card), horizontalArrangement = Arrangement.End) {
                fun done(f: () -> Unit) { onOpen(false); f() }
                SwipeAction(if (row.pinned) "Unpin" else "Pin", if (row.pinned) HerdrIcons.PinOff else HerdrIcons.Pin, c.text) { done { actions.pin(row) } }
            }
        }
        Box(
            Modifier.offset { IntOffset(x.roundToInt(), 0) }.background(c.ground)
                .draggable(
                    drag, Orientation.Horizontal,
                    onDragStopped = { v ->
                        when {
                            row.new && x >= seenAt -> { actions.seen(row); settle(0f); onOpen(false) }
                            x < -reveal / 2 || v < -1500f -> { settle(-reveal); onOpen(true) }
                            else -> { settle(0f); onOpen(false) }
                        }
                    },
                ),
        ) {
            content()
            // Open: a tap on the row closes it rather than opening the chat.
            if (open || x < 0f) Box(Modifier.matchParentSize().clickable(onClickLabel = "Close") { onOpen(false); scope.launch { settle(0f) } })
        }
    }
}

@Composable
private fun SwipeAction(label: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Column(
        Modifier.width(ACTION).fillMaxHeight().clickable(onClick = onClick).padding(vertical = Space.s),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, modifier = Modifier.padding(top = Space.xs))
    }
}

/**
 * The triage rows of an agent's long-press sheet: Mark seen (a finish not looked at), Pin to the top
 * (taken back from the same row), and its space's Notify (All / Needs me / Off).
 */
@Composable
fun TriageRows(agent: HomeAgent) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val triage by container.triage.state.collectAsStateWithLifecycle()
    val seen by container.seen.marks.collectAsStateWithLifecycle()
    val key = agent.key
    val new = agent.agent.agentStatus == com.makefunstuff.herdr.core.api.AgentStatus.Done && seen[key] != agent.finish
    if (new) ManageRow("Mark seen", icon = HerdrIcons.CheckCheck, detail = "It leaves the Done list") {
        container.seen.mark(agent.machineId, agent.agent.paneId, agent.finish)
        container.triage.change { it.copy(used = true) }
        context.toast("Marked seen")
    }
    val pinned = triage.pinned(key)
    ManageRow(if (pinned) "Unpin" else "Pin to the top", icon = if (pinned) HerdrIcons.PinOff else HerdrIcons.Pin, selected = pinned) {
        container.triage.change { (if (pinned) it.unpin(key) else it.pin(key)).copy(used = true) }
        context.toast(if (pinned) "Unpinned" else "Pinned to the top")
    }
    NotifyPicker(agent.space)
}
