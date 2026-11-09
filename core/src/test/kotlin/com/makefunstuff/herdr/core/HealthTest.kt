package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.HerdrJson
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.health.Disk
import com.makefunstuff.herdr.core.health.HealthClient
import com.makefunstuff.herdr.core.health.HealthCommands
import com.makefunstuff.herdr.core.health.HealthHistory
import com.makefunstuff.herdr.core.health.HealthPoint
import com.makefunstuff.herdr.core.health.HealthReader
import com.makefunstuff.herdr.core.health.HealthReport
import com.makefunstuff.herdr.core.health.HealthRules
import com.makefunstuff.herdr.core.health.HealthSample
import com.makefunstuff.herdr.core.health.Memory
import com.makefunstuff.herdr.core.health.Proc
import com.makefunstuff.herdr.core.health.ProcKind
import com.makefunstuff.herdr.core.health.ProcLabels
import com.makefunstuff.herdr.core.health.Sizes
import com.makefunstuff.herdr.core.health.WorktreeDir
import com.makefunstuff.herdr.core.ssh.CommandResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class HealthTest {
    private val linux = File("/proc/meminfo").canRead()

    // The command goes through a file, as SSH hands it to a login shell: on Windows Java mangles the quotes of `sh -c`.
    private fun sh(command: String, home: File? = null, dir: File? = null, path: File? = null): CommandResult {
        val script = File.createTempFile("health-sh", ".sh").apply { deleteOnExit(); writeText(command + "\n") }
        val pb = ProcessBuilder("sh", script.path)
        if (home != null) pb.environment()["HOME"] = home.path
        if (path != null) pb.environment()["PATH"] = path.path + File.pathSeparator + pb.environment()["PATH"]
        if (dir != null) pb.directory(dir)
        // stderr to a file: a command that says a lot there (gawk --lint) must not block on a full pipe.
        val errFile = File.createTempFile("health-sh", ".err").apply { deleteOnExit() }
        pb.redirectError(errFile)
        val p = pb.start()
        p.outputStream.close()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        val err = errFile.readText().also { errFile.delete() }
        script.delete()
        return CommandResult(p.exitValue(), out, err)
    }

    private val client = HealthClient { cmd, _ -> sh(cmd) }

    // ---- the probe, run on this machine -----------------------------------------------------------

    @Test
    fun probeReadsThisMachine() = runBlocking {
        assumeTrue(linux, "the full probe is for Linux")
        val s = client.sample(listOf("herdr-android/work-22-"))
        val m = assertNotNull(s.memory)
        assertTrue(m.totalKb > 0 && m.availableKb in 1..m.totalKb, "$m")
        assertTrue(s.cores >= 1)
        assertTrue(s.at > 1_700_000_000)
        assertEquals("Linux", s.os)
        assertTrue(s.home.startsWith("/"))
        assertNotNull(s.cpuBusy)
        assertTrue(s.disks.isNotEmpty(), "disks")
        assertTrue(s.disks.none { it.type == "tmpfs" })
        assertTrue(s.procs.isNotEmpty(), "procs")
        // Only what is worth showing travels: never the whole process table of small processes.
        assertTrue(s.procs.all { it.rssKb >= 65536 || it.cpu >= 2 || s.own(it) }, "filtered")
        // The account's own processes carry where they run.
        val own = s.procs.filter { s.own(it) }
        if (own.isNotEmpty()) assertTrue(own.any { it.cwd.startsWith("/") }, "cwd of own processes")
    }

    /**
     * The probe's awk never reads an array element or variable it didn't set: gawk 5.2.1 (GitHub's
     * Ubuntu 24.04 runner, Debian 12) corrupts its empty string when a program does, and crashes
     * ("fatal: floating point exception"), which left the screen without its processes. gawk's lint
     * names every such read, whatever gawk this machine has.
     */
    @Test
    fun probeReadsNothingItDidNotSet() {
        assumeTrue(linux, "the full probe is for Linux")
        assumeTrue(sh("command -v gawk").exitStatus == 0, "gawk isn't installed")
        val bin = Files.createTempDirectory("lint").toFile()
        try {
            File(bin, "awk").apply { writeText("#!/bin/sh\nexec gawk --lint \"\$@\"\n"); setExecutable(true) }
            val r = sh(HealthCommands.probe(listOf("demo/work-7-")), path = bin)
            val reads = r.stderr.lines().filter { "uninitialized" in it || "fatal" in it }
            assertEquals(0, r.exitStatus, r.stderr.take(2000))
            assertTrue(reads.isEmpty(), reads.distinct().take(10).joinToString("\n"))
            assertTrue(HealthReader.sample(r.stdout).procs.isNotEmpty())
        } finally {
            bin.deleteRecursively()
        }
    }

    /** A look that dies partway is an error, never a machine with nothing running. */
    @Test
    fun aProbeThatFailsPartwayIsAnError() = runBlocking {
        val partial = "@@herdr-health 1\n@os Linux\n@cores 4\n@meminfo\nMemTotal: 1000 kB\nMemAvailable: 500 kB\n@cpu 12.0\n"
        val crashed = HealthClient { _, _ -> CommandResult(2, partial, "awk: cmd. line:34: (FILENAME=- FNR=535) fatal: floating point exception\n") }
        val e = assertFailsWith<java.io.IOException> { crashed.sample() }
        assertEquals("Reading the machine's health failed: awk: cmd. line:34: (FILENAME=- FNR=535) fatal: floating point exception", e.message)
        assertEquals("Reading the machine's health took too long", assertFailsWith<java.io.IOException> { HealthClient { _, _ -> CommandResult(null, partial, "") }.quick() }.message)
        assertNotNull(HealthClient { _, _ -> CommandResult(0, partial, "") }.sample().memory)
    }

    @Test
    fun quickLookReadsThisMachine() = runBlocking {
        assumeTrue(linux, "Linux")
        val s = client.quick()
        assertNotNull(s.memory)
        assertNotNull(s.cpuBusy)
        assertTrue(s.procs.isEmpty())
    }

    @Test
    fun probeFindsAProcessInAFinishedWorktree() = runBlocking {
        assumeTrue(linux, "Linux")
        val home = Files.createTempDirectory("health").toFile()
        val wt = File(home, ".herdr/worktrees/demo/work-7-old-task").apply { mkdirs() }
        val p = ProcessBuilder("sleep", "300").directory(wt).start()
        try {
            // Wait until it runs as sleep in the worktree (Java starts it through a helper first).
            val pid = p.pid()
            fun seen() = sh("readlink /proc/$pid/cwd; cat /proc/$pid/comm").stdout.lines()
            val until = System.currentTimeMillis() + 5_000
            while (seen() != listOf(wt.canonicalPath, "sleep", "") && System.currentTimeMillis() < until) Thread.sleep(50)
            // The probe reads other processes' folders as any other program of the account would. Some
            // sandboxes hide those from a process that isn't their parent; there is nothing to find then.
            assumeTrue(seen().firstOrNull() == wt.canonicalPath, "this system doesn't let a shell read another process's folder: ${seen()}")
            // What each tool shows for this process here, in a failure's message: CI shows nothing else.
            val why by lazy {
                val probe = sh(HealthCommands.probe(listOf("demo/work-7-")))
                listOf(
                    "pid $pid, worktree ${wt.canonicalPath}",
                    "readlink+comm: ${seen()}",
                    "find: " + sh("find /proc -mindepth 2 -maxdepth 2 -name cwd -user \"$(id -u)\" -printf 'D\\t%h\\t%l\\n' 2>&1 | grep -w $pid; ls -ln /proc/$pid/cwd 2>&1").stdout.trim(),
                    "ps: " + sh("ps -o pid=,ppid=,uid=,rss=,etimes=,args= -p $pid 2>&1").stdout.trim(),
                    "env: " + sh("tr '\\000' '\\n' < /proc/$pid/environ 2>&1 | grep -E '^(PWD|HERDR)' ").stdout.trim(),
                    "tools: " + sh("id; readlink -f \"$(command -v awk)\"; find --version | head -n 1; ps --version 2>&1 | head -n 1; uname -r").stdout.trim().replace("\n", " | "),
                    "probe exit ${probe.exitStatus}, stderr: ${probe.stderr.take(500)}",
                    "probe: " + probe.stdout.lines().filter { "\t$pid\t" in it || it.startsWith("@") && !it.startsWith("@df") }.take(20),
                ).joinToString("\n")
            }
            fun expect(ok: Boolean, what: () -> String) { if (!ok) fail(what() + "\n" + why) }
            val s = client.sample(listOf("demo/work-7-"))
            val mine = s.procs.firstOrNull { it.pid.toLong() == pid }
            expect(mine != null) { "the sleep in the finished worktree isn't listed" }
            expect(mine!!.comm == "sleep") { "comm: ${mine.comm}" }
            expect(mine.cwd == wt.canonicalPath) { "cwd: ${mine.cwd}" }
            expect(mine.args == "sleep 300") { "args: ${mine.args}" }
            // Without the worktree in [keep] a small process stays home.
            val without = client.sample().procs.firstOrNull { it.pid.toLong() == pid }
            expect(without == null) { "listed without its worktree in keep: $without" }
        } finally {
            p.destroyForcibly()
            home.deleteRecursively()
        }
    }

    // ---- killing ------------------------------------------------------------------------------------

    @Test
    fun killStopsOnlyTheSameProgram() = runBlocking {
        assumeTrue(linux, "Linux")
        val p = ProcessBuilder("sleep", "300").start()
        try {
            val pid = p.pid().toInt()
            val wrong = client.kill(listOf(HealthCommands.Target(pid, "java")))
            assertEquals(listOf(pid), wrong.gone)
            assertTrue(p.isAlive, "a pid that is now another program is left alone")
            val done = client.kill(listOf(HealthCommands.Target(pid, "sleep")))
            assertEquals(listOf(pid), done.stopped)
            assertTrue(p.waitFor(5, TimeUnit.SECONDS))
        } finally {
            p.destroyForcibly()
        }
    }

    @Test
    fun killForcesWhatIgnoresTerm() = runBlocking {
        assumeTrue(linux, "Linux")
        val p = ProcessBuilder("sh", "-c", "trap '' TERM; while :; do sleep 1; done").start()
        try {
            Thread.sleep(300)
            val pid = p.pid().toInt()
            val done = client.kill(listOf(HealthCommands.Target(pid, "sh")))
            assertEquals(listOf(pid), done.stopped)
            assertEquals(listOf(pid), done.forced)
            assertTrue(p.waitFor(5, TimeUnit.SECONDS))
        } finally {
            p.destroyForcibly()
        }
    }

    // ---- Windows (Git Bash), faked so they run anywhere ---------------------------------------------

    /**
     * What Git Bash on Windows looks like to the scripts: `uname` says MINGW64, `powershell.exe` answers by
     * the script it is given (it arrives packed, gzip and Base64, so the fake unpacks it) and `tasklist` and
     * `taskkill` work on the files in `procs/`, one per running pid holding its image name.
     */
    private fun windowsBin(disks: String = "", procs: String = ""): File {
        val bin = Files.createTempDirectory("win").toFile()
        File(bin, "disks.txt").writeText(disks)
        File(bin, "procs.txt").writeText(procs)
        File(bin, "procs").mkdirs()
        fun tool(name: String, body: String) = File(bin, name).apply { writeText("#!/bin/sh\nd=\"\$(dirname \"\$0\")\"\n$body\n"); setExecutable(true) }
        tool("uname", "echo MINGW64_NT-10.0-26100")
        // Coreutils timeout, logging the limit it was given; with a file `hang` in bin/ every limit is 1 second.
        tool(
            "timeout",
            """
            echo "timeout ${'$'}1" >> "${'$'}d/calls.log"
            n="${'$'}1"; shift; [ -f "${'$'}d/hang" ] && [ "${'$'}n" != 0 ] && n=1
            exec /usr/bin/timeout "${'$'}n" "${'$'}@"
            """.trimIndent(),
        )
        tool(
            "powershell.exe",
            """
            echo "${'$'}HERDR_UID ${'$'}HERDR_HZ" > "${'$'}d/env.log"
            [ -f "${'$'}d/hang" ] && exec sleep 30
            # The drives' script is in the arguments as it is; the processes' is packed: gzip in Base64 in a line that runs it.
            script="${'$'}*"
            case "${'$'}script" in *FromBase64String*) script="${'$'}(printf %s "${'$'}script" | sed "s/.*FromBase64String('\([^']*\)').*/\1/" | base64 -d | gunzip)" ;; esac
            case "${'$'}script" in
              *Win32_LogicalDisk*) cat "${'$'}d/disks.txt" ;;
              *Win32_Process*) echo M1; sleep 1; echo M2; sed "s/@UID@/${'$'}HERDR_UID/" "${'$'}d/procs.txt" ;;
            esac
            """.trimIndent(),
        )
        // The image name of a pid as tasklist's CSV shows it; "INFO: No tasks" when there is none.
        tool(
            "tasklist",
            """
            echo "tasklist ${'$'}MSYS2_ARG_CONV_EXCL ${'$'}*" >> "${'$'}d/calls.log"
            p="${'$'}{2#PID eq }"
            if [ -f "${'$'}d/procs/${'$'}p" ]; then echo "\"${'$'}(cat "${'$'}d/procs/${'$'}p")\",\"${'$'}p\",\"Console\",\"1\",\"9,000 K\""; else echo "INFO: No tasks are running which match the specified criteria."; fi
            """.trimIndent(),
        )
        // A pid ending with 9 is a console process: only /F ends it. 8 ignores the polite request. 7 is gone when taskkill runs; 6 cannot be stopped.
        tool(
            "taskkill",
            """
            echo "taskkill ${'$'}MSYS2_ARG_CONV_EXCL ${'$'}*" >> "${'$'}d/calls.log"
            if [ "${'$'}1" = /F ]; then p="${'$'}3"; force=1; else p="${'$'}2"; force=0; fi
            case "${'$'}p" in
              *9) [ ${'$'}force = 1 ] || exit 1 ;;
              *7) rm -f "${'$'}d/procs/${'$'}p"; exit 1 ;;
              *6) exit 1 ;;
              *8) [ ${'$'}force = 1 ] || exit 0 ;;
            esac
            rm -f "${'$'}d/procs/${'$'}p"
            """.trimIndent(),
        )
        return bin
    }

    private fun windowsSample(bin: File, keep: List<String> = emptyList()): HealthSample =
        HealthReader.sample(sh(HealthCommands.probe(keep), path = bin).also { assertEquals(0, it.exitStatus, it.stderr.take(2000)) }.stdout)

    private fun kb(n: Long) = n * 1024

    @Test
    fun windowsDisksAreEachDrive() {
        val bin = windowsBin(disks = "C:\tNTFS\t${kb(1_952_568_336)}\t${kb(448_932_764)}\r\nD:\tNTFS\t${kb(488_378_644)}\t${kb(120_000_000)}\r\n")
        try {
            val s = HealthReader.sample(sh(HealthCommands.quick(), path = bin).stdout)
            assertEquals("MINGW64_NT-10.0-26100", s.os)
            assertEquals(
                listOf(
                    Disk("/c", "C:", "NTFS", 1_952_568_336, 1_952_568_336 - 448_932_764, 448_932_764),
                    Disk("/d", "D:", "NTFS", 488_378_644, 488_378_644 - 120_000_000, 120_000_000),
                ),
                s.disks,
            )
        } finally {
            bin.deleteRecursively()
        }
    }

    @Test
    fun windowsSkipsDrivesWithoutSize() {
        // An empty card reader has no size (or 0), and a drive that gives no free space can not be told from a full one: the others are still read.
        val bin = windowsBin(disks = "C:\tNTFS\t${kb(1_000_000)}\t${kb(400_000)}\nE:\t\t\t\nF:\tNTFS\t0\t0\nG:\tNTFS\t${kb(300_000)}\t\nD:\tNTFS\t${kb(500_000)}\t${kb(100_000)}\n")
        try {
            val s = HealthReader.sample(sh(HealthCommands.probe(), path = bin).stdout)
            assertEquals(listOf("/c", "/d"), s.disks.map { it.mount })
            assertEquals(500_000L, s.disks.last().totalKb)
        } finally {
            bin.deleteRecursively()
        }
    }

    @Test
    fun windowsProcessesAreListed() {
        val procs = listOf(
            // What the PowerShell look prints: ticks of the first look (A), of the second (T), and the rows (P).
            "A\t4100\t10", "A\t5200\t500", "A\t6300\t0", "A\t7400\t90",
            "T\t4100\t10\tclaude", "T\t5200\t500\texplorer", "T\t6300\t1000000\tbuild", "T\t7400\t90\tsvchost",
            "P\t4100 900 @UID@ 614400 3600 C:\\Users\\dev\\AppData\\Local\\claude\\claude.exe --dangerously-skip-permissions",
            "P\t5200 1 0 204800 90000 C:\\WINDOWS\\Explorer.EXE",
            "P\t6300 4100 @UID@ 30720 60 C:\\Tools\\build.exe --watch",
            "P\t7400 1 0 12288 90000 C:\\WINDOWS\\system32\\svchost.exe -k netsvcs",
        ).joinToString("\n", postfix = "\n")
        val bin = windowsBin(procs = procs)
        try {
            val s = windowsSample(bin)
            val (uid, hz) = File(bin, "env.log").readText().trim().split(' ')
            // The probe tells the script who the account is and how long a tick is, so it can mark and measure.
            assertEquals(s.uid.toString(), uid)
            assertTrue(hz.toInt() > 0, hz)
            // The small quiet process isn't worth showing.
            assertEquals(setOf(4100, 5200, 6300), s.procs.map { it.pid }.toSet())
            val labeled = ProcLabels.label(s, null).sortedByDescending { it.proc.rssKb }
            val first = labeled.first()
            assertEquals(4100, first.proc.pid)
            assertEquals(ProcKind.Agent to "claude", first.kind to first.name)
            assertEquals(614400L, first.proc.rssKb)
            assertEquals(3600L, first.proc.ageSeconds)
            assertEquals("claude", first.proc.comm)
            assertEquals("C:\\Users\\dev\\AppData\\Local\\claude\\claude.exe --dangerously-skip-permissions", first.proc.args)
            // The account's own processes carry its uid; every other one has the neutral 0.
            assertEquals(0, labeled[1].proc.uid)
            if (s.uid != 0) assertEquals(listOf(true, false, true), labeled.map { it.own })
            assertEquals("", first.proc.cwd)
            // The busy one is found by its ticks over the look, which /proc/stat measures where there is one.
            if (File("/proc/stat").canRead()) {
                assertNotNull(s.cpuBusy)
                assertTrue(labeled.first { it.proc.pid == 6300 }.proc.cpu > 100, "$labeled")
                assertEquals(0.0, labeled.first { it.proc.pid == 4100 }.proc.cpu)
            }
        } finally {
            bin.deleteRecursively()
        }
    }

    @Test
    fun windowsKillStopsOnlyTheSameProgram() {
        val bin = windowsBin()
        try {
            val running = mapOf(4100 to "claude.exe", 4101 to "notepad.exe", 4109 to "node.exe", 4108 to "sleeper.exe", 4110 to "Adobe Crash Processor.exe")
            running.forEach { (pid, name) -> File(bin, "procs/$pid").writeText(name) }
            val r = sh(
                HealthCommands.kill(
                    listOf(
                        HealthCommands.Target(4100, "claude"), HealthCommands.Target(4101, "claude"), HealthCommands.Target(4109, "node"),
                        HealthCommands.Target(4110, "Adobe Crash Processor"), HealthCommands.Target(4999, "claude"),
                    ),
                ),
                path = bin,
            )
            assertEquals(0, r.exitStatus, r.stderr)
            val done = HealthReader.killed(r.stdout)
            // Stopped; a pid that is another program now, or none, is left alone; a console process is ended by force.
            assertEquals(listOf(4100, 4109, 4110), done.stopped)
            assertEquals(listOf(4101, 4999), done.gone)
            assertEquals(listOf(4109), done.forced)
            assertTrue(done.failed.isEmpty())
            assertTrue(File(bin, "procs/4101").exists(), "a pid that is now another program is left alone")
            assertFalse(File(bin, "procs/4100").exists())
            val calls = File(bin, "calls.log").readLines()
            // Git Bash must not turn /PID into a path, so every call says it was told not to.
            val tools = calls.filter { it.startsWith("tasklist") || it.startsWith("taskkill") }
            assertTrue(tools.isNotEmpty() && tools.all { it.split(' ')[1] == "*" }, calls.toString())
            // Every one is bounded, so a wedged tasklist or taskkill ends at 10 seconds.
            assertEquals(listOf("timeout 0") + List(tools.size) { "timeout 10" }, calls.filter { it.startsWith("timeout") })
            assertEquals(listOf("taskkill * /PID 4100", "taskkill * /PID 4109", "taskkill * /F /PID 4109", "taskkill * /PID 4110"), tools.filter { it.startsWith("taskkill") })
        } finally {
            bin.deleteRecursively()
        }
    }

    @Test
    fun windowsKillForcesWhatIgnoresTheRequest() {
        val bin = windowsBin()
        try {
            File(bin, "procs/4108").writeText("sleeper.exe")
            val started = System.currentTimeMillis()
            val done = HealthReader.killed(sh(HealthCommands.kill(listOf(HealthCommands.Target(4108, "sleeper"))), path = bin).stdout)
            assertEquals(listOf(4108), done.stopped)
            assertEquals(listOf(4108), done.forced)
            assertFalse(File(bin, "procs/4108").exists())
            // It gave it the polite 5 seconds first.
            assertTrue(System.currentTimeMillis() - started >= 4_500)
        } finally {
            bin.deleteRecursively()
        }
    }

    @Test
    fun windowsKillSaysGoneWhenTheProcessEndedMeanwhile() {
        val bin = windowsBin()
        try {
            // 4107 ends just as taskkill runs (both calls fail, the pid is no more); 4106 can not be stopped.
            File(bin, "procs/4107").writeText("claude.exe")
            File(bin, "procs/4106").writeText("claude.exe")
            val targets = listOf(HealthCommands.Target(4107, "claude"), HealthCommands.Target(4106, "claude"))
            val done = HealthReader.killed(sh(HealthCommands.kill(targets), path = bin).stdout)
            assertEquals(listOf(4107), done.gone)
            assertEquals(listOf(4106), done.failed)
            assertTrue(done.stopped.isEmpty() && done.forced.isEmpty())
        } finally {
            bin.deleteRecursively()
        }
    }

    /** A PowerShell or WMI that hangs costs its own part of the look, not the memory, the CPU or the time of the rest. */
    @Test
    fun windowsLooksAreBounded() {
        val bin = windowsBin(disks = "C:\tNTFS\t${kb(1_000_000)}\t${kb(400_000)}\n", procs = "A\t1\t0\nT\t1\t0\tx\nP\t1 0 0 900000 5 x\n")
        try {
            // Their limits: 10 seconds for the drives, 20 for the processes.
            windowsSample(bin)
            val limits = File(bin, "calls.log").readLines().filter { it.startsWith("timeout") && it != "timeout 0" }
            assertEquals(listOf("timeout 10", "timeout 20"), limits)
            // With both hanging (the test's timeout cuts them at 1 second) the rest is still read.
            File(bin, "hang").writeText("")
            val started = System.currentTimeMillis()
            val s = windowsSample(bin)
            assertTrue(System.currentTimeMillis() - started < 20_000)
            assertNotNull(s.memory)
            assertTrue(s.disks.isEmpty())
            assertTrue(s.procs.isEmpty())
            if (File("/proc/stat").canRead()) assertNotNull(s.cpuBusy)
        } finally {
            bin.deleteRecursively()
        }
    }

    // ---- the Windows command line --------------------------------------------------------------------

    private val gitBash = File("C:\\Program Files\\Git\\bin\\bash.exe")

    /** Each look as the app sends it: through the login shell, so quoted twice. */
    private val sent = mapOf(
        "probe" to HealthCommands.probe(listOf("herdr-android/work-22-", "demo/work-7-")),
        "quick" to HealthCommands.quick(),
        "kill" to HealthCommands.kill((1..5).map { HealthCommands.Target(40_000 + it, "Adobe Crash Processor") }),
    ).mapValues { com.makefunstuff.herdr.core.ssh.HerdrCommands.login(it.value) }

    /**
     * On a Windows machine SSH hands the command to Git's bash as `bash.exe -c "<command>"` with each `"`
     * written `\"`, and the command line is cut at 8191 characters: a command longer than that arrives
     * without its end (bash then says "unexpected EOF while looking for matching `'`"). So every look
     * must stay under it, with some room for the folders in the probe's list.
     */
    @Test
    fun theLooksFitTheWindowsCommandLine() {
        val room = 8191 - "\"C:\\Program Files\\Git\\bin\\bash.exe\" -c \"".length - 1 - 300
        for ((name, command) in sent) {
            val line = command.replace("\"", "\\\"")
            assertTrue(line.length <= room, "$name is ${line.length} characters, the room is $room")
        }
    }

    /** The same path as SSH on Windows: Git's bash started with the command line Windows builds for `-c "<command>"`. */
    @Test
    fun windowsLooksSurviveGitBashsCommandLine() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows") && gitBash.canExecute(), "Windows with Git for Windows")
        fun run(command: String): CommandResult {
            val err = File.createTempFile("health-bash", ".err").apply { deleteOnExit() }
            // Java passes an argument that is already in quotes as it is, so the line is the one SSH builds.
            // An SSH session has no USERDOMAIN (nor USERNAME): the account of a process comes from its token.
            val pb = ProcessBuilder(gitBash.path, "-c", "\"" + command.replace("\"", "\\\"") + "\"").redirectError(err)
            pb.environment().keys.removeAll { it.equals("USERDOMAIN", true) || it.equals("USERNAME", true) }
            val p = pb.start()
            p.outputStream.close()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            assertTrue(p.waitFor(60, TimeUnit.SECONDS))
            return CommandResult(p.exitValue(), out, err.readText())
        }
        for (name in listOf("probe", "quick")) {
            val r = run(sent.getValue(name))
            assertEquals(0, r.exitStatus, r.stderr.take(500))
            assertFalse("unexpected EOF" in r.stderr, r.stderr.take(500))
            assertTrue(HealthCommands.MARK in r.stdout && "@end-df" in r.stdout, "$name: ${r.stdout.take(300)} / ${r.stderr.take(300)}")
            val s = HealthReader.sample(r.stdout)
            assertTrue(s.disks.isNotEmpty(), name)
            if (name == "probe") {
                assertTrue(s.procs.isNotEmpty(), "the processes of Windows are listed")
                // This test's own JVM runs under the account: it is listed and is the account's.
                assertTrue(s.procs.any { s.own(it) }, "no process is the account's: ${s.procs.map { it.uid }.distinct()} against ${s.uid}")
            }
        }
        // Pids that are not these programs: the stop command ran to its end.
        assertEquals("gone 40001", run(sent.getValue("kill")).stdout.lines().first().trim())
    }

    // ---- worktrees ----------------------------------------------------------------------------------

    @Test
    fun listsAndRemovesWorktrees() = runBlocking {
        assumeTrue(sh("git --version").exitStatus == 0, "git")
        val home = Files.createTempDirectory("health").toFile()
        try {
            val repo = File(home, "src/demo").apply { mkdirs() }
            fun git(vararg a: String, dir: File = repo) = sh("git " + a.joinToString(" ") { "'$it'" }, home, dir).also {
                assertEquals(0, it.exitStatus, it.stderr)
            }
            git("init", "-q", "-b", "main")
            git("-c", "user.email=a@b", "-c", "user.name=a", "commit", "-q", "--allow-empty", "-m", "first")
            val clean = File(home, ".herdr/worktrees/demo/work-3-done-task")
            val dirty = File(home, ".herdr/worktrees/demo/work-4-other")
            git("worktree", "add", "-q", "-b", "work/3", clean.path)
            git("worktree", "add", "-q", "-b", "work/4", dirty.path)
            File(dirty, "notes.txt").writeText("not committed")

            val homeClient = HealthClient { cmd, _ -> sh(cmd, home) }
            val dirs = homeClient.worktrees().sortedBy { it.path }
            assertEquals(listOf("work-3-done-task", "work-4-other"), dirs.map { it.name })
            assertEquals(listOf(false, true), dirs.map { it.dirty })
            assertTrue(dirs.all { (it.sizeKb ?: 0) > 0 })
            assertEquals("demo", dirs[0].repo)

            homeClient.removeWorktree(clean.path, force = false)
            assertFalse(clean.exists())
            val refused = assertFailsWith<java.io.IOException> { homeClient.removeWorktree(dirty.path, force = false) }
            assertTrue("untracked" in refused.message.orEmpty() || "modified" in refused.message.orEmpty(), refused.message)
            assertTrue(dirty.exists())
            homeClient.removeWorktree(dirty.path, force = true)
            assertFalse(dirty.exists())
            assertEquals("That worktree is already gone.", assertFailsWith<java.io.IOException> { homeClient.removeWorktree(dirty.path, false) }.message)
        } finally {
            home.deleteRecursively()
        }
    }

    // ---- reading -------------------------------------------------------------------------------------

    private val linuxOut = """
        Last login: someday
        @@herdr-health 1
        @os Linux
        @now 1792250000
        @uid 1000
        @home /home/demo
        @cores 8
        @meminfo
        MemTotal:       65536000 kB
        MemFree:         1000000 kB
        MemAvailable:    5242880 kB
        Buffers:          100000 kB
        Cached:          3000000 kB
        SwapTotal:       8388608 kB
        SwapFree:        4194304 kB
        @loadavg 3.20 2.10 1.50 2/1234 99999
        @df
        Filesystem     Type     1024-blocks      Used Available Capacity Mounted on
        /dev/nvme0n1p2 btrfs      976762584 900000000  70000000      93% /
        /dev/nvme0n1p2 btrfs      976762584 900000000  70000000      93% /home
        tmpfs          tmpfs       32768000      1000  32767000       1% /run
        /dev/nvme0n1p1 vfat         1046508     30000   1016508       3% /boot/efi
        /dev/loop3     squashfs       56960     56960         0     100% /snap/core20/1974
        /dev/sdb1      ext4       960302096 610837828 300609844      68% /data/big disk
        @end-df
        @cpu 37.5
        P	4242	1	1000	5234804	0.0	86400	java	/home/demo/.gradle/daemon/8.11.1	/home/demo/.herdr/worktrees/shop/work-22-old-thing	w1C:p1	w1C	/usr/lib/jvm/bin/java -Xmx4g -cp /home/demo/.gradle/wrapper/dists/gradle-8.11.1-bin/x/gradle-8.11.1/lib/gradle-daemon-main-8.11.1.jar org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.11.1
        P	5000	4999	1000	5819660	80.0	3600	qemu-system-x86	/home/demo/.herdr/worktrees/shop/work-22-old-thing/app				/home/demo/Android/Sdk/emulator/qemu/linux-x86_64/qemu-system-x86_64 -avd Pixel_8 -no-window
        P	6000	1	0	4904052	3.0	999999	qemu-system-x86					/usr/bin/qemu-system-x86_64 -name guest=home-services-vm,debug-threads=on -m 12g
        P	7000	6900	1000	412000	0.5	1200	claude	/home/demo/.herdr/worktrees/shop/work-23-new-thing		w1D:p1	w1D	claude --dangerously-skip-permissions
        P	7100	7000	1000	210000	4.0	600	node	/home/demo/.herdr/worktrees/shop/work-23-new-thing	/home/demo/.herdr/worktrees/shop/work-23-new-thing	w1D:p2	w1D	node /home/demo/.herdr/worktrees/shop/work-23-new-thing/node_modules/.bin/vite --port 5173
        P	7200	1	1000	300000	0.0	9000	node	/home/demo/src/blog				node server.js
        P	7300	1	1000	900000	0.0	9000	java	/home/demo/.herdr/worktrees/shop/work-22-old-thing				java -cp kotlin-compiler-embeddable.jar org.jetbrains.kotlin.daemon.KotlinCompileDaemon --daemon-runFilesPath x
        P	7400	1	1001	800000	0.0	9000	java	/home/other/.herdr/worktrees/shop/work-22-old-thing				java -jar other.jar
        P	7500	1	1000	20000	0.0	9000	adb	/home/demo/.herdr/worktrees/shop/work-22-old-thing				adb -L tcp:5037 fork-server server --reply-fd 4
        P	7600	1	1000	30000	0.0	9000	herdr	/home/demo/.herdr/worktrees/shop/work-22-old-thing (deleted)				herdr --session extras22 server
        P	7700	1	1000	90000	0.0	9000	herdr	/home/demo/.herdr/worktrees/shop/work-22-old-thing				/home/demo/.local/bin/herdr server
        P	7800	1	1000	900000	35.0	9000	java	/home/demo/.gradle/daemon/8.11.1	/home/demo/.herdr/worktrees/shop/work-22-old-thing			java org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.11.1
    """.trimIndent()

    @Test
    fun readsLinux() {
        val s = HealthReader.sample(linuxOut)
        assertEquals(1792250000, s.at)
        assertEquals(8, s.cores)
        assertEquals(Memory(65536000, 5242880, 8388608, 4194304), s.memory)
        assertEquals(8.0, s.memory!!.availablePercent)
        assertEquals(50.0, s.memory!!.swapUsedPercent)
        assertEquals(3.2, s.load1)
        assertEquals(40.0, s.loadPercent)
        assertEquals(37.5, s.cpuBusy)
        // btrfs subvolumes once, under /; no tmpfs, EFI or snap images; a mount with a space.
        assertEquals(listOf("/", "/data/big disk"), s.disks.map { it.mount })
        assertEquals(12, s.procs.size)
        assertEquals("/home/demo/.herdr/worktrees/shop/work-22-old-thing", s.procs.first { it.pid == 7600 }.cwd)
        val g = s.procs.first()
        assertEquals(4242, g.pid)
        assertEquals(5234804, g.rssKb)
        assertEquals(0.0, g.cpu)
        assertEquals("/home/demo/.herdr/worktrees/shop/work-22-old-thing", g.launchDir)
        assertEquals("w1C:p1", g.paneId)
    }

    @Test
    fun readsCpuFromTwoLooks() {
        val out = "@@herdr-health 1\n@cpu1 cpu  100 0 100 700 100 0 0 0 0 0\n@cpu2 cpu  200 0 200 1300 300 0 0 0 0 0\n"
        // 1000 ticks passed, 800 of them idle or waiting.
        assertEquals(20.0, HealthReader.sample(out).cpuBusy)
    }

    @Test
    fun readsMacos() {
        val out = """
            @@herdr-health 1
            @os Darwin
            @now 1792250000
            @uid 501
            @home /Users/demo
            @cores 10
            @memsize 17179869184
            @pagesize 16384
            @swapusage total = 2048.00M  used = 1024.00M  free = 1024.00M  (encrypted)
            @loadavg  2.50 2.00 1.80
            @vmstat
            Mach Virtual Memory Statistics: (page size of 16384 bytes)
            Pages free:                               10000.
            Pages active:                            300000.
            Pages inactive:                          100000.
            Pages speculative:                         5000.
            Pages purgeable:                           1000.
            @df
            Filesystem     1024-blocks      Used Available Capacity  Mounted on
            /dev/disk3s1s1   971350180  10000000 400000000     3%    /
            devfs                  199       199         0   100%    /dev
            /dev/disk3s5     971350180 500000000 400000000    56%    /System/Volumes/Data
            map auto_home            0         0         0   100%    /System/Volumes/Data/home
            /dev/disk5s1     100000000  90000000  10000000    90%    /Volumes/External
            @end-df
            @ps
              812     1   501  1500000  45.2 02-03:04:05 /Applications/Android Studio.app/Contents/jbr/bin/java -Xmx2g
              900     1   501   700000   0.0    12:34 /opt/homebrew/bin/node /Users/demo/site/node_modules/.bin/next dev
        """.trimIndent()
        val s = HealthReader.sample(out)
        val m = assertNotNull(s.memory)
        assertEquals(16L * 1024 * 1024, m.totalKb)
        assertEquals(116000L * 16, m.availableKb)
        assertEquals(2048L * 1024, m.swapTotalKb)
        assertEquals(1024L * 1024, m.swapUsedKb)
        assertEquals(2.5, s.load1)
        assertNull(s.cpuBusy)
        assertEquals(listOf("/", "/Volumes/External"), s.disks.map { it.mount })
        assertEquals(2, s.procs.size)
        assertEquals(2 * 86400L + 3 * 3600 + 4 * 60 + 5, s.procs[0].ageSeconds)
        assertEquals("java", s.procs[0].comm)
        assertEquals(754, s.procs[1].ageSeconds)
        assertEquals(ProcKind.DevServer, ProcLabels.kind(s.procs[1]).first)
    }

    @Test
    fun refusesOutputWithoutTheMark() {
        assertFailsWith<java.io.IOException> { HealthReader.sample("sh: awk: not found\n") }
    }

    @Test
    fun readsWhatKillAndWorktreesPrint() {
        assertEquals(HealthReader.Killed(listOf(1, 2), listOf(2), listOf(3), listOf(4)), HealthReader.killed("sent 1\nsent 2\ngone 3\nfail 4\nforced 2\n"))
        assertEquals(
            listOf(WorktreeDir("/h/.herdr/worktrees/a/work-1-x", 1234, false), WorktreeDir("/h/.herdr/worktrees/a/b", null, true)),
            HealthReader.worktrees("@@herdr-health 1\nW\t/h/.herdr/worktrees/a/work-1-x\t1234\t0\nW\t/h/.herdr/worktrees/a/b\t\t1\nnoise\n"),
        )
    }

    // ---- labels and owners ---------------------------------------------------------------------------

    private val snapshot = HerdrJson.decodeFromString(
        SessionSnapshot.serializer(),
        """{"workspaces":[{"workspace_id":"w1D","label":"shop 23"},{"workspace_id":"w1C","label":"shop 22","worktree":{"checkout_path":"/home/demo/.herdr/worktrees/shop/work-22-old-thing"}}],
           "tabs":[{"tab_id":"w1D:t1","workspace_id":"w1D","label":"agent"}],
           "panes":[{"pane_id":"w1D:p1","workspace_id":"w1D","tab_id":"w1D:t1","agent":"claude"}],
           "agents":[{"pane_id":"w1D:p1","workspace_id":"w1D","tab_id":"w1D:t1","agent":"claude","name":"New thing","agent_status":"working"}]}""",
    )

    @Test
    fun namesWhatEachProcessIs() {
        val s = HealthReader.sample(linuxOut)
        val names = ProcLabels.label(s, snapshot).associate { it.proc.pid to (it.kind to it.name) }
        assertEquals(ProcKind.Gradle to "Gradle daemon 8.11.1", names[4242])
        assertEquals(ProcKind.Emulator to "Android emulator · Pixel_8", names[5000])
        assertEquals(ProcKind.Vm to "QEMU VM · home-services-vm", names[6000])
        assertEquals(ProcKind.Agent to "claude", names[7000])
        assertEquals(ProcKind.DevServer to "Dev server · vite", names[7100])
        assertEquals(ProcKind.Node to "node · server.js", names[7200])
        assertEquals(ProcKind.KotlinDaemon to "Kotlin daemon", names[7300])
    }

    @Test
    fun owners() {
        val s = HealthReader.sample(linuxOut)
        val by = ProcLabels.label(s, snapshot).associateBy { it.proc.pid }
        // The Gradle daemon's own folder is Gradle's, but it was started from a worktree.
        assertEquals("/home/demo/.herdr/worktrees/shop/work-22-old-thing", by[4242]!!.owner.worktree)
        assertEquals("worktree work-22-old-thing", by[4242]!!.ownerLine)
        assertEquals("New thing", by[7000]!!.owner.agent)
        assertEquals("shop 23 › agent", by[7000]!!.owner.where)
        assertEquals("node server.js", by[7200]!!.ownerLine)
        assertEquals(listOf(5000 to "qemu-system-x86", 4242 to "java"), ProcLabels.targets(listOf(by[5000]!!, by[4242]!!)).map { it.pid to it.comm })
    }

    @Test
    fun worktrees() {
        assertEquals("/h/.herdr/worktrees/shop/work-22-x", ProcLabels.worktreeOf("/h/.herdr/worktrees/shop/work-22-x/app/src"))
        assertEquals("/h/.herdr/worktrees/shop/work-22-x", ProcLabels.worktreeOf("/h/.herdr/worktrees/shop/work-22-x"))
        assertNull(ProcLabels.worktreeOf("/h/.herdr/worktrees/shop"))
        assertNull(ProcLabels.worktreeOf("/h/src/shop"))
    }

    // ---- low or fine --------------------------------------------------------------------------------

    private fun sample(availPercent: Double, disks: List<Disk> = emptyList()) = HealthSample(
        at = 0, os = "Linux", uid = 1000, home = "/h", cores = 4,
        memory = Memory(1_000_000, (availPercent * 10_000).toLong()), load1 = 0.0, load5 = 0.0, load15 = 0.0, cpuBusy = 0.0,
        disks = disks, procs = listOf(Proc(1, 0, 1000, 700_000, 0.0, 0, "qemu-system-x86", "qemu-system-x86_64 -avd Pixel_8")),
    )

    @Test
    fun ramLowWithRoomToComeBack() {
        val procs = { s: HealthSample -> ProcLabels.label(s, null) }
        val low = sample(9.0)
        assertEquals(listOf("ram"), HealthRules.low(low))
        val report = assertNotNull(HealthReport.of(low, procs(low)))
        assertTrue(report.ram)
        assertEquals(listOf("Android emulator · Pixel_8" to 700_000L), report.top)
        // Between 10 and 15%: neither low nor fine, so a warning stays as it is.
        assertNull(HealthReport.of(sample(12.0), procs(sample(12.0))))
        assertEquals(HealthReport(emptyList()), HealthReport.of(sample(16.0), procs(sample(16.0))))
    }

    @Test
    fun diskLowIsATenthAtMostTwentyGb() {
        val gb = 1024L * 1024
        fun disk(sizeGb: Long, freeGb: Long) = Disk("/", "/dev/x", "ext4", sizeGb * gb, (sizeGb - freeGb) * gb, freeGb * gb)
        assertEquals(listOf("disk:/"), HealthRules.low(sample(50.0, listOf(disk(100, 9)))))
        assertTrue(HealthRules.low(sample(50.0, listOf(disk(100, 11)))).isEmpty())
        // A big disk is low under 20 GB, not under a tenth.
        assertTrue(HealthRules.low(sample(50.0, listOf(disk(2000, 50)))).isEmpty())
        assertEquals(listOf("disk:/"), HealthRules.low(sample(50.0, listOf(disk(2000, 19)))))
        // Fine again from one and a half times the limit.
        assertFalse(HealthRules.fine(sample(50.0, listOf(disk(100, 12)))))
        assertTrue(HealthRules.fine(sample(50.0, listOf(disk(100, 15)))))
        // Tiny filesystems aren't watched.
        assertTrue(HealthRules.low(sample(50.0, listOf(Disk("/small", "/dev/y", "ext4", 500_000, 499_000, 1000)))).isEmpty())
    }

    // ---- history and words --------------------------------------------------------------------------

    @Test
    fun historyFromHerdrPush() {
        val text = """{"v": 1, "points": [[1000, 67.0, 21.8, null], [1030, 70.5, 21.8, 15.8], ["bad"], [1060, 71]]}"""
        assertEquals(
            listOf(HealthPoint(1000, 67.0, 21.8, null), HealthPoint(1030, 70.5, 21.8, 15.8), HealthPoint(1060, 71.0, 0.0, null)),
            HealthClient.parseHistory(text),
        )
        assertTrue(HealthClient.parseHistory("").isEmpty())
        val points = listOf(HealthPoint(0, 1.0, 0.0, 0.0), HealthPoint(5000, 2.0, 0.0, 0.0), HealthPoint(5003, 3.0, 0.0, 0.0), HealthPoint(5020, 4.0, 0.0, 0.0))
        // An hour back from now, and points closer than ten seconds are one (the newer).
        assertEquals(listOf(3.0, 4.0), HealthHistory.trim(points, 5030).map { it.memUsed })
    }

    @Test
    fun sizes() {
        assertEquals("512 KB", Sizes.kb(512))
        assertEquals("812 MB", Sizes.kb(812L * 1024))
        assertEquals("3.4 GB", Sizes.kb((3.4 * 1024 * 1024).toLong()))
        assertEquals("250 GB", Sizes.kb(250L * 1024 * 1024))
        assertEquals("1.5 TB", Sizes.kb((1.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("3 h", Sizes.age(3 * 3600 + 5))
    }
}
