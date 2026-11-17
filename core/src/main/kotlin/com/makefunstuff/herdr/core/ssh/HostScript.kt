package com.makefunstuff.herdr.core.ssh

import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote

/**
 * The rule for the helper scripts the app copies to a machine (herdr-push, herdr-usage, herdr-logins,
 * herdr-app-build): it copies its own over an older one, never over a newer one. Another phone with a
 * newer build may have put the newer one there, and copying back would undo its fixes.
 */
object HostScript {
    /**
     * Whether the machine's [machine] version is older than the app's [app], compared as integers. A
     * missing or unparsable one counts as older (a script from before versions, or a broken one).
     */
    fun older(machine: String?, app: String): Boolean {
        val have = machine?.trim()?.toLongOrNull() ?: return true
        return have < number(app)
    }

    /** The app's own version, which must be a number (a bundled script without one is a build mistake). */
    private fun number(app: String): Long = requireNotNull(app.trim().toLongOrNull()) { "A helper script's version must be a number, not \"$app\"" }

    /**
     * Writes stdin to [file] in [dir] in one step, unless the file there says (in its `# version: N`
     * line near the top) it is newer than [version]: then stdin is read and dropped and the file stays.
     * Either way it exits 0 (keeping a newer one is a successful install; it then prints `kept version N`,
     * which only a person reading by hand needs). The check is on the machine, so an install never
     * downgrades, whoever asks.
     */
    fun installCommand(dir: String, file: String, version: String): String {
        val n = number(version)
        return "sh -c " + quote(
            "mkdir -p \"$dir\" || exit 1; " +
                "v=\$(head -n 5 \"$file\" 2>/dev/null | sed -n 's/^# version: *\\([0-9][0-9]*\\) *\$/\\1/p' | head -n 1); " +
                "if [ -n \"\$v\" ] && [ \"\$v\" -gt $n ] 2>/dev/null; then cat > /dev/null; echo \"kept version \$v\"; exit 0; fi; " +
                "cat > \"$file.new\" && chmod 755 \"$file.new\" && mv -f \"$file.new\" \"$file\"",
        )
    }
}
