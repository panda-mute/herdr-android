package com.makefunstuff.herdr.core.board

import com.makefunstuff.herdr.core.shepherdr.Recipe
import com.makefunstuff.herdr.core.shepherdr.V2Artifact
import com.makefunstuff.herdr.core.shepherdr.V2Chat
import com.makefunstuff.herdr.core.shepherdr.V2Choice
import com.makefunstuff.herdr.core.shepherdr.V2Evidence
import com.makefunstuff.herdr.core.shepherdr.V2Need
import com.makefunstuff.herdr.core.shepherdr.V2Question
import com.makefunstuff.herdr.core.shepherdr.VoiceCall
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException

/** Which board a machine runs, as [BoardDetection] found it. */
sealed interface BoardApi {
    /** Shepherdr 3: its service on the machine's 127.0.0.1:7788 ([com.makefunstuff.herdr.core.shepherdr.BoardDetection]). */
    data class V2(val version: String) : BoardApi
    /** herdr-projects 1.x: its launcher at [path]. */
    data class V1(val path: String) : BoardApi
    data object None : BoardApi
}

/**
 * Where a task stands, in one set of words for both boards: Shepherdr 3's own (`STATUS` and `ORDER` in its
 * `core/tasks.js`), herdr-projects 1.x's folded into them ([WorkTasks.status]). In the order a list shows them: what
 * needs you first.
 */
enum class WorkStatus(val label: String, val tone: BoardTone, val needsYou: Boolean = false) {
    /** Its agent asks or waits for you. */
    Need("Needs you", BoardTone.Red, true),
    /** Finished: waits for your Accept, which merges it. */
    Ready("Ready", BoardTone.Green, true),
    /** On its way into main: nothing for you to do. */
    Merging("Merging", BoardTone.Blue),
    Work("Working", BoardTone.Amber),
    Queued("Queued", BoardTone.Pink),
    Todo("To do", BoardTone.Grey),
    Done("Done", BoardTone.Teal),
    ;

    /** [label] inside a sentence ("AND-7 ready"). */
    val word: String get() = label.lowercase()
}

/** A space: a folder with its own Assistant (Shepherdr), or the machine's one board (v1, [id] ""). */
data class WorkSpace(
    val id: String,
    val name: String,
    val machine: String = "",
    /** The machine's home space; v1's one board is one too. */
    val home: Boolean = false,
    /** Its coordinator's chat key (v2). */
    val chat: String = "",
    /** One of its tasks needs you, or its coordinator asks something. */
    val needsYou: Boolean = false,
    /** Its machine can't be reached now (v2). */
    val unreachable: Boolean = false,
    /** Its coordinator has a question open for you (v2's `asks`). */
    val asks: Boolean = false,
    /** What its lists hold (v2's `level`): 2 its own, 1 its machine's (a home space), 0 every machine's. */
    val level: Int = 2,
    /** Where its artifacts are kept (`<machine>|<folder>`), which its worktree spaces share; empty on v1. */
    val store: String = "",
    /** Its icon, an emoji the person picked. */
    val emoji: String? = null,
)

/** One task, in the same shape from both boards. */
data class WorkTask(
    /** What the board's commands take: `HA-1` (v2), `herdr-android/3` (v1). */
    val id: String,
    /** As people say it: `HA-1`, v1's short key (`AND-12`) when it has one. */
    val name: String,
    val title: String,
    /** Its space's [WorkSpace.id]. */
    val space: String,
    val status: WorkStatus,
    /** Its second line: what it does now, or what it waits for. */
    val line: String = "",
    /** Its pull request's address, when it has one. */
    val pr: String = "",
    val progress: Int? = null,
    /** Its agent's own guess of the minutes left, when it gave one. */
    val eta: Int? = null,
    /** Working: the minutes left as Shepherdr says them under its row's dot ("6m", "<1m", "2h"). */
    val left: String? = null,
    /** Ready, its Accept under way. */
    val accepting: Boolean = false,
    /** What its row says it is in: its space's name, else its task list's (a task no space works in). */
    val listName: String = "",
    /** How long since it last changed, as the board says it ("4m", "yesterday"); none on v1. */
    val age: String = "",
    /** Its agent's chat key (v2); none before its agent starts, and on v1. */
    val chat: String = "",
    /** Its agent's CLI as the board names it ("Claude Code"); none before its agent starts, and on v1. */
    val agent: String = "",
    val pinned: Boolean = false,
    /** The v1 row as the board sent it, for the screens that keep v1's look and actions; null on v2. */
    val v1: BoardTask? = null,
)

/**
 * Every space and its tasks, what needs you first; the [artifacts] agents made, on a Shepherdr that keeps them (null
 * elsewhere: v1); and everything Shepherdr says [needs] you, each once (none on v1).
 */
data class WorkOverview(
    val spaces: List<WorkSpace>,
    val tasks: List<WorkTask>,
    val artifacts: List<V2Artifact>? = null,
    val needs: List<V2Need> = emptyList(),
) {
    val needsYou: List<WorkTask> get() = tasks.filter { it.status.needsYou }
    fun tasksIn(space: String): List<WorkTask> = tasks.filter { it.space == space }
}

/**
 * A question card waiting for you: [question] in chat [chat] (a task's agent, or the coordinator).
 * A copy of a task's card shown in a space's chat names that [space], so answering it settles the copy too.
 * A plain question with no choices is answered in words ([CardAnswer.other]) as a reply to message [replyTo].
 */
data class WorkCard(
    val chat: String,
    val question: V2Question,
    val task: String = "",
    val space: String = "",
    val replyTo: String = "",
)

/**
 * Your answer to [card]: [choice] (-1 none), or [choices] on a multi card, with an optional [note] or typed [other];
 * on a card that asks several questions ([V2Question.parts]), [answers], one per question.
 */
data class CardAnswer(
    val card: WorkCard,
    val choice: Int,
    val choices: List<Int>? = null,
    val note: String? = null,
    val other: String? = null,
    val answers: List<String>? = null,
)

/** A task's details: its brief, what it said, and the cards it waits on. */
data class WorkTaskDetails(
    val task: WorkTask,
    val brief: String = "",
    /** Its summary once ready. */
    val summary: String = "",
    /** Its facts line: PR, commit, checks, review. */
    val facts: String = "",
    val plan: List<String> = emptyList(),
    val branch: String = "",
    val cards: List<WorkCard> = emptyList(),
    /** v1's `details`, for the screens that keep showing it; null on v2. */
    val v1: TaskDetails? = null,
)

/** A space's coordinator chat as it stands. */
data class CoordinatorThread(
    val space: String,
    /** The bubbles, oldest first; yours that wait to go in are [ChatLine.pending]. */
    val lines: List<ChatLine>,
    /** It is at work on something: a reply is on its way. */
    val working: Boolean = false,
    /** The question cards it waits on, its own and the copies of its tasks'. */
    val cards: List<WorkCard> = emptyList(),
    /** The chat as Shepherdr sent it (cards, replies, reactions), for a screen that shows more; null on v1. */
    val v2: V2Chat? = null,
    /** v1's coordinator as the board sent it; null on v2. */
    val v1: CoordinatorView? = null,
)

/**
 * The work board on one machine, whichever it runs: what the Board, task and Assistant screens use.
 * [V1WorkBoard] is herdr-projects 1.x through [BoardClient]; `V2WorkBoard` is Shepherdr 3's service.
 */
interface WorkBoard {
    val api: BoardApi

    /** Spaces and tasks, what needs you first. */
    suspend fun overview(): WorkOverview

    /** [overview] now and on every change: v2's live link where there is one, else read every [everyMs]. */
    fun overviews(everyMs: Long = 5_000): Flow<WorkOverview> = flow {
        while (true) {
            emit(overview())
            delay(everyMs)
        }
    }

    suspend fun details(task: WorkTask): WorkTaskDetails

    /** Approves a ready task (Shepherdr's Accept), which merges it; the board's own sentence. */
    suspend fun approve(task: WorkTask): String

    /** What the Accept card shows of a Ready [task]: its summary, changes, checks and proof. */
    suspend fun evidence(task: WorkTask): V2Evidence = throw IOException("This board has no Accept card")

    /** Marks [task] done with nothing merged; refused while it holds work main lacks. */
    suspend fun close(task: WorkTask): String

    /**
     * A new task in [space]: [title], and [brief] for its agent. On Shepherdr only a space's Assistant makes tasks,
     * so this is a message to it ([com.makefunstuff.herdr.core.shepherdr.V2Board.addRequest]); what was done, in a
     * sentence.
     */
    suspend fun addTask(space: String, title: String, brief: String): String

    suspend fun answer(answer: CardAnswer): String

    /** [space]'s coordinator chat as it is now. */
    suspend fun coordinator(space: String): CoordinatorThread

    /** [coordinator] now and on every change. */
    fun coordinatorThreads(space: String, everyMs: Long = 3_000): Flow<CoordinatorThread> = flow {
        while (true) {
            emit(coordinator(space))
            delay(everyMs)
        }
    }

    /**
     * Sends [text] to [space]'s coordinator. On v2, [cid] is the screen's id for it, which the chat's
     * message then carries (so a screen knows it came in), and [reply] the id of the message it answers.
     */
    suspend fun say(space: String, text: String, cid: String? = null, reply: String? = null): String

    /** Copies a file for [space]'s coordinator to its machine: its path there, for [sayFiles]. */
    suspend fun upload(space: String, name: String, data: ByteArray, type: String): String =
        throw IOException("This board takes no files")

    /** Hands [paths] ([upload]ed) to [space]'s coordinator, as files sent in its chat. */
    suspend fun sayFiles(space: String, paths: List<String>, cid: String? = null): String =
        throw IOException("This board takes no files")

    /** A file sent in [space]'s coordinator chat, read back by its [path]. */
    suspend fun file(space: String, path: String): ByteArray =
        throw IOException("This board takes no files")

    /**
     * A voice call with [space]'s Assistant, or with [task]'s agent when one is given (Shepherdr's Voice add-on): the
     * phone's microphone and speaker, ElevenLabs' voice agent on the machine.
     */
    suspend fun call(space: String, task: WorkTask? = null): VoiceCall = throw IOException("This board takes no calls")

    /** What was said in [audio], written down on the machine (Shepherdr's dictation, whisper.cpp there). */
    suspend fun dictate(audio: ByteArray, type: String): String = throw IOException("This board takes no dictation")

    /**
     * [task]'s own chat, its agent's ([WorkTask.chat], v2), now and on every change: in a [CoordinatorThread]'s
     * shape, its [CoordinatorThread.space] the task's space. v1 has none.
     */
    fun taskThreads(task: WorkTask, everyMs: Long = 3_000): Flow<CoordinatorThread> =
        flow { throw IOException("This board keeps no chat for a task") }

    /** Sends [text] into [task]'s own chat, to its agent; [cid] and [reply] as for [say]. */
    suspend fun tell(task: WorkTask, text: String, cid: String? = null, reply: String? = null): String =
        throw IOException("This board keeps no chat for a task")

    /** [upload] for [task]'s agent: a file (a voice message) copied for its chat, its path there for [tellFiles]. */
    suspend fun uploadFor(task: WorkTask, name: String, data: ByteArray, type: String): String =
        throw IOException("This board takes no files")

    /** [sayFiles] into [task]'s own chat, to its agent. */
    suspend fun tellFiles(task: WorkTask, paths: List<String>, cid: String? = null): String =
        throw IOException("This board takes no files")

    /** A file sent in [task]'s own chat, read back by its [path]. */
    suspend fun taskFile(task: WorkTask, path: String): ByteArray =
        throw IOException("This board takes no files")

    /** Recipe artifact [a]'s blocks at version [v] ([WorkOverview.artifacts]). */
    suspend fun recipe(a: V2Artifact, v: Int): Recipe = throw IOException(NO_ARTIFACTS)

    /** A picture beside [a]'s recipe at version [v]; null where it isn't there. */
    suspend fun artifactFile(a: V2Artifact, v: Int, path: String): ByteArray? = null

    /**
     * What you send from version [v] of [a] to its keeper: a recipe's answer ([answer]: a choice, a form, a button),
     * your words, or a note on one of its blocks ([block], its id). Whom it went to ("HA-1 agent").
     */
    suspend fun artifactSay(a: V2Artifact, v: Int, text: String, answer: Boolean = false, block: String? = null): String =
        throw IOException(NO_ARTIFACTS)
}

private const val NO_ARTIFACTS = "This board keeps no artifacts"

object WorkTasks {
    /** v1's status in v2's words: anything that waits on you is Need, except the yes to merge. */
    fun status(s: BoardStatus): WorkStatus = when (s) {
        BoardStatus.Approval -> WorkStatus.Ready
        BoardStatus.ReviewFailed, BoardStatus.PlanReady, BoardStatus.PickOne, BoardStatus.Asks, BoardStatus.AsksOk,
        BoardStatus.NeedsLogin, BoardStatus.Stopped, BoardStatus.Error -> WorkStatus.Need
        BoardStatus.Working, BoardStatus.Reviewing -> WorkStatus.Work
        BoardStatus.Queued -> WorkStatus.Queued
        BoardStatus.Todo -> WorkStatus.Todo
        BoardStatus.Done, BoardStatus.Archived -> WorkStatus.Done
    }

    /**
     * [t] in v1's row shape, for what still reads a board as rows (the brief, the Needs you list, the
     * widget's count): v1's own row where it is one; a v2 task as near as v1's words say it (merging reads as working).
     */
    fun row(t: WorkTask): BoardTask = t.v1 ?: BoardTask(
        id = t.id,
        key = t.name,
        title = t.title,
        state = when (t.status) {
            WorkStatus.Need, WorkStatus.Ready -> "blocked"
            WorkStatus.Work, WorkStatus.Merging -> "working"
            WorkStatus.Done -> "done"
            WorkStatus.Queued, WorkStatus.Todo -> "idle"
        },
        stage = when (t.status) {
            WorkStatus.Ready -> "approval"
            WorkStatus.Queued -> "queued"
            WorkStatus.Merging -> "merging"
            else -> ""
        },
        kind = when (t.status) {
            WorkStatus.Need -> "question"
            WorkStatus.Ready -> "permission"
            else -> ""
        },
        agent = t.agent,
        msg = t.line,
        progress = t.progress,
        eta = t.eta,
        research = false,
        pr = t.pr,
        archived = false,
        pinned = t.pinned,
        reviewRound = 0,
        reviewPassed = null,
        approvalBlocked = false,
    )

    /** What needs you first, then the rest in [WorkStatus] order; pinned ones first within each; otherwise as given. */
    fun ordered(tasks: List<WorkTask>): List<WorkTask> =
        tasks.sortedWith(compareBy<WorkTask> { it.status.ordinal }.thenBy { if (it.pinned) 0 else 1 })
}

/** herdr-projects 1.x as a [WorkBoard]: [board] as the app uses it today, nothing changed. Its one board is space "". */
class V1WorkBoard(private val board: BoardClient, private val path: String = "") : WorkBoard {
    override val api: BoardApi get() = BoardApi.V1(path)

    override suspend fun overview(): WorkOverview {
        val tasks = board.tasks().map(::task)
        return WorkOverview(listOf(WorkSpace(id = "", name = "Board", home = true, needsYou = tasks.any { it.status.needsYou })), WorkTasks.ordered(tasks))
    }

    override suspend fun details(task: WorkTask): WorkTaskDetails {
        val d = board.details(task.id)
        val plan = d.plan.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val t = task.v1
        val cards = buildList {
            if (t?.status == BoardStatus.PlanReady) add(WorkCard(task.id, V1Cards.plan(task, plan), task.id))
            if (t?.status == BoardStatus.PickOne && d.attempts.isNotEmpty()) add(WorkCard(task.id, V1Cards.pick(task, d.attempts), task.id))
        }
        return WorkTaskDetails(task, brief = d.description, summary = d.msg, plan = plan, branch = d.branch, cards = cards, v1 = d)
    }

    override suspend fun approve(task: WorkTask): String = board.act(BoardAction.Approve, task.id)

    override suspend fun close(task: WorkTask): String = throw IOException("herdr-projects has no close: archive the task instead")

    override suspend fun addTask(space: String, title: String, brief: String): String = board.add(title, description = brief)

    override suspend fun answer(answer: CardAnswer): String {
        val id = answer.card.task.ifEmpty { answer.card.chat }
        return when (answer.card.question.id) {
            V1Cards.PLAN -> if (answer.choice == 0) board.act(BoardAction.ApprovePlan, id)
            else board.act(BoardAction.SendPlanBack, id, answer.note.orEmpty().ifEmpty { answer.other.orEmpty() })
            V1Cards.PICK -> board.act(BoardAction.Pick, id, letter = V1Cards.letter(answer.card.question, answer.choice))
            else -> throw IOException("herdr-projects has no card ${answer.card.question.id}")
        }
    }

    override suspend fun coordinator(space: String): CoordinatorThread {
        val list = board.list()
        val view = list.coordinator ?: return CoordinatorThread(space, emptyList())
        return CoordinatorThread(space, CoordinatorChat.lines(view), working = view.busy, v1 = view)
    }

    /** Read every [everyMs], each read joined to what came before ([CoordinatorChat.merge]): the board sends the last few lines. */
    override fun coordinatorThreads(space: String, everyMs: Long): Flow<CoordinatorThread> = flow {
        var talk = emptyList<Said>()
        while (true) {
            val view = board.list().coordinator
            if (view == null) emit(CoordinatorThread(space, emptyList()))
            else {
                talk = CoordinatorChat.merge(talk, view.talk)
                emit(CoordinatorThread(space, CoordinatorChat.lines(view, talk), working = view.busy, v1 = view))
            }
            delay(everyMs)
        }
    }

    override suspend fun say(space: String, text: String, cid: String?, reply: String?): String = board.send(text)

    private fun task(t: BoardTask) = WorkTask(
        id = t.id,
        name = t.name,
        title = t.title,
        space = "",
        status = WorkTasks.status(t.status),
        // What its badge's one word leaves out ("Review failed") before what it does now.
        line = listOfNotNull(t.status.detail, TaskLine.line(t.msg).ifEmpty { null }).joinToString(" · "),
        pr = t.pr,
        progress = t.progress,
        eta = t.eta,
        pinned = t.pinned,
        v1 = t,
    )
}

/** v1's moments that wait on a pick, as cards: a plan to approve, and the attempts to pick from. */
object V1Cards {
    const val PLAN = "v1:plan"
    const val PICK = "v1:pick"

    fun plan(task: WorkTask, steps: List<String>) = V2Question(
        id = PLAN,
        title = "Plan for ${task.name}",
        kind = "Plan",
        steps = steps,
        choices = listOf(V2Choice("Approve the plan"), V2Choice("Send it back with a note")),
        noteRequiredFor = 1,
        to = task.name,
    )

    fun pick(task: WorkTask, attempts: List<Attempt>) = V2Question(
        id = PICK,
        title = "Pick an attempt for ${task.name}",
        kind = "Form",
        choices = attempts.map { a ->
            V2Choice("${a.letter} · ${a.agent}".trim(' ', '·'), a.stat.ifEmpty { null })
        },
        noNote = true,
        to = task.name,
    )

    /** The attempt's letter of [choice] on a [pick] card. */
    fun letter(q: V2Question, choice: Int): String =
        q.choices.getOrNull(choice)?.label?.substringBefore(" · ")?.trim() ?: throw IOException("No such attempt")
}
