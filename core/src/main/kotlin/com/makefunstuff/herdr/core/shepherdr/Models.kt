package com.makefunstuff.herdr.core.shepherdr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Shepherdr 3's view model as its service sends it to the web app (`web/shared/types.ts` in its checkout, built by
 * `service/compat.ts`): only the parts the app uses. The shapes keep the web app's names, which began as 2.1's, hence
 * the `V2` on them; the service and its data are 3.x's. Unknown fields are skipped and an unknown status reads as the
 * field's default, so a newer service still parses.
 */
val ShepherdrJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
}

/** A space's or an agent's mark (`Status`): red needs you, green ready, amber working, grey the rest. */
@Serializable
enum class V2Status {
    @SerialName("need") Need,
    @SerialName("ready") Ready,
    @SerialName("merging") Merging,
    @SerialName("work") Work,
    @SerialName("idle") Idle,
    @SerialName("queued") Queued,
    @SerialName("todo") Todo,
    @SerialName("done") Done,
    @SerialName("off") Off,
}

/** Where a task stands (`TaskStatus`). */
@Serializable
enum class V2TaskStatus {
    /** Its agent asks or waits for you. */
    @SerialName("need") Need,
    /** Finished, waits for you to merge it. */
    @SerialName("ready") Ready,
    /** On its way into main: nothing for you to do. */
    @SerialName("merging") Merging,
    @SerialName("work") Work,
    /** Waits for other tasks ([V2Task.after]). */
    @SerialName("queued") Queued,
    /** Its agent waits for a go. */
    @SerialName("todo") Todo,
    @SerialName("done") Done,
}

/** `GET /api/health`: which Shepherdr service answers, and whether it reaches herdr-core. */
@Serializable
data class V2Version(val ok: Boolean = true, val version: String = "", val core: Boolean = true) {
    /** The major version, 0 when it can't be read. */
    val major: Int get() = version.trim().removePrefix("v").substringBefore('.').toIntOrNull() ?: 0

    /** The minor version, 0 when it can't be read. */
    val minor: Int get() = version.trim().removePrefix("v").split('.').getOrNull(1)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0
}

/** Where a space's artifacts are kept (`Place.store`): [key] is `<machine>|<folder>`, as [V2Artifact.store] names it. */
@Serializable
data class V2Store(val folder: String = "", val name: String = "", val key: String = "")

/** A space's Assistant's own tab (`Place.assistantTab`): [live] while its agent runs. */
@Serializable
data class V2AssistantTab(val name: String = "Assistant", val state: String = "idle", val live: Boolean = false, val cli: String? = null)

/** A space (`Place`): a folder on a machine with its own Assistant, whose chat key is [chat] (`p:<space id>`). */
@Serializable
data class V2Place(
    val id: String,
    /** As the sidebar names it ("herdr-android"). */
    val name: String = "",
    /** Its machine's name: the service's own host for this computer's spaces. */
    val machine: String = "",
    /** Nobody named it: it is called after its folder. */
    val unnamed: Boolean = false,
    /** The space's icon, an emoji the person picked. */
    val emoji: String? = null,
    /** What its lists hold: 3.x sends 0 (every space sees its own tasks by folder); kept for older answers. */
    val level: Int = 0,
    val status: V2Status = V2Status.Idle,
    /** `regular` or `autopilot`. */
    val mode: String = "regular",
    /** Its Assistant's chat key. */
    val chat: String = "",
    /** Its chat's header ("Assistant · Claude Code") and the folder it works in ("~/Work/app"). */
    val header: String? = null,
    val where: String? = null,
    val store: V2Store? = null,
    val assistantTab: V2AssistantTab? = null,
    /** A git worktree of another space. */
    val worktree: Boolean = false,
    val branch: String? = null,
    val unreachableSince: String? = null,
    val connecting: Boolean = false,
    val herdrDown: String? = null,
) {
    /** Kept for the screens' sake: 3.x has no home spaces. */
    val home: Boolean get() = false
}

/** A machine (`Machine`), as a heading over its spaces. */
@Serializable
data class V2Machine(
    /** `m:<key>`. */
    val id: String,
    /** Its key: herdr's label for it. */
    val machine: String = "",
    val name: String = "",
    val emoji: String? = null,
    /** `idle`, or `off` when Shepherdr can't reach it. */
    val status: V2Status = V2Status.Idle,
    /** The machine the service runs on ("This computer"). */
    val here: Boolean = false,
    /** Its own name, "This computer" for the service's. */
    val ownName: String? = null,
    val unreachableSince: String? = null,
    val connecting: Boolean = false,
    val herdrDown: String? = null,
)

@Serializable
data class V2Pr(val number: Int = 0, val url: String = "")

/** What a task's agent last reported (`herdr-task status`): its words, % done when it gave one, minutes left. */
@Serializable
data class V2Report(val msg: String = "", val progress: Int? = null, val eta: Int? = null, val paused: Boolean = false)

/** A task's one agent: its chat is [chat] (`HA-1/Agent`). */
@Serializable
data class V2TaskAgent(
    val chat: String,
    val role: String = "",
    val cli: String = "",
    val status: V2Status = V2Status.Idle,
    val age: String = "",
    val note: String = "",
    /** Its process is up: a To do task may still have an agent to stop. */
    val running: Boolean = false,
)

@Serializable
data class V2Task(
    /** Its short name, as people say it: `HA-1`. */
    val id: String,
    /** Its title. */
    val name: String = "",
    val status: V2TaskStatus = V2TaskStatus.Todo,
    /** Its space's id: the space that started it, else the one in its list's folder; empty when none is. */
    val place: String = "",
    /** What its row says it is in: that space's name, else its list's. */
    val listName: String? = null,
    /** Its second line in lists. */
    val sub: String? = null,
    val age: String = "",
    val doneAt: Long? = null,
    val branch: String? = null,
    val plan: List<String>? = null,
    /** None until its agent starts. */
    val agent: V2TaskAgent? = null,
    /** Its brief. */
    val desc: String? = null,
    /** Its summary, once ready. */
    val said: String? = null,
    val facts: String? = null,
    val state: String = "",
    val on: String = "",
    val created: String = "",
    val report: V2Report? = null,
    val pr: V2Pr? = null,
    /** Queued: the tasks it still waits for. */
    val after: List<String>? = null,
    /** `done`, `stopped` or `failed`. */
    val outcome: String? = null,
    val archived: Long? = null,
    val pinned: Long? = null,
    /** Working: the minutes left under its row's dot, in Shepherdr's words ("6m", "<1m", "2h"). */
    val left: String? = null,
    /** Ready, its Accept under way: its row says "Accepting…". */
    val accepting: Boolean = false,
    /** The branch it merges into, when it has one of its own. */
    val base: String? = null,
    /** Its latest news (ms). */
    val at: Double? = null,
)

@Serializable
data class V2Choice(val label: String, val description: String? = null, val recommended: Boolean = false)

/** A task a card in an Assistant's chat is about: its worker's ask, headed by its task. */
@Serializable
data class V2By(val ref: String, val title: String? = null, val chat: String = "")

/** One question of a card that asks several (`Question.parts`). */
@Serializable
data class V2Part(val title: String = "", val header: String? = null, val multi: Boolean = false, val choices: List<V2Choice> = emptyList())

/** A question card (`Question`): a choice card, a CLI's own menu, or a plan. */
@Serializable
data class V2Question(
    val id: String,
    val title: String = "",
    /** `Form`, `Question` or `Plan`; any other reads as a plain question. */
    val kind: String = "Question",
    val steps: List<String>? = null,
    /** Several questions on one card: a step for each. */
    val parts: List<V2Part>? = null,
    /** A plan in the CLI's own words, in Markdown. */
    val plan: String? = null,
    /** A CLI's own menu (a start screen): a tap presses that choice at once. */
    val press: Boolean = false,
    /** A task worker's ask in its Assistant's chat. */
    val by: V2By? = null,
    /** The choice that allows this for good. */
    val always: Int? = null,
    val screen: String? = null,
    val terminal: Boolean = false,
    val reject: String? = null,
    val choices: List<V2Choice> = emptyList(),
    val multi: Boolean = false,
    /** An "Other" answer in free text is allowed. */
    val other: Boolean = false,
    /** The choice that needs a note to be sent (a plan sent back). */
    val noteRequiredFor: Int? = null,
    val noNote: Boolean = false,
    val to: String = "",
    /** Index of the choice sent once answered; -1 answered without a pick. */
    val sent: Int? = null,
    val said: String? = null,
    /** `chat` or `terminal`: answered there, not on the card. */
    val via: String? = null,
    /** "Claude Code asks". */
    val asks: String? = null,
    /** Why it closed without the human's answer (expired, replaced); it was never answered. */
    val closed: String? = null,
) {
    /** Still waiting for an answer. */
    val open: Boolean get() = sent == null && said == null && via == null && closed == null
}

/** A task's news line in its Assistant's chat (`Message.news`): [mark] started, needs, ready, done or other. */
@Serializable
data class V2News(
    val task: String,
    val ref: String = "",
    val mark: String = "other",
    /** What happened ("started", "is ready"). */
    val what: String = "",
    /** The rest: its title, or its summary. */
    val text: String = "",
    /** Still needs you. */
    val live: Boolean = false,
    /** The newest ready line of a task still Ready: it carries Accept. */
    val accept: Boolean = false,
)

/** A message the person didn't type (`Message.brief`): a launch brief or another agent's message, as one quiet row. */
@Serializable
data class V2Brief(val label: String = "", val bare: Boolean = false)

/** A task a message mentions, as a chip under it. */
@Serializable
data class V2Mention(val task: String, val ref: String = "", val title: String = "")

/** An agent asking for a secret (`SecretAsk`): kept in the vault on the machine, never typed into the chat. */
@Serializable
data class V2SecretAsk(val id: String = "", val name: String = "", val why: String? = null, val by: V2By? = null, val saved: String? = null)

/** What a needs-you card in a space's chat asks, from its task's chat (`CardAsk`). */
@Serializable
data class V2CardAsk(
    /** The task's chat, where the answer goes. */
    val chat: String,
    val questions: List<V2Question>? = null,
    /** A plain question with no choices, answered with a reply to message [V2PlainAsk.id]. */
    val reply: V2PlainAsk? = null,
    val answered: V2Answered? = null,
    /** It still waits. */
    val open: Boolean = false,
)

@Serializable
data class V2PlainAsk(val id: String, val text: String = "")

@Serializable
data class V2Answered(val where: String = "", val text: String? = null)

/** Shepherdr's own update card in a space's chat (`UpdateCard`). */
@Serializable
data class V2UpdateCard(
    val id: String,
    val task: String? = null,
    val status: V2TaskStatus? = null,
    val mode: String? = null,
    val text: String = "",
    val pr: V2Pr? = null,
    /** The base branch went red on GitHub ("main"). */
    val red: String? = null,
    val restart: Boolean = false,
    val ask: V2CardAsk? = null,
)

@Serializable
data class V2Reaction(val emoji: String, val by: String = "", val pending: Boolean = false)

@Serializable
data class V2ReplyQuote(val id: String? = null, val who: String? = null, val text: String = "")

@Serializable
data class V2File(val name: String = "", val path: String = "")

@Serializable
data class V2ArtifactPost(val id: String, val v: Int = 0)

/** One kept version of an artifact (`ArtifactVersion`), newest first in [V2Artifact.versions]. */
@Serializable
data class V2ArtifactVersion(
    val n: Int,
    /** When it came, short ("2m"). */
    val `when`: String = "",
    /** Who made it ("HA-1 agent"). */
    val by: String = "",
    val note: String? = null,
    /** The version whose page it shows: one that brought only new data shows the page before it. */
    val html: Int = 0,
    /** What it brought when not a new page: `data` or `title`. */
    val what: String? = null,
    /** This version's kind when it differs over its versions: `recipe`, or `gpui` (a panel only the Herdr window draws). */
    val kind: String? = null,
    val at: Double? = null,
)

/**
 * An artifact (`Artifact`): what an agent shows beyond words, kept in its space's store and updated as new versions.
 * A [RECIPE] one is a `recipe.json` the app draws itself ([Recipes]), read by id ([ShepherdrClient.recipe]); a `gpui`
 * one is a panel only the Herdr window runs.
 */
@Serializable
data class V2Artifact(
    val id: String,
    val title: String = "",
    /** `recipe` or `gpui`, its latest version's. */
    val kind: String? = null,
    val place: String = "",
    /** `<machine>|<folder>`: the store it is in, as a space's [V2Store.key] names it. */
    val store: String? = null,
    /** The folder it is kept for and that folder's name ("~" at home). */
    val folder: String? = null,
    val folderName: String? = null,
    /** The machine it is kept on, when another than the service's: its files come from there. */
    val machine: String? = null,
    /** The task whose agent made it ("HERD-4"). */
    val task: String? = null,
    /** Its latest version, and when that came (ms). */
    val v: Int = 0,
    val at: Double = 0.0,
    val `when`: String = "",
    val by: String = "",
    /** Who what you send from it reaches: the agent that made the latest version, or its space's coordinator. */
    val keeper: String = "",
    /** Shepherdr's check took its picture, light and dark. */
    val thumb: Boolean = false,
    /** The chats its versions came from. */
    val chats: List<String>? = null,
    /** Newest first; only the last 20 are kept. */
    val versions: List<V2ArtifactVersion> = emptyList(),
) {
    fun version(n: Int): V2ArtifactVersion? = versions.firstOrNull { it.n == n }

    /** Version [n]'s kind: its own, else the artifact's. */
    fun kindOf(n: Int): String? = version(n)?.kind ?: kind

    /** The app draws version [n]: a recipe. */
    fun drawn(n: Int): Boolean = kindOf(n) == RECIPE

    companion object {
        const val RECIPE = "recipe"
    }
}

@Serializable
data class V2Message(
    val id: String,
    /** `you` or `agent`. */
    val who: String = "agent",
    /** Plain text (yours) or markdown (the agent's). */
    val text: String? = null,
    val answered: String? = null,
    val artifact: V2ArtifactPost? = null,
    val question: V2Question? = null,
    val reactions: List<V2Reaction>? = null,
    /** Waits to go in. */
    val waiting: Boolean = false,
    val file: V2File? = null,
    val cid: String? = null,
    val reply: V2ReplyQuote? = null,
    val newSession: Boolean = false,
    val card: V2UpdateCard? = null,
    /** A turn's tool steps, kept raw: the app shows them as one line at most. */
    val activity: JsonObject? = null,
    val at: Double? = null,
    /** A launch brief or another agent's message, as one quiet row; its words are [text]. */
    val brief: V2Brief? = null,
    /** A task's news line (an Assistant's chat). */
    val news: V2News? = null,
    /** The agent's own question as its log has it: `open`, `answered` or `dismissed`. */
    val asked: String? = null,
    /** An error the CLI wrote: red words, no bubble. */
    val error: Boolean = false,
    /** Images sent with it that the chat can't show. */
    val images: Int? = null,
    /** A publish the artifacts' list doesn't have yet. */
    val publishing: Boolean = false,
    /** A quiet line for an ask that closed ("Run: npm test · Allowed in the chat"). */
    val ended: String? = null,
    val mentions: List<V2Mention>? = null,
    /** An agent waiting for a secret. */
    val secret: V2SecretAsk? = null,
    /** Not Shepherdr's: a message in a shape this app can't read ([V2Events]), shown as one quiet line. */
    @Transient val unreadable: Boolean = false,
) {
    val you: Boolean get() = who == "you"

    /** Nothing in it the app knows how to show: a kind of message newer than the app, or one it couldn't read. */
    val unknown: Boolean
        get() = unreadable || text.isNullOrBlank() && question == null && card == null && artifact == null &&
            file == null && answered.isNullOrBlank() && activity == null && news == null && ended.isNullOrBlank() &&
            secret == null && !publishing && (images ?: 0) == 0
}

@Serializable
data class V2Chat(
    val key: String,
    val messages: List<V2Message> = emptyList(),
    /** The agent works: what you send goes in at its next step. */
    val working: Boolean = false,
    /** Only background work runs: no reply is on its way. */
    val background: Boolean = false,
    val failed: List<V2Failed>? = null,
    val finished: Boolean = false,
    val cli: String = "",
    /** The log is read from its start, or earlier pages are left to load. */
    val top: Boolean = false,
    val earlier: Boolean = false,
    /** While it works: what it does now (empty: "Working"); [waits]: only waiting. */
    val doing: String? = null,
    val waits: Boolean = false,
    /** It asks in its terminal and no card here asks for it: what it says it waits for. */
    val blocked: String? = null,
    /** A note at the conversation's end (a lost session being started again). */
    val note: String? = null,
    /** Instead of the conversation: `opening`, `unsupported` (not a chat agent), `none` (its log not found), `ended`. */
    val notice: String? = null,
    /** What it runs on, as its model button says it ("Opus 5.5 · High"). */
    val agent: V2ChatAgent? = null,
) {
    /** The question cards still waiting, oldest first. */
    val openQuestions: List<V2Question> get() = messages.mapNotNull { m -> m.question?.takeIf { it.open } }
}

@Serializable
data class V2Failed(val cid: String, val error: String = "")

@Serializable
data class V2ChatAgent(val kind: String = "", val words: String? = null, val modelLabel: String? = null)

/** One thing waiting on the human (`Need`), from any agent on any machine: its own card, never a forwarded copy. */
@Serializable
data class V2Need(
    /** The request's id in its chat; `attention` when the agent waits with no card. */
    val id: String,
    /** The agent's chat, where the answer goes. */
    val chat: String,
    val place: String = "",
    /** The task it belongs to; none for an Assistant. */
    val task: String? = null,
    /** When it began to wait (ms). */
    val at: Double = 0.0,
    val question: V2Question? = null,
    val secret: V2SecretAsk? = null,
    /** No card: what the agent last said, or why it waits, answered with a reply. */
    val text: String? = null,
    /** No card: the message [text] is, for the reply to quote. */
    val reply: String? = null,
)

/** An agent's own alert (`herdr-task alert`), as Shepherdr's toasts say it. */
@Serializable
data class V2Alert(val id: String, val at: Double = 0.0, val title: String = "", val body: String = "", val chat: String? = null, val place: String? = null, val notify: Boolean = true)

@Serializable
data class V2Snapshot(
    val name: String = "",
    val host: String = "",
    /** Every space, in the sidebar's order. */
    val places: List<V2Place> = emptyList(),
    val machines: List<V2Machine> = emptyList(),
    val tasks: List<V2Task> = emptyList(),
    /** Open Assistant questions, by space id (a choice card's id, or `blocked`). */
    val asks: Map<String, String> = emptyMap(),
    /** Everything waiting on the human, oldest first, each once: what Needs you lists. */
    val needs: List<V2Need> = emptyList(),
    val alerts: List<V2Alert> = emptyList(),
    val user: String = "",
    val demo: Boolean = false,
    /** `core`: its agents run in herdr-core. */
    val terminalTransport: String? = null,
    /** When each chat last had something new (ms), and how far each was read. */
    val last: Map<String, Double> = emptyMap(),
    val read: Map<String, Double> = emptyMap(),
    /** Every artifact; none on a Shepherdr that keeps none, where the app shows no artifacts. */
    val artifacts: List<V2Artifact>? = null,
) {
    fun place(id: String): V2Place? = places.firstOrNull { it.id == id }
    fun task(id: String): V2Task? = tasks.firstOrNull { it.id == id }

    /** What waits on you: every [needs] entry and every Ready task not archived. */
    val waiting: Int get() = needs.size + tasks.count { it.status == V2TaskStatus.Ready && it.archived == null }
}

/** What the live stream (`/api/ws`) sends. */
sealed interface V2Event {
    data class Snapshot(val snapshot: V2Snapshot) : V2Event
    data class Chat(val chat: V2Chat) : V2Event
    /** Anything else (`build`, `update`, `terminal`, `pong`, …), as sent. */
    data class Other(val type: String, val raw: JsonElement) : V2Event
}
