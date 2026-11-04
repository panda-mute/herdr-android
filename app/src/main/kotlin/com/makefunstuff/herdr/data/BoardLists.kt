package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.WorkOverview
import com.makefunstuff.herdr.core.board.WorkTasks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Every machine's work board as the app last read it, for the Agents screen's Needs you list and
 * the Board tab. Read here ([readAll]) while one of them is on screen, and told of every other
 * read of a whole list (a machine's board screen, the review cards, the widget's look now and then).
 */
class BoardLists(
    private val hosts: HostManager,
    private val onRead: (String, List<BoardTask>) -> Unit = { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * One board's list; [problem] when the last read failed (the tasks are then the read before).
     * On Shepherdr 3, [work] is the board as it sent it (its spaces) and [tasks] its tasks in v1's row shape.
     */
    data class Read(val tasks: List<BoardTask>, val problem: String? = null, val work: WorkOverview? = null)

    private val _lists = MutableStateFlow<Map<String, Read>>(emptyMap())
    /** When each board was last read, so two screens reading at once (a wide window's) don't read it twice. */
    private val readAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** By machine id; a machine never read isn't in it. */
    val lists: StateFlow<Map<String, Read>> = _lists.asStateFlow()

    /** [machineId]'s whole list, read somewhere in the app. */
    fun put(machineId: String, tasks: List<BoardTask>) {
        readAt[machineId] = now()
        _lists.update { it + (machineId to Read(tasks)) }
    }

    /** [machineId]'s Shepherdr 3 board, read somewhere in the app. */
    fun put(machineId: String, work: WorkOverview) {
        readAt[machineId] = now()
        _lists.update { it + (machineId to Read(work.tasks.map(WorkTasks::row), work = work)) }
    }

    /** Reads every connected machine's board now, side by side; one read within [freshMs] is left as it is. */
    suspend fun readAll(freshMs: Long = 3_000) = coroutineScope {
        val t = now()
        hosts.hosts.value.filter { it.online && it.hasWorkBoard && t - (readAt[it.machine.id] ?: 0L) >= freshMs }
            .map { h -> async { read(h.machine.id) } }.awaitAll()
        Unit
    }

    /** Reads [machineId]'s board now, unless it was read within [freshMs]: for a screen about one machine. */
    suspend fun readOne(machineId: String, freshMs: Long = 3_000) {
        if (now() - (readAt[machineId] ?: 0L) >= freshMs) read(machineId)
    }

    private suspend fun read(machineId: String) {
        try {
            val session = hosts.session(machineId) ?: return
            val tasks = if (session.state.value.onShepherdr) {
                val work = withTimeout(30_000) { session.awaitWorkBoard().overview() }
                put(machineId, work)
                work.tasks.map(WorkTasks::row)
            } else {
                withTimeout(30_000) { session.awaitBoard().tasks() }.also { put(machineId, it) }
            }
            onRead(machineId, tasks)
        } catch (e: CancellationException) {
            if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            failed(machineId, "The board took too long to answer")
        } catch (t: Throwable) {
            failed(machineId, t.message ?: "Couldn't read the board")
        }
    }

    private fun failed(machineId: String, problem: String) {
        _lists.update { it + (machineId to Read(it[machineId]?.tasks.orEmpty(), problem, it[machineId]?.work)) }
    }
}

/**
 * Which finish of each agent the phone has shown ([com.makefunstuff.herdr.core.home.HomeAgent.finish],
 * by `machine/pane`): a finished agent is "new" on the Agents screen until its chat is opened here,
 * swiped right, or Mark all seen is pressed, or it is looked at on herdr's own screen (herdr then says idle).
 */
class SeenStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Long.serializer())
    private val _marks = MutableStateFlow(load())
    val marks: StateFlow<Map<String, Long>> = _marks.asStateFlow()

    /** [machineId]'s [paneId] seen at its finish [finish]. */
    fun mark(machineId: String, paneId: String, finish: Long) = set(_marks.value + ("$machineId/$paneId" to finish))

    @Synchronized
    fun set(marks: Map<String, Long>) {
        if (marks == _marks.value) return
        _marks.value = marks
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(serializer, marks))
            tmp.renameTo(file)
        }
    }

    private fun load(): Map<String, Long> =
        runCatching { if (file.exists()) json.decodeFromString(serializer, file.readText()) else null }.getOrNull() ?: emptyMap()
}
