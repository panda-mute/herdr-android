package com.makefunstuff.herdr.core.board

import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.hc.HcMapping

/**
 * One of a space's tabs as Shepherdr 3.1's tab bar has them (its web's `barTabs`): the Assistant's first, then the
 * others. What the tab menu acts on.
 */
data class WorkTab(
    /** The Herdr window's tab id; null for an Assistant that has no tab yet. */
    val id: String?,
    /** As the bar shows it. */
    val name: String,
    /** Someone gave it [name]: Rename's field starts with it. */
    val named: Boolean = false,
    /** Its agent's chat key: the Assistant's `p:<space>`, another tab's `t:<address>`. */
    val chat: String,
    /** The known agent running in it (`claude`, `codex`, `pi`), or null for a shell. */
    val agent: String? = null,
    /** `working`, `blocked`, `done` or `idle`. */
    val state: String = "idle",
    val assistant: Boolean = false,
    /** Its agent runs (an Assistant's; another tab's is its [agent]). */
    val live: Boolean = false,
    /** On another machine than the service's: its agent can't be restarted from here. */
    val remote: Boolean = false,
)

/** An agent Switch agent offers (`tab-agents`): its kind, its name, and whether it is installed on that machine. */
data class WorkAgentChoice(val kind: String, val name: String, val installed: Boolean)

/** The tab menu's rules: 3.1's `tabMenu` (`core/menus.js`) and `closeTab` (`web/plugins/coordinators`), on a phone. */
object TabMenus {
    /** A row of the menu, in its order; a line goes before [Close]. */
    enum class Row(val label: String) {
        Rename("Rename…"),
        Switch("Switch agent"),
        Restart("Restart"),
        Model("Model…"),
        Close("Close tab"),
    }

    /** Switch agent's heading, its list while it is read, and the agent that runs now (`SWITCH_AGENT`). */
    const val SWITCH_TITLE = "Switch agent"
    const val SWITCH_LOADING = "Checking installed agents…"
    const val SWITCH_CURRENT = "Current"

    /** The CLIs whose models the model menu knows (`core/agents.js` `menuFor`). */
    private val MODEL_CLIS = setOf("claude", "codex")

    /**
     * [tab]'s rows: Rename… always; Switch agent and Restart while an agent runs in it on this machine; Model… for
     * Claude Code and Codex there; Close tab always. 3.1's "Edit instructions…" (the Assistant's template, a file on
     * the desktop) is the window's only.
     */
    fun rows(tab: WorkTab): List<Row> {
        val agent = !tab.remote && (if (tab.assistant) tab.live else tab.agent != null)
        return buildList {
            add(Row.Rename)
            if (agent) {
                add(Row.Switch)
                add(Row.Restart)
                // An Assistant whose CLI isn't known yet is Claude Code, as the service reads it.
                if ((tab.agent ?: "claude") in MODEL_CLIS) add(Row.Model)
            }
            add(Row.Close)
        }
    }

    /** The agents Switch agent lists: the installed ones, in their order (the one the tab runs shown as [SWITCH_CURRENT]). */
    fun switchChoices(agents: List<WorkAgentChoice>): List<WorkAgentChoice> = agents.filter { it.installed }

    /** What Close tab does, decided as 3.1's `closeTab`. */
    sealed interface Close {
        /** The space's last tab: the space closes, so it asks as Close space does. */
        data object Space : Close
        /** The Assistant's tab: its workers are counted first; with some running, it asks what becomes of them. */
        data object Assistant : Close
        /** Any other tab, by its window id. */
        data class Tab(val id: String) : Close
        /** No tab id to close it by. */
        data object Nowhere : Close
    }

    fun close(space: WorkSpace, tab: WorkTab): Close {
        // The tabs the bar shows: the Assistant's only while it runs or has a tab.
        val many = space.tabs.count { !it.assistant || it.live || it.id != null } > 1
        return when {
            tab.assistant && tab.live -> if (many) Close.Assistant else Close.Space
            !many -> Close.Space
            tab.id == null -> Close.Nowhere
            else -> Close.Tab(tab.id)
        }
    }

    /** "Close “herdr-android”?", as 3.1's `CLOSE_SPACE`. */
    fun closeSpaceTitle(name: String): String = if (name.isNotBlank() && name != "~") "Close “$name”?" else "Close this space?"
    const val CLOSE_SPACE_DETAIL = "Its tabs close and every program in them ends."
    const val CLOSE_SPACE_VERB = "Close space"

    /** "Close the Assistant?" with workers running, as the window's (`mods/coordinator`). */
    const val CLOSE_ASSISTANT_TITLE = "Close the Assistant?"
    fun closeAssistantDetail(workers: Int): String =
        "It started ${if (workers == 1) "a worker that is" else "$workers workers that are"} still running."
    const val CLOSE_ASSISTANT_STOP = "Stop its workers"
    const val CLOSE_ASSISTANT_KEEP = "Let them finish"

    /** The tab [paneId] is in, on [space]: its Assistant's ([assistantOf] its space's id), else the tab its terminal opens. */
    fun tabOf(spaces: List<WorkSpace>, paneId: String, assistantOf: String?): Pair<WorkSpace, WorkTab>? {
        assistantOf?.let { id ->
            spaces.firstOrNull { it.id == id }?.let { s -> s.tabs.firstOrNull { it.assistant }?.let { return s to it } }
        }
        val address = HcMapping.address(paneId) ?: return null
        val chat = "t:$address"
        for (s in spaces) s.tabs.firstOrNull { !it.assistant && it.chat == chat }?.let { return s to it }
        return null
    }

    /**
     * The terminal [task]'s agent runs in, as [view] has it: the one Shepherdr labelled with the task, else its chat's
     * (`t:<address>`); null before its agent starts or where the machine doesn't show it.
     */
    fun taskTerminal(view: SessionSnapshot?, task: WorkTask): String? {
        view ?: return null
        view.agents.firstOrNull { it.shepherdrTask == task.id }?.let { return it.paneId }
        val address = task.chat.takeIf { it.startsWith("t:") }?.removePrefix("t:") ?: return null
        return HcMapping.paneId(address).takeIf { view.pane(it) != null }
    }

    /** The terminal the Assistant of space [space] (its [WorkSpace.id]) runs in, as [view] has it; null while none runs. */
    fun assistantTerminal(view: SessionSnapshot?, space: String): String? =
        view?.agents?.firstOrNull { it.shepherdrSpace == space }?.paneId

    /**
     * The shell [after] shows that [before] didn't, in the space named [name] (a workspace of that label): the tab the
     * + menu's Terminal opened. Null until it shows.
     */
    fun newShell(before: SessionSnapshot?, after: SessionSnapshot?, name: String): String? {
        after ?: return null
        val had = before?.panes.orEmpty().map { it.paneId }.toSet()
        return after.panes.firstOrNull { p ->
            p.paneId !in had && after.agents.none { it.paneId == p.paneId } &&
                (after.workspace(p.workspaceId)?.label?.ifBlank { null } ?: p.workspaceId).equals(name, ignoreCase = true)
        }?.paneId
    }
}
