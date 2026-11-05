package com.makefunstuff.herdr.ui.design

import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.voice.VoiceState
import com.makefunstuff.herdr.core.voice.VoiceState.Phase
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

/** What the voice bar's controls do. */
class VoiceBarActions(
    val talk: (down: Boolean) -> Unit,
    val hush: () -> Unit,
    val handsFree: (Boolean) -> Unit,
    val mute: (Boolean) -> Unit,
    val speed: (Float) -> Unit,
    val close: () -> Unit,
)

/**
 * The coordinator's voice, over the chat's composer: the talk button (hold to talk, or tap to start
 * and tap again to send), what it is doing in a word with the voice's level, and below, hands-free,
 * the speed and mute. A tap on the bar while it speaks stops the voice.
 */
@Composable
fun VoiceBar(state: VoiceState, actions: VoiceBarActions, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    val speaking = state.phase == Phase.Speaking
    val tone = when (state.phase) {
        Phase.Speaking -> c.working
        Phase.Listening -> c.red
        Phase.Hearing, Phase.Sending -> c.accent
        Phase.Ready -> c.muted
    }
    QuietCard(modifier.fillMaxWidth(), onClick = if (speaking) actions.hush else null) {
        Row(Modifier.padding(start = Space.s, end = Space.xs, top = Space.s, bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
            TalkButton(state, actions.talk)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RectangleShape).background(tone))
                    Spacer(Modifier.width(Space.s))
                    Text(
                        state.words, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                        color = if (state.phase == Phase.Ready) c.muted else c.strong,
                    )
                    if (speaking || state.phase == Phase.Listening) {
                        Spacer(Modifier.width(Space.s))
                        Level(state.level, tone)
                    }
                    if (speaking) {
                        Spacer(Modifier.width(Space.s))
                        Text("Tap to stop", style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 1)
                    }
                }
                val note = state.problem ?: if (state.engine == VoiceState.Engine.Phone) "Phone voice: the voice server is away" else null
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.labelSmall, color = if (state.problem != null) c.notice else c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    SmallToggle("Hands-free", state.handsFree) { actions.handsFree(!state.handsFree) }
                    SmallToggle(speedWords(state.speed), false, description = "Speed ${speedWords(state.speed)}, tap for the next") {
                        actions.speed(VoiceState.nextSpeed(state.speed))
                    }
                }
            }
            IconSlot(if (state.muted) HerdrIcons.VolumeX else HerdrIcons.Volume2, if (state.muted) "Unmute" else "Mute", if (state.muted) c.notice else c.muted) {
                actions.mute(!state.muted)
            }
            IconSlot(HerdrIcons.X, "Turn voice off", c.muted, actions.close)
        }
    }
}

/** "1.2×". */
private fun speedWords(speed: Float): String =
    (if (speed % 1f == 0f) "%.0f" else "%.1f").format(speed) + "×"

/** The amber talk button: held, it hears you until you let go; tapped, until the next tap. */
@Composable
private fun TalkButton(state: VoiceState, talk: (Boolean) -> Unit) {
    val c = Herdr.colors
    val latest by rememberUpdatedState(state.phase)
    val onTalk by rememberUpdatedState(talk)
    val listening = state.phase == Phase.Listening
    val busy = state.phase == Phase.Hearing || state.phase == Phase.Sending
    Box(
        Modifier.size(48.dp).clip(RectangleShape)
            .background(if (busy) c.hover else c.primary)
            .semantics {
                contentDescription = if (listening) "Send what you said" else "Talk to the Assistant"
                stateDescription = state.words
                onClick { onTalk(!listening); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (latest == Phase.Listening) {
                        // Tapped to talk before: this tap sends.
                        onTalk(false)
                        return@detectTapGestures
                    }
                    if (latest == Phase.Hearing || latest == Phase.Sending) return@detectTapGestures
                    val t0 = System.currentTimeMillis()
                    onTalk(true)
                    tryAwaitRelease()
                    // Held: letting go sends. A quick tap keeps hearing until the next tap.
                    if (System.currentTimeMillis() - t0 >= HOLD_MS) onTalk(false)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (listening) HerdrIcons.Send else HerdrIcons.Mic, null,
            tint = if (busy) c.muted else c.onPrimary, modifier = Modifier.size(22.dp),
        )
    }
}

private const val HOLD_MS = 400L

/** Five bars that follow [level]. */
@Composable
private fun Level(level: Float, tone: androidx.compose.ui.graphics.Color) {
    val shape = listOf(0.45f, 0.8f, 1f, 0.7f, 0.5f)
    Row(Modifier.height(16.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        shape.forEach { s ->
            val h = (3f + 13f * (0.25f + 0.75f * level) * s).dp
            Box(Modifier.width(3.dp).height(h).clip(RectangleShape).background(tone))
        }
    }
}

@Composable
private fun SmallToggle(text: String, on: Boolean, description: String? = null, onClick: () -> Unit) {
    val c = Herdr.colors
    Box(
        Modifier.clip(RectangleShape)
            .background(if (on) c.hover else c.card)
            .border(1.dp, if (on) c.lineStrong else c.line, RectangleShape)
            .clickable(role = Role.Switch, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = Space.s, vertical = Space.xs),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (on) c.strong else c.muted, maxLines = 1)
    }
}

@Composable
private fun IconSlot(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RectangleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
    }
}
