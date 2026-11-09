package com.makefunstuff.herdr.core.shp

import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.ssh.HerdrCommands
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import com.makefunstuff.herdr.core.ssh.MachineConnection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/**
 * simple-herdr-projects (`shp`) over a machine's SSH connection: no service, no port, no token.
 * `shp list --json` and `shp show` for the board, `shp accept --yes` to accept, `shp say` to answer
 * an ask, and `hc read`/`hc send` (through `shp say`) for a task's chat.
 */
object ShpCommands {
    /** A space's name, before its tasks in [list]'s output. */
    const val SPACE_MARK = "@@shp-space "

    /** A task's space and id, before the list: the store's files say where each task is. */
    const val TASK_MARK = "@@shp-task "

    /** Where [list]'s `shp list --json` starts. */
    const val LIST_MARK = "@@shp-list"

    /** Runs [command] in the machine's login shell, where `shp` and `hc` are on the PATH. */
    private fun login(command: String) = HerdrCommands.login(command)

    /** Prints the path of shp where the machine has it, nothing where it doesn't. */
    fun present(): String = login("command -v shp 2>/dev/null || true")

    /**
     * Every space in shp's store, each task's space, and all of them from one `shp list --json`.
     * The store is plain files (`~/.herdr/simple/spaces/<name>.md`,
     * `spaces/<name>/<ID>.md`), so where a task lives comes from its path; a `list` without a
     * space wouldn't say which space a task is in.
     */
    fun list(): String = login(
        "for f in \"\$HOME/.herdr/simple/spaces\"/*.md; do [ -f \"\$f\" ] || continue; " +
            "n=\"\${f##*/}\"; n=\"\${n%.md}\"; printf '$SPACE_MARK%s\\n' \"\$n\"; done; " +
            "for f in \"\$HOME/.herdr/simple/spaces\"/*/*.md; do [ -f \"\$f\" ] || continue; " +
            "d=\"\${f%/*}\"; b=\"\${f##*/}\"; printf '$TASK_MARK%s %s\\n' \"\${d##*/}\" \"\${b%.md}\"; done; " +
            "printf '$LIST_MARK\\n'; shp list --json",
    )

    /** A task's header (`--json`) and then the whole file, whose body is its brief. */
    fun show(id: String) = login("shp show --json ${quote(id)}; printf '\\n@@shp-file@@\\n'; shp show ${quote(id)}")

    /** Accepts a ready task, which merges it or opens/merges its pull request (the app confirms first). */
    fun accept(id: String) = login("shp accept --yes ${quote(id)}")

    /** Sends a person's words to a task's worker; [ask] closes that ask on a confirmed send. */
    fun say(id: String, text: String, ask: String? = null) =
        // Options first and then `--`: a message that starts with `-` (a list, `-1`, `--help`) stays the message.
        login("shp say" + (ask?.let { " --ask ${quote(it)}" } ?: "") + " -- ${quote(id)} ${quote(text)}")

    /** The task's live worker terminal, by the labels shp puts on it. */
    fun worker(id: String) = login("hc ls --json --label herdr.role=worker --label herdr.task=${quote(id)}")

    /** The worker's screen and history, as text. */
    fun transcript(terminal: String, lines: Int = 200) = login("hc read --json --lines $lines ${quote(terminal)}")
}

/** Runs one of [ShpCommands] on the machine; the app's is its SSH connection, tests give their own. */
fun interface ShpRunner {
    suspend fun run(command: String, stdin: String?, timeoutSeconds: Long): CommandResult
}

/**
 * A task's header, as `shp list --json` and `shp show --json` print it (docs/design.md §1). Only the
 * fields the phone shows; a key the design may add later is skipped.
 */
@Serializable
data class ShpTask(
    val id: String = "",
    val title: String = "",
    val status: String = "todo",
    val agent: List<String>? = null,
    val branch: String = "",
    val worktree: String = "",
    val review: Int? = null,
    @SerialName("reviewed_commit") val reviewedCommit: String? = null,
    val blocking: Int? = null,
    val asks: List<ShpAsk>? = null,
    val summary: String? = null,
    val proof: String? = null,
    val pr: String? = null,
    val progress: Int? = null,
    val step: String? = null,
    val left: String? = null,
) {
    val askList: List<ShpAsk> get() = asks.orEmpty()
}

/** One thing a task's worker asks the person, closed only by `shp say` or a confirmed `hc send`. */
@Serializable
data class ShpAsk(val id: String = "", val text: String = "", val time: String = "")

/** A task's file: its [head] and the Markdown [brief] below the header. */
data class ShpTaskFile(val head: ShpTask, val brief: String)

/** simple-herdr-projects on a machine, through [runner]. */
class ShpClient(private val runner: ShpRunner) {
    /** The app's client: every command on the machine's current SSH connection. */
    constructor(connect: suspend () -> MachineConnection) : this(
        ShpRunner { command, stdin, seconds ->
            val conn = connect()
            if (stdin == null) conn.run(command, seconds) else conn.runWithInput(command, stdin.byteInputStream(), seconds)
        },
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Every space with its tasks, from one `shp list --json` and the store's own paths. A task whose file
     * went between the two reads is left out; a bad file is named by shp on stderr and the rest still read.
     */
    suspend fun spaces(): List<Pair<String, List<ShpTask>>> {
        val result = runner.run(ShpCommands.list(), null, 45)
        val text = result.stdout
        val names = mutableListOf<String>()
        val places = mutableMapOf<String, String>()
        val list = StringBuilder()
        var listing = false
        for (line in text.lines()) {
            when {
                line.startsWith(ShpCommands.LIST_MARK) -> listing = true
                listing -> list.append(line).append('\n')
                line.startsWith(ShpCommands.SPACE_MARK) ->
                    names += line.removePrefix(ShpCommands.SPACE_MARK).trim().takeIf { it.isNotEmpty() }.orEmpty()
                line.startsWith(ShpCommands.TASK_MARK) -> {
                    val (space, id) = line.removePrefix(ShpCommands.TASK_MARK).trim().split(' ', limit = 2).let { it.firstOrNull().orEmpty() to it.getOrNull(1).orEmpty() }
                    if (space.isNotEmpty() && id.isNotEmpty()) places[id] = space
                }
            }
        }
        // The marks always print, so a shp that failed (missing, crashed) shows as its empty list: that's its refusal.
        if (result.exitStatus != 0 && list.isBlank()) throw IOException(reason(result))
        val tasks = parseList(list.toString().trim())
        return names.filter { it.isNotEmpty() }.distinct().map { name -> name to tasks.filter { places[it.id] == name } }
    }

    /** A task's header and brief; shp's refusal where it has no task. */
    suspend fun show(id: String): ShpTaskFile {
        val result = runner.run(ShpCommands.show(id), null, 30)
        val header = result.stdout.substringBefore("@@shp-file@@").trim()
        val head = try {
            json.decodeFromString(ShpTask.serializer(), header)
        } catch (e: Exception) {
            throw IOException(if (result.exitStatus != 0) reason(result) else "shp show printed no task for $id")
        }
        return ShpTaskFile(head, brief(result.stdout.substringAfter("@@shp-file@@", "")))
    }

    /** Accepts [id], which merges it; shp's refusal when it won't. */
    suspend fun accept(id: String): String {
        val result = runner.run(ShpCommands.accept(id), null, 120)
        if (result.exitStatus != 0) throw IOException(reason(result))
        return result.stdout.trim().ifEmpty { "$id accepted" }
    }

    /** Sends [text] to [id]'s worker through `hc send`; [ask] closes that ask only once shp confirms the send. */
    suspend fun say(id: String, text: String, ask: String? = null): String {
        val result = runner.run(ShpCommands.say(id, text, ask), null, 60)
        if (result.exitStatus != 0) throw IOException(reason(result))
        return result.stdout.trim()
    }

    /** The task's live worker terminal, where it has one now. */
    suspend fun worker(id: String): String? {
        val text = ok(runner.run(ShpCommands.worker(id), null, 20))
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return root["terminals"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["state"]?.jsonPrimitive?.contentOrNull == "live" }
            ?.get("address")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /** The worker terminal's screen and history as text. */
    suspend fun transcript(terminal: String, lines: Int = 200): String {
        val text = ok(runner.run(ShpCommands.transcript(terminal, lines), null, 30))
        return runCatching { json.parseToJsonElement(text).jsonObject["text"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?: throw IOException("hc read printed no screen")
    }

    /** A task's chat lines: its live worker's terminal, or none where it has no worker now. */
    suspend fun chatLines(id: String): List<String> {
        val terminal = worker(id) ?: return emptyList()
        return transcript(terminal).lines().dropLastWhile { it.isBlank() }
    }

    private fun parseList(text: String): List<ShpTask> {
        if (text.isEmpty()) return emptyList()
        return try {
            json.decodeFromString(ListSerializer(ShpTask.serializer()), text)
        } catch (e: Exception) {
            throw IOException("shp list printed something that isn't a task list")
        }
    }

    /** A command's output; its refusal where it printed none. */
    private fun ok(result: CommandResult): String {
        if (result.exitStatus != 0 && result.stdout.isBlank()) throw IOException(reason(result))
        return result.stdout
    }

    /** A refusal's one useful line: shp says why on stderr (`--json` refused, a task that moved). */
    private fun reason(result: CommandResult): String =
        result.stderr.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }
            ?: result.stdout.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }
            ?: "shp refused"

    companion object {
        /** The brief below a task file's header, with its leading blank line gone. */
        fun brief(file: String): String {
            val lines = file.trimStart('\n').lines()
            if (lines.firstOrNull()?.trim() != "---") return ""
            val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: return ""
            return lines.drop(end + 1).joinToString("\n").trim('\n')
        }

        /** Whether shp is on the machine. */
        suspend fun present(conn: MachineConnection): Boolean =
            runCatching { conn.run(ShpCommands.present(), 15).stdout.trim().isNotEmpty() }.getOrDefault(false)
    }
}
