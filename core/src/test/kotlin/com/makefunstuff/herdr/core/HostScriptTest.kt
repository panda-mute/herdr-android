package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.change.HerdrAppBuild
import com.makefunstuff.herdr.core.logins.HerdrLogins
import com.makefunstuff.herdr.core.push.HerdrPush
import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.ssh.HostScript
import com.makefunstuff.herdr.core.usage.HerdrUsage
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The app copies a helper script over an older one only: never over the same or a newer version. */
class HostScriptTest {
    @Test
    fun olderComparesAsIntegers() {
        assertTrue(HostScript.older("1", "2"))
        assertTrue(HostScript.older("9", "10")) // not as text, where "9" > "10"
        assertFalse(HostScript.older("2", "2"))
        assertFalse(HostScript.older("3", "2"))
        assertFalse(HostScript.older("10", "9"))
        // Missing or unparsable: older, so the app's copy goes there.
        assertTrue(HostScript.older(null, "2"))
        assertTrue(HostScript.older("", "2"))
        assertTrue(HostScript.older("2b", "2"))
    }

    /**
     * A machine with herdr-usage at [version] (`null`: none there; [answerVersion] false: its answers
     * carry no version), as [HerdrUsage.Client] sees it. [copies] counts the app's copies to it.
     */
    private class FakeMachine(var version: String?, val answerVersion: Boolean = true) {
        var copies = 0

        fun answer(v: String) = buildString {
            append("""{"ok":true,"v":1,""")
            if (answerVersion) append(""""version":"$v",""")
            append(""""at":${if (v == "99") 99 else 1},"today":"2026-11-17","providers":[]}""")
        }

        val client = HerdrUsage.Client(
            run = { _, _ -> version?.let { CommandResult(0, answer(it) + "\n", "") } ?: CommandResult(HerdrUsage.MISSING_EXIT, "", "") },
            runWithInput = { _, input, _ ->
                assertEquals(HerdrUsage.script, input)
                copies++
                version = HerdrUsage.VERSION
                CommandResult(0, "", "")
            },
            label = "testbox",
        )
    }

    @Test
    fun usageOlderOnTheMachineIsReplaced() = runBlocking {
        val older = (HerdrUsage.VERSION.toInt() - 1).toString()
        val m = FakeMachine(older)
        m.client.read()
        assertEquals(1, m.copies)
        assertEquals(HerdrUsage.VERSION, m.version)
    }

    @Test
    fun usageSameVersionIsNotCopied() = runBlocking {
        val m = FakeMachine(HerdrUsage.VERSION)
        m.client.read()
        m.client.limits()
        assertEquals(0, m.copies)
    }

    @Test
    fun usageNewerOnTheMachineIsKeptAndRead() = runBlocking {
        val m = FakeMachine("99")
        val r = m.client.read()
        assertEquals(0, m.copies)
        assertEquals("99", m.version)
        assertEquals(99L, r.at) // the newer script's own answer
    }

    @Test
    fun usageWithoutAVersionIsReplaced() = runBlocking {
        val m = FakeMachine("0", answerVersion = false)
        m.client.read()
        assertEquals(1, m.copies)
    }

    @Test
    fun usageMissingIsCopied() = runBlocking {
        val m = FakeMachine(null)
        m.client.read()
        assertEquals(1, m.copies)
    }

    /** Each helper's install command, run by sh in a fresh HOME: it never writes over a newer script. */
    @Test
    fun installCommandKeepsANewerScript() {
        data class Helper(val path: String, val version: String, val command: String, val script: String)
        val helpers = listOf(
            Helper(".local/share/herdr-usage/herdr-usage", HerdrUsage.VERSION, HerdrUsage.installCommand(), HerdrUsage.script),
            Helper(".local/share/herdr-push/herdr-push", HerdrPush.version, HerdrPush.installCommand(), HerdrPush.script),
            Helper(".local/share/herdr-logins/herdr-logins", HerdrLogins.VERSION, HerdrLogins.installCommand(), HerdrLogins.script),
            Helper(".local/share/herdr-android/herdr-app-build", HerdrAppBuild.VERSION, HerdrAppBuild.installCommand(), HerdrAppBuild.script),
        )
        for (h in helpers) {
            val home = Files.createTempDirectory("host-script").toFile()
            try {
                val file = File(home, h.path)
                fun install(): CommandResult {
                    val pb = ProcessBuilder("sh", "-c", h.command).directory(home)
                    pb.environment()["HOME"] = home.path
                    val p = pb.start()
                    p.outputStream.use { it.write(h.script.toByteArray()) }
                    val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
                    val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
                    assertTrue(p.waitFor(30, TimeUnit.SECONDS))
                    return CommandResult(p.exitValue(), out, err)
                }
                fun script(v: String?) = "#!/usr/bin/env python3\n" + (v?.let { "# version: $it\n" } ?: "") + "print('mine')\n"

                // None there: copied.
                assertEquals(0, install().exitStatus, h.path)
                assertEquals(h.script, file.readText(), h.path)

                // Older, or one without a version line: replaced.
                for (old in listOf((h.version.toInt() - 1).toString(), null)) {
                    file.writeText(script(old))
                    assertEquals(0, install().exitStatus, h.path)
                    assertEquals(h.script, file.readText(), h.path)
                }

                // The same version: written again (harmless), so the file is the app's.
                assertEquals(0, install().exitStatus, h.path)
                assertEquals(h.script, file.readText(), h.path)

                // Newer, compared as a number (e.g. "10" over "2", though "10" < "2" as text): kept as it is.
                val newer = script("${h.version.toInt() + 8}")
                file.writeText(newer)
                val r = install()
                assertEquals(0, r.exitStatus, r.stderr)
                assertEquals(newer, file.readText(), h.path)
                assertFalse(File(file.path + ".new").exists(), h.path)
            } finally {
                home.deleteRecursively()
            }
        }
    }

    @Test
    fun theAppsOwnVersionMustBeANumber() {
        assertFailsWith<IllegalArgumentException> { HostScript.older("1", "two") }
        assertFailsWith<IllegalArgumentException> { HostScript.installCommand("d", "f", "") }
    }
}
