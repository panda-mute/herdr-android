package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.ssh.HerdrCommands
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The models Codex can switch to, from its own catalog on the machine (`codex debug models`, which
 * also lists a custom provider's), and the `/model` command that switches a running Codex: it
 * takes `/model <slug> --reasoning=<level>` as words, for that session only, between turns.
 */
object CodexModels {
    data class Model(val slug: String, val name: String, val efforts: List<String>, val defaultEffort: String?)

    // Drops what the phone doesn't need (each model's instructions make the catalog over a megabyte).
    private const val TRIM = "import json,sys; d=json.load(sys.stdin); ms=d.get(\"models\",[]) if isinstance(d,dict) else d; " +
        "json.dump({\"models\":[{k:m.get(k) for k in (\"slug\",\"display_name\",\"visibility\",\"supported_reasoning_levels\",\"default_reasoning_level\")} for m in ms]},sys.stdout)"

    private val SCRIPT = """
        c=""
        for p in "${'$'}(command -v codex 2>/dev/null)" "${'$'}HOME/.local/bin/codex" "${'$'}("${'$'}{SHELL:-sh}" -lc 'command -v codex' 2>/dev/null | tail -n 1)" /opt/homebrew/bin/codex /usr/local/bin/codex; do
          if [ -n "${'$'}p" ] && [ -x "${'$'}p" ]; then c="${'$'}p"; break; fi
        done
        [ -n "${'$'}c" ] || { echo "codex not found" >&2; exit 127; }
        if command -v python3 >/dev/null 2>&1; then "${'$'}c" debug models | python3 -c '$TRIM'; else "${'$'}c" debug models; fi
    """.trimIndent()

    /** Prints the catalog as JSON. */
    val COMMAND: String = "sh -c " + HerdrCommands.quote(SCRIPT)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The listed models in [text], in Codex's order; empty when it holds none. */
    fun parse(text: String): List<Model> {
        val root = runCatching { json.parseToJsonElement(text.trim()) }.getOrNull() ?: return emptyList()
        val models = (root as? JsonObject)?.get("models") as? JsonArray ?: root as? JsonArray ?: return emptyList()
        return models.mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            val slug = o.str("slug")?.takeIf { validSlug(it) } ?: return@mapNotNull null
            if (o.str("visibility") == "hide") return@mapNotNull null
            val efforts = (o["supported_reasoning_levels"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.str("effort")?.takeIf { e -> validEffort(e) } }
            Model(slug, o.str("display_name")?.takeIf { it.isNotBlank() } ?: slug, efforts, o.str("default_reasoning_level"))
        }
    }

    /** `/model <slug> --reasoning=<effort>`; either may be left out (but not both). */
    fun command(slug: String?, effort: String?): String {
        require(slug != null || effort != null) { "nothing to switch" }
        require(slug == null || validSlug(slug)) { "not a model: $slug" }
        require(effort == null || validEffort(effort)) { "not an effort: $effort" }
        return listOfNotNull("/model", slug, effort?.let { "--reasoning=$it" }).joinToString(" ")
    }

    // One word each: Codex splits the command at spaces.
    private val SLUG = Regex("^[A-Za-z0-9._:/@+-]{1,200}$")
    private val EFFORT = Regex("^[a-z]{1,20}$")
    private fun validSlug(s: String) = SLUG.matches(s)
    private fun validEffort(s: String) = EFFORT.matches(s)

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
