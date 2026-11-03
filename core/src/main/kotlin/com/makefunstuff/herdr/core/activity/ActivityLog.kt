package com.makefunstuff.herdr.core.activity

import com.makefunstuff.herdr.core.chat.ToolSummary
import com.makefunstuff.herdr.core.chat.arr
import com.makefunstuff.herdr.core.chat.bool
import com.makefunstuff.herdr.core.chat.isInjected
import com.makefunstuff.herdr.core.chat.obj
import com.makefunstuff.herdr.core.chat.str
import com.makefunstuff.herdr.core.chat.textOf
import com.makefunstuff.herdr.core.chat.TranscriptJson
import com.makefunstuff.herdr.core.code.FileChange
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/**
 * One agent session's file read as turns of timed steps, a line at a time so a file still being
 * written can be followed. The harnesses keep what each step was, when it started and ended, how it
 * went (an exit status, an error) and what an edit changed; lines this doesn't understand are skipped.
 */
abstract class ActivityLog(private val prefix: String = "") {
    private class Building(val id: String, val prompt: String, val text: String, val from: Origin, val at: Long, var end: Long, var agent: String) {
        val steps = ArrayList<Step>()
    }

    private val turns = ArrayList<Building>()
    private val where = HashMap<String, Pair<Building, Int>>()
    private var version = 0L

    /** Who the session is on the board, from its first message from the board; null when it didn't say. */
    var role: Role? = null
        private set

    /** The folder the agent works in, to show paths in it short. */
    protected var cwd: String = ""

    /** The harness and model of the turns from here on (`claude/claude-opus-5-5`). */
    protected var model: String = ""
        set(value) {
            field = value
            turns.lastOrNull()?.let { if (it.agent.isEmpty()) it.agent = value }
        }

    /** Changes each time the turns change, so a reader knows when to publish. */
    val revision: Long get() = version

    fun feed(line: String) {
        if (line.isBlank() || line[0] != '{') return
        val obj = runCatching { TranscriptJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        runCatching { read(obj) }
    }

    protected abstract fun read(o: JsonObject)

    fun turns(fallback: Role = Role.Agent): List<Turn> {
        val r = role ?: fallback
        return turns.map { Turn(it.id, it.prompt, it.text, it.from, it.at, maxOf(it.end, it.at), it.steps.toList(), r, it.agent) }
    }

    /** A new turn, started by [text] (a message from you, the board or a background job). */
    protected fun turn(id: String, text: String, at: Long) {
        val reading = Prompts.read(text)
        if (role == null && reading.role != null) role = reading.role
        turns.lastOrNull()?.let { settle(it) }
        turns += Building(prefix + id, reading.title, Prompts.unwrap(text), reading.origin, at, at, model)
        version++
    }

    protected fun step(s: Step) {
        val t = turns.lastOrNull() ?: Building(prefix + "start", "Earlier in this session", "", Origin.Agent, s.at, s.at, model).also { turns += it }
        t.steps += s.copy(id = prefix + s.id)
        where[s.id] = t to t.steps.lastIndex
        extend(t, maxOf(s.at, s.end))
        version++
    }

    protected fun has(id: String): Boolean = id in where

    protected fun stepOf(id: String): Step? = where[id]?.let { (t, i) -> t.steps[i] }

    /** Changes the step [id] (a tool call that just came back). */
    protected fun update(id: String, change: (Step) -> Step) {
        val (t, i) = where[id] ?: return
        val s = change(t.steps[i])
        t.steps[i] = s
        extend(t, maxOf(s.at, s.end))
        version++
    }

    /** The tool call [id] came back: when, whether it failed, its exit status and what it printed. */
    protected fun finish(id: String, end: Long, failed: Boolean, exit: Int? = null, output: String? = null, change: (Step) -> Step = { it }) {
        update(id) { s ->
            change(
                s.copy(
                    end = if (end > 0) end else s.end,
                    state = if (failed) StepState.Failed else StepState.Done,
                    exit = exit ?: s.exit,
                    output = Tools.clip(output) ?: s.output,
                ),
            )
        }
    }

    /** Every step still running in the newest turn is over: it was interrupted, or the turn moved on. */
    protected fun settle() {
        turns.lastOrNull()?.let { settle(it) }
        version++
    }

    private fun settle(t: Building) {
        for (i in t.steps.indices) if (t.steps[i].state == StepState.Running) t.steps[i] = t.steps[i].copy(state = StepState.Done)
    }

    /** A line came at [at]: the newest turn lasted at least until then. */
    protected fun touch(at: Long) {
        turns.lastOrNull()?.let { extend(it, at) }
    }

    private fun extend(t: Building, at: Long) {
        if (at > t.end) t.end = at
    }

    protected fun short(path: String): String {
        val p = path.removePrefix("file://")
        val base = cwd.trimEnd('/')
        return if (base.isNotEmpty() && p.startsWith("$base/")) p.substring(base.length + 1) else p
    }

    protected fun notice(id: String, text: String, at: Long, failed: Boolean = false) =
        step(Step(id, StepKind.Notice, text, at, at, if (failed) StepState.Failed else StepState.Done))

    companion object {
        /** The reader for an agent kind herdr or the board names, or null when the app can't read its sessions. */
        fun forAgent(agent: String, prefix: String = ""): ActivityLog? = when (agent.substringBefore('/')) {
            "claude" -> ClaudeActivity(prefix)
            "codex" -> CodexActivity(prefix)
            "pi", "omp" -> PiActivity(prefix)
            else -> null
        }

        internal fun time(iso: String?): Long = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0

        internal fun firstLine(text: String): String = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(200)
    }
}

internal fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.doubleOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.intOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.longOrNull

/**
 * Claude Code's session file (`~/.claude/projects/<cwd>/<session>.jsonl`). [sidechain]: a subagent's
 * own file, whose every line is marked as a side chain; a session file skips those.
 */
class ClaudeActivity(prefix: String = "", private val sidechain: Boolean = false) : ActivityLog(prefix) {
    private val inputs = HashMap<String, JsonObject>()

    override fun read(o: JsonObject) {
        if (o.bool("isSidechain") && !sidechain) return
        val at = time(o.str("timestamp"))
        o.str("cwd")?.let { cwd = it }
        val uuid = o.str("uuid") ?: return
        when (o.str("type")) {
            "user" -> if (!o.bool("isMeta")) user(uuid, o, at)
            "assistant" -> assistant(uuid, o.obj("message") ?: return, at)
            "system" -> when (o.str("subtype")) {
                "compact_boundary" -> notice(uuid, "Conversation compacted", at)
                "api_error" -> notice(uuid, "API error" + (o.str("content")?.let { ": " + firstLine(it) } ?: ""), at, failed = true)
            }
        }
        touch(at)
    }

    private fun user(uuid: String, o: JsonObject, at: Long) {
        val message = o.obj("message") ?: return
        when (val content = message["content"]) {
            is JsonPrimitive -> prompt(uuid, content.contentOrNull.orEmpty(), at)
            is JsonArray -> {
                val blocks = content.filterIsInstance<JsonObject>()
                val results = blocks.filter { it.str("type") == "tool_result" }
                for (b in results) result(b, (o["toolUseResult"] as? JsonObject)?.takeIf { results.size == 1 }, at)
                val text = blocks.filter { it.str("type") == "text" }.mapNotNull { it.str("text") }.joinToString("\n")
                if (text.isNotBlank()) prompt(uuid, text, at)
            }
            else -> {}
        }
    }

    private val tag = Regex("<([a-z-]+)>([\\s\\S]*?)</\\1>")

    private fun prompt(uuid: String, raw: String, at: Long) {
        val text = raw.trim()
        when {
            text.startsWith("[Request interrupted") -> { settle(); notice(uuid, "Interrupted", at) }
            text.startsWith("<command-name>") || text.startsWith("<command-message>") -> {
                val tags = tag.findAll(text).associate { it.groupValues[1] to it.groupValues[2].trim() }
                val name = tags["command-name"] ?: return
                turn(uuid, (name + " " + tags["command-args"].orEmpty()).trim(), at)
            }
            text.startsWith("<bash-input>") -> {
                val cmd = tag.find(text)?.groupValues?.get(2)?.trim().orEmpty()
                turn(uuid, "! $cmd", at)
                step(Step("$uuid/cmd", Tools.kind("bash", cmd), Tools.commandTitle(cmd), at, at, StepState.Running, detail = cmd, tool = "!"))
                lastBang = "$uuid/cmd"
            }
            text.startsWith("<bash-stdout>") || text.startsWith("<bash-stderr>") -> {
                val parts = tag.findAll(text).associate { it.groupValues[1] to it.groupValues[2].trim() }
                lastBang?.let { id ->
                    val err = parts["bash-stderr"].orEmpty()
                    finish(id, at, failed = err.isNotEmpty() && parts["bash-stdout"].isNullOrEmpty(), output = listOf(parts["bash-stdout"].orEmpty(), err).filter { it.isNotEmpty() }.joinToString("\n"))
                }
                lastBang = null
            }
            text.startsWith("<local-command-stdout>") || text.startsWith("<local-command-stderr>") -> {}
            text.startsWith("<task-notification>") -> turn(uuid, text, at)
            isInjected(text) -> {}
            text.isNotEmpty() -> turn(uuid, text, at)
        }
    }

    private var lastBang: String? = null

    private fun assistant(uuid: String, message: JsonObject, at: Long) {
        message.str("model")?.takeIf { it.isNotEmpty() && !it.startsWith("<") }?.let { model = "claude/$it" }
        val content = message["content"] as? JsonArray ?: return
        content.filterIsInstance<JsonObject>().forEachIndexed { i, block ->
            when (block.str("type")) {
                "text" -> block.str("text")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    step(Step("$uuid/$i", StepKind.Message, firstLine(it), at, at, detail = it))
                }
                "tool_use" -> {
                    val id = block.str("id") ?: return@forEachIndexed
                    val name = block.str("name").orEmpty()
                    val input = block.obj("input") ?: JsonObject(emptyMap())
                    inputs[id] = input
                    step(call(id, name, input, at))
                }
            }
        }
    }

    private fun call(id: String, name: String, input: JsonObject, at: Long): Step {
        val command = input.str("command")
        val kind = Tools.kind(name, command)
        val path = (input.str("file_path") ?: input.str("notebook_path") ?: input.str("path"))?.let(::short)
        val title = when {
            command != null -> input.str("description")?.takeIf { it.isNotBlank() } ?: Tools.commandTitle(command)
            name == "Read" && path != null -> path + lines(input)
            name == "Grep" -> "“${input.str("pattern").orEmpty()}”" + (path?.let { " in $it" } ?: "")
            name == "Glob" -> input.str("pattern").orEmpty()
            path != null -> path
            name == "WebFetch" -> input.str("url").orEmpty()
            name == "TodoWrite" -> "Updated the plan"
            name == "ExitPlanMode" -> "Proposed a plan"
            name == "Task" || name == "Agent" -> "Subagent: " + (input.str("description") ?: input.str("prompt")?.let(::firstLine)).orEmpty()
            else -> ToolSummary.of(name, input).ifEmpty { name }
        }
        val detail = when {
            command != null -> command
            name == "TodoWrite" -> input.arr("todos")?.filterIsInstance<JsonObject>()?.joinToString("\n") { todo(it.str("status"), it.str("content")) }
            name == "ExitPlanMode" -> input.str("plan")
            else -> ToolSummary.detail(input)
        }
        return Step(id, kind, title, at, at, StepState.Running, detail = detail, path = path, tool = name, note = if (input.bool("run_in_background")) "in the background" else null)
    }

    private fun lines(input: JsonObject): String {
        val offset = input.int("offset") ?: return ""
        val limit = input.int("limit")
        return if (limit != null) " · lines $offset–${offset + limit - 1}" else " · from line $offset"
    }

    private fun result(block: JsonObject, extra: JsonObject?, at: Long) {
        val id = block.str("tool_use_id") ?: return
        val failed = block.bool("is_error")
        val output = textOf(block["content"]).ifBlank { null } ?: extra?.str("stdout")
        val step = stepOf(id) ?: return
        val shell = step.kind == StepKind.Command || step.kind == StepKind.Test
        val exit = if (!shell) null else if (failed) Tools.exitIn(output) else if (extra?.str("backgroundTaskId") != null) null else 0
        finish(id, at, failed, exit, output) { s ->
            val path = extra?.str("filePath")?.let(::short) ?: s.path
            val diff = if (failed || s.kind != StepKind.Edit || path == null) null else diff(path, extra, inputs[id])
            s.copy(
                path = path,
                diff = diff ?: s.diff,
                note = when {
                    extra?.str("backgroundTaskId") != null -> "in the background"
                    s.kind == StepKind.Test -> Tools.testSummary(output)
                    s.kind == StepKind.Edit && diff != null -> "+${diff.added} −${diff.removed}"
                    else -> s.note
                },
            )
        }
    }

    private fun diff(path: String, extra: JsonObject?, input: JsonObject?): com.makefunstuff.herdr.core.code.FileDiff? {
        val patch = extra?.arr("structuredPatch")?.filterIsInstance<JsonObject>().orEmpty().map { h ->
            ((h.int("oldStart") ?: 0) to (h.int("newStart") ?: 0)) to h.arr("lines").orEmpty().mapNotNull { it.str() }
        }
        val created = extra?.str("type") == "create"
        if (patch.isNotEmpty()) return Diffs.hunks(path, patch, if (created) FileChange.Added else FileChange.Modified)
        if (created) extra?.str("content")?.let { return Diffs.added(path, it) }
        input?.str("content")?.let { return Diffs.added(path, it) }
        val old = input?.str("old_string")
        val new = input?.str("new_string")
        if (old != null || new != null) return Diffs.replaced(path, old.orEmpty(), new.orEmpty())
        val edits = input?.arr("edits")?.filterIsInstance<JsonObject>().orEmpty()
        if (edits.isNotEmpty()) return Diffs.replaced(path, edits.map { it.str("old_string").orEmpty() to it.str("new_string").orEmpty() })
        return null
    }
}

internal fun todo(status: String?, content: String?): String =
    (when (status) { "completed" -> "☑ "; "in_progress" -> "▸ "; else -> "☐ " }) + content.orEmpty()

/**
 * Codex's rollout file (`~/.codex/sessions/YYYY/MM/DD/rollout-…-<id>.jsonl`): completed UI items
 * (`item_completed`, with their start and end) in recent versions, raw function calls in older ones.
 */
class CodexActivity(prefix: String = "") : ActivityLog(prefix) {
    private var itemMode = false
    private var seq = 0

    override fun read(o: JsonObject) {
        val at = time(o.str("timestamp"))
        val payload = o.obj("payload") ?: return
        when (o.str("type")) {
            "session_meta" -> payload.str("cwd")?.let { cwd = it }
            "turn_context" -> {
                payload.str("cwd")?.let { cwd = it }
                payload.str("model")?.let { model = "codex/$it" }
            }
            "event_msg" -> when (payload.str("type")) {
                "item_completed" -> {
                    itemMode = true
                    val start = payload.long("started_at_ms") ?: at
                    val end = payload.long("completed_at_ms") ?: at
                    item(payload.obj("item") ?: return, start, end)
                }
                "user_message" -> if (!itemMode) {
                    val text = payload.str("message")?.trim().orEmpty()
                    if (text.isNotEmpty() && !isInjected(text)) turn("u${seq++}", text, at)
                }
                "agent_message" -> if (!itemMode) {
                    payload.str("message")?.trim()?.takeIf { it.isNotEmpty() }?.let { step(Step("a${seq++}", StepKind.Message, firstLine(it), at, at, detail = it)) }
                }
                "task_complete" -> {
                    val error = payload.obj("error")?.str("message") ?: payload.str("error")
                    if (!error.isNullOrBlank()) notice("err${seq++}", firstLine(error), at, failed = true)
                }
                "turn_aborted" -> { settle(); notice("abort${seq++}", "Interrupted", at) }
                "exec_command_end" -> if (!itemMode) payload.str("call_id")?.let { id ->
                    val exit = payload.int("exit_code")
                    finish(id, at, exit != null && exit != 0, exit, payload.str("aggregated_output") ?: payload.str("stdout"))
                }
            }
            "response_item" -> if (!itemMode) legacy(payload, at)
            "compacted" -> notice("compact${seq++}", "Conversation compacted", at)
        }
        touch(at)
    }

    private fun item(item: JsonObject, start: Long, end: Long) {
        val id = item.str("id") ?: "i${seq++}"
        val failed = item.str("status") == "failed"
        val state = if (failed) StepState.Failed else StepState.Done
        when (item.str("type")) {
            "UserMessage" -> {
                val text = item.arr("content").orEmpty().filterIsInstance<JsonObject>().mapNotNull { it.str("text") }.joinToString("\n").trim()
                if (text.isNotEmpty() && !isInjected(text)) turn(id, text, start)
            }
            "AgentMessage" -> textOf(item["content"]).trim().takeIf { it.isNotEmpty() }?.let { step(Step(id, StepKind.Message, firstLine(it), start, end, detail = it)) }
            "CommandExecution" -> {
                val command = commandLine(item["command"])
                val exit = item.int("exit_code")
                val output = item.str("aggregated_output") ?: item.str("formatted_output") ?: item.str("stdout")
                val parsed = item.arr("parsed_cmd").orEmpty().filterIsInstance<JsonObject>()
                val types = parsed.mapNotNull { it.str("type") }.toSet()
                val reads = types.isNotEmpty() && types.all { it == "read" || it == "search" || it == "list_files" }
                val kind = if (reads) StepKind.Read else Tools.kind("shell", command)
                val path = parsed.firstNotNullOfOrNull { it.str("path") }?.takeIf { reads }?.let(::short)
                val title = if (reads && path != null && types == setOf("read")) {
                    parsed.mapNotNull { it.str("path")?.let(::short) }.joinToString(", ")
                } else Tools.commandTitle(command)
                step(
                    Step(
                        id, kind, title, start, end, if (failed || (exit != null && exit != 0)) StepState.Failed else StepState.Done,
                        exit = exit, detail = command, output = Tools.clip(output), path = path, tool = "exec_command",
                        note = if (kind == StepKind.Test) Tools.testSummary(output) else null,
                    ),
                )
            }
            "FileChange" -> {
                val changes = item.obj("changes") ?: return
                for ((file, change) in changes) {
                    val c = change as? JsonObject ?: continue
                    val path = short(file)
                    val diff = Diffs.codexChange(path, c)
                    val word = when (c.str("type")) { "add" -> "Added "; "delete" -> "Deleted "; else -> "" }
                    step(Step("$id/$file", StepKind.Edit, word + path, start, end, state, path = path, diff = diff, tool = "apply_patch", note = diff?.let { "+${it.added} −${it.removed}" }))
                }
            }
            "McpToolCall" -> {
                val args = item.obj("arguments") ?: JsonObject(emptyMap())
                val tool = item.str("tool").orEmpty()
                step(
                    Step(
                        id, Tools.kind(tool), "${item.str("server")}: " + ToolSummary.of(tool, args).ifEmpty { tool }, start, end, state,
                        detail = ToolSummary.detail(args), output = Tools.clip(textOf(item.obj("result")?.get("content"))), tool = "${item.str("server")}.$tool",
                    ),
                )
            }
            "WebSearch" -> step(Step(id, StepKind.Web, item.str("query").orEmpty().ifEmpty { "Searched the web" }, start, end, state, tool = "web_search"))
            "ImageView" -> item.str("path")?.let { step(Step(id, StepKind.Read, short(it), start, end, state, path = short(it), tool = "view_image")) }
            "TodoList", "Plan", "PlanUpdate" -> step(
                Step(
                    id, StepKind.Plan, "Updated the plan", start, end,
                    detail = (item.arr("items") ?: item.arr("plan")).orEmpty().filterIsInstance<JsonObject>().joinToString("\n") {
                        todo(it.str("status")?.let { s -> if (s == "completed" || s == "done") "completed" else s }, it.str("text") ?: it.str("step"))
                    },
                ),
            )
            "ContextCompaction" -> notice(id, "Conversation compacted", start)
        }
    }

    private fun legacy(payload: JsonObject, at: Long) {
        when (payload.str("type")) {
            "function_call", "custom_tool_call" -> {
                val callId = payload.str("call_id") ?: return
                val name = payload.str("name").orEmpty()
                val raw = payload.str("arguments") ?: payload.str("input").orEmpty()
                val args = runCatching { TranscriptJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                val command = args?.let { (it["command"] ?: it["cmd"])?.let(::commandLine) }
                val kind = if (name == "apply_patch") StepKind.Edit else Tools.kind(name, command)
                val title = when {
                    command != null -> Tools.commandTitle(command)
                    name == "update_plan" -> "Updated the plan"
                    name == "apply_patch" -> PATCH_FILE.findAll(raw).map { short(it.groupValues[2]) }.joinToString(", ").ifEmpty { "Edited files" }
                    args == null -> firstLine(raw)
                    else -> ToolSummary.of(name, args)
                }
                val detail = when {
                    name == "update_plan" -> args?.arr("plan")?.filterIsInstance<JsonObject>()?.joinToString("\n") { todo(it.str("status"), it.str("step")) }
                    command != null -> command
                    else -> Tools.clip(raw)
                }
                step(Step(callId, kind, title, at, at, StepState.Running, detail = detail, tool = name))
            }
            "function_call_output", "custom_tool_call_output" -> {
                val callId = payload.str("call_id") ?: return
                val output = payload["output"]
                val inner = (output as? JsonPrimitive)?.contentOrNull?.let { o -> runCatching { TranscriptJson.parseToJsonElement(o).jsonObject }.getOrNull() }
                val text = inner?.str("output") ?: (output as? JsonPrimitive)?.contentOrNull ?: textOf(output)
                val exit = inner?.obj("metadata")?.int("exit_code") ?: Tools.exitIn(text)
                val took = inner?.obj("metadata")?.num("duration_seconds")
                finish(callId, at, exit != null && exit != 0, exit, text) { s ->
                    s.copy(at = if (took != null) at - (took * 1000).toLong() else s.at, note = if (s.kind == StepKind.Test) Tools.testSummary(text) else s.note)
                }
            }
        }
    }

    private val PATCH_FILE = Regex("""\*\*\* (Add|Update|Delete) File: (.+)""")

    private fun commandLine(element: JsonElement?): String = when (element) {
        is JsonArray -> {
            val parts = element.mapNotNull { it.str() }
            // ["bash", "-lc", "<script>"] → the script.
            if (parts.size >= 3 && parts[parts.size - 2].startsWith("-") && parts[parts.size - 2].endsWith("c")) parts.last() else parts.joinToString(" ")
        }
        else -> element.str().orEmpty()
    }
}

/** pi's and omp's session file (`~/.pi/agent/sessions/<cwd>/<time>_<id>.jsonl`). */
class PiActivity(prefix: String = "") : ActivityLog(prefix) {
    private val starts = HashMap<String, Long>()

    /** What a `write` call wrote, for its diff: pi's result has none. */
    private val contents = HashMap<String, String>()

    override fun read(o: JsonObject) {
        val at = time(o.str("timestamp"))
        when (o.str("type")) {
            "session" -> o.str("cwd")?.let { cwd = it }
            "model_change" -> o.str("model")?.let { model = "pi/$it" }
            "message" -> message(o.str("id") ?: return, o.obj("message") ?: return, at)
            "compaction" -> notice(o.str("id") ?: return, "Conversation compacted", at)
            "custom" -> if (o.str("customType") == "tool_execution_start") {
                val data = o.obj("data") ?: return
                val id = data.str("toolCallId") ?: return
                val start = time(data.str("startedAt")).takeIf { it > 0 } ?: at
                starts[id] = start
                update(id) { it.copy(at = start) }
            }
        }
        touch(at)
    }

    private fun message(id: String, m: JsonObject, at: Long) {
        val when_ = m.long("timestamp")?.takeIf { it > 0 } ?: at
        when (m.str("role")) {
            "user" -> {
                val text = textOf(m["content"]).trim()
                if (text.isNotEmpty() && !isInjected(text)) turn(id, text, when_)
            }
            "assistant" -> {
                val provider = m.str("provider")
                m.str("model")?.let { model = "pi/" + (provider?.let { p -> "$p/" }.orEmpty()) + it }
                (m["content"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEachIndexed { i, block ->
                    when (block.str("type")) {
                        "text" -> block.str("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { step(Step("$id/$i", StepKind.Message, firstLine(it), when_, when_, detail = it)) }
                        "toolCall" -> {
                            val callId = block.str("id") ?: return@forEachIndexed
                            step(call(callId, block.str("name").orEmpty(), block.obj("arguments") ?: JsonObject(emptyMap()), starts[callId] ?: when_))
                        }
                    }
                }
                when (m.str("stopReason")) {
                    "aborted" -> { settle(); notice("$id/abort", "Interrupted", when_) }
                    "error" -> notice("$id/error", m.str("errorMessage")?.let(::firstLine) ?: "The agent hit an error", when_, failed = true)
                }
            }
            "toolResult" -> {
                val callId = m.str("toolCallId") ?: return
                val failed = m.bool("isError")
                val details = m.obj("details")
                val output = textOf(m["content"]).ifBlank { null }
                val step = stepOf(callId) ?: return
                val shell = step.kind == StepKind.Command || step.kind == StepKind.Test
                val exit = details?.int("exitCode") ?: if (!shell) null else if (failed) Tools.exitIn(output) else 0
                val wall = details?.num("wallTimeMs")
                finish(callId, when_, failed, exit, output) { s ->
                    val diff = if (failed || s.kind != StepKind.Edit) null else s.path?.let { p ->
                        details?.str("diff")?.let { Diffs.pi(p, it) } ?: contents[callId]?.let { Diffs.added(p, it) }
                    }
                    s.copy(
                        at = if (wall != null && callId !in starts) when_ - wall.toLong() else s.at,
                        diff = diff ?: s.diff,
                        note = when {
                            s.kind == StepKind.Test -> Tools.testSummary(output)
                            diff != null -> "+${diff.added} −${diff.removed}"
                            else -> s.note
                        },
                    )
                }
            }
            "bashExecution" -> {
                val command = m.str("command").orEmpty()
                val exit = m.int("exitCode")
                step(
                    Step(
                        id, Tools.kind("bash", command), Tools.commandTitle(command), when_, when_, if (exit != null && exit != 0) StepState.Failed else StepState.Done,
                        exit = exit, detail = command, output = Tools.clip(m.str("output")), tool = "!",
                    ),
                )
            }
        }
    }

    private fun call(id: String, name: String, args: JsonObject, at: Long): Step {
        val command = args.str("command") ?: args.str("cmd")
        val kind = Tools.kind(name, command)
        val path = (args.str("path") ?: args.str("file_path") ?: args.str("file"))?.substringBefore(':')?.let(::short)
        val intent = args.str("i")?.takeIf { it.isNotBlank() }
        if (kind == StepKind.Edit) args.str("content")?.let { contents[id] = it }
        val title = when {
            command != null -> intent ?: Tools.commandTitle(command)
            name == "todo" -> "Updated the plan"
            kind == StepKind.Read && name == "read" && path != null -> path
            kind == StepKind.Edit && path != null -> path
            else -> intent ?: ToolSummary.of(name, args).ifEmpty { name }
        }
        val detail = when {
            command != null -> command
            name == "todo" -> args.arr("list")?.filterIsInstance<JsonObject>()?.flatMap { ph -> ph.arr("items").orEmpty().mapNotNull { it.str() } }?.joinToString("\n") { "☐ $it" }
            else -> ToolSummary.detail(args)
        }
        return Step(id, kind, title, at, at, StepState.Running, detail = detail, path = path, tool = name)
    }
}
