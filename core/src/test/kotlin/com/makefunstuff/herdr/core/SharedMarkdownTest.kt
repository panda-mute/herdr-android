package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.chat.Markdown
import com.makefunstuff.herdr.core.chat.Markdown.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SharedMarkdownTest {

    @Test
    fun taskListsAndNesting() {
        val b = Markdown.parse("- [ ] todo\n- [x] done **bold**\n  - [X] nested\n    1. deep")
        val items = b.filterIsInstance<Block.ListItem>()
        assertEquals(listOf(false, true, true, null), items.map { it.checked })
        assertEquals(listOf(0, 0, 1, 2), items.map { it.depth })
        assertEquals("done bold", items[1].text.joinToString("") { it.text })
    }

    @Test
    fun autolinksAndBareUrls() {
        val spans = Markdown.inline("see <https://github.com/a/b/pull/3> and https://x.dev/r.")
        assertEquals(listOf("https://github.com/a/b/pull/3", "https://x.dev/r"), spans.mapNotNull { it.link })
    }

    @Test
    fun plainFirstLine() {
        assertEquals("Fix the **thing**".replace("**", ""), Markdown.plain("## Fix the **thing**\nmore"))
        assertEquals("a task", Markdown.plain("\n- [ ] a task"))
        assertEquals("code", Markdown.plain("```\nx\n```\n`code`"))
    }

    @Test
    fun unclosedFenceAndLongLinesSurvive() {
        val long = "/very/long/path/".repeat(60)
        val b = Markdown.parse("```\n$long\nno close")
        assertEquals(1, b.size)
        assertTrue((b[0] as Block.Code).text.contains(long))
    }
}
