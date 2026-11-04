package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.WorkTasks
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.keys.KeyImport
import com.makefunstuff.herdr.core.ssh.Machine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the Board and task screens read from a real Shepherdr 3, over SSH as the app reaches it: the
 * board from the live link, each task's details and the cards it waits on, and the rows the brief reads.
 * Read only: nothing is merged, closed, added or answered. Runs only with HERDR_LIVE_KEY and
 * HERDR_LIVE_SHEPHERDR (the machine, `user@host[:port]`) set.
 */
class LiveWorkBoardScreensTest {
    private val keyPath = System.getenv("HERDR_LIVE_KEY")
    private val target = System.getenv("HERDR_LIVE_SHEPHERDR")

    @Test
    fun theScreensReadARealShepherdr() {
        assumeTrue(keyPath != null && target != null, "set HERDR_LIVE_KEY and HERDR_LIVE_SHEPHERDR to run")
        val identity = KeyImport.fromPrivateKey(File(keyPath!!).readText(), "test")
        val (user, host, port) = Machine.parseTarget(target!!)!!
        val machine = Machine("live", "live", host, port, user ?: "root", session = System.getenv("HERDR_LIVE_SESSION") ?: "default")
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val session = HostSession(machine, { identity }, { null }, scope)
            try {
                session.start()
                val s = withTimeout(60_000) { session.state.first { it.online && it.boardChecked } }
                assertTrue(s.onShepherdr && s.hasWorkBoard, "the Board screen takes the v2 way there")
                val board = session.awaitWorkBoard()
                // The Board screen follows the live link: its first snapshot comes at once.
                val o = withTimeout(30_000) { board.overviews().take(1).toList().single() }
                println("spaces: " + o.spaces.joinToString { "${it.name}${if (it.needsYou) " (needs you)" else ""}" })
                println("needs you first: " + o.tasks.takeWhile { it.status.needsYou }.joinToString { "${it.name}:${it.status.label}" })
                assertEquals(o.tasks, WorkTasks.ordered(o.tasks))
                o.tasks.forEach { t -> println("  ${t.name} ${t.status.label} [${t.status.tone}] ${t.title} · ${t.line} ${t.pr}") }
                // Each open task's details, as the task screen reads them: brief, summary, PR, its cards.
                o.tasks.filter { it.chat.isNotEmpty() && it.status.name != "Done" }.forEach { t ->
                    val d = board.details(t)
                    assertEquals(t.id, d.task.id)
                    println(
                        "${t.name}: brief ${d.brief.length} chars, summary ${d.summary.length}, facts ${d.facts.length}, " +
                            "${d.cards.size} cards" + d.cards.joinToString("") { c ->
                                " [${c.question.kind}${if (c.question.multi) " multi" else ""}${if (c.question.other) " other" else ""}: ${c.question.title.take(50)} · ${c.question.choices.size} choices]"
                            },
                    )
                }
                // The brief and the Board tab read the same tasks as rows.
                val rows = o.tasks.map(WorkTasks::row)
                assertEquals(o.tasks.count { it.status.needsYou }, rows.count { it.needsYou })
            } finally {
                session.stop()
                scope.cancel()
            }
        }
    }
}
