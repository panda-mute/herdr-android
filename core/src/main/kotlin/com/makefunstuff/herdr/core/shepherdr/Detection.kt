package com.makefunstuff.herdr.core.shepherdr

import com.makefunstuff.herdr.core.board.BoardApi
import com.makefunstuff.herdr.core.ssh.MachineConnection
import com.makefunstuff.herdr.core.ssh.PortForward
import kotlinx.coroutines.CancellationException

/** Which board a machine runs: Shepherdr 3 when its service answers with 3.x or later, else herdr-projects 1.x's launcher. */
object BoardDetection {
    /** The service's port on the machine's 127.0.0.1 (`SHEPHERDR_PORT`'s default, `service/paths.ts`). */
    const val PORT = 7788

    /** The oldest Shepherdr the app speaks to: 3.0's service, which 3.1's web app runs on. */
    const val MIN_MAJOR = 3

    /**
     * Prints the service's token, which a local caller shows it (`service/auth.ts`): `~/.herdr/shepherdr/token`, or
     * under `SHEPHERDR_DATA` where the service was told to keep its files elsewhere. Nothing where there is none.
     */
    const val TOKEN_COMMAND = "cat \"\${SHEPHERDR_DATA:-\$HOME/.herdr/shepherdr}/token\" 2>/dev/null; true"

    /** Shepherdr wins when both answer: the v1 launcher may be an old one left on disk. */
    fun decide(v3: V2Version?, v1Path: String?): BoardApi = when {
        v3 != null && v3.major >= MIN_MAJOR -> BoardApi.V2(v3.version)
        v1Path != null -> BoardApi.V1(v1Path)
        else -> BoardApi.None
    }

    /** What answers on [transport] (`/api/health`), or null when nothing (or not Shepherdr 3) does. */
    suspend fun probe(transport: ShepherdrTransport): V2Version? = try {
        ShepherdrClient(transport).version().takeIf { it.major >= MIN_MAJOR }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** The token [TOKEN_COMMAND] printed: its one line, or null for none. */
    fun token(out: String): String? = out.trim().lines().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }

    /**
     * Reaches Shepherdr's service on [conn]'s machine: through an SSH port forward to its 127.0.0.1:[PORT] with its
     * token (its live link relayed by a command there, [LiveRelay]), or, where the SSH server refuses to forward,
     * `curl` there. The transport that answered with its version, which the caller keeps (one per machine) and
     * closes with the connection; null where no Shepherdr 3 answers.
     */
    suspend fun open(conn: MachineConnection): Pair<ShepherdrTransport, V2Version>? {
        val token = runCatching { token(conn.run(TOKEN_COMMAND, 15).stdout) }.getOrNull()
        // Any free port on the phone: the forward is only ever reached by the app itself.
        val forward = runCatching { ForwardTransport.over(PortForward("127.0.0.1", PORT, preferred = 0) { h, p -> conn.openTcp(h, p) }, token, relay = { conn.start(it) }) }.getOrNull()
        if (forward != null) {
            val version = try {
                probe(forward)
            } catch (e: CancellationException) {
                forward.close()
                throw e
            }
            version?.let { return forward to it }
            forward.close()
        }
        val curl = CurlTransport { command, stdin, seconds -> conn.runWithInput(command, CurlTransport.stdin(stdin), seconds) }
        return probe(curl)?.let { curl to it }
    }
}
