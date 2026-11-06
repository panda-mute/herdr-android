package com.makefunstuff.herdr.core.chat

import kotlinx.serialization.json.JsonObject

/** One of the questions in Claude's AskUserQuestion call, as the transcript has it. */
data class AskedQuestion(
    val question: String,
    val header: String?,
    val options: List<AskedOption>,
    val multiSelect: Boolean,
)

data class AskedOption(val label: String, val description: String?)

/**
 * Claude's questions word for word, as Alma shows them: the card is read off the screen, where a
 * long question or description is cut to the rows the form has, so the transcript's own words fill
 * it in. Only where the screen's options are the transcript's: otherwise the screen stays as read.
 */
object AskedQuestions {
    fun of(input: JsonObject): List<AskedQuestion> =
        input.arr("questions").orEmpty().filterIsInstance<JsonObject>().mapNotNull { q ->
            val question = q.str("question")?.trim()?.ifEmpty { null } ?: return@mapNotNull null
            AskedQuestion(
                question,
                q.str("header")?.trim()?.ifEmpty { null },
                q.arr("options").orEmpty().filterIsInstance<JsonObject>().mapNotNull { o ->
                    o.str("label")?.trim()?.ifEmpty { null }?.let { AskedOption(it, o.str("description")?.trim()?.ifEmpty { null }) }
                },
                q.bool("multiSelect"),
            )
        }

    /** [prompt] with the words of the question it shows, from the newest unanswered AskUserQuestion in [items]. */
    fun fill(prompt: BlockedPrompt, items: List<ChatItem>): BlockedPrompt {
        val call = items.lastOrNull { it is ChatItem.Tool } as? ChatItem.Tool ?: return prompt
        if (call.asks.isEmpty() || call.state != ToolState.Running) return prompt
        val questions = prompt.steps.filter { !it.submit }
        val step = if (questions.isEmpty()) 0 else questions.indexOfFirst { it.current }
        val asked = call.asks.getOrNull(step) ?: return prompt
        // The screen's own rows: each of them one of the transcript's, in order (Claude adds "Type something." and "Chat about this").
        val own = prompt.options.withIndex().filter { (i, _) -> prompt.role(i) != OptionRole.Custom && prompt.role(i) != OptionRole.Chat }
        if (own.size != asked.options.size) return prompt
        val matched = own.zip(asked.options).all { (o, a) -> same(o.value.label, a.label) }
        if (!matched) return prompt
        val byIndex = own.zip(asked.options).associate { (o, a) -> o.index to a }
        return prompt.copy(
            question = asked.question,
            options = prompt.options.mapIndexed { i, o ->
                val a = byIndex[i] ?: return@mapIndexed o
                o.copy(label = a.label, description = a.description ?: o.description)
            },
            steps = if (questions.size == call.asks.size) {
                var k = 0
                prompt.steps.map { s -> if (s.submit) s else s.copy(label = call.asks[k++].header ?: s.label) }
            } else prompt.steps,
        )
    }

    /** The screen's label is the transcript's, maybe cut short with an ellipsis. */
    private fun same(screen: String, asked: String): Boolean {
        val a = norm(asked)
        val s = norm(screen)
        if (s == a) return true
        val cut = norm(screen.trimEnd().removeSuffix("…").removeSuffix("..."))
        return cut.length >= 4 && cut != s && a.startsWith(cut)
    }

    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim().lowercase()
}
