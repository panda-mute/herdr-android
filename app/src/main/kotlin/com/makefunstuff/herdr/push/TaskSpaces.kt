package com.makefunstuff.herdr.push

import com.makefunstuff.herdr.core.board.WorkOverview
import com.makefunstuff.herdr.core.push.TaskAlerts
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Which space each Shepherdr 3 task (and Assistant) is in, by machine, as the app last read its board: a task
 * alert names only its task, and its space's Notify decides whether it shows. Kept in a file, since alerts come
 * while the app is closed. A task never read here follows the notification settings.
 */
class TaskSpaces(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer()))
    private val byMachine: MutableMap<String, Map<String, String>> = load()

    /** [machineId]'s board as just read. */
    @Synchronized
    fun put(machineId: String, o: WorkOverview) {
        val now = TaskAlerts.spaces(o)
        if (byMachine[machineId] == now) return
        byMachine[machineId] = now
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(serializer, byMachine))
            tmp.renameTo(file)
        }
    }

    /** The space name of what alert tag [tag] (a task's id, an Assistant's chat) is about on [machineId]. */
    @Synchronized
    fun of(machineId: String, tag: String): String? = byMachine[machineId]?.get(tag)

    private fun load(): MutableMap<String, Map<String, String>> =
        runCatching { if (file.exists()) json.decodeFromString(serializer, file.readText()).toMutableMap() else null }.getOrNull() ?: mutableMapOf()
}
