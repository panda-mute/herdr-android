package com.makefunstuff.herdr.ui.design

import androidx.activity.compose.BackHandler
import com.makefunstuff.herdr.ui.theme.CodeStyle
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.RectangleShape
import com.makefunstuff.herdr.ui.theme.Mono
import com.makefunstuff.herdr.ui.theme.caps
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Radius
import com.makefunstuff.herdr.ui.theme.Space

/*
 * The app's shared components (docs/DESIGN.md), drawn as the Herdr desktop's workbench: flat
 * fills and 1dp frames, square corners, no shadows or gradients; module headings in mono capitals,
 * status as a square LED and its word, amber for your own action, focus and what is shown. Screens
 * build from these instead of stock Material ones, so spacing, shapes and colours come from one place.
 */

/** Every shape is square on the workbench; the names say what each one is for. */
object HerdrShapes {
    val Sheet = RectangleShape
    val Dialog = RectangleShape
    val Card = RectangleShape
    val Field = RectangleShape
    val Tile = RectangleShape
    val Badge = RectangleShape
    val Pill = RectangleShape
}

/** A 1dp frame, the edge of modules, fields, bars and buttons. */
@Composable
fun Modifier.hairline(shape: Shape, color: Color = Herdr.colors.line): Modifier =
    if (color.alpha == 0f) this else this.border(1.dp, color, shape)

/** Where a button sits in a dialog: a quiet button in the confirm slot draws as the primary pill. */
private enum class Slot { None, Confirm, Dismiss }
private val LocalSlot = compositionLocalOf { Slot.None }

// ---------------------------------------------------------------------------------------------
// Popups

@Composable
private fun Scrim(onDismiss: () -> Unit, align: Alignment, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Box(
        Modifier.fillMaxSize()
            .background(Herdr.colors.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = onDismiss),
        contentAlignment = align,
    ) {
        ToastHost()
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(150)) + slideInVertically(tween(200)) { it / 10 },
            exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { it / 10 },
        ) { content() }
    }
}

private val NoClick: Modifier
    @Composable get() = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}

private val FullScreen = DialogProperties(usePlatformDefaultWidth = false)

/** The colour sheets and dialogs sit on: `--bg-panel`, as the web UI's `.modal`. */
val sheetColor: Color @Composable get() = Herdr.colors.panel

/**
 * Centred dialog, only for confirmations and short forms: a title, a body, and its buttons at the
 * bottom right. A [QuietButton] in [confirmButton] draws as the primary pill.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HerdrDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = FullScreen) {
        Scrim(onDismissRequest, Alignment.Center) {
            Surface(
                color = sheetColor,
                shape = HerdrShapes.Dialog,
                modifier = Modifier.padding(Space.l).widthIn(max = 420.dp).fillMaxWidth().imePadding()
                    .hairline(HerdrShapes.Dialog, Herdr.colors.accent).then(NoClick),
            ) {
                Column(Modifier.padding(top = Space.xl, start = Space.xl, end = Space.xl, bottom = Space.l)) {
                    if (title != null) Box(Modifier.padding(bottom = Space.s).semantics { heading() }) {
                        CompositionLocalProvider(
                            LocalTextStyle provides MaterialTheme.typography.titleLarge,
                            LocalContentColor provides Herdr.colors.strong,
                        ) { title() }
                    }
                    if (text != null) Box(Modifier.weight(1f, fill = false).heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        CompositionLocalProvider(
                            LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                            LocalContentColor provides Herdr.colors.muted,
                        ) { text() }
                    }
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = Space.l),
                        horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        CompositionLocalProvider(LocalSlot provides Slot.Dismiss) { dismissButton?.invoke() }
                        CompositionLocalProvider(LocalSlot provides Slot.Confirm) { confirmButton() }
                    }
                }
            }
        }
    }
}

/** Bottom sheet with a drag handle; scrolls when long, and drags away to dismiss. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HerdrSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = HerdrShapes.Sheet,
        containerColor = sheetColor,
        contentColor = Herdr.colors.text,
        tonalElevation = 0.dp,
        scrimColor = Herdr.colors.scrim,
        dragHandle = {
            // The sheet's top edge is its amber frame; the handle a short dim bar under it.
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Herdr.colors.accent))
                Box(Modifier.padding(top = Space.m, bottom = Space.s).width(28.dp).height(3.dp).background(Herdr.colors.lineStrong))
            }
        },
    ) {
        Box {
            // Scrolls when longer than the screen. Callers with a list inside bound its height (heightIn(max)).
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Space.s), content = content)
            ToastHost(Modifier.matchParentSize())
        }
    }
}

/** A sheet's head (`.row-sheet-head`): 16sp strong title, optional dim line under it, on the sheet's 20dp margin. */
@Composable
fun SheetTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = Space.xl, end = Space.xl, top = Space.xs, bottom = Space.m)) {
        Text(
            title.caps(), style = MaterialTheme.typography.titleLarge, color = Herdr.colors.strong, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading(); contentDescription = title },
        )
        if (subtitle != null) Text(
            subtitle, style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A menu is an action sheet: a title, then big rows reachable with a thumb. Items are [HerdrMenuItem]s or [ActionRow]s. */
@Composable
fun HerdrMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    BackHandler(onBack = onDismissRequest)
    HerdrSheet(onDismissRequest) {
        if (title != null) SheetTitle(title, subtitle)
        Column(Modifier.padding(horizontal = Space.s), content = content)
    }
}

/** One row of a [HerdrMenu] (`.row-sheet-item`): a dim icon, the label, 48dp tall, the whole row the target, pressed on `--bg-hover`. */
@Composable
fun HerdrMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val c = Herdr.colors
    val tint = when {
        !enabled -> c.off
        destructive -> c.red
        else -> c.text
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(HerdrShapes.Field)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        if (leadingIcon != null) CompositionLocalProvider(LocalContentColor provides if (destructive || !enabled) tint else c.muted) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { leadingIcon() }
        }
        Box(Modifier.weight(1f)) {
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyLarge, LocalContentColor provides tint) { text() }
        }
    }
}

/** A quiet heading between groups of a menu's rows: the web UI's `.menu-heading`, a dim micro label in capitals. */
@Composable
fun MenuSection(text: String) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Herdr.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(start = Space.m, end = Space.m, top = Space.l, bottom = Space.xs).semantics { heading() },
    )
}

/** Convenience: a menu row from an icon and a label. */
@Composable
fun MenuRow(label: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true, destructive: Boolean = false) =
    HerdrMenuItem(text = { Text(label) }, onClick = onClick, leadingIcon = { Icon(icon, null, Modifier.size(20.dp)) }, enabled = enabled, destructive = destructive)

// ---------------------------------------------------------------------------------------------
// Messages and states

/** A styled error line with an optional retry; never a raw dump. */
@Composable
fun ErrorMessage(text: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    val c = Herdr.colors
    Surface(color = c.dangerTint, shape = HerdrShapes.Field, modifier = modifier.fillMaxWidth().hairline(HerdrShapes.Field, c.red)) {
        Row(
            Modifier.padding(start = Space.m, top = Space.s, bottom = Space.s, end = Space.xs).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HerdrIcons.CircleAlert, null, tint = c.red, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Space.m))
            Text(text.lineSequence().first().take(240), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.text)
            if (onRetry != null) QuietButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

/** A quiet notice: icon, sentence, on a tinted tile. [color] is a status colour; null is neutral. */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, color: Color? = null, icon: ImageVector = HerdrIcons.Info) {
    val c = Herdr.colors
    Surface(
        color = color?.let { c.tint(it) } ?: Color.Transparent, shape = HerdrShapes.Field,
        modifier = modifier.fillMaxWidth().hairline(HerdrShapes.Field, color ?: c.lineStrong),
    ) {
        Row(Modifier.padding(horizontal = Space.m, vertical = Space.m), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = color ?: c.muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Space.m))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
        }
    }
}

/** App-wide, styled replacement for system toasts. [com.makefunstuff.herdr.ui.common.toast] feeds it. */
object Toasts {
    class Msg(val text: String, val error: Boolean, val id: Long)
    var current by mutableStateOf<Msg?>(null)
        private set
    private var next = 0L
    private val errorWords = Regex("""(?i)\b(couldn't|can't|didn't|isn't|refused|failed|error|not |no app|unable)""")
    fun show(text: String, error: Boolean = errorWords.containsMatchIn(text)) {
        current = Msg(text, error, next++)
    }
    fun dismiss() { current = null }
}

/** Shows the current toast at the top of whatever it is placed in; dismisses itself. */
@Composable
fun ToastHost(modifier: Modifier = Modifier.fillMaxSize()) {
    val m = Toasts.current
    LaunchedEffect(m?.id) {
        if (m != null) { kotlinx.coroutines.delay(if (m.error) 5000 else 3000); if (Toasts.current?.id == m.id) Toasts.dismiss() }
    }
    Box(modifier.statusBarsPadding().padding(Space.m), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(m != null, enter = fadeIn(tween(150)) + slideInVertically(tween(200)) { -it }, exit = fadeOut(tween(150)) + slideOutVertically { -it }) {
            val last = remember { mutableStateOf(m) }
            if (m != null) last.value = m
            val msg = last.value
            val c = Herdr.colors
            // The desktop's toast: a panel framed in amber (red for an error).
            if (msg != null) Surface(
                color = c.panel,
                contentColor = c.strong,
                shape = HerdrShapes.Pill,
                modifier = Modifier.widthIn(max = 560.dp).hairline(HerdrShapes.Pill, if (msg.error) c.red else c.accent)
                    .clickable(onClickLabel = "Dismiss") { Toasts.dismiss() }
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Row(Modifier.padding(horizontal = Space.l, vertical = Space.m).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (msg.error) {
                        Icon(HerdrIcons.CircleAlert, null, tint = c.red, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Space.s))
                    }
                    Text(msg.text.lineSequence().first().take(200), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Centred progress with a spoken label. */
@Composable
fun LoadingState(label: String = "Loading", modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Spinner(Modifier.semantics { contentDescription = label })
        Text(label + "…", style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted)
    }
}

/** The one spinner: 24dp, thin, dim on a hairline track, square ends (waiting is not a state colour, nor amber). */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 24.dp) {
    CircularProgressIndicator(
        modifier.size(size), strokeWidth = 2.dp, color = Herdr.colors.muted, trackColor = Herdr.colors.line,
        strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt,
    )
}

/** Full-area error with what happened and a way out. */
@Composable
fun ErrorState(title: String, body: String?, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    StateBlock(HerdrIcons.CircleAlert, title, body?.lineSequence()?.first()?.take(240), modifier, iconTint = Herdr.colors.red) {
        if (onRetry != null) SecondaryButton(onClick = onRetry) { Text("Try again") }
    }
}

/** An empty, error or waiting state: an icon on a round tile, a title, a muted sentence, maybe a button. */
@Composable
fun StateBlock(
    icon: ImageVector,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    iconTint: Color? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val c = Herdr.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.xxl, vertical = Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(52.dp).background(c.ground).hairline(HerdrShapes.Tile, iconTint ?: c.lineStrong),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = iconTint ?: c.muted, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(Space.l))
        Text(
            title.caps(), style = MaterialTheme.typography.titleLarge, color = c.strong, textAlign = TextAlign.Center,
            modifier = Modifier.background(c.ground).padding(horizontal = Space.xs).semantics { heading(); contentDescription = title },
        )
        if (body != null) {
            Spacer(Modifier.height(Space.xs))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.background(c.ground).padding(horizontal = Space.xs))
        }
        if (action != null) {
            Spacer(Modifier.height(Space.l))
            action()
        }
    }
}

fun contrastRatio(a: Color, b: Color): Double {
    val x = a.luminance().toDouble() + 0.05; val y = b.luminance().toDouble() + 0.05
    return maxOf(x, y) / minOf(x, y)
}

/** [fg] nudged toward [towards] (usually onSurface) until it reads at 4.5:1 on [bg]; unchanged if it already does. */
fun readable(fg: Color, bg: Color, towards: Color): Color {
    var c = fg
    var t = 0f
    while (contrastRatio(c, bg) < 4.5 && t < 1f) { t += 0.05f; c = androidx.compose.ui.graphics.lerp(fg, towards, t) }
    return c
}

/** This status colour as text on the current ground (the palette is made to read; this is a safety net for tints). */
@Composable
fun Color.readableText(): Color = readable(this, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface)

// ---------------------------------------------------------------------------------------------
// Bars

/** Top-bar title: one ellipsised line, optional quieter second line. Keeps long names from pushing the bar open. */
@Composable
fun BarTitle(title: String, subtitle: String? = null) {
    Column {
        // A name that needs a second line gets one, as 3.1's task header does: a long title cut at the
        // first line hides which piece of work it is.
        Text(
            title, maxLines = if (subtitle == null) 1 else 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleLarge, color = Herdr.colors.strong,
        )
        if (subtitle != null) Text(
            subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted,
        )
    }
}

/** Opens the spaces drawer; given around the top-level tabs on a phone, where a bar's leading button opens it. */
val LocalBarDrawer = compositionLocalOf<(() -> Unit)?> { null }

/** A screen shown as a tab (the Chat tab's chat): its bar opens the drawer in place of its back arrow. */
val LocalInTab = compositionLocalOf { false }

/** What a tab adds after a screen's own bar actions (the Chat tab's ⋯ with the space's tabs). */
val LocalBarMore = compositionLocalOf<(@Composable RowScope.() -> Unit)?> { null }

/**
 * "Change this screen", given around the app's screens (none in a bubble): a long press on a bar's title opens it,
 * as the ⋯ menus' row does, and the bar shown last tells it its [title].
 */
class ScreenChange(val open: () -> Unit) {
    var title by mutableStateOf("")
}

val LocalScreenChange = compositionLocalOf<ScreenChange?> { null }

/**
 * The bar over a screen, as the web UI's phone header: `--bg-panel` with a hairline under it. With
 * [onBack] it is a detail screen's bar (back arrow, 16sp strong title, the place under it dim);
 * without, a top-level screen's (22sp display title), or with a [subtitle] a pane's (the detail
 * bar's title and place, no arrow). Actions are [BarIcon]s.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HerdrTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back",
    titleContent: (@Composable () -> Unit)? = null,
    /** Where a tap on the title and its line goes (a chat's: its space or task), said by [titleAction]. */
    onTitle: (() -> Unit)? = null,
    titleAction: String = "Open",
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Herdr.colors
    val drawer = LocalBarDrawer.current?.takeIf { onBack == null || LocalInTab.current }
    val more = LocalBarMore.current
    Row(
        modifier.fillMaxWidth().background(c.panel).drawBehind {
            drawLine(c.lineStrong, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
        }.statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(start = if (onBack == null && drawer == null) Space.screen else Space.xs, end = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (drawer != null) BarIcon(HerdrIcons.Menu, "Spaces", drawer)
        else if (onBack != null) BarIcon(HerdrIcons.ArrowLeft, backLabel, onBack)
        val change = LocalScreenChange.current
        if (change != null) SideEffect { change.title = title }
        Box(
            Modifier.weight(1f).padding(start = if (onBack != null || drawer != null) Space.xs else 0.dp, end = Space.s).then(
                if (change == null) Modifier
                else Modifier.pointerInput(change) { detectTapGestures(onLongPress = { change.open() }) }
                    .semantics { onLongClick("Change this screen") { change.open(); true } },
            ),
        ) {
            when {
                titleContent != null -> titleContent()
                onBack == null && subtitle == null -> Text(
                    title.caps(), style = MaterialTheme.typography.headlineMedium, color = c.strong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading(); contentDescription = title },
                )
                onTitle != null -> Box(
                    Modifier.fillMaxWidth().combinedClickable(
                        onClickLabel = titleAction, onClick = onTitle,
                        onLongClick = change?.let { ch -> { ch.open() } }, onLongClickLabel = change?.let { "Change this screen" },
                    ),
                ) { BarTitle(title, subtitle) }
                else -> BarTitle(title, subtitle)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        if (more != null) Row(verticalAlignment = Alignment.CenterVertically, content = more)
    }
}

/**
 * A 48dp icon button in a bar (`.icon-button`): a 20dp glyph in `--text`, pressed on `--bg-hover`, with an optional count badge.
 * [selected] (a setting it turns on, such as Keep screen on) draws it in amber on amber's tint, as a toggle.
 */
@Composable
fun BarIcon(icon: ImageVector, contentDescription: String, onClick: () -> Unit, badge: Int = 0, enabled: Boolean = true, selected: Boolean? = null) {
    val c = Herdr.colors
    Box(
        Modifier.size(48.dp)
            .then(if (selected == true) Modifier.background(c.accentTint) else Modifier)
            .then(
                if (selected == null) Modifier.clickable(enabled = enabled, onClickLabel = null, role = Role.Button, onClick = onClick)
                else Modifier.toggleable(value = selected, enabled = enabled, role = Role.Switch, onValueChange = { onClick() }),
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (!enabled) c.off else if (selected == true) c.accent else c.text, modifier = Modifier.size(20.dp))
        if (badge > 0) CountBadge(badge, Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = 4.dp))
    }
}

/**
 * The screen's main "add" action, where herdr-android has always put it: a floating pill at the
 * bottom right, in the user's own amber (`--primary`, ink text) with its label ("Add machine",
 * "New"), lifted by `--shadow-card`. [text] null draws the round icon-only one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HerdrFab(
    text: String?,
    contentDescription: String = text ?: "Add",
    icon: ImageVector = HerdrIcons.Plus,
    /** A second action on a long press, named [onLongClickLabel] for TalkBack. */
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    onClick: () -> Unit,
) {
    val c = Herdr.colors
    Surface(
        shape = HerdrShapes.Pill,
        color = c.primary,
        contentColor = c.onPrimary,
        modifier = Modifier.heightIn(min = 52.dp).hairline(HerdrShapes.Pill, c.ground),
    ) {
        Row(
            Modifier.clip(HerdrShapes.Pill)
                .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick, onLongClickLabel = onLongClickLabel)
                .semantics { this.contentDescription = contentDescription }
                .heightIn(min = 52.dp).padding(start = if (text == null) 14.dp else 16.dp, end = if (text == null) 14.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            if (text != null) Text(text.caps(), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

/** A small red count, for unread and needs-you. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    Box(
        modifier.defaultMinSize(minWidth = 16.dp, minHeight = 16.dp).background(c.red).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else "$count",
            style = TextStyle(fontFamily = Mono, fontSize = MaterialTheme.typography.labelSmall.fontSize, fontWeight = FontWeight.SemiBold),
            color = c.onPrimary,
            maxLines = 1,
        )
    }
}

/** The bar of primary actions a form keeps at the bottom, in thumb reach. */
@Composable
fun BottomActionBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val lineColor = Herdr.colors.lineStrong
    Row(
        modifier.fillMaxWidth().background(Herdr.colors.panel).drawBehind {
            drawLine(lineColor, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx())
        }.navigationBarsPadding().imePadding()
            .padding(horizontal = Space.screen, vertical = Space.m),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

// ---------------------------------------------------------------------------------------------
// Buttons: square, 48dp tall to tap, mono labels. Primary is your own amber with ink text;
// secondary a frame on the ground; quiet has no fill or edge; danger is the danger tint with a red
// frame and only confirms something destructive.

private val ButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

@Composable
private fun PillButton(
    onClick: () -> Unit,
    container: Color,
    content: Color,
    modifier: Modifier,
    enabled: Boolean,
    contentPadding: PaddingValues,
    edge: Color = Color.Transparent,
    body: @Composable RowScope.() -> Unit,
) {
    val c = Herdr.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = HerdrShapes.Field,
        colors = ButtonDefaults.buttonColors(
            containerColor = container, contentColor = content,
            disabledContainerColor = container, disabledContentColor = c.off,
        ),
        border = if (edge.alpha == 0f) null else androidx.compose.foundation.BorderStroke(1.dp, if (enabled) edge else c.line),
        elevation = null,
        contentPadding = contentPadding,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.labelLarge) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s), content = body)
        }
    }
}

@Composable
fun PrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, contentPadding: PaddingValues = ButtonPadding, content: @Composable RowScope.() -> Unit) {
    val c = Herdr.colors
    PillButton(
        onClick, if (enabled) c.primary else c.card, c.onPrimary, modifier, enabled, contentPadding,
        edge = if (enabled) Color.Transparent else c.line, body = content,
    )
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    PrimaryButton(onClick = onClick, modifier = modifier, enabled = enabled) { Text(text) }

@Composable
fun SecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, contentPadding: PaddingValues = ButtonPadding, content: @Composable RowScope.() -> Unit) =
    PillButton(onClick, Color.Transparent, Herdr.colors.strong, modifier, enabled, contentPadding, edge = Herdr.colors.lineStrong, body = content)

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    SecondaryButton(onClick = onClick, modifier = modifier, enabled = enabled) { Text(text) }

/**
 * A ghost button (`.btn-ghost`): no fill, no edge, `--text`. In a dialog's confirm slot it draws
 * as the primary button (the danger one when [color] is red). [color] overrides the text colour:
 * [Herdr.colors.accent] makes it a link.
 */
@Composable
fun QuietButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Color.Unspecified,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val c = Herdr.colors
    when (LocalSlot.current) {
        Slot.Confirm -> if (color == c.red || color == MaterialTheme.colorScheme.error) {
            PillButton(onClick, c.dangerTint, c.dangerText, modifier, enabled, ButtonPadding, edge = c.red, body = content)
        } else PrimaryButton(onClick, modifier, enabled, ButtonPadding, content)
        else -> PillButton(
            onClick, Color.Transparent, if (color == Color.Unspecified) c.text else color, modifier, enabled, contentPadding, body = content,
        )
    }
}

/** A destructive confirmation (`.btn-danger`): the danger tint, a red edge, the danger text. */
@Composable
fun DangerButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Herdr.colors
    PillButton(onClick, c.dangerTint, c.dangerText, modifier, enabled, ButtonPadding, edge = c.red) { Text(text) }
}

// ---------------------------------------------------------------------------------------------
// Chips, segments and status

/**
 * A filter or choice: a framed cell on the ground. Chosen, it is framed in amber with amber
 * text, as the desktop frames what is shown. 48dp target, 32dp drawn.
 */
@Composable
fun HerdrChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val c = Herdr.colors
    Box(
        modifier.heightIn(min = 48.dp).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Tab, onClick = onClick,
        ).semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.heightIn(min = 32.dp)
                .background(if (selected) c.accentTint else Color.Transparent)
                .hairline(HerdrShapes.Pill, if (selected) c.accent else c.lineStrong)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompositionLocalProvider(
                LocalContentColor provides when { !enabled -> c.off; selected -> c.accent; else -> c.text },
                LocalTextStyle provides MaterialTheme.typography.labelLarge,
            ) {
                if (leadingIcon != null) Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { leadingIcon() }
                label()
            }
        }
    }
}

@Composable
fun HerdrChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) =
    HerdrChip(selected, onClick, { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier)

/**
 * A row of mutually exclusive choices: one framed track, the chosen segment filled amber with ink
 * (as the desktop's shown space slot), the others dim.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SegmentedPills(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    // With large text the labels no longer fit one track: show them as chips that wrap instead.
    if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) {
        FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            options.forEachIndexed { i, label -> HerdrChip(label, selected = i == selected, onClick = { onSelect(i) }) }
        }
        return
    }
    Row(
        modifier.fillMaxWidth().hairline(HerdrShapes.Field, c.lineStrong).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp)
                    .background(if (on) c.primary else Color.Transparent)
                    .clickable(role = Role.Tab, onClick = { onSelect(i) })
                    .semantics { this.selected = on }
                    .padding(horizontal = Space.xs),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (on) c.onPrimary else c.muted,
                )
            }
        }
    }
}

/**
 * A state, as the desktop's pane header says it: a square LED and the word in mono capitals, in
 * the state's colour, flat. Idle and unknown are dim. The word is always there, so colour is never alone.
 */
@Composable
fun StatusBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    val quiet = color == c.idle || color == c.muted
    Row(
        modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Led(if (quiet) c.off else color, size = 6.dp)
        Text(text.caps(), style = MaterialTheme.typography.labelSmall, color = if (quiet) c.muted else color, maxLines = 1)
    }
}

/** Status as text: an LED and a dim label, for rows that already carry a lot. */
@Composable
fun StatusLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Led(color)
        Text(text, style = MaterialTheme.typography.labelMedium, color = Herdr.colors.muted, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// Fields

/**
 * A text field, as the web UI's `.field`: its label over it in dim capitals, then the box on
 * `--bg-input` with a hairline that turns amber while typed in, `--radius-md`, and a hint or
 * error under it. Same parameters as OutlinedTextField for the ones the app uses.
 */
@Composable
fun HerdrField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: String? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = HerdrShapes.Field,
) {
    val c = Herdr.colors
    Column(modifier) {
        if (label != null) FieldLabel(label, Modifier.padding(bottom = Space.xs).clearAndSetSemantics {})
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
            enabled = enabled, readOnly = readOnly, textStyle = textStyle.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize),
            placeholder = placeholder, leadingIcon = leadingIcon, trailingIcon = trailingIcon, isError = isError,
            visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            singleLine = singleLine, maxLines = maxLines, minLines = minLines, shape = shape,
            colors = fieldColors(),
        )
        if (supportingText != null) CompositionLocalProvider(
            LocalTextStyle provides MaterialTheme.typography.bodySmall,
            LocalContentColor provides if (isError) c.red else c.muted,
        ) { Box(Modifier.padding(top = Space.xs, start = Space.xxs)) { supportingText() } }
    }
}

@Composable
fun fieldColors() = Herdr.colors.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = c.input, unfocusedContainerColor = c.input, disabledContainerColor = c.card,
        errorContainerColor = c.input,
        focusedBorderColor = c.accent, unfocusedBorderColor = c.lineStrong, disabledBorderColor = c.line, errorBorderColor = c.red,
        focusedTextColor = c.strong, unfocusedTextColor = c.strong, disabledTextColor = c.muted, errorTextColor = c.strong,
        focusedLabelColor = c.muted, unfocusedLabelColor = c.muted, errorLabelColor = c.red,
        focusedSupportingTextColor = c.muted, unfocusedSupportingTextColor = c.muted, errorSupportingTextColor = c.red,
        focusedPlaceholderColor = c.muted, unfocusedPlaceholderColor = c.muted, disabledPlaceholderColor = c.off,
        cursorColor = c.accent, errorCursorColor = c.red,
        focusedTrailingIconColor = c.muted, unfocusedTrailingIconColor = c.muted,
        focusedLeadingIconColor = c.muted, unfocusedLeadingIconColor = c.muted,
        disabledLabelColor = c.off,
        selectionColors = androidx.compose.foundation.text.selection.TextSelectionColors(c.accent, c.accentTint),
    )
}

/** A form field with a label, and a hint or an error under it. */
@Composable
fun HerdrTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    hint: String? = null,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    HerdrField(
        value = value, onValueChange = onValueChange, label = label, singleLine = singleLine, readOnly = readOnly,
        isError = error != null,
        supportingText = (error ?: hint)?.let { { Text(it) } },
        keyboardOptions = keyboardOptions, visualTransformation = visualTransformation,
        modifier = modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------------------------------------
// Surfaces and rows

/** The label over a field or a group of choices (`.field-label`): 11sp, dim, in capitals ("AGENT", "WORKSPACE"). */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Herdr.colors.muted,
        modifier = modifier.padding(top = Space.xs).semantics { heading(); contentDescription = text },
    )
}

/**
 * A section's head, as the desktop's module headers: the name in mono capitals, then a 1dp rule
 * running to the edge (or to [trailing]). [color] marks a section that needs you.
 */
@Composable
fun SectionHeading(
    text: String,
    modifier: Modifier = Modifier,
    top: Dp = Space.xl,
    color: Color = Herdr.colors.muted,
    trailing: (@Composable () -> Unit)? = null,
) {
    val rule = Herdr.colors.line
    Row(
        modifier.fillMaxWidth().padding(top = top, bottom = Space.s).heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.caps(),
            style = MaterialTheme.typography.titleSmall,
            color = color,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading(); contentDescription = text },
        )
        Box(Modifier.weight(1f).padding(horizontal = Space.s).height(1.dp).background(rule))
        trailing?.invoke()
    }
}

/**
 * A module: a 1dp frame on the ground, flat, square, no shadow. Holds a group of rows (no padding)
 * or, with [padded], content. Never put one inside another: a module holds rows and sections.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuietCard(
    modifier: Modifier = Modifier,
    padded: Boolean = false,
    color: Color = Color.Transparent,
    edge: Color = Herdr.colors.lineStrong,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    /** What a long press does, as TalkBack says it ("double-tap and hold to …"). */
    onLongClickLabel: String? = "show more actions",
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(HerdrShapes.Card).background(color).hairline(HerdrShapes.Card, edge)
            .then(
                if (onClick != null || onLongClick != null) Modifier.combinedClickable(
                    onClickLabel = onClickLabel, onClick = onClick ?: {}, onLongClick = onLongClick,
                    onLongClickLabel = onLongClickLabel.takeIf { onLongClick != null },
                ) else Modifier,
            )
            .then(if (padded) Modifier.padding(Space.card) else Modifier),
        content = content,
    )
}

/**
 * The leading cell of a row: an icon or a letter in a square framed tile, or with [tint] on that
 * colour's tint. [dot] draws a status LED on its corner.
 */
@Composable
fun IconTile(
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    letter: String? = null,
    tint: Color? = null,
    background: Color? = null,
    dot: Color? = null,
    size: Dp = 40.dp,
) {
    val c = Herdr.colors
    Box(modifier.size(size)) {
        Box(
            Modifier.matchParentSize().background(background ?: tint?.let { c.tint(it) } ?: c.card).hairline(HerdrShapes.Tile, c.line),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) Icon(icon, null, tint = tint ?: c.text, modifier = Modifier.size(size / 2))
            else if (letter != null) Text(letter, style = MaterialTheme.typography.titleMedium, color = tint ?: c.text)
        }
        if (dot != null) Box(
            Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(11.dp)
                .background(LocalRowBackground.current.takeOrElse { c.ground }).padding(2.dp).background(dot),
        )
    }
}

/** What a row is drawn on, so a tile's status dot can cut itself out of it. */
val LocalRowBackground = compositionLocalOf { Color.Unspecified }

/**
 * A list row, on the web UI's sidebar grid: an optional leading cell, a title, up to two dim
 * lines, and trailing content or a chevron. At least 56dp and the whole row is the target.
 * Used on the ground (lists) and in cards (grouped settings), and by the sheets.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    meta: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    enabled: Boolean = true,
    titleColor: Color = Color.Unspecified,
    subtitleColor: Color = Color.Unspecified,
    subtitleLines: Int = 1,
    titleLines: Int = 1,
    below: (@Composable () -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = Herdr.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(
                if (onClick != null || onLongClick != null) Modifier.combinedClickable(
                    enabled = enabled, onClickLabel = onClickLabel, onClick = onClick ?: {}, onLongClick = onLongClick,
                    onLongClickLabel = "show more actions".takeIf { onLongClick != null },
                ) else Modifier,
            )
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Space.m))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, style = MaterialTheme.typography.titleMedium, maxLines = titleLines, overflow = TextOverflow.Ellipsis,
                    color = if (!enabled) c.off else if (titleColor == Color.Unspecified) c.text else titleColor,
                    modifier = Modifier.weight(1f),
                )
                if (meta != null) {
                    Spacer(Modifier.width(Space.s))
                    Text(meta, style = MaterialTheme.typography.labelMedium, color = c.muted, maxLines = 1)
                }
            }
            if (subtitle != null) Text(
                subtitle, style = MaterialTheme.typography.bodySmall, maxLines = subtitleLines, overflow = TextOverflow.Ellipsis,
                color = if (!enabled) c.off else if (subtitleColor == Color.Unspecified) c.muted else subtitleColor,
            )
            below?.invoke()
        }
        if (trailing != null) {
            Spacer(Modifier.width(Space.m))
            trailing()
        } else if (chevron) {
            Spacer(Modifier.width(Space.s))
            Icon(HerdrIcons.ChevronRight, null, tint = c.muted, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * One tappable action row: a dim 20dp icon, the label, an optional dim second line, an optional
 * chevron or trailing content. 52dp tall at the least; the whole row is the target; chosen, it sits
 * on the selected fill with an amber bar at its start, as the desktop's shown space.
 */
@Composable
fun ActionRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    chevron: Boolean = false,
    iconTint: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Herdr.colors
    val accent = when {
        !enabled -> c.off
        destructive -> c.red
        iconTint != null -> iconTint
        selected -> c.text
        else -> c.muted
    }
    val titleColor = when {
        !enabled -> c.off
        destructive -> c.red
        selected -> c.strong
        else -> c.text
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(HerdrShapes.Field)
            .then(if (selected) Modifier.background(c.selected).drawBehind { drawRect(c.accent, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) } else Modifier)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Space.m, vertical = Space.s),
    ) {
        if (icon != null) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(Space.m))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) c.muted else c.off,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(Space.m))
            trailing()
        } else if (selected) {
            Spacer(Modifier.width(Space.s))
            Icon(HerdrIcons.Check, "Selected", tint = c.accent, modifier = Modifier.size(18.dp))
        } else if (chevron) {
            Spacer(Modifier.width(Space.s))
            Icon(HerdrIcons.ChevronRight, null, tint = c.muted, modifier = Modifier.size(18.dp))
        }
    }
}

/** A setting that is on or off: the whole 56dp row toggles; a dim line under the label when it needs one. */
@Composable
fun SwitchRow(label: String, detail: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(HerdrShapes.Tile)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = if (enabled) Herdr.colors.strong else Herdr.colors.off)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted)
        }
        Spacer(Modifier.width(Space.m))
        HerdrSwitch(checked, enabled)
    }
}

/**
 * The workbench's switch, square: a framed track with a square knob; on, the track is amber with
 * an ink knob at its end. It draws only; the row around it toggles.
 */
@Composable
fun HerdrSwitch(checked: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    Box(
        modifier.size(width = 40.dp, height = 22.dp)
            .background(if (checked && enabled) c.primary else c.input)
            .hairline(HerdrShapes.Field, if (checked && enabled) c.primary else if (enabled) c.lineStrong else c.line)
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(16.dp).background(if (!enabled) c.off else if (checked) c.onPrimary else c.muted))
    }
}

/** Material's switch in the workbench's colours, for the few places that need its own (drag, semantics): amber on, framed off. */
@Composable
fun switchColors() = Herdr.colors.let { c ->
    androidx.compose.material3.SwitchDefaults.colors(
        checkedThumbColor = c.onPrimary, checkedTrackColor = c.primary,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = c.muted, uncheckedTrackColor = c.input, uncheckedBorderColor = c.lineStrong,
    )
}

/** A label/value line inside a card (Server: "Session  default"). */
@Composable
fun InfoLine(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted, modifier = Modifier.weight(1f))
        Text(
            value, style = if (mono) CodeStyle else MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A hairline between rows of one card, inset past the leading cell. */
@Composable
fun RowDivider(inset: Dp = Space.l) {
    Box(Modifier.fillMaxWidth().padding(start = inset).height(1.dp).background(Herdr.colors.line))
}

/** Code text (Geist Mono) on the ground, in a framed box: keys, paths, fingerprints. */
@Composable
fun MonoBlock(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        style = CodeStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize, lineHeight = MaterialTheme.typography.bodySmall.lineHeight),
        color = Herdr.colors.text,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().clip(HerdrShapes.Field).background(Herdr.colors.ground)
            .hairline(HerdrShapes.Field).padding(Space.m),
    )
}
