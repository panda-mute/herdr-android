package com.makefunstuff.herdr.core.logins

import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.ssh.Duplex
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import com.makefunstuff.herdr.core.ssh.HostPython
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable
import java.io.IOException

/**
 * herdr-logins (host/herdr-logins) on a machine: the app copies it there over SSH as it first reads the
 * machine's logins (and again when it carries a newer one) and runs it there. Each sign-in and sign-out
 * is that CLI's own command; a key or a code goes in on the command's stdin, never on a command line.
 */
object HerdrLogins {
    /** The script's `# version:` line; the app copies its own over one that says otherwise. */
    const val VERSION = "1"

    const val DIR = "\$HOME/.local/share/herdr-logins"
    const val FILE = "$DIR/herdr-logins"

    /** The script the app carries (core's resource, copied from host/herdr-logins/herdr-logins). */
    val script: String by lazy {
        HerdrLogins::class.java.getResourceAsStream("/herdr-logins")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("herdr-logins isn't bundled")
    }

    /** Writes stdin to the machine as herdr-logins, replacing an older one in one step. */
    fun installCommand(): String = "sh -c " + quote(
        "mkdir -p \"$DIR\" && cat > \"$FILE.new\" && chmod 755 \"$FILE.new\" && mv -f \"$FILE.new\" \"$FILE\"",
    )

    const val MISSING_EXIT = 126
    const val NO_PYTHON_EXIT = 127

    /** `herdr-logins <args>` by Python 3 ([HostPython]), as herdr-usage is run ([com.makefunstuff.herdr.core.usage.HerdrUsage.command]). */
    fun command(vararg args: String): String = "sh -c " + quote(
        "[ -f \"$FILE\" ] || exit $MISSING_EXIT; " +
            HostPython.find(NO_PYTHON_EXIT) + "; " +
            "exec \"\$P\" \$PA \"$FILE\" \"\$@\"",
    ) + " sh" + args.joinToString("") { " " + quote(it) }

    /**
     * herdr-logins on one machine: [run] runs a shell command with a timeout in seconds, [runWithInput]
     * one that reads a string on stdin, [start] one that keeps running with its streams open (a sign-in).
     */
    class Client(
        private val run: suspend (command: String, timeoutSeconds: Long) -> CommandResult,
        private val runWithInput: suspend (command: String, input: String, timeoutSeconds: Long) -> CommandResult,
        private val start: suspend (command: String) -> Duplex,
        private val label: String,
        private val allowInstall: Boolean = true,
    ) {
        /** Every CLI's login there, and the keys in its shell profiles. */
        suspend fun status(): LoginsReport {
            var r = run(command("status"), 90)
            if (r.exitStatus == MISSING_EXIT || (r.exitStatus != NO_PYTHON_EXIT && outdated(r))) {
                if (!allowInstall) throw IOException("Host scripts are off for $label")
                install()
                r = run(command("status"), 90)
            }
            if (r.exitStatus == NO_PYTHON_EXIT) throw IOException("Reading the logins needs Python 3 on $label, and it isn't installed")
            if (r.exitStatus == null && LoginsReader.answer(r.stdout) == null) throw IOException("Reading $label's logins took too long")
            if (LoginsReader.answer(r.stdout) == null) throw IOException(problem(r.stderr, "herdr-logins gave no answer on $label"))
            return LoginsReader.parse(r.stdout)
        }

        /**
         * Starts [action] for [cli] ([provider] for pi's and opencode's, and opencode's [credential] id
         * to sign out, so it opens no picker). The script there is the app's
         * version: [status] copied it when the screen opened.
         */
        suspend fun begin(cli: String, action: LoginAction, provider: String? = null, credential: String? = null): LoginRun =
            LoginRun(start(command(*listOfNotNull("run", cli, action.key, provider, credential?.takeIf { provider != null }).toTypedArray())))

        private fun outdated(r: CommandResult): Boolean {
            val v = (LoginsReader.answer(r.stdout)?.get("version") as? JsonPrimitive)?.content ?: return false
            return v != VERSION
        }

        suspend fun install() {
            val r = runWithInput(installCommand(), script, 30)
            if (r.exitStatus != 0) throw IOException(problem(r.stderr, "Couldn't copy herdr-logins to $label"))
        }

        private fun problem(stderr: String, fallback: String) = stderr.trim().lines().lastOrNull { it.isNotBlank() } ?: fallback
    }
}

/**
 * One sign-in or sign-out running on the machine: [events] as it says them, ending with
 * [LoginEvent.Done] (or a Done saying it stopped, when the connection went); [type] sends one line
 * (a code, a key, an answer) to the CLI's stdin. Nothing typed is kept: it goes out at once.
 */
class LoginRun internal constructor(private val stream: Duplex) : Closeable {
    val events: Flow<LoginEvent> = flow {
        var done = false
        val reader = stream.input.bufferedReader(Charsets.UTF_8)
        try {
            while (true) {
                val line = reader.readLine() ?: break
                val e = LoginsReader.event(line) ?: continue
                emit(e)
                if (e is LoginEvent.Done) { done = true; break }
            }
        } catch (e: IOException) {
            if (!done) emit(LoginEvent.Done(false, "The connection to the machine went: ${e.message ?: "closed"}", null))
            done = true
        }
        if (!done) emit(LoginEvent.Done(false, "It stopped without saying why", null))
    }.flowOn(Dispatchers.IO)

    suspend fun type(line: String) = withContext(Dispatchers.IO) {
        require('\n' !in line && '\r' !in line) { "one line" }
        stream.output.write((line + "\n").toByteArray(Charsets.UTF_8))
        stream.output.flush()
    }

    override fun close() {
        runCatching { stream.close() }
    }
}
