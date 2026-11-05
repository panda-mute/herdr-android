package com.makefunstuff.herdr.ui.design

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every icon builds, as a Lucide stroke icon or a workbench glyph, and every program gets its chip. */
class IconsTest {
    private fun paths(g: VectorGroup): List<VectorPath> = g.flatMap {
        when (it) {
            is VectorPath -> listOf(it)
            is VectorGroup -> paths(it)
        }
    }

    @Test fun everyIconIsALucideStrokeOrAWorkbenchGlyph() {
        val icons = HerdrIcons::class.java.declaredMethods
            .filter { it.name.startsWith("get") && it.returnType == ImageVector::class.java && it.parameterCount == 0 }
            .map { it.invoke(HerdrIcons) as ImageVector }
        assertTrue("found ${icons.size} icons", icons.size > 50)
        var glyphs = 0
        icons.forEach { icon ->
            val p = paths(icon.root)
            assertTrue(icon.name, p.isNotEmpty())
            if (icon.viewportWidth == 14f) {
                // A dot-matrix glyph: filled squares on the desktop's 14-unit grid.
                glyphs++
                p.forEach { path -> assertNotNull("${icon.name} is filled", path.fill) }
            } else {
                assertEquals(icon.name, 24f, icon.viewportWidth)
                p.forEach { path ->
                    assertNull("${icon.name} has no fill", path.fill)
                    assertEquals("${icon.name} keeps Lucide's 2-unit stroke", 2f, path.strokeLineWidth)
                }
            }
        }
        assertTrue("the workbench's glyphs are used ($glyphs)", glyphs >= 20)
    }

    @Test fun programsGetTheirChips() {
        assertSame(ChipArt.Claude, chipFor("claude"))
        assertSame(ChipArt.Codex, chipFor("Codex"))
        assertSame(ChipArt.Agent, chipFor("pi"))
        assertSame(ChipArt.Shell, chipFor("zsh"))
        assertSame(ChipArt.Python, chipFor("python3"))
        assertSame(ChipArt.Program, chipFor("hermes"))
        assertEquals(ChipInk.Agent, chipFor("claude").ink)
        assertEquals(ChipInk.Tool, chipFor("bash").ink)
    }
}
