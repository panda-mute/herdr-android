package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.home.Home
import com.makefunstuff.herdr.core.shepherdr.V2Chat

/**
 * Whose a chat is, as its header says it in 3.1's way (`docs/parity/header.md`: "Assistant · Claude Code" over its
 * folder, `~` for home): one line of who, space, machine (when there are several) and folder or branch.
 */
object ChatHeader {
    private val HOME = Regex("""^(/home/[^/]+|/Users/[^/]+|/root)(?=/|$)""")

    /** [path] as 3.1 shows a folder: the home folder as `~`; null for none. */
    fun folder(path: String?): String? = path?.trim()?.ifEmpty { null }?.let { HOME.replace(it, "~") }

    /** The parts that say something, each once, as one line. */
    fun line(vararg parts: String?): String =
        parts.mapNotNull { it?.trim()?.ifEmpty { null } }.distinctBy { it.lowercase() }.joinToString(" · ")

    /** [machine] when the phone has more than one, so a header names it only where it tells chats apart. */
    fun machine(machine: String, machines: Int): String? = machine.takeIf { machines > 1 }

    /** A task agent's header title: its ID and title (`HERD-30 · Chats show…`). */
    fun task(name: String, title: String): String = line(name, title)

    /**
     * Who [a] is, as its chat's title: a space's Assistant as 3.1 calls it, else its own name unless that is only its
     * CLI's (or a launcher's hash), else what its terminal's title says it works on (Claude Code names its session
     * there: two in one folder read apart), else its [space], else its CLI. A task agent's title is [task]'s.
     */
    fun agentTitle(a: AgentInfo, task: String? = null, space: String? = null): String =
        task ?: if (a.shepherdrSpace != null) "Assistant"
        else a.name?.trim()?.takeIf { it.isNotEmpty() && !Home.isBareName(a, it) }
            ?: a.terminalTitle?.trim()?.ifEmpty { null }
            ?: space?.trim()?.ifEmpty { null }
            ?: a.displayName

    /**
     * The line under it: its CLI (unless the title is it), where it runs (`space › tab`, or the space alone for an
     * Assistant), its machine when the phone has several, and its folder.
     */
    fun agentLine(a: AgentInfo, title: String, place: String?, machine: String?, folder: String? = a.cwd): String =
        line(a.agent?.takeIf { !it.equals(title, ignoreCase = true) }, place, machine, folder(folder))
}

/**
 * Which herdr-core agent a Shepherdr chat belongs to, and when the app reads that agent's own transcript in its
 * place: Shepherdr answers "Can't find this conversation" (`notice: none`) for agents whose log it misses, while
 * the app finds it from the agent's process (seen on 3.1.2 for a task's worker and Assistants started before the
 * desktop app showed them).
 */
object ChatOwner {
    /** Over the agent's own transcript where Shepherdr's chat lost its log. */
    const val NOTE = "Shepherdr can't find this chat's log, so this is the agent's own session, read from its process."

    /** The live agent working on task [taskId] (`HERD-30`; `vm:HERD-8` on a machine hc added); not its reviewer. */
    fun taskAgent(agents: List<AgentInfo>, taskId: String): AgentInfo? = agents.firstOrNull { it.shepherdrTask == taskId }

    /** The live Assistant of space [spaceId]. */
    fun assistant(agents: List<AgentInfo>, spaceId: String): AgentInfo? = agents.firstOrNull { it.shepherdrSpace == spaceId }

    /** Show [agent]'s own transcript instead of [chat]: Shepherdr found no log, and the app knows where it is. */
    fun readsOwn(chat: V2Chat?, agent: AgentInfo?): Boolean =
        chat != null && chat.notice == "none" && agent?.agentSession?.value?.isNotEmpty() == true
}
