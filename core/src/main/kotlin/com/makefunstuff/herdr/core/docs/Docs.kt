package com.makefunstuff.herdr.core.docs

import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import com.makefunstuff.herdr.core.ssh.MachineConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.IOException

/**
 * A herdr.doc/2 document (the desktop's Docs panel, mods/px-doc/SPEC.md): an investigation, a runbook, a report that
 * an agent writes as one JSON file in `~/.herdr/docs/<slug>.hdoc.json`. The phone only reads them.
 */
data class HerdrDoc(
    val title: String,
    val subtitle: String? = null,
    /** investigation, incident, runbook, design, notes, report: only a label. */
    val kind: String? = null,
    /** work, need, ready, done, fail, info. */
    val status: String? = null,
    val tags: List<String> = emptyList(),
    /** When its writer last set it (ISO 8601), as written. */
    val updated: String? = null,
    val blocks: List<DocBlock> = emptyList(),
)

/** One of a document's blocks. A type the phone doesn't know is left out, so newer documents still open. */
sealed interface DocBlock {
    data class Heading(val text: String, val level: Int) : DocBlock
    data class Text(val text: String) : DocBlock
    /** Markdown prose. */
    data class Md(val text: String) : DocBlock
    data class Section(val title: String, val blocks: List<DocBlock>, val collapsed: Boolean, val intro: String?) : DocBlock
    data class Columns(val columns: List<List<DocBlock>>) : DocBlock
    /** [text] is Markdown; [tone] info, ok, warn or bad. */
    data class Callout(val text: String, val tone: String, val title: String?) : DocBlock
    data class Quote(val text: String, val who: String?, val where: String?) : DocBlock
    data class Stats(val items: List<Stat>) : DocBlock
    data class Kv(val items: List<Pair<String, String>>) : DocBlock
    data class Items(val items: List<String>, val numbered: Boolean) : DocBlock
    data class Todo(val items: List<TodoItem>) : DocBlock
    data class Checks(val items: List<Check>) : DocBlock
    data class Steps(val items: List<Step>) : DocBlock
    data class Timeline(val items: List<Moment>) : DocBlock
    /** [detail] is Markdown; [severity] low, medium, high or critical. */
    data class Finding(val title: String, val severity: String?, val detail: String?, val evidence: List<DocBlock>, val fix: String?) : DocBlock
    data class Evidence(val command: String, val output: String, val where: String?) : DocBlock
    data class Table(val columns: List<String>, val rows: List<List<String>>, val right: Set<Int>) : DocBlock
    data class Bars(val items: List<Bar>, val unit: String?, val max: Double) : DocBlock
    data class Progress(val label: String, val value: Double, val max: Double, val tone: String?) : DocBlock
    data class Code(val text: String, val lang: String?, val file: String?) : DocBlock
    data class Diff(val text: String, val file: String?) : DocBlock
    data class Links(val items: List<Link>) : DocBlock
    /** A picture on the machine ([path], absolute). */
    data class Image(val path: String, val caption: String?) : DocBlock
    data object Divider : DocBlock
}

data class Stat(val label: String, val value: String, val tone: String?, val note: String?)
data class TodoItem(val text: String, val done: Boolean, val who: String?)
/** [ok]: passed, failed, or null where it wasn't run. */
data class Check(val text: String, val ok: Boolean?, val note: String?)
/** [state]: done, now, todo, skip. */
data class Step(val text: String, val state: String, val note: String?)
data class Moment(val time: String, val text: String, val tone: String?, val who: String?)
data class Bar(val label: String, val value: Double, val tone: String?)
data class Link(val label: String, val url: String)

/** A document in the list: its file and what its head says, without its blocks. */
data class DocEntry(
    val path: String,
    val title: String,
    val kind: String? = null,
    val status: String? = null,
    /** The file's change time, in seconds since the epoch; 0 where the machine didn't say. */
    val mtime: Long = 0,
    /** It isn't a document the phone can read (not JSON, or not an object). */
    val broken: Boolean = false,
) {
    /** The file's name without `.hdoc.json`: its slug. */
    val slug: String get() = path.substringAfterLast('/').removeSuffix(".hdoc.json")
}

/** Reads herdr.doc files: a whole one ([parse]) and a list entry from its head alone ([entry]). */
object HerdrDocs {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Blocks this deep inside sections, columns and findings are left out: a document is never that deep. */
    private const val MAX_DEPTH = 8

    /** A document, or an error that says why it isn't one. Unknown blocks and fields are skipped; missing ones are empty. */
    fun parse(text: String): HerdrDoc {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            // The parser's own words name offsets and paths; a file cut short is most often one still being written.
            throw IOException("It isn't valid JSON: cut short, or not a herdr.doc. An agent may still be writing it.")
        }
        val o = root as? JsonObject ?: throw IOException("It isn't a herdr.doc: not a JSON object")
        return HerdrDoc(
            title = o.str("title")?.takeIf { it.isNotBlank() } ?: "Untitled",
            subtitle = o.str("subtitle")?.takeIf { it.isNotBlank() },
            kind = o.str("kind")?.takeIf { it.isNotBlank() },
            status = o.str("status")?.takeIf { it.isNotBlank() },
            tags = o.arr("tags").mapNotNull { it.text() }.filter { it.isNotBlank() },
            updated = o.str("updated")?.takeIf { it.isNotBlank() },
            blocks = blocks(o["blocks"], 0),
        )
    }

    /**
     * A list entry from the start of a file ([head], perhaps cut short): its title, kind and status are near the top,
     * before the blocks, so the list never reads whole documents. A head that doesn't start as an object is [DocEntry.broken].
     */
    fun entry(path: String, mtime: Long, head: String): DocEntry {
        val name = path.substringAfterLast('/').removeSuffix(".hdoc.json")
        val fields = topStrings(head) ?: return DocEntry(path, name, mtime = mtime, broken = true)
        return DocEntry(
            path = path,
            title = fields["title"]?.takeIf { it.isNotBlank() } ?: name,
            kind = fields["kind"]?.takeIf { it.isNotBlank() },
            status = fields["status"]?.takeIf { it.isNotBlank() },
            mtime = mtime,
        )
    }

    private fun blocks(e: JsonElement?, depth: Int): List<DocBlock> =
        if (depth > MAX_DEPTH) emptyList() else (e as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { b -> block(b, depth) } }

    private fun block(b: JsonObject, depth: Int): DocBlock? = when (b.str("type")) {
        "heading" -> DocBlock.Heading(b.text("text"), (b.num("level")?.toInt() ?: 2).coerceIn(1, 3))
        "text" -> DocBlock.Text(b.text("text"))
        "md" -> DocBlock.Md(b.text("text"))
        "section" -> DocBlock.Section(b.text("title"), blocks(b["blocks"], depth + 1), b.bool("collapsed") == true, b.str("intro"))
        "columns" -> DocBlock.Columns(b.arr("columns").take(3).map { blocks(it, depth + 1) })
        "callout" -> DocBlock.Callout(b.text("text"), b.str("tone") ?: "info", b.str("title")?.takeIf { it.isNotBlank() })
        "quote" -> DocBlock.Quote(b.text("text"), b.str("who"), b.str("where"))
        "stats" -> DocBlock.Stats(b.objs("items").map { Stat(it.text("label"), it.text("value"), it.str("tone"), it.str("note")) })
        "kv" -> DocBlock.Kv(
            b.arr("items").mapNotNull { row ->
                when (row) {
                    is JsonArray -> (row.getOrNull(0)?.text() ?: "") to (row.getOrNull(1)?.text() ?: "")
                    // A writer may give an object per line; take its key and value as written.
                    is JsonObject -> row.text("key").ifEmpty { row.text("k") } to row.text("value").ifEmpty { row.text("v") }
                    else -> null
                }
            },
        )
        "list" -> DocBlock.Items(b.arr("items").mapNotNull { it.text() }, b.str("style") == "number")
        "todo" -> DocBlock.Todo(b.objs("items").map { TodoItem(it.text("text"), it.bool("done") == true, it.str("who")) })
        "checks" -> DocBlock.Checks(b.objs("items").map { Check(it.text("text"), it.bool("ok"), it.str("note")) })
        "steps" -> DocBlock.Steps(b.objs("items").map { Step(it.text("text"), it.str("state") ?: "todo", it.str("note")) })
        "timeline" -> DocBlock.Timeline(b.objs("items").map { Moment(it.text("time"), it.text("text"), it.str("tone"), it.str("who")) })
        "finding" -> DocBlock.Finding(b.text("title"), b.str("severity"), b.str("detail"), blocks(b["evidence"], depth + 1), b.str("fix"))
        "evidence" -> DocBlock.Evidence(b.text("command"), b.text("output"), b.str("where"))
        "table" -> {
            val columns = b.arr("columns").map { it.text() ?: "" }
            DocBlock.Table(
                columns,
                b.arr("rows").take(100).map { r -> (r as? JsonArray).orEmpty().map { it.text() ?: "" } },
                b.arr("align").withIndex().filter { it.value.text() == "right" }.map { it.index }.toSet(),
            )
        }
        "bars" -> {
            val items = b.objs("items").map { Bar(it.text("label"), it.num("value") ?: 0.0, it.str("tone")) }
            DocBlock.Bars(items, b.str("unit"), b.num("max")?.takeIf { it > 0 } ?: items.maxOfOrNull { it.value }?.coerceAtLeast(1.0) ?: 1.0)
        }
        "progress" -> DocBlock.Progress(b.text("label"), b.num("value") ?: 0.0, b.num("max")?.takeIf { it > 0 } ?: 1.0, b.str("tone"))
        "code" -> DocBlock.Code(b.text("text"), b.str("lang"), b.str("file"))
        "diff" -> DocBlock.Diff(b.text("text"), b.str("file"))
        "links" -> DocBlock.Links(b.objs("items").map { Link(it.text("label").ifEmpty { it.text("url") }, it.text("url").trim()) }.filter { opens(it.url) })
        "image" -> b.str("path")?.takeIf { it.isNotBlank() }?.let { DocBlock.Image(it, b.str("caption")) }
        "divider" -> DocBlock.Divider
        else -> null
    }

    /**
     * A unique name for each of a document's top-level blocks, which keys what the phone remembers of it (a section's
     * fold): a section by its title and how many before it share it, so it keeps its name when a block is written above
     * it; any other block by its place.
     */
    fun names(blocks: List<DocBlock>): List<String> {
        val seen = HashMap<String, Int>()
        return blocks.mapIndexed { i, b ->
            if (b is DocBlock.Section) "s${seen.merge(b.title, 1, Int::plus)}-${b.title}" else "b$i"
        }
    }

    /** Links open in the browser or the mail app only: an agent's document never opens an intent, a file or a content URI. */
    fun opens(url: String): Boolean = url.substringBefore(':', "").lowercase() in setOf("http", "https", "mailto")

    /** A number written as a number or as text ("42", "3.5"). */
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull() }

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

    private fun JsonObject.str(key: String): String? = this[key]?.text()

    /** A field as text: a string as it is, a number as written (42, not 42.0), null where there is none. */
    private fun JsonObject.text(key: String): String = str(key).orEmpty()

    private fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

    private fun JsonObject.objs(key: String): List<JsonObject> = arr(key).mapNotNull { it as? JsonObject }

    private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    /**
     * The top-level string fields of a JSON object that may be cut off anywhere: each key at depth one whose value is
     * a string, until the text runs out. Null where the text doesn't start as an object.
     */
    internal fun topStrings(text: String): Map<String, String>? {
        var i = text.indexOfFirst { !it.isWhitespace() }
        if (i < 0 || text[i] != '{') return null
        i++
        val out = mutableMapOf<String, String>()
        var depth = 1
        var key: String? = null
        var afterColon = false
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '"' -> {
                    val end = stringEnd(text, i) ?: return out
                    val s = runCatching { json.decodeFromString<String>(text.substring(i, end + 1)) }.getOrNull()
                    if (depth == 1) {
                        if (afterColon) {
                            if (key != null && s != null) out.putIfAbsent(key, s)
                            key = null
                            afterColon = false
                        } else key = s
                    }
                    i = end + 1
                    continue
                }
                ch == ':' -> if (depth == 1) afterColon = true
                ch == ',' -> if (depth == 1) { key = null; afterColon = false }
                ch == '{' || ch == '[' -> depth++
                ch == '}' || ch == ']' -> {
                    depth--
                    if (depth == 0) return out
                    if (depth == 1) { key = null; afterColon = false }
                }
            }
            i++
        }
        return out
    }

    /** Where the string that opens at [start] closes, or null where the text runs out first. */
    private fun stringEnd(text: String, start: Int): Int? {
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i++
                '"' -> return i
            }
            i++
        }
        return null
    }
}

/**
 * The shell commands that read `~/.herdr/docs` over SSH. Plain POSIX shell, no herdr-doc needed: the phone only reads
 * the files the desktop and the agents write.
 */
object DocsCommands {
    /** Before each file in [list] and [read]: `@@hdoc <mtime> <size> <path>`. JSON never puts this at a line's start. */
    const val MARK = "@@hdoc "

    /** How much of each file the list reads: its head, where the title, kind and status are. */
    const val HEAD_BYTES = 4096

    /** The largest document the phone reads: the format asks for under 2 MB. */
    const val MAX_BYTES = 4 * 1024 * 1024

    /** At most this many documents in the list, the newest. */
    const val MAX_LIST = 60

    /** The change time in seconds of [file] (a shell word), with GNU's stat or the BSDs' and macOS's. */
    private fun mtime(file: String) = "{ stat -c %Y $file 2>/dev/null || stat -f %m $file 2>/dev/null || echo 0; }"

    /** The folder's documents, newest first, each name as `./name` so one that starts with `-` stays a name. */
    fun list(): String =
        "d=\"\$HOME/.herdr/docs\"; [ -d \"\$d\" ] || exit 0; cd \"\$d\" || exit 0; " +
            "ls -1t 2>/dev/null | grep '\\.hdoc\\.json\$' | head -n $MAX_LIST | while IFS= read -r f; do " +
            "[ -f \"./\$f\" ] || continue; m=\$(${mtime("\"./\$f\"")}); s=\$(wc -c < \"./\$f\" | tr -d ' '); " +
            "printf '\\n$MARK%s %s %s/%s\\n' \"\$m\" \"\$s\" \"\$d\" \"\$f\"; head -c $HEAD_BYTES \"./\$f\"; done"

    /**
     * One document: its mark, then the file, unless it is still the one read at [since] (its [DocRead.stamp]: mtime and
     * size, so two writes in one second still differ) or larger than [MAX_BYTES]. A document open on the phone is sent
     * again only when its writer changes it.
     */
    fun read(path: String, since: String? = null): String =
        "f=${quote(path)}; [ -f \"\$f\" ] || { echo \"No such document\" >&2; exit 2; }; m=\$(${mtime("\"\$f\"")}); s=\$(wc -c < \"\$f\" | tr -d ' '); " +
            "printf '$MARK%s %s %s\\n' \"\$m\" \"\$s\" \"\$f\"; " +
            "[ \"\$m \$s\" = ${quote(since ?: "-")} ] || [ \"\$s\" -gt $MAX_BYTES ] || head -c $MAX_BYTES \"\$f\""
}

/**
 * One read of a document: its file's [mtime], its [stamp] (mtime and size) to ask with next time, and the document where
 * it changed since the stamp asked with.
 */
data class DocRead(val mtime: Long, val stamp: String, val doc: HerdrDoc?)

/** The file is there but can't be shown (too large, not a herdr.doc); [stamp] says which version, so it is read again only once it changes. */
class DocUnreadable(val stamp: String, message: String) : IOException(message)

/** Runs one of [DocsCommands] on the machine; the app's is its SSH connection, tests give their own. */
fun interface DocsRunner {
    suspend fun run(command: String, timeoutSeconds: Long): CommandResult
}

/** A machine's herdr.doc documents, through [runner]. */
class DocsClient(private val runner: DocsRunner) {
    /** The app's client: every command on the machine's current SSH connection. */
    constructor(connect: suspend () -> MachineConnection) : this(DocsRunner { command, seconds -> connect().run(command, seconds) })

    /** The machine's documents, newest first; none where it has no `~/.herdr/docs`. */
    suspend fun list(): List<DocEntry> {
        val r = runner.run(DocsCommands.list(), 30)
        if (r.exitStatus == null && r.stdout.isEmpty()) throw IOException("The machine took too long to answer")
        if (r.exitStatus != 0 && r.stdout.isBlank()) throw IOException(reason(r, "Couldn't list the documents"))
        return parseList(r.stdout)
    }

    /**
     * The document at [path], or only its stamp where it is still the one read at [since]. A file that is there but
     * can't be shown is a [DocUnreadable] with its stamp.
     */
    suspend fun read(path: String, since: String? = null): DocRead {
        val r = runner.run(DocsCommands.read(path, since), 60)
        if (r.exitStatus == null && r.stdout.isEmpty()) throw IOException("The machine took too long to answer")
        // From the mark on: a login script may have printed something before it.
        val at = r.stdout.indexOf(DocsCommands.MARK).takeIf { it == 0 || it > 0 && r.stdout[it - 1] == '\n' }
            ?: throw IOException(reason(r, "Couldn't read ${path.substringAfterLast('/')}"))
        val text = r.stdout.substring(at)
        val words = text.substringBefore('\n').removePrefix(DocsCommands.MARK).split(' ')
        val mtime = words.getOrNull(0)?.toLongOrNull() ?: 0
        val size = words.getOrNull(1)?.toLongOrNull() ?: 0
        val stamp = "$mtime $size"
        if (stamp == since) return DocRead(mtime, stamp, null)
        if (size > DocsCommands.MAX_BYTES) throw DocUnreadable(stamp, "It is ${size / (1024 * 1024)} MB, too large to read on the phone")
        val doc = try {
            HerdrDocs.parse(text.substringAfter('\n', ""))
        } catch (e: IOException) {
            throw DocUnreadable(stamp, e.message ?: "It isn't a herdr.doc")
        }
        return DocRead(mtime, stamp, doc)
    }

    companion object {
        /** [DocsCommands.list]'s output: each mark and the head after it, as an entry. */
        fun parseList(text: String): List<DocEntry> {
            val out = mutableListOf<DocEntry>()
            var mark: String? = null
            val head = StringBuilder()
            fun flush() {
                val m = mark ?: return
                val words = m.removePrefix(DocsCommands.MARK).split(' ', limit = 3)
                val path = words.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return
                out += HerdrDocs.entry(path, words[0].toLongOrNull() ?: 0, head.toString())
            }
            for (line in text.lineSequence()) {
                if (line.startsWith(DocsCommands.MARK)) {
                    flush()
                    mark = line
                    head.setLength(0)
                } else if (mark != null) head.append(line).append('\n')
            }
            flush()
            return out
        }

        private fun reason(r: CommandResult, fallback: String) =
            r.stderr.trim().lines().lastOrNull { it.isNotBlank() } ?: fallback
    }
}
