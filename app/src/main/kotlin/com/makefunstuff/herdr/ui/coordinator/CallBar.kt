package com.makefunstuff.herdr.ui.coordinator

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.voice.CallState
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

/**
 * Over the composer while a voice call with this chat is on: who it is with, what it does now ("Listening",
 * "Thinking…", "Speaking") and End. After a call that ended by itself, why it did, until it is put away.
 */
@Composable
fun CallBar(call: CallState, modifier: Modifier, onEnd: () -> Unit) {
    val c = Herdr.colors
    QuietCard(modifier) {
        Row(Modifier.padding(start = Space.l), verticalAlignment = Alignment.CenterVertically) {
            Icon(HerdrIcons.Phone, null, Modifier.size(16.dp), tint = if (call.on) c.done else c.muted)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f).padding(vertical = Space.s)) {
                Text(
                    if (call.on) "On a call with ${call.who}" else "The call ended",
                    style = MaterialTheme.typography.bodyMedium, color = c.strong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (call.on) call.words else call.problem.orEmpty(),
                    style = MaterialTheme.typography.labelMedium, color = if (call.on) c.muted else c.red, maxLines = 3,
                )
            }
            QuietButton(onClick = onEnd, color = if (call.on) c.red else c.accent) { Text(if (call.on) "End" else "Close") }
        }
    }
}
