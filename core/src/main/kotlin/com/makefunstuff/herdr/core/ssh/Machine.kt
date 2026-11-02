package com.makefunstuff.herdr.core.ssh

import kotlinx.serialization.Serializable

/**
 * A herdr machine: an SSH target plus one herdr session, matching herdr's own saved machines.
 */
@Serializable
data class Machine(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val session: String = "default",
    /** SHA256 host key fingerprint pinned on first connect. */
    val hostKey: String? = null,
    /** Another machine to use as an SSH jump host, for hosts only reachable from it. */
    val viaMachineId: String? = null,
    /** Explicit herdr binary path on the host; empty means search the usual places. */
    val herdrPath: String? = null,
    val enabled: Boolean = true,
    /** Whether herdr may copy helper scripts (herdr-usage, herdr-push, etc.) to this host. */
    val allowHostScripts: Boolean = true,
) {
    val target: String get() = if (port == 22) "$user@$host" else "$user@$host:$port"

    companion object {
        /** Parses `user@host`, `user@host:port`, or `ssh://user@host:port`. */
        fun parseTarget(raw: String): Triple<String?, String, Int>? {
            var t = raw.trim().removePrefix("ssh://").trimEnd('/')
            if (t.isEmpty()) return null
            var user: String? = null
            val at = t.lastIndexOf('@')
            if (at >= 0) {
                user = t.substring(0, at).ifEmpty { null }
                t = t.substring(at + 1)
            }
            var port = 22
            if (t.startsWith("[")) {
                val end = t.indexOf(']')
                if (end < 0) return null
                val host = t.substring(1, end)
                val rest = t.substring(end + 1)
                if (rest.startsWith(":")) port = rest.drop(1).toIntOrNull() ?: return null
                return Triple(user, host, port)
            }
            val colon = t.lastIndexOf(':')
            if (colon >= 0 && t.count { it == ':' } == 1) {
                port = t.substring(colon + 1).toIntOrNull() ?: return null
                t = t.substring(0, colon)
            }
            if (t.isEmpty()) return null
            return Triple(user, t, port)
        }
    }
}

/** Builds the remote shell commands the app runs on a machine. */
object HerdrCommands {
    private val SEARCH = listOf(
        "\"\$h\"",
        "\"\$(command -v herdr 2>/dev/null)\"",
        "\"\$HOME/.local/bin/herdr\"",
        "/opt/homebrew/bin/herdr",
        "/usr/local/bin/herdr",
        "\"\$HOME/.nix-profile/bin/herdr\"",
        "\"/etc/profiles/per-user/\$USER/bin/herdr\"",
        "/run/current-system/sw/bin/herdr",
        "\"\$HOME/.local/share/mise/shims/herdr\"",
        "/home/linuxbrew/.linuxbrew/bin/herdr",
    )

    const val NOT_FOUND_EXIT = 127
    const val NOT_FOUND_MESSAGE = "herdr was not found on this machine"

    private val SCRIPT = "h=\"\$1\"; shift; for c in ${SEARCH.joinToString(" ")}; do " +
        "if [ -n \"\$c\" ] && [ -x \"\$c\" ]; then exec \"\$c\" \"\$@\"; fi; done; " +
        "echo '$NOT_FOUND_MESSAGE' >&2; exit $NOT_FOUND_EXIT"

    /** Runs herdr with [args], finding the binary even when the SSH shell has a minimal PATH. */
    fun herdr(machine: Machine, vararg args: String): String =
        "sh -c ${quote(SCRIPT)} herdr ${quote(machine.herdrPath ?: "")} " +
            (listOf("--session", machine.session) + args).joinToString(" ") { quote(it) }

    /** Like [herdr] but without selecting a session (for commands such as `session list`). */
    fun herdrNoSession(machine: Machine, vararg args: String): String =
        "sh -c ${quote(SCRIPT)} herdr ${quote(machine.herdrPath ?: "")} " + args.joinToString(" ") { quote(it) }

    /**
     * Lists every herdr executable on the machine, one `source<TAB>path<TAB>--version output` line
     * each, best first: [Machine.herdrPath], the running servers' own executables, `~/.local/bin`,
     * the login shell's PATH, SSH's PATH, then the usual install places. A host can have several
     * (an old distro package next to a self-updating install), and only the server's own version
     * is sure to speak its protocol; [HerdrBinaries.choose] picks one.
     */
    fun probe(machine: Machine): String = "sh -c ${quote(PROBE)} herdr ${quote(machine.herdrPath ?: "")}"

    private val PROBE = "h=\"\$1\"; seen=' '; " +
        "emit() { [ -n \"\$2\" ] && [ -f \"\$2\" ] && [ -x \"\$2\" ] || return 0; " +
        "case \"\$seen\" in *\" \$2 \"*) return 0;; esac; seen=\"\$seen\$2 \"; " +
        "v=\"\$(\"\$2\" --version </dev/null 2>/dev/null | head -n 1)\"; printf '%s\\t%s\\t%s\\n' \"\$1\" \"\$2\" \"\$v\"; }; " +
        "emit explicit \"\$h\"; " +
        // Global options (notably --session NAME) precede `server`. Anchor at the executable
        // and use [h] so pgrep doesn't discover the shell running this probe itself.
        "for p in \$(pgrep -u \"\$(id -u)\" -f '^(.*/)?[h]erdr[^/[:space:]]*([[:space:]]+[^[:space:]]+)*[[:space:]]+server([[:space:]]|$)' 2>/dev/null); do " +
        "if [ -d /proc/\$p ]; then e=\"\$(readlink /proc/\$p/exe 2>/dev/null)\"; e=\"\${e% (deleted)}\"; " +
        "else e=\"\$(lsof -a -p \$p -d txt -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1)\"; fi; " +
        "case \"\${e##*/}\" in herdr*) emit server \"\$e\";; esac; done; " +
        "emit home \"\$HOME/.local/bin/herdr\"; " +
        "emit login \"\$(\"\${SHELL:-/bin/sh}\" -lc 'command -v herdr' </dev/null 2>/dev/null | tail -n 1)\"; " +
        "emit path \"\$(command -v herdr 2>/dev/null)\"; " +
        "for c in ${SEARCH.drop(3).joinToString(" ")}; do emit other \"\$c\"; done; exit 0"

    fun apiBridge(machine: Machine) = herdr(machine, "remote-api-bridge")
    fun apiBridgeCheck(machine: Machine) = herdr(machine, "remote-api-bridge", "--check")
    fun sessionList(machine: Machine) = herdrNoSession(machine, "session", "list", "--json")
    fun machineList(machine: Machine) = herdrNoSession(machine, "machine", "list", "--json")

    fun machineAdd(machine: Machine, sshTarget: String, label: String?, session: String?): String {
        val args = mutableListOf("machine", "add")
        if (!label.isNullOrBlank()) args += listOf("--label", label)
        if (!session.isNullOrBlank()) args += listOf("--remote-session", session)
        args += sshTarget
        return herdrNoSession(machine, *args.toTypedArray())
    }

    fun machineRemove(machine: Machine, profileId: String) = herdrNoSession(machine, "machine", "remove", profileId)
    fun version(machine: Machine) = herdrNoSession(machine, "--version")
    fun channelShow(machine: Machine) = herdrNoSession(machine, "channel", "show")
    fun channelSet(machine: Machine, channel: String) = herdrNoSession(machine, "channel", "set", channel)
    fun update(machine: Machine, handoff: Boolean) =
        if (handoff) herdrNoSession(machine, "update", "--handoff") else herdrNoSession(machine, "update")

    fun terminalControl(machine: Machine, target: String, cols: Int, rows: Int, takeover: Boolean = true): String {
        val args = mutableListOf("terminal", "session", "control", target)
        if (takeover) args += "--takeover"
        args += listOf("--cols", cols.toString(), "--rows", rows.toString())
        return herdr(machine, *args.toTypedArray())
    }

    /**
     * Watches a pane without taking it over: the pane keeps the computer's size and nothing is
     * locked, so the desktop window draws on undisturbed. Typing goes through the API instead.
     */
    fun terminalObserve(machine: Machine, target: String, cols: Int, rows: Int): String =
        herdr(machine, "terminal", "session", "observe", target, "--cols", cols.toString(), "--rows", rows.toString())

    /** `ssh -G` prints the effective config for an alias, so the phone can resolve ~/.ssh/config names. */
    fun resolveSshAlias(alias: String) = "ssh -G ${quote(alias)}"

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

/** One herdr executable found on a machine by [HerdrCommands.probe]. */
data class HerdrBinary(val path: String, val version: String?, val source: String)

/** The herdr executable the app runs on a machine, and how it compares with the server's. */
data class HerdrChoice(val binary: HerdrBinary, val serverVersion: String?, val found: List<HerdrBinary>) {
    /** True when the binary is known to be a different version from the server it talks to. */
    val mismatch: Boolean get() = serverVersion != null && binary.version != serverVersion

    /** "/usr/bin/herdr (0.8.2)". */
    val describe: String get() = "${binary.path} (${binary.version ?: "unknown version"})"

    /** Says which binary runs and why it may not work, for a failure message. */
    fun explain(machineLabel: String): String = buildString {
        append("The app runs ").append(describe).append(" on ").append(machineLabel)
        if (serverVersion != null) append("; the herdr server there is ").append(serverVersion)
        append('.')
        if (mismatch) {
            val others = found.filter { it.path != binary.path }
            if (others.isNotEmpty()) append(" Also found: ").append(others.joinToString { "${it.path} (${it.version ?: "?"})" }).append('.')
            val match = others.firstOrNull { it.version == serverVersion }
            when {
                binary.source == "explicit" && match != null -> append(" Set this machine's herdr path to ${match.path}, or clear it.")
                binary.source == "explicit" -> append(" Point this machine's herdr path at the server's own binary, or clear it.")
                else -> append(" Update herdr there (herdr update), or set this machine's herdr path to the server's binary.")
            }
        }
    }
}

object HerdrBinaries {
    private val VERSION = Regex("""\d+\.\d+\.\d+[0-9A-Za-z.+-]*""")

    /** Reads [HerdrCommands.probe] output; shell noise around it is skipped. */
    fun parse(out: String): List<HerdrBinary> = out.lines().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size != 3 || !parts[1].startsWith("/")) return@mapNotNull null
        HerdrBinary(parts[1], VERSION.find(parts[2])?.value, parts[0])
    }.distinctBy { it.path }

    /**
     * The binary to run: the one set in the machine's settings; else the first whose version is the
     * server's ([serverVersion], once known); else the first found, which is the running server's
     * own executable when it could be seen, then `~/.local/bin/herdr`, then the login shell's.
     */
    fun choose(found: List<HerdrBinary>, serverVersion: String?): HerdrBinary? =
        found.firstOrNull { it.source == "explicit" }
            ?: serverVersion?.let { v -> found.firstOrNull { it.version == v } }
            ?: found.firstOrNull()
}
