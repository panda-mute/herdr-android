package com.makefunstuff.herdr.core.activity

import com.makefunstuff.herdr.core.chat.AgyTranscript
import com.makefunstuff.herdr.core.chat.ChatItem
import com.makefunstuff.herdr.core.chat.ToolState
import com.makefunstuff.herdr.core.chat.str
import kotlinx.serialization.json.JsonObject

/** Uses the same item ids and conservative result association as the chat reader. */
class AgyActivity(prefix: String = "") : ActivityLog(prefix) {
    private val transcript = AgyTranscript()
    private val seen = HashMap<String, ChatItem>()
    override fun read(o: JsonObject) {
        val at = time(o.str("created_at"))
        o.str("model")?.let { model = "agy/$it" }
        transcript.feed(o.toString())
        for (item in transcript.items()) {
            if (seen[item.id] == item) continue
            val old = seen.put(item.id, item)
            when (item) {
                is ChatItem.User -> if (old == null) turn(item.id, item.text, at)
                is ChatItem.Agent -> if (old == null) step(Step(item.id, StepKind.Message, firstLine(item.text), at, at, detail = item.text))
                is ChatItem.Tool -> {
                    val state = when (item.state) { ToolState.Running -> StepState.Running; ToolState.Done -> StepState.Done; ToolState.Failed -> StepState.Failed }
                    if (old == null) step(Step(item.id, kind(item.name), item.summary, at, at, state, detail = item.input, output = item.output, tool = item.name))
                    else update(item.id) { it.copy(state = state, end = at, output = item.output) }
                }
                else -> Unit
            }
        }
        touch(at)
    }
    private fun kind(name: String) = when (name) {
        "run_command", "command_status" -> StepKind.Command
        "view_file", "view_file_outline", "grep_search", "find_by_name", "list_dir" -> StepKind.Read
        "write_to_file", "replace_file_content", "multi_replace_file_content" -> StepKind.Edit
        "search_web", "read_url_content" -> StepKind.Web
        else -> StepKind.Tool
    }
}
