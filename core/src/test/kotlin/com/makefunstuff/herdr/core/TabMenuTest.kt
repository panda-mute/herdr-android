package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.PaneInfo
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.api.WorkspaceInfo
import com.makefunstuff.herdr.core.board.TabMenus
import com.makefunstuff.herdr.core.board.TabMenus.Row
import com.makefunstuff.herdr.core.board.WorkAgentChoice
import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTab
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.shepherdr.ModelMenus
import com.makefunstuff.herdr.core.shepherdr.ShepherdrJson
import com.makefunstuff.herdr.core.shepherdr.V2Board
import com.makefunstuff.herdr.core.shepherdr.V2Event
import com.makefunstuff.herdr.core.shepherdr.V2Events
import com.makefunstuff.herdr.core.shepherdr.V2ModelMenu
import com.makefunstuff.herdr.core.shepherdr.V2ModelPick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The tab menu (3.1's `tabMenu`, `closeTab`) and the model menu (3.1's `ModelMenu`), on the phone. */
class TabMenuTest {
    private val assistant = WorkTab(id = "tb-a", name = "Assistant", named = true, chat = "p:sp1", agent = "claude", assistant = true, live = true)
    private val codex = WorkTab(id = "tb-c", name = "Codex", chat = "t:t4", agent = "codex", live = true)
    private val shell = WorkTab(id = "tb-s", name = "Terminal", chat = "t:t5")

    @Test
    fun theRowsFollowWhatRunsInTheTab() {
        assertEquals(listOf(Row.Rename, Row.Switch, Row.Restart, Row.Model, Row.Close), TabMenus.rows(assistant))
        assertEquals(listOf(Row.Rename, Row.Switch, Row.Restart, Row.Model, Row.Close), TabMenus.rows(codex))
        // A shell: nothing to restart.
        assertEquals(listOf(Row.Rename, Row.Close), TabMenus.rows(shell))
        // An agent the model menu doesn't know: Switch and Restart, no Model.
        assertEquals(listOf(Row.Rename, Row.Switch, Row.Restart, Row.Close), TabMenus.rows(codex.copy(agent = "pi")))
        // Another machine's agent restarts on that machine's window only; an Assistant not running has nothing to restart.
        assertEquals(listOf(Row.Rename, Row.Close), TabMenus.rows(codex.copy(remote = true)))
        assertEquals(listOf(Row.Rename, Row.Close), TabMenus.rows(assistant.copy(live = false)))
        assertEquals(listOf("Rename…", "Switch agent", "Restart", "Model…", "Close tab"), TabMenus.rows(assistant).map { it.label })
    }

    @Test
    fun closingDecidesAs31Does() {
        val space = WorkSpace("sp1", "herdr-android", tabs = listOf(assistant, codex, shell))
        assertEquals(TabMenus.Close.Assistant, TabMenus.close(space, assistant))
        assertEquals(TabMenus.Close.Tab("tb-c"), TabMenus.close(space, codex))
        // The last tab closes the space.
        val alone = WorkSpace("sp1", "herdr-android", tabs = listOf(assistant))
        assertEquals(TabMenus.Close.Space, TabMenus.close(alone, assistant))
        val shellOnly = WorkSpace("sp1", "herdr-android", tabs = listOf(assistant.copy(id = null, live = false), shell))
        assertEquals(TabMenus.Close.Space, TabMenus.close(shellOnly, shell))
        assertEquals(TabMenus.Close.Nowhere, TabMenus.close(space, codex.copy(id = null)))
        assertEquals("Close “herdr-android”?", TabMenus.closeSpaceTitle("herdr-android"))
        assertEquals("Close this space?", TabMenus.closeSpaceTitle("~"))
        assertEquals("It started a worker that is still running.", TabMenus.closeAssistantDetail(1))
        assertEquals("It started 3 workers that are still running.", TabMenus.closeAssistantDetail(3))
    }

    @Test
    fun switchListsTheInstalledAgents() {
        val agents = listOf(WorkAgentChoice("claude", "Claude Code", true), WorkAgentChoice("codex", "Codex", true), WorkAgentChoice("pi", "Pi", false))
        assertEquals(listOf("claude", "codex"), TabMenus.switchChoices(agents).map { it.kind })
    }

    @Test
    fun aPaneFindsItsTab() {
        val space = WorkSpace("sp1", "herdr-android", tabs = listOf(assistant, codex, shell))
        val other = WorkSpace("sp2", "api-server", tabs = listOf(assistant.copy(chat = "p:sp2"), WorkTab(id = "tb-v", name = "Claude", chat = "t:vm/t9", agent = "claude", remote = true)))
        assertEquals("tb-c", TabMenus.tabOf(listOf(space, other), "hc:t4", null)?.second?.id)
        assertEquals("tb-v", TabMenus.tabOf(listOf(space, other), "hc:vm/t9", null)?.second?.id)
        assertEquals("p:sp2", TabMenus.tabOf(listOf(space, other), "hc:t1", "sp2")?.second?.chat)
        assertNull(TabMenus.tabOf(listOf(space, other), "hc:t77", null))
        // A classic herdr pane is in no Shepherdr tab.
        assertNull(TabMenus.tabOf(listOf(space, other), "p3", null))
    }

    @Test
    fun aSnapshotsTabsAreTheBarsTabs() {
        val text = javaClass.getResource("/shepherdr/snapshot.json")!!.readText()
        val event = if (text.trimStart().startsWith("[")) {
            (ShepherdrJson.parseToJsonElement(text) as kotlinx.serialization.json.JsonArray).first { it.toString().startsWith("""{"type":"snapshot"""") }.toString()
        } else text
        val s = (V2Events.parse(event) as V2Event.Snapshot).snapshot
        val p = s.places.first { it.name == "demo-app" }
        val tabs = V2Board.tabs(s, p)
        assertEquals(listOf(true, false), tabs.map { it.assistant })
        assertEquals(WorkTab(id = "tb00000000000000000000000000000a01", name = "Assistant", named = true, chat = p.chat, agent = "claude", state = "done", assistant = true, live = true), tabs[0])
        assertEquals(WorkTab(id = "tb0000000000000000000000000000ab01", name = "Terminal", chat = "t:t21"), tabs[1])
        assertEquals(tabs, V2Board.overview(s).spaces.first { it.id == p.id }.tabs)
    }

    // ---- the model menu ----

    private val menu = ShepherdrJson.decodeFromString(
        V2ModelMenu.serializer(),
        """
        {"current":{"agent":"claude","model":"opus","effort":"high","fast":false,"perm":"auto"},
         "menus":{
           "claude":{"name":"Claude Code","perms":[{"id":"manual","label":"Ask"},{"id":"auto","label":"Auto"},{"id":"bypassPermissions","label":"Full access"}],"fastLocked":false,
             "models":[{"id":"opus","name":"Opus 5.5","efforts":["low","medium","high","xhigh","max"],"defaultEffort":"high","fast":true},
                       {"id":"sonnet","name":"Sonnet 5.5","efforts":["low","medium","high","xhigh","max"],"defaultEffort":"medium","fast":false}]},
           "codex":{"name":"Codex","perms":[{"id":"read-only","label":"Read only"},{"id":"auto","label":"Auto"},{"id":"full","label":"Full access"}],"fastLocked":false,
             "models":[{"id":"gpt-5.5","name":"GPT-5.5","efforts":["low","medium","high"],"defaultEffort":"medium","fast":true}]}},
         "permDefault":{"claude":"manual","codex":"auto"},"remote":null}
        """,
    )

    @Test
    fun theModelMenuSaysWhatARestartChanges() {
        val now = menu.current
        assertEquals(emptyList(), ModelMenus.changes(menu, now))
        val sonnet = ModelMenus.choose(menu, now, "claude", menu.menus.getValue("claude").models[1])
        assertEquals(V2ModelPick("claude", "sonnet", "high", false, "auto"), sonnet)
        assertEquals(listOf("Sonnet 5.5"), ModelMenus.changes(menu, sonnet))
        assertEquals("Restart with Sonnet 5.5 · Max · Fast", ModelMenus.restartWords(ModelMenus.changes(menu, sonnet.copy(effort = "max", fast = true))))
        // Another CLI brings its own permissions: full access stays full access; a level it lacks goes back to its default.
        val full = now.copy(perm = "bypassPermissions", effort = "xhigh")
        val codex = ModelMenus.choose(menu, full, "codex", menu.menus.getValue("codex").models[0])
        assertEquals(V2ModelPick("codex", "gpt-5.5", null, false, "full"), codex)
        assertEquals("medium", ModelMenus.level(menu, codex))
        assertEquals(listOf("GPT-5.5", "Medium", "Full access"), ModelMenus.changes(menu, codex))
        assertEquals("auto", ModelMenus.choose(menu, now, "codex", menu.menus.getValue("codex").models[0]).perm)
        // Nothing restarts from a machine it can't.
        assertEquals(emptyList(), ModelMenus.changes(menu.copy(remote = "This computer only"), sonnet))
    }

    @Test
    fun theTerminalsBehindAChatAndANewTab() {
        fun pane(id: String, ws: String = "hc:sp1") = PaneInfo(paneId = id, workspaceId = ws, tabId = "$ws/tab")
        fun agent(id: String, task: String? = null, space: String? = null) =
            AgentInfo(workspaceId = "hc:sp1", tabId = "hc:sp1/tab", paneId = id, shepherdrTask = task, shepherdrSpace = space)
        val view = SessionSnapshot(
            workspaces = listOf(WorkspaceInfo("hc:sp1", label = "herdr-android"), WorkspaceInfo("hc:sp2", label = "notes")),
            panes = listOf(pane("hc:t1"), pane("hc:t4"), pane("hc:t9")),
            agents = listOf(agent("hc:t1", space = "sp1"), agent("hc:t4", task = "AND-19")),
        )
        val task = WorkTask("AND-19", "AND-19", "Speed up /jobs", "sp1", WorkStatus.Work, chat = "t:t4")
        // Its worker, found by the task Shepherdr labelled it with; else by its chat's terminal, when the machine shows it.
        assertEquals("hc:t4", TabMenus.taskTerminal(view, task))
        assertEquals("hc:t9", TabMenus.taskTerminal(view, task.copy(id = "AND-7", name = "AND-7", chat = "t:t9")))
        assertNull(TabMenus.taskTerminal(view, task.copy(id = "AND-7", name = "AND-7", chat = "t:t44")))
        assertNull(TabMenus.taskTerminal(view, task.copy(id = "AND-7", name = "AND-7", chat = "")))
        assertNull(TabMenus.taskTerminal(null, task))
        assertEquals("hc:t1", TabMenus.assistantTerminal(view, "sp1"))
        assertNull(TabMenus.assistantTerminal(view, "sp2"))
        // The + menu's Terminal: the shell that wasn't there, in that space; an agent or another space's doesn't count.
        val after = view.copy(
            panes = view.panes + pane("hc:t10", "hc:sp2") + pane("hc:t11") + pane("hc:t12"),
            agents = view.agents + agent("hc:t11"),
        )
        assertEquals("hc:t12", TabMenus.newShell(view, after, "HERDR-ANDROID"))
        assertEquals("hc:t10", TabMenus.newShell(view, after, "notes"))
        assertNull(TabMenus.newShell(view, view, "herdr-android"))
    }
}
