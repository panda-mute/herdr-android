package com.makefunstuff.herdr.ui.agents

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.chat.AgentChat
import com.makefunstuff.herdr.core.chat.ChatTurns
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.ui.common.LocalContainer

/**
 * An agent's own transcript, read from its process as its Agent chat reads it, for a Shepherdr chat whose log
 * Shepherdr couldn't find ([com.makefunstuff.herdr.core.chat.ChatOwner.readsOwn]): its conversation rather than
 * "Can't find this conversation". Null until it has read something.
 */
class OwnTranscript(val rows: List<ChatRow>, internal val opened: MutableMap<String, Boolean>)

/** [own]'s rows, newest first, for a chat list laid out from the bottom. */
fun LazyListScope.ownRows(own: OwnTranscript) {
    items(own.rows.asReversed(), key = { "own/" + it.key }) { row ->
        ChatRowView(row, onToggle = { head -> own.opened[head.turn] = !head.open }, onThought = { t -> own.opened[t.key] = !t.open })
    }
}

@Composable
fun rememberOwnTranscript(session: HostSession, paneId: String?, status: AgentStatus): OwnTranscript? {
    if (paneId == null) return null
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val chat = remember(session, paneId) { AgentChat(session, paneId, scope) }
    DisposableEffect(chat) {
        chat.start()
        onDispose { chat.close() }
    }
    val state by chat.state.collectAsStateWithLifecycle()
    if (state.source != AgentChat.Source.Transcript) return null
    val opened = remember(paneId) { mutableStateMapOf<String, Boolean>() }
    val zone = remember { container.briefWork.zone() }
    val now = container.clock()
    val live = when (status) {
        AgentStatus.Working -> ChatRow.Live.Working
        AgentStatus.Blocked -> ChatRow.Live.Blocked
        else -> null
    }
    val rows = remember(state.items, state.turns, live, opened.toMap(), state.truncated) {
        val turns = ChatTurns.of(state.items, state.turns)
        buildList {
            if (state.truncated) add(ChatRow.Info("truncated", "Earlier messages aren't loaded"))
            addAll(chatRows(turns, ChatTurns.Steps(state.turns), live, opened, now, state.thinking) { at -> clock(at, now, zone) })
        }
    }
    return OwnTranscript(rows, opened)
}
