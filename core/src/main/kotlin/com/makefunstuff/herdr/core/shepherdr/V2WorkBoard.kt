package com.makefunstuff.herdr.core.shepherdr

import com.makefunstuff.herdr.core.board.BoardApi
import com.makefunstuff.herdr.core.board.CardAnswer
import com.makefunstuff.herdr.core.board.ChatLine
import com.makefunstuff.herdr.core.board.CoordinatorThread
import com.makefunstuff.herdr.core.board.WorkBoard
import com.makefunstuff.herdr.core.board.WorkCard
import com.makefunstuff.herdr.core.board.WorkOverview
import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.core.board.WorkAgentChoice
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTab
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.board.WorkTaskDetails
import com.makefunstuff.herdr.core.board.WorkTasks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.IOException

/**
 * Shepherdr 3's service as a [WorkBoard]. [connect] gives the machine's client each time (HostSession's
 * `awaitShepherdr`), so a link lost with the SSH connection comes back on the new one.
 */
class V2WorkBoard(private val connect: suspend () -> ShepherdrClient, private val version: String = "") : WorkBoard {
    constructor(client: ShepherdrClient, version: String = "") : this({ client }, version)

    override val api: BoardApi get() = BoardApi.V2(version)

    override suspend fun overview(): WorkOverview = V2Board.overview(connect().snapshot())

    /** From the live link's snapshots, linked again when it drops; read every [everyMs] where there is no live link. */
    override fun overviews(everyMs: Long): Flow<WorkOverview> =
        following(everyMs, emptyList(), { overview() }) { e -> (e as? V2Event.Snapshot)?.let { V2Board.overview(it.snapshot) } }

    override suspend fun details(task: WorkTask): WorkTaskDetails {
        val client = connect()
        val snap = client.snapshot()
        val t = snap.task(task.id) ?: throw IOException("${task.name} isn't on the board any more")
        val now = V2Board.task(t)
        // What it asks: its needs, each once, wherever its card shows.
        val cards = V2Board.cards(snap.needs.filter { it.task == t.id || now.chat.isNotEmpty() && it.chat == now.chat })
        return WorkTaskDetails(
            task = now,
            brief = t.desc.orEmpty(),
            summary = t.said.orEmpty(),
            facts = t.facts.orEmpty(),
            plan = t.plan.orEmpty(),
            branch = t.branch.orEmpty(),
            cards = cards,
        )
    }

    /** Accept (`herdr-task accept`), as the Accept card's: it merges when there is something to merge. */
    override suspend fun approve(task: WorkTask): String {
        connect().accept(task.id)
        return "${task.name} accepted"
    }

    override suspend fun evidence(task: WorkTask): V2Evidence = connect().evidence(task.id)

    override suspend fun folder(task: WorkTask): TaskFolder = connect().taskFolder(task.id)

    override suspend fun pullRequest(task: WorkTask): V2PrAnswer = connect().taskPr(task.id)

    override suspend fun browser(task: WorkTask): TaskBrowser = connect().browser(task.id)

    /** Done with nothing merged (`herdr-task done`), by its chat key as the task's row menu sends it. */
    override suspend fun close(task: WorkTask): String {
        val o = connect().actRaw(V2Actions.close(task.chat.ifEmpty { V2Board.workerChat(task.id) }))
        if (o.ok) return "${task.name} is done, nothing merged"
        throw IOException(V2Board.closeRefusal(task, o.json))
    }

    /** A message to [space]'s Assistant, which makes the task: Shepherdr makes tasks no other way. */
    override suspend fun addTask(space: String, title: String, brief: String): String {
        connect().send(chatOf(space), V2Board.addRequest(title, brief))
        return "Sent to the Assistant: it adds the task"
    }

    override suspend fun answer(answer: CardAnswer): String {
        val c = answer.card
        if (c.replyTo.isNotEmpty()) {
            val text = answer.other?.takeIf { it.isNotBlank() } ?: answer.note?.takeIf { it.isNotBlank() } ?: throw IOException("Write an answer first")
            connect().act(V2Actions.send(c.chat, text, reply = c.replyTo))
        } else {
            connect().act(V2Actions.answer(c.chat, c.question.id, answer.choice, answer.choices, answer.note, answer.other, answer.answers))
        }
        return ""
    }

    override suspend fun keepSecret(chat: String, ask: V2SecretAsk, level: String, value: String): String {
        if (value.isEmpty()) throw IOException("Type or paste the value first")
        connect().act(V2Actions.secret(chat, ask.id, ask.name, level, value))
        return "Kept for " + (ask.levels.firstOrNull { it.id == level }?.label ?: level)
    }

    override suspend fun coordinator(space: String): CoordinatorThread =
        V2Board.thread(space, connect().chat(chatOf(space)))

    /** From the live link as the chat changes, linked again when it drops; read every [everyMs] where there is no live link. */
    override fun coordinatorThreads(space: String, everyMs: Long): Flow<CoordinatorThread> {
        val key = chatOf(space)
        return following(everyMs, listOf(key), { coordinator(space) }) { e ->
            (e as? V2Event.Chat)?.takeIf { it.chat.key == key }?.let { V2Board.thread(space, it.chat) }
        }
    }

    override suspend fun say(space: String, text: String, cid: String?, reply: String?): String {
        connect().act(V2Actions.send(chatOf(space), text, reply = reply, cid = cid))
        return ""
    }

    override suspend fun react(space: String, task: WorkTask?, message: String, emoji: String) {
        connect().act(V2Actions.react(task?.let(::agentChat) ?: chatOf(space), message, emoji))
    }

    override suspend fun earlier(space: String, task: WorkTask?) {
        connect().act(V2Actions.earlier(task?.let(::agentChat) ?: chatOf(space)))
    }

    override suspend fun upload(space: String, name: String, data: ByteArray, type: String): String =
        connect().upload(name, data, type)

    override suspend fun sayFiles(space: String, paths: List<String>, cid: String?): String {
        connect().act(V2Actions.send(chatOf(space), "", cid = cid, attachments = paths))
        return ""
    }

    override suspend fun file(space: String, path: String): ByteArray = connect().file(chatOf(space), path)

    override suspend fun call(space: String, task: WorkTask?): VoiceCall =
        connect().voice(task?.let(::agentChat) ?: chatOf(space))

    override suspend fun dictate(audio: ByteArray, type: String): String = connect().dictate(audio, type)

    override suspend fun autopilot(space: String, on: Boolean) {
        connect().act(V2Actions.autopilot(space, on))
    }

    override suspend fun autopilotSetting(key: String, value: String) {
        connect().act(V2Actions.autopilotSetting(key, value))
    }
    override suspend fun canDictate(): Boolean = connect().canDictate()

    /** Its agent's chat from the live link, as [coordinatorThreads] follows a space's. */
    override fun taskThreads(task: WorkTask, everyMs: Long): Flow<CoordinatorThread> {
        val key = agentChat(task)
        return following(everyMs, listOf(key), { V2Board.thread(task.space, connect().chat(key)) }) { e ->
            (e as? V2Event.Chat)?.takeIf { it.chat.key == key }?.let { V2Board.thread(task.space, it.chat) }
        }
    }

    override suspend fun tell(task: WorkTask, text: String, cid: String?, reply: String?): String {
        connect().act(V2Actions.send(agentChat(task), text, reply = reply, cid = cid))
        return ""
    }

    override suspend fun uploadFor(task: WorkTask, name: String, data: ByteArray, type: String): String =
        connect().upload(name, data, type)

    override suspend fun tellFiles(task: WorkTask, paths: List<String>, cid: String?): String {
        connect().act(V2Actions.send(agentChat(task), "", cid = cid, attachments = paths))
        return ""
    }

    override suspend fun taskFile(task: WorkTask, path: String): ByteArray = connect().file(agentChat(task), path)

    override suspend fun recipe(a: V2Artifact, v: Int): Recipe = connect().recipe(a.id, v, a.machine)

    override suspend fun artifactFile(a: V2Artifact, v: Int, path: String): ByteArray? = connect().artifactFile(a.id, v, path, a.machine)

    override suspend fun artifactSay(a: V2Artifact, v: Int, text: String, answer: Boolean, block: String?): String {
        val action = when {
            answer -> V2ArtifactActions.send(a, v, text)
            block != null -> V2ArtifactActions.note(a, v, text, block)
            else -> V2ArtifactActions.say(a, v, text)
        }
        val o = connect().act(action)
        return (o["to"] as? JsonPrimitive)?.contentOrNull ?: a.keeper
    }

    override suspend fun tabAgents(space: String): List<WorkAgentChoice> {
        val o = connect().act(V2Actions.tabAgents(space))
        return (o["agents"] as? JsonArray).orEmpty().mapNotNull { e ->
            val a = e as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (a[k] as? JsonPrimitive)?.contentOrNull
            val kind = str("kind") ?: return@mapNotNull null
            WorkAgentChoice(kind, str("name") ?: kind, (a["installed"] as? JsonPrimitive)?.booleanOrNull == true)
        }
    }

    override suspend fun newTerminal(space: String) {
        connect().act(V2Actions.tabNew(space))
    }

    override suspend fun renameTab(space: String, tab: WorkTab, name: String) {
        connect().act(V2Actions.tabRename(tab.id, space.takeIf { tab.id == null }, name.trim().take(80)))
    }

    override suspend fun closeTab(id: String) {
        connect().act(V2Actions.tabClose(id))
    }

    override suspend fun restartTab(tab: WorkTab, kind: String?) {
        connect().act(V2Actions.tabSwitch(tab.chat, kind))
    }

    override suspend fun modelMenu(chat: String): V2ModelMenu {
        val menu = connect().act(V2Actions.modelMenu(chat))["menu"] as? JsonObject ?: throw IOException("Couldn't read what it runs on.")
        return ShepherdrJson.decodeFromJsonElement(V2ModelMenu.serializer(), menu)
    }

    override suspend fun restartWith(chat: String, pick: V2ModelPick, level: String?) {
        connect().act(V2Actions.restart(chat, pick, level))
    }

    override suspend fun assistantWorkers(space: String): Int =
        (connect().act(V2Actions.assistantClose(space, "count"))["workers"] as? JsonPrimitive)?.intOrNull ?: 0

    override suspend fun closeAssistant(space: String, stopWorkers: Boolean) {
        connect().act(V2Actions.assistantClose(space, if (stopWorkers) "stop" else "keep"))
    }

    override suspend fun closeSpace(space: String) {
        connect().act(V2Actions.spaceRemove(space))
    }

    private fun agentChat(task: WorkTask) = task.chat.ifEmpty { throw IOException("${task.name} has no agent yet") }

    /** A space's Assistant chat key: its id where it is one already (`Place.chat`, `p:<space id>`), else made from it. */
    private fun chatOf(space: String) = V2Board.assistantChat(space)

    /**
     * What [pick] takes from the live link (watching [chats]), as the web screen follows it: linked
     * again when it drops (a restart, a lost connection), sooner after it last worked. Where there is no
     * live link, [read] every [everyMs]. The first failure is thrown, so a screen can say why; once it
     * has worked, failures are waited out.
     */
    private fun <T> following(everyMs: Long, chats: List<String>, read: suspend () -> T, pick: (V2Event) -> T?): Flow<T> = flow {
        var worked = false
        var wait = RETRY_MS
        while (true) {
            try {
                val client = connect()
                if (!client.canStream) {
                    emit(read())
                    worked = true
                    delay(everyMs)
                    continue
                }
                // A chat's screen needs no snapshots: they never cross the phone's connection when the relay runs.
                val link = client.live(snapshots = chats.isEmpty())
                try {
                    if (chats.isNotEmpty()) link.watch(chats)
                } catch (e: Throwable) {
                    // Only events() closes it from here on: cancelled (or failed) before, it would stay open.
                    link.close()
                    throw e
                }
                link.events().collect { e ->
                    pick(e)?.let {
                        worked = true
                        wait = RETRY_MS
                        emit(it)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!worked) throw e
            }
            delay(wait)
            wait = minOf(wait * 2, MAX_RETRY_MS)
        }
    }

    private companion object {
        const val RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 30_000L
    }
}

/** Shepherdr 3's snapshot and chats in the [WorkBoard]'s shapes. */
object V2Board {
    fun overview(s: V2Snapshot): WorkOverview {
        val tasks = WorkTasks.ordered(s.tasks.filter { it.archived == null }.map(::task))
        val spaces = s.places.map { p ->
            WorkSpace(
                id = p.id,
                name = p.name,
                machine = p.machine,
                home = p.home,
                chat = p.chat.ifEmpty { assistantChat(p.id) },
                needsYou = p.status == V2Status.Need || s.asks.containsKey(p.id) || s.needs.any { it.place == p.id } ||
                    tasks.any { it.space == p.id && it.status.needsYou },
                unreachable = p.unreachableSince != null,
                // A secret ask is its own item on Needs you (Waiting.Secret), not the Assistant's question.
                asks = s.asks.containsKey(p.id) || s.needs.any { it.place == p.id && it.task == null && it.secret == null },
                level = p.level,
                store = p.store?.key.orEmpty(),
                emoji = p.emoji,
                pilot = Autopilot.pilotOf(s, p),
                tabs = tabs(s, p),
                header = p.header.orEmpty(),
                where = p.where.orEmpty(),
            )
        }
        return WorkOverview(spaces, tasks, s.artifacts, s.needs, Autopilot.settingsOf(s))
    }

    /** [p]'s tabs as 3.1's bar has them (`barTabs`): its Assistant's first, then the others. */
    fun tabs(s: V2Snapshot, p: V2Place): List<WorkTab> {
        val a = p.assistantTab
        val assistant = WorkTab(
            id = a?.id, name = a?.name ?: "Assistant", named = a?.named == true, chat = p.chat.ifEmpty { assistantChat(p.id) },
            agent = a?.agent ?: a?.cli, state = a?.state ?: "idle", assistant = true, live = a?.live == true,
            remote = s.host.isNotEmpty() && p.machine != s.host,
        )
        return listOf(assistant) + p.tabs.map { t ->
            WorkTab(
                id = t.id, name = t.name, named = t.named, chat = t.term, agent = t.agent ?: t.cli, state = t.state,
                live = (t.agent ?: t.cli) != null, remote = '/' in t.term.drop(2),
            )
        }
    }

    fun task(t: V2Task) = WorkTask(
        id = t.id,
        name = t.id.substringAfter(':'),
        title = t.name,
        space = t.place,
        status = status(t.status),
        // Shepherdr's own second line (why it needs you, what it waits for, its agent's words), capital first.
        line = capital(t.sub?.takeIf { it.isNotBlank() } ?: t.report?.msg.orEmpty()),
        pr = t.pr?.url.orEmpty(),
        progress = t.report?.progress,
        eta = t.report?.eta,
        left = t.left?.takeIf { it.isNotBlank() },
        accepting = t.accepting,
        listName = t.listName.orEmpty(),
        age = t.age,
        chat = t.agent?.chat.orEmpty(),
        agent = t.agent?.cli.orEmpty(),
        pinned = t.pinned != null,
        running = t.agent?.running == true,
        browser = t.browser,
    )

    /** Shepherdr's `capital`: its first letter up ("after HERD-3" → "After HERD-3"). */
    fun capital(s: String): String = s.trim().replaceFirstChar { it.uppercaseChar() }

    /** A task's own chat key (`<task id>/worker`), before its snapshot names it. */
    fun workerChat(task: String) = "$task/worker"

    /** A space's Assistant chat key: `p:<space id>`, its `/` (another machine's space) as `:`. */
    fun assistantChat(space: String) = if (space.startsWith("p:")) space else "p:" + space.replace('/', ':')

    /** What Shepherdr says needs you, as cards: a question as its card, a wait with no card as a reply to its words. */
    fun cards(needs: List<V2Need>): List<WorkCard> = needs.mapNotNull { n ->
        val task = n.task?.substringAfter(':').orEmpty()
        when {
            n.question != null -> WorkCard(n.chat, n.question, task, space = n.place)
            n.reply != null || n.text != null -> {
                val q = V2Question(id = n.id, title = n.text.orEmpty(), kind = "Reply", other = true, to = task)
                WorkCard(n.chat, q, task, space = n.place, replyTo = n.reply.orEmpty())
            }
            else -> null
        }
    }

    /** What a space's Assistant is asked, to add a task: its title, then the brief. */
    fun addRequest(title: String, brief: String): String = buildString {
        append("Please add a task: ").append(title.trim())
        brief.trim().takeIf { it.isNotEmpty() }?.let { append("\n\n").append(it) }
    }

    /** Why `close` refused, from its answer: its own error. */
    fun closeRefusal(task: WorkTask, o: JsonObject): String =
        (o["error"] as? JsonPrimitive)?.contentOrNull ?: "Shepherdr refused to close ${task.name}"

    /** An agent's kind from its CLI's name, as the agents' marks are keyed: "Claude Code" is `claude`, "Codex" `codex`. */
    fun agentKind(cli: String): String = cli.trim().substringBefore(' ').lowercase()

    fun status(s: V2TaskStatus): WorkStatus = when (s) {
        V2TaskStatus.Need -> WorkStatus.Need
        V2TaskStatus.Ready -> WorkStatus.Ready
        V2TaskStatus.Merging -> WorkStatus.Merging
        V2TaskStatus.Work -> WorkStatus.Work
        V2TaskStatus.Queued -> WorkStatus.Queued
        V2TaskStatus.Todo -> WorkStatus.Todo
        V2TaskStatus.Done -> WorkStatus.Done
    }

    /**
     * An Assistant's (or a task's) chat as bubbles: words and questions as their one line; tool steps left out. Its
     * cards are the questions still open in it: its own, and its tasks' workers' asks headed by their task ([V2By]).
     */
    fun thread(space: String, chat: V2Chat): CoordinatorThread {
        val lines = chat.messages.mapNotNull { m ->
            val text = words(m) ?: return@mapNotNull null
            ChatLine(m.you, text, pending = m.waiting)
        }
        val cards = chat.messages.mapNotNull { m ->
            val q = m.question?.takeIf { it.open } ?: return@mapNotNull null
            WorkCard(q.by?.chat?.takeIf { it.isNotEmpty() } ?: chat.key, q, q.by?.ref.orEmpty(), space = space)
        }
        return CoordinatorThread(space, lines, working = chat.working && !chat.background, cards = cards, v2 = chat)
    }

    /** A message's words as one bubble shows them; null for one with none (a turn's tool steps). */
    fun words(m: V2Message): String? = m.text?.let(ShepherdrText::clean)?.takeIf { it.isNotBlank() }
        ?: m.question?.title?.takeIf { it.isNotBlank() }
        ?: m.news?.let { n -> listOf(n.ref, n.what, n.text).filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() } }
        ?: m.file?.name?.takeIf { it.isNotBlank() }
        ?: m.answered?.takeIf { it.isNotBlank() }
        ?: m.ended?.takeIf { it.isNotBlank() }
}
