package com.makefunstuff.herdr.ui.coordinator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.shepherdr.ChatFind
import com.makefunstuff.herdr.core.shepherdr.Reactions
import com.makefunstuff.herdr.core.shepherdr.SpaceChatItem
import com.makefunstuff.herdr.core.shepherdr.V2ReplyQuote
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.common.copyToClipboard
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrSheet
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// What 3.1's chat lets you do with its messages, shared by a space's Assistant chat and a task's: hold a message to
// react or reply (its reactions bar), tap a reaction to add yours or take it back, tap a reply's line to go to what it
// answers, and find words in the chat.

/** A Shepherdr chat's list on screen: where each item sits in it ([indexOf], null when it isn't shown). */
@Stable
class ChatPlaces(val list: LazyListState, val indexOf: (String) -> Int?) {
    /** Brings [id] into view a third of the way up the chat, clear of the box under it; false when it isn't shown. */
    suspend fun show(id: String): Boolean {
        val at = indexOf(id) ?: return false
        // The list runs bottom up: a negative offset lifts the item off its bottom edge.
        list.animateScrollToItem(at, -list.layoutInfo.viewportSize.height / 3)
        return true
    }
}

/**
 * Find in chat: the box under the bar, the items that hold its words ([matches], oldest first) and the one gone to
 * ([current]), which starts at the newest.
 */
@Stable
class ChatFindState {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    var matches by mutableStateOf<List<String>>(emptyList())
        private set
    var at by mutableIntStateOf(-1)
        private set
    /** "Search earlier" was pressed: the earlier page is on its way. */
    var loading by mutableStateOf(false)

    val current: String? get() = matches.getOrNull(at)

    /** Read [items] again: a new query goes to its newest match, more of the chat keeps the one gone to. */
    fun update(items: List<SpaceChatItem>, newQuery: Boolean) {
        val was = current
        matches = ChatFind.matches(items, query)
        at = if (!newQuery && was != null && was in matches) matches.indexOf(was) else matches.lastIndex
    }

    fun older() { if (matches.isNotEmpty()) at = (at - 1 + matches.size) % matches.size }
    fun newer() { if (matches.isNotEmpty()) at = (at + 1) % matches.size }
    fun close() { open = false; query = ""; matches = emptyList(); at = -1; loading = false }
}

@Composable
fun rememberChatFind(key: Any?, items: List<SpaceChatItem>, places: ChatPlaces): ChatFindState {
    val find = remember(key) { ChatFindState() }
    LaunchedEffect(find, find.query) { find.update(items, newQuery = true) }
    LaunchedEffect(find, items) { find.update(items, newQuery = false); find.loading = false }
    // To each match in turn.
    LaunchedEffect(find, find.current) { find.current?.let { places.show(it) } }
    return find
}

/**
 * The find box over a chat: "Find in chat", the count ("2 of 7", "No matches"), older and newer, close; "Search
 * earlier" while the chat has earlier pages ([earlier]: reads one).
 */
@Composable
fun FindBar(find: ChatFindState, earlier: (() -> Unit)?) {
    val c = Herdr.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().background(c.ground).padding(horizontal = Space.m, vertical = Space.xs)) {
        HerdrField(
            value = find.query,
            onValueChange = { find.query = it },
            placeholder = { Text(ChatFind.PLACEHOLDER) },
            leadingIcon = { Icon(HerdrIcons.Search, null, Modifier.size(20.dp)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { find.older() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (find.query.isBlank()) "" else ChatFind.count(find.at, find.matches.size),
                style = MaterialTheme.typography.labelMedium, color = c.muted,
                modifier = Modifier.weight(1f).padding(start = Space.xs),
            )
            if (earlier != null && find.query.isNotBlank()) QuietButton(
                onClick = { find.loading = true; earlier() }, enabled = !find.loading, color = c.accent,
                contentPadding = PaddingValues(horizontal = Space.s, vertical = Space.xs),
            ) { Text(if (find.loading) "Loading…" else ChatFind.SEARCH_EARLIER) }
            BarIcon(HerdrIcons.ChevronUp, "Older match", { find.older() }, enabled = find.matches.isNotEmpty())
            BarIcon(HerdrIcons.ChevronDown, "Newer match", { find.newer() }, enabled = find.matches.isNotEmpty())
            BarIcon(HerdrIcons.X, "Close find", { find.close() })
        }
    }
}

/** The message a reply's line went to, lit for a moment (3.1 lights it as it scrolls there). */
@Stable
class ChatJump(private val scope: CoroutineScope, private val places: ChatPlaces) {
    var lit by mutableStateOf<String?>(null)
        private set

    fun to(id: String) {
        if (places.indexOf(id) == null) return
        scope.launch {
            places.show(id)
            lit = id
            delay(1_600)
            if (lit == id) lit = null
        }
    }
}

/** An item's tint: the match gone to, another match, or the message a reply's line went to. */
@Composable
fun Modifier.lit(id: String, find: ChatFindState, jump: ChatJump): Modifier {
    val c = Herdr.colors
    val tint = when {
        find.open && id == find.current -> c.accentTint
        find.open && id in find.matches -> c.hover
        id == jump.lit -> c.accentTint
        else -> return this
    }
    return background(tint, HerdrShapes.Field)
}

/**
 * Reactions as you give them: [send] tells Shepherdr (a toggle), and what you sent shows at once ([sentTo]) until
 * the chat has it; [holding] is the message whose bar is up.
 */
@Stable
class ChatReactions(private val scope: CoroutineScope, private val send: suspend (message: String, emoji: String) -> Unit, private val failed: (String) -> Unit) {
    private var sent by mutableStateOf<Map<String, Map<String, Boolean>>>(emptyMap())
    var holding by mutableStateOf<SpaceChatItem.Words?>(null)

    fun sentTo(message: String): Map<String, Boolean> = sent[message].orEmpty()

    /** Yours added to [w], or taken back when it is yours already. */
    fun toggle(w: SpaceChatItem.Words, emoji: String) {
        val mine = Reactions.pills(w.reactions, sentTo(w.id)).any { it.emoji == emoji && it.mine }
        sent = sent + (w.id to (sentTo(w.id) + (emoji to !mine)))
        scope.launch {
            try {
                send(w.id, emoji)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                sent = sent + (w.id to (sentTo(w.id) - emoji))
                failed(e.message ?: "Couldn't react")
            }
        }
    }

    /** The chat came again: what it now shows no longer waits. */
    fun settle(items: List<SpaceChatItem>) {
        if (sent.isEmpty()) return
        val words = items.filterIsInstance<SpaceChatItem.Words>().associateBy { it.id }
        sent = sent.mapValues { (id, s) -> words[id]?.let { Reactions.stillSent(it.reactions, s) } ?: s }.filterValues { it.isNotEmpty() }
    }
}

/** A message's reactions under it, one pill each with its count; yours marked, a tap toggles yours. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReactionPills(w: SpaceChatItem.Words, reactions: ChatReactions?, modifier: Modifier = Modifier) {
    val pills = Reactions.pills(w.reactions, reactions?.sentTo(w.id).orEmpty())
    if (pills.isEmpty()) return
    val c = Herdr.colors
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        pills.forEach { p ->
            Row(
                Modifier.alpha(if (p.pending) 0.55f else 1f).heightIn(min = 32.dp)
                    .background(if (p.mine) c.accentTint else c.card, HerdrShapes.Pill)
                    .border(1.dp, if (p.mine) c.accent.copy(alpha = 0.5f) else c.line, HerdrShapes.Pill)
                    .clickable(enabled = reactions != null && !w.id.startsWith("cid:"), role = Role.Button, onClickLabel = if (p.mine) "take yours back" else "add yours") { reactions?.toggle(w, p.emoji) }
                    .padding(horizontal = Space.s)
                    .semantics { selected = p.mine; contentDescription = "${p.emoji} ${p.count}" + if (p.mine) ", yours" else "" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(p.emoji, style = MaterialTheme.typography.bodyMedium)
                if (p.count > 1) Text(" ${p.count}", style = MaterialTheme.typography.labelMedium, color = if (p.mine) c.accent else c.muted)
            }
        }
    }
}

/** The bar for a held message: the quick reactions, then Reply and Copy. */
@Composable
fun MessageSheet(reactions: ChatReactions, onReply: (V2ReplyQuote) -> Unit) {
    val w = reactions.holding ?: return
    val c = Herdr.colors
    val context = LocalContext.current
    val close = { reactions.holding = null }
    val mine = Reactions.pills(w.reactions, reactions.sentTo(w.id)).filter { it.mine }.map { it.emoji }.toSet()
    HerdrSheet(onDismissRequest = close) {
        Text(
            w.text, style = MaterialTheme.typography.bodyMedium, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.xs),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s), horizontalArrangement = Arrangement.SpaceBetween) {
            Reactions.QUICK.forEach { e ->
                val on = e in mine
                Box(
                    Modifier.size(48.dp).background(if (on) c.accentTint else c.card, HerdrShapes.Field)
                        .border(1.dp, if (on) c.accent else c.line, HerdrShapes.Field)
                        .clickable(role = Role.Button, onClickLabel = if (on) "take yours back" else "react") { close(); reactions.toggle(w, e) }
                        .semantics { selected = on; contentDescription = "React $e" },
                    contentAlignment = Alignment.Center,
                ) { Text(e, style = MaterialTheme.typography.titleLarge) }
            }
        }
        Column(Modifier.padding(horizontal = Space.s)) {
            MenuRow("Reply", HerdrIcons.Reply, {
                close()
                onReply(V2ReplyQuote(id = w.id, who = if (w.you) "you" else "agent", text = w.text.take(120)))
            })
            MenuRow("Copy", HerdrIcons.Copy, { close(); context.copyToClipboard("message", w.text) })
        }
    }
}

/**
 * A task's news line in an Assistant's chat, as 3.1 draws it: its mark (a tick when ready or done, a dot when
 * started, red while it still needs you), the task, what happened, then its words, dim; a press opens the task
 * ([onOpen]), and a Ready line's Accept sits at its right end ([onAccept]).
 */
@Composable
internal fun NewsLine(u: SpaceChatItem.Update, onOpen: (() -> Unit)?, onAccept: (() -> Unit)?) {
    val c = Herdr.colors
    val needs = u.status == com.makefunstuff.herdr.core.board.WorkStatus.Need && u.live
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp)
            .then(if (onOpen != null) Modifier.clickable(onClickLabel = "Open ${u.task}", onClick = onOpen) else Modifier)
            .padding(horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            when (u.status) {
                com.makefunstuff.herdr.core.board.WorkStatus.Ready -> Icon(HerdrIcons.CircleCheck, null, Modifier.size(14.dp), tint = c.done)
                com.makefunstuff.herdr.core.board.WorkStatus.Done -> Icon(HerdrIcons.CircleCheck, null, Modifier.size(14.dp), tint = c.muted)
                com.makefunstuff.herdr.core.board.WorkStatus.Work -> StatusDot(c.working, size = 8)
                else -> StatusDot(if (needs) c.red else c.muted, size = 8)
            }
        }
        Spacer(Modifier.width(Space.s))
        Text(u.task, style = MaterialTheme.typography.labelLarge, color = c.strong, maxLines = 1)
        Spacer(Modifier.width(Space.s))
        Text(u.what, style = MaterialTheme.typography.bodyMedium, color = if (needs) c.red else c.text, maxLines = 1)
        if (u.detail.isNotBlank()) {
            Text(" · ", style = MaterialTheme.typography.bodyMedium, color = c.muted)
            Text(u.detail, style = MaterialTheme.typography.bodyMedium, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else Spacer(Modifier.weight(1f))
        // Small, as 3.1's, on a target a finger still finds.
        if (u.accept && onAccept != null) Box(
            Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onAccept).padding(start = Space.s),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                com.makefunstuff.herdr.core.shepherdr.Accept.ACCEPT, style = MaterialTheme.typography.labelMedium, color = c.onPrimary,
                modifier = Modifier.background(c.primary, HerdrShapes.Field).padding(horizontal = Space.m, vertical = 6.dp),
            )
        }
    }
}

