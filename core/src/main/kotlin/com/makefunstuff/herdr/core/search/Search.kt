package com.makefunstuff.herdr.core.search

import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.WorkspaceInfo
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.home.Home
import com.makefunstuff.herdr.core.home.HomeAgent
import com.makefunstuff.herdr.core.home.HomeBoard
import com.makefunstuff.herdr.core.host.HostState

/** The search box's chips: everything, or the agents and tasks in one state. */
enum class SearchFilter(val label: String) {
    All("All"), NeedsYou("Needs you"), Working("Working"), Done("Done");

    companion object {
        fun of(status: AgentStatus): SearchFilter? = when (status) {
            AgentStatus.Blocked -> NeedsYou
            AgentStatus.Working -> Working
            AgentStatus.Done -> Done
            else -> null
        }

        fun of(task: WorkTask): SearchFilter? = when {
            task.status.needsYou -> NeedsYou
            task.status == WorkStatus.Work || task.status == WorkStatus.Merging -> Working
            task.status == WorkStatus.Done -> Done
            else -> null
        }
    }
}

/** An agent found, with the task it works on, when it works on one. */
data class AgentHit(val agent: HomeAgent, val task: WorkTask?) {
    val state: SearchFilter? get() = SearchFilter.of(agent.agent.agentStatus)
    /** What the list calls it: its task's title, else what herdr says it does, else its name. */
    val title: String get() = task?.title ?: agent.agent.subtitle ?: agent.agent.displayName
}

data class TaskHit(val machineId: String, val machineLabel: String, val task: WorkTask) {
    val state: SearchFilter? get() = SearchFilter.of(task)
}

data class MachineHit(val host: HostState) {
    val machineId: String get() = host.machine.id
}

/** A workspace found: its machine, and the folder it sits in (its checkout, else its first pane's). */
data class WorkspaceHit(
    val machineId: String,
    val machineLabel: String,
    val workspace: WorkspaceInfo,
    val folder: String?,
    /** The branch its integration reports, when it does. */
    val branch: String? = null,
) {
    val name: String get() = workspace.label.ifBlank { workspace.workspaceId }
}

/** What one query found across the machines, best first in each kind. */
data class SearchResults(
    val agents: List<AgentHit> = emptyList(),
    val tasks: List<TaskHit> = emptyList(),
    val machines: List<MachineHit> = emptyList(),
    val workspaces: List<WorkspaceHit> = emptyList(),
    /** How many agents and tasks each chip would show; [SearchFilter.All] counts machines and workspaces too. */
    val counts: Map<SearchFilter, Int> = emptyMap(),
) {
    val isEmpty: Boolean get() = agents.isEmpty() && tasks.isEmpty() && machines.isEmpty() && workspaces.isEmpty()
}

/**
 * One box for everything the app holds: the machines' agents and workspaces (their last snapshots),
 * the boards' tasks (as last read), and the machines. Every word of the query has to be somewhere in
 * an item; a word at the start of its name counts most. An empty query lists what is there, the
 * agents and tasks waiting for you first.
 */
object Search {
    /** Most rows of one kind shown at once. */
    const val LIMIT = 8

    fun of(
        query: String,
        hosts: List<HostState>,
        boards: List<HomeBoard>,
        filter: SearchFilter = SearchFilter.All,
        /** Only this machine's, when the search was opened from one. */
        machineId: String? = null,
    ): SearchResults {
        val words = words(query)
        val inScope = hosts.filter { machineId == null || it.machine.id == machineId }
        val ownBoards = boards.filter { machineId == null || it.machineId == machineId }
        val tasksOn = ownBoards.associate { b -> b.machineId to b.tasks }
        val agents = HomeAgent.of(inScope).map { a -> AgentHit(a, Home.taskOf(a, tasksOn[a.machineId].orEmpty())) }
        val tasks = ownBoards.flatMap { b -> tasksOn[b.machineId].orEmpty().map { TaskHit(b.machineId, b.machineLabel, it) } }

        val agentScores = agents.score(words) { h ->
            listOf(h.title, h.agent.agent.name.orEmpty(), h.agent.agent.displayName, h.task?.name.orEmpty(), h.agent.workspace, h.agent.machineLabel)
        }
        // With nothing typed, only the tasks that wait for you: the rest of the board is a tap away.
        val taskScores = tasks.score(words) { h -> listOf(h.task.title, h.task.name, h.task.listName, h.machineLabel) }
            .filter { words.isNotEmpty() || it.first.task.status.needsYou }
        val machineScores = if (words.isEmpty()) emptyList() else inScope.map(::MachineHit).score(words) { h -> listOf(h.host.machine.label, h.host.machine.target) }
        val workspaces = if (words.isEmpty()) emptyList() else inScope.flatMap { s ->
            val snap = s.snapshot ?: return@flatMap emptyList()
            snap.workspaces.map { ws ->
                val folder = ws.worktree?.checkoutPath?.ifEmpty { null } ?: snap.panes.firstOrNull { it.workspaceId == ws.workspaceId }?.cwd
                WorkspaceHit(s.machine.id, s.machine.label, ws, folder, s.workspaceMeta[ws.workspaceId]?.get("branch")?.ifEmpty { null })
            }
        }.score(words) { h -> listOf(h.name, h.workspace.worktree?.repoName.orEmpty(), h.folder.orEmpty(), h.branch.orEmpty(), h.machineLabel) }

        val counts = SearchFilter.entries.associateWith { f ->
            when (f) {
                SearchFilter.All -> agentScores.size + taskScores.size + machineScores.size + workspaces.size
                else -> agentScores.count { it.first.state == f } + taskScores.count { it.first.state == f }
            }
        }
        fun <T> List<Pair<T, Int>>.best(state: (T) -> SearchFilter?): List<T> =
            filter { filter == SearchFilter.All || state(it.first) == filter }
                // Equal scores: what waits for you first, then as the machines list them.
                .sortedWith(compareByDescending<Pair<T, Int>> { it.second }.thenBy { rank(state(it.first)) })
                .take(LIMIT).map { it.first }
        val all = filter == SearchFilter.All
        return SearchResults(
            agents = agentScores.best { it.state },
            tasks = taskScores.best { it.state },
            machines = if (all) machineScores.best { null } else emptyList(),
            workspaces = if (all) workspaces.best { null } else emptyList(),
            counts = counts,
        )
    }

    private fun rank(f: SearchFilter?): Int = when (f) {
        SearchFilter.NeedsYou -> 0
        SearchFilter.Working -> 1
        SearchFilter.Done -> 2
        else -> 3
    }

    /** The query's words, in lower case. */
    fun words(query: String): List<String> = query.lowercase().split(SPACE).filter { it.isNotEmpty() }

    private inline fun <T> List<T>.score(words: List<String>, fields: (T) -> List<String>): List<Pair<T, Int>> =
        mapNotNull { item -> score(words, fields(item)).takeIf { it > 0 }?.let { item to it } }

    /**
     * How well [fields] (the name first) hold every one of [words]: 0 when one is missing. A word
     * that starts the name counts 4, one that starts a word of the name 3, of another field 2, inside
     * any field 1. With no words everything counts 1.
     */
    fun score(words: List<String>, fields: List<String>): Int {
        if (words.isEmpty()) return 1
        val lower = fields.map { it.lowercase() }
        var total = 0
        for (w in words) {
            var best = 0
            lower.forEachIndexed { i, f ->
                val at = f.indexOf(w)
                if (at < 0) return@forEachIndexed
                val s = when {
                    i == 0 && at == 0 -> 4
                    startsWord(f, w) -> if (i == 0) 3 else 2
                    else -> 1
                }
                if (s > best) best = s
            }
            if (best == 0) return 0
            total += best
        }
        return total
    }

    /** Whether [w] starts one of [f]'s words (after a space, a slash, a dash, a dot…). */
    private fun startsWord(f: String, w: String): Boolean {
        var at = f.indexOf(w)
        while (at >= 0) {
            if (at == 0 || !f[at - 1].isLetterOrDigit()) return true
            at = f.indexOf(w, at + 1)
        }
        return false
    }

    private val SPACE = Regex("\\s+")
}
