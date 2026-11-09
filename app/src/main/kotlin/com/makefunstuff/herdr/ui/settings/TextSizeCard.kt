package com.makefunstuff.herdr.ui.settings

import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.makefunstuff.herdr.data.TextSizeStore
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Mono
import com.makefunstuff.herdr.ui.theme.Space
import kotlin.math.roundToInt

/**
 * The chats' and the terminal's text sizes in Settings, each its own, on top of the phone's font
 * size: a − and a + for each, and a line of each as it will read.
 */
@Composable
fun TextSizeCard() {
    val store = LocalContainer.current.textSizes
    val sizes by store.state.collectAsStateWithLifecycle()
    val c = Herdr.colors
    val steps = TextSizeStore.CHAT_STEPS
    val step = steps.indexOf(sizes.chat).coerceAtLeast(0)
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text("Text size", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(
            "Each its own, on top of the phone's font size. Pinching the terminal changes its size too.",
            style = MaterialTheme.typography.bodySmall,
            color = c.muted,
        )
        Column {
            RowDivider(inset = 0.dp)
            SizeRow(
                "Chats", "${(sizes.chat * 100).roundToInt()}%", "chat text",
                smaller = steps.getOrNull(step - 1)?.let { { store.setChat(it) } },
                larger = steps.getOrNull(step + 1)?.let { { store.setChat(it) } },
            ) {
                Text(
                    "The deploy is running; I'll tell you when it lands.",
                    style = MaterialTheme.typography.bodyLarge.let { it.copy(fontSize = it.fontSize * sizes.chat, lineHeight = it.lineHeight * sizes.chat) },
                    color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            RowDivider(inset = 0.dp)
            val sp = sizes.terminal.roundToInt()
            SizeRow(
                "Terminal", "$sp", "terminal text",
                smaller = (sp - 1).takeIf { it >= TextSizeStore.TERMINAL_RANGE.start }?.let { { store.setTerminal(it.toFloat()) } },
                larger = (sp + 1).takeIf { it <= TextSizeStore.TERMINAL_RANGE.endInclusive }?.let { { store.setTerminal(it.toFloat()) } },
            ) {
                Text(
                    "$ git status",
                    fontFamily = Mono, fontSize = sizes.terminal.sp, lineHeight = (sizes.terminal * 1.3f).sp, color = c.text, maxLines = 1,
                    modifier = Modifier.fillMaxWidth().clip(HerdrShapes.Tile).background(c.panel).padding(horizontal = Space.s, vertical = Space.xs),
                )
            }
        }
    }
}

/** One size: its name and value, − and + (gone at the ends), and a sample of it below. */
@Composable
private fun SizeRow(
    label: String,
    value: String,
    what: String,
    smaller: (() -> Unit)?,
    larger: (() -> Unit)?,
    sample: @Composable () -> Unit,
) {
    val c = Herdr.colors
    Column(Modifier.padding(vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = c.strong, modifier = Modifier.weight(1f))
            Step(HerdrIcons.Minus, "Smaller $what", smaller)
            Text(
                value, style = MaterialTheme.typography.labelLarge, color = c.strong, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 48.dp).semantics { contentDescription = "$label: $value" },
            )
            Step(HerdrIcons.Plus, "Larger $what", larger)
        }
        sample()
    }
}

@Composable
private fun Step(icon: ImageVector, description: String, onClick: (() -> Unit)?) {
    val c = Herdr.colors
    Box(
        Modifier.size(48.dp).clip(RectangleShape)
            .clickable(enabled = onClick != null, role = Role.Button, onClick = { onClick?.invoke() })
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(36.dp).clip(RectangleShape).background(c.hover), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (onClick != null) c.text else c.muted.copy(alpha = 0.38f), modifier = Modifier.size(18.dp))
        }
    }
}
