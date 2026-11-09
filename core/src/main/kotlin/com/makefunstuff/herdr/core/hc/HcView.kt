package com.makefunstuff.herdr.core.hc

import com.makefunstuff.herdr.core.agents.AgentKinds
import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.AgentSessionRef
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.PaneInfo
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.api.TabInfo
import com.makefunstuff.herdr.core.api.WorkspaceInfo
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * A core's terminals as its event stream describes them: a subscription's snapshot, then each
 * event applied the way the schema's README says.
 */
data class HcView(val seq: Long = 0, val terminals: Map<String, HcTerminal> = emptyMap()) {
    /**
     * The new status word when [event] changes a terminal's, else null. Its `status_ms` (the core's clock, which the
     * event doesn't carry) is then read from the core and given to [apply], so it reads the same
     * after a resubscribe and never mixes in the phone's clock.
     */
    fun statusChange(event: HcEvent): String? {
        if (event.method != "event" || event.type != "status" || event.seq <= seq) return null
        val old = terminals[event.terminal ?: return null] ?: return null
        val next = (event.data as? JsonObject)?.decode<HcStatusChange>()?.status ?: return null
        return next.state.takeIf { it != old.status?.state }
    }

    /** [event] applied; [statusAt] is when a status word changed, by the core's clock (else the phone's). */
    fun apply(event: HcEvent, statusAt: Long? = null): HcView {
        if (event.method == "missed") return HcView(event.seq, event.terminals.orEmpty().associateBy { it.address })
        if (event.method != "event") return this
        // After a `missed` snapshot, events at or below its seq are already in it.
        if (event.seq <= seq) return this
        val address = event.terminal ?: return copy(seq = event.seq)
        val data = event.data as? JsonObject
        val old = terminals[address]
        val next: HcTerminal? = when (event.type) {
            "created", "restarted" -> data?.let { runCatching { HcJson.decodeFromJsonElement<HcTerminal>(it) }.getOrNull() } ?: old
            "removed" -> null
            "lost" -> old?.let { t ->
                val e = data?.decode<HcEnded>()
                t.copy(reason = e?.reason, exitCode = e?.exitCode, pid = null, foreground = null)
            }
            "finished" -> old?.let { t ->
                val e = data?.decode<HcEnded>()
                t.copy(state = "finished", reason = e?.reason, exitCode = e?.exitCode, finishedMs = e?.finishedMs, foreground = null)
            }
            "status" -> old?.let { t ->
                val s = data?.decode<HcStatusChange>() ?: return@let t
                val changed = s.status?.state != t.status?.state
                t.copy(status = s.status, records = s.records, reported = s.reported, statusMs = if (changed && s.status != null) statusAt ?: System.currentTimeMillis() else t.statusMs)
            }
            "turn" -> old?.copy(turn = data?.long("turn") ?: old.turn)
            "title" -> old?.copy(title = data?.string("title") ?: old.title)
            "labels" -> {
                val record = (data?.get("record") as? JsonObject)?.let { runCatching { HcJson.decodeFromJsonElement<HcTerminal>(it) }.getOrNull() }
                val labels = (data?.get("labels") as? JsonObject)?.let { runCatching { HcJson.decodeFromJsonElement<Map<String, String>>(it) }.getOrNull() }
                (record ?: old)?.let { t -> labels?.let { t.copy(labels = it) } ?: t }
            }
            "size" -> old?.copy(cols = data?.long("cols")?.toInt() ?: old.cols, rows = data?.long("rows")?.toInt() ?: old.rows)
            "foreground" -> old?.copy(
                foreground = event.data?.takeIf { it !is JsonNull }?.let { runCatching { HcJson.decodeFromJsonElement<HcForeground>(it) }.getOrNull() },
            )
            else -> old
        }
        val map = if (next == null) terminals - address else terminals + (address to next)
        return HcView(event.seq, map)
    }

    companion object {
        fun of(snapshot: HcSubscribed): HcView = HcView(snapshot.seq, snapshot.terminals.associateBy { it.address })

        private fun JsonObject.long(key: String): Long? = (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
        private fun JsonObject.string(key: String): String? = (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/**
 * herdr-core's terminals as the app's own models ([SessionSnapshot]), so the Agents list, Needs
 * you, notices and the machine screen read them as they read classic herdr's. Every id is
 * prefixed with [PREFIX] so a machine running both can show both side by side; [address] gives
 * back the terminal's hc address from a pane id.
 *
 * - A space of the Herdr desktop app is a workspace, its tab a tab, each terminal a pane: the app
 *   labels each terminal it shows with `herdr.space` and `herdr.tab` (ids) and `where`
 *   (`<space> / <tab>`, the names). A helper an agent started sits with its parent, as in the app.
 * - Terminals the app doesn't show (started with `hc run`) go in one "Other terminals" workspace.
 * - A terminal is an agent while its program reports a status (OSC 7501), or while a known agent
 *   runs in it and hasn't reported yet.
 */
object HcMapping {
    const val PREFIX = "hc:"
    /** Terminals the desktop app doesn't show; `!` keeps it apart from any space id the app makes. */
    const val OTHER = "${PREFIX}!other"
    private const val SPACE = "herdr.space"
    private const val TAB = "herdr.tab"
    private const val WHERE = "where"
    // Shepherdr 3's labels on the agents it starts (`service/actions.ts`, herdr-task's): a task's worker and reviewers
    // carry its key (`<list>#<n>`), an Assistant its space.
    private const val TASK = "herdr.task"
    private const val ROLE = "herdr.role"
    private const val ASSISTANT_SPACE = "herdr.coordinator.space"
    private const val ASSISTANT_TAB = "herdr.coordinator.tab"
    private const val ASSISTANT = "herdr.coordinator"
    private const val TASK_SPACE = "herdr.task.space"

    /** The agents whose transcript is found from their process (Shepherdr 3.1's chat finds the same two). */
    private val BY_PID = setOf("claude", "codex")

    /**
     * The hc address of a pane id from [snapshot], or null when it isn't one of herdr-core's: `t7`
     * for `hc:t7` (the default session's) and for `hc:@shepherdr:t7` (another session's, [session]).
     */
    fun address(paneId: String): String? {
        val rest = paneId.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
        return if (rest.startsWith("@")) rest.substringAfter(':', "").ifEmpty { null } else rest
    }

    /** The herdr-core session a pane id is in: null for the default one's (`hc:t7`), `shepherdr` for `hc:@shepherdr:t7`. */
    fun session(paneId: String): String? =
        paneId.takeIf { it.startsWith("$PREFIX@") }?.removePrefix("$PREFIX@")?.substringBefore(':', "")?.ifEmpty { null }

    /** What every id of [session]'s terminals starts with: [PREFIX] for the default session, `hc:@<session>:` for another. */
    fun scope(session: String?): String = if (session == null) PREFIX else "$PREFIX@$session:"

    fun paneId(address: String, session: String? = null): String = scope(session) + address

    /** The agent name the app knows (`claude` for Claude Code's `claude-code`), from its 7501 app or its program. */
    fun agentName(t: HcTerminal): String? {
        val app = t.status?.app?.trim()?.lowercase().orEmpty()
        // A program that reports a status is an agent even when the app doesn't know its name.
        if (app.isNotEmpty()) return AgentKinds.fromApp(app) ?: app.removeSuffix("-cli")
        val argv = t.foreground?.argv?.takeIf { it.isNotEmpty() } ?: t.argv
        return AgentKinds.fromArgv(argv) ?: AgentKinds.normalize(t.foreground?.name)
    }

    /**
     * Where the agent's own transcript is found: hc reports no session, so it is looked up from the
     * agent's process on the machine ([AgentSessionRef] kind `pid`). Only for the agents whose
     * transcripts the app reads that way, and not on a machine this core added (`rohan/t7`): the
     * process runs there, not on the machine the app reaches.
     */
    fun sessionOf(t: HcTerminal): AgentSessionRef? {
        val agent = agentName(t)?.takeIf { it in BY_PID } ?: return null
        val pid = t.foreground?.pid?.takeIf { it > 0 } ?: return null
        if ('/' in t.address) return null
        return AgentSessionRef(agent = agent, kind = "pid", value = pid.toString(), source = "herdr-core")
    }

    /** It reports a status now, or a known agent runs in it ([HcTerminal.reported] stays true after the agent quit). */
    fun isAgent(t: HcTerminal): Boolean = t.status != null || agentName(t) != null

    /** The status word as the app's: `error` needs the human as `blocked` does (labelled `error`). */
    fun statusWord(t: HcTerminal): String = when (val s = t.status?.state) {
        null -> AgentStatus.Unknown.wire
        "error" -> AgentStatus.Blocked.wire
        else -> s
    }

    /** [named]: [spaceName] is the desktop app's (its `where` label), not made up from an id. */
    private data class Place(val spaceId: String, val spaceName: String, val tabId: String, val tabName: String, val named: Boolean = false)

    private fun placeOf(t: HcTerminal, all: Map<String, HcTerminal>, scope: String): Place {
        var cur: HcTerminal? = t
        for (i in 0 until 32) {
            val c = cur ?: break
            own(c, scope)?.let { return it }
            cur = c.parent?.let { all[sibling(c.address, it)] }
        }
        val machine = machineOf(t.address)
        val other = scope + OTHER.removePrefix(PREFIX)
        val id = if (machine == null) other else "$other@$machine"
        return Place(id, if (machine == null) "Other terminals" else "Other terminals on $machine", "$id/tab", "Terminals")
    }

    /**
     * The task a Shepherdr 3 agent works on, as people say it (`HERD-8`; `vm:HERD-8` on a machine hc added), or null.
     * Its label holds the task list's key and number (`data-jpl-Work-app#8`), not its ref: the ref's letters come from
     * the name herdr-task gives its worker (`herd-8-dark-mode`), taken only when its number is the label's.
     */
    fun shepherdrTask(t: HcTerminal): String? {
        val n = t.labels[TASK]?.substringAfterLast('#', "")?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        val m = Regex("""^([A-Za-z][A-Za-z0-9]*)-(\d+)(?:-|$)""").find(t.name.orEmpty()) ?: return null
        if (m.groupValues[2] != n) return null
        return machineOf(t.address)?.let { "$it:" }.orEmpty() + m.groupValues[1].uppercase() + "-" + n
    }

    /** The space whose Assistant runs in [t] (its id, `vm/<id>` on a machine hc added), or null. */
    fun shepherdrSpace(t: HcTerminal): String? {
        if (t.labels[ROLE] != "coordinator") return null
        val space = t.labels[ASSISTANT_SPACE]?.takeIf { it.isNotBlank() } ?: return null
        return machineOf(t.address)?.takeIf { '/' !in space }?.let { "$it/" }.orEmpty() + space
    }

    /**
     * What Shepherdr 3 calls the agent it started: its task (`HERD-8`, `HERD-8 reviewer`) or `<space> Assistant`, in
     * place of the terminal's name; null for any other terminal.
     */
    fun shepherdrName(t: HcTerminal, spaceName: String? = null): String? {
        shepherdrTask(t)?.let { task -> return if (t.labels[ROLE] == "reviewer") "${task.substringAfter(':')} reviewer" else task.substringAfter(':') }
        shepherdrSpace(t) ?: return null
        // An Assistant Shepherdr started before the desktop app showed it has no `where`: its space's name is another terminal's.
        val space = t.labels[WHERE]?.split(" / ", limit = 2)?.firstOrNull()?.takeIf { it.isNotBlank() } ?: spaceName?.takeIf { it.isNotBlank() }
        return if (space != null) "$space Assistant" else "Assistant"
    }

    /** Where the desktop app put it, from its labels; null where it didn't. */
    private fun own(t: HcTerminal, scope: String): Place? {
        val where = t.labels[WHERE]?.takeIf { it.isNotBlank() }
        val names = where?.split(" / ", limit = 2)
        // A Shepherdr worker names its space and its Assistant's tab, where the desktop app shows it.
        val space = listOf(SPACE, TASK_SPACE, ASSISTANT_SPACE).firstNotNullOfOrNull { t.labels[it]?.takeIf(String::isNotBlank) }
        val tab = listOf(TAB, ASSISTANT_TAB, ASSISTANT).firstNotNullOfOrNull { t.labels[it]?.takeIf(String::isNotBlank) }
        if (space == null && where == null) return null
        val remote = machineOf(t.address)?.let { "$it/" }.orEmpty()
        val spaceId = scope + remote + (space ?: "where:${names!![0]}")
        val tabKey = tab ?: names?.getOrNull(1)?.let { "where:$it" } ?: t.address
        return Place(
            spaceId = spaceId,
            spaceName = names?.get(0)?.takeIf { it.isNotBlank() } ?: space ?: "Space",
            tabId = "$spaceId/$tabKey",
            tabName = names?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: tab ?: t.name ?: t.address,
            named = names?.get(0)?.isNotBlank() == true,
        )
    }

    /** `rohan` for `rohan/t7`; null for this core's own. */
    private fun machineOf(address: String): String? = address.substringBeforeLast('/', "").ifEmpty { null }

    /** A parent's address as the list has it: a remote terminal's parent `t3` is `rohan/t3` here. */
    private fun sibling(child: String, parent: String): String =
        machineOf(child)?.takeIf { '/' !in parent }?.let { "$it/$parent" } ?: parent

    private fun attention(words: List<String>): String =
        words.maxByOrNull { AgentStatus.of(it).attention }?.takeIf { AgentStatus.of(it) != AgentStatus.Unknown } ?: AgentStatus.Unknown.wire

    /**
     * Live terminals as a snapshot, spaces and tabs in the order their first terminal started, other
     * terminals last. [session]: the herdr-core session they are in, null for the default one; every
     * id then starts with its [scope], so two sessions' `t7` stay apart.
     */
    fun snapshot(terminals: Collection<HcTerminal>, version: String = "", generation: Int = HcConnection.GENERATION, session: String? = null): SessionSnapshot {
        val scope = scope(session)
        val other = scope + OTHER.removePrefix(PREFIX)
        val all = terminals.associateBy { it.address }
        val live = terminals.filter { it.live }.sortedWith(compareBy({ it.createdMs }, { it.address }))
        val raw = live.map { it to placeOf(it, all, scope) }
        // A space is called what any of its terminals' `where` calls it: the first one placed may carry only its id.
        val spaceNames = raw.map { it.second }.filter { it.named }.groupBy { it.spaceId }.mapValues { it.value.first().spaceName }
        val placed = raw.map { (t, p) -> t to (spaceNames[p.spaceId]?.let { p.copy(spaceName = it, named = true) } ?: p) }
        fun nameOf(t: HcTerminal, p: Place) = shepherdrName(t, p.spaceName.takeIf { p.named })
        val panes = placed.map { (t, p) ->
            val agent = agentName(t)
            PaneInfo(
                paneId = paneId(t.address, session),
                terminalId = t.address,
                workspaceId = p.spaceId,
                tabId = p.tabId,
                cwd = t.cwd,
                foregroundCwd = t.foreground?.cwd,
                label = nameOf(t, p) ?: t.name ?: if (agent == null) t.foreground?.name else null,
                agent = agent,
                terminalTitle = t.title.ifBlank { null },
                agentStatusRaw = if (isAgent(t)) statusWord(t) else AgentStatus.Unknown.wire,
                stateLabels = labels(t),
            )
        }
        val agents = placed.filter { isAgent(it.first) }.map { (t, p) ->
            AgentInfo(
                terminalId = t.address,
                name = nameOf(t, p) ?: t.name,
                agent = agentName(t),
                title = t.status?.msg?.ifBlank { null } ?: t.status?.title?.ifBlank { null },
                terminalTitle = t.title.ifBlank { null },
                agentStatusRaw = statusWord(t),
                stateLabels = labels(t),
                tokensRaw = t.status?.progress?.let { JsonObject(mapOf("progress" to kotlinx.serialization.json.JsonPrimitive(it))) },
                workspaceId = p.spaceId,
                tabId = p.tabId,
                paneId = paneId(t.address, session),
                stateChangeSeq = t.statusMs ?: t.createdMs,
                completionSeq = t.turn.takeIf { it > 0 },
                cwd = t.foreground?.cwd ?: t.cwd,
                agentSession = sessionOf(t),
                shepherdrTask = shepherdrTask(t)?.takeIf { t.labels[ROLE] != "reviewer" },
                shepherdrSpace = shepherdrSpace(t),
            )
        }
        // The desktop app's spaces first, the terminals it doesn't show after them.
        val spaces = placed.map { it.second }.distinctBy { it.spaceId }.sortedBy { it.spaceId.startsWith(other) }
        val tabs = placed.map { it.second }.distinctBy { it.tabId }
        fun words(match: (PaneInfo) -> Boolean) = panes.filter(match).map { it.agentStatusRaw }
        return SessionSnapshot(
            version = version,
            protocol = generation,
            workspaces = spaces.mapIndexed { i, s ->
                val own = tabs.filter { it.spaceId == s.spaceId }
                WorkspaceInfo(
                    workspaceId = s.spaceId,
                    number = i + 1,
                    label = s.spaceName,
                    paneCount = panes.count { it.workspaceId == s.spaceId },
                    tabCount = own.size,
                    activeTabId = own.first().tabId,
                    agentStatusRaw = attention(words { it.workspaceId == s.spaceId }),
                )
            },
            tabs = spaces.flatMap { s ->
                tabs.filter { it.spaceId == s.spaceId }.mapIndexed { i, t ->
                    TabInfo(
                        tabId = t.tabId,
                        workspaceId = s.spaceId,
                        number = i + 1,
                        label = t.tabName,
                        paneCount = panes.count { it.tabId == t.tabId },
                        agentStatusRaw = attention(words { it.tabId == t.tabId }),
                    )
                }
            },
            panes = panes,
            agents = agents,
        )
    }

    /** What to call a status the app's words lack: `error`, and what a blocked program waits for. */
    private fun labels(t: HcTerminal): Map<String, String> {
        val s = t.status ?: return emptyMap()
        return when {
            s.state == "error" -> mapOf(AgentStatus.Blocked.wire to "error")
            s.state == "blocked" && s.kind == "question" -> mapOf(AgentStatus.Blocked.wire to "question")
            s.state == "blocked" && s.kind == "auth" -> mapOf(AgentStatus.Blocked.wire to "needs sign-in")
            else -> emptyMap()
        }
    }
}
