package com.makefunstuff.herdr.core.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Typed calls on top of an [ApiCaller]. Method names follow herdr's socket API. */
class HerdrApi(private val connection: ApiCaller) {

    suspend fun ping(): PongInfo = decode(connection.call("ping"))

    suspend fun snapshot(): SessionSnapshot =
        HerdrJson.decodeFromJsonElement(connection.call("session.snapshot")["snapshot"] ?: JsonObject(emptyMap()))

    suspend fun agents(): List<AgentInfo> =
        HerdrJson.decodeFromJsonElement(connection.call("agent.list")["agents"] ?: JsonArray(emptyList()))

    suspend fun readPane(paneId: String, source: String = "recent", lines: Int = 80, ansi: Boolean = false): PaneRead =
        HerdrJson.decodeFromJsonElement(
            connection.call(
                "pane.read",
                // [ansi]: the text with its colours and styles, as the terminal draws it.
                if (ansi) params("pane_id" to paneId, "source" to source, "lines" to lines, "format" to "ansi", "strip_ansi" to false)
                else params("pane_id" to paneId, "source" to source, "lines" to lines),
            )["read"]
                ?: JsonObject(emptyMap()),
        )

    /** Types [text] into an agent and submits it. */
    suspend fun prompt(target: String, text: String) {
        connection.call("agent.prompt", params("target" to target, "text" to text))
    }

    /** Sends herdr key-combo strings such as `enter`, `esc`, `ctrl+c`, `1`. */
    suspend fun sendKeys(paneId: String, keys: List<String>) {
        connection.call("pane.send_keys", params("pane_id" to paneId, "keys" to JsonArray(keys.map(::JsonPrimitive))))
    }

    suspend fun sendText(paneId: String, text: String) {
        connection.call("pane.send_text", params("pane_id" to paneId, "text" to text))
    }

    suspend fun createWorkspace(label: String?, cwd: String?) {
        connection.call("workspace.create", params("label" to label, "cwd" to cwd, "focus" to false))
    }

    suspend fun createTab(workspaceId: String, label: String?) {
        connection.call("tab.create", params("workspace_id" to workspaceId, "label" to label, "focus" to false))
    }

    /**
     * Opens a shell in [cwd] with [env]: a new tab of [workspaceId], or a new workspace when it is
     * null. Returns the new pane's id.
     */
    suspend fun openShell(workspaceId: String?, label: String, cwd: String?, env: Map<String, String> = emptyMap()): String {
        val vars = JsonObject(env.mapValues { JsonPrimitive(it.value) })
        val result = if (workspaceId != null) {
            connection.call("tab.create", params("workspace_id" to workspaceId, "label" to label, "cwd" to cwd, "env" to vars, "focus" to false))
        } else {
            connection.call("workspace.create", params("label" to label, "cwd" to cwd, "env" to vars, "focus" to false))
        }
        fun id(obj: String, key: String) = ((result[obj] as? JsonObject)?.get(key) as? JsonPrimitive)?.content
        val tab = id("tab", "tab_id")
        val workspace = id("workspace", "workspace_id")
        val panes = snapshot().panes
        return (panes.firstOrNull { tab != null && it.tabId == tab } ?: panes.firstOrNull { workspace != null && it.workspaceId == workspace })?.paneId
            ?: throw java.io.IOException("herdr didn't say which pane it opened")
    }

    /** Splits [paneId] and opens a shell in the new pane; its id, when herdr says it. */
    suspend fun splitPane(paneId: String, direction: String): String? {
        val result = connection.call("pane.split", params("target_pane_id" to paneId, "direction" to direction, "focus" to false))
        return ((result["pane"] as? JsonObject)?.get("pane_id") as? JsonPrimitive)?.content
    }

    suspend fun closePane(paneId: String) {
        connection.call("pane.close", params("pane_id" to paneId))
    }

    /**
     * Starts an agent of [kind] (claude, codex, pi…) named [name] in [paneId], which must be at its
     * shell's prompt. [args] only from a restart, which keeps an agent's own options
     * ([com.makefunstuff.herdr.core.chat.AgentFlags]): the phone never starts one that skips its permission prompts.
     */
    suspend fun startAgent(name: String, kind: String, paneId: String, args: List<String> = emptyList(), timeoutMs: Long = 60_000) {
        connection.call(
            "agent.start",
            params(
                "name" to name, "kind" to kind, "pane_id" to paneId,
                "args" to JsonArray(args.map(::JsonPrimitive)).takeIf { args.isNotEmpty() },
                "timeout_ms" to timeoutMs.takeIf { args.isNotEmpty() },
            ),
            timeoutMs = timeoutMs + 10_000,
        )
    }

    /**
     * Lists the checkouts of the repository that a workspace ([workspaceId]) or a folder ([cwd]) is in.
     * [trustRepository] grants Git trust for this one request; only after the user has vouched for the repository.
     */
    suspend fun worktrees(workspaceId: String? = null, cwd: String? = null, trustRepository: Boolean = false): WorktreeList =
        decode(connection.call("worktree.list", repoParams(workspaceId, cwd, trustRepository)))

    /**
     * Creates a worktree of the repository a workspace or folder is in and opens it as a new workspace.
     * Leaving [branch] out lets herdr pick a name; [base] is the ref the new branch starts from.
     */
    suspend fun createWorktree(
        workspaceId: String? = null,
        cwd: String? = null,
        branch: String? = null,
        base: String? = null,
        path: String? = null,
        label: String? = null,
        trustRepository: Boolean = false,
    ): WorktreeOpened = decode(
        connection.call(
            "worktree.create",
            repoParams(workspaceId, cwd, trustRepository, "branch" to branch, "base" to base, "path" to path, "label" to label, "focus" to false),
            timeoutMs = 60_000,
        ),
    )

    /** Opens an existing worktree, by [path] or [branch], as a workspace (or finds the one it's already open in). */
    suspend fun openWorktree(
        workspaceId: String? = null,
        cwd: String? = null,
        path: String? = null,
        branch: String? = null,
        label: String? = null,
        trustRepository: Boolean = false,
    ): WorktreeOpened = decode(
        connection.call(
            "worktree.open",
            repoParams(workspaceId, cwd, trustRepository, "path" to path, "branch" to branch, "label" to label, "focus" to false),
            timeoutMs = 60_000,
        ),
    )

    /**
     * Deletes the linked worktree checkout that [workspaceId] is open on, and closes the workspace.
     * herdr refuses a checkout with changes (`dirty_worktree_requires_force`) unless [force].
     */
    suspend fun removeWorktree(workspaceId: String, force: Boolean = false, trustRepository: Boolean = false): WorktreeRemoved =
        decode(
            connection.call(
                "worktree.remove",
                params("workspace_id" to workspaceId, "force" to force, "trust_repository" to trustRepository.takeIf { it }),
                timeoutMs = 60_000,
            ),
        )

    suspend fun pane(paneId: String): PaneInfo =
        HerdrJson.decodeFromJsonElement(connection.call("pane.get", params("pane_id" to paneId))["pane"] ?: JsonObject(emptyMap()))

    // ---- Scrollback, search, selection, links ------------------------------------------

    /** Scrolls the pane's viewport [offsetFromBottom] lines into its scrollback (0 = live screen). */
    suspend fun scrollPane(paneId: String, offsetFromBottom: Long): PaneInfo =
        HerdrJson.decodeFromJsonElement(
            connection.call("pane.scroll", params("pane_id" to paneId, "offset_from_bottom" to offsetFromBottom.coerceAtLeast(0)))["pane"]
                ?: JsonObject(emptyMap()),
        )

    /** Moves a copy-mode [cursor] by [motion]. Also the way to learn the pane's content revision. */
    suspend fun copyMotion(paneId: String, cursor: TextPoint, motion: CopyMotion, contentRevision: Long? = null): CopyMotionResult =
        decode(
            connection.call(
                "pane.copy_motion",
                params("pane_id" to paneId, "cursor" to point(cursor), "motion" to motion.wire, "content_revision" to contentRevision),
            ),
        )

    /** The pane's current content revision, which search and selection pin to. */
    suspend fun contentRevision(paneId: String): Long =
        copyMotion(paneId, TextPoint(0, 0), CopyMotion.LineEnd).contentRevision

    /**
     * Searches the scrollback for [query] from [cursor]. Pass the current match as [previous] to
     * step to the next one. Fails with code `stale_content` when the pane changed since [contentRevision].
     */
    suspend fun copySearch(
        paneId: String,
        query: String,
        backward: Boolean,
        cursor: TextPoint,
        contentRevision: Long,
        previous: TextRange? = null,
    ): CopySearchResult = decode(
        connection.call(
            "pane.copy_search",
            params(
                "pane_id" to paneId,
                "query" to query,
                "direction" to if (backward) "backward" else "forward",
                "cursor" to point(cursor),
                "content_revision" to contentRevision,
                "previous" to previous?.let { HerdrJson.encodeToJsonElement(it) },
            ),
        ),
    )

    /** The text between [anchor] and [cursor], inclusive, as herdr's copy mode would copy it. */
    suspend fun readSelection(paneId: String, anchor: TextPoint, cursor: TextPoint, contentRevision: Long? = null): String {
        val result = connection.call(
            "pane.selection.read",
            params("pane_id" to paneId, "anchor" to point(anchor), "cursor" to point(cursor), "content_revision" to contentRevision),
        )
        return (result["text"] as? JsonPrimitive)?.content ?: ""
    }

    /** The link regions under viewport cell ([viewportRow], [col]); empty when there's no link. */
    suspend fun resolveLink(paneId: String, viewportRow: Int, col: Int): List<LinkRegion> =
        HerdrJson.decodeFromJsonElement(
            connection.call("pane.link.resolve", params("pane_id" to paneId, "viewport_row" to viewportRow, "col" to col))["regions"]
                ?: JsonArray(emptyList()),
        )

    /** Activates the link under a viewport cell: a host plugin may handle it, otherwise its URL comes back. */
    suspend fun activateLink(paneId: String, viewportRow: Int, col: Int): LinkActivation =
        decode(connection.call("pane.link.activate", params("pane_id" to paneId, "viewport_row" to viewportRow, "col" to col)))

    // ---- Layout ------------------------------------------------------------------------

    /** Moves the split edge of [paneId] toward [direction] by [amount] (a fraction of the split; herdr's step when null). */
    suspend fun resizePane(paneId: String, direction: ResizeDirection, amount: Float? = null): PaneResizeResult =
        HerdrJson.decodeFromJsonElement(
            connection.call("pane.resize", params("pane_id" to paneId, "direction" to direction.wire, "amount" to amount))["resize"]
                ?: JsonObject(emptyMap()),
        )

    /** The pane's tab layout as rectangles: panes and splits. */
    suspend fun paneLayout(paneId: String): TabLayout =
        HerdrJson.decodeFromJsonElement(connection.call("pane.layout", params("pane_id" to paneId))["layout"] ?: JsonObject(emptyMap()))

    /** The tab's split tree. */
    suspend fun exportLayout(tabId: String): LayoutDescription =
        HerdrJson.decodeFromJsonElement(connection.call("layout.export", params("tab_id" to tabId))["layout"] ?: JsonObject(emptyMap()))

    /** Sets the ratio of the split at [path] in [tabId] (false = first child, true = second; empty = the root). */
    suspend fun setSplitRatio(tabId: String, path: List<Boolean>, ratio: Float): LayoutDescription =
        HerdrJson.decodeFromJsonElement(
            connection.call(
                "layout.set_split_ratio",
                params("tab_id" to tabId, "path" to JsonArray(path.map(::JsonPrimitive)), "ratio" to ratio),
            )["layout"] ?: JsonObject(emptyMap()),
        )

    /**
     * Builds [root] as a tab. With [replaceTabId], that tab's panes are closed and the layout
     * takes its place; otherwise it opens as a new tab in [workspaceId] named [label].
     */
    suspend fun applyLayout(
        root: LayoutNode,
        workspaceId: String? = null,
        replaceTabId: String? = null,
        label: String? = null,
    ): LayoutDescription =
        HerdrJson.decodeFromJsonElement(
            connection.call(
                "layout.apply",
                params(
                    "root" to HerdrJson.encodeToJsonElement(root),
                    "workspace_id" to workspaceId,
                    "tab_id" to replaceTabId,
                    "tab_label" to label,
                    "focus" to false,
                ),
                timeoutMs = 60_000,
            )["layout"] ?: JsonObject(emptyMap()),
        )

    private fun point(p: TextPoint) = params("row" to p.row, "col" to p.col)

    // Workspaces

    suspend fun workspace(workspaceId: String): WorkspaceInfo =
        HerdrJson.decodeFromJsonElement(connection.call("workspace.get", params("workspace_id" to workspaceId))["workspace"]!!)

    suspend fun renameWorkspace(workspaceId: String, label: String) {
        connection.call("workspace.rename", params("workspace_id" to workspaceId, "label" to label))
    }

    suspend fun closeWorkspace(workspaceId: String) {
        connection.call("workspace.close", params("workspace_id" to workspaceId))
    }

    /** Names the repository by workspace or folder; trust is only sent when granted. */
    private fun repoParams(workspaceId: String?, cwd: String?, trust: Boolean, vararg more: Pair<String, Any?>): JsonObject =
        params("workspace_id" to workspaceId, "cwd" to cwd, "trust_repository" to trust.takeIf { it }, *more)
    suspend fun focusWorkspace(workspaceId: String) {
        connection.call("workspace.focus", params("workspace_id" to workspaceId))
    }

    /** Moves a workspace to [insertIndex]: before the workspace now at that index, or last at the list's size. */
    suspend fun moveWorkspace(workspaceId: String, insertIndex: Int) {
        connection.call("workspace.move", params("workspace_id" to workspaceId, "insert_index" to insertIndex))
    }

    /** Moves [workspaceIds] together, in that order, before [beforeWorkspaceId] (to the end when null). */
    suspend fun moveWorkspaces(workspaceIds: List<String>, beforeWorkspaceId: String?) {
        connection.call(
            "workspace.move_block",
            params("workspace_ids" to JsonArray(workspaceIds.map(::JsonPrimitive)), "before_workspace_id" to beforeWorkspaceId),
        )
    }

    // Tabs

    suspend fun renameTab(tabId: String, label: String) {
        connection.call("tab.rename", params("tab_id" to tabId, "label" to label))
    }

    suspend fun closeTab(tabId: String) {
        connection.call("tab.close", params("tab_id" to tabId))
    }

    suspend fun focusTab(tabId: String) {
        connection.call("tab.focus", params("tab_id" to tabId))
    }

    /** Moves a tab within its workspace; [insertIndex] works as in [moveWorkspace]. */
    suspend fun moveTab(tabId: String, insertIndex: Int) {
        connection.call("tab.move", params("tab_id" to tabId, "insert_index" to insertIndex))
    }

    // Panes

    /** Names a pane; a null or blank [label] clears the name. */
    suspend fun renamePane(paneId: String, label: String?) {
        connection.call("pane.rename", params("pane_id" to paneId, "label" to label?.ifBlank { null }))
    }

    suspend fun movePane(paneId: String, destination: PaneDestination): LayoutChange =
        LayoutChange.of(
            connection.call("pane.move", params("pane_id" to paneId, "destination" to destination.toJson(), "focus" to false))["move_result"] as? JsonObject,
        )

    /** Swaps a pane with [targetPaneId], or with its neighbour in [direction] (left, right, up, down). */
    suspend fun swapPane(paneId: String, targetPaneId: String? = null, direction: String? = null): LayoutChange =
        LayoutChange.of(
            connection.call(
                "pane.swap",
                // herdr takes either pane_id + direction, or source_pane_id + target_pane_id.
                if (direction != null) params("pane_id" to paneId, "direction" to direction)
                else params("source_pane_id" to paneId, "target_pane_id" to targetPaneId),
            )["swap"] as? JsonObject,
        )

    /** [mode] is toggle, on or off. */
    suspend fun zoomPane(paneId: String, mode: String = "toggle"): LayoutChange =
        LayoutChange.of(
            connection.call("pane.zoom", params("pane_id" to paneId, "mode" to mode))["zoom"] as? JsonObject,
            changedKey = "zoom_changed",
        )

    suspend fun focusPane(paneId: String) {
        connection.call("pane.focus", params("pane_id" to paneId))
    }

    suspend fun focusDirection(paneId: String, direction: String): LayoutChange =
        LayoutChange.of(
            connection.call("pane.focus_direction", params("pane_id" to paneId, "direction" to direction))["focus"] as? JsonObject,
        )

    /** Clears the pane's screen and scrollback. */
    suspend fun clearPane(paneId: String) {
        connection.call("pane.clear", params("pane_id" to paneId))
    }

    suspend fun processInfo(paneId: String): PaneProcessInfo =
        HerdrJson.decodeFromJsonElement(
            connection.call("pane.process_info", params("pane_id" to paneId))["process_info"] ?: JsonObject(emptyMap()),
        )

    // Agents. [target] is an agent name, a pane id or a terminal id.

    suspend fun agent(target: String): AgentInfo =
        HerdrJson.decodeFromJsonElement(connection.call("agent.get", params("target" to target))["agent"]!!)

    /** Names an agent; a null or blank [name] clears it. */
    suspend fun renameAgent(target: String, name: String?) {
        connection.call("agent.rename", params("target" to target, "name" to (name?.ifBlank { null } ?: JsonNull)))
    }

    suspend fun focusAgent(target: String) {
        connection.call("agent.focus", params("target" to target))
    }

    suspend fun explainAgent(target: String): AgentExplanation =
        AgentExplanation.of(connection.call("agent.explain", params("target" to target))["explain"])

    /** Sends key combos to a named agent (see [sendKeys] for panes). */
    suspend fun agentSendKeys(target: String, keys: List<String>) {
        connection.call("agent.send_keys", params("target" to target, "keys" to JsonArray(keys.map(::JsonPrimitive))))
    }

    // Notifications

    /** Shows a notification in herdr on the machine. [sound] is none, done or request. */
    suspend fun showNotification(title: String, body: String? = null, sound: String = "none"): NotificationResult =
        decode(connection.call("notification.show", params("title" to title, "body" to body?.ifBlank { null }, "sound" to sound)))

    // --- Plugins ---

    suspend fun plugins(): List<PluginInfo> =
        HerdrJson.decodeFromJsonElement(connection.call("plugin.list")["plugins"] ?: JsonArray(emptyList()))

    /** Enables or disables a plugin and returns it as herdr now reports it. */
    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean): PluginInfo =
        HerdrJson.decodeFromJsonElement(
            connection.call(if (enabled) "plugin.enable" else "plugin.disable", params("plugin_id" to pluginId))["plugin"]
                ?: throw HerdrApiException("bad_response", "herdr didn't return the plugin"),
        )

    suspend fun pluginActions(pluginId: String? = null): List<PluginAction> =
        HerdrJson.decodeFromJsonElement(
            connection.call("plugin.action.list", params("plugin_id" to pluginId))["actions"] ?: JsonArray(emptyList()),
        )

    /** Starts a plugin action. herdr runs it in the background; the returned log is its first state. */
    suspend fun invokePluginAction(pluginId: String, actionId: String): PluginLog? =
        connection.call(
            "plugin.action.invoke",
            params("plugin_id" to pluginId, "action_id" to actionId, "context" to params("invocation_source" to "herdr-android")),
        )["log"]?.let { HerdrJson.decodeFromJsonElement<PluginLog>(it) }

    /** Recent plugin command runs, newest first. */
    suspend fun pluginLogs(pluginId: String? = null, limit: Int? = 50): List<PluginLog> =
        HerdrJson.decodeFromJsonElement<List<PluginLog>>(
            connection.call("plugin.log.list", params("plugin_id" to pluginId, "limit" to limit))["logs"] ?: JsonArray(emptyList()),
        ).sortedByDescending { it.startedUnixMs }

    // --- Integrations ---

    suspend fun integrations(): List<IntegrationInfo> =
        HerdrJson.decodeFromJsonElement(connection.call("integration.list")["integrations"] ?: JsonArray(emptyList()))

    /** Installs herdr's hook for an agent; returns herdr's messages about what it did. */
    suspend fun installIntegration(target: String): List<String> =
        messages(connection.call("integration.install", params("target" to target), timeoutMs = 60_000))

    suspend fun uninstallIntegration(target: String): List<String> =
        messages(connection.call("integration.uninstall", params("target" to target), timeoutMs = 60_000))

    // --- Server ---

    suspend fun reloadConfig(): ConfigReload = decode(connection.call("server.reload_config"))

    suspend fun agentManifests(): List<AgentManifestInfo> =
        HerdrJson.decodeFromJsonElement(connection.call("server.agent_manifests")["manifests"] ?: JsonArray(emptyList()))

    suspend fun reloadAgentManifests(): List<AgentManifestInfo> =
        HerdrJson.decodeFromJsonElement(
            connection.call("server.reload_agent_manifests", timeoutMs = 60_000)["manifests"] ?: JsonArray(emptyList()),
        )

    /** Stops the herdr server, closing every pane in the session. */
    suspend fun stopServer() {
        connection.call("server.stop")
    }

    private fun messages(result: JsonObject): List<String> =
        (result["details"] as? JsonObject)?.get("messages")?.let { HerdrJson.decodeFromJsonElement<List<String>>(it) } ?: emptyList()

    private inline fun <reified T> decode(obj: JsonObject): T = HerdrJson.decodeFromJsonElement(obj)

    companion object {
        /** Lifecycle events that change what the app shows. */
        val LIFECYCLE_EVENTS = listOf(
            "workspace.created", "workspace.updated", "workspace.renamed", "workspace.closed",
                        "worktree.created", "worktree.opened", "worktree.removed",
            "workspace.reordered", "workspace.moved", "workspace.focused", "workspace.metadata_updated",
            "tab.created", "tab.closed", "tab.renamed", "tab.moved", "tab.focused",
            "pane.created", "pane.closed", "pane.updated", "pane.moved", "pane.focused", "pane.exited",
            "pane.agent_detected", "layout.updated",
        )

        fun subscriptionParams(paneIds: List<String>): JsonObject {
            val entries = LIFECYCLE_EVENTS.map { JsonObject(mapOf("type" to JsonPrimitive(it))) } +
                paneIds.map {
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("pane.agent_status_changed"),
                            "pane_id" to JsonPrimitive(it),
                        ),
                    )
                }
            return params("subscriptions" to JsonArray(entries))
        }
    }
}
