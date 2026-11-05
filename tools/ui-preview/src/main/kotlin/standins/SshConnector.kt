// Stand-in for core/…/ssh/SshConnector.kt: the same API, but a "connection" reaches the preview's
// fake machine (preview/FakeMachine.kt) instead of SSH. Everything above it (HostSession, HerdrApi,
// the board, transcripts, terminals) is the real code.
package com.makefunstuff.herdr.core.ssh

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.PublicKey

/** Private key in OpenSSH PEM form plus its `authorized_keys` line. */
class SshIdentity(val privateKeyPem: String, val publicKeyLine: String)

sealed class ConnectError(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Auth(cause: Throwable?) :
        ConnectError("The machine refused the app's SSH key. Add the app's public key to ~/.ssh/authorized_keys there.", cause)

    class HostKeyChanged(val expected: String, val actual: String) :
        ConnectError("This machine's host key changed (expected $expected, got $actual). Re-approve it if you trust the change.")

    class Unreachable(target: String, cause: Throwable?) :
        ConnectError("Couldn't reach $target: ${cause?.message ?: "connection failed"}", cause)

    class HerdrMissing(detail: String) : ConnectError(detail)
}

/** A two-way byte stream to the host. */
interface Duplex : Closeable {
    val input: InputStream
    val output: OutputStream
}

/** A process started on the fake machine. */
class RemoteProcess(
    val stdin: OutputStream,
    val stdout: InputStream,
    val stderr: InputStream,
    private val onClose: () -> Unit,
) : Duplex {
    override val input: InputStream get() = stdout
    override val output: OutputStream get() = stdin
    override fun close() = onClose()
}

data class CommandResult(val exitStatus: Int?, val stdout: String, val stderr: String)

class MachineConnection(private val host: preview.FakeMachine, val hostKeyFingerprint: String) : Closeable {
    val isConnected: Boolean get() = true

    suspend fun start(command: String): RemoteProcess = host.start(command)

    suspend fun run(command: String, timeoutSeconds: Long = 30): CommandResult = host.run(command)

    suspend fun runWithInput(command: String, input: InputStream, timeoutSeconds: Long = 120): CommandResult =
        host.run(command, input.readBytes().decodeToString())

    suspend fun openUnixSocket(path: String): Duplex = host.socket()

    /** A port of the machine: nothing is forwarded, the fake machines have no servers behind their ports. */
    fun openTcp(host: String, port: Int): Duplex = throw IOException("Nothing listens on the fake machines' ports")

    fun forward(host: String, port: Int): PortForward = PortForward(host, port, preferred = 0) { h, p -> openTcp(h, p) }

    override fun close() {}
}

object SshConnector {
    suspend fun connect(
        machine: Machine,
        identity: SshIdentity,
        lookup: (String) -> Machine? = { null },
        depth: Int = 0,
    ): MachineConnection {
        machine.viaMachineId?.let { via -> connect(lookup(via) ?: throw ConnectError.Unreachable(machine.target, IOException("jump machine is missing")), identity, lookup, depth + 1) }
        val host = preview.FakeMachines.connect(machine)
        return MachineConnection(host, host.fingerprint)
    }

    /** The host key an address shows, as a scene set it ([preview.PreviewScanner.hostKeys]); other addresses don't answer. */
    suspend fun hostKey(host: String, port: Int, timeoutMs: Int = 6_000): String =
        preview.PreviewScanner.hostKeys[host] ?: throw ConnectError.Unreachable("$host:$port", IOException("No route to host"))

    fun fingerprint(key: PublicKey): String = "SHA256:preview"
}
