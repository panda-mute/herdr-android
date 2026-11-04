package com.makefunstuff.herdr.core.shepherdr

import com.makefunstuff.herdr.core.board.WorkCard
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.chat.VoiceNote
import com.makefunstuff.herdr.core.chat.VoiceNotes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A space's Assistant chat (or a task's) on Shepherdr 3 as the chat screens draw it, oldest first: your words and its
 * replies, Shepherdr's news lines about tasks, question cards, files, artifacts, and quiet lines (a launch brief, an
 * ask that closed). Shepherdr's own tagged lines are no one's words, so they never show as text.
 */
sealed interface SpaceChatItem {
    /** The message's id: unique in the chat, so a list can key on it. */
    val id: String

    /** Words: yours on the right, the coordinator's (markdown) on the left. */
    data class Words(
        override val id: String,
        val you: Boolean,
        val text: String,
        /** What it answers, quoted. */
        val reply: V2ReplyQuote? = null,
        val reactions: List<V2Reaction> = emptyList(),
        /** Yours, not in yet: the coordinator shows a menu or is starting; or sent from here and not seen back yet. */
        val waiting: Boolean = false,
        /** Yours that couldn't go in, and why. */
        val failed: String? = null,
    ) : SpaceChatItem

    /**
     * Shepherdr's news line about a task ("HERD-4 is ready · Dark mode works"), with the questions of its that wait
     * here; [accept]: the newest ready line of a task still Ready, which carries Accept; [live]: it still needs you.
     */
    data class Update(
        override val id: String,
        val task: String,
        val status: WorkStatus?,
        /** What happened, after the task's name: "is ready to merge". */
        val text: String,
        val pr: V2Pr? = null,
        /** The base branch went red ("main"). */
        val red: String? = null,
        /** Its task's questions copied here, still open: answered from here. */
        val asks: List<WorkCard> = emptyList(),
        /** Its task's question, answered: where, in words. */
        val answered: String? = null,
        val accept: Boolean = false,
        val live: Boolean = false,
    ) : SpaceChatItem

    /** A question card, with the words that came with it ([intro]); [answer] once answered. */
    data class Question(
        override val id: String,
        val card: WorkCard,
        val intro: String = "",
        val answer: String? = null,
    ) : SpaceChatItem

    /** A file sent along: yours, or one the coordinator shared. */
    data class File(override val id: String, val you: Boolean, val name: String, val path: String) : SpaceChatItem

    /**
     * A voice message: a file sent as one ([VoiceNotes.isVoice]), [path] on the machine; or one of
     * yours still on the phone ([note], not sent yet, or sent and not in the chat yet).
     */
    data class Voice(override val id: String, val you: Boolean, val path: String?, val millis: Long, val note: VoiceNote? = null) : SpaceChatItem

    /**
     * An artifact posted in the chat, at [version], with the words that came with it ([intro]). [later]: the
     * chat posts it again further down, so this one folds to a line, as a chat never repeats big cards.
     */
    data class Artifact(override val id: String, val artifact: String, val version: Int, val intro: String = "", val later: Boolean = false) : SpaceChatItem

    /** A message in a shape this app can't read yet (a newer Shepherdr's): one quiet line in its place. */
    data class Unreadable(override val id: String) : SpaceChatItem

    /** A thin "New session" line before the message after it. */
    data class NewSession(override val id: String) : SpaceChatItem

    /** "Answered “Rerun CI on main?”": your answer to a card, as the chat keeps it. */
    data class Note(override val id: String, val text: String) : SpaceChatItem

    /**
     * A turn's tool steps folded to one line ("Worked for 14m · 9 steps"), with the [rows] that say most
     * ("Edited 2 files: app.ts, panel.tsx") and how many [more]; [millis] null while it runs.
     */
    data class Steps(
        override val id: String,
        val steps: Int,
        val millis: Long?,
        val rows: List<String> = emptyList(),
        val more: Int = 0,
        val failed: Int = 0,
    ) : SpaceChatItem
}

/** What you sent from the screen and the chat hasn't shown yet: it shows as waiting until a message carries its [cid]. */
data class OutgoingLine(val cid: String, val text: String, val reply: V2ReplyQuote? = null)

object SpaceChat {
    /**
     * [chat] (a space's coordinator chat, [space] its id) as the screen's items; [outgoing] not yet in it go last,
     * then the voice messages of yours ([voice]) it doesn't show yet, while they wait ([VoiceNotes.waiting] at [now]).
     * With [steps], a turn's tool steps show as one folded line each ([SpaceChatItem.Steps]): a task's agent chat.
     */
    fun items(
        space: String,
        chat: V2Chat,
        outgoing: List<OutgoingLine> = emptyList(),
        voice: List<VoiceNote> = emptyList(),
        now: Long = 0,
        steps: Boolean = false,
    ): List<SpaceChatItem> {
        val failed = chat.failed.orEmpty().associate { it.cid to it.error }
        val out = ArrayList<SpaceChatItem>()
        // Where each artifact is posted last: earlier posts of it fold.
        val lastPost = HashMap<String, Int>()
        chat.messages.forEachIndexed { i, m -> m.artifact?.let { lastPost[it.id] = i } }
        for ((i, m) in chat.messages.withIndex()) {
            if (m.newSession) out += SpaceChatItem.NewSession("${m.id}#new")
            if (m.unknown) {
                out += SpaceChatItem.Unreadable(m.id)
                continue
            }
            val text = ShepherdrText.clean(m.text.orEmpty())
            // Quiet lines: what the person didn't type (a launch brief, another agent's message), a publish on its
            // way, an ask that closed, a secret asked for, pictures the chat can't show.
            val quiet = quiet(m)
            if (quiet != null) {
                out += SpaceChatItem.Note(m.id, quiet)
                continue
            }
            val n = m.news
            if (n != null) {
                out += SpaceChatItem.Update(m.id, n.ref.ifEmpty { n.task }, newsStatus(n.mark), listOf(n.what, n.text).filter { it.isNotBlank() }.joinToString(" · "), accept = n.accept, live = n.live)
                continue
            }
            val card = m.card
            val q = m.question
            val post = m.artifact
            if (steps) m.activity?.let { a -> steps("${m.id}#steps", a)?.let { out += it } }
            when {
                card != null -> out += update(space, m.id, card, text)
                q != null -> out += SpaceChatItem.Question(m.id, WorkCard(chat.key, q), intro = text, answer = answered(q))
                post != null -> out += SpaceChatItem.Artifact(m.id, post.id, post.v, intro = text, later = (lastPost[post.id] ?: i) > i)
                else -> {
                    if (text.isNotEmpty()) {
                        out += SpaceChatItem.Words(
                            m.id, m.you, text, m.reply, m.reactions.orEmpty(),
                            waiting = m.waiting, failed = m.cid?.let { failed[it] },
                        )
                    }
                    m.file?.takeIf { it.name.isNotBlank() }?.let { f ->
                        val millis = VoiceNotes.millis(f.name)
                        out += if (millis != null) SpaceChatItem.Voice("${m.id}#file", m.you, f.path, millis, VoiceNotes.byRemote(voice, f.path))
                        else SpaceChatItem.File("${m.id}#file", m.you, f.name, f.path)
                    }
                    m.answered?.takeIf { it.isNotBlank() }?.let { a -> out += SpaceChatItem.Note("${m.id}#answered", a) }
                }
            }
        }
        val seen = chat.messages.mapNotNullTo(HashSet()) { it.cid }
        outgoing.filter { it.cid !in seen }.forEach { o ->
            out += SpaceChatItem.Words("cid:${o.cid}", true, o.text, o.reply, waiting = failed[o.cid] == null, failed = failed[o.cid])
        }
        val files = chat.messages.mapNotNullTo(HashSet()) { it.file?.path }
        voice.filter { (it.remote == null || it.remote !in files) && VoiceNotes.waiting(it, now) }.forEach { n ->
            out += SpaceChatItem.Voice("voice:${n.id}", true, n.remote, n.millis, n)
        }
        return out
    }

    /** A quiet line's words, for a message that is one; null for any other. */
    fun quiet(m: V2Message): String? = when {
        m.brief != null -> m.brief.label.ifBlank { "Message" }
        m.publishing -> "Publishing an artifact…"
        !m.ended.isNullOrBlank() -> m.ended
        m.secret != null -> "Wants the secret ${m.secret.name}" + (m.secret.saved?.let { " · kept $it" }.orEmpty())
        (m.images ?: 0) > 0 && m.text.isNullOrBlank() && m.file == null -> if (m.images == 1) "1 image" else "${m.images} images"
        else -> null
    }

    /** A news line's mark as the task's status then. */
    fun newsStatus(mark: String): WorkStatus? = when (mark) {
        "started" -> WorkStatus.Work
        "needs" -> WorkStatus.Need
        "ready" -> WorkStatus.Ready
        "done" -> WorkStatus.Done
        else -> null
    }

    /** The outgoing lines the chat doesn't show yet: one that came back (its [OutgoingLine.cid]) is dropped. */
    fun stillOutgoing(chat: V2Chat, outgoing: List<OutgoingLine>): List<OutgoingLine> {
        val seen = chat.messages.mapNotNullTo(HashSet()) { it.cid }
        return outgoing.filter { it.cid !in seen }
    }

    /** A question's answer in words: the choice sent, the words typed, or where it was answered; null while open. */
    fun answered(q: V2Question): String? = when {
        q.open -> null
        q.sent != null && q.sent >= 0 -> q.choices.getOrNull(q.sent)?.label ?: "Answered"
        q.via == "terminal" -> "Answered in its terminal"
        !q.said.isNullOrBlank() -> q.said
        q.via == "chat" -> "Answered in the chat"
        else -> "Answered"
    }

    /** A turn's activity as its folded line; null for one without steps. */
    fun steps(id: String, a: JsonObject): SpaceChatItem.Steps? {
        fun num(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
        val rows = (a["rows"] as? JsonArray).orEmpty().mapNotNull { r -> ((r as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
        val count = num(a, "steps")?.toInt() ?: rows.size
        if (count <= 0 && rows.isEmpty()) return null
        val at = num(a, "at")
        val end = num(a, "end")
        val millis = if (at != null && end != null) (end - at).toLong().coerceAtLeast(0) else null
        return SpaceChatItem.Steps(id, count, millis, rows, more = num(a, "more")?.toInt() ?: 0, failed = num(a, "failed")?.toInt() ?: 0)
    }

    /** How long a turn worked, as people say it: "40s", "14m", "1h 5m". */
    fun took(millis: Long): String {
        val s = millis / 1000
        return when {
            s < 60 -> "${s.coerceAtLeast(1)}s"
            s < 3600 -> "${s / 60}m"
            else -> "${s / 3600}h" + (s % 3600 / 60).takeIf { it > 0 }?.let { " ${it}m" }.orEmpty()
        }
    }

    private fun update(space: String, id: String, c: V2UpdateCard, text: String): SpaceChatItem.Update {
        val task = c.task.orEmpty()
        val ask = c.ask
        val asks = buildList {
            if (ask == null || !ask.open) return@buildList
            ask.questions.orEmpty().filter { it.open }.forEach { add(WorkCard(ask.chat, it, task, space = space)) }
            ask.reply?.let { r ->
                add(WorkCard(ask.chat, V2Question(id = r.id, title = r.text, kind = "Reply", other = true, to = task), task, space = space, replyTo = r.id))
            }
        }
        val answered = ask?.answered?.let { a -> a.text?.takeIf { it.isNotBlank() } ?: a.where.takeIf { it.isNotBlank() }?.let { "Answered in $it" } }
        // The card's own words come after the task's name; a card without them says its message's.
        val words = ShepherdrText.clean(c.text).ifEmpty { text.removePrefix(task).trim() }
        return SpaceChatItem.Update(id, task, c.status?.let(V2Board::status), words, c.pr, c.red, asks, answered)
    }
}

/** What a chat shows instead of its conversation, in Shepherdr's own words (`core/chat-view.js`'s). */
object ChatNotice {
    const val EMPTY_TITLE = "What should the Assistant do?"
    const val EMPTY_BODY = "It plans the work, starts a task for each part and keeps you posted."

    /** [chat]'s notice ([V2Chat.notice]) as a title and the words under it; null where it has none. */
    fun of(chat: V2Chat): Pair<String, String?>? = when (chat.notice) {
        null, "" -> null
        "opening" -> "Opening the conversation…" to null
        "unsupported" -> "No chat for this agent" to "Chat reads Claude Code's and Codex's own session logs. This agent works in its terminal only."
        "none" -> "Can't find this conversation" to "Chat reads the agent's own session log, and Herdr can't tell which one this agent writes. Rather than show the wrong one, it shows none."
        else -> (if (chat.key.endsWith("/worker")) "This task's agent has ended" else "This agent has ended") to null
    }
}

/** Shepherdr's own lines in agents' words, and the chat-line tags an agent writes, taken out of what people read. */
object ShepherdrText {
    private val hidden = Regex("""<shepherdr(?:\s+[\w-]+="[^"]*")*\s*>[\s\S]*?</shepherdr>\s*""")
    private val noReply = Regex("""^`?(<no-reply>[\s\S]*?</no-reply>|<no-reply\s*/>)`?$""", RegexOption.IGNORE_CASE)
    private val agentLine = Regex("""^\s*<(react|reply)(\s+[\w-]+="[^"]*")*\s*/>\s*$""", RegexOption.MULTILINE)

    fun clean(s: String): String {
        val t = s.replace(hidden, "").trim()
        if (noReply.matches(t)) return ""
        return t.replace(agentLine, "").trim()
    }
}
