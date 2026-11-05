package com.makefunstuff.herdr.ui.terminal

import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.terminal.BarKey
import com.makefunstuff.herdr.core.terminal.CursorDrag
import com.makefunstuff.herdr.core.terminal.Hold
import com.makefunstuff.herdr.core.terminal.KeyBar
import com.makefunstuff.herdr.core.terminal.Sticky
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.tapFeedback
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Mono
import com.makefunstuff.herdr.ui.theme.Radius
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The key bar over the keyboard: [keys] in rows as wide as the screen, the edit key last; with
 * [fnPage], the page of F-keys instead. Sticky modifiers show how they stand ([sticky]). Space sends
 * a space on a tap; held, then dragged, it moves the cursor ([onCursor] gets the arrow keys).
 */
@Composable
fun KeyBarRows(
    keys: List<BarKey>,
    sticky: Sticky,
    fnPage: Boolean,
    onKey: (BarKey) -> Unit,
    onCursor: (String) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = Space.xs)) {
        val perRow = KeyBar.perRow(maxWidth.value)
        val rows = if (fnPage) KeyBar.rows(KeyBar.F_PAGE, perRow, edit = false) else KeyBar.rows(keys, perRow)
        Column {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // The edit key keeps the right end of the last row; empty slots go before it.
                    val keysHere = row.filterNotNull()
                    keysHere.forEach { key -> BarKeyView(key, sticky, fnPage, onKey, onCursor, Modifier.weight(1f)) }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                    if (row.size != keysHere.size) EditKey(onEdit, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun BarKeyView(key: BarKey, sticky: Sticky, fnPage: Boolean, onKey: (BarKey) -> Unit, onCursor: (String) -> Unit, modifier: Modifier) {
    val name = KeyBar.name(key)
    when {
        key is BarKey.Modifier -> {
            val hold = sticky[key.which]
            KeyCap(
                KeyBar.label(key), modifier, armed = hold == Hold.Once, locked = hold == Hold.Locked,
                description = name,
                state = when (hold) { Hold.Off -> "off"; Hold.Once -> "on for the next key"; Hold.Locked -> "kept on" },
            ) { onKey(key) }
        }
        key is BarKey.Send && key.isSpace -> SpaceKey(modifier, onTap = { onKey(key) }, onCursor = onCursor)
        key == BarKey.Keyboard -> KeyCap(KeyBar.label(key), modifier, highlighted = true, description = "Show the keyboard", content = {
            Icon(HerdrIcons.Keyboard, null, tint = Herdr.colors.text, modifier = Modifier.size(20.dp))
        }) { onKey(key) }
        key == BarKey.FKeys -> KeyCap(KeyBar.label(key), modifier, armed = fnPage, description = if (fnPage) "Back to your keys" else "F-keys") { onKey(key) }
        else -> KeyCap(KeyBar.label(key), modifier, description = name) { onKey(key) }
    }
}

/**
 * A key (the web UI's `.key`): a 52dp row with a 40dp key drawn in it, no fill and a hairline,
 * `--radius-sm`. An armed modifier is the chosen one (`--bg-elevated`, an amber edge, strong text);
 * a kept one is filled with amber's tint and underlined.
 */
@Composable
private fun KeyCap(
    label: String,
    modifier: Modifier,
    armed: Boolean = false,
    locked: Boolean = false,
    highlighted: Boolean = false,
    description: String? = null,
    state: String? = null,
    content: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Box(
        modifier.heightIn(min = 52.dp)
            .clickable(role = Role.Button) { view.tapFeedback(); onClick() }
            .semantics {
                if (description != null) contentDescription = description
                if (state != null) stateDescription = state
            }
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        KeyFace(label, armed, locked, highlighted, content)
    }
}

@Composable
private fun KeyFace(label: String, armed: Boolean, locked: Boolean, highlighted: Boolean, content: (@Composable () -> Unit)? = null) {
    val c = Herdr.colors
    val on = armed || locked
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RectangleShape)
            .background(
                when {
                    locked -> c.tint(c.accent)
                    armed -> c.card
                    highlighted -> c.hover
                    else -> Color.Transparent
                },
            )
            .border(1.dp, if (on) c.accent else c.line, RectangleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) content() else Text(
            label,
            fontFamily = Mono,
            style = if (label.length > 3) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            color = if (on) c.strong else c.text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
        if (locked) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(width = 14.dp, height = 2.dp).background(c.accent, RectangleShape))
        }
    }
}

/** The edit key: opens the key bar's editor. */
@Composable
private fun EditKey(onEdit: () -> Unit, modifier: Modifier) {
    val c = Herdr.colors
    KeyCap("", modifier, description = "Edit the key bar", content = {
        Icon(HerdrIcons.Pencil, null, tint = c.text, modifier = Modifier.size(18.dp))
    }, onClick = onEdit)
}

/**
 * Space: a tap types a space. Held until the long press, the finger then moves the cursor: a cell
 * for each 12dp across, a line for each 24dp up or down; the key says so while it does.
 */
@Composable
private fun SpaceKey(modifier: Modifier, onTap: () -> Unit, onCursor: (String) -> Unit) {
    val view = LocalView.current
    val density = LocalDensity.current
    var moving by remember { mutableStateOf(false) }
    val tap by rememberUpdatedState(onTap)
    val cursor by rememberUpdatedState(onCursor)
    val stepX = with(density) { 12.dp.toPx() }
    val stepY = with(density) { 24.dp.toPx() }
    Box(
        modifier.heightIn(min = 52.dp)
            .pointerInput(stepX, stepY) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var up = false
                    val beforeLong = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        val released = waitForUpOrCancellation()
                        up = released != null
                        released?.consume()
                        true
                    }
                    if (beforeLong != null) {
                        if (up) { view.tapFeedback(); tap() }
                        return@awaitEachGesture
                    }
                    moving = true
                    view.tapFeedback()
                    val drag = CursorDrag(stepX, stepY)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val d = change.position - change.previousPosition
                            change.consume()
                            val keys = drag.moved(d.x, d.y)
                            if (keys.isNotEmpty()) cursor(keys)
                        }
                    } finally {
                        moving = false
                    }
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = "Space"
                stateDescription = "Hold and drag to move the cursor"
                onClick { tap(); true }
            }
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        KeyFace(if (moving) "◂ ▸" else "space", armed = moving, locked = false, highlighted = false)
    }
}
