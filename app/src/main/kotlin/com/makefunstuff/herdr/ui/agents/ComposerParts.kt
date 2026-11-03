package com.makefunstuff.herdr.ui.agents

import androidx.compose.foundation.Canvas
import com.makefunstuff.herdr.core.chat.VoiceNotes
import com.makefunstuff.herdr.core.chat.VoiceRecorder
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.chat.AgentCommand
import com.makefunstuff.herdr.core.chat.Held
import com.makefunstuff.herdr.core.chat.HeldMessage
import com.makefunstuff.herdr.core.chat.ModelPill
import com.makefunstuff.herdr.core.chat.ModelUse
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrSheet
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.InfoLine
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.SheetTitle
import com.makefunstuff.herdr.ui.design.hairline
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Radius
import com.makefunstuff.herdr.ui.theme.Space

/**
 * The messages held while the agent worked (see [Held]), over the composer: a caption with an
 * hourglass ("Held until claude is ready · 2 messages"), then a row per message with Send now and
 * an X that discards it. Once the agent is ready the caption says to review and send, in amber.
 * [folded] (while the agent asks something) shows the caption alone; a tap opens the rows.
 */
@Composable
fun HeldList(
    held: List<HeldMessage>,
    status: AgentStatus,
    agent: String,
    canSend: Boolean,
    onSendNow: (HeldMessage) -> Unit,
    onDiscard: (HeldMessage) -> Unit,
    folded: Boolean = false,
    onUnfold: () -> Unit = {},
) {
    val c = Herdr.colors
    val ready = Held.ready(status)
    val shape = RoundedCornerShape(Radius.xl)
    Surface(
        color = c.card,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (ready) c.accent else c.line),
        shape = shape,
        modifier = Modifier.fillMaxWidth().shadow(6.dp, shape, ambientColor = c.shadow, spotColor = c.shadow)
            .semantics { contentDescription = "Held messages (${held.size})" },
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 36.dp).then(if (folded) Modifier.clickable(onClick = onUnfold) else Modifier)
                    .padding(start = Space.l, end = Space.s, top = Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HerdrIcons.Hourglass, null, tint = if (ready) c.accent else c.muted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(Space.s))
                Text(
                    Held.caption(status, held.size, agent),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ready) c.text else c.muted,
                    modifier = Modifier.weight(1f),
                )
                if (folded) Icon(HerdrIcons.ChevronUp, "Show the held messages", tint = c.muted, modifier = Modifier.size(16.dp))
            }
            if (!folded) {
                // Two and a half rows, then they scroll.
                Column(Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                    held.forEachIndexed { i, m ->
                        if (i > 0) RowDivider(inset = Space.l)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = Space.l),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // A voice message held: its length, not its file's path.
                            val voiceMillis = VoiceNotes.mentioned(m.text)?.let(VoiceNotes::millis)
                            Text(
                                if (voiceMillis != null) "Voice message · ${VoiceRecorder.elapsed(voiceMillis)}" else "“${m.text}”",
                                style = MaterialTheme.typography.bodyLarge,
                                color = c.strong,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(vertical = Space.s),
                            )
                            QuietButton(onClick = { onSendNow(m) }, enabled = canSend) {
                                Text("Send now", color = if (!canSend) c.off else if (ready) c.accent else c.text)
                            }
                            Box(
                                Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = { onDiscard(m) })
                                    .semantics { contentDescription = "Discard" },
                                contentAlignment = Alignment.Center,
                            ) { Icon(HerdrIcons.X, null, tint = c.muted, modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
            }
        }
    }
}

/** The quick replies over the composer, one tap each to send; the pencil at the end edits them. */
@Composable
fun QuickReplyRow(replies: List<String>, onReply: (String) -> Unit, onEdit: (() -> Unit)?) {
    val c = Herdr.colors
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        replies.forEach { r ->
            Box(
                Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Send", onClick = { onReply(r) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    r,
                    style = MaterialTheme.typography.labelLarge,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 240.dp).clip(HerdrShapes.Pill).background(c.card).hairline(HerdrShapes.Pill)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        if (onEdit != null) Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onEdit)
                .semantics { contentDescription = "Edit quick replies" },
            contentAlignment = Alignment.Center,
        ) { Icon(HerdrIcons.Pencil, null, tint = c.muted, modifier = Modifier.size(16.dp)) }
    }
}

/** One row of the completion menu: what goes in (mono) and what it is. */
data class CompletionRow(val insert: String, val label: String, val detail: String?)

/**
 * What `/` or `@` completes, over the composer: a small caps heading ("CLAUDE COMMANDS", "FILES IN
 * HERDR-ANDROID") and up to about five rows, then it scrolls. [note] stands in for the rows while
 * they load, or when none match.
 */
@Composable
fun CompletionMenu(title: String, rows: List<CompletionRow>, note: String?, onPick: (CompletionRow) -> Unit) {
    val c = Herdr.colors
    val shape = RoundedCornerShape(Radius.xl)
    Surface(
        color = c.card,
        border = androidx.compose.foundation.BorderStroke(1.dp, c.line),
        shape = shape,
        modifier = Modifier.fillMaxWidth().shadow(6.dp, shape, ambientColor = c.shadow, spotColor = c.shadow),
    ) {
        Column(Modifier.padding(vertical = Space.xs)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = c.muted,
                modifier = Modifier.padding(start = Space.l, end = Space.l, top = Space.s, bottom = Space.xs).semantics { heading() },
            )
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(horizontal = Space.l, vertical = Space.m))
            } else {
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    rows.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = { onPick(r) })
                                .padding(horizontal = Space.l, vertical = Space.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                r.label,
                                style = CodeStyle,
                                color = c.strong,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = if (r.detail != null) Modifier.widthIn(min = 96.dp, max = 180.dp) else Modifier.weight(1f),
                            )
                            if (r.detail != null) {
                                Spacer(Modifier.width(Space.m))
                                Text(
                                    r.detail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The menu's rows for `/` commands. */
fun commandRows(commands: List<AgentCommand>): List<CompletionRow> =
    commands.map { CompletionRow("/" + it.name, "/" + it.name, it.description) }

/** The menu's rows for `@` files: the file's name, then its folder (`app/…/ui/machines`); the whole path goes in. */
fun fileRows(files: List<String>): List<CompletionRow> = files.map { path ->
    val folder = path.substringBeforeLast('/', "")
    val parts = folder.split('/').filter { it.isNotEmpty() }
    val shown = if (parts.size <= 3) folder else parts.first() + "/…/" + parts.takeLast(2).joinToString("/")
    CompletionRow("@$path", path.substringAfterLast('/'), shown.ifEmpty { null })
}

/**
 * The model pill in the composer's controls (`.composer-pill`): the context ring and "Opus 5.5 · 62%"
 * on a quiet fill; a tap opens its details.
 */
@Composable
fun ModelPillButton(pill: ModelPill, onClick: () -> Unit) {
    val c = Herdr.colors
    val shape = RoundedCornerShape(50)
    val percent = pill.percent
    val words = pill.title + (percent?.let { " · $it%" } ?: "")
    Box(
        Modifier.heightIn(min = 48.dp).clip(shape).clickable(role = Role.Button, onClickLabel = "Model details", onClick = onClick)
            .semantics { contentDescription = "Model ${pill.title}" + (pill.percent?.let { ", context $it% full" } ?: "") },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.heightIn(min = 32.dp).clip(shape).background(c.hover).padding(horizontal = Space.m - Space.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (percent != null) {
                ContextRing(percent, Modifier.size(14.dp))
                Spacer(Modifier.width(Space.xs + Space.xxs))
            }
            Text(words, style = MaterialTheme.typography.labelLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        }
    }
}

/** How full the context is, as a ring: the track hairline-strong, the fill in text colour, amber past 80%. */
@Composable
fun ContextRing(percent: Int, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    val fill = if (percent >= 80) c.notice else c.text
    val track = c.text.copy(alpha = 0.22f)
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        val inset = stroke / 2
        val arc = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
        drawArc(fill, -90f, 360f * percent / 100f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/**
 * The model pill's details: the model by name and id, its reasoning level, how much of the
 * context its last request filled (and of what window), what herdr reports about the agent, and
 * the way to the machine's usage and limits page.
 */
@Composable
fun ModelSheet(
    pill: ModelPill,
    agent: String,
    stateLabel: String?,
    reported: Map<String, String>,
    limit: String?,
    onUsage: (() -> Unit)?,
    onDismiss: () -> Unit,
    /**
     * Switches the model or the effort with the agent's own command (`/model opus`); null where it
     * can't be switched from the phone. [switchNote] says why it can't right now.
     */
    onSwitch: ((command: String) -> Unit)? = null,
    switchNote: String? = null,
) {
    val c = Herdr.colors
    val use = pill.use
    val percent = pill.percent
    HerdrSheet(onDismissRequest = onDismiss) {
        SheetTitle(pill.title, subtitle = agent)
        if (onSwitch != null) SwitchModel(use, switchNote, onSwitch)
        Column(Modifier.padding(horizontal = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (percent != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ContextRing(percent, Modifier.size(20.dp))
                    Spacer(Modifier.width(Space.s))
                    Text("Context ${percent}% full", style = MaterialTheme.typography.titleMedium, color = c.strong)
                }
                Box(Modifier.fillMaxWidth().padding(vertical = Space.xs).height(6.dp).clip(HerdrShapes.Pill).background(c.input)) {
                    Box(
                        Modifier.fillMaxWidth(percent / 100f).height(6.dp).clip(HerdrShapes.Pill)
                            .background(if (percent >= 80) c.notice else c.accent),
                    )
                }
            }
            if (use != null && use.used > 0) {
                InfoLine("Last request", "${ModelUse.tokens(use.used)} tokens")
                use.window?.let { w -> InfoLine("Window", ModelUse.tokens(w) + if (use.windowAssumed) " (usual size)" else "") }
            }
            use?.effort?.let { InfoLine("Reasoning", it.replaceFirstChar { ch -> ch.uppercase() }) }
            use?.model?.let { InfoLine("Model id", it, mono = true) }
            stateLabel?.let { InfoLine("State", it) }
            reported.forEach { (k, v) -> InfoLine(k.replaceFirstChar { it.uppercase() }, v) }
            if (percent != null && percent >= 80) {
                Text(
                    "Nearly full: /compact summarises the conversation to free room.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                    modifier = Modifier.padding(top = Space.xs),
                )
            }
            if (use?.windowAssumed == true) {
                Text(
                    "The transcript doesn't say how big the window is, so this counts the model's usual one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                    modifier = Modifier.padding(top = Space.xs),
                )
            }
        }
        if (onUsage != null) {
            Spacer(Modifier.height(Space.s))
            ListRow(
                title = "Usage and limits",
                subtitle = limit ?: "What each provider allows, and what was used",
                leading = { Icon(HerdrIcons.Gauge, null, tint = c.muted, modifier = Modifier.size(20.dp)) },
                trailing = { Icon(HerdrIcons.ChevronRight, null, tint = c.muted, modifier = Modifier.size(18.dp)) },
                onClick = onUsage,
            )
        }
        Spacer(Modifier.height(Space.m))
    }
}

/**
 * Claude's model and effort as chips, the ones it runs at now picked: a tap sends its `/model` or
 * `/effort` command, which it takes even while it works. A model picked so also becomes its
 * default for new sessions, as on the computer.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SwitchModel(use: com.makefunstuff.herdr.core.chat.ModelUse?, note: String?, onSwitch: (String) -> Unit) {
    val c = Herdr.colors
    val family = com.makefunstuff.herdr.core.chat.AgentSettings.family(use?.model)
    val effort = use?.effort
    val enabled = note == null
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.xl).padding(bottom = Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text("Model", style = MaterialTheme.typography.labelLarge, color = c.muted)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            com.makefunstuff.herdr.core.chat.AgentSettings.CLAUDE_MODELS.forEach { m ->
                HerdrChip(m.label, selected = m.value == family, onClick = {
                    if (enabled && m.value != family) onSwitch(com.makefunstuff.herdr.core.chat.AgentSettings.modelCommand(m.value))
                })
            }
        }
        Text("Effort", style = MaterialTheme.typography.labelLarge, color = c.muted, modifier = Modifier.padding(top = Space.xs))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            com.makefunstuff.herdr.core.chat.AgentSettings.CLAUDE_EFFORTS.forEach { e ->
                HerdrChip(e.label, selected = e.value == effort, onClick = {
                    if (enabled && e.value != effort) onSwitch(com.makefunstuff.herdr.core.chat.AgentSettings.effortCommand(e.value))
                })
            }
        }
        Text(
            note ?: "A model picked here also becomes Claude's default for new sessions. A level the model doesn't take is lowered to its highest.",
            style = MaterialTheme.typography.bodySmall,
            color = if (note != null) c.notice else c.muted,
            modifier = Modifier.padding(top = Space.xxs),
        )
    }
}

/**
 * Asks before a restart: what stops, the options it comes back with ([kept], null while they are
 * read), and words to send it once it is back (empty sends nothing).
 */
@Composable
fun RestartDialog(agent: String, kept: com.makefunstuff.herdr.core.chat.AgentFlags.Kept?, working: Boolean, onDismiss: () -> Unit, onRestart: (then: String?) -> Unit) {
    var then by remember { mutableStateOf("") }
    com.makefunstuff.herdr.ui.design.HerdrDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restart $agent?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(
                    (if (working) "What it's doing now stops. " else "") + "It quits, then opens the same conversation again in the same folder.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    kept == null -> Text("Reading its options…", style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted)
                    else -> {
                        if (kept.flags.isNotEmpty()) Text(
                            "With its options: " + kept.flags.joinToString(" "),
                            style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted,
                        )
                        if (kept.dropped.isNotEmpty()) Text(
                            "Without " + kept.dropped.joinToString(", ") + ": the phone never starts an agent that skips its permission prompts.",
                            style = MaterialTheme.typography.bodySmall, color = Herdr.colors.notice,
                        )
                    }
                }
                com.makefunstuff.herdr.ui.design.HerdrTextField(
                    value = then,
                    onValueChange = { then = it },
                    label = "Then tell it",
                    hint = "Optional: sent once it's back, such as \"continue\"",
                    singleLine = false,
                )
            }
        },
        confirmButton = { com.makefunstuff.herdr.ui.design.DangerButton("Restart", enabled = kept != null) { onRestart(then.trim().ifEmpty { null }) } },
        dismissButton = { QuietButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The restart's steps while it runs, over the chat. */
@Composable
fun RestartProgress(phase: com.makefunstuff.herdr.core.chat.AgentRestart.Phase, agent: String) {
    val words = when (phase) {
        com.makefunstuff.herdr.core.chat.AgentRestart.Phase.Stopping -> "Quitting $agent"
        com.makefunstuff.herdr.core.chat.AgentRestart.Phase.Starting -> "Opening the conversation again"
        com.makefunstuff.herdr.core.chat.AgentRestart.Phase.Waiting -> "Waiting for $agent to be ready"
        com.makefunstuff.herdr.core.chat.AgentRestart.Phase.Sending -> "Sending your message"
        com.makefunstuff.herdr.core.chat.AgentRestart.Phase.Done -> "Restarted"
    }
    com.makefunstuff.herdr.ui.design.HerdrDialog(
        onDismissRequest = {},
        title = { Text("Restarting") },
        text = { com.makefunstuff.herdr.ui.design.LoadingState(words) },
        confirmButton = {},
    )
}
