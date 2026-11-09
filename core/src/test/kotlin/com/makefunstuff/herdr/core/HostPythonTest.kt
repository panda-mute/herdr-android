package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.ssh.HostPython
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Python the host scripts run with: the first one that runs, not a Store alias or a Python 2. */
class HostPythonTest {
    private class Ran(val exit: Int, val out: String, val err: String)

    /**
     * Runs [HostPython.find] in a shell whose PATH holds [fakes] (name to script body, each a `#!/bin/sh`
     * file) and a `tail`, and nothing else, so no real python of the machine the test runs on is found.
     * [shell] is the login shell's script, when it should find one.
     */
    private fun run(fakes: Map<String, String>, noPythonExit: Int?, shell: String? = null): Ran {
        val dir = Files.createTempDirectory("host-python").toFile()
        try {
            val bin = File(dir, "bin").also { it.mkdirs() }
            fakes.forEach { (name, body) -> File(bin, name).apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) } }
            val tools = File(dir, "tools").also { it.mkdirs() }
            File(tools, "tail").apply {
                writeText("#!/bin/sh\nfor t in /usr/bin/tail /bin/tail; do [ -x \"\$t\" ] && exec \"\$t\" \"\$@\"; done\nexit 127\n")
                setExecutable(true)
            }
            File(dir, "login").mkdirs()
            File(dir, "login/python3").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
            File(dir, "shell").apply { writeText("#!/bin/sh\n${shell ?: "exit 1"}\n"); setExecutable(true) }
            // The file is run from [dir], so $PWD is the directory as sh sees it (not as Java names it on Windows).
            File(dir, "run.sh").writeText(
                "PATH=\"\$PWD/bin:\$PWD/tools\"; SHELL=\"\$PWD/shell\"; export PATH SHELL\n" +
                    HostPython.find(noPythonExit) + "; echo \"P=\$P PA=\$PA\"\n",
            )
            val p = ProcessBuilder("sh", "run.sh").directory(dir).start()
            p.outputStream.close()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
            assertTrue(p.waitFor(60, TimeUnit.SECONDS))
            return Ran(p.exitValue(), out.trim(), err.trim())
        } finally {
            dir.deleteRecursively()
        }
    }

    private val works = "exit 0"

    @Test
    fun skipsAStorePython3ForPython() {
        // On Windows, python3 is often the Microsoft Store's alias: it prints a hint and exits 9009.
        val r = run(mapOf("python3" to "echo 'no se encontró Python' >&2; exit 9009", "python" to works), 127)
        assertEquals(0, r.exit, r.err)
        assertTrue(r.out.endsWith("/bin/python PA="), r.out)
    }

    @Test
    fun skipsAPython2() {
        // An old Linux's python is Python 2, and `py` needs -3 to be Python 3.
        val r = run(mapOf("python" to "exit 1", "py" to "[ \"\$1\" = -3 ] && exit 0; exit 1"), 127)
        assertEquals(0, r.exit, r.err)
        assertTrue("/bin/py PA=-3" in r.out, r.out)
    }

    @Test
    fun keepsAWorkingPython3First() {
        val r = run(mapOf("python3" to works, "python" to works), 127)
        assertEquals(0, r.exit, r.err)
        assertTrue(r.out.endsWith("/bin/python3 PA="), r.out)
    }

    @Test
    fun fallsBackToTheLoginShell() {
        // A python only a login shell's PATH has, after the noise a login shell prints.
        val r = run(emptyMap(), 127, shell = "echo 'motd noise'; echo \"\$PWD/login/python3\"")
        assertEquals(0, r.exit, r.err)
        assertTrue(r.out.endsWith("/login/python3 PA="), r.out)
    }

    @Test
    fun exitsWithTheCodeWhenNone() {
        val r = run(emptyMap(), 127)
        assertEquals(127, r.exit)
        assertEquals("Python 3.6 or newer is not installed", r.err)
        assertEquals("", r.out)
    }

    @Test
    fun nullLeavesPEmpty() {
        val r = run(emptyMap(), null)
        assertEquals(0, r.exit, r.err)
        assertEquals("P= PA=", r.out)
    }
}
