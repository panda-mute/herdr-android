package com.makefunstuff.herdr.ui.design

import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.chat.Dictation
import com.makefunstuff.herdr.core.chat.VoiceRecorder
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Radius
import com.makefunstuff.herdr.ui.theme.Space

/** Whether the composer records now: the box gives way to the recording. */
val VoiceRecorder.State.hearing: Boolean get() = this is VoiceRecorder.State.Recording

/**
 * The mic in the composer's row of controls, beside Send. Held, it records until the finger lets go,
 * which sends (sliding [CANCEL_SLIDE] to the left cancels); a quick tap starts it, and the next tap
 * sends. Where it [talks] (to type), a quick tap is that instead ([VoiceRecorder.tap]), and TalkBack
 * offers the hands-free voice message as an action. Idle it is a dim glyph like the attach button;
 * recording, a red circle that swells with your voice (a square in it while a tap's recording waits
 * for the next tap). One place for both, so a holding finger never loses it.
 */
@Composable
fun ComposerMic(recorder: VoiceRecorder, state: VoiceRecorder.State, enabled: Boolean, talks: Boolean = false) {
    val c = Herdr.colors
    val view = LocalView.current
    val rec = state as? VoiceRecorder.State.Recording
    val halo by animateDpAsState(if (rec != null) 32.dp + 14.dp * rec.level else 32.dp, tween(120), label = "halo")
    val description = when {
        rec != null && rec.held -> "Recording, let go to send"
        rec != null -> "Send voice message"
        talks -> "Talk to type"
        else -> "Record a voice message"
    }
    Box(
        Modifier.size(48.dp).clip(RectangleShape)
            .pointerInput(recorder, enabled) {
                if (!enabled) return@pointerInput
                val cancelAt = CANCEL_SLIDE.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    view.confirmTap()
                    recorder.down()
                    var upAt: Long? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            upAt = change.uptimeMillis
                            change.consume()
                            break
                        }
                        recorder.slide(cancel = change.position.x - down.position.x < -cancelAt)
                        change.consume()
                    }
                    // A gesture taken away (another window) counts as a long hold: what was said goes.
                    recorder.up(heldFor = upAt?.let { it - down.uptimeMillis } ?: Long.MAX_VALUE)
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = description
                if (state.hearing) stateDescription = "Recording"
                // TalkBack's double tap: a tap, which starts it and, the next time, sends it.
                if (enabled) onClick(label = if (rec != null) "Send" else if (talks) "Talk to type" else "Record") { recorder.down(); recorder.up(0); true }
                if (enabled && talks && rec == null) customActions = listOf(CustomAccessibilityAction("Record a voice message") { recorder.record(); true })
            },
        contentAlignment = Alignment.Center,
    ) {
        if (rec != null) {
            // How loud you are: a soft ring round the circle.
            Box(Modifier.size(halo).background(c.red.copy(alpha = 0.22f), RectangleShape))
            Box(Modifier.size(32.dp).background(c.red, RectangleShape), contentAlignment = Alignment.Center) {
                if (rec.held) Icon(HerdrIcons.Mic, null, tint = c.ground, modifier = Modifier.size(16.dp))
                else Box(Modifier.size(10.dp).background(c.ground, RectangleShape))
            }
        } else {
            Icon(HerdrIcons.Mic, null, tint = if (enabled) c.muted else c.off, modifier = Modifier.size(20.dp))
        }
    }
}

/** How far to the left a holding finger slides to cancel. */
val CANCEL_SLIDE = 96.dp

/**
 * What the composer records, in the message box's place: "Recording a voice message" over bars that
 * follow how loud you are, newest on the right.
 */
@Composable
fun RecordingLevels(state: VoiceRecorder.State) {
    val c = Herdr.colors
    val level = (state as? VoiceRecorder.State.Recording)?.level ?: 0f
    val levels = remember { mutableStateListOf<Float>().apply { repeat(LEVEL_BARS) { add(0f) } } }
    LaunchedEffect(level) {
        levels.removeAt(0)
        levels.add(level)
    }
    Column(
        Modifier.fillMaxWidth().padding(start = Space.l, end = Space.l, top = Space.m + Space.xxs, bottom = Space.xs)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text("Recording a voice message", style = MaterialTheme.typography.bodyLarge, color = c.text, maxLines = 1)
        Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            levels.forEach { l ->
                Box(Modifier.weight(1f).height(4.dp + 20.dp * l).background(c.red.copy(alpha = 0.35f + 0.65f * l), RectangleShape))
            }
        }
    }
}

private const val LEVEL_BARS = 32

/**
 * The left of the controls while it records: a pulsing red dot and how long you've spoken, then what
 * letting go does ("‹ Slide to cancel", "Let go to cancel" in red), or, for a tapped recording, an X
 * to cancel first. Ticks the [recorder] along ([VoiceRecorder.tick]).
 */
@Composable
fun RowScope.RecordingInfo(recorder: VoiceRecorder, state: VoiceRecorder.State) {
    val c = Herdr.colors
    val rec = state as? VoiceRecorder.State.Recording
    var now by remember { mutableLongStateOf(recorder.clock()) }
    LaunchedEffect(recorder) {
        while (true) {
            now = recorder.clock()
            recorder.tick()
            kotlinx.coroutines.delay(150)
        }
    }
    val started = rec?.startedAt ?: now
    if (rec != null && !rec.held) ComposerIconButton(HerdrIcons.X, "Cancel voice message", onClick = recorder::cancel)
    else Spacer(Modifier.width(Space.l))
    val pulse = rememberInfiniteTransition(label = "recording")
    val alpha by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dot")
    Box(Modifier.size(8.dp).alpha(if (rec != null) alpha else 0.5f).background(c.red, RectangleShape))
    Spacer(Modifier.width(Space.s))
    Text(VoiceRecorder.elapsed(now - started), style = CodeStyle, color = c.strong, maxLines = 1)
    val hint = when {
        rec == null -> null
        rec.cancelling -> "Let go to cancel"
        rec.held -> "‹ Slide to cancel"
        else -> "Tap to send"
    }
    if (hint != null) Text(
        hint,
        style = MaterialTheme.typography.labelMedium,
        color = if (rec?.cancelling == true) c.red else c.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(horizontal = Space.m).semantics { liveRegion = LiveRegionMode.Polite },
    ) else Spacer(Modifier.weight(1f))
}

/**
 * Talk to type, in the message box's place: the words heard so far as you speak (the phone's
 * recogniser writes them as it goes), else "Listening…", over bars that follow how loud you are;
 * once Done is pressed, "Writing it down…".
 */
@Composable
fun DictationLevels(state: Dictation.State) {
    val c = Herdr.colors
    val listening = state as? Dictation.State.Listening
    val level = listening?.level ?: 0f
    val levels = remember { mutableStateListOf<Float>().apply { repeat(LEVEL_BARS) { add(0f) } } }
    LaunchedEffect(level) {
        levels.removeAt(0)
        levels.add(level)
    }
    val heard = listening?.heard.orEmpty()
    Column(
        Modifier.fillMaxWidth().padding(start = Space.l, end = Space.l, top = Space.m + Space.xxs, bottom = Space.xs)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        when {
            state is Dictation.State.Writing -> Text("Writing it down…", style = MaterialTheme.typography.bodyLarge, color = c.muted, maxLines = 1)
            heard.isNotEmpty() -> Text(heard, style = MaterialTheme.typography.bodyLarge, color = c.strong, maxLines = 4, overflow = TextOverflow.Ellipsis)
            else -> Text("Listening… speak, then Done", style = MaterialTheme.typography.bodyLarge, color = c.muted, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            levels.forEach { l ->
                Box(Modifier.weight(1f).height(4.dp + 20.dp * l).background(c.text.copy(alpha = 0.25f + 0.65f * l), RectangleShape))
            }
        }
    }
}

/**
 * The left of the controls while you talk to type: an X that drops it, a pulsing dot and how long
 * you've spoken, and "Talk to type". Ticks the [dictation] along ([Dictation.tick]).
 */
@Composable
fun RowScope.DictationInfo(dictation: Dictation, state: Dictation.State) {
    val c = Herdr.colors
    var now by remember { mutableLongStateOf(dictation.clock()) }
    LaunchedEffect(dictation) {
        while (true) {
            now = dictation.clock()
            dictation.tick()
            kotlinx.coroutines.delay(150)
        }
    }
    val listening = state is Dictation.State.Listening
    val started = (state as? Dictation.State.Listening)?.startedAt ?: (state as? Dictation.State.Writing)?.startedAt ?: now
    ComposerIconButton(HerdrIcons.X, "Cancel talk to type", onClick = dictation::cancel)
    val pulse = rememberInfiniteTransition(label = "dictating")
    val alpha by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dot")
    Box(Modifier.size(8.dp).alpha(if (listening) alpha else 0.5f).background(c.red, RectangleShape))
    Spacer(Modifier.width(Space.s))
    Text(VoiceRecorder.elapsed(now - started), style = CodeStyle, color = c.strong, maxLines = 1)
    Text(
        if (listening) "Talk to type" else "Writing",
        style = MaterialTheme.typography.labelMedium,
        color = c.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(horizontal = Space.m),
    )
}

/** Over the composer when talk to type went wrong: what to do, and OK. */
@Composable
fun DictationNotice(message: String, onDismiss: () -> Unit) {
    val c = Herdr.colors
    Surface(color = c.card, border = BorderStroke(1.dp, c.line), shape = RectangleShape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = Space.l, end = Space.s, top = Space.m)) {
            Row(Modifier.padding(end = Space.s).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Icon(HerdrIcons.CircleAlert, null, tint = c.notice, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                Spacer(Modifier.width(Space.s))
                Text(message, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                QuietButton(onClick = onDismiss) { Text("OK") }
            }
        }
    }
}

/** Where a voice message of yours is: with the agent, on its way, waiting for the machine, or not sent ([Failed.why]). */
sealed interface VoiceStatus {
    data object Sent : VoiceStatus
    data object Sending : VoiceStatus
    data object Offline : VoiceStatus
    /** Waits for one before it that failed. */
    data object Behind : VoiceStatus
    data class Failed(val why: String) : VoiceStatus
}

/**
 * A voice message in a chat: a play button, bars that fill as it plays, and how long it is (how far
 * along while it plays). Yours ([you]) sit on the right like your words; one not with the agent yet
 * says so under it, and one that failed says why, with Retry and Discard.
 */
@Composable
fun VoiceBubble(
    millis: Long,
    you: Boolean,
    playing: Boolean,
    positionMs: Long?,
    loading: Boolean,
    status: VoiceStatus,
    onPlay: () -> Unit,
    onRetry: () -> Unit = {},
    onDiscard: () -> Unit = {},
) {
    val c = Herdr.colors
    val total = millis.coerceAtLeast(1)
    val done = ((positionMs ?: 0) / total.toFloat()).coerceIn(0f, 1f)
    val failed = status as? VoiceStatus.Failed
    Row(
        Modifier.fillMaxWidth().padding(start = if (you) 48.dp else 0.dp, end = if (you) 0.dp else 48.dp),
        horizontalArrangement = if (you) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = c.card,
            contentColor = c.text,
            shape = BubbleShape,
            border = when {
                failed != null -> BorderStroke(1.dp, c.red.copy(alpha = 0.6f))
                c.bubbleBorder.alpha > 0f -> BorderStroke(1.dp, c.bubbleBorder)
                else -> null
            },
            modifier = Modifier.widthIn(min = 220.dp, max = 320.dp),
        ) {
            Column(Modifier.padding(start = Space.s, end = Space.l, top = Space.s, bottom = Space.s)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(RectangleShape).background(c.accent.copy(alpha = 0.16f))
                            .clickable(onClickLabel = if (playing) "Pause" else "Play", role = Role.Button, onClick = onPlay)
                            .semantics { contentDescription = if (playing) "Pause voice message" else "Play voice message" },
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            loading -> CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
                            playing -> Icon(HerdrIcons.Pause, null, tint = c.accent, modifier = Modifier.size(18.dp))
                            else -> Icon(HerdrIcons.Play, null, tint = c.accent, modifier = Modifier.size(18.dp).padding(start = 2.dp))
                        }
                    }
                    Spacer(Modifier.width(Space.m))
                    // Bars of a made-up shape, the same for the same length: it shows how far along, not the sound.
                    Row(Modifier.weight(1f).height(24.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        val bars = BUBBLE_BARS
                        repeat(bars) { i ->
                            val h = 0.3f + 0.7f * (((i * 37 + millis / 1000 * 11) % 17) / 16f)
                            val on = (i + 0.5f) / bars <= done
                            Box(Modifier.weight(1f).height(24.dp * h).background(if (on) c.accent else c.faint.copy(alpha = 0.5f), RectangleShape))
                        }
                    }
                    Spacer(Modifier.width(Space.m))
                    Text(
                        VoiceRecorder.elapsed(if (positionMs != null) positionMs else millis),
                        style = CodeStyle,
                        color = c.muted,
                        maxLines = 1,
                    )
                }
                val line = when (status) {
                    VoiceStatus.Sent -> null
                    VoiceStatus.Sending -> "Sending…"
                    VoiceStatus.Offline -> "Sends when the machine is back"
                    VoiceStatus.Behind -> "Waits for the one before it"
                    is VoiceStatus.Failed -> "Not sent: ${status.why}"
                }
                if (line != null) Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed != null) c.red else c.muted,
                    modifier = Modifier.padding(start = Space.xs, top = Space.xs).semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (failed != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    QuietButton(onClick = onDiscard) { Text("Discard") }
                    QuietButton(onClick = onRetry, color = c.accent) { Text("Retry") }
                }
            }
        }
    }
}

private const val BUBBLE_BARS = 28

/**
 * Over the composer when the mic can't be used: while Android asks for the microphone, why it is
 * needed; refused, that again with Allow, or Open settings once Android won't ask again; or what
 * went wrong while it recorded.
 */
@Composable
fun MicNotice(recorder: VoiceRecorder, state: VoiceRecorder.State) {
    val c = Herdr.colors
    val shape = RectangleShape
    val noMic = state as? VoiceRecorder.State.NoMic
    val asking = state == VoiceRecorder.State.Asking
    Surface(
        color = c.card,
        border = BorderStroke(1.dp, c.line),
        shape = shape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = Space.l, end = Space.s, top = Space.m, bottom = if (asking) Space.m else 0.dp)) {
            Row(Modifier.padding(end = Space.s).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Icon(
                    when {
                        asking -> HerdrIcons.Mic
                        noMic != null -> HerdrIcons.MicOff
                        else -> HerdrIcons.CircleAlert
                    },
                    null,
                    tint = when {
                        asking -> c.accent
                        noMic != null -> c.muted
                        else -> c.notice
                    },
                    modifier = Modifier.padding(top = 2.dp).size(16.dp),
                )
                Spacer(Modifier.width(Space.s))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                    if (asking) {
                        Text("Allow the microphone", style = MaterialTheme.typography.bodyLarge, color = c.strong)
                        Text(
                            "So you can talk to type, or send a voice message: tap the mic to have what you say written into the box, hold it to send the recording.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.muted,
                        )
                    } else if (noMic != null) {
                        Text("The microphone is off", style = MaterialTheme.typography.bodyLarge, color = c.strong)
                        Text(
                            if (noMic.forever) "Voice messages need it to record you. Allow it under Permissions in the phone's settings for herdr."
                            else "Voice messages need it to record you. The recording goes to the agent once you let go.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.muted,
                        )
                    } else {
                        Text((state as? VoiceRecorder.State.Failed)?.message.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = c.text)
                    }
                }
            }
            // While Android's own question is up, its buttons answer it.
            if (!asking) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                when {
                    noMic == null -> QuietButton(onClick = recorder::dismiss) { Text("OK") }
                    noMic.forever -> {
                        QuietButton(onClick = recorder::dismiss) { Text("Not now") }
                        QuietButton(onClick = recorder::settings, color = c.accent) { Text("Open settings") }
                    }
                    else -> {
                        QuietButton(onClick = recorder::dismiss) { Text("Not now") }
                        QuietButton(onClick = recorder::askAgain, color = c.accent) { Text("Allow") }
                    }
                }
            }
        }
    }
}
