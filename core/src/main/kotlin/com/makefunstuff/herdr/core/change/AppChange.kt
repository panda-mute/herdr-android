package com.makefunstuff.herdr.core.change

import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.host.Attachments
import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException

/**
 * Changing the app from the phone: the person's words about a screen go to the herdr-android space's Assistant on
 * the machine that builds the app (its "builder"), which makes them a task there; once it is merged the Assistant
 * runs [HerdrAppBuild] there and the phone installs what it built. Nothing that edits or builds code runs here.
 */
object AppChange {
    /** The repository the app is built from, and the space that works in it. */
    const val REPO = "herdr-android"

    /** The screen a change is about: its bar's title and its route in the app's navigation. */
    data class Screen(val title: String, val route: String?)

    /** The herdr-android space among a machine's [spaces]: by its name, else by its folder's. */
    fun space(spaces: List<WorkSpace>): WorkSpace? =
        spaces.firstOrNull { it.name.equals(REPO, ignoreCase = true) && !it.home }
            ?: spaces.firstOrNull { folder(it)?.substringAfterLast('/')?.equals(REPO, ignoreCase = true) == true && !it.home }

    /** Where a space works, from its artifact store (`<machine>|<folder>`); null where it doesn't say. */
    fun folder(space: WorkSpace): String? = space.store.substringAfter('|', "").trim().takeIf { it.startsWith("/") || it.startsWith("~") }

    /** The first message: what to change, about which screen, in which build, with its [shot] (its path on the machine). */
    fun firstMessage(screen: Screen, versionName: String, versionCode: Long, words: String, shot: String?): String = buildString {
        // The screen as it is, named first as the composer names its pictures: the chat shows it as one.
        if (shot != null) append(Attachments.mention(shot)).append("\n\n")
        append("Change this screen of the app: ").append(words.trim()).append("\n\n")
        append("- Screen: ").append(screen.title.ifBlank { "untitled" })
        screen.route?.takeIf { it.isNotBlank() }?.let { append(" (route `").append(it).append("`)") }
        append('\n')
        append("- App: ").append(versionName).append(" (version code ").append(versionCode).append(")\n")
        append('\n')
        append("Make it a herdr-task on $REPO here: worktree, tests, and ui-preview shots of the screens it touches, before and after. ")
        append("Once I accept it and it is merged, the phone asks you to build its update.")

    }

    /**
     * Asks the Assistant to build the update, in its own terminal, where herdr-secret hands the build the release
     * key; [task] (merged) is what it brings.
     */
    fun buildMessage(task: String?, checkout: String?): String = buildString {
        append(if (task != null) "$task is merged. " else "")
        append("Build the phone's update from main here, in your terminal: it signs with the release key from herdr-secret, or fetches CI's release of the merge without one.\n\n")
        append("```\n").append(HerdrAppBuild.buildLine(checkout)).append("\n```\n\n")
        append("The phone picks the APK up when it is ready.")
    }

    /** Asks for [task]'s merge to be reverted as a new task, which ships as a new, higher build. */
    fun undoMessage(task: WorkTask): String = buildString {
        append("Undo the last change to the app, ").append(task.name)
        if (task.title.isNotBlank()) append(" (“").append(task.title.trim()).append("”)")
        append(": revert its merge on main as a new herdr-task on $REPO, with the same checks and ui-preview shots. ")
        append("Android won't install an older build, so the undo ships as a new, higher one, the same way.")
    }

    /** The newest of [accepted] (task ids, oldest first) that is merged now: the change Undo takes back. */
    fun lastChange(accepted: List<String>, tasks: List<WorkTask>): WorkTask? =
        accepted.asReversed().firstNotNullOfOrNull { id -> tasks.firstOrNull { (it.id == id || it.name == id) && it.status == WorkStatus.Done } }
}

/** What a machine has for building the app, as `herdr-app-build check` found it. */
data class BuilderCheck(
    val checkout: String? = null,
    /** origin on GitHub (`owner/name`), whose CI gives the version codes and the releases. */
    val repo: String? = null,
    val sdk: String? = null,
    /** The JDK's major version (17 or newer), when there is one. */
    val java: Int? = null,
    val key: Key = Key.None,
    val git: Boolean = true,
) {
    /** Where the release key is kept: in this machine's herdr-secret, the main machine's (asked when building), nowhere. */
    enum class Key { Here, Main, None }

    /** What the machine lacks to build the app, in words; empty when it can. */
    val missing: List<String>
        get() = listOfNotNull(
            "git".takeIf { !git },
            "a herdr-android checkout".takeIf { checkout == null },
            "the Android SDK".takeIf { sdk == null },
            "a JDK 17 or newer".takeIf { java == null },
        )

    val ok: Boolean get() = missing.isEmpty()

    /** Updates are signed there with the release key, else they come from CI's release. */
    val signsHere: Boolean get() = key != Key.None

    companion object {
        fun parse(json: JsonObject): BuilderCheck = BuilderCheck(
            checkout = json.str("checkout"),
            repo = json.str("repo"),
            sdk = json.str("sdk"),
            java = (json["java"] as? JsonPrimitive)?.intOrNull,
            key = when (json.str("key")) {
                "here" -> Key.Here
                "main" -> Key.Main
                else -> Key.None
            },
            git = (json["git"] as? JsonPrimitive)?.booleanOrNull ?: true,
        )
    }
}

/** The machine's last build of the app, as `herdr-app-build state` keeps it. */
data class BuildState(
    val phase: Phase = Phase.None,
    val sha: String? = null,
    /** The merge's subject line. */
    val subject: String? = null,
    /** The version code, CI's run number for the merge. */
    val number: Long? = null,
    /** Built there with the key ("host"), or CI's release fetched ("release"). */
    val source: String? = null,
    val apk: String? = null,
    val size: Long? = null,
    val sha256: String? = null,
    val error: String? = null,
    val at: Long? = null,
) {
    enum class Phase { None, Fetching, WaitingCi, Building, Downloading, Ready, Failed }

    val versionName: String? get() = number?.let { "0.1.$it" }
    val running: Boolean get() = phase in setOf(Phase.Fetching, Phase.WaitingCi, Phase.Building, Phase.Downloading)

    /** What it is at, as one line ("Building 0.1.131"). */
    val words: String
        get() = when (phase) {
            Phase.None -> "No build yet"
            Phase.Fetching -> "Fetching main"
            Phase.WaitingCi -> "Waiting for CI's version number"
            Phase.Building -> "Building ${versionName ?: "the update"}"
            Phase.Downloading -> "Waiting for CI's release ${versionName ?: ""}".trimEnd()
            Phase.Ready -> "${versionName ?: "The update"} is built"
            Phase.Failed -> "The build failed" + (error?.let { ": $it" } ?: "")
        }

    companion object {
        fun parse(json: JsonObject): BuildState = BuildState(
            phase = when (json.str("state")) {
                "fetching" -> Phase.Fetching
                "waiting-ci" -> Phase.WaitingCi
                "building" -> Phase.Building
                "downloading" -> Phase.Downloading
                "ready" -> Phase.Ready
                "failed" -> Phase.Failed
                else -> Phase.None
            },
            sha = json.str("sha"),
            subject = json.str("subject"),
            number = (json["number"] as? JsonPrimitive)?.longOrNull,
            source = json.str("source"),
            apk = json.str("apk"),
            size = (json["size"] as? JsonPrimitive)?.longOrNull,
            sha256 = json.str("sha256"),
            error = json.str("error"),
            at = (json["at"] as? JsonPrimitive)?.longOrNull,
        )
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

/**
 * herdr-app-build (host/herdr-app-build) on the builder: the app copies it there over SSH, as herdr-usage, and runs
 * its `check` and `state`; `build` runs in the Assistant's terminal ([AppChange.buildMessage]).
 */
object HerdrAppBuild {
    /** The script's `# version:` line; the app copies its own over one that says otherwise. */
    const val VERSION = "1"

    const val DIR = "\$HOME/.local/share/herdr-android"
    const val FILE = "$DIR/herdr-app-build"

    /** The script the app carries (core's resource, copied from host/herdr-app-build/herdr-app-build). */
    val script: String by lazy {
        HerdrAppBuild::class.java.getResourceAsStream("/herdr-app-build")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("herdr-app-build isn't bundled")
    }

    /** Writes stdin to the machine as herdr-app-build, replacing an older one in one step. */
    fun installCommand(): String = "sh -c " + quote(
        "mkdir -p \"$DIR\" && cat > \"$FILE.new\" && chmod 755 \"$FILE.new\" && mv -f \"$FILE.new\" \"$FILE\"",
    )

    const val MISSING_EXIT = 126
    const val NO_PYTHON_EXIT = 127

    /** `herdr-app-build <args>` by python3. Exits [MISSING_EXIT] before it is there, [NO_PYTHON_EXIT] without python3. */
    fun command(vararg args: String): String = "sh -c " + quote(
        "[ -f \"$FILE\" ] || exit $MISSING_EXIT; " +
            "P=\"\$(command -v python3 2>/dev/null)\"; " +
            "[ -n \"\$P\" ] || P=\"\$(\"\${SHELL:-/bin/sh}\" -lc 'command -v python3' </dev/null 2>/dev/null | tail -n 1)\"; " +
            "[ -n \"\$P\" ] || { echo 'python3 is not installed' >&2; exit $NO_PYTHON_EXIT; }; " +
            "exec \"\$P\" \"$FILE\" \"\$@\"",
    ) + " sh" + args.joinToString("") { " " + quote(it) }

    /** The line the Assistant runs in its terminal to build. */
    fun buildLine(checkout: String?): String =
        "python3 ~/.local/share/herdr-android/herdr-app-build build" + (checkout?.let { " --checkout " + quote(it) } ?: "")

    /** Prints the APK at [path] (the state's), for the phone to copy over SSH. */
    fun fetchCommand(path: String): String = "cat -- " + quote(path)

    /** The one JSON line an answer is, or null. */
    fun answer(stdout: String): JsonObject? = stdout.lineSequence().map { it.trim() }.filter { it.startsWith("{") }
        .mapNotNull { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        .lastOrNull { it.containsKey("herdrAppBuild") }

    /** herdr-app-build on one machine: [run] runs a shell command with a timeout in seconds, [runWithInput] one fed a string. */
    class Client(
        private val run: suspend (command: String, timeoutSeconds: Long) -> CommandResult,
        private val runWithInput: suspend (command: String, input: String, timeoutSeconds: Long) -> CommandResult,
        private val label: String,
        private val allowInstall: Boolean = true,
    ) {
        /** What the machine has for building the app; [checkout] is where the herdr-android space works, when known. */
        suspend fun check(checkout: String?): BuilderCheck =
            BuilderCheck.parse(ask(listOf("check") + (checkout?.let { listOf("--checkout", it) } ?: emptyList())))

        suspend fun state(): BuildState = BuildState.parse(ask(listOf("state")))

        private suspend fun ask(args: List<String>): JsonObject {
            var r = run(command(*args.toTypedArray()), 60)
            if (r.exitStatus == MISSING_EXIT || (r.exitStatus != NO_PYTHON_EXIT && outdated(r))) {
                if (!allowInstall) throw IOException("Host scripts are off for $label, so the app can't look there")
                val i = runWithInput(installCommand(), script, 30)
                if (i.exitStatus != 0) throw IOException(problem(i.stderr, "Couldn't copy herdr-app-build to $label"))
                r = run(command(*args.toTypedArray()), 60)
            }
            if (r.exitStatus == NO_PYTHON_EXIT) throw IOException("Building the app needs python3 on $label, and it isn't installed")
            return answer(r.stdout) ?: throw IOException(problem(r.stderr, "herdr-app-build gave no answer on $label"))
        }

        private fun outdated(r: CommandResult): Boolean {
            val v = (answer(r.stdout)?.get("version") as? JsonPrimitive)?.contentOrNull ?: return false
            return v != VERSION
        }

        private fun problem(stderr: String, fallback: String) = stderr.trim().lines().lastOrNull { it.isNotBlank() }?.removePrefix("herdr-app-build: ") ?: fallback
    }
}
