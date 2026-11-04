package com.makefunstuff.herdr

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.host.HostState

/** A window this wide or wider (a tablet, an open foldable, a large or desktop window) shows three panes side by side. */
val WIDE = 840.dp

/**
 * The big-screen layout, as Puggie has it: on a window [WIDE] or wider, the agents list on the left,
 * the screen the app is on (an agent's chat, a terminal, the Coordinator, a machine) in the middle,
 * and a work board on the right. A narrower window shows one screen at a time, as on a phone.
 *
 * Screens open what they lead to through here, so a tap does the right thing in both: [open] puts a
 * screen from the list in the middle instead of on top of the list, [openBoard] shows a board on the right.
 */
@Immutable
class Panes(
    /** Three panes; false on a phone-sized window. */
    val wide: Boolean = false,
    /** The machine whose board the right pane shows; null when it shows none. */
    val board: String? = null,
    internal val chosen: BoardChoice = BoardChoice(),
) {
    /** Opens [route] from the agents list: in the middle in place of what was there, or on a phone as the next screen. */
    fun open(nav: NavController, route: String) {
        if (!wide) return nav.navigate(route)
        nav.navigate(route) {
            // What was in the middle is kept as a top-level tab's screens are, so the Board or Machines tab comes back as it was.
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
        }
    }

    /**
     * Opens [agent]'s chat from a list. One Shepherdr 3 started ([AgentInfo.shepherdrTask],
     * [AgentInfo.shepherdrSpace]) on a machine whose board is Shepherdr 3 ([shepherdr]) opens where its
     * cards and replies are: its task's chat, or its space's Coordinator. Its terminal stays on the
     * machine's screen.
     */
    fun openAgent(nav: NavController, machineId: String, agent: AgentInfo, shepherdr: Boolean) {
        if (shepherdr) {
            agent.shepherdrTask?.let { return openBoard(nav, machineId, it, alone = true) }
            agent.shepherdrSpace?.let { return open(nav, Routes.coordinator(machineId, it)) }
        }
        open(nav, Routes.agent(machineId, agent.paneId))
    }

    /** [openAgent] for a pane named by its id alone (a notice, a chat hit, the brief): [host]'s agent there, if it is one. */
    fun openPane(nav: NavController, host: HostState?, machineId: String, paneId: String) {
        val agent = host?.view?.agents?.firstOrNull { it.paneId == paneId }
        if (agent != null) openAgent(nav, machineId, agent, host.onShepherdr) else open(nav, Routes.agent(machineId, paneId))
    }

    /**
     * Opens [machineId]'s board, on [task] if given: on the right, or on a phone as its own screen.
     * [alone]: on a phone, closing the task goes back to where it was opened from (the Board tab), not to its board.
     */
    fun openBoard(nav: NavController, machineId: String, task: String? = null, alone: Boolean = false) {
        if (!wide) return nav.navigate(Routes.board(machineId, task, alone = alone && task != null))
        // A board still open in the middle (it was opened while the window was narrow) hides the right pane:
        // close it, so the board asked for shows where boards go on a wide window, and its task opens there now.
        if (nav.currentDestination?.route == Routes.BOARD) nav.popBackStack()
        chosen.machine = machineId
        chosen.task = task
    }

    /** Opens a [Links] route (a notification, the tile, a widget): a board's task on the right, the rest as [open]. */
    fun link(nav: NavController, route: String) {
        // The Agents screen (the launcher's Needs you): back to it, not another one on top.
        if (route == Routes.AGENTS) {
            nav.navigate(route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = false }
                launchSingleTop = true
            }
            return
        }
        if (!wide) return nav.navigate(route) { launchSingleTop = true }
        val board = Routes.boardOf(route) ?: return open(nav, route)
        openBoard(nav, board.first, board.second)
    }

    companion object {
        /**
         * How wide the left and right panes are on a window [width] wide; the middle has the rest. The
         * chat gets most of a big window, and on the narrowest wide one each pane still fits its cards.
         */
        fun widths(width: Dp, board: Boolean): Pair<Dp, Dp> {
            val left = (width * 0.27f).coerceIn(256.dp, 360.dp)
            val right = if (board) (width * 0.3f).coerceIn(280.dp, 420.dp) else 0.dp
            return left to right
        }
    }
}

/** The board chosen for the right pane and a task to open on it once; kept through rotation and a restart. */
@Stable
class BoardChoice(machine: String? = null, task: String? = null) {
    var machine by mutableStateOf(machine)
    var task by mutableStateOf(task)

    companion object {
        val Saver = listSaver<BoardChoice, String?>({ listOf(it.machine, it.task) }, { BoardChoice(it[0], it[1]) })
    }
}

val LocalPanes = compositionLocalOf { Panes() }
