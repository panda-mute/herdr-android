package com.makefunstuff.herdr.core.chat

/**
 * Claude Code's subagents: a call to its Agent tool (`Task` in older versions) runs one, and its
 * result names it (`toolUseResult.agentId`, and `agentId: …` in the text). Its own steps are kept in
 * `<session>/subagents/agent-<id>.jsonl` beside the session file, or `agent-<id>.jsonl` in the
 * project folder in older versions; [TranscriptFollower] finds that file from the session's.
 */
object Subagents {
    private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val IN_TEXT = Regex("""\bagentId:\s*([A-Za-z0-9_-]{1,64})""")

    fun isAgentTool(name: String): Boolean = name == "Agent" || name == "Task"

    /** The subagent id from a call's result, or from its text; null when neither names a usable one. */
    fun id(fromResult: String?, text: String?): String? =
        fromResult?.takeIf { valid(it) } ?: text?.let { IN_TEXT.find(it)?.groupValues?.get(1) }

    /** [text] without the `agentId: …` line Claude adds for itself, which means nothing to a reader. */
    fun withoutId(text: String): String =
        text.lineSequence().filterNot { IN_TEXT.containsMatchIn(it) && it.trimStart().startsWith("agentId:") }.joinToString("\n").trim()

    /** Only letters, digits, `-` and `_`: the id becomes part of a path on the machine. */
    fun valid(id: String): Boolean = ID.matches(id)
}
