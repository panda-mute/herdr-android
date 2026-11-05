package com.makefunstuff.herdr.core.shepherdr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.Closeable
import java.io.IOException

/** What `/api/action` answered: [ok], and the whole answer. */
data class V2ActResult(val ok: Boolean, val json: JsonObject)

/** The bodies `POST /api/action` takes (`Action` in `web/shared/types.ts`), for what the app does. */
object V2Actions {
    /**
     * Words into a chat; [reply]: the id of the message they answer; [attachments]: files uploaded for it
     * ([ShepherdrClient.upload]), their paths after the words. [human]: typed in the app by the person.
     */
    fun send(
        chat: String,
        text: String,
        reply: String? = null,
        cid: String? = null,
        attachments: List<String> = emptyList(),
        human: Boolean = true,
    ): JsonObject = buildJsonObject {
        put("type", "send")
        put("chat", chat)
        put("text", text)
        if (attachments.isNotEmpty()) putJsonArray("attachments") { attachments.forEach { add(JsonPrimitive(it)) } }
        cid?.let { put("cid", it) }
        reply?.let { put("reply", it) }
        if (human) put("human", true)
    }

    /**
     * Answers question card [question] in [chat]: [choice] picked (-1 none), or [choices] on a multi card;
     * [note] goes with it (a plan sent back needs one); [other] is a typed answer; [answers] one per part of a
     * card that asks several.
     */
    fun answer(
        chat: String,
        question: String,
        choice: Int,
        choices: List<Int>? = null,
        note: String? = null,
        other: String? = null,
        answers: List<String>? = null,
    ): JsonObject = buildJsonObject {
        put("type", "answer")
        put("chat", chat)
        put("question", question)
        put("choice", choice)
        choices?.let { c -> putJsonArray("choices") { c.forEach { add(JsonPrimitive(it)) } } }
        note?.takeIf { it.isNotEmpty() }?.let { put("note", it) }
        other?.takeIf { it.isNotEmpty() }?.let { put("other", it) }
        answers?.let { a -> putJsonArray("answers") { a.forEach { add(JsonPrimitive(it)) } } }
    }

    /** Adds [emoji] to message [message] in [chat], or takes yours away again. */
    fun react(chat: String, message: String, emoji: String): JsonObject = buildJsonObject {
        put("type", "react")
        put("chat", chat)
        put("message", message)
        put("emoji", emoji)
    }

    /** The page of [chat]'s log before what it shows (`Search earlier`, `Loading earlier…`): the chat then comes again, longer. */
    fun earlier(chat: String): JsonObject = buildJsonObject {
        put("type", "earlier")
        put("chat", chat)
    }

    /** Marks task [task]'s chat's task done with nothing merged (`herdr-task done`): its chat key, `<task>/worker`. */
    fun close(chat: String): JsonObject = buildJsonObject {
        put("type", "close")
        put("chat", chat)
    }

    /** Accept on a Ready task (`herdr-task accept`): it merges. */
    fun accept(task: String): JsonObject = buildJsonObject {
        put("type", "accept")
        put("task", task)
    }

    /** What the Accept card shows of a Ready task (`herdr-task show --evidence`). */
    fun evidence(task: String): JsonObject = buildJsonObject {
        put("type", "evidence")
        put("task", task)
    }

    /**
     * Keeps [value] as secret [name] at [level] ([V2SecretLevel.id]), for the agent in [chat] that asked ([ask]: its
     * card's id), as the web's masked card sends it: the service puts it in the vault with `herdr-secret set` (on its
     * stdin) and tells the agent its name only. Never logged or kept: this object lives as long as the request.
     */
    fun secret(chat: String, ask: String, name: String, level: String, value: String): JsonObject = buildJsonObject {
        put("type", "secret")
        put("level", level)
        put("name", name)
        put("value", value)
        put("card", buildJsonObject { put("chat", chat); put("id", ask) })
    }

    /** Stops the agent in [chat]: the task goes back to To do, its work stays. */
    fun stop(chat: String): JsonObject = buildJsonObject {
        put("type", "stop")
        put("chat", chat)
    }

    /** Switches [place]'s Autopilot on or off, as its Assistant's header switch (`herdr-task autopilot on|off`). */
    fun autopilot(place: String, on: Boolean): JsonObject = buildJsonObject {
        put("type", "mode")
        put("place", place)
        put("mode", if (on) "autopilot" else "regular")
    }

    /**
     * Sets one of Settings › Autopilot's rows ([Autopilot.HEARTBEAT], [Autopilot.EVERY], [Autopilot.EVENTS]): the
     * same in every space of the machine, so Everywhere's.
     */
    fun autopilotSetting(key: String, value: String): JsonObject = buildJsonObject {
        put("type", "setting")
        put("key", key)
        put("place", "all")
        put("value", value)
    }

    /** The agents a new tab of [place] can run, and Switch agent's list (`tab-agents`): each one's kind, name, installed. */
    fun tabAgents(place: String): JsonObject = buildJsonObject {
        put("type", "tab-agents")
        put("place", place)
    }

    /** Names tab [tab] (the window's tab id), or the Assistant's tab of [place] when it has no id yet; empty unnames it. */
    fun tabRename(tab: String?, place: String?, name: String): JsonObject = buildJsonObject {
        put("type", "tab-rename")
        if (tab != null) put("tab", tab) else put("place", place)
        put("name", name)
    }

    /** Closes tab [tab]: its programs end. */
    fun tabClose(tab: String): JsonObject = buildJsonObject {
        put("type", "tab-close")
        put("tab", tab)
    }

    /** Restarts the agent in [chat]'s tab, as it runs now, or as agent [kind] (Switch agent): `restart.sh` in its tab. */
    fun tabSwitch(chat: String, kind: String? = null): JsonObject = buildJsonObject {
        put("type", "tab-switch")
        put("chat", chat)
        kind?.let { put("kind", it) }
    }

    /** What the agent in [chat] runs on and what its CLIs offer: the model menu. */
    fun modelMenu(chat: String): JsonObject = buildJsonObject {
        put("type", "model-menu")
        put("chat", chat)
    }

    /** Restarts the agent in [chat] on [pick] (its [level] the effort), as the model menu's Restart with…. */
    fun restart(chat: String, pick: V2ModelPick, level: String?): JsonObject = buildJsonObject {
        put("type", "restart")
        put("chat", chat)
        put("kind", pick.agent)
        put("model", pick.model.orEmpty())
        put("effort", level.orEmpty())
        put("speed", if (pick.fast) "fast" else "normal")
        put("perm", pick.perm.orEmpty())
    }

    /**
     * Closes [place]'s Assistant: [mode] `count` only counts the workers it started, `stop` ends them first, `keep`
     * lets them finish.
     */
    fun assistantClose(place: String, mode: String): JsonObject = buildJsonObject {
        put("type", "assistant-close")
        put("place", place)
        put("mode", mode)
    }

    /** Closes space [place]: its tabs close and every program in them ends. */
    fun spaceRemove(place: String): JsonObject = buildJsonObject {
        put("type", "space-remove")
        put("place", place)
    }

    /** What the live link is told: the chats on screen. Sent as not visible, so the machine's alerts keep coming. */
    fun watch(chats: List<String>): JsonObject = buildJsonObject {
        put("type", "watch")
        putJsonArray("chats") { chats.forEach { add(JsonPrimitive(it)) } }
        put("term", JsonNull)
        put("visible", false)
        put("push", JsonNull)
    }
}

/** Shepherdr 3's service on one machine, over [transport]: what its web app calls. */
class ShepherdrClient(private val transport: ShepherdrTransport) {
    val canStream: Boolean get() = transport.canStream

    suspend fun version(): V2Version = get("/api/health")

    /** Everything the screens list, as the live link sends it first (the service sends it no other way). */
    suspend fun snapshot(timeoutMs: Long = 20_000): V2Snapshot {
        if (!transport.canStream) {
            val e = V2Events.parse(transport.readSnapshot(timeoutMs)) as? V2Event.Snapshot ?: throw IOException("Shepherdr sent no snapshot")
            return e.snapshot
        }
        return V2Live(transport.webSocket("/api/ws")).use { link ->
            withTimeoutOrNull(timeoutMs) {
                var snap: V2Snapshot? = null
                while (snap == null) {
                    val e = link.next() ?: throw IOException("Shepherdr closed the live link")
                    if (e is V2Event.Snapshot) snap = e.snapshot
                }
                snap
            } ?: throw IOException("Shepherdr sent no snapshot")
        }
    }

    /** Sends [action] ([V2Actions]); its answer, or its own reason in the exception. */
    suspend fun act(action: JsonObject): JsonObject {
        val o = ok(transport.http("POST", "/api/action", action.toString(), timeoutMs = 0))
        if ((o["ok"] as? JsonPrimitive)?.booleanOrNull == false || o["error"] is JsonPrimitive) {
            throw IOException((o["error"] as? JsonPrimitive)?.content ?: "Shepherdr refused it")
        }
        return o
    }

    /** Sends [action] and gives its answer as it is, refused or not (a refusal can carry more than its words). */
    suspend fun actRaw(action: JsonObject): V2ActResult {
        val o = ok(transport.http("POST", "/api/action", action.toString(), timeoutMs = 0))
        return V2ActResult((o["ok"] as? JsonPrimitive)?.booleanOrNull != false && o["error"] !is JsonPrimitive, o)
    }

    suspend fun send(chat: String, text: String, reply: String? = null): JsonObject = act(V2Actions.send(chat, text, reply))

    suspend fun answer(
        chat: String,
        question: String,
        choice: Int,
        choices: List<Int>? = null,
        note: String? = null,
        other: String? = null,
    ): JsonObject = act(V2Actions.answer(chat, question, choice, choices, note, other))

    suspend fun react(chat: String, message: String, emoji: String): JsonObject = act(V2Actions.react(chat, message, emoji))

    /** Accept on Ready task [task] (its id, `HERD-4`, or `<machine>:HERD-4`): it merges, as the Accept card's Accept. */
    suspend fun accept(task: String): JsonObject = act(V2Actions.accept(task))

    /** What the Accept card shows of [task] before it is accepted. */
    suspend fun evidence(task: String): V2Evidence =
        ShepherdrJson.decodeFromJsonElement(V2Evidence.serializer(), act(V2Actions.evidence(task)))

    /**
     * Subscribes [endpoint] (the phone's UnifiedPush address) to the service's push alerts, as a web page subscribes
     * (`POST /api/push/subscribe`): the alerts come encrypted for [keys] ([com.makefunstuff.herdr.core.push.WebPush]).
     * [device] names the phone in the service's list.
     */
    suspend fun subscribePush(endpoint: String, keys: com.makefunstuff.herdr.core.push.WebPushKeys, device: String) {
        val body = buildJsonObject {
            put("sub", buildJsonObject {
                put("endpoint", endpoint)
                put("keys", buildJsonObject { put("p256dh", keys.public); put("auth", keys.auth) })
            })
            put("device", device)
        }
        ok(transport.http("POST", "/api/push/subscribe", body.toString()))
    }

    /** Where [task] works ([Worktree.folderOf]), as the web app's Files reads it (`GET /api/worktree?what=dir`). */
    suspend fun taskFolder(task: String): TaskFolder {
        val d = ShepherdrJson.decodeFromJsonElement(V2TaskDir.serializer(), worktree(task, "dir"))
        d.error?.let { throw IOException(it) }
        return Worktree.folderOf(task, d.root?.takeIf { it.isNotEmpty() } ?: throw IOException(Worktree.NO_FOLDER))
    }

    /** [task]'s pull request and its checks, gh asked on its machine (`GET /api/worktree?what=pr`). */
    suspend fun taskPr(task: String): V2PrAnswer =
        ShepherdrJson.decodeFromJsonElement(V2PrAnswer.serializer(), worktree(task, "pr"))

    /** A task's worktree as the web app's Files, Changes and Pull request read it: git and gh run on the task's machine. */
    private suspend fun worktree(task: String, what: String): JsonObject =
        ok(transport.http("GET", "/api/worktree?" + query("task" to task, "what" to what), timeoutMs = 75_000))

    /**
     * Keeps a file on the service's machine for a message (`POST /api/upload`, where the Herdr window keeps pasted
     * ones): its path, to send with a message ([V2Actions.send]'s attachments). An agent on another machine gets a copy.
     */
    suspend fun upload(name: String, data: ByteArray, type: String = "application/octet-stream"): String {
        val (body, contentType) = Wire.form(emptyMap(), "file", name, type, data)
        val r = transport.bytes("POST", "/api/upload", body, contentType)
        val o = ok(HttpReply(r.status, r.body.toString(Charsets.UTF_8)))
        return (o["path"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() } ?: throw IOException("Shepherdr didn't say where it put the file")
    }

    /** A file attached in [chat], by its [path] (`GET /api/file`): its bytes. */
    suspend fun file(chat: String, path: String): ByteArray {
        val r = transport.bytes("GET", "/api/file?" + query("chat" to chat, "path" to path))
        if (r.status == 404) throw IOException("${path.substringAfterLast('/')} isn't there any more")
        if (r.status !in 200..299) throw IOException("Shepherdr answered ${r.status}")
        return r.body
    }

    /** Recipe artifact [id]'s `recipe.json` at version [v] (`GET /api/artifacts/<id>/recipe`); [on]: its machine, when another. */
    suspend fun recipe(id: String, v: Int, on: String? = null): Recipe {
        val r = transport.http("GET", artifactPath(id, "/recipe", query("v" to v.toString(), "on" to on)))
        if (r.status == 404) throw ArtifactGone("Shepherdr has no recipe for it: it was deleted, or it isn't a recipe")
        if (r.status !in 200..299) throw IOException("Shepherdr answered ${r.status}")
        return Recipes.parse(r.body)
    }

    /** A file beside a recipe ([path], a picture) at version [v]; null where it isn't there. */
    suspend fun artifactFile(id: String, v: Int, path: String, on: String? = null): ByteArray? {
        val r = transport.bytes("GET", artifactPath(id, "/file/" + path.split('/').joinToString("/") { enc(it) }, query("v" to v.toString(), "on" to on)))
        return r.body.takeIf { r.status in 200..299 && it.isNotEmpty() }
    }

    /**
     * What was said in [audio] (a recording of [type]), written down by whisper.cpp on the service's machine
     * (`POST /api/dictate`), as the web app's dictation.
     */
    suspend fun dictate(audio: ByteArray, type: String = "audio/mp4"): String {
        val r = transport.bytes("POST", "/api/dictate", audio, type)
        val o = ok(HttpReply(r.status, r.body.toString(Charsets.UTF_8)))
        (o["error"] as? JsonPrimitive)?.contentOrNull?.let { throw IOException(it) }
        return (o["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    /**
     * Whether the service's machine writes speech down ([dictate]: whisper.cpp and its model there). Asked with no
     * audio: a service that has them says it can't read the recording, one without says it needs them, and one
     * older than dictation doesn't know the path.
     */
    suspend fun canDictate(): Boolean {
        val r = transport.bytes("POST", "/api/dictate", ByteArray(0), "audio/mp4")
        if (r.status in 200..299) return true
        val error = runCatching { ShepherdrJson.parseToJsonElement(r.body.toString(Charsets.UTF_8)).jsonObject["error"] }.getOrNull()
        val why = (error as? JsonPrimitive)?.contentOrNull ?: return false
        return r.status == 400 && !why.contains("needs whisper", ignoreCase = true)
    }

    /**
     * A voice call with [chat]'s running agent (`/api/voice`, the Voice add-on): 16 kHz PCM both ways as binary
     * messages, and the call's state as text ones ([VoiceCall]).
     */
    suspend fun voice(chat: String): VoiceCall = VoiceCall(transport.webSocket("/api/voice?" + query("chat" to chat)))

    private fun artifactPath(id: String, part: String, query: String) =
        "/api/artifacts/" + enc(id) + part + query.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun query(vararg kv: Pair<String, String?>) = kv.filter { it.second != null }.joinToString("&") { (k, v) -> "$k=" + enc(v!!) }

    /**
     * Opens the live link: a snapshot comes at once, then each change. Without [snapshots] (a chat's screen) the
     * link may leave them out, and those that come are not read ([V2Event.Other]).
     */
    suspend fun live(snapshots: Boolean = true): V2Live = V2Live(transport.live(snapshots), snapshots)

    /** One chat as it is now: through the live link, the only way chats come (or the transport's own way without one). */
    suspend fun chat(key: String, timeoutMs: Long = 15_000): V2Chat {
        if (!transport.canStream) {
            val e = V2Events.parse(transport.readChat(key, timeoutMs)) as? V2Event.Chat ?: throw IOException("Shepherdr sent no chat $key")
            return e.chat
        }
        return liveChat(key, timeoutMs)
    }

    // One read on Shepherdr's own link: a relay would be an SSH session for each, and screens read several at once.
    private suspend fun liveChat(key: String, timeoutMs: Long): V2Chat = V2Live(transport.webSocket("/api/ws"), snapshots = false).use { link ->
        link.watch(listOf(key))
        withTimeoutOrNull(timeoutMs) {
            var chat: V2Chat? = null
            while (chat == null) {
                val e = link.next() ?: throw IOException("Shepherdr closed the live link")
                // Opening it: the reader is still looking for its log; the next one says what it found.
                if (e is V2Event.Chat && e.chat.key == key && e.chat.notice != "opening") chat = e.chat
            }
            chat
        } ?: throw IOException("Shepherdr sent no chat $key")
    }

    private suspend inline fun <reified T> get(path: String): T =
        ShepherdrJson.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), ok(transport.http("GET", path)))

    private fun ok(r: HttpReply): JsonObject {
        val o = runCatching { ShepherdrJson.parseToJsonElement(r.body).jsonObject }.getOrNull()
        if (r.status !in 200..299) {
            throw IOException((o?.get("error") as? JsonPrimitive)?.content ?: r.body.trim().ifEmpty { "Shepherdr answered ${r.status}" })
        }
        return o ?: throw IOException("Shepherdr gave an answer that isn't JSON")
    }
}

/** The live link (`/api/ws`): snapshots and the watched chats, as they change. */
class V2Live internal constructor(private val socket: LiveSocket, private val snapshots: Boolean = true) : Closeable {
    /** The chats to be sent ([V2Event.Chat]) now and on every change; a newly named one comes at once. */
    suspend fun watch(chats: List<String>) = withContext(Dispatchers.IO) { socket.send(V2Actions.watch(chats).toString()) }

    /** Asks for a pong: the link still works when one comes. */
    suspend fun ping() = withContext(Dispatchers.IO) { socket.send("""{"type":"ping"}""") }

    /** The next event, or null once the server closed the link. Cancelled while it waits, the link is closed. */
    suspend fun next(): V2Event? = coroutineScope {
        val read = async(Dispatchers.IO) { runCatching { socket.receive() } }
        val text = try {
            read.await().getOrThrow()
        } catch (e: CancellationException) {
            // A socket's read can't be interrupted: closing it ends the wait.
            socket.close()
            throw e
        }
        text?.let { V2Events.parse(it, snapshots) }
    }

    /** Every event until the link closes; collecting stops when it does, and cancelling closes it. */
    fun events(): Flow<V2Event> = flow {
        try {
            while (true) emit(next() ?: break)
        } finally {
            close()
        }
    }

    override fun close() = socket.close()
}

/**
 * The live link's events, read as leniently as Shepherdr's newer shapes need: an event never fails as a whole for one
 * part the app can't read. A chat message that doesn't read keeps its words where it has them, else shows as one quiet
 * line ([V2Message.unreadable]); a snapshot's space, task or artifact that doesn't read is left out; an event of a type
 * the app doesn't know, or not JSON at all, is [V2Event.Other].
 */
object V2Events {
    fun parse(text: String, snapshots: Boolean = true): V2Event {
        // A chat's screen has no use for a snapshot, most of a megabyte: not even read; nor the web app's own views.
        if (!snapshots && text.startsWith(SNAPSHOT)) return V2Event.Other("snapshot", JsonNull)
        if (text.startsWith(VIEW)) return V2Event.Other("view", JsonNull)
        val o = runCatching { ShepherdrJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return V2Event.Other("", JsonNull)
        val type = (o["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when {
            type == "snapshot" && o["snapshot"] is JsonObject -> V2Event.Snapshot(snapshot(o["snapshot"] as JsonObject))
            type == "chat" && o["chat"] is JsonObject -> chat(o["chat"] as JsonObject)?.let { V2Event.Chat(it) } ?: V2Event.Other(type, o)
            else -> V2Event.Other(type, o)
        }
    }

    /** A chat, each message read on its own where the whole doesn't read; null without even its key. */
    fun chat(o: JsonObject): V2Chat? {
        runCatching { return ShepherdrJson.decodeFromJsonElement(V2Chat.serializer(), o) }
        val key = (o["key"] as? JsonPrimitive)?.contentOrNull ?: return null
        val rest = runCatching { ShepherdrJson.decodeFromJsonElement(V2Chat.serializer(), JsonObject(o - "messages")) }.getOrElse { V2Chat(key) }
        val messages = (o["messages"] as? JsonArray).orEmpty().mapIndexedNotNull { i, m -> message(m, "$key#$i") }
        return rest.copy(messages = messages)
    }

    /** A message, or its id, who sent it, when, and its words where only those read; null for one that isn't an object. */
    fun message(e: JsonElement, fallbackId: String): V2Message? {
        runCatching { return ShepherdrJson.decodeFromJsonElement(V2Message.serializer(), e) }
        val o = e as? JsonObject ?: return null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val text = str("text")?.takeIf { it.isNotBlank() }
        return V2Message(
            id = str("id") ?: fallbackId,
            who = str("who") ?: "agent",
            text = text,
            at = (o["at"] as? JsonPrimitive)?.doubleOrNull,
            cid = str("cid"),
            unreadable = text == null,
        )
    }

    /** A snapshot, its lists read item by item where the whole doesn't read. */
    fun snapshot(o: JsonObject): V2Snapshot {
        runCatching { return ShepherdrJson.decodeFromJsonElement(V2Snapshot.serializer(), o) }
        val lists = listOf("places", "machines", "tasks", "artifacts", "asks", "last", "read", "needs", "alerts", "settings", "off")
        val base = runCatching { ShepherdrJson.decodeFromJsonElement(V2Snapshot.serializer(), JsonObject(o - lists.toSet())) }.getOrElse { V2Snapshot() }
        fun <T> each(k: String, s: kotlinx.serialization.KSerializer<T>): List<T>? =
            (o[k] as? JsonArray)?.mapNotNull { runCatching { ShepherdrJson.decodeFromJsonElement(s, it) }.getOrNull() }
        fun <T> map(k: String, f: (JsonPrimitive) -> T?): Map<String, T> =
            (o[k] as? JsonObject).orEmpty().mapNotNull { (key, v) -> (v as? JsonPrimitive)?.let(f)?.let { key to it } }.toMap()
        return base.copy(
            places = each("places", V2Place.serializer()).orEmpty(),
            machines = each("machines", V2Machine.serializer()).orEmpty(),
            tasks = each("tasks", V2Task.serializer()).orEmpty(),
            artifacts = each("artifacts", V2Artifact.serializer()),
            needs = each("needs", V2Need.serializer()).orEmpty(),
            alerts = each("alerts", V2Alert.serializer()).orEmpty(),
            settings = each("settings", V2SettingSection.serializer()).orEmpty(),
            off = (o["off"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            asks = map("asks") { it.contentOrNull },
            last = map("last") { it.doubleOrNull },
            read = map("read") { it.doubleOrNull },
        )
    }

    /** The events in a captured list of them (a JSON array), for tests and the previews. */
    fun parseAll(json: String): List<V2Event> =
        (ShepherdrJson.parseToJsonElement(json) as JsonArray).map { parse(it.toString()) }

    /** How Shepherdr's snapshot events start (`JSON.stringify` keeps its `type` first). */
    private const val SNAPSHOT = """{"type":"snapshot""""

    /** How the service's named views start (`service/live.ts`): the web app's, never read here. */
    private const val VIEW = """{"type":"view""""
}
