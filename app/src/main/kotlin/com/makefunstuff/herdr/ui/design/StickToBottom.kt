package com.makefunstuff.herdr.ui.design

import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.chat.StickToBottom
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A chat list kept to its newest message by [StickToBottom]'s rules: [rememberStickToBottom]. It
 * follows the end until the reader scrolls away from it, and again once they come back: only a
 * scroll lets go, not something added under the newest message or a scroll of its own.
 */
@Stable
class ChatStick internal constructor(private val list: LazyListState, private val scope: CoroutineScope, private val slop: Int) {
    internal var rules by mutableStateOf(StickToBottom())
    internal var messages by mutableStateOf(emptyList<String>())

    /** At the newest message (within about 80 px), following it. */
    var atEnd by mutableStateOf(true)
        private set

    /** Its own scroll down runs: where the list is meanwhile isn't the reader's doing. */
    private var going = 0

    /** What came in since the reader left the end, for the ↓. */
    val fresh by derivedStateOf { rules.fresh(messages) }

    /** The message the New line goes above. */
    val unread: String? get() = rules.unread

    /** Down to the newest, as the ↓ does. Its own job: a list's update doesn't stop it, a finger does. */
    fun toEnd() {
        atEnd = true
        going++
        scope.launch {
            try {
                list.animateScrollToItem(0)
            } finally {
                going--
                // Stopped by a finger: where the reader is now says it.
                if (going == 0) atEnd = here()
            }
        }
    }

    /** You wrote (from any thread: a voice message is sent from the background): down to the newest, and what was new is behind you. */
    fun sent() {
        scope.launch {
            rules = rules.sent()
            toEnd()
        }
    }

    /** Sending that shows as [items] first (voice messages, by id): one added goes down, as sending does. */
    @Composable
    fun SentWith(items: List<String>) {
        val seen = remember(this) { mutableSetOf<String>().apply { addAll(items) } }
        LaunchedEffect(this, items) {
            if (items.any { it !in seen }) sent()
            seen.addAll(items)
        }
    }

    private fun here() = !list.canScrollBackward || StickToBottom.atEnd(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, slop)

    /**
     * Keeps the list to [messages] (see [rememberStickToBottom]); [content] also changes whenever
     * something is added under the newest message (the working line, a notice), so it is followed.
     */
    @Composable
    fun Follow(messages: List<String>, content: Any? = null) {
        this.messages = messages
        LaunchedEffect(this, messages, content) {
            val r = rules.seen(messages, atEnd)
            rules = r.next
            if (r.toEnd) toEnd()
        }
        // The reader's own scrolls: away from the end lets go, back near it follows again. Something added
        // under the newest message moves the list's index, not the item on top or its offset: no scroll.
        LaunchedEffect(this) {
            var was: Pair<Any?, Int>? = null
            snapshotFlow {
                val first = list.firstVisibleItemIndex
                list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == first }?.key to list.firstVisibleItemScrollOffset
            }.collect { now ->
                if (going == 0 && now != was) atEnd = here()
                was = now
            }
        }
        val current by rememberUpdatedState(messages)
        LaunchedEffect(this) { snapshotFlow { atEnd }.collect { rules = rules.moved(it, current) } }
    }
}

/**
 * The one way a chat stays at its newest, for the agent chat and both Coordinators, so they can't drift
 * apart: [list] is reversed (item 0 is the newest), [messages] are the keys that count as messages,
 * oldest first, and [content] changes whenever anything at the end may have grown or been added (the
 * working line, an answer being written, a notice). A [list] remembered anew (another space) opens at
 * the newest again.
 */
@Composable
fun rememberStickToBottom(list: LazyListState, messages: List<String>, content: Any? = null): ChatStick =
    rememberStickToBottom(list).also { it.Follow(messages, content) }

/** The [ChatStick] of [list], for a screen that sends before its messages are known: it then calls [ChatStick.Follow]. */
@Composable
fun rememberStickToBottom(list: LazyListState): ChatStick {
    val scope = rememberCoroutineScope()
    val slop = with(LocalDensity.current) { StickToBottom.SLOP_DP.dp.roundToPx() }
    return remember(list, slop) { ChatStick(list, scope, slop) }
}

/**
 * Over the composer while the reader is up the chat: "↓ 2 new messages" once more came in, else a
 * plain ↓. [bottom] is the composer's height.
 */
@Composable
fun BoxScope.NewestButton(stick: ChatStick, bottom: Dp) {
    if (stick.atEnd) return
    if (stick.fresh > 0) {
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + Space.xs)) {
            NewMessagesPill(stick.fresh, stick::toEnd)
        }
    } else {
        Box(Modifier.align(Alignment.BottomEnd).padding(end = Space.m, bottom = bottom + Space.xs)) {
            RoundAction(HerdrIcons.ArrowDown, "Newest", primary = false, onClick = stick::toEnd)
        }
    }
}

/** "↓ 2 new messages": over the composer while the reader is scrolled up and more came in. */
@Composable
fun NewMessagesPill(count: Int, onClick: () -> Unit) {
    val c = Herdr.colors
    val shape = RectangleShape
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.heightIn(min = 48.dp).padding(vertical = 6.dp)
            .clip(shape).background(c.card).border(1.dp, c.line, shape)
            .clickable(role = Role.Button, onClickLabel = "Go to the newest", onClick = onClick)
            .padding(horizontal = Space.l, vertical = Space.s),
    ) {
        Icon(HerdrIcons.ArrowDown, null, Modifier.size(16.dp), tint = c.text)
        Spacer(Modifier.width(Space.s))
        Text(if (count == 1) "1 new message" else "$count new messages", style = MaterialTheme.typography.labelLarge, color = c.text)
    }
}

/** "── New ──" in amber, above the first message that came in while the reader was up the chat. */
@Composable
fun NewLine(modifier: Modifier = Modifier) {
    val c = Herdr.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(c.accent.copy(alpha = 0.6f)))
        Text("New", style = MaterialTheme.typography.labelMedium, color = c.accent, modifier = Modifier.padding(horizontal = Space.s))
        Box(Modifier.weight(1f).height(1.dp).background(c.accent.copy(alpha = 0.6f)))
    }
}
