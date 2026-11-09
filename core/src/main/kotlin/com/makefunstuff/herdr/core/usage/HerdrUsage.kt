package com.makefunstuff.herdr.core.usage

import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import com.makefunstuff.herdr.core.ssh.HostPython
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

/**
 * herdr-usage (host/herdr-usage) on a machine: the app copies it there over SSH as it first reads the
 * machine's usage (and again when it carries a newer one), and runs it there, so each provider is asked
 * with the keys and logins already on that machine; none of them comes to the phone.
 */
object HerdrUsage {
    /** The script's `# version:` line; the app copies its own over one that says otherwise. */
    const val VERSION = "1"

    const val DIR = "\$HOME/.local/share/herdr-usage"
    const val FILE = "$DIR/herdr-usage"

    /** The script the app carries (core's resource, copied from host/herdr-usage/herdr-usage). */
    val script: String by lazy {
        HerdrUsage::class.java.getResourceAsStream("/herdr-usage")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("herdr-usage isn't bundled")
    }

    /** Writes stdin to the machine as herdr-usage, replacing an older one in one step. */
    fun installCommand(): String = "sh -c " + quote(
        "mkdir -p \"$DIR\" && cat > \"$FILE.new\" && chmod 755 \"$FILE.new\" && mv -f \"$FILE.new\" \"$FILE\"",
    )

    const val MISSING_EXIT = 126
    const val NO_PYTHON_EXIT = 127

    /**
     * `herdr-usage <args>` by Python 3 ([HostPython]). Exits [MISSING_EXIT] before it is there,
     * [NO_PYTHON_EXIT] without Python 3.
     */
    fun command(vararg args: String): String = "sh -c " + quote(
        "[ -f \"$FILE\" ] || exit $MISSING_EXIT; " +
            HostPython.find(NO_PYTHON_EXIT) + "; " +
            "exec \"\$P\" \$PA \"$FILE\" \"\$@\"",
    ) + " sh" + args.joinToString("") { " " + quote(it) }

    /**
     * herdr-usage on one machine: [run] runs a shell command with a timeout in seconds, [runWithInput]
     * one that reads a string on stdin. [label] names the machine in what goes wrong.
     */
    class Client(
        private val run: suspend (command: String, timeoutSeconds: Long) -> CommandResult,
        private val runWithInput: suspend (command: String, input: String, timeoutSeconds: Long) -> CommandResult,
        private val label: String,
        private val allowInstall: Boolean = true,
    ) {
        /** Everything: limits, spend, and what the agents used this week. [fresh] asks the providers again now. */
        suspend fun read(fresh: Boolean = false): UsageReport = ask(listOfNotNull("read", "--fresh".takeIf { fresh }), 150)

        /** The limits alone (no session records read): the app's own look while closed. */
        suspend fun limits(): UsageReport = ask(listOf("limits"), 60)

        private suspend fun ask(args: List<String>, timeout: Long): UsageReport {
            var r = run(command(*args.toTypedArray()), timeout)
            if (r.exitStatus == MISSING_EXIT || (r.exitStatus != NO_PYTHON_EXIT && outdated(r))) {
                if (!allowInstall) return UsageReport(at = 0L)
                install()
                r = run(command(*args.toTypedArray()), timeout)
            }
            if (r.exitStatus == NO_PYTHON_EXIT && UsageReader.answer(r.stdout) == null) {
                throw IOException("Reading usage needs Python 3 on $label, and it isn't installed")
            }
            if (r.exitStatus == null && UsageReader.answer(r.stdout) == null) throw IOException("Reading $label's usage took too long")
            if (UsageReader.answer(r.stdout) == null) throw IOException(problem(r.stderr, "herdr-usage gave no answer on $label"))
            return UsageReader.parse(r.stdout)
        }

        /** The machine's copy is another version than the app's (an answer says which). */
        private fun outdated(r: CommandResult): Boolean {
            val v = (UsageReader.answer(r.stdout)?.get("version") as? JsonPrimitive)?.content ?: return false
            return v != VERSION
        }

        suspend fun install() {
            val r = runWithInput(installCommand(), script, 30)
            if (r.exitStatus != 0) throw IOException(problem(r.stderr, "Couldn't copy herdr-usage to $label"))
        }

        private fun problem(stderr: String, fallback: String) = stderr.trim().lines().lastOrNull { it.isNotBlank() } ?: fallback
    }
}
