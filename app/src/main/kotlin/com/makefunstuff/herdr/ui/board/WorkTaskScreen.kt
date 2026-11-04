package com.makefunstuff.herdr.ui.board

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.makefunstuff.herdr.core.board.CardAnswer
import com.makefunstuff.herdr.core.board.WorkCard
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.board.WorkTaskDetails
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.common.Pill
import com.makefunstuff.herdr.ui.common.openUrl
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.InfoLine
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * A Shepherdr 3 task without an agent's chat (one not started yet), full screen: where it stands, the
 * cards it waits on (answered here), what it said once ready, its pull request, its brief; Merge at the
 * bottom when it is ready, and Close in ⋯. Read again while open, and at once when the board says it
 * changed. A task with an agent opens as its chat ([TaskChatScreen]).
 */
@Composable
internal fun WorkTaskScreen(
    session: HostSession,
    t: WorkTask,
    space: String,
    busy: String?,
    onBack: () -> Unit,
    onMerge: () -> Unit,
    onClose: () -> Unit,
    onAnswer: (CardAnswer) -> Unit,
    backEnabled: Boolean = true,
) {
    BackHandler(enabled = backEnabled, onBack = onBack)
    val owner = LocalLifecycleOwner.current
    var details by remember { mutableStateOf<WorkTaskDetails?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    // The task as the board last sent it: a change there (its status, its line, a card) reads it again now.
    LaunchedEffect(t, busy == null) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    details = session.awaitWorkBoard().details(t)
                    problem = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    problem = e.message ?: "Couldn't read the task"
                }
                delay(8_000)
            }
        }
    }
    WorkTaskContent(t, details?.takeIf { it.task.id == t.id }, problem, space, busy, onBack, onMerge, onClose, onAnswer)
}

/** The screen itself, from data alone: what [WorkTaskScreen] draws once it has read the task's details. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkTaskContent(
    t: WorkTask,
    d: WorkTaskDetails?,
    problem: String?,
    space: String,
    busy: String?,
    onBack: () -> Unit,
    onMerge: () -> Unit,
    onClose: () -> Unit,
    onAnswer: (CardAnswer) -> Unit,
) {
    val context = LocalContext.current
    val c = Herdr.colors
    val color = t.status.color()
    val canClose = t.status != WorkStatus.Done && t.status != WorkStatus.Merging
    var menu by remember(t.id) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = c.ground,
            topBar = {
                HerdrTopBar(t.name, subtitle = space.ifEmpty { null }, onBack = onBack, backLabel = "Back to the board") {
                    if (canClose) BarIcon(HerdrIcons.Ellipsis, "More", { menu = true })
                }
            },
            // Only the one thing it waits for sits under the thumb: Close is in ⋯.
            bottomBar = {
                if (busy != null || t.status == WorkStatus.Ready) Column(
                    Modifier.fillMaxWidth().background(c.ground).navigationBarsPadding().padding(horizontal = Space.screen, vertical = Space.m),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    busy?.let { BusyLine(it) }
                    if (t.status == WorkStatus.Ready) PrimaryButton(onClick = onMerge, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = busy == null) {
                        Icon(HerdrIcons.GitMerge, null, Modifier.size(18.dp))
                        Text(if (t.accepting) "Accepting…" else "Accept")
                    }
                }
            },
        ) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).padding(top = Space.xs, bottom = Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(t.title, style = MaterialTheme.typography.headlineSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        StatusBadge(t.status.label, color)
                        if (t.pinned) Pill("pinned")
                    }
                }
                // Where it stands: the next step, its own line, progress.
                val step = nextStep(t, d?.cards.orEmpty().size)
                val p = t.progress?.takeIf { it > 0 && t.status == WorkStatus.Work }
                if (step != null || t.line.isNotEmpty() || p != null) QuietCard(
                    padded = true,
                    color = if (t.status.needsYou) c.tint(color).compositeOver(c.ground) else c.card,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Text(if (t.status.needsYou) "Needs you" else "Status", style = MaterialTheme.typography.titleSmall, color = color, modifier = Modifier.semantics { heading() })
                        if (step != null) Text(step, style = MaterialTheme.typography.bodyLarge)
                        if (t.line.isNotEmpty()) Preview(t.line, "status", lines = 3, color = c.muted)
                        if (p != null) Row(verticalAlignment = Alignment.CenterVertically) {
                            Progress(p, color, Modifier.weight(1f))
                            Text("$p%", style = MaterialTheme.typography.labelMedium, color = c.faint, modifier = Modifier.padding(start = Space.m))
                        }
                    }
                }
                problem?.let { ErrorMessage(it) }
                if (d == null && problem == null) LoadingState("Reading the task")
                d?.cards?.forEach { card -> QuestionCard(card, enabled = busy == null, onSend = onAnswer) }
                if (d != null && d.summary.isNotEmpty()) Section("What it did", startOpen = true) { MarkdownText(d.summary, style = MaterialTheme.typography.bodyMedium) }
                if (t.pr.isNotEmpty()) LinkRow("Pull request" + (prNumberOf(t.pr)?.let { " #$it" } ?: ""), t.pr.substringAfter("github.com/")) { context.openUrl(t.pr) }
                if (d != null && d.plan.isNotEmpty()) Section("Plan", startOpen = false) {
                    d.plan.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium) }
                }
                if (d != null && d.brief.isNotEmpty()) Section("Brief", startOpen = d.cards.isEmpty() && d.summary.isEmpty()) {
                    MarkdownText(d.brief, style = MaterialTheme.typography.bodyMedium)
                }
                if (d != null && (d.facts.isNotEmpty() || d.branch.isNotEmpty())) Section("Facts", startOpen = false) {
                    if (d.branch.isNotEmpty()) InfoLine("Branch", d.branch)
                    if (d.facts.isNotEmpty()) Text(d.facts, style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
            }
        }
        if (menu) BoardPanel("${t.name} · ${t.title}", onDismiss = { menu = false }) {
            MenuRow("Close, nothing merged…", HerdrIcons.CircleStop, { menu = false; onClose() }, enabled = busy == null, destructive = true)
        }
    }
}

/** What a person should do about a task in this status, in a sentence; [cards] it waits on. */
private fun nextStep(t: WorkTask, cards: Int): String? = when (t.status) {
    WorkStatus.Need -> if (cards > 0) "Its agent asks you: answer below." else "Its agent waits for you in its chat."
    WorkStatus.Ready -> "Finished. Read what it did, then merge it, or tell its agent what to change."
    WorkStatus.Merging -> "On its way into main: nothing to do."
    WorkStatus.Queued -> "Waits for other tasks to finish."
    WorkStatus.Todo -> "Its agent waits for a go."
    else -> null
}

/**
 * A question card the task waits on, answered here: its choices (one, or several on a multi card),
 * an answer in your own words where it allows one, and a note (needed for the choice that sends a plan back).
 * A plain question with no choices is answered in words.
 */
@Composable
internal fun QuestionCard(card: WorkCard, enabled: Boolean, onSend: (CardAnswer) -> Unit) {
    val q = card.question
    val c = Herdr.colors
    val key = "${card.chat}/${q.id}"
    var picked by rememberSaveable(key) { mutableStateOf(-1) }
    var picks by rememberSaveable(key) { mutableStateOf(listOf<Int>()) }
    var other by rememberSaveable(key) { mutableStateOf("") }
    var note by rememberSaveable(key) { mutableStateOf("") }
    val words = card.replyTo.isNotEmpty() || q.choices.isEmpty()
    val noteNeeded = q.noteRequiredFor != null && q.noteRequiredFor == picked
    val ready = when {
        words -> other.isNotBlank()
        other.isNotBlank() -> true
        q.multi -> picks.isNotEmpty()
        else -> picked >= 0 && (!noteNeeded || note.isNotBlank())
    }
    QuietCard(padded = true, color = c.tint(WorkStatus.Need.color()).compositeOver(c.ground)) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(q.asks ?: if (q.kind == "Plan") "Plan to approve" else "Asks you", style = MaterialTheme.typography.titleSmall, color = WorkStatus.Need.color())
            if (q.title.isNotEmpty()) MarkdownText(q.title, style = MaterialTheme.typography.titleMedium)
            q.steps.orEmpty().forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium) }
            if (q.multi) Text("Pick any", style = MaterialTheme.typography.labelMedium, color = c.muted)
            q.choices.forEachIndexed { i, ch ->
                val on = if (q.multi) i in picks else i == picked
                QuietCard(
                    Modifier.semantics { selected = on },
                    // The chosen row's neutral fill (DESIGN.md: selection is never amber).
                    color = if (on) c.hover else c.card,
                    edge = if (on) c.lineStrong else c.line,
                    onClick = if (enabled) ({ if (q.multi) picks = if (on) picks - i else picks + i else picked = i; if (!q.multi) other = "" }) else null,
                    onClickLabel = if (on) "Chosen" else "Choose",
                    padded = true,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (q.multi) (if (on) HerdrIcons.CircleCheck else HerdrIcons.Square) else (if (on) HerdrIcons.CircleCheck else HerdrIcons.Circle),
                            null, tint = if (on) c.strong else c.faint, modifier = Modifier.size(20.dp),
                        )
                        Column(Modifier.weight(1f).padding(start = Space.m)) {
                            Text(ch.label, style = MaterialTheme.typography.bodyLarge, color = c.text)
                            ch.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
                        }
                    }
                }
            }
            if (words || q.other) HerdrField(
                // Your own words answer in place of a pick: the picks are let go, so none looks sent that isn't.
                other, { other = it; if (it.isNotBlank()) { picked = -1; picks = emptyList() } },
                label = if (words) "Your answer" else "Other", modifier = Modifier.fillMaxWidth(), enabled = enabled,
            )
            if (!words && !q.noNote && (picked >= 0 || picks.isNotEmpty())) HerdrField(
                note, { note = it },
                label = if (noteNeeded) "What should change" else "Note (optional)", modifier = Modifier.fillMaxWidth(), enabled = enabled,
            )
            PrimaryButton("Send answer", Modifier.fillMaxWidth(), enabled = enabled && ready) {
                onSend(
                    CardAnswer(
                        card,
                        choice = if (q.multi || other.isNotBlank()) -1 else picked,
                        choices = picks.sorted().takeIf { q.multi && other.isBlank() },
                        note = note.trim().takeIf { it.isNotEmpty() && !words },
                        other = other.trim().takeIf { it.isNotEmpty() },
                    ),
                )
            }
        }
    }
}
