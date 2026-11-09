package com.makefunstuff.herdr.core.push

import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import com.makefunstuff.herdr.core.ssh.HostPython
import com.makefunstuff.herdr.core.ssh.MachineConnection
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.Base64

/**
 * herdr-push (host/herdr-push) on a machine: the app copies it there over SSH, subscribes this phone
 * and starts it, so the machine can tell the phone about its agents while the app is closed.
 */
object HerdrPush {
    /** Where the app puts it on a machine. */
    const val DIR = "\$HOME/.local/share/herdr-push"
    const val FILE = "$DIR/herdr-push"

    /** The watcher the app carries (core's resource, copied from host/herdr-push/herdr-push). */
    val script: String by lazy {
        HerdrPush::class.java.getResourceAsStream("/herdr-push")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("herdr-push isn't bundled")
    }

    /** The `# version: N` line near the top of a herdr-push script. */
    fun versionOf(script: String): String =
        Regex("^# version: (\\S+)$", RegexOption.MULTILINE).find(script.lineSequence().take(5).joinToString("\n"))?.groupValues?.get(1).orEmpty()

    val version: String by lazy { versionOf(script) }

    /** Writes stdin to the machine as herdr-push, replacing an older one in one step. */
    fun installCommand(): String = "sh -c " + quote(
        "mkdir -p \"$DIR\" && cat > \"$FILE.new\" && chmod 755 \"$FILE.new\" && mv -f \"$FILE.new\" \"$FILE\"",
    )

    const val NO_PYTHON_EXIT = 127

    /**
     * `herdr-push <args>`, run by Python 3 ([HostPython]). Exits [NO_PYTHON_EXIT] without Python 3,
     * [MISSING_EXIT] before herdr-push is there.
     */
    fun command(vararg args: String): String = "sh -c " + quote(
        "[ -f \"$FILE\" ] || exit $MISSING_EXIT; " +
            HostPython.find(NO_PYTHON_EXIT) + "; " +
            "exec \"\$P\" \$PA \"$FILE\" \"\$@\"",
    ) + " sh" + args.joinToString("") { " " + quote(it) }

    const val MISSING_EXIT = 126

    /**
     * This phone's subscription for one machine in the app. Per machine, not per phone: two machines in
     * the app can be one host (two herdr sessions, or the same host by two names), each with its own
     * session and key, and one subscription mustn't replace the other.
     */
    fun subscriberId(phoneId: String, machineId: String): String = "$phoneId:$machineId"

    /** What `herdr-push subscribe` reads: this subscription ([subscriberId]), its push address and its key for this machine. */
    fun subscription(
        id: String,
        phoneName: String,
        endpoint: String,
        key: ByteArray,
        session: String,
        herdrPath: String?,
        live: Boolean = false,
        /** Agents (by pane) herdr-push tells each change of (the app's one-shot watches did; it asks for none now). */
        watchPanes: Collection<String> = emptyList(),
    ): String = buildJsonObject {
        put("id", id)
        put("name", phoneName)
        put("endpoint", endpoint)
        put("key", Base64.getEncoder().encodeToString(key))
        put("session", session.ifEmpty { "default" })
        herdrPath?.takeIf { it.isNotBlank() }?.let { put("herdr", it) }
        // Live Updates: also what is running, as it changes.
        if (live) put("live", true)
        if (watchPanes.isNotEmpty()) put("watch_panes", kotlinx.serialization.json.JsonArray(watchPanes.sorted().map(::JsonPrimitive)))
    }.toString()

    /** herdr-push's answer: its last JSON line with an `ok`. */
    fun answer(stdout: String): JsonObject? = stdout.lines().asReversed().firstNotNullOfOrNull { l ->
        l.trim().takeIf { it.startsWith("{") }?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.takeIf { it["ok"] != null }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** What `status` says about this phone and the watcher. */
    data class Status(val version: String, val running: Boolean, val autostart: String, val subscribed: Boolean, val error: String?, val gone: Boolean)

    /** How the watcher stands for subscription [id] ([subscriberId]). */
    fun status(o: JsonObject, id: String): Status {
        val phone = (o["phones"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }.firstOrNull { it.str("id") == id }
        return Status(
            version = o.str("version"),
            running = (o["running"] as? JsonPrimitive)?.booleanOrNull == true,
            autostart = o.str("autostart"),
            subscribed = phone != null,
            error = phone?.str("error")?.ifEmpty { null },
            gone = (phone?.get("gone") as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    /** herdr-push on one machine, over the app's SSH connection. */
    class Client(private val conn: MachineConnection, private val label: String) {
        /** Copies the app's herdr-push to the machine. */
        suspend fun install() {
            val r = conn.runWithInput(installCommand(), script.byteInputStream(), timeoutSeconds = 30)
            if (r.exitStatus != 0) throw IOException(problem(r.stderr, "Couldn't copy herdr-push to $label"))
        }

        /** Runs herdr-push; its JSON answer, or the reason it failed. [MissingException] before it is installed. */
        suspend fun run(vararg args: String, input: String? = null, timeoutSeconds: Long = 60): JsonObject {
            val r = if (input != null) conn.runWithInput(command(*args), input.byteInputStream(), timeoutSeconds)
            else conn.run(command(*args), timeoutSeconds)
            if (r.exitStatus == MISSING_EXIT) throw MissingException()
            if (r.exitStatus == NO_PYTHON_EXIT && answer(r.stdout) == null) {
                throw IOException("herdr-push needs Python 3 on $label, and it isn't installed")
            }
            val o = answer(r.stdout) ?: throw IOException(problem(r.stderr, "herdr-push gave no answer on $label"))
            if ((o["ok"] as? JsonPrimitive)?.booleanOrNull != true) throw IOException(o.str("msg").ifEmpty { "herdr-push refused on $label" })
            return o
        }

        /** Whether someone is at the machine now ([Presence]); null from a herdr-push that can't tell (older, or none). */
        suspend fun presence(): Presence? = runCatching { PushMessages.presence(run("presence", timeoutSeconds = 20)) }
            .getOrElse { if (it is CancellationException) throw it; null }

        private fun problem(stderr: String, fallback: String) = stderr.trim().lines().lastOrNull { it.isNotBlank() } ?: fallback
    }

    class MissingException : IOException("herdr-push isn't installed")
}

/** The app's own look at a machine while it is closed: what herdr-push would have sent about it. */
object PushLook {

    /**
     * Every agent of [snapshot], as events; the notifications sort out what is new. A blocked agent's
     * screen is read with [screen] (classic herdr's pane, or herdr-core's terminal).
     */
    suspend fun events(screen: suspend (paneId: String) -> String, snapshot: SessionSnapshot): List<PushEvent> =
        snapshot.agents.map { a ->
            val screen = if (a.agentStatus == AgentStatus.Blocked) {
                try {
                    screen(a.paneId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
            } else null
            PushEvent.Agent(a, snapshot.location(a.workspaceId, a.tabId), screen)
        }
}
