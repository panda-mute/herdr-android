package com.makefunstuff.herdr.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.board.BoardTone
import com.makefunstuff.herdr.core.code.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG contrast of the text/background pairs the app actually uses (AA: 4.5:1 for text, 3:1 for
 * marks), in both themes, on every surface text sits on.
 */
class ContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val x = a.luminance().toDouble() + 0.05; val y = b.luminance().toDouble() + 0.05
        return maxOf(x, y) / minOf(x, y)
    }

    private fun check(name: String, s: ColorScheme, h: HerdrColors) {
        val fails = mutableListOf<String>()
        fun need(label: String, fg: Color, bg: Color, min: Double = 4.5) {
            val r = ratio(fg, bg)
            println("$name $label %.2f".format(r))
            if (r < min) fails += "$name $label = %.2f < $min".format(r)
        }
        // The grounds text sits on: the screen, bars and sheets, cards, a pressed or chosen row, fields.
        val grounds = listOf("ground" to h.ground, "panel" to h.panel, "card" to h.card, "hover" to h.hover, "input" to h.input)
        for ((g, bg) in grounds) {
            need("text on $g", h.text, bg); need("strong on $g", h.strong, bg); need("muted on $g", h.muted, bg)
            need("accent on $g", h.accent, bg); need("error on $g", s.error, bg)
            AgentStatus.entries.forEach { need("status ${it.name} on $g", h.status(it), bg) }
            BoardTone.entries.forEach { need("tone ${it.name} on $g", h.tone(it), bg) }
            need("notice on $g", h.notice, bg)
        }
        for ((g, bg) in listOf("ground" to h.ground, "panel" to h.panel, "card" to h.card)) {
            // A status badge: the state's word in its colour on its tint.
            listOf(h.red, h.working, h.done).forEach { need("state %08X on its tint over $g".format(it.value.toLong() shr 32), it, h.tint(it).compositeOver(bg)) }
            need("text on danger tint over $g", h.text, h.dangerTint.compositeOver(bg))
            need("danger text on danger tint over $g", h.dangerText, h.dangerTint.compositeOver(bg))
            // Hairlines and switches off: marks, 3:1 is not required of decorative edges, but the
            // strong edge (a keycap, an unchecked switch's track) must show against its surface.
            need("idle dot mark on $g", h.idle, bg, 3.0)
        }
        // Code: every syntax colour on the viewer's ground, on a marked line and on a diff's added and removed lines.
        val code = listOf("panel" to h.panel, "marked line" to h.accentTint.compositeOver(h.panel),
            "added line" to h.lineTint(h.done).compositeOver(h.panel), "removed line" to h.lineTint(h.red).compositeOver(h.panel))
        for ((g, bg) in code) Role.entries.forEach { need("syntax ${it.name} on $g", h.syntax(it), bg) }
        // Your own action: ink on the amber fill.
        need("onPrimary on primary", h.onPrimary, h.primary)
        need("scheme onPrimary on primary", s.onPrimary, s.primary)
        // The chosen lens: its amber glyph on the neutral pill.
        need("accent mark on hover", h.accent, h.hover, 3.0)
        // The toast: strong text on the chrome, framed in amber (red for an error).
        need("toast text", h.strong, h.panel)
        need("toast error mark", h.red, h.panel, 3.0)
        // The amber frame of what is in focus shows against the ground.
        need("focus frame", h.accent, h.ground, 3.0)
        // The shown space's slot: its name in amber on the selected fill (its number is ink on the amber fill, onPrimary on primary).
        need("chosen slot name", h.accent, h.selected)
        // The terminal: its text and cursor on its ground.
        val term = h.terminalPalette()
        need("terminal text", Color(term.foreground), Color(term.background))
        need("terminal cursor", Color(term.cursor), Color(term.background), 3.0)
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun dark() = check("dark", Dark, DarkHerdr)
    @Test fun light() = check("light", Light, LightHerdr)

    /** The dark theme is the desktop's workbench (herdr-app/themes/workbench.json), value for value. */
    @Test fun darkIsTheWorkbenchTheme() {
        fun hex(c: Color) = "#%06X".format(c.value.toLong().ushr(32).toInt() and 0xFFFFFF)
        assertEquals(
            // background, chrome, surface, input, border, selected, foreground, muted, dim, accent, accent_foreground, working, blocked, done
            listOf("#0D0F10", "#0A0D0C", "#1B1F21", "#090A0B", "#4E5B57", "#1E3A40", "#DFE4E1", "#8FA3A8", "#66727A", "#FFB000", "#0B0D0E", "#FFB000", "#FF5A4A", "#5FD38A"),
            DarkHerdr.run { listOf(ground, panel, card, input, lineStrong, selected, text, muted, off, accent, onPrimary, working, red, done) }.map(::hex),
        )
        assertEquals("#23404A", hex(DarkHerdr.termSelection))
    }

    /** Work is the console's own amber, as on the desktop; the other states never are, and none is another's colour. */
    @Test fun statesKeepTheirColours() {
        for (h in listOf(DarkHerdr, LightHerdr)) {
            assertEquals(h.accent, h.status(AgentStatus.Working))
            val others = listOf(AgentStatus.Blocked, AgentStatus.Done, AgentStatus.Idle).map { h.status(it) }
            others.forEach { assertTrue(it != h.accent && it != h.primary) }
            assertEquals(others.size, others.toSet().size)
        }
    }
}
