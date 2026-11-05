package com.makefunstuff.herdr.ui.code

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.core.chat.AgentInput
import com.makefunstuff.herdr.core.code.CheckoutInfo
import com.makefunstuff.herdr.core.code.ReviewNote
import com.makefunstuff.herdr.core.code.ReviewNotes
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.FieldLabel
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrSheet
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.MonoBlock
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.design.SheetTitle
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.launch

/** The lines a comment is about, before its words are written. */
data class NoteDraft(
    val path: String,
    val start: Int,
    val end: Int,
    val quote: String,
    val language: String,
    /** "" for the file as it is, else the diff or commit the lines were read in. */
    val source: String,
    val oldSide: Boolean = false,
)

/** Who a comment can go to. */
sealed interface NoteTarget {
    val key: String
    val label: String

    /** An agent at work in the checkout: the message goes in as its next prompt (or answers its question). */
    data class Agent(val paneId: String, override val label: String, val blocked: Boolean) : NoteTarget {
        override val key: String get() = "agent:$paneId"
    }

    /** The Shepherdr task the checkout was opened from (its worktree): the message goes into its chat, to its agent. */
    data class Task(val board: HostSession, val id: String, override val label: String) : NoteTarget {
        override val key: String get() = "task:$id"
    }
}

/**
 * Who can get comments on [top]: the task it was opened from, when [board] (its machine's session) runs Shepherdr,
 * then the agents at work in it (the one it was opened from first).
 */
fun noteTargets(host: HostState, top: String, spot: CodeSpot, board: HostSession? = null): List<NoteTarget> {
    val agents = host.agentsIn(top, spot.pane)
    val names = agents.groupingBy { it.shownName }.eachCount()
    val ws = host.snapshot?.workspaces.orEmpty()
    return buildList {
        if (spot.task != null && board != null && board.state.value.onShepherdr) add(NoteTarget.Task(board, spot.task, spot.taskName ?: spot.task))
        agents.forEach { a ->
            val place = ws.firstOrNull { it.workspaceId == a.workspaceId }?.label?.ifBlank { null }
            add(NoteTarget.Agent(a.paneId, if ((names[a.shownName] ?: 0) > 1 && place != null) "${a.shownName} · $place" else a.shownName, a.blocked))
        }
    }
}

/** Sends [text] to [target] on [session]'s machine. */
suspend fun deliver(session: HostSession, target: NoteTarget, text: String) {
    when (target) {
        is NoteTarget.Agent -> AgentInput.send(session.awaitAgent(target.paneId), target.paneId, text, named = true, blocked = target.blocked)
        is NoteTarget.Task -> {
            val b = target.board.awaitWorkBoard()
            val t = b.overview().tasks.firstOrNull { it.id == target.id } ?: throw java.io.IOException("${target.label} isn't on the board any more")
            b.tell(t, text)
            return
        }
    }
    session.refresh()
}

/**
 * Writes a comment on the chosen lines: the code it quotes, the words, who gets it. Send sends it
 * now; Keep for later adds it to the comments waiting on this machine, to send together.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommentSheet(spot: CodeSpot, host: HostState, draft: NoteDraft, onDismiss: () -> Unit, onDone: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = container.hosts.session(spot.machine) ?: return
    val board = container.hosts.session(spot.board ?: spot.machine)
    val targets = remember(host.snapshot) { noteTargets(host, spot.dir, spot, board) }
    var target by remember(targets) { mutableStateOf(targets.firstOrNull()) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val branch = container.code.cache.get<CheckoutInfo>(infoKey(spot))?.branch.orEmpty()
    fun note() = ReviewNote(
        id = container.code.newId(), machineId = spot.machine, root = spot.dir, branch = branch, path = draft.path,
        start = draft.start, end = draft.end, source = draft.source, oldSide = draft.oldSide, quote = draft.quote,
        language = draft.language, text = text.trim(),
    )
    val lines = ReviewNotes.lines(note()).replaceFirstChar { it.uppercase() }

    HerdrSheet(onDismiss) {
        SheetTitle("Comment on ${lines.replaceFirstChar { it.lowercase() }}", draft.path + if (draft.source.isNotEmpty()) " · ${draft.source}" else "")
        Column(Modifier.padding(horizontal = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            MonoBlock(draft.quote, maxLines = 6)
            HerdrField(
                text, { text = it }, minLines = 3, maxLines = 8,
                placeholder = { Text("What should change here, or what to check") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (targets.isEmpty()) {
                Text(
                    "No agent works in this checkout and ${host.machine.label} has no work board, so there is no one to send it to now. Keep it with the comments for later.",
                    style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted,
                )
            } else {
                Column {
                    FieldLabel("Send to")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        targets.forEach { t -> HerdrChip(t.label, selected = t == target, onClick = { target = t }) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = Space.xs, bottom = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End)) {
                SecondaryButton("Keep for later", enabled = text.isNotBlank() && !sending) {
                    container.code.add(note())
                    context.toast("Kept with the comments")
                    onDone()
                }
                PrimaryButton(if (sending) "Sending…" else "Send", enabled = text.isNotBlank() && target != null && !sending) {
                    val to = target ?: return@PrimaryButton
                    sending = true
                    scope.launch {
                        runCatching { deliver(session, to, ReviewNotes.message(listOf(note()))) }
                            .onSuccess { context.toast("Sent to ${to.label}"); onDone() }
                            .onFailure { context.toast(it.message ?: "Couldn't send it") }
                        sending = false
                    }
                }
            }
        }
    }
}

/**
 * The comments waiting on a machine, by checkout: each can be changed or dropped, and a checkout's
 * are sent together, as one message, to the agent at work there, its task's agent or the Coordinator.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotesScreen(nav: NavController, spot: CodeSpot) {
    val container = LocalContainer.current
    val session = container.hosts.session(spot.machine)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host by session.state.collectAsStateWithLifecycle()
    val all by container.code.notes.collectAsStateWithLifecycle()
    val notes = all.filter { it.machineId == spot.machine }
    // This checkout's first, then the others on the machine.
    val groups = notes.groupBy { it.root }.toList().sortedBy { (root, _) -> if (root == spot.dir) 0 else 1 }
    var editing by remember { mutableStateOf<ReviewNote?>(null) }
    var sending by remember { mutableStateOf<String?>(null) }
    val chosen = remember { mutableStateOf(mapOf<String, String>()) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar(
                "Comments", subtitle = "${host.machine.label} · " + if (notes.isEmpty()) "none waiting" else "${notes.size} waiting",
                onBack = { nav.popBackStack() },
            )
        },
    ) { padding ->
        if (notes.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                EmptyState(
                    "No comments waiting",
                    "Choose lines in a file or a diff and tap Comment. The ones you keep for later wait here, to send together to the agent at work there or to the Coordinator.",
                    icon = HerdrIcons.MessageSquarePlus,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            groups.forEach { (root, list) ->
                val branch = list.firstNotNullOfOrNull { it.branch.ifEmpty { null } }
                item(key = "head:$root") {
                    SectionHeading(host.shortPath(root) + (branch?.let { " · $it" } ?: ""), top = Space.l)
                }
                items(list, key = { it.id }) { n ->
                    NoteCard(n, onEdit = { editing = n }, onDrop = { container.code.remove(listOf(n.id)) })
                }
                item(key = "send:$root") {
                    val targets = noteTargets(host, root, spot, container.hosts.session(spot.board ?: spot.machine))
                    val target = targets.firstOrNull { it.key == chosen.value[root] } ?: targets.firstOrNull()
                    Column(Modifier.padding(top = Space.xs), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        if (targets.isEmpty()) {
                            Text(
                                "No agent works here and ${host.machine.label} has no work board: start one, or open the board, to send these.",
                                style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted,
                            )
                        } else {
                            FieldLabel("Send to")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                targets.forEach { t ->
                                    HerdrChip(t.label, selected = t == target, onClick = { chosen.value = chosen.value + (root to t.key) })
                                }
                            }
                        }
                        PrimaryButton(
                            when {
                                sending == root -> "Sending…"
                                list.size == 1 -> "Send 1 comment"
                                else -> "Send ${list.size} comments"
                            },
                            Modifier.fillMaxWidth(),
                            enabled = target != null && sending == null,
                        ) {
                            val to = target ?: return@PrimaryButton
                            sending = root
                            scope.launch {
                                runCatching { deliver(session, to, ReviewNotes.message(list)) }
                                    .onSuccess { container.code.remove(list.map { it.id }); context.toast("Sent to ${to.label}") }
                                    .onFailure { context.toast(it.message ?: "Couldn't send them") }
                                sending = null
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { n ->
        EditNoteSheet(n, onDismiss = { editing = null }, onSave = { container.code.update(it); editing = null }, onDrop = {
            container.code.remove(listOf(n.id)); editing = null
        })
    }
}

@Composable
private fun NoteCard(n: ReviewNote, onEdit: () -> Unit, onDrop: () -> Unit) {
    val c = Herdr.colors
    QuietCard(onClick = onEdit, onClickLabel = "Change the comment", padded = true) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(n.path, style = CodeStyle, color = c.strong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        ReviewNotes.lines(n).replaceFirstChar { it.uppercase() } + if (n.source.isNotEmpty()) " · ${n.source}" else "",
                        style = MaterialTheme.typography.bodySmall, color = c.muted,
                    )
                }
                BarIcon(HerdrIcons.Trash2, "Drop the comment", onDrop)
            }
            MonoBlock(n.quote, maxLines = 4)
            Text(n.text, style = MaterialTheme.typography.bodyLarge, color = c.text)
        }
    }
}

@Composable
private fun EditNoteSheet(n: ReviewNote, onDismiss: () -> Unit, onSave: (ReviewNote) -> Unit, onDrop: () -> Unit) {
    var text by remember(n.id) { mutableStateOf(n.text) }
    HerdrSheet(onDismiss) {
        SheetTitle("Comment on ${ReviewNotes.lines(n)}", n.path)
        Column(Modifier.padding(horizontal = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            MonoBlock(n.quote, maxLines = 6)
            HerdrField(text, { text = it }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(bottom = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                QuietButton(onClick = onDrop, color = Herdr.colors.red) { Text("Drop") }
                Box(Modifier.weight(1f))
                SecondaryButton("Cancel", onClick = onDismiss)
                PrimaryButton("Save", enabled = text.isNotBlank()) { onSave(n.copy(text = text.trim())) }
            }
        }
    }
}

/** The cache key of a checkout's [CheckoutInfo]. */
fun infoKey(spot: CodeSpot) = "info:${spot.machine}:${spot.dir}"
