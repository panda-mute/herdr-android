package com.makefunstuff.herdr.core.shp

import com.makefunstuff.herdr.core.board.BoardApi
import com.makefunstuff.herdr.core.board.CardAnswer
import com.makefunstuff.herdr.core.board.ChatLine
import com.makefunstuff.herdr.core.board.CoordinatorThread
import com.makefunstuff.herdr.core.board.WorkBoard
import com.makefunstuff.herdr.core.board.WorkCard
import com.makefunstuff.herdr.core.board.WorkOverview
import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.board.WorkTaskDetails
import com.makefunstuff.herdr.core.board.WorkTasks
import com.makefunstuff.herdr.core.shepherdr.V2Question
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException

/**
 * simple-herdr-projects as a [WorkBoard], over the machine's SSH connection: it lists, shows, accepts
 * and says, and reads a task's chat from its worker's terminal with `hc read`. There is no service,
 * Assistant, artifact store or Autopilot here, so those screens stay Shepherdr's.
 */
class ShpBoard(private val client: ShpClient, private val machineId: String, private val label: String = "") : WorkBoard {
    override val api: BoardApi get() = BoardApi.Shp

    override suspend fun overview(): WorkOverview {
        val spaces = client.spaces()
        val workSpaces = spaces.map { (name, tasks) ->
            WorkSpace(
                id = name,
                name = name,
                machine = machineId,
                needsYou = tasks.any { status(it.status).needsYou },
                store = "shp:$name",
            )
        }
        val tasks = spaces.flatMap { (name, list) -> list.map { it.toTask(name) } }
        return WorkOverview(workSpaces, WorkTasks.ordered(tasks))
    }

    override suspend fun details(task: WorkTask): WorkTaskDetails {
        val file = client.show(task.id)
        val now = file.head.toTask(task.space)
        return WorkTaskDetails(
            task = now,
            brief = file.brief,
            summary = file.head.summary.orEmpty(),
            facts = facts(file.head),
            branch = file.head.branch,
            cards = file.head.askList.map { ask ->
                WorkCard(
                    chat = now.id,
                    question = V2Question(id = ask.id, title = ask.text),
                    task = now.id,
                    space = now.space,
                    replyTo = ask.id,
                )
            },
        )
    }

    override suspend fun approve(task: WorkTask): String = client.accept(task.id)

    /** Answers an ask with `shp say`: shp closes it only once hc confirms the send. */
    override suspend fun answer(answer: CardAnswer): String {
        val card = answer.card
        val text = (answer.other ?: answer.note)?.trim().orEmpty()
        if (text.isEmpty()) throw IOException("Write an answer first")
        val id = card.task.ifEmpty { card.chat }
        if (id.isEmpty()) throw IOException("This question has no task to answer")
        client.say(id, text, ask = card.replyTo.ifEmpty { null })
        return ""
    }

    /** A message to the task's worker, through `shp say` (and so `hc send`). */
    override suspend fun tell(task: WorkTask, text: String, cid: String?, reply: String?): String {
        client.say(task.id, text)
        return ""
    }

    /** The worker's screen and history, read with `hc read` while the chat is open. */
    override fun taskThreads(task: WorkTask, everyMs: Long): Flow<CoordinatorThread> = flow {
        while (true) {
            val lines = if (task.status == WorkStatus.Work || task.status == WorkStatus.Need) client.chatLines(task.id) else emptyList()
            emit(CoordinatorThread(space = task.space, lines = lines.map { ChatLine(you = false, text = it) }, working = task.status == WorkStatus.Work))
            delay(everyMs)
        }
    }

    override suspend fun say(space: String, text: String, cid: String?, reply: String?): String =
        throw IOException("simple-herdr-projects has no Assistant chat on the phone")

    override suspend fun coordinator(space: String): CoordinatorThread =
        throw IOException("simple-herdr-projects has no Assistant chat on the phone")

    override suspend fun addTask(space: String, title: String, brief: String): String =
        throw IOException("Add a task with shp on $label")

    override suspend fun close(task: WorkTask): String =
        throw IOException("simple-herdr-projects can't close a task from the phone")

    /** Its header's facts, as the task view shows them. */
    private fun facts(head: ShpTask): String = listOfNotNull(
        head.review?.let { "Review $it" },
        head.blocking?.let { "blocking $it" },
        head.reviewedCommit?.takeIf { it.isNotBlank() }?.let { "commit ${it.take(7)}" },
    ).joinToString(" · ")

    private fun ShpTask.toTask(space: String): WorkTask {
        val state = status(status)
        val known = status in STATUS_WORDS
        return WorkTask(
            id = id,
            name = id,
            title = title.ifEmpty { id },
            space = space,
            status = state,
            line = when {
                !known -> "shp says it is $status"
                askList.isNotEmpty() -> askList.joinToString(" · ") { it.text }
                !step.isNullOrBlank() -> step
                state == WorkStatus.Ready -> summary.orEmpty()
                else -> ""
            },
            pr = pr.orEmpty(),
            progress = progress?.takeIf { it in 0..100 },
            left = left?.takeIf { it.isNotBlank() },
            listName = space,
            // A worker's chat is the one its terminal has: only a working or waiting task is open to it.
            chat = id.takeIf { state == WorkStatus.Work || state == WorkStatus.Need }.orEmpty(),
        )
    }

    companion object {
        private val STATUS_WORDS = setOf("todo", "queued", "working", "needs", "ready", "merging", "done")

        /** shp's status words (docs/design.md §2) as the board's; an unknown one stays to do, saying which. */
        fun status(word: String): WorkStatus = when (word) {
            "todo" -> WorkStatus.Todo
            "queued" -> WorkStatus.Queued
            "working" -> WorkStatus.Work
            "needs" -> WorkStatus.Need
            "ready" -> WorkStatus.Ready
            "merging" -> WorkStatus.Merging
            "done" -> WorkStatus.Done
            else -> WorkStatus.Todo
        }
    }
}
