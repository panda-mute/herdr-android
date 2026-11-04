package com.makefunstuff.herdr.core.shepherdr

import com.makefunstuff.herdr.core.board.WorkSpace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Shepherdr 3's artifacts as the app shows them: which a space keeps, and how a version reads. */
object Artifacts {
    /**
     * [space]'s artifacts, newest first, as Shepherdr's own Artifacts list has them: those in its store (a repo's main
     * folder, which its worktree spaces share: [WorkSpace.store]), else those that name it. [spaces] says where each lives.
     */
    fun inSpace(all: List<V2Artifact>, space: String, spaces: List<WorkSpace> = emptyList()): List<V2Artifact> {
        val store = spaces.firstOrNull { it.id == space }?.store.orEmpty()
        return all.filter { a -> a.place == space || store.isNotEmpty() && a.store == store }.sortedByDescending { it.at }
    }

    /** What is sent to a keeper: at most 4000 characters, as the service keeps. */
    fun line(text: String): String = text.trim().take(4000)

    /** How a version reads in its list: "v3 · latest · bars by week", "v2 · new data". */
    fun versionLine(a: V2Artifact, v: V2ArtifactVersion): String = buildString {
        append("v").append(v.n)
        if (v.n == a.v) append(" · latest")
        val what = v.note?.takeIf { it.isNotBlank() } ?: when (v.what) {
            "data" -> "new data"
            "title" -> "new title"
            else -> null
        }
        what?.let { append(" · ").append(it) }
    }
}

/** Shepherdr didn't serve the artifact: it was deleted, or its artifacts are turned off there. Asking again won't help. */
class ArtifactGone(message: String) : java.io.IOException(message)

/**
 * The bodies of `POST /api/action` for an artifact: a recipe's answer (a choice, a form, a button), a word to its
 * keeper, a note on one of its blocks ([target], its id).
 */
object V2ArtifactActions {
    fun send(a: V2Artifact, v: Int, text: String): JsonObject = action("artifact-send", a, v, "text" to text)

    fun say(a: V2Artifact, v: Int, text: String): JsonObject = action("artifact-say", a, v, "text" to text)

    fun note(a: V2Artifact, v: Int, note: String, target: String?): JsonObject =
        action("artifact-note", a, v, "note" to note, *listOfNotNull(target?.let { "target" to it }).toTypedArray())

    private fun action(type: String, a: V2Artifact, v: Int, vararg words: Pair<String, String>) = JsonObject(
        mapOf("type" to JsonPrimitive(type), "place" to JsonPrimitive(a.place), "artifact" to JsonPrimitive(a.id), "v" to JsonPrimitive(v)) +
            words.associate { it.first to JsonPrimitive(it.second) },
    )
}
