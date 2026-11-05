package com.makefunstuff.herdr.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.ui.theme.Herdr

/*
 * The workbench's own pieces (docs/DESIGN.md): the dot-matrix glyphs, the chip-package program and
 * agent icons, the status LED, the numbered slot and the circuit board, from the Herdr desktop's
 * workbench art (WorkbenchArt.kt, made by tools/workbench-art/gen.py).
 */

/** A dot-matrix glyph from its path on the desktop's 14-unit grid (viewBox -1 -1 14 14), drawn in the icon's tint. */
internal fun glyph(name: String, d: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 14f, 14f).apply {
        addGroup(translationX = 1f, translationY = 1f)
        addPath(addPathNodes(d), fill = SolidColor(Color.Black))
        clearGroup()
    }.build()

private fun path(d: String): Path = PathParser().parsePathString(d).toPath()

/** Which ink a chip is printed in: agents amber, runtimes teal, tools grey (the desktop's three). */
enum class ChipInk { Agent, Runtime, Tool }

/** A chip-package icon on the 24-unit grid: its body, its pins and its marking. */
class Chip internal constructor(val ink: ChipInk, private val frameData: String, private val bodyData: String, private val pinsData: String, private val markData: String) {
    internal val frame: Path by lazy { path(frameData) }
    internal val body: Path by lazy { path(bodyData) }
    internal val pins: Path by lazy { path(pinsData) }
    internal val mark: Path by lazy { path(markData) }
}

/** The chip for a program or agent, by the name herdr gives it ("claude", "codex", "bun", "zsh"…). */
fun chipFor(program: String?): Chip {
    val p = program?.lowercase()?.trim().orEmpty()
    return when {
        p.startsWith("claude") -> ChipArt.Claude
        p.startsWith("codex") -> ChipArt.Codex
        p in SHELLS -> ChipArt.Shell
        p == "bun" -> ChipArt.Bun
        p == "cargo" || p == "rustc" -> ChipArt.Cargo
        p == "docker" -> ChipArt.Docker
        p == "git" || p == "lazygit" -> ChipArt.Git
        p == "go" -> ChipArt.Go
        p == "htop" || p == "btop" || p == "top" -> ChipArt.Htop
        p == "make" -> ChipArt.Make
        p == "node" || p == "deno" -> ChipArt.Node
        p == "npm" || p == "pnpm" || p == "yarn" -> ChipArt.Npm
        p == "nvim" -> ChipArt.Nvim
        p == "vim" || p == "vi" -> ChipArt.Vim
        p.startsWith("python") || p == "uv" -> ChipArt.Python
        p == "ssh" || p == "mosh" -> ChipArt.Ssh
        p in AGENTS -> ChipArt.Agent
        else -> ChipArt.Program
    }
}

private val SHELLS = setOf("", "bash", "zsh", "fish", "sh", "nu", "shell", "terminal")
private val AGENTS = setOf("cursor", "opencode", "omp", "pi", "amp", "copilot", "droid", "gemini", "grok", "agent", "aider", "goose", "assistant")

/** A chip icon, [size] square, in the theme's colours: the body a step off the ground, pins dim, its edge and marking in its ink. */
@Composable
fun ChipIcon(chip: Chip, modifier: Modifier = Modifier, size: Dp = 22.dp, contentDescription: String? = null) {
    val c = Herdr.colors
    val ink = when (chip.ink) {
        ChipInk.Agent -> c.accent
        ChipInk.Runtime -> c.teal
        ChipInk.Tool -> c.muted
    }
    val body = if (c.dark) Color(0xFF15181A) else c.card
    Canvas(modifier.size(size).then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)) {
        scale(this.size.width / 24f, this.size.height / 24f, pivot = Offset.Zero) {
            drawPath(chip.pins, c.off)
            drawPath(chip.frame, ink)
            drawPath(chip.body, body)
            drawPath(chip.mark, ink)
        }
    }
}

/** A status light: a small square in the state's colour, as the desktop's space slots and pane headers draw it. */
@Composable
fun Led(color: Color, modifier: Modifier = Modifier, size: Dp = 7.dp) {
    Box(modifier.size(size).background(color))
}

/** A numbered slot, the desktop's space index cell ("01"): framed, or amber with ink when it is the one shown. */
@Composable
fun SlotNumber(n: Int, on: Boolean, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    Box(
        modifier.sizeIn(minWidth = 26.dp, minHeight = 20.dp)
            .background(if (on) c.primary else c.card)
            .border(1.dp, if (on) c.primary else c.lineStrong),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            n.toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (on) c.onPrimary else c.muted,
        )
    }
}

/**
 * The circuit board, quiet, behind a screen with room to spare (an empty state, the drawer's
 * foot): the desktop's backgrounds/workbench.svg covering the box, in colours a step off the
 * ground so text over it keeps its contrast. Never under rows of text.
 */
@Composable
fun CircuitBackdrop(modifier: Modifier = Modifier) {
    val c = Herdr.colors
    val layers = remember { listOf(CircuitArt.trace, CircuitArt.bus, CircuitArt.pad, CircuitArt.part).map(::path) }
    val holes = remember { path(CircuitArt.hole) }
    Canvas(modifier.clipToBounds()) {
        drawRect(c.ground)
        val k = maxOf(size.width / CircuitArt.WIDTH, size.height / CircuitArt.HEIGHT)
        translate((size.width - CircuitArt.WIDTH * k) / 2f, (size.height - CircuitArt.HEIGHT * k) / 2f) {
            scale(k, k, pivot = Offset.Zero) {
                layers.forEachIndexed { i, p -> drawPath(p, c.circuit[i]) }
                drawPath(holes, c.ground)
            }
        }
    }
}
