package com.makefunstuff.herdr.ui.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.LocalPanes
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.core.host.HostStatus
import com.makefunstuff.herdr.core.share.AskTargets
import com.makefunstuff.herdr.core.share.Shared
import com.makefunstuff.herdr.core.ssh.Machine
import com.makefunstuff.herdr.data.DraftStore
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.common.color
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.design.FieldLabel
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

/** Where a tap from outside the app goes: [Routes.ask]'s `to`. */
object Ask {
    /** The Quick Settings tile, the Ask widget and the launcher's shortcut: the Chat tab, the shown space's Assistant. */
    const val COORDINATOR = "coordinator"
    /** The status widget's counts: the Tasks tab, Needs you first. */
    const val BOARD = "board"
    /** The share sheet and "Send to the Assistant" on selected text: an Assistant's chat, with what was shared in its message box. */
    const val SHARE = "share"
}

/**
 * The way in from outside the app ([Ask]). The tile, the widgets and the shortcut go on at once: to
 * the Chat tab (the shown space's Assistant) or the Tasks tab. A share goes on at once when the
 * machine is clear (the Assistant used last, or the only machine that has one), and otherwise asks
 * which machine, with what is being sent above.
 */
@Composable
fun AskScreen(nav: NavController, to: String) {
    val container = LocalContainer.current
    val panes = LocalPanes.current
    val machines by container.machines.machines.collectAsStateWithLifecycle()
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val shared by container.shares.pending.collectAsStateWithLifecycle()
    val last by container.shares.last.collectAsStateWithLifecycle()
    val share = to == Ask.SHARE
    val board = to == Ask.BOARD
    val on = machines.filter { it.enabled }
    val candidates = on.map { m -> AskTargets.Candidate(m.id, boardOf(hosts.firstOrNull { it.machine.id == m.id })) }

    fun go(route: String) {
        // On a wide window a board opens on the right, beside what was open.
        val board = Routes.boardOf(route)
        if (board != null && panes.wide) {
            nav.popBackStack(Routes.ASK, inclusive = true)
            return panes.openBoard(nav, board.first)
        }
        nav.navigate(route) {
            popUpTo(Routes.ASK) { inclusive = true }
            launchSingleTop = true
        }
    }

    fun leave() {
        if (share) container.shares.cancel()
        nav.popBackStack()
    }

    fun target(machineId: String) = Start(if (board) Routes.board(machineId) else Routes.coordinator(machineId), machineId)

    // Decided once, as the screen opens: where to go on at once, or null to ask.
    val first = remember {
        when {
            share && container.shares.pending.value == null -> Start(Routes.TASKS)
            share -> AskTargets.choose(candidates, last, true)?.let(::target)
            board -> Start(Routes.TASKS)
            else -> Start(Routes.CHAT)
        }
    }
    var picked by remember { mutableStateOf<Start?>(null) }
    val start = first ?: picked
    LaunchedEffect(start) {
        if (start == null) return@LaunchedEffect
        val id = start.machineId
        if (share && id != null) container.shares.deliver(id) { container.drafts.add(DraftStore.coordinator(id), it) }
        go(start.route)
    }
    if (first != null) {
        Box(Modifier.fillMaxSize())
        return
    }

    BackHandler { leave() }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar(
                "Send to the Assistant",
                subtitle = if (on.size > 1) "Choose the machine" else null,
                onBack = ::leave,
            )
        },
    ) { padding ->
        if (on.isEmpty()) {
            EmptyState(
                "No machines yet",
                "Add the machine that runs herdr and Shepherdr, and its Assistant is a tap away from here.",
                icon = HerdrIcons.Monitor,
            ) { PrimaryButton("Add machine") { nav.navigate(Routes.edit()) { popUpTo(Routes.ASK) { inclusive = true } } } }
            return@Scaffold
        }
        val order = AskTargets.candidates(candidates, last).map { it.machineId } + candidates.filter { it.board == AskTargets.Board.None }.map { it.machineId }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.xs, bottom = Space.xxxl),
        ) {
            val s = shared
            if (share && s != null) item(key = "shared") { SharedCard(s) }
            item(key = "head") { SectionHeading("Machines", top = if (share) Space.xxl else Space.s) }
            item(key = "list") {
                QuietCard {
                    order.forEachIndexed { i, id ->
                        val m = on.first { it.id == id }
                        val host = hosts.firstOrNull { it.machine.id == id }
                        if (i > 0) RowDivider()
                        MachineRow(m, host, boardOf(host), used = id == last && order.size > 1) { picked = target(id) }
                    }
                }
            }
            if (share) item(key = "note") {
                Text(
                    "It goes into the chat's message box, so you can add to it before you send.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Herdr.colors.muted,
                    modifier = Modifier.padding(top = Space.m, start = Space.xs, end = Space.xs),
                )
            }
        }
    }
}

private class Start(val route: String, val machineId: String? = null)

private fun boardOf(host: HostState?): AskTargets.Board = when {
    host == null || !host.online -> AskTargets.Board.Unknown
    host.onShepherdr -> AskTargets.Board.Here
    host.boardChecked -> AskTargets.Board.None
    else -> AskTargets.Board.Unknown
}

/** What is being sent: its words, and the files that go with them. */
@Composable
private fun SharedCard(s: Shared) {
    val c = Herdr.colors
    QuietCard(padded = true) {
        FieldLabel("What you're sending")
        if (s.text.isNotBlank()) Text(
            s.text,
            style = MaterialTheme.typography.bodyMedium,
            color = c.text,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Space.s),
        )
        for (f in s.files) Row(Modifier.padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(HerdrIcons.FileText, null, tint = c.muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Space.s))
            Text(f.name, style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(Space.s))
            Text(size(f.size), style = MaterialTheme.typography.labelMedium, color = c.muted)
        }
    }
}

@Composable
private fun MachineRow(m: Machine, host: HostState?, board: AskTargets.Board, used: Boolean, onClick: () -> Unit) {
    val status = host?.status ?: HostStatus.Connecting
    ListRow(
        m.label,
        subtitle = when (board) {
            AskTargets.Board.Here -> if (used) "Assistant · used last" else "Assistant"
            AskTargets.Board.None -> "No Shepherdr here"
            AskTargets.Board.Unknown -> listOfNotNull(status.label(), "used last".takeIf { used }).joinToString(" · ")
        },
        leading = { Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { StatusDot(status.color()) } },
        chevron = board != AskTargets.Board.None,
        enabled = board != AskTargets.Board.None,
        onClickLabel = "Open ${m.label}'s Assistant",
        onClick = onClick,
    )
}

private fun size(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / (1024.0 * 1024))
}
