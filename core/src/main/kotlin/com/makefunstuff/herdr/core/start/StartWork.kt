package com.makefunstuff.herdr.core.start

import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.api.IntegrationInfo
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import kotlinx.coroutines.async

/**
 * Starting work from a sentence: what you want first, then how (a board task, or an agent now) and
 * where (the machine, a folder). The agent is started as herdr starts it, with no arguments of the
 * app's own: no mode that skips the agent's permission prompts is ever offered from the phone.
 */
object StartWork {
    /**
     * The longest title a board task gets, the board's own limit (it cuts a longer one and says so);
     * a longer sentence goes whole into its description.
     */
    const val TITLE = 60

    /** A board task's title and description from what was said: its first line, cut at a word when long. */
    fun task(text: String): Pair<String, String> {
        val t = text.trim()
        val first = t.lineSequence().first().trim()
        // Cut with room for the … , so the board takes the title as it is.
        val cut = first.take(TITLE - 1)
        val title = if (first.length <= TITLE) first else cut.substringBeforeLast(' ').ifEmpty { cut }.trimEnd(',', ';', ':', '.', ' ') + "…"
        // The description repeats nothing the title says in full.
        val desc = if (title == t) "" else t
        return title to desc
    }

    /**
     * A name for an agent started on [text]: its first few words as `add-last-answered`, kept apart
     * from the names already on the machine ([taken]) with -2, -3…; [kind] when nothing is said.
     */
    fun agentName(text: String, kind: String, taken: Collection<String>): String {
        val words = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in SMALL }.take(3)
        val base = words.joinToString("-").take(32).trimEnd('-').ifEmpty { kind }
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }

    private val SMALL = setOf("a", "an", "the", "to", "of", "and", "for", "in", "on", "it", "is", "with", "please")

    /** The agents herdr can start on a machine, as the app offers them when it can't ask which are installed. */
    val KINDS = listOf("claude", "codex", "pi", "opencode", "amp", "droid", "copilot")

    /**
     * Every kind `agent.start` takes (`herdr agent start --help`), by the program it runs. herdr has
     * an integration for only some of them, so the machine is also asked which programs it has.
     */
    val STARTABLE = listOf(
        "pi", "claude", "codex", "gemini", "cursor", "devin", "agy", "cline", "omp", "mastracode", "opencode", "copilot",
        "kimi", "kiro", "droid", "amp", "grok", "hermes", "kilo", "qodercli", "qwen", "letta", "maki", "muse",
    ).associateWith { if (it == "cursor") "cursor-agent" else it }

    private const val KIND_MARK = "@@herdr-kind "

    // Each kind, then its program: the kinds whose program is on the PATH are printed.
    private val PROBE = "while [ ${'$'}# -ge 2 ]; do command -v \"${'$'}2\" >/dev/null 2>&1 && echo \"$KIND_MARK${'$'}1\"; shift 2; done"

    /** The shell script that prints the kinds installed, with each kind and its program as its arguments. */
    fun probeScript(): String = PROBE

    /** The command that asks the machine which agents' programs it has, through a login shell (so its PATH is the one the agents get). */
    fun probeCommand(): String {
        val inner = "sh -c " + quote(PROBE) + " herdr-agent-kinds " + STARTABLE.flatMap { (k, exe) -> listOf(k, exe) }.joinToString(" ") { quote(it) }
        return "sh -c " + quote("exec \"\${SHELL:-/bin/sh}\" -lc \"\$1\" </dev/null") + " sh " + quote(inner)
    }

    /** The kinds [probeCommand] found. */
    fun probed(stdout: String): List<String> =
        stdout.lines().filter { it.startsWith(KIND_MARK) }.map { it.removePrefix(KIND_MARK).trim() }.filter { it in STARTABLE }

    /** The order the agents are offered in: the usual ones first, then the rest as herdr lists them. */
    private val FIRST = listOf("claude", "codex", "pi", "omp", "opencode", "gemini", "copilot", "droid", "amp", "cursor")

    /**
     * The agents installed on a machine, as `agent.start` names them, from herdr's integrations: the
     * ones whose program is there. herdr's target and the kind differ for two of them.
     */
    fun installed(integrations: List<IntegrationInfo>, probed: List<String> = emptyList()): List<String> =
        (integrations.filter { it.available }.map { kindOf(it.target) } + probed).distinct()
            .sortedBy { FIRST.indexOf(it).let { i -> if (i < 0) FIRST.size else i } }

    /**
     * The agents installed on [session]'s machine: those herdr's integrations find, and those whose
     * program the machine has (herdr starts some it has no integration for, such as gemini and amp).
     * [KINDS] when neither can be read.
     */
    suspend fun installedOn(session: HostSession): List<String> {
        // Both at once: on herdr-core alone (no classic herdr to ask) the probe answers while the integrations wait in vain.
        val (integrations, probed) = kotlinx.coroutines.coroutineScope {
            val integrations = async { if (session.state.value.classicMissing != null) null else runCatching { session.awaitApi(10_000).integrations() }.getOrNull() }
            val probed = async { runCatching { probed(session.awaitConnection(10_000).run(probeCommand(), timeoutSeconds = 15).stdout) }.getOrNull() }
            integrations.await() to probed.await()
        }
        if (integrations == null && probed == null) return KINDS
        return installed(integrations.orEmpty(), probed.orEmpty()).ifEmpty { KINDS }
    }

    private fun kindOf(target: String): String = when (target) {
        "antigravity_cli" -> "agy"
        else -> target
    }

    /**
     * Folders to start in on a machine: the ones used from the phone first ([used], newest first),
     * then each workspace's (its checkout, else its first pane's; a task's worktree counts as the
     * repository it was made from, and one in herdr's worktrees folder whose repository herdr doesn't
     * name is left out), without repeats, at most [max].
     */
    fun recentFolders(snapshot: SessionSnapshot?, used: List<String>, max: Int = 6): List<String> {
        val workspaces = snapshot?.workspaces.orEmpty().mapNotNull { ws ->
            val tree = ws.worktree
            when {
                tree != null && tree.isLinkedWorktree && tree.repoRoot.isNotEmpty() -> tree.repoRoot
                tree != null && tree.checkoutPath.isNotEmpty() -> tree.checkoutPath
                else -> snapshot?.panes?.firstOrNull { it.workspaceId == ws.workspaceId }?.cwd?.ifEmpty { null }
            }?.takeIf { WORKTREES !in it }
        }
        return (used + workspaces).map { it.trimEnd('/').ifEmpty { "/" } }.distinct().take(max)
    }

    /** Where herdr makes its worktrees (`~/.herdr/worktrees/<repo>/<branch>`): a task's checkout, not a place to start new work. */
    private const val WORKTREES = "/.herdr/worktrees/"

    /** A folder's name, as a chip shows it: its last part. */
    fun folderName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

    /** The workspace on [snapshot] that sits in [folder], so an agent started there opens as a tab of it. */
    fun workspaceIn(snapshot: SessionSnapshot?, folder: String): String? {
        val f = folder.trimEnd('/')
        return snapshot?.workspaces?.firstOrNull { ws ->
            ws.worktree?.checkoutPath?.trimEnd('/') == f || snapshot.panes.firstOrNull { it.workspaceId == ws.workspaceId }?.cwd?.trimEnd('/') == f
        }?.workspaceId
    }
}

/**
 * The folder browser: a folder on the machine and the folders in it, the ones that are Git
 * repositories marked. Hidden folders are left out.
 */
data class Folder(val path: String, val folders: List<Entry>) {
    data class Entry(val name: String, val repo: Boolean)

    /** The folder it is in; null at the top. */
    val parent: String?
        get() = if (path == "/") null else path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }

    fun child(name: String): String = if (path.endsWith('/')) path + name else "$path/$name"

    companion object {
        private const val MARK = "@@herdr-folder "

        // $1 the folder, or nothing for the home folder. Prints where it is, then R or D and a name for each folder in it.
        private val SCRIPT = """
            d="${'$'}{1:-${'$'}HOME}"
            case "${'$'}d" in "~") d="${'$'}HOME" ;; "~/"*) d="${'$'}HOME/${'$'}{d#\~/}" ;; esac
            cd -- "${'$'}d" 2>/dev/null || exit 3
            echo "$MARK${'$'}(pwd)"
            for e in */; do
              [ -d "${'$'}e" ] || continue
              n="${'$'}{e%/}"
              if [ -e "${'$'}n/.git" ]; then echo "R ${'$'}n"; else echo "D ${'$'}n"; fi
            done
        """.trimIndent()

        /** The command that lists [path] (the home folder when null). */
        fun command(path: String?): String = "sh -c ${quote(SCRIPT)} herdr-folder" + (path?.let { " " + quote(it) } ?: "")

        /** What [command] printed; null when the folder couldn't be opened. */
        fun parse(stdout: String): Folder? {
            val lines = stdout.lines()
            val at = lines.indexOfFirst { it.startsWith(MARK) }
            if (at < 0) return null
            val entries = lines.drop(at + 1).mapNotNull { l ->
                when {
                    l.startsWith("R ") -> Entry(l.substring(2), true)
                    l.startsWith("D ") -> Entry(l.substring(2), false)
                    else -> null
                }
            }.filter { it.name.isNotEmpty() && it.name != "*" }
            return Folder(lines[at].removePrefix(MARK).trim(), entries.sortedWith(compareBy({ !it.repo }, { it.name.lowercase() })))
        }
    }
}

/** Starting an agent now, with what it is asked first. */
object AgentLaunch {
    /**
     * Starts [kind] as [name] in [folder]: in a new tab of the workspace that sits there, else in a new
     * workspace named after the folder. Then gives it [text], when there is any. Returns its pane.
     */
    suspend fun inFolder(api: HerdrApi, snapshot: SessionSnapshot?, folder: String, kind: String, name: String, text: String): String {
        val ws = StartWork.workspaceIn(snapshot, folder)
        val pane = api.openShell(ws, if (ws != null) name else StartWork.folderName(folder), folder)
        return start(api, pane, kind, name, text)
    }

    /** Starts [kind] as [name] in a new split next to [anchor] (a workspace's focused pane), then gives it [text]. Returns its pane. */
    suspend fun beside(api: HerdrApi, anchor: String, kind: String, name: String, text: String): String {
        val before = api.snapshot().panes.map { it.paneId }.toSet()
        val pane = api.splitPane(anchor, "right")
            ?: api.snapshot().panes.firstOrNull { it.paneId !in before }?.paneId
            ?: throw java.io.IOException("herdr didn't say which pane it opened")
        return start(api, pane, kind, name, text)
    }

    private suspend fun start(api: HerdrApi, pane: String, kind: String, name: String, text: String): String {
        api.startAgent(name, kind, pane)
        if (text.isNotBlank()) api.prompt(pane, text.trim())
        return pane
    }
}
