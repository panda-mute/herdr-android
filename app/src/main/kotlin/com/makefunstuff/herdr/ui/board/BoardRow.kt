package com.makefunstuff.herdr.ui.board

import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.board.WorkTasks
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

/**
 * A task's status mark on a board row, as Shepherdr's own board draws it: a dot in its status colour,
 * a pie that fills with how far along it is while it works (the minutes its agent thinks are left
 * under it), a ring while it only waits its turn (queued, to do).
 */
@Composable
internal fun StatusMark(status: WorkStatus, progress: Int?, eta: Int?, left: String? = null) {
    val color = status.color()
    val track = Herdr.colors.line
    Column(Modifier.width(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            status == WorkStatus.Work && progress != null -> Canvas(Modifier.size(16.dp)) {
                drawCircle(color.copy(alpha = 0.22f).compositeOn(track))
                drawArc(color, -90f, 360f * progress.coerceIn(0, 100) / 100f, useCenter = true, topLeft = Offset.Zero, size = Size(size.width, size.height))
            }
            status == WorkStatus.Queued || status == WorkStatus.Todo -> Canvas(Modifier.size(10.dp)) {
                drawCircle(color, radius = size.minDimension / 2 - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            }
            else -> Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        }
        // Shepherdr's own words for the minutes left ("6m", "<1m", "2h"), under the dot in amber; else its agent's guess.
        if (left != null && status == WorkStatus.Work) Text(
            left, style = CodeStyle.copy(fontSize = 10.sp), color = Herdr.colors.working, maxLines = 1,
            modifier = Modifier.padding(top = Space.xxs),
        ) else if (eta != null && status == WorkStatus.Work) Text(
            minutes(eta), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = Herdr.colors.muted, maxLines = 1,
            modifier = Modifier.padding(top = Space.xxs),
        )
    }
}

/** A colour over [under], opaque: the pie's track is its colour's tint, as the web board's. */
private fun Color.compositeOn(under: Color): Color {
    val a = alpha
    return Color(red * a + under.red * (1 - a), green * a + under.green * (1 - a), blue * a + under.blue * (1 - a))
}

/** "12m", "1h 20m": time left as the row says it. */
internal fun minutes(m: Int): String = if (m < 60) "${m}m" else if (m % 60 == 0) "${m / 60}h" else "${m / 60}h ${m % 60}m"

/**
 * A task as one compact board row, Shepherdr's way: its status mark, its id and title on one line,
 * what it does now (or waits for) under it, and on the right its agent's chip (a tap opens its chat)
 * over how long since it changed. About twice as many fit a screen as the old cards.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BoardRow(
    name: String,
    title: String,
    line: String,
    status: WorkStatus,
    description: String,
    onOpen: () -> Unit,
    onClickLabel: String,
    progress: Int? = null,
    eta: Int? = null,
    left: String? = null,
    agent: String = "",
    onAgent: (() -> Unit)? = null,
    age: String = "",
    pinned: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    actions: List<CustomAccessibilityAction> = emptyList(),
    /** Its status's word first on its line, in its colour: where no section heading says it already. */
    showWord: Boolean = true,
) {
    val c = Herdr.colors
    val quiet = status == WorkStatus.Done
    val color = status.color()
    // The word, unless the line starts with it already ("Ready to merge", "Merging: …").
    val word = status.label.takeIf { showWord && !line.startsWith(it, ignoreCase = true) }
    val second = buildAnnotatedString {
        if (word != null) {
            withStyle(SpanStyle(color = color)) { append(word) }
            if (line.isNotEmpty()) append(" · ")
        }
        append(line)
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RectangleShape)
            .combinedClickable(onClickLabel = onClickLabel, onClick = onOpen, onLongClickLabel = onLongClick?.let { "show more actions" }, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                val all = actions + listOfNotNull(onAgent?.let { o -> CustomAccessibilityAction("Open agent") { o(); true } })
                if (all.isNotEmpty()) customActions = all
            }
            .padding(vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusMark(status, progress, eta, left)
        Spacer(Modifier.width(Space.s))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = CodeStyle.copy(fontSize = 12.sp), color = c.muted, maxLines = 1)
                Spacer(Modifier.width(Space.s))
                Text(
                    title, style = MaterialTheme.typography.titleMedium, color = if (quiet) c.muted else c.strong,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (pinned) Icon(HerdrIcons.Pin, "pinned", tint = c.muted, modifier = Modifier.padding(start = Space.xs).size(14.dp))
            }
            if (second.isNotEmpty()) Text(second, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onAgent != null || agent.isNotEmpty() || age.isNotEmpty()) Column(Modifier.padding(start = Space.s), horizontalAlignment = Alignment.End) {
            if (onAgent != null) AgentChip(agent, onAgent)
            else if (agent.isNotEmpty()) Text(
                agent, style = MaterialTheme.typography.labelMedium, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 110.dp),
            )
            if (age.isNotEmpty()) Text(age, style = MaterialTheme.typography.labelSmall, color = c.faint, maxLines = 1, modifier = Modifier.padding(top = Space.xxs))
        }
    }
}

/** A Shepherdr task as a [BoardRow]; [space] names its space where the list mixes them. */
@Composable
internal fun WorkTaskRow(t: WorkTask, onOpen: () -> Unit, space: String = "", showWord: Boolean = true) {
    val onAgent = if (t.chat.isNotEmpty()) onOpen else null
    val pr = prNumberOf(t.pr)?.let { "PR #$it" }
    BoardRow(
        name = t.name, title = t.title,
        line = listOfNotNull(
            (if (t.accepting) "Accepting…" else t.line.ifEmpty { null }).takeIf { t.status != WorkStatus.Done }, pr, space.ifEmpty { null },
        ).joinToString(" · "),
        status = t.status,
        description = listOf(t.name, t.status.label, t.title, t.line, space).filter { it.isNotEmpty() }.joinToString(", "),
        onOpen = onOpen, onClickLabel = if (onAgent != null) "Open its chat" else "Open details",
        progress = t.progress, eta = t.eta, left = t.left, agent = t.agent, onAgent = onAgent, age = t.age, pinned = t.pinned, showWord = showWord,
    )
}

/** A space whose coordinator waits for you, as a row: opens its chat, where the question is. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CoordinatorAsksRow(s: WorkSpace, onOpen: () -> Unit) {
    val c = Herdr.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RectangleShape)
            .combinedClickable(onClickLabel = "Open the Assistant chat", onClick = onOpen).padding(vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            Icon(HerdrIcons.MessagesSquare, null, tint = WorkStatus.Need.color(), modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(Space.s))
        Column(Modifier.weight(1f)) {
            Text("${s.name}'s Assistant asks you", style = MaterialTheme.typography.titleMedium, color = c.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Answer it in the Assistant chat", style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1)
        }
        Icon(HerdrIcons.ChevronRight, null, tint = c.muted, modifier = Modifier.size(20.dp))
    }
}
