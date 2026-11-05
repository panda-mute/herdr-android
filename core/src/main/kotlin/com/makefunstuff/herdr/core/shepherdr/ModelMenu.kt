package com.makefunstuff.herdr.core.shepherdr

import kotlinx.serialization.Serializable

/**
 * Shepherdr 3.1's model menu (the window's `mods/chat-models`, the web's `ModelMenu` in `web/src/restart.tsx`), as the
 * service's `model-menu` action answers it (`service/agents.ts` `menu()`): what an agent runs on now, what each
 * installed CLI offers, and the one restart that applies a change.
 */
@Serializable
data class V2ModelMenu(
    val current: V2ModelPick = V2ModelPick(),
    /** Each installed CLI's choices, by its kind (`claude`, `codex`), in the menu's order. */
    val menus: Map<String, V2AgentMenu> = emptyMap(),
    /** The permission mode a CLI starts in when nothing else is picked. */
    val permDefault: Map<String, String> = emptyMap(),
    /** Why it can't restart from here ("This computer only"), or null. */
    val remote: String? = null,
)

/** What an agent runs on, or what is picked for it: its CLI, model, reasoning level, Fast and permission mode. */
@Serializable
data class V2ModelPick(
    val agent: String = "claude",
    val model: String? = null,
    val effort: String? = null,
    val fast: Boolean = false,
    val perm: String? = null,
)

/** One CLI's part of the menu (`core/agents.js` `menuFor`). */
@Serializable
data class V2AgentMenu(
    val name: String = "",
    val models: List<V2MenuModel> = emptyList(),
    val perms: List<V2Perm> = emptyList(),
    /** Fast is set in its own config: not a switch here. */
    val fastLocked: Boolean = false,
)

@Serializable
data class V2MenuModel(
    val id: String,
    val name: String = "",
    val efforts: List<String> = emptyList(),
    val defaultEffort: String? = null,
    /** It has a Fast mode. */
    val fast: Boolean = false,
)

@Serializable
data class V2Perm(val id: String, val label: String = "")

/** The menu's rules, as the web's `ModelMenu` has them. */
object ModelMenus {
    /** The menu's headings, `MODEL_MENU` in 3.1's `core/settings.js`. */
    const val MODEL = "Model"
    const val REASONING = "Reasoning"
    const val FAST = "Fast"
    const val PERMISSIONS = "Permissions"

    /** Reasoning levels in words. */
    private val LEVEL = mapOf(
        "minimal" to "Minimal", "low" to "Low", "medium" to "Medium", "high" to "High", "xhigh" to "Extra high",
        "max" to "Max", "ultra" to "Ultra", "none" to "None",
    )

    fun levelName(level: String): String = LEVEL[level] ?: level

    /** A level as a phone's five segments fit it: "Extra high" as "X-high". */
    fun shortLevelName(level: String): String = if (level == "xhigh") "X-high" else levelName(level)

    /** Every installed CLI's models, in the menu's order, each with its CLI's kind. */
    fun models(m: V2ModelMenu): List<Pair<String, V2MenuModel>> = m.menus.flatMap { (agent, menu) -> menu.models.map { agent to it } }

    fun model(m: V2ModelMenu, sel: V2ModelPick): V2MenuModel? = m.menus[sel.agent]?.models?.firstOrNull { it.id == sel.model }

    /** The reasoning level picked, else the model's own default. */
    fun level(m: V2ModelMenu, sel: V2ModelPick): String? = sel.effort ?: model(m, sel)?.defaultEffort

    /**
     * [model] of [agent] picked: the level and Fast stay where it has them; another CLI brings its own permission modes
     * (full access staying full access) and its own Fast setting.
     */
    fun choose(m: V2ModelMenu, sel: V2ModelPick, agent: String, model: V2MenuModel): V2ModelPick {
        var next = sel.copy(agent = agent, model = model.id)
        if (next.effort != null && next.effort !in model.efforts) next = next.copy(effort = null)
        if (!model.fast) next = next.copy(fast = false)
        if (agent != sel.agent) {
            val full = sel.perm == "bypassPermissions" || sel.perm == "full"
            next = next.copy(
                perm = if (full) (if (agent == "claude") "bypassPermissions" else "full") else m.permDefault[agent],
                fast = m.menus[agent]?.fastLocked == true,
            )
        }
        return next
    }

    /** What [sel] changes from what runs now, in words ("Sonnet 5.5", "High", "Fast"); empty: nothing to restart for. */
    fun changes(m: V2ModelMenu, sel: V2ModelPick): List<String> {
        if (m.remote != null) return emptyList()
        val w = m.current
        val out = ArrayList<String>()
        if (sel.agent != w.agent || sel.model != w.model) out += model(m, sel)?.name ?: sel.model.orEmpty()
        val level = level(m, sel)
        if (level != null && level != level(m, w)) out += levelName(level)
        if (sel.fast != w.fast) out += if (sel.fast) "Fast" else "Standard"
        if (sel.perm != null && sel.perm != w.perm) out += m.menus[sel.agent]?.perms?.firstOrNull { it.id == sel.perm }?.label ?: sel.perm
        return out
    }

    /** The restart button's words: "Restart with Sonnet 5.5 · High". */
    fun restartWords(changes: List<String>): String = "Restart with " + changes.joinToString(" · ")
}
