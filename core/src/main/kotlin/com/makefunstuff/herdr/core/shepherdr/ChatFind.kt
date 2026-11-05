package com.makefunstuff.herdr.core.shepherdr

/**
 * Find in chat (3.1's Ctrl+F, `web/plugins/chat/find.tsx`): the items of a Shepherdr chat whose words hold what was
 * typed, oldest first, and the count as 3.1 says it ("2 of 7", "No matches").
 */
object ChatFind {
    const val PLACEHOLDER = "Find in chat"
    const val NO_MATCHES = "No matches"
    const val SEARCH_EARLIER = "Search earlier"

    /** The ids of [items] that hold [query] (any case), oldest first; none for a blank query. */
    fun matches(items: List<SpaceChatItem>, query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return items.filter { words(it).any { w -> w.contains(q, ignoreCase = true) } }.map { it.id }
    }

    /** "2 of 7" for the [at]th (from 0) of [count] matches, "No matches" for none. */
    fun count(at: Int, count: Int): String = if (count == 0) NO_MATCHES else "${at + 1} of $count"

    /** The words an item shows, as find reads them. */
    fun words(item: SpaceChatItem): List<String> = when (item) {
        is SpaceChatItem.Words -> listOf(item.text)
        is SpaceChatItem.Update -> listOf(item.task, item.text)
        is SpaceChatItem.Question -> listOf(item.intro, item.card.question.title)
        is SpaceChatItem.Note -> listOf(item.text)
        is SpaceChatItem.Artifact -> listOf(item.intro)
        is SpaceChatItem.File -> listOf(item.name)
        is SpaceChatItem.Steps -> item.rows
        else -> emptyList()
    }
}

/**
 * Reactions on a message as 3.1 draws them: one pill per emoji with how many gave it, yours marked; a tap adds yours
 * or takes it back. The quick row is 3.1's favourites (`web/plugins/reactions/emoji.ts` `EMOJI`).
 */
object Reactions {
    val QUICK = listOf("👍", "❤️", "😂", "🙌", "👀", "✅")

    /** One pill: [count] 1 or 2 (yours, the agent's, or both), [mine] when yours is in it, [pending] while it goes in. */
    data class Pill(val emoji: String, val count: Int, val mine: Boolean, val pending: Boolean = false)

    /** Whether [r] is the person's: Shepherdr says "me" or "both" ("you" in older chats). */
    fun mine(r: V2Reaction) = r.by == "me" || r.by == "both" || r.by == "you"

    /**
     * [reactions] as pills, with [sent] (emoji to whether you want yours there) laid over them until the chat shows
     * it: a reaction you just added shows faded, one you took back is gone at once.
     */
    fun pills(reactions: List<V2Reaction>, sent: Map<String, Boolean> = emptyMap()): List<Pill> {
        val out = LinkedHashMap<String, Pill>()
        for (r in reactions) {
            val was = out[r.emoji]
            val n = if (r.by == "both") 2 else 1
            out[r.emoji] = Pill(r.emoji, (was?.count ?: 0) + n, (was?.mine ?: false) || mine(r), (was?.pending ?: false) || r.pending)
        }
        for ((emoji, want) in sent) {
            val p = out[emoji]
            when {
                want && p == null -> out[emoji] = Pill(emoji, 1, mine = true, pending = true)
                want && !p!!.mine -> out[emoji] = p.copy(count = p.count + 1, mine = true, pending = true)
                !want && p != null && p.mine -> if (p.count <= 1) out.remove(emoji) else out[emoji] = p.copy(count = p.count - 1, mine = false)
            }
        }
        return out.values.toList()
    }

    /** What is still on its way of [sent]: an emoji whose state [reactions] already show is dropped. */
    fun stillSent(reactions: List<V2Reaction>, sent: Map<String, Boolean>): Map<String, Boolean> =
        sent.filter { (emoji, want) -> reactions.any { it.emoji == emoji && mine(it) } != want }
}
