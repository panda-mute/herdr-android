package com.makefunstuff.herdr.core.chat

import kotlin.math.roundToInt

/**
 * The model an agent answers with and how full its context is, as its own transcript records it:
 * [model] is the id as written (`claude-opus-5-5`, `gpt-5.5`), [effort] the reasoning level it
 * ran at, [used] the tokens its last request filled and [window] how many it holds, when known.
 */
data class ModelUse(
    val model: String? = null,
    val effort: String? = null,
    val used: Long = 0,
    val window: Long? = null,
    /** True when [window] is the model's usual size rather than one the transcript states. */
    val windowAssumed: Boolean = false,
    val outputTokens: Long? = null,
    val cacheReadTokens: Long? = null,
) {
    /** How full the context is, 0–100; null without a window. */
    val percent: Int? get() = window?.takeIf { it > 0 && used > 0 }?.let { ((used * 100.0) / it).roundToInt().coerceIn(0, 100) }

    /** The model's name ([name]), or its id as written. */
    val title: String? get() = model?.let { name(it) ?: it }

    companion object {
        /** Claude's standard window; a request past it can only have run in the 1M one. */
        const val CLAUDE_WINDOW = 200_000L
        const val CLAUDE_LONG_WINDOW = 1_000_000L

        private val CLAUDE = Regex("^(?:anthropic/)?claude-([a-z]+)-([1-9][0-9]?)(?:-([0-9]|[1-9][0-9]))?$")
        private val GPT = Regex("^gpt-([1-9][0-9]*(?:\\.[0-9]+)?)(-sol)?$")
        private val GLM = Regex("^glm-([1-9][0-9]*(?:\\.[0-9]+)?)$")

        /**
         * A model's name, only where its id follows the vendor's own regular naming, matched
         * whole (as the herdr web UI names them): `claude-opus-5-5` is `Opus 5.5`, `gpt-5.5` is
         * `GPT-5.5`, `glm-4.6` is `GLM-4.6`. Anything else (a dated snapshot, another provider's
         * prefix, a `[1m]` suffix) is null, and is shown as the id: a name is never guessed.
         */
        fun name(id: String): String? {
            CLAUDE.matchEntire(id)?.let { m ->
                val (family, major, minor) = m.destructured
                return family.replaceFirstChar { it.uppercase() } + " " + major + (if (minor.isNotEmpty()) ".$minor" else "")
            }
            GPT.matchEntire(id)?.let { m -> return "GPT-" + m.groupValues[1] + (if (m.groupValues[2].isNotEmpty()) "-Sol" else "") }
            GLM.matchEntire(id)?.let { m -> return "GLM-" + m.groupValues[1] }
            return null
        }

        /**
         * The context a request filled, with its window: the one the transcript states, else for a
         * Claude model its standard 200k (assumed), or 1M once a request went past that or the id
         * asks for it (and it keeps it: a compaction shrinks the use, not the window).
         */
        fun filled(used: Long, model: String?, stated: Long?, before: ModelUse?): ModelUse {
            val base = (before ?: ModelUse()).copy(model = model ?: before?.model)
            if (used <= 0) return base
            if (stated != null && stated > 0) return base.copy(used = used, window = stated, windowAssumed = false)
            val m = base.model.orEmpty()
            if (!m.removePrefix("anthropic/").startsWith("claude")) return base.copy(used = used, window = null, windowAssumed = false)
            val long = used > CLAUDE_WINDOW || m.endsWith("[1m]") || before?.window == CLAUDE_LONG_WINDOW
            return base.copy(used = used, window = if (long) CLAUDE_LONG_WINDOW else CLAUDE_WINDOW, windowAssumed = true)
        }

        /** A token count as people read it: `950`, `12k`, `124k`, `1.2M`. */
        fun tokens(n: Long): String = when {
            n < 1_000 -> "$n"
            n < 1_000_000 -> "${(n / 1_000.0).roundToInt()}k"
            else -> {
                val m = (n / 100_000.0).roundToInt() / 10.0
                if (m == m.toLong().toDouble()) "${m.toLong()}M" else "${m}M"
            }
        }
    }
}

/**
 * What the composer's model pill shows: herdr's own fields for the agent first (an integration
 * that reports `model` and `context` among its `tokens`), then what the transcript says.
 */
data class ModelPill(val title: String, val percent: Int?, val use: ModelUse?) {
    companion object {
        fun of(tokens: Map<String, String>, use: ModelUse?): ModelPill? {
            val model = tokens["model"]?.trim()?.ifEmpty { null }
            val reported = tokens["context"]?.trim()?.removeSuffix("%")?.trim()?.toDoubleOrNull()?.roundToInt()?.coerceIn(0, 100)
            val title = model?.let { ModelUse.name(it) ?: it } ?: use?.title ?: return null
            return ModelPill(title, reported ?: use?.percent, use)
        }
    }
}
