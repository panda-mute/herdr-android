package com.makefunstuff.herdr.data

import android.content.Context
import com.makefunstuff.herdr.core.share.ShareText
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * What you were typing in each chat, kept in the app's preferences so a draft survives leaving the
 * chat, a rotation and a restart of the app. A draft is removed when it is empty (sent or erased).
 */
class DraftStore(context: Context) {
    private val prefs = context.getSharedPreferences("drafts", Context.MODE_PRIVATE)

    fun get(key: String): String = prefs.getString(key, null).orEmpty()

    private val _added = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** The keys of drafts something was added to from outside their chat ([add]), so an open chat shows it. */
    val added: SharedFlow<String> = _added.asSharedFlow()

    fun put(key: String, text: String) {
        if (text == get(key)) return
        prefs.edit().apply { if (text.isEmpty()) remove(key) else putString(key, text) }.apply()
    }

    /** Adds [text] to the end of the draft under [key] from outside its chat (something shared to it). */
    @Synchronized
    fun add(key: String, text: String) {
        put(key, ShareText.intoDraft(get(key), text))
        _added.tryEmit(key)
    }

    companion object {
        /** An agent's chat: one draft per pane on each machine. */
        fun agent(machineId: String, paneId: String) = "agent/$machineId/$paneId"

        /** The work board's Coordinator chat on a machine; on Shepherdr 3, one per space. */
        fun coordinator(machineId: String, space: String? = null) = "coordinator/$machineId" + (space?.let { "/$it" } ?: "")

        /** A Shepherdr 3 task's own chat, to its agent: one draft per task on each machine. */
        fun task(machineId: String, task: String) = "task/$machineId/$task"

        /**
         * The draft once [sent] has gone: empty, or what was typed on after it while it was on its
         * way (the box stays open while a message is sent). A draft edited in what was sent is kept whole.
         */
        fun afterSend(draft: String, sent: String): String = when {
            draft == sent -> ""
            draft.startsWith(sent) -> draft.removePrefix(sent).trimStart()
            else -> draft
        }
    }
}
