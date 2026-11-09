package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.docs.DocBlock
import com.makefunstuff.herdr.core.docs.DocUnreadable
import com.makefunstuff.herdr.core.docs.DocsClient
import com.makefunstuff.herdr.core.docs.DocsCommands
import com.makefunstuff.herdr.core.docs.DocsRunner
import com.makefunstuff.herdr.core.docs.HerdrDocs
import com.makefunstuff.herdr.core.ssh.CommandResult
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** herdr.doc/2 documents on the phone: what it makes of a file, and the shell that lists and reads them. */
class DocsTest {
    /** The format's own example (SPEC.md), with a block from a newer format and one of each odd field. */
    private val example = """
        { "format": "herdr.doc/2", "title": "Pod scheduling failures in shop-prod", "kind": "investigation", "status": "work",
          "subtitle": "on duty · 4 Nov 2026", "tags": ["shop-prod", "kubernetes"], "updated": "2026-11-04T14:05:00Z",
          "blocks": [
            { "type": "callout", "tone": "warn", "text": "**6 alerts** since 19:21: the `default` node pool is at max nodes." },
            { "type": "kv", "items": [["project", "shop-prod"], ["nodes", 16]] },
            { "type": "timeline", "items": [ {"time": "19:21", "text": "First FailedScheduling", "tone": "bad"},
                                             {"time": "19:24", "text": "Autoscaler: max node group size reached"} ] },
            { "type": "finding", "title": "Autoscaler ceiling", "severity": "medium", "detail": "The pool's `maxNodeCount` is 15.",
              "evidence": [ { "type": "evidence", "command": "kubectl get nodes | wc -l", "output": "16", "where": "shop-prod" } ] },
            { "type": "hologram", "text": "from a newer herdr" },
            { "type": "section", "title": "More", "collapsed": true, "blocks": [ { "type": "heading", "text": "Deep", "level": 7 } ] },
            { "type": "table", "columns": ["pool", "nodes"], "rows": [["default", 15], ["spot", 3]], "align": ["left", "right"] },
            { "type": "bars", "items": [ {"label": "a", "value": 2}, {"label": "b", "value": "8"} ], "unit": "ms" },
            { "type": "checks", "items": [ {"text": "lint", "ok": true}, {"text": "e2e", "ok": null} ] },
            { "type": "image", "caption": "no path, so nothing to show" },
            { "type": "todo", "items": [ {"text": "Raise maxNodeCount to 20", "who": "platform"} ] }
          ] }
    """.trimIndent()

    @Test
    fun readsEveryBlockItKnowsAndSkipsTheRest() {
        val doc = HerdrDocs.parse(example)
        assertEquals("Pod scheduling failures in shop-prod", doc.title)
        assertEquals("investigation", doc.kind)
        assertEquals("work", doc.status)
        assertEquals(listOf("shop-prod", "kubernetes"), doc.tags)
        assertEquals(
            listOf("Callout", "Kv", "Timeline", "Finding", "Section", "Table", "Bars", "Checks", "Todo"),
            doc.blocks.map { it::class.simpleName },
        )
        val kv = doc.blocks[1] as DocBlock.Kv
        assertEquals("project" to "shop-prod", kv.items[0])
        assertEquals("nodes" to "16", kv.items[1])
        val finding = doc.blocks[3] as DocBlock.Finding
        assertEquals("medium", finding.severity)
        assertEquals("kubectl get nodes | wc -l", (finding.evidence.single() as DocBlock.Evidence).command)
        val section = doc.blocks[4] as DocBlock.Section
        assertTrue(section.collapsed)
        assertEquals(3, (section.blocks.single() as DocBlock.Heading).level)
        val table = doc.blocks[5] as DocBlock.Table
        assertEquals(listOf("default", "15"), table.rows[0])
        assertEquals(setOf(1), table.right)
        val bars = doc.blocks[6] as DocBlock.Bars
        assertEquals(8.0, bars.max)
        assertEquals(8.0, bars.items[1].value)
        assertNull((doc.blocks[7] as DocBlock.Checks).items[1].ok)
    }

    @Test
    fun aFileThatIsNotADocumentSaysSo() {
        assertFailsWith<IOException> { HerdrDocs.parse("not json at all {") }
        assertFailsWith<IOException> { HerdrDocs.parse("[1, 2]") }
        assertEquals("Untitled", HerdrDocs.parse("{}").title)
    }

    @Test
    fun theListReadsOnlyTheHeadEvenCutShort() {
        val cut = example.take(200)
        val e = HerdrDocs.entry("/home/me/.herdr/docs/pods.hdoc.json", 1_700_000_000, cut)
        assertEquals("Pod scheduling failures in shop-prod", e.title)
        assertEquals("investigation", e.kind)
        assertEquals("work", e.status)
        assertEquals("pods", e.slug)
        // Strings inside blocks are not the document's.
        val nested = """{"blocks":[{"title":"inner","status":"done"}],"title":"Outer"}"""
        assertEquals(mapOf("title" to "Outer"), HerdrDocs.topStrings(nested))
        assertEquals("He said \"hi\"", HerdrDocs.topStrings("""{"title":"He said \"hi\"","kind":"no""")!!["title"])
        val broken = HerdrDocs.entry("/d/x.hdoc.json", 0, "oops")
        assertTrue(broken.broken)
        assertEquals("x", broken.title)
    }

    @Test
    fun aDocumentOpenOnThePhoneIsReReadOnlyWhenItChanges() = runTest {
        val commands = mutableListOf<String>()
        var mtime = 100L
        val client = DocsClient(
            DocsRunner { command, _ ->
                commands += command
                // A login script that prints something before the mark: the document starts after the mark's line.
                CommandResult(0, "welcome back\n${DocsCommands.MARK}$mtime 13 /d/a.hdoc.json\n" + if ("'100 13'" in command && mtime == 100L) "" else """{"title":"A"}""", "")
            },
        )
        val first = client.read("/d/a.hdoc.json")
        assertEquals("A", first.doc?.title)
        assertEquals("100 13", first.stamp)
        assertNull(client.read("/d/a.hdoc.json", since = first.stamp).doc)
        mtime = 101
        assertEquals("A", client.read("/d/a.hdoc.json", since = first.stamp).doc?.title)
        assertTrue(commands.all { "'/d/a.hdoc.json'" in it })
    }

    @Test
    fun aFileThatCannotBeShownSaysWhichVersion() = runTest {
        val big = DocsClient(DocsRunner { _, _ -> CommandResult(0, "${DocsCommands.MARK}5 ${DocsCommands.MAX_BYTES + 1} /d/big.hdoc.json\n", "") })
        assertEquals("5 ${DocsCommands.MAX_BYTES + 1}", assertFailsWith<DocUnreadable> { big.read("/d/big.hdoc.json") }.stamp)
        val half = DocsClient(DocsRunner { _, _ -> CommandResult(0, "${DocsCommands.MARK}6 9 /d/half.hdoc.json\n{\"title", "") })
        assertEquals("6 9", assertFailsWith<DocUnreadable> { half.read("/d/half.hdoc.json") }.stamp)
    }

    @Test
    fun aSectionKeepsItsNameWhenABlockIsWrittenAboveIt() {
        fun section(title: String) = DocBlock.Section(title, emptyList(), false, null)
        val before = listOf(DocBlock.Md("x"), section("Logs"), section("Notes"), section("Logs"))
        val after = listOf(DocBlock.Heading("new", 2)) + before
        val a = HerdrDocs.names(before)
        val b = HerdrDocs.names(after)
        assertEquals(a.size, a.toSet().size)
        assertEquals(b.size, b.toSet().size)
        assertEquals(a.drop(1), b.drop(2))
        assertTrue(a[1] != a[3])
        // A name that looks like a place never meets one: sections start with "s", the rest with "b".
        assertEquals(listOf("b0", "s1-Logs", "s1-Notes", "s2-Logs"), a)
    }

    @Test
    fun linksOpenOnlyTheWebAndMail() {
        val doc = HerdrDocs.parse(
            """{"blocks":[{"type":"links","items":[{"label":"PR","url":"https://x/pr/1"},{"url":"mailto:a@b"},
              {"label":"bad","url":"intent://evil#Intent;end"},{"label":"file","url":"file:///etc/passwd"},{"label":"none"}]}]}""",
        )
        assertEquals(listOf("https://x/pr/1", "mailto:a@b"), (doc.blocks.single() as DocBlock.Links).items.map { it.url })
    }

    @Test
    fun aMissingDocumentIsAnError() = runTest {
        val client = DocsClient(DocsRunner { _, _ -> CommandResult(2, "", "No such document\n") })
        assertEquals("No such document", assertFailsWith<IOException> { client.read("/d/gone.hdoc.json") }.message)
    }

    // ---- the commands themselves, in a real shell against a scratch home

    private fun sh(home: File, command: String): CommandResult {
        val p = ProcessBuilder("sh", "-c", command).apply { environment()["HOME"] = home.path }.start()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor(20, TimeUnit.SECONDS)
        return CommandResult(p.exitValue(), out, err)
    }

    @Test
    fun theShellListsTheFolderNewestFirstAndReadsOne() = runTest {
        val home = Files.createTempDirectory("hdoc").toFile()
        try {
            val client = DocsClient(DocsRunner { command, _ -> sh(home, command) })
            // No folder yet: no documents, no error.
            assertEquals(emptyList(), client.list())
            val dir = File(home, ".herdr/docs").apply { mkdirs() }
            File(dir, "old.hdoc.json").apply { writeText(example); setLastModified(1_700_000_000_000) }
            File(dir, "new one.hdoc.json").apply { writeText("""{"title":"Runbook: rotate keys","kind":"runbook","status":"ready","blocks":[]}"""); setLastModified(1_700_000_500_000) }
            File(dir, "broken.hdoc.json").apply { writeText("{\"title\": \"Half"); setLastModified(1_700_000_100_000) }
            File(dir, "notes.md").writeText("# not a doc")
            File(dir, "-n.hdoc.json").apply { writeText("""{"title":"Dash"}"""); setLastModified(1_699_000_000_000) }
            val list = client.list()
            // The cut-off one keeps its place, under its file's name.
            // A name that starts with a dash is a name, not an option.
            assertEquals(listOf("Runbook: rotate keys", "broken", "Pod scheduling failures in shop-prod", "Dash"), list.map { it.title })
            assertEquals(1_699_000_000, list[3].mtime)
            assertEquals(1_700_000_500, list[0].mtime)
            assertEquals("${dir.path}/new one.hdoc.json", list[0].path)
            val read = client.read(list[2].path)
            assertEquals(1_700_000_000, read.mtime)
            assertEquals(9, read.doc!!.blocks.size)
            assertNull(client.read(list[2].path, since = read.stamp).doc)
            assertEquals("No such document", assertFailsWith<IOException> { client.read("${dir.path}/gone.hdoc.json") }.message)
            assertFailsWith<DocUnreadable> { client.read(list[1].path) }
            // Rewritten within the same second, longer: still read again.
            val old = File(list[2].path)
            old.writeText(example.replace("\"status\": \"work\"", "\"status\": \"ready\""))
            old.setLastModified(1_700_000_000_000)
            assertEquals("ready", client.read(list[2].path, since = read.stamp).doc?.status)
            // Too large: the shell doesn't send it at all.
            val big = File(dir, "big.hdoc.json").apply { writeText("{\"title\":\"" + "x".repeat(DocsCommands.MAX_BYTES) + "\"}") }
            val raw = sh(home, DocsCommands.read(big.path))
            assertTrue(raw.stdout.length < 200)
            assertFailsWith<DocUnreadable> { client.read(big.path) }
        } finally {
            home.deleteRecursively()
        }
    }
}
