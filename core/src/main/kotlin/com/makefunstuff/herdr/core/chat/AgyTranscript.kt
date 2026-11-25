package com.makefunstuff.herdr.core.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Antigravity CLI's public, append-only step transcript, not transcript_full or chunk copies. */
class AgyTranscript : Transcript() {
    private var pending: List<String> = emptyList()
    override fun read(obj: JsonObject) {
        val id = (obj["step_index"] as? JsonPrimitive)?.contentOrNull ?: return
        val key = "agy/$id"
        val content = obj.str("content").orEmpty()
        obj.str("model")?.let { model(it) }
        when (obj.str("type")) {
            "USER_INPUT" -> {
                val text = userText(content)
                if (text.isNotBlank() && obj.str("source") == "USER_EXPLICIT") put(ChatItem.User(key, text))
            }
            "PLANNER_RESPONSE" -> {
                fun count(key: String) = (obj[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
                count("input_tokens")?.let { requestUsage(it, count("output_tokens"), count("cache_read_tokens")) }
                obj.str("thinking")?.let { thought("$key/thinking", it) }
                if (content.isNotBlank()) put(ChatItem.Agent(key, content))
                val calls = obj.arr("tool_calls").orEmpty().mapIndexedNotNull { i, element ->
                    val call = element as? JsonObject ?: return@mapIndexedNotNull null
                    val name = call.str("name") ?: return@mapIndexedNotNull null
                    val args = call.obj("args") ?: JsonObject(emptyMap())
                    val toolId = "$key/tool/$i"
                    val summary = listOf("toolSummary", "CommandLine", "TargetFile", "AbsolutePath", "SearchPath", "Query")
                        .firstNotNullOfOrNull { args.str(it)?.let(::unquote) } ?: name
                    // DONE describes the planner step, not execution of its proposed calls.
                    put(ChatItem.Tool(toolId, name, summary, input = args.toString()))
                    toolId
                }
                if (calls.isNotEmpty()) pending = calls
            }
            "GENERIC" -> {
                // The public log has no call id. Only associate an unambiguous single call.
                if (pending.size == 1) {
                    val status = obj.str("status")
                    if (status in setOf("DONE", "ERROR", "FAILED", "CANCELLED")) {
                        val failed = status != "DONE" || Regex("Command exited with code ([1-9][0-9]*)\\.").containsMatchIn(content)
                        finishTool(pending.single(), content, failed)
                        pending = emptyList()
                    }
                } else if (content.isNotBlank()) put(ChatItem.Notice(key, clip(content)))
            }
            // SYSTEM_MESSAGE includes harness instructions, not words from the human or model.
        }
    }

    companion object {
        internal fun userText(text: String): String = Regex("<USER_REQUEST>\\s*([\\s\\S]*?)\\s*</USER_REQUEST>")
            .find(text)?.groupValues?.get(1)?.trim()
            ?: text.substringBefore("<ADDITIONAL_METADATA>").substringBefore("<USER_SETTINGS_CHANGE>").trim()
        private fun unquote(value: String): String = runCatching {
            (TranscriptJson.parseToJsonElement(value) as? JsonPrimitive)?.contentOrNull
        }.getOrNull() ?: value
    }
}
