package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.ssh.HerdrCommands
import kotlinx.coroutines.delay
import java.io.IOException

/** The CLI is the authority for available models, modes and account quotas. */
object AgySettings {
    fun supports(kind: String?) = kind in setOf("agy", "antigravity", "antigravity-cli", "antigravity_cli")
    data class Model(val id: String, val name: String)
    private val ID = Regex("[A-Za-z0-9._:/@+-]{1,200}")
    val COMMAND = "sh -c " + HerdrCommands.quote("""
        for p in "${'$'}(command -v agy 2>/dev/null)" "${'$'}HOME/.local/bin/agy" /opt/homebrew/bin/agy /usr/local/bin/agy; do
          if [ -n "${'$'}p" ] && [ -x "${'$'}p" ]; then exec "${'$'}p" models; fi
        done
        exit 127
    """.trimIndent())
    fun models(text: String): List<Model> = text.lineSequence().mapNotNull { line ->
        val fields = line.split('\t', limit = 2)
        if (fields.size != 2 || !ID.matches(fields[0]) || fields[1].isBlank()) null
        else Model(fields[0], fields[1].trim())
    }.distinctBy { it.id }.toList()
    fun modelCommand(id: String): String {
        require(ID.matches(id))
        return "/model $id"
    }

    /** Only explicit CLI menus, never an ordinary `> prompt` or model output. */
    fun prompt(screen: String): BlockedPrompt? {
        if (screen.lineSequence().any { it.trim() in setOf("Settings", "Switch Model") } || modes(screen) != null) return null
        val menu = BlockedPrompt.parse(screen) ?: return null
        return menu.takeIf {
            it.options.size >= 2 && screen.contains(Regex("(?i)(↑/↓|↑↓).*?(navigate|select)|enter (confirm|save|select)|esc.*(reject|cancel)"))
        }
    }

    fun modes(screen: String): BlockedPrompt? = BlockedPrompt.parse(screen)?.takeIf { p ->
        screen.contains("Agent Mode") && screen.contains("enter Save") &&
            p.options.size >= 2 && p.options.all { it.label.removeSuffix(" (current)") in setOf("default", "accept-edits", "auto", "plan") }
    }

    private suspend fun waitScreen(api: AgentPort, pane: String, accepts: (String) -> Boolean): String {
        repeat(20) {
            val text = api.read(pane, 160, visible = true)
            if (accepts(text)) return text
            delay(150)
        }
        throw IOException("The agy menu didn't open. Check its terminal.")
    }

    /** Opens the actual picker; no guessed /mode command or blind Shift+Tab cycling. */
    suspend fun openModes(api: AgentPort, pane: String): BlockedPrompt {
        api.type(pane, "/config")
        waitScreen(api, pane) { it.contains("Settings") && Regex("(?m)^\\s*> Agent Mode\\s+").containsMatchIn(it) }
        api.keys(pane, listOf("enter"), false)
        return modes(waitScreen(api, pane) { modes(it) != null })!!
    }

    suspend fun openModels(api: AgentPort, pane: String): BlockedPrompt {
        api.type(pane, "/model")
        val screen = waitScreen(api, pane) { it.contains("Switch Model") && it.contains("enter Select") }
        return BlockedPrompt.parse(screen)?.takeIf { it.options.size >= 2 }
            ?: throw IOException("Couldn't read agy's model picker. Check its terminal.")
    }

    /** Copies every page of the quota viewer; does not infer limits from token counts. */
    suspend fun usage(api: AgentPort, pane: String): String {
        api.type(pane, "/usage")
        var page = waitScreen(api, pane) { it.contains("Models & Quota") }
        val pages = mutableListOf<String>()
        repeat(8) {
            val content = page.substringAfter("Models & Quota").substringBefore("↑/↓").lines()
                .filterNot { it.trim().startsWith("Account:") || it.contains("? for shortcuts") }.joinToString("\n").trim()
            if (content !in pages) pages += content
            val range = Regex("\\((\\d+)[–-](\\d+) of (\\d+) lines\\)").find(page)
            if (range == null || range.groupValues[2] == range.groupValues[3]) {
                api.keys(pane, listOf("esc"), false)
                return pages.joinToString("\n\n")
            }
            api.keys(pane, listOf("pgdn"), false)
            delay(200)
            page = api.read(pane, 160, visible = true)
        }
        api.keys(pane, listOf("esc"), false)
        return pages.joinToString("\n\n")
    }
}
