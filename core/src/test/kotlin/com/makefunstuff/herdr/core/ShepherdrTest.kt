package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.BoardApi
import com.makefunstuff.herdr.core.board.CardAnswer
import com.makefunstuff.herdr.core.board.WorkCard
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.board.WorkTasks
import com.makefunstuff.herdr.core.shepherdr.Accept
import com.makefunstuff.herdr.core.shepherdr.BoardDetection
import com.makefunstuff.herdr.core.shepherdr.CurlTransport
import com.makefunstuff.herdr.core.shepherdr.ForwardTransport
import com.makefunstuff.herdr.core.shepherdr.LiveRelay
import com.makefunstuff.herdr.core.shepherdr.ShepherdrClient
import com.makefunstuff.herdr.core.shepherdr.ShepherdrJson
import com.makefunstuff.herdr.core.shepherdr.SpaceChat
import com.makefunstuff.herdr.core.shepherdr.SpaceChatItem
import com.makefunstuff.herdr.core.shepherdr.TaskFolder
import com.makefunstuff.herdr.core.shepherdr.V2Actions
import com.makefunstuff.herdr.core.shepherdr.V2Board
import com.makefunstuff.herdr.core.shepherdr.V2Event
import com.makefunstuff.herdr.core.shepherdr.V2Events
import com.makefunstuff.herdr.core.shepherdr.V2Evidence
import com.makefunstuff.herdr.core.shepherdr.V2EvidenceBody
import com.makefunstuff.herdr.core.shepherdr.V2Snapshot
import com.makefunstuff.herdr.core.shepherdr.V2Status
import com.makefunstuff.herdr.core.shepherdr.V2Task
import com.makefunstuff.herdr.core.shepherdr.V2TaskAgent
import com.makefunstuff.herdr.core.shepherdr.V2TaskStatus
import com.makefunstuff.herdr.core.shepherdr.V2Version
import com.makefunstuff.herdr.core.shepherdr.V2WorkBoard
import com.makefunstuff.herdr.core.shepherdr.VoiceCall
import com.makefunstuff.herdr.core.shepherdr.WebSocketLink
import com.makefunstuff.herdr.core.shepherdr.Wire
import com.makefunstuff.herdr.core.shepherdr.Worktree
import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.ssh.Duplex
import com.makefunstuff.herdr.core.ssh.PortForward
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shepherdr 3's service as the app reads it: `resources/shepherdr` (its README says which answers are captured). */
class ShepherdrTest {
    private fun fixture(name: String) = javaClass.getResource("/shepherdr/$name")!!.readText()

    /** [name]'s snapshot event as Shepherdr sends it: the file itself, or the snapshot in its list of events. */
    private fun snapshotEvent(name: String = "snapshot.json"): String {
        val text = fixture(name)
        if (!text.trimStart().startsWith("[")) return text
        return (ShepherdrJson.parseToJsonElement(text) as kotlinx.serialization.json.JsonArray)
            .first { it.toString().startsWith("""{"type":"snapshot"""") }.toString()
    }

    private fun snapshot(name: String = "snapshot.json"): V2Snapshot = (V2Events.parse(snapshotEvent(name)) as V2Event.Snapshot).snapshot

    private fun chats(name: String): Map<String, com.makefunstuff.herdr.core.shepherdr.V2Chat> =
        V2Events.parseAll(fixture(name)).filterIsInstance<V2Event.Chat>().associate { it.chat.key to it.chat }

    // ---- what a running 3.1.2 sent ----------------------------------------------------------------

    @Test
    fun readsTheHealthOf312() {
        val v = ShepherdrJson.decodeFromString(V2Version.serializer(), fixture("health.json"))
        assertEquals("3.1.2", v.version)
        assertEquals(3, v.major)
        assertEquals(1, v.minor)
        assertEquals(BoardApi.V2("3.1.2"), BoardDetection.decide(v))
        // 2.x isn't spoken any more: a machine still on it has no board.
        assertEquals(BoardApi.None, BoardDetection.decide(V2Version(version = "2.1.0")))
        assertEquals(BoardApi.None, BoardDetection.decide(V2Version(version = "nonsense")))
        assertEquals(BoardApi.None, BoardDetection.decide(null))
    }

    @Test
    fun readsTheSnapshot312Sent() {
        val s = snapshot()
        assertEquals("devbox", s.host)
        assertEquals("core", s.terminalTransport)
        assertEquals(listOf("voice-notes", "homelab", "demo-app"), s.places.map { it.name })
        val p = s.places.first { it.name == "demo-app" }
        assertEquals("p:" + p.id, p.chat)
        assertEquals("Assistant · Claude Code", p.header)
        assertEquals("|/home/u/Work/demo-app", p.store!!.key)
        assertTrue(p.assistantTab!!.live)
        assertEquals(0, p.level)
        val work = s.task("DEMO-8")!!
        assertEquals(V2TaskStatus.Work, work.status)
        assertEquals("6h", work.left)
        assertEquals(2, work.report!!.progress)
        assertEquals(359, work.report!!.eta)
        assertEquals("DEMO-8/worker", work.agent!!.chat)
        assertTrue(work.agent!!.running)
        assertEquals("demo-app", work.listName)
        // A task no space works in: no place, its list's name.
        val queued = s.task("DEMO-9")!!
        assertEquals("", queued.place)
        assertEquals(V2TaskStatus.Queued, queued.status)
        assertEquals("after DEMO-8", queued.sub)
        assertTrue(s.needs.isEmpty())
        assertEquals(0, s.waiting)
    }

    @Test
    fun theSnapshot312SentAsTheBoardSeesIt() {
        val o = V2Board.overview(snapshot())
        assertEquals(3, o.spaces.size)
        assertFalse(o.spaces.any { it.needsYou })
        assertEquals(listOf("DEMO-8", "DEMO-9", "HOME-16", "DEMO-7"), o.tasks.map { it.id })
        val t = o.tasks[0]
        assertEquals(WorkStatus.Work, t.status)
        assertEquals("Working", t.status.label)
        assertEquals("6h", t.left)
        assertEquals(2, t.progress)
        assertEquals("Reading the 3.1 docs", t.line)
        // Shepherdr's own second line, its first letter up as its web app shows it.
        assertEquals("After DEMO-8", o.tasks[1].line)
        assertEquals("To do", o.tasks[2].status.label)
        val app = o.spaces.first { it.name == "demo-app" }
        assertEquals("p:" + app.id, app.chat)
        assertEquals("|/home/u/Work/demo-app", app.store)
        // A task no space works in (queued, done) is in its list's, by name.
        assertEquals(listOf("DEMO-8"), o.tasksIn(app.id).map { it.id })
        assertEquals(listOf("DEMO-8", "DEMO-9", "DEMO-7"), o.tasks.filter { it.listName == "demo-app" }.map { it.id })
    }

    @Test
    fun readsTheChats312Sent() {
        val c = chats("chats.json")
        val assistant = c.getValue("p:sp00000000000000000000000000000a01")
        // Its log wasn't found on that machine: the notice Shepherdr shows instead of the conversation.
        assertEquals("none", assistant.notice)
        assertEquals("Opus 5.5", assistant.agent!!.modelLabel)
        val news = assistant.messages.single().news!!
        assertEquals("DEMO-8", news.ref)
        assertEquals("started", news.mark)
        val items = SpaceChat.items("sp00000000000000000000000000000a01", assistant)
        val u = assertIs<SpaceChatItem.Update>(items.single())
        assertEquals(WorkStatus.Work, u.status)
        assertEquals("started · Shepherdr 3 client", u.text)
        assertEquals(listOf("DEMO-8 started Shepherdr 3 client"), V2Board.thread("x", assistant).lines.map { it.text })
        val worker = c.getValue("DEMO-8/worker")
        assertTrue(worker.working)
        assertEquals("Running ./gradlew -p core test", worker.doing)
    }

    // ---- what waits on you, in 3.x's shapes -------------------------------------------------------

    @Test
    fun needsReadyAndMergingInShepherdrsOrder() {
        val o = V2Board.overview(snapshot("waiting.json"))
        // Needs you, Ready, Merging, Working (core/tasks.js ORDER); the archived one left out.
        assertEquals(listOf("DEMO-5", "DEMO-4", "vm:API-2", "DEMO-7", "DEMO-6"), o.tasks.map { it.id })
        assertEquals(listOf("Needs you", "Ready", "Ready", "Merging", "Working"), o.tasks.map { it.status.label })
        val api = o.tasks.first { it.id == "vm:API-2" }
        assertEquals("API-2", api.name)
        assertTrue(api.accepting)
        assertEquals("6m", o.tasks.first { it.id == "DEMO-6" }.left)
        assertEquals(2, o.needs.size)
        val app = o.spaces.first { it.name == "demo-app" }
        assertTrue(app.needsYou)
        assertTrue(app.asks)
        assertEquals("p:vm:sp0000000000000000000000000000b001", o.spaces[1].chat)
        assertEquals(4, snapshot("waiting.json").waiting)
    }

    @Test
    fun aNeedIsItsOwnCardAndAWaitWithNoCardIsAReply() {
        val cards = V2Board.cards(snapshot("waiting.json").needs)
        val q = cards[0]
        assertEquals("DEMO-5/worker", q.chat)
        assertEquals("DEMO-5", q.task)
        assertEquals("rq7", q.question.id)
        assertEquals(listOf("GitHub", "Google", "Email link"), q.question.choices.map { it.label })
        assertTrue(q.question.choices[0].recommended)
        val reply = cards[1]
        assertEquals("m12", reply.replyTo)
        assertEquals("Should I split the login work into two tasks?", reply.question.title)
        assertEquals("p:sp0000000000000000000000000000a001", reply.chat)
    }

    @Test
    fun anAssistantsChatWithEveryKindOfRow() {
        val chat = chats("waiting.json").getValue("p:sp0000000000000000000000000000a001")
        assertEquals("Reading the task list", chat.doing)
        assertTrue(chat.top)
        val items = SpaceChat.items("sp0000000000000000000000000000a001", chat)
        val byId = items.associateBy { it.id }
        assertEquals(SpaceChatItem.Note("m1:b", "New session"), byId["m1:b"])
        assertEquals("Plan dark mode and a login screen", (byId["m1"] as SpaceChatItem.Words).text)
        val ready = byId.getValue("tn:DEMO-4:ready:2") as SpaceChatItem.Update
        assertEquals(WorkStatus.Ready, ready.status)
        assertTrue(ready.accept)
        assertEquals("is ready · Dark mode works on every screen", ready.text)
        assertEquals(SpaceChatItem.Artifact("a1", "demo-coverage", 2), byId["a1"])
        assertEquals(SpaceChatItem.Note("p1", "Publishing an artifact…"), byId["p1"])
        assertEquals(SpaceChatItem.Note("ae:9", "Run: npm test · Allowed in the chat"), byId["ae:9"])
        assertEquals(SpaceChatItem.Note("s1", "Wants the secret GITHUB_TOKEN"), byId["s1"])
        assertEquals("API Error: overloaded", (byId["e1"] as SpaceChatItem.Words).text)
        assertTrue((byId["w1"] as SpaceChatItem.Words).waiting)
        // A shape the app doesn't know: one quiet line, the rest of the chat as it was.
        assertIs<SpaceChatItem.Unreadable>(byId["z1"])
        // A worker's ask, headed by its task, answered in its own chat.
        val thread = V2Board.thread("sp0000000000000000000000000000a001", chat)
        val ask = thread.cards.first { it.question.id == "rq7" }
        assertEquals("DEMO-5/worker", ask.chat)
        assertEquals("DEMO-5", ask.task)
        val parts = thread.cards.first { it.question.id == "q2" }.question.parts!!
        assertEquals(listOf("Colour?", "Which screens?"), parts.map { it.title })
        assertTrue(parts[1].multi)
        // The view a web page reads is never read here.
        assertEquals(V2Event.Other("view", kotlinx.serialization.json.JsonNull), V2Events.parse("""{"type":"view","name":"tasks","value":[]}"""))
        assertEquals("ended", chats("waiting.json").getValue("DEMO-6/worker").notice)
    }

    // ---- the Accept card -------------------------------------------------------------------------

    @Test
    fun theAcceptCardsEvidence() {
        val e = ShepherdrJson.decodeFromString(V2Evidence.serializer(), fixture("evidence.json"))
        assertTrue(e.ready)
        assertEquals("Accept DEMO-4?", Accept.title(e.ref!!))
        val ev = e.evidence!!
        assertEquals(3, ev.commits!!.count)
        assertEquals("3 commits", Accept.commitsLine(ev.commits!!.count))
        assertEquals("7 files changed", Accept.filesLine(ev.diff!!.files))
        assertEquals("1 file changed", Accept.filesLine(1))
        assertEquals("And 5 more", Accept.moreLine(5))
        assertEquals("passed", ev.checks!!.state)
        assertEquals("Dark mode · Proof", Accept.proofTitle(ev.proof!!.title))
        assertEquals("Already accepted.", Accept.whereNow(e.copy(state = "done", accepted_at = 1.0)))
        assertEquals("Already done.", Accept.whereNow(e.copy(state = "done")))
        assertEquals("Not ready right now: it's in Working.", Accept.whereNow(e.copy(state = "running", status_text = "Working")))
    }

    /** core/accept.test.js's cases, so the app's card says what the window's and the web's say. */
    @Test
    fun theAcceptCardsWordsAsShepherdrsOwn() {
        // A short summary stays as it is; a long one without details keeps its first sentence up top.
        assertEquals("Short." to "", Accept.words(V2EvidenceBody(summary = "Short.")))
        val long = "The board shows every task with its status and progress now. " + "It also reads every space's tasks and keeps them in step with the window, so nothing is lost. ".repeat(2)
        val (sum, det) = Accept.words(V2EvidenceBody(summary = long))
        // Cut at the last sentence that ends within the limit.
        assertEquals("The board shows every task with its status and progress now. It also reads every space's tasks and keeps them in step with the window, so nothing is lost.", sum)
        // No sentence ends in time: its first words (40 letters where there is no space) and an ellipsis, as accept.js.
        assertEquals("a".repeat(40) + "…", Accept.words(V2EvidenceBody(summary = "a".repeat(200))).first)
        assertEquals(long.trim(), det)
        // Details given: the summary stays whole.
        assertEquals(long.replace(Regex("\\s+"), " ").trim() to "x", Accept.words(V2EvidenceBody(summary = long, details = "x")))
        // Plain text as bullets, clear paths as code; Markdown as written.
        assertEquals("- Fixed the board\n- Tests in `core/src/test/A.kt` pass", Accept.readable("Fixed the board. Tests in core/src/test/A.kt pass."))
        assertEquals("- a\n- b", Accept.readable("- a\n- b"))
        assertEquals("One sentence only", Accept.readable("One sentence only"))
    }

    // ---- what the app sends ----------------------------------------------------------------------

    @Test
    fun actionBodies() {
        assertEquals("""{"type":"send","chat":"p:sp1","text":"hello","human":true}""", V2Actions.send("p:sp1", "hello").toString())
        assertEquals(
            """{"type":"send","chat":"DEMO-1/worker","text":"yes","cid":"c1","reply":"m4","human":true}""",
            V2Actions.send("DEMO-1/worker", "yes", reply = "m4", cid = "c1").toString(),
        )
        assertEquals(
            """{"type":"answer","chat":"DEMO-5/worker","question":"rq7","choice":1,"note":"Fewer pages"}""",
            V2Actions.answer("DEMO-5/worker", "rq7", 1, note = "Fewer pages").toString(),
        )
        assertEquals(
            """{"type":"answer","chat":"c","question":"q","choice":-1,"choices":[0,2],"other":"Both"}""",
            V2Actions.answer("c", "q", -1, choices = listOf(0, 2), note = "", other = "Both").toString(),
        )
        assertEquals(
            """{"type":"answer","chat":"c","question":"q2","choice":-1,"answers":["Amber","All, Board"]}""",
            V2Actions.answer("c", "q2", -1, answers = listOf("Amber", "All, Board")).toString(),
        )
        assertEquals("""{"type":"react","chat":"c","message":"m1","emoji":"👍"}""", V2Actions.react("c", "m1", "👍").toString())
        assertEquals("""{"type":"accept","task":"vm:API-2"}""", V2Actions.accept("vm:API-2").toString())
        assertEquals("""{"type":"evidence","task":"DEMO-4"}""", V2Actions.evidence("DEMO-4").toString())
        assertEquals("""{"type":"close","chat":"DEMO-4/worker"}""", V2Actions.close("DEMO-4/worker").toString())
        assertEquals("""{"type":"watch","chats":["p:sp1"],"term":null,"visible":false,"push":null}""", V2Actions.watch(listOf("p:sp1")).toString())
        assertEquals("p:vm:sp9", V2Board.assistantChat("vm/sp9"))
        assertEquals("p:sp9", V2Board.assistantChat("p:sp9"))
    }

    @Test
    fun aRequestCarriesTheTokenAndNoOrigin() {
        val r = Wire.request("POST", "/api/action", """{"a":"é"}""", token = "t0k").toString(Charsets.UTF_8)
        assertTrue(r.startsWith("POST /api/action HTTP/1.1\r\nHost: 127.0.0.1:7788\r\nAuthorization: Bearer t0k\r\n"), r)
        assertFalse(r.contains("Origin", ignoreCase = true))
        assertTrue(r.contains("Content-Length: 10\r\n"))
        assertTrue(r.endsWith("\r\n\r\n{\"a\":\"é\"}"))
        assertFalse(Wire.request("GET", "/api/health", null).toString(Charsets.UTF_8).contains("Authorization"))
    }

    @Test
    fun theTokenIsReadFromItsFileOnTheMachine() {
        assertEquals("cat \"\${SHEPHERDR_DATA:-\$HOME/.herdr/shepherdr}/token\" 2>/dev/null; true", BoardDetection.TOKEN_COMMAND)
        assertEquals("ab12", BoardDetection.token("ab12\n"))
        assertNull(BoardDetection.token(""))
        assertNull(BoardDetection.token("No such file or directory here"))
        // The relay and the curl fallback read it there themselves: it never crosses the command line.
        val relay = LiveRelay.command(snapshots = true)
        assertTrue(relay.contains("/.herdr/shepherdr") && relay.contains("Bearer") && relay.contains("ws://127.0.0.1:7788/api/ws"))
        assertTrue(CurlTransport.command("GET", "/api/health", false, 5).contains("Authorization: Bearer"))
    }

    // ---- detection -------------------------------------------------------------------------------

    @Test
    fun detectsShepherdrThroughAForwardAndNothingWhereNothingAnswers() = runBlocking {
        FakeShepherdr().use { fake ->
            fake.on("GET /api/health") { 200 to fixture("health.json") }
            ForwardTransport({ ForwardTransport.socket(fake.port, it) }).use { t ->
                assertEquals("3.1.2", BoardDetection.probe(t)!!.version)
            }
        }
        // A port where nothing listens: the SSH channel is refused, the probe says none.
        val dead = ServerSocket(0).use { it.localPort }
        assertNull(BoardDetection.probe(ForwardTransport({ ForwardTransport.socket(dead, it) })))
        FakeShepherdr().use { fake ->
            // Something else answers there; or a 2.x.
            fake.on("GET /api/health") { 404 to """{"error":"Not found"}""" }
            assertNull(BoardDetection.probe(ForwardTransport({ ForwardTransport.socket(fake.port, it) })))
            fake.on("GET /api/health") { 200 to """{"ok":true,"version":"2.1.0"}""" }
            assertNull(BoardDetection.probe(ForwardTransport({ ForwardTransport.socket(fake.port, it) })))
        }
    }

    @Test
    fun curlIsTheFallbackWithItsStatusAtTheEnd() = runBlocking<Unit> {
        val cmd = CurlTransport.command("POST", "/api/action", true, 30)
        // One `sh` around curl, its token header read there ([curlSendsTheTokenItReadsThere] runs it).
        assertTrue(cmd.startsWith("sh -c 'exec "), cmd)
        assertTrue("http://127.0.0.1:7788/api/action" in cmd && "@@shepherdr-status@@%{http_code}" in cmd && "--data-binary" in cmd, cmd)
        assertTrue(cmd.endsWith(" -H \"" + CurlTransport.AUTH + "\"'"), cmd)
        val sent = mutableListOf<Pair<String, String>>()
        val curl = CurlTransport { c, stdin, _ ->
            sent += c to stdin
            CommandResult(0, """{"ok":true}""" + "\n@@shepherdr-status@@200", "")
        }
        assertFalse(curl.canStream)
        ShepherdrClient(curl).accept("DEMO-4")
        assertEquals("""{"type":"accept","task":"DEMO-4"}""", sent.single().second)
        val down = CurlTransport { _, _, _ -> CommandResult(7, "\n@@shepherdr-status@@000", "curl: (7) Failed to connect") }
        assertEquals("curl: (7) Failed to connect", assertFailsWith<IOException> { down.http("GET", "/api/health") }.message)
        assertNull(BoardDetection.probe(down))
        val missing = CurlTransport { _, _, _ -> CommandResult(127, "", "sh: curl: not found") }
        assertFailsWith<IOException> { missing.http("GET", "/api/health") }
    }

    /** The curl fallback, run for real by `sh` with a stand-in `curl` that prints what it got, with a token file. */
    @Test
    fun curlSendsTheTokenItReadsThere() = runBlocking<Unit> {
        val dir = kotlin.io.path.createTempDirectory("curl").toFile()
        java.io.File(dir, "token").writeText("s3cret\n")
        val fakeCurl = java.io.File(dir, "curl")
        fakeCurl.writeText("#!/bin/sh\nfor a in \"\$@\"; do case \"\$a\" in Authorization*) printf '%s' \"\$a\";; esac; done\nprintf '\\n@@shepherdr-status@@200'\n")
        fakeCurl.setExecutable(true)
        val r = ProcessBuilder("sh", "-c", CurlTransport.command("GET", "/api/health", false, 5))
            .apply { environment()["PATH"] = dir.path + ":" + System.getenv("PATH"); environment()["SHEPHERDR_DATA"] = dir.path }.start()
        val out = r.inputStream.readBytes().toString(Charsets.UTF_8)
        r.waitFor()
        assertEquals("Authorization: Bearer s3cret\n@@shepherdr-status@@200", out)
        dir.deleteRecursively()
    }

    @Test
    fun aFileGoesUpAsAFormAndComesBackAsBytes() = runBlocking {
        FakeShepherdr().use { fake ->
            fake.on("POST /api/upload") { 200 to """{"path":"/home/u/.config/herdr-app/data/chat-attach/web-lq3x-voice-message-0m07s.m4a","name":"voice-message-0m07s.m4a"}""" }
            fake.on("GET /api/file?chat=p%3Asp1&path=%2Fhome%2Fu%2Fx.m4a") { 200 to "AUDIO" }
            val client = ShepherdrClient(ForwardTransport({ ForwardTransport.socket(fake.port, it) }, token = "t0k"))
            val path = client.upload("voice-message-0m07s.m4a", "AUDIO".toByteArray(), "audio/mp4")
            assertEquals("/home/u/.config/herdr-app/data/chat-attach/web-lq3x-voice-message-0m07s.m4a", path)
            val form = fake.bodies("POST /api/upload").single()
            assertTrue("name=\"file\"; filename=\"voice-message-0m07s.m4a\"\r\nContent-Type: audio/mp4\r\n\r\nAUDIO\r\n--" in form, form)
            assertEquals("AUDIO", String(client.file("p:sp1", "/home/u/x.m4a")))
            assertEquals("x.m4a.gone isn't there any more", assertFailsWith<IOException> { client.file("p:sp1", "/home/u/x.m4a.gone") }.message)
            assertEquals(listOf("t0k", "t0k", "t0k"), fake.tokens.toList())
        }
    }

    @Test
    fun aSendWithFilesNamesThem() {
        val a = V2Actions.send("p:sp1", "", cid = "c1", attachments = listOf("/attach/a.m4a"))
        assertEquals("""{"type":"send","chat":"p:sp1","text":"","attachments":["/attach/a.m4a"],"cid":"c1","human":true}""", a.toString())
        assertFalse("attachments" in V2Actions.send("p:sp1", "hi").toString())
    }

    @Test
    fun aFormKeepsTheBytesAsTheyAre() {
        val data = byteArrayOf(0, -1, 13, 10, 45, 45)
        val (body, type) = Wire.form(mapOf("chat" to "a/b"), "file", "x\"y.m4a", "audio/mp4", data, boundary = "B")
        assertEquals("multipart/form-data; boundary=B", type)
        val head = "--B\r\nContent-Disposition: form-data; name=\"chat\"\r\n\r\na/b\r\n" +
            "--B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"x_y.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n"
        assertContentEquals(head.toByteArray() + data + "\r\n--B--\r\n".toByteArray(), body)
    }

    // ---- the client over the wire ----------------------------------------------------------------

    @Test
    fun aTasksFolderAndPullRequestAsTheWebAppReadsThem() = runBlocking {
        FakeShepherdr().use { fake ->
            fake.on("GET /api/worktree?task=DEMO-4&what=dir") { 200 to """{"root":"~/.herdr/worktrees/demo/task-4","entries":[{"name":"src","dir":true}]}""" }
            fake.on("GET /api/worktree?task=vm%3AAPI-2&what=dir") { 200 to """{"root":"vm:/srv/api/.herdr/worktrees/api/task-2","entries":[]}""" }
            fake.on("GET /api/worktree?task=DEMO-7&what=dir") { 200 to """{"error":"This task's folder can't be read. It may have been removed after the task finished."}""" }
            fake.on("GET /api/worktree?task=DEMO-4&what=pr") {
                200 to """{"pr":{"number":212,"title":"Quiet hours","state":"open","url":"https://github.com/o/r/pull/212","head":"task/4","base":"main","add":120,"del":8,"files":6,
                    "checks":[{"name":"build","state":"fail","url":"https://github.com/o/r/runs/1","workflow":"Check"},{"name":"lint","state":"pending"},{"name":"test","state":"pass"},{"name":"docs","state":"pass"}]}}"""
            }
            fake.on("GET /api/worktree?task=DEMO-7&what=pr") { 200 to """{"why":"This task has no pull request."}""" }
            val forward = PortForward("127.0.0.1", 7788, preferred = 0) { _, _ -> fake.channel() }
            val board = V2WorkBoard(ShepherdrClient(ForwardTransport.over(forward, "t0k")), "3.1.2")
            fun task(id: String) = com.makefunstuff.herdr.core.board.WorkTask(id, id.substringAfter(':'), "", "", com.makefunstuff.herdr.core.board.WorkStatus.Ready)

            assertEquals(TaskFolder("~/.herdr/worktrees/demo/task-4"), board.folder(task("DEMO-4")))
            // Another machine's task: its folder there, and which machine.
            assertEquals(TaskFolder("/srv/api/.herdr/worktrees/api/task-2", "vm"), board.folder(task("vm:API-2")))
            assertEquals(
                "This task's folder can't be read. It may have been removed after the task finished.",
                assertFailsWith<IOException> { board.folder(task("DEMO-7")) }.message,
            )

            val pr = board.pullRequest(task("DEMO-4")).pr!!
            assertEquals(212, pr.number)
            assertEquals("Open", Worktree.state(pr.state))
            assertEquals("1 failed · 1 running · 2 passed", Worktree.checksLine(pr.checks))
            assertEquals("Check", pr.checks.first().workflow)
            assertEquals("6 files", Worktree.filesCount(pr.files!!))
            assertEquals(Worktree.NO_PR, board.pullRequest(task("DEMO-7")).why)
        }
    }

    @Test
    fun theTabMenuSendsWhat31sWebSends() = runBlocking {
        FakeShepherdr().use { fake ->
            fake.on("POST /api/action") { body ->
                when {
                    body.contains("\"tab-agents\"") -> 200 to """{"ok":true,"agents":[{"kind":"claude","name":"Claude Code","installed":true},{"kind":"pi","name":"Pi","installed":false}]}"""
                    body.contains("\"count\"") -> 200 to """{"ok":true,"workers":2}"""
                    body.contains("\"model-menu\"") -> 200 to """{"ok":true,"menu":{"current":{"agent":"claude","model":"opus","effort":"high","fast":false,"perm":"auto"},"menus":{"claude":{"name":"Claude Code","models":[{"id":"opus","name":"Opus 5.5","efforts":["high"]}]}},"permDefault":{"claude":"manual"},"remote":null}}"""
                    body.contains("\"tab-close\"") -> 200 to """{"error":"Tabs are the Herdr window's: it needs the window open."}"""
                    else -> 200 to """{"ok":true}"""
                }
            }
            val forward = PortForward("127.0.0.1", 7788, preferred = 0) { _, _ -> fake.channel() }
            val board = V2WorkBoard(ShepherdrClient(ForwardTransport.over(forward, "t0k")), "3.1.2")
            fun last() = fake.bodies("POST /api/action").last()
            val tab = com.makefunstuff.herdr.core.board.WorkTab(id = "tb1", name = "Claude", chat = "t:t4", agent = "claude", live = true)
            assertEquals(listOf("Claude Code", "Pi"), board.tabAgents("sp1").map { it.name })
            assertEquals("""{"type":"tab-agents","place":"sp1"}""", last())
            board.renameTab("sp1", tab, "  Review  ")
            assertEquals("""{"type":"tab-rename","tab":"tb1","name":"Review"}""", last())
            board.renameTab("sp1", tab.copy(id = null, assistant = true), "")
            assertEquals("""{"type":"tab-rename","place":"sp1","name":""}""", last())
            board.restartTab(tab)
            assertEquals("""{"type":"tab-switch","chat":"t:t4"}""", last())
            board.restartTab(tab, "codex")
            assertEquals("""{"type":"tab-switch","chat":"t:t4","kind":"codex"}""", last())
            val menu = board.modelMenu("t:t4")
            assertEquals("Opus 5.5", menu.menus.getValue("claude").models[0].name)
            board.restartWith("t:t4", menu.current.copy(model = "sonnet"), "high")
            assertEquals("""{"type":"restart","chat":"t:t4","kind":"claude","model":"sonnet","effort":"high","speed":"normal","perm":"auto"}""", last())
            assertEquals(2, board.assistantWorkers("sp1"))
            assertEquals("""{"type":"assistant-close","place":"sp1","mode":"count"}""", last())
            board.closeAssistant("sp1", stopWorkers = true)
            assertEquals("""{"type":"assistant-close","place":"sp1","mode":"stop"}""", last())
            board.closeSpace("sp1")
            assertEquals("""{"type":"space-remove","place":"sp1"}""", last())
            // The service's own reason, word for word.
            assertEquals("Tabs are the Herdr window's: it needs the window open.", assertFailsWith<IOException> { board.closeTab("tb1") }.message)
            forward.close()
        }
    }

    @Test
    fun theBoardOverASshStylePortForward() = runBlocking {
        FakeShepherdr().use { fake ->
            fake.onSocket { ws ->
                ws.sendText("""{"type":"hello","version":"3.1.2"}""")
                ws.sendText("""{"type":"view","name":"tasks","value":[]}""")
                ws.sendText(snapshotEvent("waiting.json"))
                ws.readText()
            }
            fake.on("POST /api/action") { body ->
                when {
                    body.contains("\"gone\"") -> 400 to "That chat isn't here any more."
                    body.contains("\"evidence\"") -> 200 to fixture("evidence.json")
                    body.contains("\"accept\"") && body.contains("API-2") -> 400 to "herdr-task accept failed: its checks failed"
                    else -> 200 to """{"ok":true}"""
                }
            }
            // As MachineConnection.forward does it, with the SSH channel replaced by a socket to the fake.
            val forward = PortForward("127.0.0.1", 7788, preferred = 0) { _, _ -> fake.channel() }
            val client = ShepherdrClient(ForwardTransport.over(forward, "t0k"))
            val board = V2WorkBoard(client, "3.1.2")
            val o = board.overview()
            assertEquals(5, o.tasks.size)
            val ready = o.tasks.first { it.id == "DEMO-4" }
            assertEquals("Ready", board.evidence(ready).status_text)
            assertEquals("DEMO-4 accepted", board.approve(ready))
            assertEquals("""{"type":"accept","task":"DEMO-4"}""", fake.bodies("POST /api/action").last())
            val api = o.tasks.first { it.id == "vm:API-2" }
            assertEquals("herdr-task accept failed: its checks failed", assertFailsWith<IOException> { board.approve(api) }.message)
            assertEquals("That chat isn't here any more.", assertFailsWith<IOException> { client.send("gone", "hello") }.message)
            board.close(o.tasks.first { it.id == "DEMO-7" })
            assertEquals("""{"type":"close","chat":"DEMO-7/worker"}""", fake.bodies("POST /api/action").last())
            board.answer(CardAnswer(WorkCard("DEMO-5/worker", com.makefunstuff.herdr.core.shepherdr.V2Question(id = "q1", title = "Plan for DEMO-5")), 0))
            assertEquals("""{"type":"answer","chat":"DEMO-5/worker","question":"q1","choice":0}""", fake.bodies("POST /api/action").last())
            val reply = V2Board.cards(o.needs)[1]
            board.answer(CardAnswer(reply, -1, other = "Yes, two"))
            assertEquals(
                """{"type":"send","chat":"p:sp0000000000000000000000000000a001","text":"Yes, two","reply":"m12","human":true}""",
                fake.bodies("POST /api/action").last(),
            )
            assertFailsWith<IOException> { board.answer(CardAnswer(reply, -1)) }
            board.say("sp0000000000000000000000000000a001", "hi")
            assertEquals("""{"type":"send","chat":"p:sp0000000000000000000000000000a001","text":"hi","human":true}""", fake.bodies("POST /api/action").last())
            assertTrue(fake.tokens.all { it == "t0k" })
            forward.close()
        }
    }

    @Test
    fun aRecipeAndItsPicturesComeFromItsMachine() = runBlocking {
        FakeShepherdr().use { fake ->
            fake.on("GET /api/artifacts/api-load/recipe?v=1&on=vm") { 200 to fixture("recipe.json") }
            fake.on("GET /api/artifacts/demo-coverage/recipe?v=2") { 404 to """{"error":"No recipe here."}""" }
            fake.on("GET /api/artifacts/api-load/file/shots/after%20one.png?v=1&on=vm") { 200 to "PNG" }
            val board = V2WorkBoard(ShepherdrClient(ForwardTransport({ ForwardTransport.socket(fake.port, it) })))
            val arts = snapshot("waiting.json").artifacts!!
            val load = arts.first { it.id == "api-load" }
            assertEquals(15, board.recipe(load, 1).blocks.size)
            assertEquals("PNG", String(board.artifactFile(load, 1, "shots/after one.png")!!))
            assertNull(board.artifactFile(load, 1, "missing.png"))
            assertFailsWith<com.makefunstuff.herdr.core.shepherdr.ArtifactGone> { board.recipe(arts.first { it.id == "demo-coverage" }, 2) }
        }
    }

    @Test
    fun aVoiceCallCarriesPcmBothWays() = runBlocking {
        FakeShepherdr().use { fake ->
            val got = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
            fake.onSocket { ws ->
                ws.sendText("""{"type":"state","state":"listening"}""")
                ws.sendBinary(byteArrayOf(1, 0, 2, 0))
                ws.readText()
                got += ws.lastBytes
                ws.sendText("""{"type":"interrupt"}""")
                ws.sendText("""{"type":"error","message":"No ElevenLabs key. Add ELEVENLABS_API_KEY in Settings › Secrets."}""")
                ws.close()
                ws.readText()
            }
            val board = V2WorkBoard(ShepherdrClient(ForwardTransport({ ForwardTransport.socket(fake.port, it) }, token = "t0k")))
            val call = board.call("sp1")
            assertEquals(VoiceCall.Event.State("listening"), call.next())
            assertContentEquals(byteArrayOf(1, 0, 2, 0), (call.next() as VoiceCall.Event.Audio).pcm)
            // An odd byte at the end is held back: whole samples only.
            call.send(byteArrayOf(5, 0, 6, 0, 7))
            assertContentEquals(byteArrayOf(5, 0, 6, 0), got.poll(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(VoiceCall.Event.Interrupt, call.next())
            assertEquals("No ElevenLabs key. Add ELEVENLABS_API_KEY in Settings › Secrets.", (call.next() as VoiceCall.Event.Error).message)
            assertNull(call.next())
            assertEquals("t0k", fake.tokens.single())
        }
    }

    @Test
    fun aCallsAudioIs16BitLittleEndian() {
        val pcm = com.makefunstuff.herdr.core.voice.CallState.pcm(floatArrayOf(0f, 1f, -1f, 0.5f))
        assertContentEquals(byteArrayOf(0, 0, 0xFF.toByte(), 0x7F, 0x01, 0x80.toByte(), 0xFF.toByte(), 0x3F), pcm)
        val back = com.makefunstuff.herdr.core.voice.CallState.floats(pcm)
        assertEquals(listOf(0f, 32767f / 32768, -32767f / 32768, 16383f / 32768), back.toList())
        val c = com.makefunstuff.herdr.core.voice.CallState(on = true, machineId = "m", space = "sp1")
        assertTrue(c.isFor("m", "sp1", null))
        assertFalse(c.isFor("m", "sp1", "DEMO-1"))
        assertEquals("Calling…", c.words)
    }

    @Test
    fun dictationIsWrittenDownOnTheMachine() = runBlocking {
        FakeShepherdr().use { fake ->
            fake.on("POST /api/dictate") { body -> if (body == "AUDIO") 200 to """{"text":"merge the dark mode task"}""" else 400 to """{"error":"That recording couldn't be read."}""" }
            val board = V2WorkBoard(ShepherdrClient(ForwardTransport({ ForwardTransport.socket(fake.port, it) })))
            assertEquals("merge the dark mode task", board.dictate("AUDIO".toByteArray(), "audio/mp4"))
            assertEquals("That recording couldn't be read.", assertFailsWith<IOException> { board.dictate("x".toByteArray(), "audio/mp4") }.message)
        }
    }

    @Test
    fun aMachineSaysWhetherItWritesSpeechDown() = runBlocking {
        for ((answer, can) in listOf(
            400 to """{"error":"That recording couldn't be read."}""" to true,
            400 to """{"error":"Dictation needs whisper.cpp and its model on the Shepherdr machine (shepherdr whisper)."}""" to false,
            404 to "Not found" to false,
        )) FakeShepherdr().use { fake ->
            fake.on("POST /api/dictate") { body -> if (body.isEmpty()) answer else 500 to "{}" }
            val board = V2WorkBoard(ShepherdrClient(ForwardTransport({ ForwardTransport.socket(fake.port, it) })))
            assertEquals(can, board.canDictate(), answer.second)
        }
    }

    @Test
    fun cancellingALongCallLetsGoAtOnce() = runBlocking {
        FakeShepherdr().use { fake ->
            val asked = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            fake.on("POST /api/action") { asked.countDown(); release.await(); 200 to """{"ok":true}""" }
            val client = ShepherdrClient(ForwardTransport({ ForwardTransport.socket(fake.port, it) }))
            val call = launch(kotlinx.coroutines.Dispatchers.IO) { client.accept("DEMO-4") }
            assertTrue(asked.await(10, java.util.concurrent.TimeUnit.SECONDS))
            // herdr-task still waits on GitHub: cancelling doesn't wait with it.
            withTimeout(5_000) { call.cancelAndJoin() }
            release.countDown()
        }
    }

    @Test
    fun aServerErrorSaysWhy() = runBlocking {
        FakeShepherdr().use { fake ->
            fake.on("POST /api/action") { 401 to "no token" }
            val client = ShepherdrClient(ForwardTransport({ ForwardTransport.socket(fake.port, it) }))
            assertEquals("no token", assertFailsWith<IOException> { client.accept("DEMO-4") }.message)
        }
    }

    @Test
    fun readsAChunkedAnswer() {
        val raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n{\"a\"\r\n3\r\n:1}\r\n0\r\n\r\n"
        val r = Wire.exchange(bytes(raw), "GET", "/x", null)
        assertEquals(HttpReplyOf(200, """{"a":1}"""), HttpReplyOf(r.status, r.body))
    }

    @Test
    fun theLiveLinkSendsSnapshotsAndTheWatchedChat() = runBlocking {
        FakeShepherdr().use { fake ->
            val chat = ShepherdrJson.parseToJsonElement(fixture("chats.json")).let { (it as kotlinx.serialization.json.JsonArray)[1].toString() }
            fake.onSocket { ws ->
                ws.sendText("""{"type":"hello","version":"3.1.2"}""")
                ws.sendText(snapshotEvent())
                val watch = ws.readText()
                assertTrue(watch.contains("\"watch\"") && watch.contains("p:sp00000000000000000000000000000a01"))
                ws.ping()
                // A chat in two frames.
                ws.sendText(chat.substring(0, 100), fin = false)
                ws.sendText(chat.substring(100), opcode = 0)
                ws.close()
                // The pong, then the client's own close.
                ws.readText()
            }
            val client = ShepherdrClient(ForwardTransport({ ForwardTransport.socket(fake.port, it) }))
            val link = client.live()
            assertEquals("hello", (link.next() as V2Event.Other).type)
            val first = link.next() as V2Event.Snapshot
            assertEquals(4, first.snapshot.tasks.size)
            link.watch(listOf("p:sp00000000000000000000000000000a01"))
            val c = link.next() as V2Event.Chat
            assertEquals(1, c.chat.messages.size)
            assertNull(link.next())
            withTimeout(10_000) { while (fake.closedSockets.get() == 0) kotlinx.coroutines.delay(20) }
            assertEquals(listOf("p"), fake.pongs)
        }
    }

    @Test
    fun anAssistantsChatComesThroughTheLiveLinkAndItsThreadFollowsIt() = runBlocking {
        val chat = ShepherdrJson.parseToJsonElement(fixture("chats.json")).let { (it as kotlinx.serialization.json.JsonArray)[1].toString() }
        FakeShepherdr().use { fake ->
            fake.onSocket { ws ->
                ws.sendText(snapshotEvent())
                ws.readText()
                // Opening: the next one says what it found.
                ws.sendText("""{"type":"chat","chat":{"key":"p:sp00000000000000000000000000000a01","notice":"opening","messages":[]}}""")
                ws.sendText(chat)
                ws.sendText(chat.replace("Shepherdr 3 client", "and one more"))
                // Held open until the client goes.
                ws.readText()
            }
            val board = V2WorkBoard(ShepherdrClient(ForwardTransport({ ForwardTransport.socket(fake.port, it) })))
            val thread = board.coordinator("sp00000000000000000000000000000a01")
            assertEquals("none", thread.v2!!.notice)
            assertEquals(1, thread.lines.size)
            val three = withTimeout(10_000) { board.coordinatorThreads("sp00000000000000000000000000000a01").take(3).toList() }
            assertTrue(three[2].lines[0].text.endsWith("and one more"))
        }
    }

    @Test
    fun aTasksOwnChatFollowsItsAgentsChatAndWordsGoThere() = runBlocking {
        val chat = ShepherdrJson.parseToJsonElement(fixture("chats.json")).let { (it as kotlinx.serialization.json.JsonArray)[2].toString() }
        FakeShepherdr().use { fake ->
            fake.onSocket { ws ->
                ws.sendText(snapshotEvent())
                val watch = ws.readText()
                assertTrue(watch.contains("DEMO-8/worker"))
                ws.sendText(chat)
                ws.readText()
            }
            val board = V2WorkBoard(ShepherdrClient(ForwardTransport({ ForwardTransport.socket(fake.port, it) })))
            val task = V2Board.task(V2Task("DEMO-8", "Shepherdr 3 client", place = "sp1", agent = V2TaskAgent("DEMO-8/worker", cli = "claude")))
            assertEquals("claude", V2Board.agentKind(task.agent))
            val t = withTimeout(10_000) { board.taskThreads(task).first() }
            assertEquals("DEMO-8/worker", t.v2?.key)
            assertEquals("Running ./gradlew -p core test", t.v2?.doing)
            assertEquals("sp1", t.space)
            // A task before its agent starts has no chat to write to.
            val e = runCatching { board.tell(task.copy(chat = ""), "Hi") }.exceptionOrNull()
            assertEquals("DEMO-8 has no agent yet", e?.message)
        }
    }

    @Test
    fun aDroppedLiveLinkIsLinkedAgain() = runBlocking {
        FakeShepherdr().use { fake ->
            val links = java.util.concurrent.atomic.AtomicInteger()
            fake.onSocket { ws ->
                val n = links.incrementAndGet()
                ws.sendText("""{"type":"snapshot","snapshot":{"places":[{"id":"sp$n"}]}}""")
                // The first link drops (the service restarts); the second stays until the app goes.
                if (n == 1) ws.close() else ws.readText()
            }
            val board = V2WorkBoard(ShepherdrClient(ForwardTransport({ ForwardTransport.socket(fake.port, it) })))
            val two = withTimeout(10_000) { board.overviews().take(2).toList() }
            assertEquals(listOf("sp1", "sp2"), two.map { it.spaces.single().id })
            assertEquals("p:sp1", two[0].spaces.single().chat)
        }
    }

    @Test
    fun theFirstFailureOfALiveFlowIsThrown() = runBlocking<Unit> {
        val dead = ServerSocket(0).use { it.localPort }
        val board = V2WorkBoard(ShepherdrClient(ForwardTransport({ ForwardTransport.socket(dead, it) })))
        assertFailsWith<IOException> { withTimeout(10_000) { board.overviews().first() } }
    }

    @Test
    fun withoutAForwardTheSnapshotAndChatsAreReadByTheMachinesBun() = runBlocking {
        val cmd = CurlTransport.chatCommand("p:it's", 15)
        assertTrue(cmd.startsWith("sh -c "))
        assertTrue(cmd.contains("bun -e") && cmd.contains("node -e") && cmd.contains("Bearer"))
        // The key goes in as a JSON string, its quote escaped for the shell.
        assertTrue(cmd.contains("const k=\"p:it"))
        assertTrue(CurlTransport.snapshotCommand(15).contains("snapshot"))
        val chat = ShepherdrJson.parseToJsonElement(fixture("chats.json")).let { (it as kotlinx.serialization.json.JsonArray)[1].toString() }
        val snap = ShepherdrJson.parseToJsonElement(snapshotEvent()).toString()
        val curl = CurlTransport { c, _, _ ->
            when {
                c.contains("const k=") -> CommandResult(0, "login banner\n@@shepherdr-chat@@$chat\n", "")
                else -> CommandResult(0, "@@shepherdr-chat@@$snap\n", "")
            }
        }
        val board = V2WorkBoard(ShepherdrClient(curl))
        assertEquals(1, board.coordinator("sp00000000000000000000000000000a01").lines.size)
        // No live link: the flows read again.
        assertEquals(4, withTimeout(10_000) { board.overviews(everyMs = 10).take(2).toList() }.last().tasks.size)
        val none = CurlTransport { _, _, _ -> CommandResult(3, "", "Shepherdr sent no chat x") }
        assertEquals("Shepherdr sent no chat x", assertFailsWith<IOException> { ShepherdrClient(none).chat("x") }.message)
    }

    @Test
    fun cancellingTheLiveLinkClosesIt() = runBlocking {
        FakeShepherdr().use { fake ->
            fake.onSocket { ws ->
                ws.sendText("""{"type":"snapshot","snapshot":{"places":[{"id":"sp1"}]}}""")
                ws.readText()
            }
            val board = V2WorkBoard(ShepherdrClient(ForwardTransport({ ForwardTransport.socket(fake.port, it) })))
            val o = withTimeout(10_000) { board.overviews().first() }
            assertEquals("sp1", o.spaces.single().id)
            // The fake's read returned once the app closed its end.
            withTimeout(10_000) { while (fake.closedSockets.get() == 0) kotlinx.coroutines.delay(20) }
        }
    }

    @Test
    fun aClientsFrameIsMasked() {
        val mask = byteArrayOf(1, 2, 3, 4)
        val f = WebSocketLink.encode(1, "hi".toByteArray(), mask)
        assertContentEquals(byteArrayOf(0x81.toByte(), 0x82.toByte(), 1, 2, 3, 4, ('h'.code xor 1).toByte(), ('i'.code xor 2).toByte()), f)
        val long = WebSocketLink.encode(1, ByteArray(300), mask)
        assertEquals(0xFE.toByte(), long[1])
        assertEquals(300, (long[2].toInt() and 0xFF shl 8) or (long[3].toInt() and 0xFF))
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", Wire.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun pinnedFirstWithinEachStatus() {
        fun t(id: String, s: WorkStatus, pinned: Boolean = false) = WorkTask(id, id, id, "", s, pinned = pinned)
        val out = WorkTasks.ordered(listOf(t("a", WorkStatus.Done), t("b", WorkStatus.Work), t("c", WorkStatus.Work, true), t("d", WorkStatus.Need), t("e", WorkStatus.Merging)))
        assertEquals(listOf("d", "e", "c", "b", "a"), out.map { it.id })
    }

    private data class HttpReplyOf(val status: Int, val body: String)

    private fun bytes(raw: String): Duplex = object : Duplex {
        override val input: InputStream = raw.toByteArray().inputStream()
        override val output: OutputStream = ByteArrayOutputStream()
        override fun close() {}
    }
}

/** A stand-in for Shepherdr's service on a loopback port: canned HTTP answers and a scripted live link. */
private class FakeShepherdr : AutoCloseable {
    private val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    private val routes = HashMap<String, (String) -> Pair<Int, String>>()
    private val seen = CopyOnWriteArrayList<Pair<String, String>>()
    /** The token each request came with (`Authorization: Bearer …`), in order; null for none. */
    val tokens = CopyOnWriteArrayList<String?>()
    private var socketScript: ((FakeSocket) -> Unit)? = null
    val pongs = CopyOnWriteArrayList<String>()
    val closedSockets = java.util.concurrent.atomic.AtomicInteger()

    init {
        thread(isDaemon = true) {
            while (true) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(s) }; runCatching { s.close() } }
            }
        }
    }

    fun on(route: String, answer: (String) -> Pair<Int, String>) { routes[route] = answer }
    fun onSocket(script: (FakeSocket) -> Unit) { socketScript = script }
    fun bodies(route: String): List<String> = seen.filter { it.first == route }.map { it.second }

    /** A connection to it, as the SSH channel the port forward opens. */
    fun channel(): Duplex {
        val s = Socket("127.0.0.1", port)
        return object : Duplex {
            override val input: InputStream = s.getInputStream()
            override val output: OutputStream = s.getOutputStream()
            override fun close() = s.close()
        }
    }

    private fun serve(s: Socket) {
        val input = BufferedInputStream(s.getInputStream())
        val (line, headers) = head(input)
        val (method, path) = line.split(' ').let { it[0] to it[1] }
        assertNull(headers["origin"])
        assertEquals("127.0.0.1:7788", headers["host"])
        tokens += headers["authorization"]?.removePrefix("Bearer ")
        if (headers["upgrade"] == "websocket") {
            val accept = Wire.acceptFor(headers.getValue("sec-websocket-key"))
            s.getOutputStream().write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
            try {
                socketScript?.invoke(FakeSocket(input, s.getOutputStream(), pongs))
            } finally {
                closedSockets.incrementAndGet()
            }
            return
        }
        val body = headers["content-length"]?.toInt()?.let { String(Wire.readBody(input, headers)) } ?: ""
        seen += "$method $path" to body
        val (status, text) = routes["$method $path"]?.invoke(body) ?: (404 to """{"error":"Not found"}""")
        val bytes = text.toByteArray()
        s.getOutputStream().write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray() + bytes)
        s.getOutputStream().flush()
    }

    private fun head(input: InputStream): Pair<String, Map<String, String>> {
        val lines = mutableListOf<String>()
        val b = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) break
            if (c == '\n'.code) {
                val l = b.toString().trimEnd('\r'); b.clear()
                if (l.isEmpty()) break
                lines += l
            } else b.append(c.toChar())
        }
        return lines.first() to lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
    }

    override fun close() { server.close() }
}

/** The server's end of a WebSocket: unmasked frames out, masked ones in. */
private class FakeSocket(private val input: InputStream, private val output: OutputStream, private val pongs: MutableList<String>) {
    fun sendText(text: String, fin: Boolean = true, opcode: Int = 1) = frame(opcode, text.toByteArray(), fin)
    fun sendBinary(bytes: ByteArray) = frame(2, bytes, true)
    /** The opcode of the last frame [readText] gave. */
    var lastOpcode = 0
    /** The payload of the last frame [readText] gave, as bytes. */
    var lastBytes = ByteArray(0)
    fun ping() = frame(0x9, "p".toByteArray(), true)
    fun close() = frame(0x8, ByteArray(0), true)

    private fun frame(opcode: Int, payload: ByteArray, fin: Boolean) {
        val out = ByteArrayOutputStream()
        out.write((if (fin) 0x80 else 0) or opcode)
        when {
            payload.size < 126 -> out.write(payload.size)
            payload.size <= 0xFFFF -> { out.write(126); out.write(payload.size shr 8); out.write(payload.size and 0xFF) }
            else -> { out.write(127); for (i in 7 downTo 0) out.write(((payload.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
        }
        out.write(payload)
        output.write(out.toByteArray())
        output.flush()
    }

    /** The client's next text message (its pongs noted on the way); "" once it closed. */
    fun readText(): String {
        while (true) {
            val b0 = input.read()
            if (b0 < 0) return ""
            val b1 = input.read()
            var len = b1 and 0x7F
            if (len == 126) len = (input.read() shl 8) or input.read()
            assertTrue(b1 and 0x80 != 0, "a client's frames are masked")
            val mask = Wire.readExactly(input, 4)
            val p = Wire.readExactly(input, len)
            for (i in p.indices) p[i] = (p[i].toInt() xor mask[i % 4].toInt()).toByte()
            lastOpcode = b0 and 0x0F
            lastBytes = p
            when (b0 and 0x0F) {
                0xA -> pongs += String(p)
                0x8 -> return ""
                else -> return String(p)
            }
        }
    }
}
