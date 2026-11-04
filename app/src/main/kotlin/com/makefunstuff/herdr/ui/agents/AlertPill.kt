package com.makefunstuff.herdr.ui.agents

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.makefunstuff.herdr.Panes
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.board.Approvals
import com.makefunstuff.herdr.core.home.Waiting
import com.makefunstuff.herdr.core.home.WaitingNews
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.LiveItems
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.AgentMark
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.delay

/** What the in-app alert shows: something that newly needs you (and how many more came with it), or a watch that fired. */
sealed interface PillItem {
    val id: String

    data class Needs(val waiting: Waiting, val more: Int, override val id: String) : PillItem

    data class Fired(val alert: Alert, override val id: String) : PillItem
}

/** How long the alert stays before it leaves by itself. */
const val PILL_MS = 5_000L

/**
 * The in-app alert: when another agent (or a board task) needs you while you are in the app, one
 * black line drops from the top ("api-server · needs you: run db:migrate?"); a tap opens it, and it
 * leaves after a few seconds. Never about the chat (or task) open now, and not on the Agents list,
 * which shows it already. A watch that fires while the app is open shows here too, there as well.
 */
@Composable
fun AlertPill(
    nav: NavHostController,
    panes: Panes,
    entry: NavBackStackEntry?,
    needsYou: List<Waiting>,
    agentsRead: Set<String>,
    boardsRead: Set<String>,
    fired: kotlinx.coroutines.flow.Flow<Alert>,
) {
    val news = remember { WaitingNews() }
    var item by remember { mutableStateOf<PillItem?>(null) }
    var count by remember { mutableLongStateOf(0L) }
    val route = entry?.destination?.route
    val args: (String) -> String? = { k -> entry?.arguments?.getString(k) }
    val hosts by LocalContainer.current.hosts.hosts.collectAsStateWithLifecycle()
    LaunchedEffect(needsYou, agentsRead, boardsRead) {
        val fresh = news.update(needsYou, agentsRead, boardsRead).filter { !it.isOpen(route, args) }
        // The Agents list in front shows it already.
        if (fresh.isNotEmpty() && route != Routes.AGENTS) item = PillItem.Needs(fresh.first(), fresh.size - 1, "n${count++}")
    }
    LaunchedEffect(fired) { fired.collect { item = PillItem.Fired(it, "f${count++}") } }
    val shown = item
    LaunchedEffect(shown?.id) {
        if (shown != null) {
            delay(PILL_MS)
            if (item?.id == shown.id) item = null
        }
    }
    // Opened meanwhile (or the Agents list in front, which lists what needs you): it goes.
    val visible = shown != null && (shown is PillItem.Fired || route != Routes.AGENTS) && !shown.isOpen(route, args)
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Space.m, vertical = Space.s), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible,
            enter = fadeIn(tween(150)) + slideInVertically(tween(220)) { -it },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { -it },
        ) {
            // The last one stays drawn while it slides away.
            val last = remember { mutableStateOf(shown) }
            if (shown != null) last.value = shown
            last.value?.let { p -> Pill(p) { item = null; open(nav, panes, p, hosts) } }
        }
    }
}

/** The black line itself: the agent's mark (or a bell), the words on one line, how many more. */
@Composable
private fun Pill(p: PillItem, onOpen: () -> Unit) {
    val ink = Color(0xFFF5F5F7)
    val words = when (p) {
        is PillItem.Needs -> words(p.waiting)
        is PillItem.Fired -> listOf(p.alert.title, p.alert.text).filter { it.isNotBlank() }.joinToString(" · ")
    }
    Surface(
        color = Color.Black, contentColor = ink, shape = HerdrShapes.Pill, shadowElevation = 6.dp,
        modifier = Modifier.widthIn(max = 560.dp).clickable(onClickLabel = "Open", onClick = onOpen)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(start = Space.s, end = Space.l, top = Space.s, bottom = Space.s).heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            when (p) {
                is PillItem.Needs -> {
                    val agent = when (val w = p.waiting) {
                        is Waiting.Question -> w.agent
                        is Waiting.Task -> w.agent
                        is Waiting.Coordinator -> null
                    }
                    if (agent != null) AgentMark(agent.agent.agent, size = 26.dp)
                    else Icon(HerdrIcons.SquareKanban, null, tint = Color(0xFFF0A830), modifier = Modifier.padding(4.dp).size(18.dp))
                }
                is PillItem.Fired -> Icon(HerdrIcons.BellRing, null, tint = Color(0xFFF0A830), modifier = Modifier.padding(4.dp).size(18.dp))
            }
            Spacer(Modifier.width(Space.s))
            Text(
                words, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            if (p is PillItem.Needs && p.more > 0) {
                Spacer(Modifier.width(Space.s))
                Text("+${p.more}", style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = 0.7f))
            }
        }
    }
}

/** "api-server · needs you: run db:migrate?", "AND-9 · ready: Board view". */
@Composable
private fun words(w: Waiting): String = when (w) {
    is Waiting.Question -> {
        val asked = rememberAsked(w.agent)
        val who = w.task?.name ?: w.agent.workspace.ifEmpty { w.agent.agent.displayName }
        "$who · needs you: " + (asked?.question ?: w.agent.agent.subtitle ?: "waiting for your answer")
    }
    is Waiting.Task -> "${w.task.name} · ${w.work?.status?.label ?: w.task.status.word}: ${w.task.title}"
    is Waiting.Coordinator -> "${w.space.name}'s coordinator · needs you"
}

/** Opens what [p] is about: the agent's chat, or the task (finished work at its review card). */
private fun open(nav: NavHostController, panes: Panes, p: PillItem, hosts: List<HostState>) {
    when (p) {
        is PillItem.Needs -> when (val w = p.waiting) {
            is Waiting.Question -> panes.openAgent(nav, w.machineId, w.agent.agent, hosts.any { it.machine.id == w.machineId && it.onShepherdr })
            is Waiting.Task -> if (w.work == null && Approvals.waits(w.task)) panes.open(nav, Routes.review(w.machineId, w.task.id)) else panes.openBoard(nav, w.machineId, w.task.id)
            is Waiting.Coordinator -> panes.open(nav, Routes.coordinator(w.machineId, w.space.id))
        }
        is PillItem.Fired -> {
            val a = p.alert
            when {
                a.paneId.isNotEmpty() -> panes.openPane(nav, hosts.firstOrNull { it.machine.id == a.machineId }, a.machineId, a.paneId)
                a.taskId.isNotEmpty() && a.review -> panes.open(nav, Routes.review(a.machineId, a.taskId))
                a.taskId.isNotEmpty() -> panes.openBoard(nav, a.machineId, a.taskId)
            }
        }
    }
}

/** What the screen in front shows: an agent's chat or terminal, the Coordinator, a task on its board or its review card. */
private data class Front(val machine: String, val pane: String? = null, val task: String? = null, val coordinator: Boolean = false)

private fun front(route: String?, args: (String) -> String?): Front? {
    fun arg(k: String) = args(k)?.let(Uri::decode)?.ifEmpty { null }
    val mid = arg("mid") ?: return null
    return when (route) {
        Routes.AGENT, Routes.TERMINAL -> Front(mid, pane = arg("pane"))
        Routes.COORDINATOR -> Front(mid, coordinator = true)
        Routes.BOARD, Routes.REVIEW -> Front(mid, task = arg("task"))
        else -> null
    }
}

/** Whether [this] is about what the screen in front shows. */
private fun Waiting.isOpen(route: String?, args: (String) -> String?): Boolean {
    val f = front(route, args) ?: return false
    if (f.machine != machineId) return false
    if (this is Waiting.Coordinator) return f.coordinator
    val agent = when (this) {
        is Waiting.Question -> agent
        is Waiting.Task -> agent
        is Waiting.Coordinator -> null
    }
    return (f.pane != null && agent?.agent?.paneId == f.pane) ||
        (f.task != null && task?.id == f.task) ||
        (f.coordinator && agent != null && LiveItems.isCoordinator(agent.agent.name.orEmpty()))
}

private fun PillItem.isOpen(route: String?, args: (String) -> String?): Boolean = when (this) {
    is PillItem.Needs -> waiting.isOpen(route, args)
    is PillItem.Fired -> front(route, args)?.let { f ->
        f.machine == alert.machineId && ((f.pane != null && f.pane == alert.paneId) || (f.task != null && f.task == alert.taskId))
    } == true
}
