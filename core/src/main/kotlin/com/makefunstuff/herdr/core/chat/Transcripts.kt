package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.activity.ActivityLog
import com.makefunstuff.herdr.core.activity.Diffs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Turns an agent's own JSONL transcript into chat items, one line at a time, so a file that is
 * still being written can be followed. Lines it doesn't understand are skipped.
 */
abstract class Transcript {
    private val entries = LinkedHashMap<String, ChatItem>()
    private var version = 0L

    /**
     * The same lines read as timed turns, when set: the chat's turn headers, times and edit counts
     * come from it, by the ids both readers give a tool call or a message.
     */
    var activity: ActivityLog? = null

    /** Changes each time the items (or the [activity]) change, so a follower knows when to publish. */
    val revision: Long get() = version + (activity?.revision ?: 0)

    fun items(): List<ChatItem> = entries.values.toList()

    /** The model the agent answers with and how full its context is, from the newest lines that say. */
    var use: ModelUse? = null
        private set

    protected fun model(model: String?, effort: String? = use?.effort) {
        val next = (use ?: ModelUse()).copy(model = model ?: use?.model, effort = effort)
        if (next != use) { use = next; version++ }
    }

    /**
     * Messages sent while the agent works, waiting for it to take them, oldest first: Claude Code
     * queues them and logs each as it goes in and out ([ClaudeTranscript]).
     */
    var queued: List<String> = emptyList()
        private set

    /** The agent's queue changed to [next]. */
    protected fun queue(next: List<String>) {
        if (next != queued) { queued = next; version++ }
    }

    /** A request filled [used] tokens of the context ([window] when the transcript states it). */
    protected fun filled(used: Long, window: Long? = null) {
        val next = ModelUse.filled(used, use?.model, window, use)
        if (next != use) { use = next; version++ }
    }

    fun feed(line: String) {
        activity?.feed(line)
        if (line.isBlank() || line[0] != '{') return
        val obj = runCatching { TranscriptJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        runCatching { read(obj) }
    }

    protected abstract fun read(obj: JsonObject)

    protected fun put(item: ChatItem) {
        entries[item.id] = item
        version++
    }

    /** Reasoning [text] as item [id], when there is any: a model that keeps it to itself leaves an empty block. */
    protected fun thought(id: String, text: String?) {
        text?.trim()?.takeIf { it.isNotEmpty() }?.let { put(ChatItem.Reasoning(id, it)) }
    }

    protected fun finishTool(id: String, output: String?, failed: Boolean) {
        val tool = entries[id] as? ChatItem.Tool ?: return
        entries[id] = tool.copy(output = output?.let(::clip), state = if (failed) ToolState.Failed else ToolState.Done)
        version++
    }

    /** Notes that tool call [id] started subagent [agent], when it is a call that can. */
    protected fun linkSubagent(id: String, agent: String?) {
        val tool = entries[id] as? ChatItem.Tool ?: return
        if (agent == null || !Subagents.isAgentTool(tool.name) || tool.subagent == agent) return
        entries[id] = tool.copy(subagent = agent, output = tool.output?.let(Subagents::withoutId)?.ifEmpty { null })
        version++
    }

    /** Marks every tool still running as done: the turn moved on, so it is no longer waiting. */
    protected fun settleTools() {
        for ((id, item) in entries) {
            if (item is ChatItem.Tool && item.state == ToolState.Running) entries[id] = item.copy(state = ToolState.Done)
        }
        version++
    }

    companion object {
        /** The parser for an agent kind herdr reports, or null when the app can't read its transcript. */
        fun forAgent(agent: String): Transcript? = when (agent) {
            "claude" -> ClaudeTranscript()
            "codex" -> CodexTranscript()
            "pi", "omp" -> PiTranscript()
            else -> null
        }

        private const val MAX_OUTPUT = 6_000

        internal fun clip(text: String): String =
            if (text.length <= MAX_OUTPUT) text else text.take(MAX_OUTPUT) + "\n… (${text.length - MAX_OUTPUT} more characters)"
    }
}

internal val TranscriptJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.str(key: String): String? = this[key].str()

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

/** A count, or 0 when it is missing, not a number or not above 0. */
internal fun JsonObject.count(key: String): Long =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }?.toLong() ?: 0

/** Text from a string or a list of `{type: text, text}` blocks. */
internal fun textOf(element: JsonElement?): String = when (element) {
    null, JsonNull -> ""
    is JsonPrimitive -> element.contentOrNull.orEmpty()
    is JsonArray -> element.mapNotNull { (it as? JsonObject)?.let { b -> b.str("text") } ?: it.str() }.joinToString("\n")
    is JsonObject -> element.str("text") ?: element["content"]?.let(::textOf).orEmpty()
}

/** Text the agent harness injects into the user turn, which the human never typed. */
private val INJECTED = listOf(
    "<system-reminder>", "<task-notification>", "<local-command-caveat>", "Caveat: The messages below",
    "<user-prompt-submit-hook>", "<environment_context>", "<user_instructions>", "# AGENTS.md instructions",
    "<turn_aborted>", "<subagent_notification>", "<system-notice>",
)

internal fun isInjected(text: String): Boolean {
    val t = text.trimStart()
    return INJECTED.any { t.startsWith(it) }
}

private val TAG = Regex("<([a-z-]+)>([\\s\\S]*?)</\\1>")

/**
 * Claude Code's transcript (`~/.claude/projects/<cwd>/<session>.jsonl`). [sidechain]: a subagent's
 * own file (`<session>/subagents/agent-<id>.jsonl`), whose every line is marked as a side chain.
 */
class ClaudeTranscript(private val sidechain: Boolean = false) : Transcript() {
    override fun read(obj: JsonObject) {
        if ((obj.bool("isSidechain") && !sidechain) || obj.bool("isMeta")) return
        if (obj.str("type") == "queue-operation") return queueOp(obj)
        val uuid = obj.str("uuid") ?: return
        when (obj.str("type")) {
            "user" -> user(uuid, obj.obj("message") ?: return, obj.obj("toolUseResult"))
            "assistant" -> {
                val message = obj.obj("message") ?: return
                meter(obj, message)
                assistant(uuid, message)
            }
            "system" -> if (obj.str("subtype") == "compact_boundary") put(ChatItem.Notice(uuid, "Conversation compacted"))
            // A message queued while it worked and read in the middle of its turn: said only here.
            "attachment" -> obj.obj("attachment")?.takeIf { it.str("type") == "queued_command" && it.str("commandMode") == "prompt" }?.let { a ->
                userText(uuid, textOf(a["prompt"]), 0)
            }
        }
    }

    /**
     * Claude Code's queue, as its `queue-operation` lines log it: `enqueue` with the message,
     * `dequeue` when it takes the oldest as its next turn, `remove` when it read one mid-turn. Its
     * own entries (task notifications) come without words and are kept only to keep the count.
     */
    private val waiting = ArrayList<String?>()

    private fun queueOp(obj: JsonObject) {
        when (obj.str("operation")) {
            "enqueue" -> waiting += obj.str("content")?.trim()?.takeIf { it.isNotEmpty() && !isInjected(it) }
            "dequeue" -> if (waiting.isNotEmpty()) waiting.removeAt(0)
            "remove" -> {
                val text = obj.str("content")?.trim()
                val i = waiting.indexOf(text)
                if (i >= 0) waiting.removeAt(i) else if (text == null && waiting.isNotEmpty()) waiting.removeAt(0)
            }
            else -> return
        }
        queue(waiting.filterNotNull())
    }

    /** Claude Code records the model and its usage on each answer, and the effort it ran at (newer versions). */
    private fun meter(obj: JsonObject, message: JsonObject) {
        val model = message.str("model")?.takeIf { it.isNotBlank() && !it.startsWith("<") } ?: return
        model(model, if ("effort" in obj) obj.str("effort") else use?.effort)
        val usage = message.obj("usage") ?: return
        filled(usage.count("input_tokens") + usage.count("cache_creation_input_tokens") + usage.count("cache_read_input_tokens"))
    }

    /** [result]: the line's `toolUseResult`, which names the subagent an Agent call started. */
    private fun user(uuid: String, message: JsonObject, result: JsonObject?) {
        when (val content = message["content"]) {
            is JsonPrimitive -> userText(uuid, content.contentOrNull.orEmpty(), 0)
            is JsonArray -> {
                val texts = mutableListOf<String>()
                var images = 0
                for (block in content.filterIsInstance<JsonObject>()) {
                    when (block.str("type")) {
                        "text" -> texts += block.str("text").orEmpty()
                        "image" -> images++
                        "tool_result" -> {
                            val id = block.str("tool_use_id") ?: continue
                            val output = textOf(block["content"]).ifBlank { null }
                            finishTool(id, output, block.bool("is_error"))
                            linkSubagent(id, Subagents.id(result?.str("agentId"), output))
                        }
                    }
                }
                if (texts.isNotEmpty() || images > 0) userText(uuid, texts.joinToString("\n"), images)
            }
            else -> {}
        }
    }

    private fun userText(uuid: String, raw: String, images: Int) {
        val text = raw.trim()
        if (text.startsWith("[Request interrupted")) {
            settleTools()
            put(ChatItem.Notice(uuid, "Interrupted"))
            return
        }
        if (text.startsWith("<command-name>") || text.startsWith("<command-message>")) {
            val tags = TAG.findAll(text).associate { it.groupValues[1] to it.groupValues[2].trim() }
            val name = tags["command-name"] ?: return
            put(ChatItem.User(uuid, (name + " " + tags["command-args"].orEmpty()).trim()))
            return
        }
        if (text.startsWith("<local-command-stdout>") || text.startsWith("<local-command-stderr>")) {
            val out = TAG.find(text)?.groupValues?.get(2)?.trim().orEmpty()
            if (out.isNotEmpty()) put(ChatItem.Notice(uuid, stripAnsi(out)))
            return
        }
        if (text.startsWith("<bash-input>")) {
            put(ChatItem.User(uuid, "! " + TAG.find(text)?.groupValues?.get(2)?.trim().orEmpty()))
            return
        }
        if (text.startsWith("<bash-stdout>")) {
            val out = TAG.findAll(text).joinToString("\n") { it.groupValues[2].trim() }.trim()
            if (out.isNotEmpty()) put(ChatItem.Notice(uuid, stripAnsi(out)))
            return
        }
        if (isInjected(text)) return
        if (text.isEmpty() && images == 0) return
        put(ChatItem.User(uuid, text, images))
    }

    private fun assistant(uuid: String, message: JsonObject) {
        val content = message["content"] as? JsonArray ?: return
        content.filterIsInstance<JsonObject>().forEachIndexed { i, block ->
            when (block.str("type")) {
                "text" -> block.str("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { put(ChatItem.Agent("$uuid/$i", it)) }
                // Empty when the model returned only its signature; `redacted_thinking` is never readable.
                "thinking" -> thought("$uuid/$i", block.str("thinking"))
                "tool_use" -> {
                    val id = block.str("id") ?: return@forEachIndexed
                    val name = block.str("name").orEmpty()
                    val input = block.obj("input") ?: JsonObject(emptyMap())
                    put(
                        ChatItem.Tool(
                            id, name, ToolSummary.of(name, input), ToolSummary.detail(input), diffs = EditCalls.of(name, input).orEmpty(),
                            asks = if (name == "AskUserQuestion") AskedQuestions.of(input) else emptyList(),
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Codex's rollout file (`~/.codex/sessions/YYYY/MM/DD/rollout-…-<id>.jsonl`). Recent versions log
 * completed UI items (`item_completed`), which read like the conversation; older ones log
 * `user_message`/`agent_message` events and raw function calls.
 */
class CodexTranscript : Transcript() {
    private var itemMode = false
    private var seq = 0

    override fun read(obj: JsonObject) {
        val payload = obj.obj("payload") ?: return
        val type = payload.str("type")
        when (obj.str("type")) {
            "event_msg" -> when (type) {
                "item_completed" -> {
                    itemMode = true
                    item(payload.obj("item") ?: return)
                }
                "user_message" -> if (!itemMode) {
                    val text = payload.str("message")?.trim().orEmpty()
                    if (text.isNotEmpty() && !isInjected(text)) put(ChatItem.User("u${seq++}", text))
                }
                "agent_message" -> if (!itemMode) {
                    payload.str("message")?.trim()?.takeIf { it.isNotEmpty() }?.let { put(ChatItem.Agent("a${seq++}", it)) }
                }
                "turn_aborted" -> {
                    settleTools()
                    put(ChatItem.Notice("abort${seq++}", "Interrupted"))
                }
                // What the last request filled, against the window Codex itself states.
                "token_count" -> payload.obj("info")?.let { info ->
                    filled(info.obj("last_token_usage")?.count("total_tokens") ?: 0, info.count("model_context_window").takeIf { it > 0 })
                }
            }
            "turn_context", "session_meta" -> {
                val settings = payload.obj("collaboration_mode")?.obj("settings")
                val model = payload.str("model") ?: settings?.str("model")
                val effort = when {
                    "effort" in payload -> payload.str("effort")
                    "reasoning_effort" in payload -> payload.str("reasoning_effort")
                    else -> settings?.str("reasoning_effort")
                }
                if (model != null || effort != null) model(model, effort)
            }
            "response_item" -> if (!itemMode) legacyCall(payload)
            "compacted" -> put(ChatItem.Notice("compact${seq++}", "Conversation compacted"))
        }
    }

    private fun item(item: JsonObject) {
        val id = item.str("id") ?: "i${seq++}"
        val failed = item.str("status") == "failed"
        val state = if (failed) ToolState.Failed else ToolState.Done
        when (item.str("type")) {
            "UserMessage" -> {
                val content = item.arr("content") ?: return
                val text = content.filterIsInstance<JsonObject>().mapNotNull { it.str("text") }.joinToString("\n").trim()
                val images = content.count { (it as? JsonObject)?.str("type")?.contains("image", ignoreCase = true) == true }
                if ((text.isNotEmpty() || images > 0) && !isInjected(text)) put(ChatItem.User(id, text, images))
            }
            "AgentMessage" -> textOf(item["content"]).trim().takeIf { it.isNotEmpty() }?.let { put(ChatItem.Agent(id, it)) }
            // The raw reasoning when the model shares it (open models), else the summary Codex asked for; often neither.
            "Reasoning" -> thought(id, reasoning(item.arr("raw_content")).ifBlank { reasoning(item.arr("summary_text")) })
            "CommandExecution" -> {
                val command = commandLine(item["command"])
                val output = item.str("aggregated_output") ?: item.str("formatted_output") ?: item.str("stdout")
                val exit = (item["exit_code"] as? JsonPrimitive)?.contentOrNull
                put(
                    ChatItem.Tool(
                        id, "shell", command, null,
                        output?.ifBlank { null }?.let(Transcript::clip),
                        if (failed || (exit != null && exit != "0")) ToolState.Failed else ToolState.Done,
                    ),
                )
            }
            "FileChange" -> {
                val changes = item.obj("changes")
                val summary = changes?.entries?.joinToString(", ") { (path, change) ->
                    val kind = (change as? JsonObject)?.str("type")
                    (when (kind) { "add" -> "+ "; "delete" -> "− "; else -> "" }) + path
                } ?: item.str("stdout")?.lines()?.drop(1)?.joinToString(", ") { it.trim() }.orEmpty()
                val diffs = changes?.entries?.mapNotNull { (path, change) -> (change as? JsonObject)?.let { Diffs.codexChange(path, it) } }.orEmpty()
                put(ChatItem.Tool(id, "edit", summary, null, item.str("stdout")?.ifBlank { null }, state, diffs))
            }
            "McpToolCall" -> {
                val args = item.obj("arguments") ?: JsonObject(emptyMap())
                val name = "${item.str("server")}.${item.str("tool")}"
                put(
                    ChatItem.Tool(
                        id, name, ToolSummary.of(item.str("tool").orEmpty(), args), ToolSummary.detail(args),
                        textOf(item.obj("result")?.get("content")).ifBlank { null }?.let(Transcript::clip), state,
                    ),
                )
            }
            "WebSearch" -> put(ChatItem.Tool(id, "web search", item.str("query").orEmpty(), state = state))
            "Extension" -> {
                val query = item.str("query") ?: item.obj("action")?.arr("queries")?.joinToString(" · ") { it.str().orEmpty() }
                put(ChatItem.Tool(id, item.str("kind") ?: "extension", query.orEmpty(), state = state))
            }
            "ImageView" -> put(ChatItem.Tool(id, "view image", item.str("path")?.removePrefix("file://").orEmpty(), state = state))
            "ContextCompaction" -> put(ChatItem.Notice(id, "Conversation compacted"))
        }
    }

    /** Reasoning parts, as strings or `{type: summary_text|reasoning_text, text}` blocks, one paragraph each. */
    private fun reasoning(parts: JsonArray?): String =
        parts.orEmpty().mapNotNull { it.str() ?: (it as? JsonObject)?.str("text") }.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")

    private fun legacyCall(payload: JsonObject) {
        when (payload.str("type")) {
            "reasoning" -> thought(payload.str("id") ?: "r${seq++}", reasoning(payload.arr("content")).ifBlank { reasoning(payload.arr("summary")) })
            "function_call", "custom_tool_call" -> {
                val callId = payload.str("call_id") ?: return
                val name = payload.str("name").orEmpty()
                val raw = payload.str("arguments") ?: payload.str("input").orEmpty()
                val args = runCatching { TranscriptJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                // The patch as the call's text (`apply_patch`), or in a script that applies it (`exec`).
                val edit = EditCalls.isEdit(name)
                val diffs = (args?.let { EditCalls.of(name, it) } ?: if (edit) EditCalls.patch(raw) else EditCalls.applied(raw)).orEmpty()
                val summary = when {
                    // A script that applies a patch may do more: its own first line.
                    args == null && edit && diffs.isNotEmpty() -> diffs.joinToString(", ") { it.path }
                    args == null -> raw.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                    args["command"] != null || args["cmd"] != null -> commandLine(args["command"] ?: args["cmd"])
                    else -> ToolSummary.of(name, args)
                }
                put(ChatItem.Tool(callId, name, summary, raw.takeIf { it.length > summary.length }?.let(Transcript::clip), diffs = diffs))
            }
            "function_call_output", "custom_tool_call_output" -> {
                val callId = payload.str("call_id") ?: return
                val output = payload["output"]
                val text = (output as? JsonPrimitive)?.contentOrNull?.let { o ->
                    runCatching { TranscriptJson.parseToJsonElement(o).jsonObject.str("output") }.getOrNull() ?: o
                } ?: textOf(output)
                finishTool(callId, text.ifBlank { null }, false)
            }
        }
    }

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
class PiTranscript : Transcript() {
    /**
     * The agent's words [text] as item [id], and the reasoning an open model put in front of them in
     * `<think>` tags (a server that doesn't split it off) as its own item before them. Only pi runs
     * such models; Claude and Codex never write their reasoning into their words.
     */
    private fun say(id: String, text: String) {
        val (thought, words) = Thinking.split(text)
        thought?.let { put(ChatItem.Reasoning("$id/think", it)) }
        words.takeIf { it.isNotEmpty() }?.let { put(ChatItem.Agent(id, it)) }
    }

    override fun read(obj: JsonObject) {
        val id = obj.str("id") ?: return
        when (obj.str("type")) {
            "message" -> message(id, obj.obj("message") ?: return)
            "compaction" -> put(ChatItem.Notice(id, "Conversation compacted"))
        }
    }

    private fun message(id: String, message: JsonObject) {
        when (message.str("role")) {
            "user" -> {
                val content = message["content"]
                val text = textOf(content).trim()
                val images = (content as? JsonArray)?.count { (it as? JsonObject)?.str("type") == "image" } ?: 0
                if ((text.isNotEmpty() || images > 0) && !isInjected(text)) put(ChatItem.User(id, text, images))
            }
            "assistant" -> {
                message.str("model")?.takeIf { it.isNotBlank() }?.let { model(it) }
                message.obj("usage")?.takeIf { message.str("stopReason") != "error" && message.str("stopReason") != "aborted" }?.let { u ->
                    filled(u.count("totalTokens").takeIf { it > 0 } ?: (u.count("input") + u.count("output") + u.count("cacheRead") + u.count("cacheWrite")))
                }
                val content = message["content"] as? JsonArray ?: JsonArray(emptyList())
                content.filterIsInstance<JsonObject>().forEachIndexed { i, block ->
                    when (block.str("type")) {
                        "text" -> block.str("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { say("$id/$i", it) }
                        // pi keeps any provider's reasoning (Anthropic thinking, OpenAI-style `reasoning_content`) here.
                        "thinking" -> if (!block.bool("redacted")) thought("$id/$i", block.str("thinking"))
                        "toolCall" -> {
                            val callId = block.str("id") ?: return@forEachIndexed
                            val name = block.str("name").orEmpty()
                            val args = block.obj("arguments") ?: JsonObject(emptyMap())
                            put(ChatItem.Tool(callId, name, ToolSummary.of(name, args), ToolSummary.detail(args), diffs = EditCalls.of(name, args).orEmpty()))
                        }
                    }
                }
                if (message.str("stopReason") == "aborted") {
                    settleTools()
                    put(ChatItem.Notice("$id/abort", "Interrupted"))
                } else if (message.str("stopReason") == "error") {
                    put(ChatItem.Notice("$id/error", message.str("errorMessage") ?: "The agent hit an error"))
                }
            }
            "toolResult" -> finishTool(message.str("toolCallId") ?: return, textOf(message["content"]).ifBlank { null }, message.bool("isError"))
            "bashExecution" -> put(
                ChatItem.Tool(
                    id, "bash", message.str("command").orEmpty(), null,
                    message.str("output")?.ifBlank { null }?.let(Transcript::clip), ToolState.Done,
                ),
            )
        }
    }
}

/** Picks the one line worth showing for a tool call. */
object ToolSummary {
    // A human description of the call (claude's Bash `description`, omp's intent `i`) reads best on a
    // phone; the raw command stays in the expanded view.
    private val PREFERRED = listOf(
        "description", "i", "command", "cmd", "file_path", "path", "pattern", "query", "url", "prompt", "skill", "name", "title",
    )

    fun of(name: String, input: JsonObject): String {
        when (name) {
            "TodoWrite", "todo", "update_plan" -> return "Updated the plan"
            "AskUserQuestion" -> {
                val q = input.arr("questions")?.firstOrNull() as? JsonObject
                q?.str("question")?.let { return it }
            }
        }
        val key = PREFERRED.firstOrNull { input.str(it)?.isNotBlank() == true }
        val value = key?.let { input.str(it) }
            ?: input.values.firstNotNullOfOrNull { it.str()?.takeIf { s -> s.isNotBlank() } }
            ?: ""
        return value.trim()
    }

    /** The full input when it says more than the summary, for the expanded view. */
    fun detail(input: JsonObject): String? {
        if (input.isEmpty()) return null
        val parts = input.entries.mapNotNull { (k, v) ->
            val text = v.str() ?: v.takeIf { it !is JsonNull }?.toString() ?: return@mapNotNull null
            "$k: $text"
        }
        return parts.joinToString("\n").takeIf { it.isNotBlank() }?.let(Transcript::clip)
    }
}

private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

internal fun stripAnsi(text: String): String = text.replace(ANSI, "")
