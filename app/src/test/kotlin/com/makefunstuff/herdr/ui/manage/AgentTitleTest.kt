package com.makefunstuff.herdr.ui.manage

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import org.junit.Assert.assertEquals
import org.junit.Test

/** The long-press sheet's title names the agent by its task, as the Agents list does. */
class AgentTitleTest {
    private fun agent(name: String? = null, title: String? = null) = AgentInfo(
        name = name, agent = "claude", title = title, agentStatusRaw = "blocked", workspaceId = "w1", tabId = "t1", paneId = "w1:p1",
    )
    private val task = WorkTask("AND-12", "AND-12", "Speed up /jobs", "s1", WorkStatus.Need)

    @Test fun theTaskNamesIt() {
        assertEquals("Speed up /jobs" to "AND-12 · claude · needs you", agentTitle(agent(), "w1:p1", task))
    }

    @Test fun withoutATaskWhatItDoesOrItsName() {
        assertEquals("Fix the login form" to "claude · needs you", agentTitle(agent(title = "Fix the login form"), "w1:p1", null))
        // Was "claude" over "blocked · claude".
        assertEquals("claude" to "needs you", agentTitle(agent(), "w1:p1", null))
    }

    @Test fun anAgentWithItsOwnNameKeepsItsKind() {
        assertEquals("Speed up /jobs" to "AND-12 · reviewer (claude) · needs you", agentTitle(agent(name = "reviewer"), "w1:p1", task))
        // Named after its task: the task says it already.
        assertEquals("Speed up /jobs" to "AND-12 · claude · needs you", agentTitle(agent(name = "AND-12"), "w1:p1", task))
        assertEquals("Speed up /jobs" to "AND-12 · claude · needs you", agentTitle(agent(name = "claude"), "w1:p1", task))
        // Without a task its name is the title once, not the title and again under it.
        assertEquals("reviewer (claude)" to "needs you", agentTitle(agent(name = "reviewer"), "w1:p1", null))
    }

    @Test fun aPaneWithoutAnAgent() {
        assertEquals("zsh" to "No agent in this pane", agentTitle(null, "zsh", null))
    }
}
