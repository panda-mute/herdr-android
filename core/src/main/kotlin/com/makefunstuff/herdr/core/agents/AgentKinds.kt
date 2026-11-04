package com.makefunstuff.herdr.core.agents

/**
 * The coding agents the app recognises, as herdr names them, and how to tell one from a
 * program's name or command line. The list and the rules follow Alma's desktop app (its herdr
 * mapping): other names an agent goes by (`claude-code`, `cursor-agent`, `antigravity`), npm
 * packages (`@openai/codex`), a version after the name (`codex@0.166.0`), and launchers in front
 * of it (`node`, `npx`, `bunx`), including wrappers that run Claude Code (`happy`, `ccr code`).
 */
object AgentKinds {
    /** Every kind, herdr's names: Alma's list, with aider and the two herdr starts besides. */
    val ALL: Set<String> = linkedSetOf(
        "claude", "codex", "pi", "opencode", "gemini", "amp", "droid", "cursor", "copilot", "kiro", "agy", "qodercli",
        "qwen", "kimi", "grok", "hermes", "mastracode", "devin", "cline", "kilo", "omp", "letta", "goose", "crush",
        "aider", "maki", "muse",
    )

    /** Other names for a kind: what its program or its status report calls it. */
    private val ALIASES = mapOf(
        "claude-code" to "claude", "pi-coding-agent" to "pi", "github-copilot" to "copilot", "cursor-agent" to "cursor",
        "kiro-cli" to "kiro", "antigravity" to "agy", "antigravity-cli" to "agy", "qoder" to "qodercli",
        "kilo-code" to "kilo", "kilocode" to "kilo", "open-code" to "opencode", "open_code" to "opencode",
        "qwen-code" to "qwen", "gemini-cli" to "gemini", "kimi-cli" to "kimi", "grok-cli" to "grok",
    )

    /** npm packages whose name isn't the agent's. */
    private val PACKAGES = mapOf(
        "@anthropic-ai/claude-code" to "claude", "@openai/codex" to "codex", "@mariozechner/pi-coding-agent" to "pi",
        "@google/gemini-cli" to "gemini", "opencode-ai" to "opencode", "happy-coder" to "happy",
        "@musistudio/claude-code-router" to "ccr",
    )

    /** Interpreters and package runners that start an agent: the agent comes after them. */
    private val INTERPRETERS = setOf("node", "bun", "deno", "python", "python3")
    private val RUNNERS = setOf("npx", "bunx", "pnpx", "pnpm", "yarn", "dlx", "exec", "x")

    /** `happy`'s own commands, which aren't an agent session. */
    private val HAPPY_COMMANDS = Regex("^(daemon|auth|doctor|notify|logout|connect|install|uninstall|help|version)$")
    private val CLAUDE_BUILD = Regex("""[\\/]claude[\\/]versions[\\/][^\\/]+$""")
    private val CLAUDE_LAUNCHER = Regex("""claude_(local|remote)_launcher\.cjs$""")
    private val VERSION = Regex("""(?<=.)@[^/@]*$""")
    private val SCRIPT = Regex("""\.(js|mjs|cjs|exe)$""", RegexOption.IGNORE_CASE)

    /** The kind [name] is (`claude` for `claude-code`), or null when it names no agent. */
    fun normalize(name: String?): String? {
        val n = name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val kind = ALIASES[n] ?: n
        return kind.takeIf { it in ALL }
    }

    /** The kind a status report's app name is: `claude…` is Claude, else the name or its first word. */
    fun fromApp(app: String?): String? {
        val a = app?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (a.startsWith("claude")) return "claude"
        return normalize(a) ?: normalize(a.split('-', '_', ' ').first())
    }

    /** The agent a command line runs, looking past launchers in its first few words; null for none. */
    fun fromArgv(argv: List<String>): String? {
        val words = argv.take(6)
        for ((i, word) in words.withIndex()) {
            if (word.startsWith("-")) continue
            if (CLAUDE_BUILD.containsMatchIn(word) || CLAUDE_LAUNCHER.containsMatchIn(word)) return "claude"
            val bare = word.replace(VERSION, "")
            val program = PACKAGES[bare]
                ?: if (Regex("""[\\/]claude-code-router[\\/]""").containsMatchIn(word)) "ccr"
                else word.split('/', '\\').last().replace(SCRIPT, "").lowercase()
            normalize(program)?.let { return it }
            // Wrappers that run Claude Code: `ccr code`, and `happy` but for its own commands.
            val next = words.drop(i + 1).firstOrNull { !it.startsWith("-") }
            when (program) {
                "ccr" -> return if (next == "code") "claude" else null
                "happy" -> return when {
                    next == null -> "claude"
                    else -> normalize(next) ?: if (HAPPY_COMMANDS.matches(next)) null else "claude"
                }
            }
            if (program !in INTERPRETERS && program !in RUNNERS) break
        }
        return null
    }

    /** True when a process named [name] is an agent's own program (`cursor-agent`, `kiro-cli`). */
    fun isProgram(name: String): Boolean = normalize(name) != null
}
