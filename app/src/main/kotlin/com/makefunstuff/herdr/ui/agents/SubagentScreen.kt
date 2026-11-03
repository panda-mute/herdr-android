package com.makefunstuff.herdr.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.core.chat.ChatTurns
import com.makefunstuff.herdr.core.chat.SubagentChat
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.images.ChatImages
import com.makefunstuff.herdr.ui.images.LocalChatImages
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

/**
 * What a Claude subagent did, step by step, as the chat shows its agent's own work: the task it was
 * given, its calls and its answer, live while it runs. Read only; the agent that started it gets
 * its answer.
 */
@Composable
fun SubagentScreen(nav: NavController, machineId: String, paneId: String, agentId: String, title: String) {
    val container = LocalContainer.current
    val session = container.hosts.session(machineId)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val host by session.state.collectAsStateWithLifecycle()
    val chat = remember(session, paneId, agentId) { SubagentChat(session, paneId, agentId) }
    LaunchedEffect(chat) { chat.run() }
    val state by chat.state.collectAsStateWithLifecycle()
    // Its work is what you came for: every turn opens unless folded by hand; thoughts open by hand.
    val toggled = remember(agentId) { mutableStateMapOf<String, Boolean>() }
    val zone = remember { container.briefWork.zone() }
    val now = container.clock()
    val rows = remember(state, toggled.toMap()) {
        val turns = ChatTurns.of(state.items, state.turns)
        val opened = turns.associate { it.key to true } + toggled
        buildList {
            if (state.truncated) add(ChatRow.Info("truncated", "Its earlier steps aren't loaded"))
            addAll(chatRows(turns, ChatTurns.Steps(state.turns), live = null, opened = opened, now = now) { at -> clock(at, now, zone) })
        }
    }
    val c = Herdr.colors
    Scaffold(
        containerColor = c.ground,
        topBar = {
            HerdrTopBar(
                title.ifBlank { "Subagent" },
                subtitle = "Subagent · " + host.machine.label,
                onBack = { nav.popBackStack() },
            )
        },
    ) { padding ->
        Box(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize().navigationBarsPadding()) {
            when {
                state.missing -> EmptyState(
                    "No steps to show",
                    "This Claude Code version keeps no transcript for the subagent. Its result is in the chat.",
                    icon = HerdrIcons.Bot,
                )
                !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (host.online) LoadingState("Reading its steps") else EmptyState("Not connected", host.machine.label, icon = HerdrIcons.Monitor)
                }
                rows.isEmpty() -> EmptyState("No steps yet", "It hasn't written anything so far.", icon = HerdrIcons.Bot)
                else -> CompositionLocalProvider(LocalChatImages provides ChatImages(machineId, session, container.images, host.online)) {
                    LazyColumn(
                        reverseLayout = true,
                        contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.m, bottom = Space.m),
                        verticalArrangement = Arrangement.spacedBy(Space.m, Alignment.Bottom),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(rows.asReversed(), key = { it.key }) { row ->
                            ChatRowView(row, onToggle = { head -> toggled[head.turn] = !head.open }, onThought = { t -> toggled[t.key] = !t.open })
                        }
                    }
                }
            }
        }
    }
}
