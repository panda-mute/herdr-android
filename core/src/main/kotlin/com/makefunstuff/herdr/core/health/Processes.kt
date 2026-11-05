package com.makefunstuff.herdr.core.health

import com.makefunstuff.herdr.core.agents.AgentKinds
import com.makefunstuff.herdr.core.api.SessionSnapshot

/** What kind of program a process is, for the ones worth calling out. */
enum class ProcKind {
    Emulator, Vm, Gradle, GradleWorker, KotlinDaemon, DevServer, Node, Agent, Herdr, Other;

    /** Gradle's own daemons, which `Stop Gradle daemons` stops. */
    val gradle: Boolean get() = this == Gradle || this == KotlinDaemon
}

/** Who a process belongs to: the worktree it runs in, and the herdr pane and agent it was started from. */
data class Owner(
    /** The worktree folder it runs in, the full path. */
    val worktree: String = "",
    /** The agent of the herdr pane it was started from. */
    val agent: String = "",
    /** That pane's place, `workspace › tab`. */
    val where: String = "",
)

/** A process with what it is and whose it is. */
data class LabeledProc(
    val proc: Proc,
    val kind: ProcKind,
    /** "Gradle daemon 8.11.1", "Android emulator · Pixel_8", "node · vite", "claude". */
    val name: String,
    val owner: Owner,
    val own: Boolean,
) {
    /** One line on whose it is: its worktree, else the agent and its pane, else the folder or command. */
    val ownerLine: String get() = when {
        owner.worktree.isNotEmpty() -> "worktree ${owner.worktree.substringAfterLast('/')}"
        owner.agent.isNotEmpty() -> listOf(owner.agent, owner.where).filter { it.isNotEmpty() }.joinToString(" · ")
        owner.where.isNotEmpty() -> "herdr pane · ${owner.where}"
        else -> proc.args.ifEmpty { proc.comm }
    }
}

object ProcLabels {
    private val WORKTREE = Regex("""^(.*/\.herdr/worktrees/[^/]+/[^/]+)(/.*)?$""")
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
     * Labels each process of [sample] with what it is and whose it is: the worktree it runs in and the
     * herdr pane it was started from (from [snapshot]).
     */
    fun label(sample: HealthSample, snapshot: SessionSnapshot?): List<LabeledProc> =
        sample.procs.map { p ->
            val (kind, name) = kind(p)
            val own = sample.own(p)
            val worktree = worktreeOf(p.cwd) ?: worktreeOf(p.launchDir)
            val pane = snapshot?.pane(p.paneId)
            val agent = snapshot?.agents?.firstOrNull { it.paneId == p.paneId }
            val owner = Owner(
                worktree = worktree.orEmpty(),
                agent = (agent?.name ?: agent?.agent ?: pane?.displayAgent ?: pane?.agent).orEmpty(),
                where = pane?.let { snapshot.location(it.workspaceId, it.tabId) }.orEmpty(),
            )
            LabeledProc(p, kind, name, owner, own)
        }

    /** The processes [HealthCommands.kill] stops for a group: the pids, each with the name it must still have. */
    fun targets(procs: List<LabeledProc>): List<HealthCommands.Target> =
        procs.distinctBy { it.proc.pid }.map { HealthCommands.Target(it.proc.pid, it.proc.comm) }
}
