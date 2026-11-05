package com.makefunstuff.herdr.ui.board

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.shepherdr.V2Board
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.common.Pill
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.sheetColor
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

/** An action running on the board: a thin accent bar and what it is. */
@Composable
internal fun BusyLine(what: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.xs)) {
        LinearProgressIndicator(
            Modifier.fillMaxWidth().height(3.dp).clip(HerdrShapes.Pill).semantics { contentDescription = "Running: $what" },
            color = Herdr.colors.accent, trackColor = Herdr.colors.line,
        )
        Text("$what…", style = MaterialTheme.typography.labelMedium, color = Herdr.colors.muted, modifier = Modifier.padding(top = Space.xs))
    }
}

/** A thin rounded progress bar in a status colour. */
@Composable
internal fun Progress(p: Int, color: Color, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { p / 100f }, color = color, trackColor = Herdr.colors.line,
        gapSize = 0.dp, drawStopIndicator = {},
        modifier = modifier.height(4.dp).clip(HerdrShapes.Pill),
    )
}

/**
 * The card's way to its agent: the agent's name with a chat glyph, in amber, a tap target of its own.
 * A Shepherdr 3 task's CLI ("Claude Code") shows as its kind ("claude").
 */
@Composable
internal fun AgentChip(agent: String, onClick: () -> Unit) {
    val c = Herdr.colors
    Row(
        Modifier.padding(start = Space.s).clip(HerdrShapes.Pill).background(c.accentTint)
            .clickable(onClickLabel = "Open agent", onClick = onClick).padding(horizontal = Space.s, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HerdrIcons.MessageSquare, null, tint = c.accent, modifier = Modifier.size(14.dp))
        Text(
            V2Board.agentKind(agent).ifEmpty { "agent" }, style = MaterialTheme.typography.labelMedium, color = c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Space.xs).widthIn(max = 110.dp),
        )
    }
}

/**
 * The board's own bottom panel: a scrim and a rounded surface drawn inside the screen (no platform dialog or
 * Material sheet window), with a title, scrolling content and a fixed row of buttons.
 */
@Composable
fun BoardPanel(
    title: String,
    onDismiss: () -> Unit,
    accent: Color? = null,
    buttons: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Herdr.colors.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            color = sheetColor,
            shape = HerdrShapes.Sheet,
            modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).imePadding().heightIn(max = 640.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.navigationBarsPadding()) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).padding(top = Space.m, bottom = Space.s).width(36.dp).height(4.dp)
                        .clip(HerdrShapes.Pill).background(Herdr.colors.lineStrong),
                )
                Text(
                    title, style = MaterialTheme.typography.titleLarge, color = accent ?: Herdr.colors.strong,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = Space.xl, end = Space.xl, top = Space.xs, bottom = Space.m).semantics { heading() },
                )
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = Space.xl),
                    verticalArrangement = Arrangement.spacedBy(Space.m), content = content,
                )
                if (buttons != null) Row(
                    Modifier.fillMaxWidth().padding(start = Space.l, end = Space.l, top = Space.l, bottom = Space.m),
                    horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = buttons,
                ) else Spacer(Modifier.height(Space.l))
            }
        }
    }
}

@Composable
internal fun PanelCancel(onDismiss: () -> Unit) = QuietButton(onClick = onDismiss) { Text("Cancel") }

@Composable
internal fun LinkRow(label: String, value: String, onClick: () -> Unit) {
    QuietCard(onClick = onClick, onClickLabel = "Open $label in the browser", padded = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = Herdr.colors.accent)
                Text(value, style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(HerdrIcons.ExternalLink, null, tint = Herdr.colors.faint, modifier = Modifier.size(20.dp))
        }
    }
}

/** A titled part of the task that folds away: a 48dp heading row with a chevron, its content under it. */
@Composable
internal fun Section(title: String, startOpen: Boolean = true, key: String = title, content: @Composable () -> Unit) {
    var open by rememberSaveable(key) { mutableStateOf(startOpen) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(onClickLabel = if (open) "Collapse $title" else "Expand $title") { open = !open }
                .semantics { heading() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(if (open) HerdrIcons.ChevronUp else HerdrIcons.ChevronDown, if (open) "Collapse" else "Expand", tint = Herdr.colors.muted)
        }
        if (open) content()
    }
}

/** The first lines of a long Markdown text, about [lines] of them, and "Show all" to read the rest. Short text shows whole. */
@Composable
internal fun Preview(source: String, key: String, lines: Int, color: Color = Color.Unspecified) {
    val short = remember(source, lines) { previewOf(source, lines) }
    var all by rememberSaveable(key) { mutableStateOf(false) }
    Column(Modifier.animateContentSize()) {
        MarkdownText(if (all || short == null) source else short, style = MaterialTheme.typography.bodyMedium, color = color)
        if (short != null) QuietButton(onClick = { all = !all }, color = Herdr.colors.accent, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)) {
            Text(if (all) "Show less" else "Show all")
        }
    }
}

/** [source] cut to about [lines] lines (of some 60 characters) at a line's end, or null when it is that short already. */
internal fun previewOf(source: String, lines: Int): String? {
    val text = source.trim()
    val budget = lines * 60
    val rows = text.lines()
    if (text.length <= budget + 40 && rows.size <= lines + 1) return null
    val out = StringBuilder()
    for (row in rows) {
        if (out.isNotEmpty() && (out.length + row.length > budget || out.lines().size >= lines)) break
        if (out.isNotEmpty()) out.append('\n')
        out.append(if (row.length > budget) row.take(budget).substringBeforeLast(' ') + "…" else row)
    }
    return out.toString().trimEnd() + if (out.endsWith("…")) "" else " …"
}
