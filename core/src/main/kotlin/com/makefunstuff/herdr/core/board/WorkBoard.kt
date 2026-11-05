package com.makefunstuff.herdr.core.board

import com.makefunstuff.herdr.core.shepherdr.AutopilotSettings
import com.makefunstuff.herdr.core.shepherdr.Recipe
import com.makefunstuff.herdr.core.shepherdr.TaskFolder
import com.makefunstuff.herdr.core.shepherdr.V2Artifact
import com.makefunstuff.herdr.core.shepherdr.V2Chat
import com.makefunstuff.herdr.core.shepherdr.V2Evidence
import com.makefunstuff.herdr.core.shepherdr.V2ModelMenu
import com.makefunstuff.herdr.core.shepherdr.V2ModelPick
import com.makefunstuff.herdr.core.shepherdr.V2Need
import com.makefunstuff.herdr.core.shepherdr.V2Pilot
import com.makefunstuff.herdr.core.shepherdr.V2PrAnswer
import com.makefunstuff.herdr.core.shepherdr.V2Question
import com.makefunstuff.herdr.core.shepherdr.V2SecretAsk
import com.makefunstuff.herdr.core.shepherdr.VoiceCall
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException

/** Which board a machine runs, as [BoardDetection] found it. */
sealed interface BoardApi {
    /** Shepherdr 3: its service on the machine's 127.0.0.1:7788 ([com.makefunstuff.herdr.core.shepherdr.BoardDetection]). */
    data class V2(val version: String) : BoardApi
    data object None : BoardApi
}

/** The board's colours for a task's status (docs/colours.md), as 0xRRGGBB. */
enum class BoardTone(val rgb: Long) {
    Amber(0xE2B64A), Red(0xEF7B70), Green(0x6CC993), Blue(0x7AA2F7), Teal(0x4DC4B8), Pink(0xE58FC0), Grey(0x7A847F),
}

/**
 * Where a task stands, in Shepherdr 3's own words (`STATUS` and `ORDER` in its `core/tasks.js`). In the order a list
 * shows them: what needs you first.
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

/** A space: a folder with its own Assistant. */
data class WorkSpace(
    val id: String,
    val name: String,
    val machine: String = "",
    /** The machine's home space. */
    val home: Boolean = false,
    /** Its coordinator's chat key. */
    val chat: String = "",
    /** One of its tasks needs you, or its coordinator asks something. */
    val needsYou: Boolean = false,
    /** Its machine can't be reached now. */
    val unreachable: Boolean = false,
    /** Its coordinator has a question open for you (Shepherdr's `asks`). */
    val asks: Boolean = false,
    /** What its lists hold (Shepherdr's `level`): 2 its own, 1 its machine's (a home space), 0 every machine's. */
    val level: Int = 2,
    /** Where its artifacts are kept (`<machine>|<folder>`), which its worktree spaces share. */
    val store: String = "",
    /** Its icon, an emoji the person picked. */
    val emoji: String? = null,
    /** Its Autopilot, switched in its Assistant's header; none where it can't be switched from here. */
    val pilot: V2Pilot? = null,
    /** Its tabs as the bar shows them, the Assistant's first ([TabMenus]). */
    val tabs: List<WorkTab> = emptyList(),
)

/** One task on Shepherdr's board. */
data class WorkTask(
    /** What the board's commands take: `HA-1`. */
    val id: String,
    /** As people say it: `HA-1`. */
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
    /** How long since it last changed, as the board says it ("4m", "yesterday"). */
    val age: String = "",
    /** Its agent's chat key; none before its agent starts. */
    val chat: String = "",
    /** Its agent's CLI as the board names it ("Claude Code"); none before its agent starts. */
    val agent: String = "",
    val pinned: Boolean = false,
)

/**
 * Every space and its tasks, what needs you first; the [artifacts] agents made, on a Shepherdr that keeps them (null
 * elsewhere); and everything Shepherdr says [needs] you, each once.
 */
data class WorkOverview(
    val spaces: List<WorkSpace>,
    val tasks: List<WorkTask>,
    val artifacts: List<V2Artifact>? = null,
    val needs: List<V2Need> = emptyList(),
    /** Settings › Autopilot on this machine; null where it has none. */
    val autopilot: AutopilotSettings? = null,
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
)

/** A bubble in the chat. */
data class ChatLine(val you: Boolean, val text: String, val pending: Boolean = false)

/** A space's coordinator chat as it stands. */
data class CoordinatorThread(
    val space: String,
    /** The bubbles, oldest first; yours that wait to go in are [ChatLine.pending]. */
    val lines: List<ChatLine>,
    /** It is at work on something: a reply is on its way. */
    val working: Boolean = false,
    /** The question cards it waits on, its own and the copies of its tasks'. */
    val cards: List<WorkCard> = emptyList(),
    /** The chat as Shepherdr sent it (cards, replies, reactions), for a screen that shows more. */
    val v2: V2Chat? = null,
)

/**
 * The work board on one machine: what the Board, task and Assistant screens use. `V2WorkBoard` is Shepherdr 3's
 * service.
 */
interface WorkBoard {
    val api: BoardApi

    /** Spaces and tasks, what needs you first. */
    suspend fun overview(): WorkOverview

    /** [overview] now and on every change: Shepherdr's live link where there is one, else read every [everyMs]. */
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

    /** Where [task] works: its worktree, else its list's folder (the task view's Files and Changes read it). */
    suspend fun folder(task: WorkTask): TaskFolder = throw IOException("This board doesn't say where a task works")

    /** [task]'s pull request with its checks, as GitHub says it now (the task view's Pull request). */
    suspend fun pullRequest(task: WorkTask): V2PrAnswer = throw IOException("This board doesn't read pull requests")

    /** Marks [task] done with nothing merged; refused while it holds work main lacks. */
    suspend fun close(task: WorkTask): String

    /**
     * A new task in [space]: [title], and [brief] for its agent. On Shepherdr only a space's Assistant makes tasks,
     * so this is a message to it ([com.makefunstuff.herdr.core.shepherdr.V2Board.addRequest]); what was done, in a
     * sentence.
     */
    suspend fun addTask(space: String, title: String, brief: String): String

    suspend fun answer(answer: CardAnswer): String

    /**
     * Keeps [value] for [ask], an agent's secret ask in [chat], at [level]: into the machine's vault, the agent told
     * its name only. Where it is kept, in words ("Kept for This space").
     */
    suspend fun keepSecret(chat: String, ask: V2SecretAsk, level: String, value: String): String =
        throw IOException("This board can't keep secrets")

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
     * Sends [text] to [space]'s coordinator. [cid] is the screen's id for it, which the chat's
     * message then carries (so a screen knows it came in), and [reply] the id of the message it answers.
     */
    suspend fun say(space: String, text: String, cid: String? = null, reply: String? = null): String

    /**
     * Adds [emoji] to message [message] of [space]'s coordinator chat, or of [task]'s own chat when one is given; a
     * reaction already yours is taken back (Shepherdr's toggle).
     */
    suspend fun react(space: String, task: WorkTask?, message: String, emoji: String): Unit =
        throw IOException("This board takes no reactions")

    /** Reads the page of [space]'s coordinator chat (or [task]'s) before what it shows; the chat then comes again, longer. */
    suspend fun earlier(space: String, task: WorkTask?): Unit = throw IOException("This board reads no earlier pages")

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

    /** Switches [space]'s Autopilot on or off (its Assistant's header switch); herdr-task's refusal in the exception. */
    suspend fun autopilot(space: String, on: Boolean): Unit = throw IOException("This board has no Autopilot")

    /** Sets one of Settings › Autopilot's rows ([com.makefunstuff.herdr.core.shepherdr.Autopilot.EVERY] and the rest). */
    suspend fun autopilotSetting(key: String, value: String): Unit = throw IOException("This board has no Autopilot")

    /** What was said in [audio], written down on the machine (Shepherdr's dictation, whisper.cpp there). */
    suspend fun dictate(audio: ByteArray, type: String): String = throw IOException("This board takes no dictation")

    /** Whether [dictate] works here: the machine has whisper.cpp. */
    suspend fun canDictate(): Boolean = false

    /**
     * [task]'s own chat, its agent's ([WorkTask.chat]), now and on every change: in a [CoordinatorThread]'s
     * shape, its [CoordinatorThread.space] the task's space.
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

    /** The agents a tab of [space] can switch to ([TabMenus.switchChoices]). */
    suspend fun tabAgents(space: String): List<WorkAgentChoice> = throw IOException(NO_TABS)

    /** Names [tab] of [space] [name]; an empty name unnames it. */
    suspend fun renameTab(space: String, tab: WorkTab, name: String): Unit = throw IOException(NO_TABS)

    /** Closes the window's tab [id]: its programs end. */
    suspend fun closeTab(id: String): Unit = throw IOException(NO_TABS)

    /** Restarts [tab]'s agent in its tab: as it runs now, or as agent [kind] (Switch agent). */
    suspend fun restartTab(tab: WorkTab, kind: String? = null): Unit = throw IOException(NO_TABS)

    /** The model menu of the agent in [chat]. */
    suspend fun modelMenu(chat: String): V2ModelMenu = throw IOException(NO_TABS)

    /** Restarts the agent in [chat] on [pick], as the model menu's Restart with…. */
    suspend fun restartWith(chat: String, pick: V2ModelPick, level: String?): Unit = throw IOException(NO_TABS)

    /** How many workers [space]'s Assistant started are still running. */
    suspend fun assistantWorkers(space: String): Int = throw IOException(NO_TABS)

    /** Closes [space]'s Assistant: [stopWorkers] ends the workers it started first, else they finish. */
    suspend fun closeAssistant(space: String, stopWorkers: Boolean): Unit = throw IOException(NO_TABS)

    /** Closes [space]: its tabs close and every program in them ends. */
    suspend fun closeSpace(space: String): Unit = throw IOException(NO_TABS)
}

private const val NO_TABS = "This board has no tabs"
private const val NO_ARTIFACTS = "This board keeps no artifacts"

object WorkTasks {
    /** What needs you first, then the rest in [WorkStatus] order; pinned ones first within each; otherwise as given. */
    fun ordered(tasks: List<WorkTask>): List<WorkTask> =
        tasks.sortedWith(compareBy<WorkTask> { it.status.ordinal }.thenBy { if (it.pinned) 0 else 1 })
}
