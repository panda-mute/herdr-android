package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.chat.HeldMessage
import com.makefunstuff.herdr.core.chat.QuickReplies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What the agent chats' composers keep: the messages held while an agent worked, per chat (by its
 * [DraftStore] key), and the quick replies with whether they show. Kept through restarts.
 */
class ComposerStore(private val file: File) {
    @Serializable
    data class State(val held: Map<String, List<HeldMessage>> = emptyMap(), val replies: QuickReplies = QuickReplies())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state.asStateFlow()

    fun held(key: String): List<HeldMessage> = _state.value.held[key].orEmpty()

    /** Holds [text] at the end of [key]'s list. */
    fun hold(key: String, text: String, at: Long, known: List<String> = emptyList()): HeldMessage {
        val ids = held(key).map { it.id }.toSet()
        val m = HeldMessage(generateSequence(ids.size) { it + 1 }.map { "$at-$it" }.first { it !in ids }, text, at, known)
        change { it.copy(held = it.held + (key to (it.held[key].orEmpty() + m))) }
        return m
    }

    /** Takes [id] out of [key]'s list: sent, or discarded. */
    fun drop(key: String, id: String) = change { s ->
        val left = s.held[key].orEmpty().filterNot { it.id == id }
        s.copy(held = if (left.isEmpty()) s.held - key else s.held + (key to left))
    }

    /** The quick replies as edited: kept as typed (an empty one being written included); [QuickReplies.shown] is what shows. */
    fun setReplies(replies: QuickReplies) = change { it.copy(replies = replies) }

    @Synchronized
    private fun change(f: (State) -> State) {
        val before = _state.value
        _state.update(f)
        val next = _state.value
        if (next == before) return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(State.serializer(), next))
            tmp.renameTo(file)
        }
    }

    private fun load(): State =
        runCatching { if (file.exists()) json.decodeFromString(State.serializer(), file.readText()) else null }.getOrNull() ?: State()
}
