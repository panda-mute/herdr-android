package com.makefunstuff.herdr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.makefunstuff.herdr.R
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.board.BoardTone
import com.makefunstuff.herdr.core.code.Role
import com.makefunstuff.herdr.core.terminal.TerminalPalette

/*
 * The app's look, as docs/DESIGN.md describes it: the Herdr desktop's `workbench` layout (an
 * engineering-puzzle-game console), carried over to a phone. Dark is its theme value for value
 * (herdr-app/themes/workbench.json): near-black panels, amber frames and accent, the theme's status
 * colours. Light is the same language on paper: paper panels with dark-amber frames. Flat: frames
 * and flat fills only, square corners, no shadows or gradients. Screens read these through
 * MaterialTheme and [Herdr]; nothing else defines a colour.
 */

/** The tokens, named as the app's screens use them; each one's workbench theme key is beside it. */
@Immutable
class HerdrColors(
    /** `background`: the screen's ground, the chat canvas, the terminal. */
    val ground: Color,
    /** `chrome`: the bars, the nav bar, the drawer, sheets and dialogs. */
    val panel: Color,
    /** `surface`: chips, secondary buttons, tracks, your chat turns, a module's header strip. */
    val card: Color,
    /** `hover`, a step darker in dark so the blocked red keeps 4.5:1 on it: a pressed row, chip or key; icon tiles. */
    val hover: Color,
    /** `input`: text fields. */
    val input: Color,
    /** A hairline between rows, a field's edge: quieter than [lineStrong]. */
    val line: Color,
    /** `border`: a module's frame, a keycap, an unchecked switch. */
    val lineStrong: Color,
    /** Amber at half strength: the frame of a module (a card, a sheet, a panel). [accent] frames the one in focus. */
    val frame: Color,
    /** `selected`: the chosen row (the open space, the chosen segment). */
    val selected: Color,
    /** `foreground`: body and row titles. */
    val text: Color,
    /** Titles, a chosen segment, emphasis. */
    val strong: Color,
    /** `muted`: secondary lines, icons, module headings, meta. */
    val muted: Color,
    /** `dim`: disabled controls, a resting LED, pins. Not for text that must be read. */
    val off: Color,
    /** `accent`: amber that reads as text (links, the chosen tab, focus, the cursor) and frames what is in focus. */
    val accent: Color,
    /** `accent`, as a fill: your own action (Send, primary buttons, the + button). */
    val primary: Color,
    /** `accent_foreground`: ink on [primary]. */
    val onPrimary: Color,
    /** `blocked`: needs you, blocked, failed, destructive. */
    val red: Color,
    /** `working`: an agent or a task at work. Amber, as on the desktop: work is the console's own colour. */
    val working: Color,
    /** `done`: finished, connected, passed, ready to accept. */
    val done: Color,
    /** `idle`, as text: idle, to do, stopped, unknown. */
    val idle: Color,
    /** The runtimes' chip ink (bun, node, python): `ansi6`, teal. */
    val teal: Color,
    /** A warning that something is partial or stale (offline, outdated, minor): `ansi11`. */
    val notice: Color,
    /** Text on the danger tint (the destructive button). */
    val dangerText: Color,
    /** Under sheets and dialogs. */
    val scrim: Color,
    /** The edge of your chat turns. */
    val bubbleBorder: Color,
    /** `selection`: the terminal's selected cells. */
    val termSelection: Color,
    /** The circuit board behind empty screens: its traces, buses, pads and parts (quiet, under any text's contrast). */
    val circuit: List<Color>,
    /** The terminal's 16 ANSI colours: the theme's `ansi`. */
    val ansi: List<Color>,
    val dark: Boolean,
) {
    /** [card] under its old name: chips, tiles and secondary buttons. */
    val field: Color get() = card
    /** [hover] under its old name: a focused field, a pressed key, a sheet's handle. */
    val fieldFocused: Color get() = hover
    /** Tertiary text: the theme has three text strengths, so meta is [muted] too. */
    val faint: Color get() = muted

    /** A state's quiet background: 14% in dark, 12% in light. */
    fun tint(c: Color): Color = c.copy(alpha = if (dark) 0.14f else 0.12f)

    /** A chosen pick, a marked line. */
    val accentTint: Color get() = accent.copy(alpha = if (dark) 0.13f else 0.10f)

    /** No shadows on the workbench: anything that floats is framed instead. Kept transparent for the few callers. */
    val shadow: Color get() = Color.Transparent

    /** The destructive button's fill. */
    val dangerTint: Color get() = red.copy(alpha = if (dark) 0.12f else 0.10f)

    /**
     * A chart's two series (a machine's RAM and CPU): blue and orange, checked for colour-blind
     * separation and 3:1 on `card` in both themes. Not states, and not amber.
     */
    val chart1: Color get() = if (dark) Color(0xFF4AA3D8) else Color(0xFF2A78D6)
    val chart2: Color get() = Color(0xFFD95926)

    /** Code's colours: comments dim, functions amber, inserted and deleted the done and blocked states, and six of their own. */
    fun syntax(role: Role): Color = when (role) {
        Role.Keyword -> if (dark) Color(0xFFFF7A6B) else Color(0xFF953414)
        Role.Str -> if (dark) Color(0xFFA6E08F) else Color(0xFF365E14)
        Role.Number -> if (dark) Color(0xFFCFA0F0) else Color(0xFF7A3F8A)
        Role.Type -> if (dark) Color(0xFF7FC4F0) else Color(0xFF155A72)
        Role.Variable -> if (dark) Color(0xFF8BE8DE) else Color(0xFF0E615A)
        Role.Meta -> if (dark) Color(0xFFB07AD8) else Color(0xFF5D4791)
        Role.Comment -> muted
        Role.Function -> accent
        Role.Inserted -> done
        Role.Deleted -> red
    }

    /** A diff's added or removed line: the state's tint, a little lighter, so even a dim comment reads on it. */
    fun lineTint(c: Color): Color = c.copy(alpha = 0.10f)

    /** The code viewer's line numbers, quieter than the code beside them. */
    val lineNumber: Color get() = muted
}

private fun ansi(vararg v: Long) = v.map { Color(it) }

/** herdr-app/themes/workbench.json, the `ansi` list: the terminal's colours in both themes (the light one holds them at 4.5:1). */
private val WORKBENCH_ANSI = ansi(
    0xFF0D0F10, 0xFFE0574A, 0xFF7FC46A, 0xFFFFB000, 0xFF4AA3D8, 0xFFB07AD8, 0xFF4FD1C5, 0xFFC9CFCC,
    0xFF4A5257, 0xFFFF7A6B, 0xFFA6E08F, 0xFFFFCC4D, 0xFF7FC4F0, 0xFFCFA0F0, 0xFF8BE8DE, 0xFFFFFFFF,
)

internal val DarkHerdr = HerdrColors(
    ground = Color(0xFF0D0F10), panel = Color(0xFF0A0D0C), card = Color(0xFF1B1F21), hover = Color(0xFF252B2E),
    input = Color(0xFF090A0B), line = Color(0xFF283033), lineStrong = Color(0xFF4E5B57), frame = Color(0xFF7A5809),
    selected = Color(0xFF1E3A40),
    text = Color(0xFFDFE4E1), strong = Color(0xFFF4F6F5), muted = Color(0xFF8FA3A8), off = Color(0xFF66727A),
    accent = Color(0xFFFFB000), primary = Color(0xFFFFB000), onPrimary = Color(0xFF0B0D0E),
    red = Color(0xFFFF5A4A), working = Color(0xFFFFB000), done = Color(0xFF5FD38A), idle = Color(0xFF8FA3A8),
    teal = Color(0xFF4FD1C5), notice = Color(0xFFFFCC4D), dangerText = Color(0xFFFFD3CE),
    scrim = Color(0x99050606), bubbleBorder = Color(0xFF4E5B57), termSelection = Color(0xFF23404A),
    // backgrounds/workbench.svg's own layers: traces, buses, pads, parts.
    circuit = listOf(Color(0xFF122019), Color(0xFF1A2A21), Color(0xFF1D2F25), Color(0xFF2E2A1A)),
    ansi = WORKBENCH_ANSI,
    dark = true,
)

internal val LightHerdr = HerdrColors(
    ground = Color(0xFFF1EEE6), panel = Color(0xFFECE8DE), card = Color(0xFFFBFAF6), hover = Color(0xFFE4E0D4),
    input = Color(0xFFFFFFFF), line = Color(0xFFD5CFC0), lineStrong = Color(0xFF8E897C), frame = Color(0xFFB98A3A),
    selected = Color(0xFFD9E6E3),
    text = Color(0xFF1E2224), strong = Color(0xFF0B0D0E), muted = Color(0xFF4F5B60), off = Color(0xFF9AA2A3),
    accent = Color(0xFF7A4C00), primary = Color(0xFFFFB000), onPrimary = Color(0xFF0B0D0E),
    red = Color(0xFFA3211A), working = Color(0xFF7A4C00), done = Color(0xFF17623A), idle = Color(0xFF4F5B60),
    teal = Color(0xFF0E7A70), notice = Color(0xFF6E5600), dangerText = Color(0xFF8F1D1D),
    scrim = Color(0x59281F16), bubbleBorder = Color(0xFF8E897C), termSelection = Color(0xFFF0D9AE),
    circuit = listOf(Color(0xFFE6E2D6), Color(0xFFE0DBCD), Color(0xFFDBD5C5), Color(0xFFE3D6B6)),
    ansi = WORKBENCH_ANSI,
    dark = false,
)

val LocalHerdrColors = staticCompositionLocalOf { DarkHerdr }

/** The theme's extra tokens: `Herdr.colors.card`, `Herdr.colors.red`… */
object Herdr {
    val colors: HerdrColors
        @Composable @ReadOnlyComposable get() = LocalHerdrColors.current
}

/** The 4dp grid. Screens space with these, not with their own numbers. */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    /** A screen's side margin. */
    val screen = 16.dp
    /** Inside a module. */
    val card = 12.dp
}

/** Corners. The workbench has none: every one of these is square, kept by name so a screen says what it draws. */
object Radius {
    val s = 0.dp
    val m = 0.dp
    val l = 0.dp
    val xl = 0.dp
    val xxl = 0.dp
}

/** Frames: a module's 1dp edge; [focus], the amber edge of what is in focus. */
object Frame {
    val width = 1.dp
    val focus = 1.dp
}

/** Geist (OFL), the workbench's companion face for reading: body text, chat prose, row titles. */
val Sans = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_semibold, FontWeight.Bold),
)

/** Geist Mono (OFL), the workbench's interface face: headers, labels, buttons, code, paths, keys, the terminal. */
val Mono = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold),
    Font(R.font.geist_mono_semibold, FontWeight.Bold),
)

internal val Dark = darkColorScheme(
    primary = DarkHerdr.primary, onPrimary = DarkHerdr.onPrimary,
    primaryContainer = DarkHerdr.hover, onPrimaryContainer = DarkHerdr.strong,
    inversePrimary = LightHerdr.accent,
    secondary = DarkHerdr.muted, onSecondary = DarkHerdr.ground,
    secondaryContainer = DarkHerdr.card, onSecondaryContainer = DarkHerdr.text,
    tertiary = DarkHerdr.accent, onTertiary = DarkHerdr.onPrimary,
    tertiaryContainer = DarkHerdr.hover, onTertiaryContainer = DarkHerdr.strong,
    background = DarkHerdr.ground, onBackground = DarkHerdr.text,
    surface = DarkHerdr.ground, onSurface = DarkHerdr.text,
    surfaceVariant = DarkHerdr.card, onSurfaceVariant = DarkHerdr.muted,
    surfaceTint = Color.Transparent,
    inverseSurface = DarkHerdr.strong, inverseOnSurface = DarkHerdr.ground,
    error = DarkHerdr.red, onError = DarkHerdr.ground,
    errorContainer = Color(0xFF3A1D19), onErrorContainer = DarkHerdr.dangerText,
    outline = DarkHerdr.lineStrong, outlineVariant = DarkHerdr.line,
    scrim = Color(0xFF080604),
    surfaceBright = DarkHerdr.hover, surfaceDim = DarkHerdr.ground,
    surfaceContainerLowest = DarkHerdr.ground, surfaceContainerLow = DarkHerdr.panel,
    surfaceContainer = DarkHerdr.panel, surfaceContainerHigh = DarkHerdr.card,
    surfaceContainerHighest = DarkHerdr.hover,
)

internal val Light = lightColorScheme(
    primary = LightHerdr.primary, onPrimary = LightHerdr.onPrimary,
    primaryContainer = LightHerdr.hover, onPrimaryContainer = LightHerdr.strong,
    inversePrimary = DarkHerdr.accent,
    secondary = LightHerdr.muted, onSecondary = LightHerdr.panel,
    secondaryContainer = LightHerdr.card, onSecondaryContainer = LightHerdr.text,
    tertiary = LightHerdr.accent, onTertiary = LightHerdr.panel,
    tertiaryContainer = LightHerdr.hover, onTertiaryContainer = LightHerdr.strong,
    background = LightHerdr.ground, onBackground = LightHerdr.text,
    surface = LightHerdr.ground, onSurface = LightHerdr.text,
    surfaceVariant = LightHerdr.card, onSurfaceVariant = LightHerdr.muted,
    surfaceTint = Color.Transparent,
    inverseSurface = LightHerdr.strong, inverseOnSurface = LightHerdr.panel,
    error = LightHerdr.red, onError = LightHerdr.panel,
    errorContainer = Color(0xFFF3DCD6), onErrorContainer = LightHerdr.dangerText,
    outline = LightHerdr.lineStrong, outlineVariant = LightHerdr.line,
    scrim = Color(0xFF281F16),
    surfaceBright = LightHerdr.panel, surfaceDim = LightHerdr.hover,
    surfaceContainerLowest = LightHerdr.input, surfaceContainerLow = LightHerdr.panel,
    surfaceContainer = LightHerdr.panel, surfaceContainerHigh = LightHerdr.card,
    surfaceContainerHighest = LightHerdr.hover,
)

private fun style(size: Int, line: Double, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0, family: FontFamily = Sans) = TextStyle(
    fontFamily = family, fontSize = size.sp, lineHeight = (size * line).sp, fontWeight = weight,
    letterSpacing = if (tracking == 0.0) TextStyle.Default.letterSpacing else tracking.em,
)

/**
 * The type scale in sp, so it follows the phone's font size. The console's voice (titles, module
 * headings, labels, buttons, the nav) is Geist Mono, written in capitals where it names a place
 * (`Caps`); what is read at length (prose, row titles, body) is Geist, so a phone stays readable.
 */
internal val HerdrTypography = Typography(
    // Top-level screen titles: mono, semibold, in capitals.
    headlineLarge = style(18, 1.3, FontWeight.SemiBold, 0.04, Mono),
    headlineMedium = style(18, 1.3, FontWeight.SemiBold, 0.04, Mono),
    // A task's title, Markdown h1.
    headlineSmall = style(18, 1.3, FontWeight.SemiBold),
    // A detail bar's title, sheet and dialog titles: mono.
    titleLarge = style(15, 1.35, FontWeight.SemiBold, 0.02, Mono),
    // Row titles: the reading face, medium.
    titleMedium = style(14, 1.5, FontWeight.Medium),
    // Module headings: mono, small, semibold, tracked, in capitals.
    titleSmall = style(12, 1.4, FontWeight.SemiBold, 0.08, Mono),
    // Chat prose, your turns, menu rows, form values.
    bodyLarge = style(15, 1.6),
    // Body and dialog copy, secondary lines.
    bodyMedium = style(14, 1.5),
    // Subtitles, hints, meta lines.
    bodySmall = style(12, 1.5),
    // Buttons, chips, segments: mono.
    labelLarge = style(13, 1.4, FontWeight.Medium, 0.02, Mono),
    // Counts, pills, nav labels: mono.
    labelMedium = style(12, 1.35, FontWeight.Medium, 0.02, Mono),
    // Badges and field labels, in capitals: mono, tracked.
    labelSmall = style(11, 1.3, FontWeight.SemiBold, 0.08, Mono),
)

/** Code and terminal text: Geist Mono. */
val CodeStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 20.sp)

private val Square = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)

internal val HerdrShapesM3 = Shapes(extraSmall = Square, small = Square, medium = Square, large = Square, extraLarge = Square)

/** How the workbench writes a heading, a label or a place: in capitals. */
fun String.caps(): String = uppercase()

@Composable
fun HerdrTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHerdrColors provides if (dark) DarkHerdr else LightHerdr) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            shapes = HerdrShapesM3,
            typography = HerdrTypography,
            content = content,
        )
    }
}

/**
 * A board status's colour: the board's tones folded onto the four agent states, so a
 * task and an agent in the same state look the same. Working and reviewing are at work; ready to
 * approve and done are done; to do, queued and archived are idle; everything that needs you is red.
 * The written status always goes with it.
 */
fun HerdrColors.tone(t: BoardTone): Color = when (t) {
    BoardTone.Amber, BoardTone.Blue -> working
    BoardTone.Red -> red
    BoardTone.Green, BoardTone.Teal -> done
    BoardTone.Pink, BoardTone.Grey -> idle
}

/** The workbench's agent state colours. */
fun HerdrColors.status(s: AgentStatus): Color = when (s) {
    AgentStatus.Blocked -> red
    AgentStatus.Working -> working
    AgentStatus.Done -> done
    AgentStatus.Idle, AgentStatus.Unknown -> idle
}

@Composable
@ReadOnlyComposable
fun AgentStatus.color(): Color = LocalHerdrColors.current.status(this)

fun AgentStatus.title(): String = when (this) {
    AgentStatus.Blocked -> "Needs you"
    AgentStatus.Working -> "Working"
    AgentStatus.Done -> "Done"
    AgentStatus.Idle -> "Idle"
    AgentStatus.Unknown -> "Other"
}

/** Its state in the one word every screen uses ("needs you", not herdr-core's "question"); a state the app lacks as herdr says it. */
fun com.makefunstuff.herdr.core.api.AgentInfo.statusWord(): String =
    if (agentStatus == AgentStatus.Unknown) statusLabel else agentStatus.title().lowercase()

/**
 * The terminal's colours: the workbench's ground, text and amber cursor over its own ANSI colours;
 * any cell's text is held at 4.5:1 on its background.
 */
fun HerdrColors.terminalPalette(): TerminalPalette = TerminalPalette(
    foreground = text.toArgb(),
    background = ground.toArgb(),
    cursor = accent.toArgb(),
    ansi = ansi.map { it.toArgb() }.toIntArray(),
    minimumContrast = 4.5,
)
