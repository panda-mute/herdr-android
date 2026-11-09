package com.makefunstuff.herdr.core.hc

import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/**
 * Starting a terminal on herdr-core from the phone (`terminal.run`, as `hc run --bg`): a shell or an
 * agent, in a space's folder, labelled into that space as the desktop app labels its own terminals
 * (`herdr.space`, a tab of its own, `where`), so the desktop app and the app's [HcMapping] both put it
 * there. A terminal started outside any space lands in Other terminals.
 */
object HcStart {
    /**
     * Where a terminal for the space [workspaceId] ([HcMapping]'s `hc:sp…` id; null for none) is started:
     * the herdr-core [session] it is in, the [machine] hc added it runs on (`on`), and its labels.
     */
    data class Place(val session: String?, val machine: String?, val labels: Map<String, String>)

    /**
     * [workspaceId]'s place for a new tab called [tabName] ([tabId]: a new tab's id, as the desktop app
     * makes them). Null when the id isn't herdr-core's.
     */
    fun place(workspaceId: String, spaceName: String, tabName: String, tabId: String = newTabId()): Place? {
        if (!workspaceId.startsWith(HcMapping.PREFIX)) return null
        val session = HcMapping.session(workspaceId)
        var rest = workspaceId.removePrefix(HcMapping.scope(session))
        if (rest.startsWith(OTHER)) {
            // Other terminals (`!other`, `!other@rohan`): none of the desktop app's spaces, so no labels.
            return Place(session, rest.removePrefix(OTHER).removePrefix("@").ifEmpty { null }, emptyMap())
        }
        // A space on a machine hc added is `rohan/sp…`; a space known only by its name is `where:<name>` (which may hold a `/`).
        var machine: String? = null
        if (!rest.startsWith(WHERE) && '/' in rest) {
            machine = rest.substringBefore('/')
            rest = rest.substringAfter('/')
        }
        val where = "$spaceName / $tabName"
        val labels = if (rest.startsWith(WHERE)) mapOf("herdr.tab" to tabId, "where" to "${rest.removePrefix(WHERE)} / $tabName")
        else mapOf("herdr.space" to rest, "herdr.tab" to tabId, "where" to where)
        return Place(session, machine, labels)
    }

    /** A new tab's id as the desktop app writes them: `tb` and 32 hex digits. */
    fun newTabId(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "tb" + bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * The desktop app's space on [snapshot] whose terminals sit in [folder] (its first terminal's folder), so an
     * agent started there opens in it; null when none does. Only the default session's: another session's core is
     * another program's (Shepherdr's), and Other terminals is no space.
     */
    fun spaceIn(snapshot: SessionSnapshot?, folder: String): String? {
        val f = folder.trimEnd('/')
        fun desktops(id: String) = id.startsWith(HcMapping.PREFIX) && HcMapping.session(id) == null && !id.removePrefix(HcMapping.PREFIX).startsWith(OTHER)
        return snapshot?.workspaces?.filter { desktops(it.workspaceId) }?.firstOrNull { ws ->
            // A space on a machine hc added (`rohan/t7`) has that machine's folders, not this one's.
            snapshot.panes.firstOrNull { it.workspaceId == ws.workspaceId }?.takeIf { '/' !in it.terminalId }?.cwd?.trimEnd('/') == f
        }?.workspaceId
    }

    /**
     * The line that starts [program] through the machine's login shell, as the app's probe found it
     * ([com.makefunstuff.herdr.core.start.StartWork.probeCommand]): a desktop app's core may have a
     * shorter `PATH` than the person's shell. `exec` leaves the agent itself in the terminal.
     */
    // POSIX only: hc runs a line with cmd.exe on Windows, so [launch] gives the program as argv there.
    fun programLine(program: String): String = "exec \"\${SHELL:-/bin/sh}\" -lc " + quote("exec " + quote(program))

    /** A name hc takes (1-32 of a-z 0-9 - _), or null. */
    fun hcName(name: String): String? =
        name.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').take(32).trimEnd('-').ifEmpty { null }

    /** Starts a shell in [place] in [cwd]. Returns its pane id. */
    suspend fun shell(client: HcClient, place: Place, cwd: String?): String {
        val t = client.run(cwd = cwd?.ifBlank { null }, labels = place.labels, on = place.machine, env = ENV)
        return HcMapping.paneId(t.address, place.session)
    }

    /**
     * Starts [program] as [name] in [folder] in [place]: its pane id, as soon as it runs. Through the login shell
     * ([programLine]), except on a [windows] machine (Git Bash's), where hc runs a line with cmd.exe: there the
     * program itself (`argv`), as the core's `PATH` finds it.
     */
    suspend fun launch(client: HcClient, place: Place, folder: String, program: String, name: String, windows: Boolean = false): String {
        val line = programLine(program).takeIf { !windows }
        val argv = if (windows) listOf(program) else emptyList()
        val t = try {
            client.run(line, folder, place.labels, hcName(name), place.machine, ENV, argv)
        } catch (e: HcException) {
            // Another live terminal has the name: hc numbers none for us, so it goes without one.
            if (e.kind != "name_taken") throw e
            client.run(line, folder, place.labels, null, place.machine, ENV, argv)
        }
        return HcMapping.paneId(t.address, place.session)
    }

    /** `uname -s` names a Windows machine (Git Bash, MSYS, Cygwin), as the health page reads it. */
    fun windows(uname: String): Boolean = listOf("MINGW", "MSYS", "CYGWIN").any { uname.trim().startsWith(it) }

    /**
     * Gives the agent in [paneId] [text] once it reports that it waits (OSC 7501 `idle` or `done`). Nothing is typed
     * blind: an agent that says nothing within [readyMs] (most kinds don't report), asks something first (`blocked`,
     * such as whether to trust the folder), reports an error or ends gets no Enter it could take as an answer.
     * [wanted] says whether the words are still to go (the person may have sent them, or others, or discarded them
     * meanwhile): it is asked while waiting. [claim] takes them just before sending, at once, so no one else sends them
     * too; false: they went another way. Returns why the words didn't go in, null when they did or are no longer wanted.
     */
    suspend fun deliver(
        client: HcClient, paneId: String, text: String, readyMs: Long = 20_000,
        wanted: suspend () -> Boolean = { true }, claim: suspend () -> Boolean = wanted,
    ): String? {
        val address = HcMapping.address(paneId) ?: return "it isn't herdr-core's"
        // Why not to type now, or READY.
        val wait: String = withTimeoutOrNull(readyMs) {
            var answer: String? = null
            while (answer == null) {
                val now = try { client.get(address) } catch (e: java.io.IOException) { null }
                answer = when {
                    !wanted() -> TAKEN
                    now != null && !now.live -> "it ended"
                    now?.status?.state in setOf("idle", "done") -> READY
                    now?.status?.state == "blocked" -> "it asks something first"
                    now?.status?.state == "error" -> "it reports an error"
                    else -> null
                }
                if (answer == null) delay(500)
            }
            answer
        } ?: "it didn't say it was ready"
        if (wait == TAKEN || (wait == READY && !claim())) return null
        if (wait != READY) return wait
        return try { client.send(address, text.trim()); null } catch (e: java.io.IOException) { e.message ?: "it didn't take them" }
    }

    /**
     * The terminal the program is told it runs in: hc's core may have no `TERM` of its own (a desktop app's), and
     * the phone draws what it writes with an xterm-like emulator in colour.
     */
    val ENV = mapOf("TERM" to "xterm-256color", "COLORTERM" to "truecolor")

    private const val READY = "ready"
    private const val TAKEN = "taken"
    private const val OTHER = "!other"
    private const val WHERE = "where:"
}
