package com.makefunstuff.herdr.ui.board

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.makefunstuff.herdr.core.board.WorkOverview
import com.makefunstuff.herdr.core.home.Home
import com.makefunstuff.herdr.core.home.HomeAgent
import com.makefunstuff.herdr.core.home.HomeBoard
import com.makefunstuff.herdr.core.home.Waiting
import com.makefunstuff.herdr.ui.common.LocalContainer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** The Shepherdr boards of the connected machines, as last read, in the machines' order. */
@Composable
fun rememberBoards(): List<HomeBoard> {
    val container = LocalContainer.current
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val lists by container.boards.lists.collectAsStateWithLifecycle()
    return remember(hosts, lists) {
        hosts.filter { it.online && it.onShepherdr }.mapNotNull { h -> lists[h.machine.id]?.work?.let { HomeBoard(h.machine.id, h.machine.label, it) } }
    }
}

/**
 * The Needs you list as every machine's agents and boards make it (the Agents screen's, before its
 * machine filter): what both tabs count, so the Agents and Board badges never differ.
 */
@Composable
fun rememberNeedsYou(boards: List<HomeBoard> = rememberBoards()): List<Waiting> {
    val container = LocalContainer.current
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val seen by container.seen.marks.collectAsStateWithLifecycle()
    return remember(hosts, boards, seen) { Home.of(HomeAgent.of(hosts), boards, seen).needsYou }
}

/** The Shepherdr boards of the connected machines, as last read, by machine id: their spaces and tasks. */
@Composable
fun rememberWorkBoards(): Map<String, WorkOverview> {
    val boards = rememberBoards()
    return remember(boards) { boards.associate { it.machineId to it.work } }
}

/** Machines whose board didn't answer the last read, by label, with what went wrong. */
@Composable
fun rememberBoardProblems(): List<Pair<String, String>> {
    val container = LocalContainer.current
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val lists by container.boards.lists.collectAsStateWithLifecycle()
    return remember(hosts, lists) {
        hosts.filter { it.online && it.onShepherdr }.mapNotNull { h -> lists[h.machine.id]?.problem?.let { h.machine.label to it } }
    }
}

/**
 * Reads every connected machine's board while the screen is in front: at once, then every
 * [everyMs], and again as soon as [readNow] is sent (after an action). Nothing while it's backgrounded.
 */
@Composable
fun ReadBoards(readNow: Channel<Unit>, everyMs: Long = 15_000) {
    val container = LocalContainer.current
    val owner = LocalLifecycleOwner.current
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val ids = hosts.filter { it.online && it.onShepherdr }.joinToString(",") { it.machine.id }
    LaunchedEffect(ids) {
        if (ids.isEmpty()) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var asked = false
            while (true) {
                // Asked for (after an action): read now, even if another screen just did.
                container.boards.readAll(freshMs = if (asked) 0 else 3_000)
                asked = withTimeoutOrNull(everyMs) { readNow.receive() } != null
            }
        }
    }
}
