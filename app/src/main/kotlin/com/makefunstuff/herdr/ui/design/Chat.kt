package com.makefunstuff.herdr.ui.design

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.makefunstuff.herdr.ui.theme.Pretendard
import com.makefunstuff.herdr.core.chat.Completions
import com.makefunstuff.herdr.core.chat.VoiceRecorder
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.shadow
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Radius
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

/** Your chat turns (`.chat-turn-user`): `--radius-lg` on all four corners. */
val BubbleShape = RoundedCornerShape(12.dp)
/** The Coordinator's replies, in the same shape. */
val ReplyBubbleShape = RoundedCornerShape(12.dp)

/**
 * The message composer at the bottom of a chat, the web UI's input card (`.composer-surface`):
 * one card on `--bg-elevated` with a hairline that turns amber while you type, `--radius-2xl`,
 * `--shadow-card`, no Material underline or inner outline. Inside it the message box is a row of
 * its own at the card's full width, growing from one line to [maxLines] (and never past 40% of the
 * room it has, so a keyboard and a large font still leave the transcript in sight) and then
 * scrolling inside. Under it ONE row of controls: [leading] on the left (both chats put the
 * attach button there, [ComposerIconButton] with Lucide `Plus`), then on the right the [status]
 * text, [trailing] (the Coordinator's "Start agents") and the one round Send/Stop button.
 * [holdSend] keeps Send off without touching the text (a file still being copied). [attachments]
 * goes inside the card over the message box (the pictures going with the message); with
 * [attached], Send works with no words typed.
 *
 * Send is the amber circle with an up arrow, faded and off while the draft is empty; Stop (while
 * the agent works, nothing is typed and the screen can [stop] it) is the same circle in strong text
 * with a square. While [sending] the button holds a progress ring and the draft stays in the box,
 * dimmed, until the screen clears it (sent; see [com.makefunstuff.herdr.data.DraftStore.afterSend]) or it is yours again (failed).
 *
 * Enter makes a new line; Ctrl+Enter (or ⌘+Enter) on a hardware keyboard sends. A send gives a
 * light haptic. [floating] draws the composer for a place over the transcript's end (see
 * [ChatSurface]): the region over the card fades the transcript into the ground. [above] goes over
 * the card, inside the composer (a blocked agent's question, the agent's key row), and never takes
 * more than about 60% of the room: what it holds scrolls inside. [focusRequester] lets the screen put
 * the cursor in the box (a reply the question asks for).
 *
 * [completion] draws what the word at the cursor completes to, over all of that: a `/command` at
 * the start of the message or an `@path` ([Completions.at]); what it accepts replaces the word,
 * with a space after it, and the cursor goes there.
 *
 * [voice] puts the mic beside Send ([ComposerMic]): while it records, the box gives way to how loud
 * you are ([RecordingLevels]) and the left of the controls to the time and how to cancel
 * ([RecordingInfo]); Send then sends the recording, as letting go of the mic does. The draft stays as
 * it was. A refused microphone, or a recording that failed, says so over the card ([MicNotice]).
 */
@Composable
fun ChatComposer(
    draft: String,
    onDraft: (String) -> Unit,
    placeholder: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    sending: Boolean = false,
    stop: (() -> Unit)? = null,
    status: String? = null,
    holdSend: Boolean = false,
    sendLabel: String = "Send",
    maxLines: Int = 8,
    floating: Boolean = false,
    above: (@Composable () -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    attachments: (@Composable () -> Unit)? = null,
    attached: Boolean = false,
    focusRequester: FocusRequester? = null,
    completion: (@Composable (query: Completions.Query, accept: (String) -> Unit) -> Unit)? = null,
    voice: VoiceRecorder? = null,
) {
    val c = Herdr.colors
    val view = LocalView.current
    val focus = remember { MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()
    val ownFocus = remember { FocusRequester() }
    val focusRequester = focusRequester ?: ownFocus
    val keyboard = LocalSoftwareKeyboardController.current
    val voiceState = voice?.let { it.state.collectAsState().value } ?: VoiceRecorder.State.Idle
    val hearing = voiceState.hearing
    val canSend = enabled && !sending && !holdSend && !hearing && (draft.isNotBlank() || attached)
    val showStop = stop != null && draft.isBlank() && !attached && !sending && !hearing
    val card = RoundedCornerShape(Radius.xxl)
    val fade = Space.xxl

    fun send() {
        // While it records, Send sends the recording, as letting go of the mic does.
        if (hearing) { view.confirmTap(); voice?.stop(); return }
        if (!canSend) return
        // A light confirmation under the thumb, as a keyboard's own send key gives.
        view.confirmTap()
        onSend()
    }

    // The text with its cursor and selection; the draft itself is the screen's (saved per chat).
    // Opened with a draft, the cursor stands at its end.
    var field by remember { mutableStateOf(TextFieldValue(draft, TextRange(draft.length))) }
    // A draft changed from outside (an attached file's mention put in front, a sent message taken
    // out): the cursor keeps its place in the words you typed, or goes to the end.
    val value = if (field.text == draft) field else {
        val grew = draft.length - field.text.length
        val cursor = if (grew > 0 && draft.endsWith(field.text)) field.selection.end + grew else draft.length
        TextFieldValue(draft, TextRange(cursor.coerceIn(0, draft.length)))
    }
    SideEffect { if (value != field) field = value }
    // What went wrong while it recorded is said for a while, then goes by itself.
    LaunchedEffect(voiceState) {
        if (voiceState is VoiceRecorder.State.Failed) {
            delay(FAILED_SHOWN_MS)
            voice?.dismiss()
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        val room = if (constraints.hasBoundedHeight) maxHeight else Dp.Infinity
        // A short window (a phone on its side with the keyboard up) keeps the card whole: no fade over
        // it, and the box gives up lines before the controls do.
        val short = room < 240.dp
        val glide = floating && !short
        val textMax = when {
            room == Dp.Infinity -> 320.dp
            short -> (room - 72.dp).coerceAtLeast(48.dp)
            else -> (room * 0.4f).coerceAtLeast(72.dp)
        }
        Column(
            Modifier.fillMaxWidth()
                // Never clipped by its slot: on a short screen it rises over what is above it.
                .wrapContentHeight(Alignment.Bottom, unbounded = true)
                .background(
                    if (glide) Brush.verticalGradient(0f to Color.Transparent, 1f to c.ground, endY = with(LocalDensity.current) { (fade + Space.m).toPx() })
                    else SolidColor(c.ground),
                )
                .padding(start = Space.m, end = Space.m, top = if (glide) fade else Space.s, bottom = Space.s),
        ) {
            val query = if (completion != null && enabled) Completions.at(value.text, value.selection.min, value.selection.max) else null
            if (query != null) {
                Box(Modifier.padding(bottom = Space.s).then(if (room != Dp.Infinity) Modifier.heightIn(max = room * 0.45f) else Modifier)) {
                    completion?.invoke(query) { replacement ->
                        val (text, cursor) = Completions.accept(value.text, query, replacement)
                        field = TextFieldValue(text, TextRange(cursor))
                        onDraft(text)
                        focusRequester.requestFocus()
                    }
                }
            }
            if (above != null) {
                Box(Modifier.padding(bottom = Space.s).then(if (room != Dp.Infinity) Modifier.heightIn(max = room * 0.6f) else Modifier)) { above() }
            }
            if (voice != null && (voiceState is VoiceRecorder.State.NoMic || voiceState is VoiceRecorder.State.Failed || voiceState == VoiceRecorder.State.Asking)) {
                Box(Modifier.padding(bottom = Space.s)) { MicNotice(voice, voiceState) }
            }
            Column(
                Modifier.fillMaxWidth()
                    .shadow(6.dp, card, ambientColor = c.shadow, spotColor = c.shadow)
                    .clip(card).background(c.card).border(1.dp, if (focused && enabled) c.accent else c.line, card)
                    // A tap anywhere on the card that is not a control puts the cursor in the box.
                    .pointerInput(enabled, hearing) {
                        detectTapGestures {
                            if (enabled && !hearing) { focusRequester.requestFocus(); keyboard?.show() }
                        }
                    },
            ) {
                attachments?.invoke()
                if (hearing) RecordingLevels(voiceState)
                if (!hearing) CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(c.accent, c.accent.copy(alpha = 0.32f))) {
                    BasicTextField(
                        value = value,
                        onValueChange = { field = it; if (it.text != draft) onDraft(it.text) },
                        // Not read-only while sending: that would put the keyboard away and bring it back.
                        enabled = enabled,
                        textStyle = ComposerTextStyle.copy(color = if (sending || !enabled) c.muted else c.strong),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        minLines = 1,
                        maxLines = maxLines,
                        interactionSource = focus,
                        modifier = Modifier.fillMaxWidth().heightIn(max = textMax)
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { e ->
                                // A hardware keyboard: Ctrl/⌘+Enter sends, Enter stays a new line.
                                val enter = e.key == Key.Enter || e.key == Key.NumPadEnter
                                if (enter && (e.isCtrlPressed || e.isMetaPressed)) {
                                    if (e.type == KeyEventType.KeyDown) send()
                                    true
                                } else false
                            }
                            .semantics { if (sending) stateDescription = "Sending" },
                        decorationBox = { inner ->
                            Box(Modifier.padding(start = Space.l, end = Space.l, top = Space.m + Space.xxs, bottom = Space.xs)) {
                                if (draft.isEmpty()) {
                                    Text(
                                        placeholder,
                                        style = ComposerTextStyle,
                                        color = if (enabled) c.muted else c.off,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = Space.xs, end = Space.xxs, bottom = Space.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (voice != null && hearing) RecordingInfo(voice, voiceState)
                    // The controls on the left take what the buttons on the right leave, and shrink to fit it.
                    else Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { leading?.invoke(this) }
                    if (status != null && !hearing) {
                        Text(
                            status,
                            style = MaterialTheme.typography.labelMedium,
                            color = c.muted,
                            textAlign = TextAlign.End,
                            // One line: squeezed by a long model name, wrapped words made the row (and the card) tall.
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).padding(horizontal = Space.s)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    // While it hears, the row is the recording's (its time and what letting go does).
                    if (!hearing) trailing?.invoke(this)
                    // Always in this place, so a finger holding it through every state keeps it.
                    if (voice != null) ComposerMic(voice, voiceState, enabled = enabled)
                    when {
                        showStop -> ComposerAction(ComposerActionKind.Stop, "Stop", enabled = enabled, onClick = { stop?.invoke() })
                        hearing -> ComposerAction(ComposerActionKind.Send, "Send voice message", enabled = true, onClick = ::send)
                        sending -> ComposerAction(ComposerActionKind.Sending, "Sending…", enabled = false, onClick = {})
                        else -> ComposerAction(ComposerActionKind.Send, sendLabel, enabled = canSend, onClick = ::send)
                    }
                }
            }
        }
    }
}

/** How long the composer says what went wrong with a voice message before it goes by itself. */
private const val FAILED_SHOWN_MS = 8_000L

/** The message's type: `--fs-input` (16sp, the web UI's touch size) on `--lh-base`. */
private val ComposerTextStyle = TextStyle(fontFamily = Pretendard, fontSize = 16.sp, lineHeight = 24.sp)

private enum class ComposerActionKind { Send, Stop, Sending }

/**
 * The composer's one round button (`.composer-action`): a compact 32dp circle in a 48dp target, the
 * same place and size for Send, Stop and an in-flight send, so only the glyph changes.
 */
@Composable
private fun ComposerAction(kind: ComposerActionKind, description: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Herdr.colors
    val fill = if (kind == ComposerActionKind.Stop) c.strong else c.primary
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(32.dp)
                // Send with nothing to send: the amber circle, faded (the web UI's disabled opacity).
                .alpha(if (kind == ComposerActionKind.Send && !enabled) 0.4f else 1f)
                .background(fill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when (kind) {
                ComposerActionKind.Send -> Icon(HerdrIcons.ArrowUp, null, tint = c.onPrimary, modifier = Modifier.size(16.dp))
                ComposerActionKind.Stop -> Box(Modifier.size(10.dp).background(c.ground, RoundedCornerShape(2.dp)))
                ComposerActionKind.Sending -> CircularProgressIndicator(
                    Modifier.size(14.dp), color = c.onPrimary, strokeWidth = 2.dp, trackColor = c.onPrimary.copy(alpha = 0.25f),
                )
            }
        }
    }
}

/**
 * An icon button in the composer's row of controls (`.composer-controls .icon-button`): a 20dp
 * glyph in `--text-dim` in a 48dp round target, `--bg-hover` while pressed; `off` when disabled.
 * The attach button (Lucide `Plus`, "Attach files").
 */
@Composable
fun ComposerIconButton(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Herdr.colors
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) c.muted else c.off, modifier = Modifier.size(20.dp))
    }
}

/**
 * A switch in the composer's row of controls, beside Send: a compact pill in a 48dp target. Off it
 * is quiet (a hairline and dim text); on it takes the accent (tint, edge, glyph and words), so it
 * is plain which way Send will go. The Coordinator's "Start agents" (the board's Tab).
 */
@Composable
fun ComposerToggle(label: String, icon: ImageVector, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = Herdr.colors
    val pill = RoundedCornerShape(50)
    val tone = when {
        !enabled -> c.off
        checked -> c.accent
        else -> c.muted
    }
    Box(
        Modifier.heightIn(min = 48.dp).clip(pill)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.heightIn(min = 32.dp).clip(pill)
                .background(if (checked) c.accentTint else Color.Transparent)
                .border(1.dp, if (checked && enabled) c.accent else c.line, pill)
                .padding(horizontal = Space.m - Space.xxs, vertical = Space.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = tone, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Space.xs + Space.xxs))
            Text(label, style = MaterialTheme.typography.labelLarge, color = tone, maxLines = 1)
        }
    }
}

/**
 * A phone on its side with the keyboard up: room for the composer and little else, so a chat
 * screen puts its bars aside while you type (they come back with the keyboard's going).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun typingInShortWindow(): Boolean = WindowInsets.isImeVisible && LocalConfiguration.current.screenHeightDp < 520

/**
 * A chat screen's body as the web UI lays it out on a phone: the transcript fills the space and
 * the [composer] floats over its end, so the transcript's last lines fade behind the card. The
 * [transcript] gets the composer's measured height to leave free under its last line (as bottom
 * content padding), so the newest message is never under the card, and a reversed list stays at
 * its end while the box grows or the keyboard opens. [textScale] is the chats' own text size over
 * the phone's (Settings → Text size), after the phone's own font scaling ([withTextScale]): the
 * messages and the composer grow with it, the bars don't.
 */
@Composable
fun ChatSurface(
    modifier: Modifier = Modifier,
    textScale: Float = 1f,
    composer: @Composable () -> Unit,
    transcript: @Composable BoxScope.(bottom: Dp) -> Unit,
) {
    var height by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides density.withTextScale(textScale)) {
        Box(modifier) {
            transcript(with(density) { height.toDp() })
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { height = it.height }) { composer() }
        }
    }
}

/**
 * The web UI's round button (`.composer-action`): a 36dp circle in a 48dp target. Primary (Send,
 * Run) is amber with ink; [stop] is strong text with a filled square; [primary] false is the
 * neutral one that floats over content ("Newest"), `--bg-elevated` with a hairline and a shadow.
 */
@Composable
fun RoundAction(icon: ImageVector, description: String, enabled: Boolean = true, primary: Boolean = true, stop: Boolean = false, onClick: () -> Unit) {
    val c = Herdr.colors
    val (fill, glyph) = when {
        !enabled -> c.card to c.off
        stop -> c.strong to c.ground
        primary -> c.primary to c.onPrimary
        else -> c.card to c.text
    }
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(36.dp)
                .then(if (!primary && !stop) Modifier.shadow(8.dp, CircleShape) else Modifier)
                .background(fill, CircleShape)
                .then(if (!enabled || (!primary && !stop)) Modifier.border(1.dp, c.line, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (stop) Box(Modifier.size(12.dp).background(glyph, RoundedCornerShape(2.dp)))
            else Icon(icon, null, tint = glyph, modifier = Modifier.size(18.dp))
        }
    }
}

/** A key the agent's screen understands (Enter, Esc, 1…): a keycap (`.kbd`): mono, a strong edge, 48dp tall to tap. */
@Composable
fun KeyChip(label: String, onClick: () -> Unit) {
    val c = Herdr.colors
    val shape = RoundedCornerShape(Radius.m)
    Box(
        Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = CodeStyle,
            color = c.text,
            modifier = Modifier.clip(shape).background(c.card).border(1.dp, c.lineStrong, shape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
