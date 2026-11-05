package com.makefunstuff.herdr

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.host.HostState

/** A window this wide or wider (a tablet, an open foldable, a large or desktop window) shows the spaces beside the app. */
val WIDE = 840.dp

/**
 * The big-screen layout, as Shepherdr 3.1's web app has it on a desktop: on a window [WIDE] or
 * wider, the spaces sidebar stays on the left and the app (its tabs and what they open) fills the
 * rest. A narrower window keeps the spaces in a drawer, as on a phone.
 *
 * Screens open what they lead to through here, so a tap does the right thing in both: [open] puts a
 * screen from a list in place of what the middle showed instead of on top of it.
 */
@Immutable
class Panes(
    /** The spaces sidebar is shown; false on a phone-sized window. */
    val wide: Boolean = false,
) {
    /** Opens [route] from a list: on a wide window in place of what was there, on a phone as the next screen. */
    fun open(nav: NavController, route: String) {
        if (!wide) return nav.navigate(route)
        nav.navigate(route) {
            // What was shown is kept as a top-level tab's screens are, so a tab comes back as it was.
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
        }
    }

    /**
     * Opens [agent]'s chat from a list. One Shepherdr 3 started ([AgentInfo.shepherdrTask],
     * [AgentInfo.shepherdrSpace]) on a machine that runs Shepherdr 3 ([shepherdr]) opens where its
     * cards and replies are: its task's chat, or its space's Assistant. Its terminal stays on the
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
     * Opens [machineId]'s task [task] (its chat, or its details before its agent starts), or its
     * whole list without one. [alone]: closing the task goes back to where it was opened from.
     */
    fun openBoard(nav: NavController, machineId: String, task: String? = null, alone: Boolean = false) =
        open(nav, Routes.board(machineId, task, alone = alone && task != null))

    /** Opens a [Links] route (a notification, the tile, a widget). */
    fun link(nav: NavController, route: String) {
        // The Tasks tab (the launcher's Needs you): back to it, not another one on top.
        if (route == Routes.TASKS) {
            nav.navigate(route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = false }
                launchSingleTop = true
            }
            return
        }
        if (!wide) return nav.navigate(route) { launchSingleTop = true }
        open(nav, route)
    }

    companion object {
        /** How wide the spaces sidebar is on a window [width] wide; the app has the rest. */
        fun sidebar(width: Dp): Dp = (width * 0.27f).coerceIn(256.dp, 320.dp)
    }
}

val LocalPanes = compositionLocalOf { Panes() }
