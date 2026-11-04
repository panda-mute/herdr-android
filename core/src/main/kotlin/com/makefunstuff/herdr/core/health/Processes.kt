package com.makefunstuff.herdr.core.health

import com.makefunstuff.herdr.core.agents.AgentKinds
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.api.WorkspaceInfo
import com.makefunstuff.herdr.core.board.BoardStatus
import com.makefunstuff.herdr.core.board.BoardTask

/** What kind of program a process is, for the ones worth calling out. */
enum class ProcKind {
    Emulator, Vm, Gradle, GradleWorker, KotlinDaemon, DevServer, Node, Agent, Herdr, Other;

    /** Gradle's own daemons, which `Stop Gradle daemons` stops. */
    val gradle: Boolean get() = this == Gradle || this == KotlinDaemon
}

/** Who a process belongs to: a board task (by the worktree it runs in), and the herdr pane and agent it was started from. */
data class Owner(
    /** `<repo>/<number>`, when it runs in a board task's worktree. */
    val taskId: String = "",
    /** The task's short key (`AND-22`), or its id. */
    val taskName: String = "",
    val taskTitle: String = "",
    val taskStatus: BoardStatus? = null,
    /** The worktree folder it runs in, the full path. */
    val worktree: String = "",
    /** The agent of the herdr pane it was started from. */
    val agent: String = "",
    /** That pane's place, `workspace › tab`. */
    val where: String = "",
) {
    val finished: Boolean get() = taskStatus == BoardStatus.Done || taskStatus == BoardStatus.Archived
}

/** A process with what it is and whose it is. [leftover]: the account's own, of a finished task, safe to stop. */
data class LabeledProc(
    val proc: Proc,
    val kind: ProcKind,
    /** "Gradle daemon 8.11.1", "Android emulator · Pixel_8", "node · vite", "claude". */
    val name: String,
    val owner: Owner,
    val own: Boolean,
    val leftover: Boolean,
) {
    /** One line on whose it is: the task and its state, else the agent and its pane, else the folder or command. */
    val ownerLine: String get() = when {
        owner.taskId.isNotEmpty() -> listOfNotNull(
            owner.taskName, owner.taskStatus?.label, owner.taskTitle.ifEmpty { null },
        ).joinToString(" · ")
        owner.worktree.isNotEmpty() -> "worktree ${owner.worktree.substringAfterLast('/')}"
        owner.agent.isNotEmpty() -> listOf(owner.agent, owner.where).filter { it.isNotEmpty() }.joinToString(" · ")
        owner.where.isNotEmpty() -> "herdr pane · ${owner.where}"
        else -> proc.args.ifEmpty { proc.comm }
    }
}

/** A finished task's leftovers: its processes still running and its worktree still on disk. */
data class Leftover(
    val taskId: String,
    val taskName: String,
    val taskTitle: String,
    val status: BoardStatus,
    val procs: List<LabeledProc>,
    /** The worktree's folder, when it is still there. */
    val worktree: WorktreeDir?,
    /** The herdr workspace open on that worktree, if any: removing it closes it too. */
    val workspace: WorkspaceInfo? = null,
) {
    val rssKb: Long get() = procs.sumOf { it.proc.rssKb }
}

object ProcLabels {
    private val WORKTREE = Regex("""^(.*/\.herdr/worktrees/[^/]+/[^/]+)(/.*)?$""")
    private val TASK_DIR = Regex("""^work-(\d+)(-|$)""")
    private val DEV = listOf(
        "vite", "next dev", "next-server", "webpack-dev-server", "webpack serve", "react-scripts start", "nuxt", "astro dev",
        "remix dev", "parcel", "storybook", "expo start", "metro", "ng serve", "svelte-kit dev", "turbo dev", "wrangler dev",
        "manage.py runserver", "flask run", "uvicorn", "gunicorn", "http.server", "rails server", "rails s", "hugo server",
        "jekyll serve", "npm run dev", "pnpm dev", "yarn dev", "bun dev", "bun run dev", "npm start", "live-server", "serve -",
    )

    /** The agent a process runs ([AgentKinds]): by its program, or by its command line [a]. */
    private fun agentOf(exe: String, c: String, a: String): String? =
        AgentKinds.normalize(exe) ?: AgentKinds.normalize(c) ?: AgentKinds.fromArgv(a.split(' ').filter { it.isNotEmpty() })

    /** The worktree folder [path] is in (`…/.herdr/worktrees/<repo>/<folder>`), or null. */
    fun worktreeOf(path: String): String? = WORKTREE.find(path)?.groupValues?.get(1)

    /** `<repo>/<number>` for a board task's worktree folder (`…/<repo>/work-<number>-<slug>`), else null. */
    fun taskIdOf(worktree: String): String? {
        val folder = worktree.trimEnd('/').substringAfterLast('/')
        val repo = worktree.trimEnd('/').substringBeforeLast('/').substringAfterLast('/')
        val n = TASK_DIR.find(folder)?.groupValues?.get(1) ?: return null
        return "$repo/$n"
    }

    /** What [p] is, and what to call it. */
    fun kind(p: Proc): Pair<ProcKind, String> {
        val a = p.args
        val c = p.comm.lowercase()
        val exe = a.substringBefore(' ').substringAfterLast('/').lowercase()
        return when {
            c.startsWith("qemu-system") || c.startsWith("emulator") || exe.startsWith("qemu-system") -> {
                val avd = Regex("""(?:^|\s)-avd\s+(\S+)""").find(a)?.groupValues?.get(1) ?: Regex("""(?:^|\s)@(\S+)""").find(a)?.groupValues?.get(1)
                if (avd != null || "/emulator/" in a || "android" in a.lowercase()) {
                    ProcKind.Emulator to ("Android emulator" + (avd?.let { " · $it" } ?: ""))
                } else {
                    val guest = Regex("""-name\s+(?:guest=)?([^,\s]+)""").find(a)?.groupValues?.get(1)
                    ProcKind.Vm to ("QEMU VM" + (guest?.let { " · $it" } ?: ""))
                }
            }
            "GradleDaemon" in a -> {
                val v = (Regex("""GradleDaemon\s+(\d+(?:\.\d+)+(?:-rc-\d+)?)""").find(a) ?: Regex("""gradle-(\d+(?:\.\d+)+(?:-rc-\d+)?)""").find(a))
                    ?.groupValues?.get(1)
                ProcKind.Gradle to ("Gradle daemon" + (v?.let { " $it" } ?: ""))
            }
            "GradleWorkerMain" in a || "GradleWrapperMain" in a || "org.gradle.launcher.GradleMain" in a ->
                ProcKind.GradleWorker to if ("Worker" in a) "Gradle worker" else "Gradle build"
            "KotlinCompileDaemon" in a -> ProcKind.KotlinDaemon to "Kotlin daemon"
            agentOf(exe, c, a) != null -> ProcKind.Agent to agentOf(exe, c, a)!!
            DEV.any { it in a } -> {
                val tool = DEV.first { it in a }.substringBefore(' ').substringBefore('-').removeSuffix(".py")
                ProcKind.DevServer to "Dev server · $tool"
            }
            c == "node" || c == "bun" || c == "deno" || exe == "node" || exe == "bun" || exe == "deno" -> {
                val script = a.split(' ').drop(1).firstOrNull { !it.startsWith("-") }?.substringAfterLast('/')
                ProcKind.Node to (c.ifEmpty { exe } + (script?.let { " · $it" } ?: ""))
            }
            c.startsWith("herdr") -> ProcKind.Herdr to c
            else -> ProcKind.Other to p.comm.ifEmpty { exe }
        }
    }

    /**
     * Labels each process of [sample] with what it is and whose it is: the board task whose worktree
     * it runs in (from [tasks], which may be null without a board) and the herdr pane it was started
     * from (from [snapshot]).
     */
    fun label(sample: HealthSample, tasks: List<BoardTask>?, snapshot: SessionSnapshot?, session: String = "default"): List<LabeledProc> {
        val byId = tasks.orEmpty().filter { ':' !in it.id }.associateBy { it.id }
        return sample.procs.map { p ->
            val (kind, name) = kind(p)
            val own = sample.own(p)
            val worktree = worktreeOf(p.cwd) ?: worktreeOf(p.launchDir)
            val taskId = worktree?.let(::taskIdOf).orEmpty()
            val task = byId[taskId]
            val pane = snapshot?.pane(p.paneId)
            val agent = snapshot?.agents?.firstOrNull { it.paneId == p.paneId }
            val owner = Owner(
                taskId = if (task != null) taskId else "",
                taskName = task?.name.orEmpty(),
                taskTitle = task?.title.orEmpty(),
                taskStatus = task?.status,
                worktree = worktree.orEmpty(),
                agent = (agent?.name ?: agent?.agent ?: pane?.displayAgent ?: pane?.agent).orEmpty(),
                where = pane?.let { snapshot.location(it.workspaceId, it.tabId) }.orEmpty(),
            )
            LabeledProc(p, kind, name, owner, own, leftover = own && owner.finished && safeToStop(p, kind, session))
        }
    }

    /**
     * Whether a finished task's process may be stopped with its leftovers. Not what other work shares:
     * adb's server (every emulator and phone goes through it), this machine's own herdr server (a test
     * server an agent started for another session may go), and a Gradle or Kotlin daemon while it
     * builds (another task's build may have picked it up since; an idle one starts again when needed).
     */
    fun safeToStop(p: Proc, kind: ProcKind, session: String = "default"): Boolean = when {
        p.comm == "adb" || p.args.substringBefore(' ').endsWith("/adb") -> false
        kind == ProcKind.Herdr -> {
            val other = Regex("""--session[ =](\S+)""").find(p.args)?.groupValues?.get(1)
            other != null && other != session
        }
        kind.gradle -> p.cpu < 1.0
        else -> true
    }

    /**
     * The worktree folders to list every process of, for [HealthCommands.probe]: the finished tasks'
     * (`<repo>/work-<n>-`), so their leftovers show however small they are.
     */
    fun keep(tasks: List<BoardTask>?): List<String> = tasks.orEmpty()
        .filter { ':' !in it.id && (it.status == BoardStatus.Done || it.status == BoardStatus.Archived) }
        .map { "${it.repo}/work-${it.number}-" }

    /**
     * Each finished task's leftovers: its own processes still running and its worktree still on disk
     * ([worktrees], null when not read yet). A task with neither isn't listed.
     */
    fun leftovers(procs: List<LabeledProc>, tasks: List<BoardTask>?, worktrees: List<WorktreeDir>?, snapshot: SessionSnapshot?): List<Leftover> {
        val finished = tasks.orEmpty().filter { ':' !in it.id && (it.status == BoardStatus.Done || it.status == BoardStatus.Archived) }
        if (finished.isEmpty()) return emptyList()
        val dirs = worktrees.orEmpty().groupBy { taskIdOf(it.path) }
        return finished.mapNotNull { t ->
            val mine = procs.filter { it.leftover && it.owner.taskId == t.id }
            val dir = dirs[t.id]?.firstOrNull()
            if (mine.isEmpty() && dir == null) return@mapNotNull null
            val ws = dir?.let { d -> snapshot?.workspaces?.firstOrNull { it.worktree?.checkoutPath?.trimEnd('/') == d.path.trimEnd('/') } }
            Leftover(t.id, t.name, t.title, t.status, mine.sortedByDescending { it.proc.rssKb }, dir, ws)
        }.sortedWith(compareByDescending<Leftover> { it.procs.isNotEmpty() }.thenByDescending { it.rssKb + (it.worktree?.sizeKb ?: 0) / 64 })
    }

    /** The processes [HealthCommands.kill] stops for a group: the pids, each with the name it must still have. */
    fun targets(procs: List<LabeledProc>): List<HealthCommands.Target> =
        procs.distinctBy { it.proc.pid }.map { HealthCommands.Target(it.proc.pid, it.proc.comm) }
}
