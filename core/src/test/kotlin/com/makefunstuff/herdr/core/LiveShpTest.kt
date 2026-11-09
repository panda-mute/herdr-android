package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.CardAnswer
import com.makefunstuff.herdr.core.board.WorkCard
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.shepherdr.V2Question
import com.makefunstuff.herdr.core.shp.ShpBoard
import com.makefunstuff.herdr.core.shp.ShpClient
import com.makefunstuff.herdr.core.shp.ShpRunner
import com.makefunstuff.herdr.core.ssh.CommandResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The app's shp client against a real simple-herdr-projects on a scratch store: the spaces and tasks `shp list
 * --json` gives, a task's brief from `shp show`, and an ask that clears only when `shp say`'s `hc send` is
 * confirmed, and an accept that shp refuses on a dirty worktree and then merges. Runs only with SHP_BIN set to a built shp (`go build -o /tmp/shp` in simple-herdr-projects).
 */
class LiveShpTest {
    private val shp = System.getenv("SHP_BIN")

    @Test
    fun listsShowsAndSaysAgainstARealShp() = runTest {
        assumeTrue(shp != null && File(shp!!).canExecute(), "set SHP_BIN to a built shp to run")

        val root = File.createTempFile("shp-live", "").let { it.delete(); it.mkdirs(); it }
        val home = File(root, "home").apply { mkdirs() }
        val bin = File(root, "bin").apply { mkdirs() }
        // shp itself, on the scratch machine's PATH.
        File(bin, "shp").apply {
            writeText("#!/bin/sh\nexec ${'"'}${shp}${'"'} \"\$@\"\n")
            setExecutable(true)
        }
        // The machine's hc, as shp calls it: a live worker, a send that succeeds unless the fail file is there.
        File(bin, "hc").apply {
            writeText(
                """
                #!/bin/sh
                case "${'$'}1" in
                  ls) printf '%s\n' '{"terminals":[{"address":"t1","state":"live"}]}'; exit 0 ;;
                  send) if [ -e "${'$'}HOME/hc-fails" ]; then echo 'hc send: no terminal' >&2; exit 1; fi
                        echo "${'$'}*" >> "${'$'}HOME/hc.sent"; exit 0 ;;
                esac
                exit 1
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
        val repo = File(root, "repo").apply { mkdirs() }
        run("git init -b main -q && git -c user.name=t -c user.email=t@t commit --allow-empty -qm init", repo, home, bin)

        // A real store: a space and its tasks, through shp itself.
        run("shp space add app --folder ${repo.path} --merge local --brief -", root, home, bin, stdin = "The app space.\n")
        run("shp add 'Show the machine on the task card' --space app --brief -", root, home, bin, stdin = "Put the machine under the title.\n")
        run("shp add 'Ship the smaller rows' --space app", root, home, bin)
        val needs = File(home, ".herdr/simple/spaces/app/APP-1.md")
        // A worker that asked: shp's own files, as a worker writes them (the `ask` command only runs a working one).
        needs.writeText(
            """
            ---
            id: APP-1
            title: Show the machine on the task card
            status: needs
            agent: []
            asks: [{"id": "a1", "text": "Should the card show the machine too?", "time": "2026-11-07T10:00:00+00:00"}]
            progress: 55
            step: Waiting for your answer
            left: 10m
            ---

            Put the machine under the title.
            """.trimIndent() + "\n",
        )

        val client = ShpClient(shpRunner(root, home, bin))
        val board = ShpBoard(client, "scratch", "scratch")

        val o = board.overview()
        assertEquals(listOf("app"), o.spaces.map { it.id })
        assertEquals(listOf("APP-1", "APP-2"), o.tasks.map { it.id })
        assertEquals(WorkStatus.Need, o.tasks.first().status)
        assertEquals("Should the card show the machine too?", o.tasks.first().line)
        assertEquals("10m", o.tasks.first().left)

        val d = board.details(o.tasks.first())
        assertEquals("Put the machine under the title.", d.brief)
        assertEquals("a1", d.cards.single().replyTo)

        // The ask clears once hc confirms the send, as shp's own words then show.
        val card = WorkCard(chat = "APP-1", question = V2Question(id = "a1", title = "Should the card show the machine too?"), task = "APP-1", replyTo = "a1")
        board.answer(CardAnswer(card, -1, other = "Only with several machines"))
        val after = board.overview().tasks.first { it.id == "APP-1" }
        assertEquals(WorkStatus.Work, after.status)
        assertEquals("Waiting for your answer", after.line)
        assertTrue(File(home, "hc.sent").readText().contains("Only with several machines"))

        // And a send that fails leaves the ask: shp never clears it on its own.
        needs.writeText(
            needs.readText().replace("status: working", "status: needs")
                .replace("asks: []", """asks: [{"id": "a2", "text": "And the branch?", "time": "2026-11-07T10:05:00+00:00"}]"""),
        )
        File(home, "hc-fails").createNewFile()
        val again = WorkCard(chat = "APP-1", question = V2Question(id = "a2", title = "And the branch?"), task = "APP-1", replyTo = "a2")
        val refusal = assertFailsWith<java.io.IOException> { board.answer(CardAnswer(again, -1, other = "task/app")) }
        assertTrue(refusal.message!!.contains("still needs you"))
        val kept = board.overview().tasks.first { it.id == "APP-1" }
        assertEquals(WorkStatus.Need, kept.status)
        assertTrue(kept.line.contains("And the branch?"))

        // Accept: a reviewed, ready task on its own branch, as `shp review`/`ready` leave it.
        val worktree = File(root, "wt-app-2")
        run("git worktree add -q -b task/app-2 ${worktree.path} && cd ${worktree.path} && echo rows > rows.txt && git add rows.txt && " +
            "git -c user.name=t -c user.email=t@t commit -qm 'Smaller rows'", repo, home, bin)
        val reviewed = output("git rev-parse HEAD", worktree, home, bin)
        val ready = File(home, ".herdr/simple/spaces/app/APP-2.md")
        val fields = mapOf(
            "status" to "ready", "branch" to "task/app-2", "worktree" to worktree.path, "review" to "2",
            "reviewed_commit" to reviewed, "blocking" to "0", "summary" to "The rows are smaller.",
        )
        ready.writeText(
            fields.entries.fold(ready.readText()) { text, (k, v) ->
                val line = Regex("(?m)^$k: .*$")
                if (line.containsMatchIn(text)) text.replace(line, "$k: $v") else text.replaceFirst("---\n", "---\n$k: $v\n")
            },
        )
        val toAccept = board.overview().tasks.first { it.id == "APP-2" }
        assertEquals(WorkStatus.Ready, toAccept.status)
        // A dirty worktree: shp refuses and says why, and the task stays ready.
        File(worktree, "stray.txt").writeText("not committed\n")
        val dirty = assertFailsWith<java.io.IOException> { board.approve(toAccept) }
        assertTrue(dirty.message!!.contains("uncommitted changes"), dirty.message)
        assertEquals(WorkStatus.Ready, board.overview().tasks.first { it.id == "APP-2" }.status)
        File(worktree, "stray.txt").delete()
        // Then it merges into main and is done.
        board.approve(toAccept)
        assertEquals(WorkStatus.Done, board.overview().tasks.first { it.id == "APP-2" }.status)
        run("git merge-base --is-ancestor $reviewed main", repo, home, bin)

        println("shp $shp; store $home; say sent: " + File(home, "hc.sent").readText().trim().lines() +
            "; refused while dirty: ${dirty.message}; main: " + output("git log --oneline -2 main", repo, home, bin).lines())
    }

    /** Runs shp and hc as the app's connection would, in the scratch HOME. */
    private fun shpRunner(root: File, home: File, bin: File) = ShpRunner { command, stdin, _ ->
        val process = ProcessBuilder("sh", "-c", command).directory(root).redirectErrorStream(false).apply {
            environment()["HOME"] = home.path
            environment()["PATH"] = bin.path + ":" + System.getenv("PATH")
        }.start()
        if (stdin != null) process.outputStream.use { it.write(stdin.toByteArray()) } else process.outputStream.close()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = process.errorStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor(60, TimeUnit.SECONDS)
        CommandResult(process.exitValue(), out, err)
    }

    private fun output(command: String, dir: File, home: File, bin: File): String {
        val p = ProcessBuilder("sh", "-c", command).directory(dir).apply {
            environment()["HOME"] = home.path
            environment()["PATH"] = bin.path + ":" + System.getenv("PATH")
        }.start()
        p.outputStream.close()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        p.waitFor(60, TimeUnit.SECONDS)
        check(p.exitValue() == 0) { "$command failed" }
        return text
    }

    private fun run(command: String, dir: File, home: File, bin: File, stdin: String? = null) {
        val p = ProcessBuilder("sh", "-c", command).directory(dir).redirectErrorStream(true).apply {
            environment()["HOME"] = home.path
            environment()["PATH"] = bin.path + ":" + System.getenv("PATH")
        }.start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) } else p.outputStream.close()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor(60, TimeUnit.SECONDS)
        check(p.exitValue() == 0) { "$command failed: $text" }
    }
}
