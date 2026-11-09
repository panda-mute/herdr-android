// Shepherdr 3's service on a fake machine: its API as the app reaches it without a port forward (the previews
// forward nothing), `curl` for the HTTP calls and the machine's `bun` for the snapshot and a chat (CurlTransport),
// answered from fixed demo data in 3.1's shapes (`web/shared/types.ts`). What the app sends (a message, an answer,
// Accept) changes the chat and the board, so a scene that taps Send or a choice shows what follows.
package preview

import com.makefunstuff.herdr.core.ssh.CommandResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FakeShepherdr(
    var version: String = "3.1.2",
    /** What the live link sends first (`Snapshot`). */
    var snapshot: JsonObject = Json.parseToJsonElement(DemoV2.SNAPSHOT).jsonObject,
    /** Each chat as its live link sends it (`Chat`), by its key. */
    var chats: Map<String, JsonObject> = DemoV2.chats(),
) {
    init {
        snapshot = DemoV2.withBrowsers(DemoV2.withAutopilot(snapshot))
    }

    /** The tasks whose browser is open now (its agent checked a page): AND-19's, on its dev server. */
    var browsers: Set<String> = setOf("AND-19")

    /** When set, reading any chat fails with these words (Shepherdr down, or the chat's file unreadable). */
    var chatRefused: String? = null

    /** The bodies of `POST /api/action`, as sent. */
    val actions = ArrayList<JsonObject>()

    /** The + menu's Terminal (`tab-new` with no agent) on space [place]: what the window opens. */
    var onNewTerminal: (place: String) -> Unit = {}

    /** A `curl` to its API or the `bun` that reads the snapshot or a chat; null for any other command. */
    fun run(command: String, stdin: String): CommandResult? {
        val w = Shell.words(command)
        // `sh -c 'exec curl … -H "Authorization: Bearer $(cat …)"'` (CurlTransport.command): the curl inside.
        val inner = if (w.getOrNull(0) == "sh" && w.getOrNull(1) == "-c" && w.size >= 3) Shell.words(w[2]).dropWhile { it == "exec" } else emptyList()
        if (inner.firstOrNull() == "curl") return curl(inner, stdin)
        // A file up or down (CurlTransport.bytes): base64 both ways.
        if (w.firstOrNull() == "sh" && "/api/upload" in command) return bytes(200, upload(String(java.util.Base64.getMimeDecoder().decode(stdin.trim()), Charsets.ISO_8859_1)).toByteArray())
        if (w.firstOrNull() == "sh" && "/api/file" in command) return bytes(200, ByteArray(64))
        if (w.firstOrNull() == "sh" && "/api/dictate" in command) return bytes(200, """{"text":"merge the board fix once its checks pass"}""".toByteArray())
        // A picture beside a recipe.
        if (w.firstOrNull() == "sh" && "/api/artifacts/" in command && "/file/" in command) {
            val m = Regex("""/api/artifacts/([^/?']+)/file/([^?']+)""").find(command)
            return DemoArtifacts.picture(m?.groupValues?.get(1).orEmpty(), m?.groupValues?.get(2).orEmpty())?.let { bytes(200, it) } ?: bytes(404, ByteArray(0))
        }
        if ("@@shepherdr-chat@@" in command) {
            chatRefused?.let { return CommandResult(3, "", it) }
            // The snapshot (CurlTransport.snapshotCommand): the first event of its kind.
            if ("const k=" !in command) return CommandResult(0, "@@shepherdr-chat@@" + obj("type" to "snapshot", "snapshot" to snapshot) + "\n", "")
            val key = Regex("""const k=("(?:[^"\\]|\\.)*")""").find(command)?.groupValues?.get(1)
                ?.let { Json.parseToJsonElement(it).jsonPrimitive.content } ?: return CommandResult(3, "", "no chat")
            val chat = chats[key] ?: obj("key" to key, "messages" to JsonArray(emptyList()))
            return CommandResult(0, "@@shepherdr-chat@@" + obj("type" to "chat", "chat" to chat) + "\n", "")
        }
        return null
    }

    /** The uploads, as their paths where the service keeps them. */
    val uploads = ArrayList<String>()

    /** A file sent as a form: where it is kept until it goes with a message (`data/chat-attach`). */
    private fun upload(form: String): String {
        val name = Regex("""filename="([^"]*)"""").find(form)?.groupValues?.get(1) ?: "file"
        val path = "/home/demo/.config/herdr-app/data/chat-attach/web-mh2k1a${uploads.size}-$name"
        uploads += path
        return """{"path":"$path","name":"$name"}"""
    }

    private fun bytes(status: Int, body: ByteArray) =
        CommandResult(0, java.util.Base64.getMimeEncoder().encodeToString(body) + "\n@@shepherdr-status@@$status", "")

    private fun curl(w: List<String>, stdin: String): CommandResult {
        val method = w.getOrNull(w.indexOf("-X") + 1) ?: "GET"
        val url = w.first { it.startsWith("http://") }
        val path = url.substringAfter("7788")
        val (status, body) = when {
            path == "/api/health" -> 200 to """{"ok":true,"version":"$version","core":true,"jobs":true,"serving":true}"""
            path == "/api/action" && method == "POST" -> act(Json.parseToJsonElement(stdin).jsonObject)
            path.startsWith("/api/artifacts/") && "/recipe" in path -> recipe(path.removePrefix("/api/artifacts/").substringBefore('/'))
            path.startsWith("/api/worktree?") -> worktree(path.substringAfter('?'))
            else -> 404 to """{"error":"not found"}"""
        }
        return CommandResult(0, "$body\n@@shepherdr-status@@$status", "")
    }

    /** The ids of the artifacts its snapshot lists. */
    private fun artifacts(): List<String> = (snapshot["artifacts"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }

    /** `/api/artifacts/<id>/recipe`. */
    private fun recipe(id: String): Pair<Int, String> {
        if (id !in artifacts()) return 404 to """{"error":"No recipe here."}"""
        return 200 to (DemoArtifacts.RECIPES[id] ?: return 404 to """{"error":"No recipe here."}""")
    }

    /**
     * `/api/worktree?task=…&what=…`, the task view's Files, Changes and Pull request: a task with a branch works in
     * the fake machine's task worktree ([FakeCode.TASK], on its branch); one without has no folder left. Its pull
     * request has passed its checks but one, skipped.
     */
    private fun worktree(query: String): Pair<Int, String> {
        val q = query.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val t = task(q["task"].orEmpty()) ?: return 404 to """{"error":"No such task."}"""
        return when (q["what"]) {
            "dir" -> if (t["branch"] == null) 200 to """{"error":"This task's folder can't be read. It may have been removed after the task finished."}"""
            else 200 to """{"root":"${FakeCode.TASK}","entries":[]}"""
            "pr" -> {
                val pr = t["pr"]?.jsonObject ?: return 200 to """{"why":"This task has no pull request."}"""
                200 to """
                    {"pr":{"number":${pr["number"]},"title":${t["name"]},"state":"open","url":${pr["url"]},"head":"work/9-last-answered","base":"main",
                      "add":86,"del":21,"files":4,"checks":[
                        {"name":"build","state":"pass","url":"${pr["url"]?.jsonPrimitive?.content}/checks","workflow":"Check"},
                        {"name":"unit tests","state":"pass","url":"${pr["url"]?.jsonPrimitive?.content}/checks","workflow":"Check"},
                        {"name":"screens","state":"pass","url":"${pr["url"]?.jsonPrimitive?.content}/checks","workflow":"Android"},
                        {"name":"release notes","state":"skip","workflow":"Release"}]}}
                """.trimIndent()
            }
            else -> 400 to """{"error":"Ask for dir, marks, files, file, diff, patch or pr."}"""
        }
    }

    private fun act(a: JsonObject): Pair<Int, String> {
        // A secret's value is never kept, here neither: only that one was sent.
        actions += if (a["type"]?.jsonPrimitive?.content == "secret") JsonObject(a - "value") else a
        val chat = a["chat"]?.jsonPrimitive?.content.orEmpty()
        when (a["type"]?.jsonPrimitive?.content) {
            // Kept in the vault: the agent no longer waits for it.
            "secret" -> {
                val id = a["card"]?.jsonObject?.get("id")?.jsonPrimitive?.content
                snapshot = JsonObject(snapshot + ("needs" to JsonArray((snapshot["needs"] as? JsonArray).orEmpty().filter { it.jsonObject["id"]?.jsonPrimitive?.content != id })))
            }
            // In at once, the agent at work on it.
            "send" -> change(chat) { c ->
                val words = a.getValue("text").jsonPrimitive.content
                val m = obj("id" to "sent-${actions.size}", "who" to "you", "text" to a.getValue("text"), "at" to Demo.NOW) +
                    listOfNotNull(a["cid"]?.let { "cid" to it }, a["reply"]?.let { r -> "reply" to quote(c, r.jsonPrimitive.content) })
                // Each file sent with it is a message of its own, by the name it was sent as.
                val files = a["attachments"]?.jsonArray.orEmpty().mapIndexed { i, p ->
                    val path = p.jsonPrimitive.content
                    obj("id" to "sent-${actions.size}-f$i", "who" to "you", "at" to Demo.NOW, "file" to obj("name" to path.substringAfterLast('/').replace(Regex("^web-[a-z0-9]+-"), ""), "path" to path))
                }
                val sent = (if (words.isNotEmpty() || files.isEmpty()) listOf(JsonObject(m)) else emptyList()) + files
                JsonObject(c + mapOf("messages" to JsonArray(messages(c) + sent), "working" to JsonPrimitive(true)))
            }
            // The card shows the choice sent; what it asked no longer needs you.
            "answer" -> {
                val q = a.getValue("question").jsonPrimitive.content
                val choice = a.getValue("choice")
                chats.keys.forEach { key -> change(key) { c -> JsonObject(c + ("messages" to JsonArray(messages(c).map { answered(it.jsonObject, q, choice) }))) } }
                snapshot = JsonObject(snapshot + ("needs" to JsonArray((snapshot["needs"] as? JsonArray).orEmpty().filter { it.jsonObject["id"]?.jsonPrimitive?.content != q })))
            }
            // Yours added, or taken back when it is yours already; the agent's stays.
            "react" -> change(chat) { c ->
                val id = a.getValue("message").jsonPrimitive.content
                val emoji = a.getValue("emoji").jsonPrimitive.content
                JsonObject(c + ("messages" to JsonArray(messages(c).map { m -> if (m.jsonObject["id"]?.jsonPrimitive?.content == id) reacted(m.jsonObject, emoji) else m })))
            }
            // What its Accept card shows.
            "evidence" -> {
                val id = a.getValue("task").jsonPrimitive.content
                return DemoV2.evidence(task(id) ?: return 400 to "That task isn't here any more.")
            }
            // A space's Autopilot switch: herdr-task's on or off, the next wake an hour on.
            "mode" -> {
                val place = a.getValue("place").jsonPrimitive.content
                val on = a.getValue("mode").jsonPrimitive.content == "autopilot"
                setPlace(place) { p ->
                    val pilot = p["pilot"]?.jsonObject ?: return 400 to "Autopilot is set on that space's own machine."
                    JsonObject(p + mapOf("mode" to JsonPrimitive(if (on) "autopilot" else "regular"),
                        "pilot" to JsonObject(pilot + mapOf("on" to JsonPrimitive(on), "next_run" to if (on) JsonPrimitive(Demo.NOW + 60 * 60_000) else kotlinx.serialization.json.JsonNull))))
                }
            }
            // Settings › Autopilot: its row takes the value; a schedule herdr-task can't read is refused in its words.
            "setting" -> {
                val key = a.getValue("key").jsonPrimitive.content
                val value = a["value"]?.jsonPrimitive?.content.orEmpty()
                if (key == "autopilot.every" && value.isNotEmpty() && !Regex("""^(every \d+ (minutes|hours?)|weekdays \d{1,2}:\d{2}|hourly|daily \d{1,2}:\d{2})( on weekdays)?""").containsMatchIn(value)) {
                    return 200 to """{"ok":false,"error":${JsonPrimitive("Unknown time '$value'. " + DemoV2.HOW)}}"""
                }
                snapshot = JsonObject(snapshot + ("settings" to JsonArray((snapshot["settings"] as? JsonArray).orEmpty().map { sec ->
                    val o = sec.jsonObject
                    JsonObject(o + ("rows" to JsonArray(o["rows"]?.jsonArray.orEmpty().map { r ->
                        if (r.jsonObject["key"]?.jsonPrimitive?.content == key) JsonObject(r.jsonObject + ("value" to JsonPrimitive(value))) else r
                    })))
                })))
                return 200 to """{"ok":true}"""
            }
            // The tab menu: Switch agent's list, the model menu, the Assistant's workers before it closes.
            "tab-agents" -> return 200 to DemoV2.TAB_AGENTS
            "tab-new" -> if (a["kind"] == null) onNewTerminal(a.getValue("place").jsonPrimitive.content)
            "model-menu" -> return 200 to """{"ok":true,"menu":${DemoV2.MODEL_MENU}}"""
            "assistant-close" -> if (a["mode"]?.jsonPrimitive?.content == "count") return 200 to """{"ok":true,"workers":4}"""
            // Accepted: it merges.
            "accept" -> {
                val id = a.getValue("task").jsonPrimitive.content
                if (task(id)?.get("status")?.jsonPrimitive?.content != "ready") return 400 to "It isn't Ready."
                setTask(id) { t -> JsonObject(t + mapOf("status" to JsonPrimitive("merging"), "sub" to JsonPrimitive("Merges once its checks pass"))) }
            }
        }
        a["type"]?.jsonPrimitive?.content?.takeIf { it.startsWith("artifact-") }?.let { return 200 to """{"ok":true,"to":"AND-19 agent"}""" }
        return 200 to """{"ok":true}"""
    }

    /** Gives space [place] its Assistant's tab ([assistant], `assistantTab`) and its other [tabs] (`tabs`, a JSON list). */
    fun tabs(place: String, assistant: String, tabs: String) {
        val places = (snapshot["places"] as? JsonArray).orEmpty().map { p ->
            val o = p.jsonObject
            if (o["id"]?.jsonPrimitive?.content != place) o
            else JsonObject(o + mapOf("assistantTab" to Json.parseToJsonElement(assistant), "tabs" to Json.parseToJsonElement(tabs)))
        }
        snapshot = JsonObject(snapshot + ("places" to JsonArray(places)))
    }

    private fun tasks() = (snapshot["tasks"] as? JsonArray).orEmpty().map { it.jsonObject }

    private inline fun setPlace(id: String, f: (JsonObject) -> JsonObject) {
        snapshot = JsonObject(snapshot + ("places" to JsonArray((snapshot["places"] as? JsonArray).orEmpty().map { val p = it.jsonObject; if (p["id"]?.jsonPrimitive?.content == id) f(p) else p })))
    }

    private fun task(id: String) = tasks().firstOrNull { it["id"]?.jsonPrimitive?.content == id }

    private fun setTask(id: String, f: (JsonObject) -> JsonObject) {
        snapshot = JsonObject(snapshot + ("tasks" to JsonArray(tasks().map { if (it["id"]?.jsonPrimitive?.content == id) f(it) else it })))
    }

    private fun change(key: String, f: (JsonObject) -> JsonObject) {
        chats[key]?.let { chats = chats + (key to f(it)) }
    }

    private fun messages(c: JsonObject) = c["messages"]?.jsonArray.orEmpty()

    private fun quote(c: JsonObject, id: String): JsonObject {
        val m = messages(c).firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == id }?.jsonObject
        return obj("id" to id, "who" to "agent", "text" to (m?.get("text") ?: JsonPrimitive("")))
    }

    /** [m] with yours [emoji] toggled: "me" added or gone, "both" when the agent gave it too. */
    private fun reacted(m: JsonObject, emoji: String): JsonObject {
        val list = (m["reactions"] as? JsonArray).orEmpty().map { it.jsonObject }
        val was = list.firstOrNull { it["emoji"]?.jsonPrimitive?.content == emoji }
        val by = was?.get("by")?.jsonPrimitive?.content
        val now = when (by) {
            null -> list + obj("emoji" to emoji, "by" to "me")
            "me" -> list - was
            "both" -> list.map { if (it === was) obj("emoji" to emoji, "by" to "them") else it }
            else -> list.map { if (it === was) obj("emoji" to emoji, "by" to "both") else it }
        }
        return JsonObject(m + ("reactions" to JsonArray(now)))
    }

    private fun answered(m: JsonObject, q: String, choice: JsonElement): JsonObject {
        val question = m["question"]?.jsonObject
        if (question != null && question["id"]?.jsonPrimitive?.content == q) return JsonObject(m + ("question" to JsonObject(question + ("sent" to choice))))
        return m
    }
}

/** devbox on Shepherdr 3: its spaces home, herdr-android and api-server, and their Assistants' chats at [Demo.NOW]. */
object DemoV2 {
    /** The spaces' ids; [chat] gives each Assistant's chat key. */
    const val HOME = "sp-home"
    const val APP = "sp-herdr-android"
    const val API = "sp-api-server"

    /** herdr-android's Assistant's tab, as `Place.assistantTab` names it once it has a tab. */
    const val TAB_ASSISTANT = """{"id":"tb-herdr-android","name":"Assistant","named":true,"state":"blocked","live":true,"cli":"claude","agent":"claude"}"""

    /** What `tab-agents` answers on devbox: Claude Code and Codex installed, Pi not. */
    const val TAB_AGENTS = """{"ok":true,"agents":[{"kind":"claude","name":"Claude Code","installed":true},{"kind":"codex","name":"Codex","installed":true},{"kind":"pi","name":"Pi","installed":false}]}"""

    /** The model menu of a Claude Code on devbox (`service/agents.ts` `menu()`): Opus 5.5 at High, Auto. */
    val MODEL_MENU = """
        {"current":{"agent":"claude","model":"opus","effort":"high","fast":false,"perm":"auto"},
         "menus":{
           "claude":{"name":"Claude Code","fastLocked":false,
             "perms":[{"id":"manual","label":"Ask"},{"id":"auto","label":"Auto"},{"id":"acceptEdits","label":"Accept edits"},{"id":"plan","label":"Plan"},{"id":"bypassPermissions","label":"Full access"}],
             "models":[{"id":"opus","name":"Opus 5.5","efforts":["low","medium","high","xhigh","max"],"defaultEffort":"high","fast":true},
                       {"id":"opus[1m]","name":"Opus 5.5 · 1M","efforts":["low","medium","high","xhigh","max"],"defaultEffort":"high","fast":true},
                       {"id":"claude-fable-5-1","name":"Fable 5.1","efforts":["low","medium","high","xhigh","max"],"defaultEffort":"high","fast":false},
                       {"id":"sonnet","name":"Sonnet 5.5","efforts":["low","medium","high","xhigh","max"],"defaultEffort":"medium","fast":false}]},
           "codex":{"name":"Codex","fastLocked":false,
             "perms":[{"id":"read-only","label":"Read only"},{"id":"auto","label":"Auto"},{"id":"full","label":"Full access"}],
             "models":[{"id":"gpt-5.5","name":"GPT-5.5","efforts":["low","medium","high","xhigh"],"defaultEffort":"medium","fast":true}]}},
         "permDefault":{"claude":"manual","codex":"auto"},"remote":null}
    """.trimIndent()

    /** A space's Assistant chat key (`Place.chat`). */
    fun chat(space: String) = "p:$space"

    /** herdr-task's words on how to write a schedule (`automations.py` HOW). */
    const val HOW = "Write times like \"weekdays 9:00\", \"daily 9:00,17:30\", \"mon,thu 8:15\", \"every 30 minutes\", " +
        "\"every 2 hours on weekdays from 8:00 to 18:00\", \"hourly\" or \"once 2026-11-05 9:00\" (this machine's time zone)."

    /** The heartbeat devbox's Autopilot works from. */
    const val HEARTBEAT = "Direction: keep herdr-android's main green and its board short.\n\nPriorities:\n- Failing checks first\n- Then the oldest Ready task\n\n" +
        "Constraints: never merge; ask before anything that touches the release.\n\nReporting: one line after a wake, an alert only when a check fails.\n"

    /**
     * [s] with Autopilot as 3.1's service sends it: every space of this machine its switch (off), and Settings ›
     * Autopilot, Everywhere's page (`service/compat.ts` headerPilot and pilotRows). A snapshot that has them is kept.
     */
    fun withAutopilot(s: JsonObject): JsonObject {
        val places = (s["places"] as? JsonArray).orEmpty().map { p ->
            val o = p.jsonObject
            if ("pilot" in o || "/" in o["id"]?.jsonPrimitive?.content.orEmpty()) o
            else JsonObject(o + mapOf("mode" to JsonPrimitive("regular"), "pilot" to pilot(on = false)))
        }
        val settings = (s["settings"] as? JsonArray).orEmpty()
        val page = Json.parseToJsonElement(
            """{"name":"Autopilot","level":"all","about":${JsonPrimitive(com.makefunstuff.herdr.core.shepherdr.Autopilot.ABOUT)},"rows":[
              {"label":"Heartbeat","key":"autopilot.heartbeat","value":${JsonPrimitive(HEARTBEAT)},"doc":true,"hint":"What it works from: direction, priorities, limits and when to stop.","file":"~/.config/herdr-core/autopilot/everywhere.md"},
              {"label":"Schedule","key":"autopilot.every","value":"every 60 minutes","text":true,"hint":"When it wakes by itself."},
              {"label":"Events","key":"autopilot.events","value":"ready,failed","hint":"These wake a space's Assistant at once when they happen to its tasks."}]}""",
        )
        val withPage: JsonElement = if (settings.any { it.jsonObject["name"]?.jsonPrimitive?.content == "Autopilot" }) JsonArray(settings) else JsonArray(settings + page)
        return JsonObject(s + mapOf<String, JsonElement>("places" to JsonArray(places), "settings" to withPage))
    }

    /**
     * [s] with each task's Browser as 3.1's service sends it for this machine's tasks (`service/compat.ts`): ready. A
     * task that has one is kept.
     */
    fun withBrowsers(s: JsonObject): JsonObject {
        val tasks = (s["tasks"] as? JsonArray ?: return s).map { t ->
            val o = t.jsonObject
            if ("browser" in o) o else JsonObject(o + ("browser" to Json.parseToJsonElement("""{"ready":true}""")))
        }
        return JsonObject(s + ("tasks" to JsonArray(tasks)))
    }

    /** A space's `pilot`: [on], its next wake 40 minutes on, woken by Ready and Failed; [blank]: the heartbeat unwritten. */
    fun pilot(on: Boolean, blank: Boolean = false, pending: Boolean = false) = Json.parseToJsonElement(
        """{"on":$on,"next_run":${if (on) Demo.NOW + 40 * 60_000 else null},"pending":$pending,"inflight":false,"events":["ready","failed"],"blank":$blank}""",
    )

    /** [space]'s Autopilot switched on in [f]'s snapshot, its heartbeat still the starter where [blank]. */
    fun autopilotOn(f: FakeShepherdr, space: String, blank: Boolean = false, pending: Boolean = false) {
        f.snapshot = JsonObject(f.snapshot + ("places" to JsonArray((f.snapshot["places"] as? JsonArray).orEmpty().map { p ->
            val o = p.jsonObject
            if (o["id"]?.jsonPrimitive?.content == space) JsonObject(o + mapOf("mode" to JsonPrimitive("autopilot"), "pilot" to pilot(true, blank, pending))) else o
        })))
    }

    private fun place(id: String, name: String, status: String, folder: String) = """
        {"id":"$id","name":"$name","machine":"devbox","level":0,"status":"$status","mode":"regular","chat":"p:$id",
         "header":"Assistant · Claude Code","where":"$folder","store":{"folder":"${folder.replace("~", "/home/demo")}","key":"|${folder.replace("~", "/home/demo")}","name":"$name"},
         "assistantTab":{"name":"Assistant","named":true,"state":"${if (status == "need") "blocked" else "done"}","live":true,"cli":"claude"}}
    """.trimIndent()

    val SNAPSHOT = """
        {"name":"Shepherdr","host":"devbox","user":"Sam","terminalTransport":"core",
         "machines":[{"id":"m:devbox","machine":"devbox","name":"This computer","ownName":"This computer","status":"idle","here":true}],
         "places":[
           ${place(HOME, "home", "idle", "~")},
           ${place(APP, "herdr-android", "need", "~/src/herdr-android")},
           ${place(API, "api-server", "work", "~/src/api-server")}
         ],
         "tasks":[
           {"id":"AND-13","name":"Keep the Board card in place on reconnect","status":"ready","place":"$APP","listName":"herdr-android","sub":"Ready 5m ago","age":"5m",
            "agent":{"chat":"AND-13/worker","role":"worker","cli":"claude","status":"idle","running":true},"pr":{"number":212,"url":"https://github.com/example/herdr-android/pull/212"},
            "report":{"msg":"Card stays put on reconnect","progress":100}},
           {"id":"AND-14","name":"Fix the flaky upload test","status":"need","place":"$APP","listName":"herdr-android","sub":"Plan to approve",
            "agent":{"chat":"AND-14/worker","role":"worker","cli":"claude","status":"need","running":true},"report":{"msg":"Plan to approve","progress":10,"paused":true}},
           {"id":"AND-15","name":"Voice input in the task composer","status":"work","place":"$APP","listName":"herdr-android","sub":"Wiring the recognizer","left":"25m",
            "agent":{"chat":"AND-15/worker","role":"worker","cli":"codex","status":"work","running":true},"report":{"msg":"Wiring the recognizer","progress":40,"eta":25}},
           {"id":"API-7","name":"Speed up /jobs on large tables","status":"work","place":"$API","listName":"api-server","sub":"Adding the index","left":"6m",
            "agent":{"chat":"API-7/worker","role":"worker","cli":"codex","status":"work","running":true},"report":{"msg":"Adding the index","progress":60,"eta":6}}
         ],
         "needs":[
           {"id":"plan-and-14","chat":"AND-14/worker","place":"$APP","task":"AND-14","at":1793228000000,
            "question":{"id":"plan-and-14","title":"Plan for AND-14","kind":"Plan","choices":[{"label":"Approve"},{"label":"Keep planning"}],"noteRequiredFor":1,"to":"AND-14/worker",
              "plan":"1. Run the upload test 50 times to catch the failure\n2. Wait for the temp file to close before reading it back\n3. Add a test that uploads while the connection drops"}}
         ],
         "asks":{},"last":{},"read":{}}
    """.trimIndent()

    private val APP_CHAT = """
        {"key":"p:$APP","cli":"claude","working":false,"top":true,"agent":{"kind":"claude","words":"Opus 5.5 · High","modelLabel":"Opus 5.5"},"messages":[
          {"id":"m1","who":"you","at":1,"text":"The Board card jumps when a machine reconnects, and the upload test is flaky again. Can you get agents on both?"},
          {"id":"m2","who":"agent","at":2,"text":"I added two tasks and started an agent on each:\n\n- **AND-13** Keep the Board card in place on reconnect\n- **AND-14** Fix the flaky upload test\n\nEach opens a pull request and waits for your Accept before merging."},
          {"id":"tn:AND-14:started:3","who":"agent","at":3,"news":{"task":"AND-14","ref":"AND-14","mark":"started","what":"started","text":"Fix the flaky upload test"}},
          {"id":"ask:plan-and-14","who":"agent","at":4,"question":{"id":"plan-and-14","title":"Plan for AND-14","kind":"Plan","choices":[{"label":"Approve"},{"label":"Keep planning"}],"noteRequiredFor":1,"to":"p:$APP",
            "by":{"ref":"AND-14","title":"Fix the flaky upload test","chat":"AND-14/worker"},
            "plan":"1. Run the upload test 50 times to catch the failure\n2. Wait for the temp file to close before reading it back\n3. Add a test that uploads while the connection drops"}},
          {"id":"tn:AND-13:ready:5","who":"agent","at":5,"news":{"task":"AND-13","ref":"AND-13","mark":"ready","what":"is ready","text":"Card stays put on reconnect","accept":true}},
          {"id":"m6","who":"agent","at":6,"text":"AND-13 passed its checks and its review. The card stays put now when a machine reconnects; I checked it on the Board and Agents screens."},
          {"id":"m7","who":"agent","at":7,"artifact":{"id":"rn09","v":2}}
        ]}
    """.trimIndent()

    private val HOME_CHAT = """
        {"key":"p:$HOME","cli":"claude","working":false,"messages":[
          {"id":"h1","who":"agent","at":1,"text":"Morning. Nothing on devbox needs you from here: herdr-android has one task to accept, api-server is at work on API-7."},
          {"id":"h2","who":"you","at":2,"text":"Thanks. Is the backup to the NAS still running at night?"},
          {"id":"h3","who":"agent","at":3,"text":"Yes, it ran at 02:00 and finished in 14 minutes."}
        ]}
    """.trimIndent()

    /** AND-14's own chat: its agent's plan, which its Assistant's chat shows headed by the task. */
    private val AND14_CHAT = """
        {"key":"AND-14/worker","cli":"claude","working":false,"messages":[
          {"id":"t1","who":"agent","at":1,"text":"The upload test fails about once in 30 runs: it reads the temp file back before it is closed. Here's my plan."},
          {"id":"ask:plan-and-14","who":"agent","at":2,"question":{"id":"plan-and-14","title":"Plan for AND-14","kind":"Plan","choices":[{"label":"Approve"},{"label":"Keep planning"}],"noteRequiredFor":1,"to":"AND-14/worker",
            "plan":"1. Run the upload test 50 times to catch the failure\n2. Wait for the temp file to close before reading it back\n3. Add a test that uploads while the connection drops"}}
        ]}
    """.trimIndent()

    private val API_CHAT = """
        {"key":"p:$API","cli":"codex","working":true,"doing":"Reading the slow-query log","messages":[
          {"id":"a1","who":"you","at":1,"text":"/jobs is slow on the big tenant again. Look into it?"},
          {"id":"a2","who":"agent","at":2,"text":"API-7 is on it: it's adding an index on (tenant, created_at)."},
          {"id":"tn:API-7:started:3","who":"agent","at":3,"news":{"task":"API-7","ref":"API-7","mark":"started","what":"started","text":"Speed up /jobs on large tables"}}
        ]}
    """.trimIndent()

    /**
     * devbox's board on Shepherdr 3 for the Board and task scenes (`demo/shepherdr/`): home, herdr-android and
     * website, a task in every status, two tasks whose cards ask (one choice or your own words; several), and
     * website's Assistant waiting on a reply; the Assistants' chats above too.
     */
    /**
     * The home space's Assistant asking for a secret by name, as the service lists it under needs (`secretAsk` in its
     * compat.ts): where it can be kept, narrowest first. No value anywhere: the box starts empty.
     */
    const val SECRET_NEED = """
        {"id":"secret:t3:VERCEL_TOKEN","chat":"p:sp-home","place":"sp-home","at":1793228000000,
         "secret":{"id":"secret:t3:VERCEL_TOKEN","name":"VERCEL_TOKEN","why":"To deploy the preview of the new pricing page",
          "levels":[{"id":"p::sp-home","label":"This space"},{"id":"m:","label":"This computer"},{"id":"all","label":"Everywhere"}]}}
    """

    fun board() = FakeShepherdr(
        snapshot = Json.parseToJsonElement(Demo.resource("shepherdr/snapshot.json")).jsonObject,
        chats = chats() + Json.parseToJsonElement(Demo.resource("shepherdr/chats.json")).jsonObject.mapValues { it.value.jsonObject },
    )

    fun chats(): Map<String, JsonObject> =
        listOf(APP_CHAT, HOME_CHAT, API_CHAT, AND14_CHAT).associate { Json.parseToJsonElement(it).jsonObject.let { c -> c.getValue("key").jsonPrimitive.content to c } }

    /** A Ready task's Accept card, as `herdr-task show --evidence` answers: its commits, files, checks and proof. */
    fun evidence(t: JsonObject): Pair<Int, String> {
        val id = t.getValue("id").jsonPrimitive.content
        val state = when (t["status"]?.jsonPrimitive?.content) { "ready" -> "ready"; "done" -> "done"; "merging" -> "merging"; else -> "running" }
        val title = t["name"]?.jsonPrimitive?.content.orEmpty()
        val summary = t["said"]?.jsonPrimitive?.content ?: "The Board card stays where it is when a machine reconnects, on the Board and Agents screens."
        return 200 to """
            {"id":7,"ref":"$id","title":${JsonPrimitive(title)},"state":"$state","status_text":"${state.replaceFirstChar { it.uppercase() }}","evidence":{
              "summary":${JsonPrimitive(summary)},
              "details":"Kept the card's key stable across reconnects; added a test that reconnects twice. Checked on the Board and Agents screens, dark and light.",
              "proof":{"id":"bl3x","title":"$id: Board load times"},"git":true,"scope":"task/$id into main",
              "commits":{"count":3,"list":[{"sha":"4f2a91c","subject":"Keep the card's key across reconnects"},{"sha":"9b03d17","subject":"Test: reconnect twice"},{"sha":"e1c55a0","subject":"Board: no jump on reconnect"}]},
              "diff":{"files":4,"added":86,"removed":21,"list":[{"path":"app/src/main/kotlin/com/example/ui/board/BoardScreen.kt","added":40,"removed":12},{"path":"core/src/test/kotlin/ReconnectTest.kt","added":38,"removed":0}]},
              "checks":{"state":"passed","text":"All 4 passed"}}}
        """.trimIndent()
    }
}
