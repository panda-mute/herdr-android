package com.makefunstuff.herdr.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Agent state as herdr reports it. Unrecognised values map to [Unknown]. */
enum class AgentStatus(val wire: String, val attention: Int) {
    Blocked("blocked", 4),
    Done("done", 3),
    Working("working", 2),
    Idle("idle", 1),
    Unknown("unknown", 0);

    companion object {
        fun of(value: String?): AgentStatus = entries.firstOrNull { it.wire == value } ?: Unknown
    }
}

@Serializable
data class WorkspaceInfo(
    @SerialName("workspace_id") val workspaceId: String,
    val number: Int = 0,
    val label: String = "",
    val focused: Boolean = false,
    @SerialName("pane_count") val paneCount: Int = 0,
    @SerialName("tab_count") val tabCount: Int = 0,
    @SerialName("active_tab_id") val activeTabId: String = "",
    @SerialName("agent_status") val agentStatusRaw: String = "unknown",
    val worktree: WorktreeInfo? = null,
    /** Metadata that integrations report for the workspace (branch, PR, …), as key → value. */
    val tokens: Map<String, String> = emptyMap(),
) {
    val agentStatus: AgentStatus get() = AgentStatus.of(agentStatusRaw)
}

/** The Git checkout a workspace sits in, as herdr reports it on the workspace. */
@Serializable
data class WorktreeInfo(
    @SerialName("repo_key") val repoKey: String = "",
    @SerialName("repo_name") val repoName: String = "",
    @SerialName("repo_root") val repoRoot: String = "",
    @SerialName("checkout_path") val checkoutPath: String = "",
    @SerialName("is_linked_worktree") val isLinkedWorktree: Boolean = false,
)

/** One checkout of a repository, from `worktree.list`. */
@Serializable
data class GitWorktree(
    val path: String,
    val branch: String? = null,
    val label: String = "",
    @SerialName("is_bare") val isBare: Boolean = false,
    @SerialName("is_detached") val isDetached: Boolean = false,
    @SerialName("is_prunable") val isPrunable: Boolean = false,
    @SerialName("is_linked_worktree") val isLinkedWorktree: Boolean = false,
    @SerialName("open_workspace_id") val openWorkspaceId: String? = null,
) {
    val isOpen: Boolean get() = openWorkspaceId != null

    /** What to call this checkout: its branch, or what it is when it has none. */
    val title: String
        get() = branch?.takeIf { it.isNotBlank() } ?: when {
            isBare -> "bare repository"
            isDetached -> "detached HEAD"
            else -> path.substringAfterLast('/')
        }
}

/** The repository a `worktree.list` call resolved to. */
@Serializable
data class WorktreeSource(
    @SerialName("repo_key") val repoKey: String = "",
    @SerialName("repo_name") val repoName: String = "",
    @SerialName("repo_root") val repoRoot: String = "",
    @SerialName("source_checkout_path") val sourceCheckoutPath: String = "",
    @SerialName("source_workspace_id") val sourceWorkspaceId: String? = null,
)

@Serializable
data class WorktreeList(
    val source: WorktreeSource = WorktreeSource(),
    val worktrees: List<GitWorktree> = emptyList(),
)

/** The workspace a worktree was created in or opened as. */
@Serializable
data class WorktreeOpened(
    val workspace: WorkspaceInfo,
    val worktree: GitWorktree,
    @SerialName("already_open") val alreadyOpen: Boolean = false,
)

@Serializable
data class WorktreeRemoved(
    @SerialName("workspace_id") val workspaceId: String = "",
    val path: String = "",
    val forced: Boolean = false,
)

@Serializable
data class TabInfo(
    @SerialName("tab_id") val tabId: String,
    @SerialName("workspace_id") val workspaceId: String,
    val number: Int = 0,
    val label: String = "",
    val focused: Boolean = false,
    @SerialName("pane_count") val paneCount: Int = 0,
    @SerialName("agent_status") val agentStatusRaw: String = "unknown",
) {
    val agentStatus: AgentStatus get() = AgentStatus.of(agentStatusRaw)
}

@Serializable
data class PaneInfo(
    @SerialName("pane_id") val paneId: String,
    @SerialName("terminal_id") val terminalId: String = "",
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    val focused: Boolean = false,
    val cwd: String? = null,
    @SerialName("foreground_cwd") val foregroundCwd: String? = null,
    val label: String? = null,
    val agent: String? = null,
    val title: String? = null,
    @SerialName("terminal_title_stripped") val terminalTitle: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    @SerialName("agent_status") val agentStatusRaw: String = "unknown",
    @SerialName("state_labels") val stateLabels: Map<String, String> = emptyMap(),
    val scroll: PaneScroll? = null,
    val revision: Long = 0,
) {
    val agentStatus: AgentStatus get() = AgentStatus.of(agentStatusRaw)

    /** The name shown for this pane: agent, label, or terminal title. */
    val displayName: String
        get() = displayAgent ?: agent ?: label ?: terminalTitle?.takeIf { it.isNotBlank() } ?: paneId
}

@Serializable
data class AgentInfo(
    @SerialName("terminal_id") val terminalId: String = "",
    val name: String? = null,
    val agent: String? = null,
    val title: String? = null,
    @SerialName("terminal_title_stripped") val terminalTitle: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    @SerialName("agent_status") val agentStatusRaw: String = "unknown",
    @SerialName("state_labels") val stateLabels: Map<String, String> = emptyMap(),
    /** What the agent's integration reports about it (`summary`, `progress`, `model`, `context`, …); see [tokens]. */
    @SerialName("tokens") val tokensRaw: kotlinx.serialization.json.JsonObject? = null,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    @SerialName("pane_id") val paneId: String,
    val focused: Boolean = false,
    @SerialName("state_change_seq") val stateChangeSeq: Long = 0,
    /**
     * The state change that ended the work it finished last, whoever has looked since (herdr 0.9.5);
     * null when it has finished nothing, or on an older herdr. herdr's own "seen" is its status:
     * `done` until its pane is in front at herdr's terminal, then `idle`.
     */
    @SerialName("completion_seq") val completionSeq: Long? = null,
    val cwd: String? = null,
    @SerialName("agent_session") val agentSession: AgentSessionRef? = null,
    /** The Shepherdr task it is the agent of (`HERD-8`), from its terminal's labels (herdr-core); null for any other (its reviewer too). */
    @SerialName("shepherdr_task") val shepherdrTask: String? = null,
    /** The Shepherdr space (its id) whose Assistant this is, from its terminal's labels (herdr-core); null for any other. */
    @SerialName("shepherdr_space") val shepherdrSpace: String? = null,
) {
    val agentStatus: AgentStatus get() = AgentStatus.of(agentStatusRaw)

    val displayName: String get() = displayAgent ?: agent ?: name ?: paneId

    /** A short description of what the agent is doing, if herdr knows one. */
    val subtitle: String?
        get() = title?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it != agent && it.isNotBlank() }
            ?: terminalTitle?.takeIf { it.isNotBlank() }

    val statusLabel: String get() = stateLabels[agentStatusRaw] ?: agentStatusRaw

    /** [tokensRaw] as key → text: a number or a flag reads as written, anything nested is left out. */
    val tokens: Map<String, String>
        get() = tokensRaw?.mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { k to it.content } }?.toMap().orEmpty()
}

/**
 * Where an agent keeps its conversation, as its herdr integration reports it: a transcript file
 * path, or a session id that the agent's own storage layout turns into one.
 */
@Serializable
data class AgentSessionRef(
    val agent: String = "",
    val kind: String = "",
    val value: String = "",
    val source: String = "",
)

@Serializable
data class LayoutRect(val x: Int = 0, val y: Int = 0, val width: Int = 0, val height: Int = 0)

@Serializable
data class LayoutPane(
    @SerialName("pane_id") val paneId: String,
    val focused: Boolean = false,
    val rect: LayoutRect = LayoutRect(),
)

@Serializable
data class LayoutSplit(
    val id: String = "",
    val direction: String = "right",
    val ratio: Float = 0.5f,
    val rect: LayoutRect = LayoutRect(),
)

@Serializable
data class TabLayout(
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    val zoomed: Boolean = false,
    val area: LayoutRect = LayoutRect(),
    @SerialName("focused_pane_id") val focusedPaneId: String = "",
    val panes: List<LayoutPane> = emptyList(),
    val splits: List<LayoutSplit> = emptyList(),
)

@Serializable
data class SessionSnapshot(
    val version: String = "",
    val protocol: Int = 0,
    val workspaces: List<WorkspaceInfo> = emptyList(),
    val tabs: List<TabInfo> = emptyList(),
    val panes: List<PaneInfo> = emptyList(),
    val layouts: List<TabLayout> = emptyList(),
    val agents: List<AgentInfo> = emptyList(),
) {
    fun workspace(id: String): WorkspaceInfo? = workspaces.firstOrNull { it.workspaceId == id }
    fun tab(id: String): TabInfo? = tabs.firstOrNull { it.tabId == id }
    fun pane(id: String): PaneInfo? = panes.firstOrNull { it.paneId == id }
    fun layout(tabId: String): TabLayout? = layouts.firstOrNull { it.tabId == tabId }
    fun paneRect(paneId: String): LayoutRect? =
        layouts.firstNotNullOfOrNull { l -> l.panes.firstOrNull { it.paneId == paneId }?.rect }

    /** "workspace › tab" for a pane, using labels where herdr has them. */
    fun location(workspaceId: String, tabId: String): String {
        val ws = workspace(workspaceId)?.label?.ifBlank { null } ?: workspaceId
        val tab = tab(tabId)?.label?.ifBlank { null } ?: tabId
        return "$ws › $tab"
    }
}

@Serializable
data class PaneRead(
    @SerialName("pane_id") val paneId: String = "",
    val text: String = "",
    val revision: Long = 0,
    val truncated: Boolean = false,
)

@Serializable
data class SessionEntry(
    val name: String,
    val default: Boolean = false,
    val running: Boolean = false,
    @SerialName("socket_path") val socketPath: String = "",
)

@Serializable
data class SavedMachineEntry(
    val id: String = "",
    val label: String = "",
    val target: String = "",
    val session: String = "default",
    val enabled: Boolean = true,
)

@Serializable
data class PongInfo(
    val version: String = "",
    val protocol: Int = 0,
    val capabilities: ServerCapabilities = ServerCapabilities(),
)

@Serializable
data class ServerCapabilities(
    @SerialName("live_handoff") val liveHandoff: Boolean = false,
    @SerialName("detached_server_daemon") val detachedServerDaemon: Boolean = false,
    @SerialName("health_check") val healthCheck: Boolean = false,
    @SerialName("ssh_agent_registration") val sshAgentRegistration: Boolean = false,
)

/** An installed herdr plugin (`plugin.list`). */
@Serializable
data class PluginInfo(
    @SerialName("plugin_id") val pluginId: String,
    val name: String = "",
    val version: String = "",
    val description: String? = null,
    val enabled: Boolean = true,
    @SerialName("min_herdr_version") val minHerdrVersion: String = "",
    @SerialName("plugin_root") val pluginRoot: String = "",
    val actions: List<PluginManifestAction> = emptyList(),
    val panes: List<PluginManifestPane> = emptyList(),
    val events: List<PluginEventHook> = emptyList(),
    val source: PluginSource? = null,
    val warnings: List<String> = emptyList(),
) {
    val title: String get() = name.ifBlank { pluginId }

    /** Where it came from: `owner/repo` for GitHub installs, else "local". */
    val origin: String get() = when {
        source?.kind == "github" && source.owner != null && source.repo != null -> "${source.owner}/${source.repo}"
        else -> source?.kind ?: "local"
    }
}

@Serializable
data class PluginManifestAction(
    val id: String,
    val title: String = "",
    val description: String? = null,
    val contexts: List<String> = emptyList(),
)

@Serializable
data class PluginManifestPane(val id: String, val title: String = "", val description: String? = null)

@Serializable
data class PluginEventHook(val on: String = "")

@Serializable
data class PluginSource(
    val kind: String = "local",
    val owner: String? = null,
    val repo: String? = null,
    @SerialName("requested_ref") val requestedRef: String? = null,
    @SerialName("resolved_commit") val resolvedCommit: String? = null,
)

/** A runnable plugin action (`plugin.action.list`). */
@Serializable
data class PluginAction(
    @SerialName("plugin_id") val pluginId: String,
    @SerialName("action_id") val actionId: String,
    val title: String = "",
    val description: String? = null,
    val contexts: List<String> = emptyList(),
    val platforms: List<String>? = null,
    val command: List<String> = emptyList(),
) {
    /** Actions declaring platforms only run on those; null means any. */
    fun runsOn(os: String?): Boolean = platforms == null || os == null || os in platforms
}

/** One run of a plugin command (`plugin.log.list`). */
@Serializable
data class PluginLog(
    @SerialName("log_id") val logId: String,
    @SerialName("plugin_id") val pluginId: String,
    @SerialName("action_id") val actionId: String? = null,
    val event: String? = null,
    val command: List<String> = emptyList(),
    val status: String = "running",
    @SerialName("started_unix_ms") val startedUnixMs: Long = 0,
    @SerialName("finished_unix_ms") val finishedUnixMs: Long? = null,
    @SerialName("exit_code") val exitCode: Int? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    val error: String? = null,
) {
    /** What triggered the run: the action id, the event name, or the command. */
    val trigger: String get() = actionId ?: event ?: command.joinToString(" ")
}

/** An agent integration herdr can install (`integration.list`). */
@Serializable
data class IntegrationInfo(
    val target: String,
    val label: String = "",
    val command: String = "",
    /** Whether the agent's CLI is present on the machine. */
    val available: Boolean = false,
    /** `not_installed`, `current` or `outdated`. */
    val state: String = "not_installed",
) {
    val installed: Boolean get() = state != "not_installed"
    val outdated: Boolean get() = state == "outdated"
}

@Serializable
data class AgentManifestInfo(
    val agent: String,
    val source: String = "",
    @SerialName("source_kind") val sourceKind: String = "",
    @SerialName("active_version") val activeVersion: String? = null,
    @SerialName("cached_remote_version") val cachedRemoteVersion: String? = null,
    @SerialName("remote_update_result") val remoteUpdateResult: String? = null,
    @SerialName("remote_update_error") val remoteUpdateError: String? = null,
    val warning: String? = null,
)

@Serializable
data class ConfigReload(
    /** `applied`, `partial` or `failed`. */
    val status: String = "applied",
    val diagnostics: List<String> = emptyList(),
)

internal val HerdrJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    coerceInputValues = true
}
