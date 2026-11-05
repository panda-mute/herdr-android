// Every scene, in the order they render. Each renders twice: <name>.png (dark) and <name>-light.png.
// A scene starts from Demo's machines; `route` opens a screen, `actions` taps on what it shows.
package preview

import com.makefunstuff.herdr.core.voice.CallState
import com.makefunstuff.herdr.core.voice.VoiceState
import com.makefunstuff.herdr.Links
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.bubble.BubbleChat
import com.makefunstuff.herdr.core.push.PhoneQuiet
import com.makefunstuff.herdr.core.push.QuietHours
import com.makefunstuff.herdr.data.CodeView
import com.makefunstuff.herdr.data.IdentityStore
import com.makefunstuff.herdr.ui.code.CheckoutTab
import com.makefunstuff.herdr.ui.code.CodeSpot
import com.makefunstuff.herdr.ui.code.DiffSource
import com.makefunstuff.herdr.ui.share.Ask
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import preview.Demo.API_AGENT
import preview.Demo.APP_AGENT
import preview.Demo.APP_SHELL
import preview.Demo.DEVBOX
import preview.Demo.GPU
import preview.Demo.SITE_AGENT

/** Task AND-9's worktree on devbox, opened from the task (its agent works in devbox's herdr-android workspace). */
private val TASK_CODE = CodeSpot(DEVBOX, FakeCode.TASK, task = "herdr-android/9", taskName = "AND-9", pane = APP_AGENT)
private const val CARD = "app/src/main/kotlin/demo/herdr/ui/machines/MachineCard.kt"

private const val SHOT_1 = "Screenshot_20261018_101204.png"

/** Two screenshots waiting in the app agent's composer, with words for them. */
private fun SceneData.subagent() {
    host(DEVBOX).transcripts = host(DEVBOX).transcripts +
        (Demo.APP_SESSION to Demo.appTranscriptSubagent()) + ("${Demo.APP_SESSION}/agent-${Demo.APP_SUBAGENT}" to Demo.appSubagentSteps())
}

private fun SceneData.pictures() {
    images[com.makefunstuff.herdr.data.DraftStore.agent(DEVBOX, APP_AGENT)] = listOf(
        SHOT_1 to Demo.picture("board"),
        "Screenshot_20261018_101230.png" to Demo.picture("board-light"),
    )
    drafts[com.makefunstuff.herdr.data.DraftStore.agent(DEVBOX, APP_AGENT)] = "The Board card jumps when a machine reconnects. Keep it where it was?"
}

/** A message sent to the app agent while it worked, held over its composer. */
private fun SceneData.held() {
    val key = com.makefunstuff.herdr.data.DraftStore.agent(DEVBOX, APP_AGENT)
    composer = (composer ?: com.makefunstuff.herdr.data.ComposerStore.State()).let {
        it.copy(held = it.held + (key to listOf(com.makefunstuff.herdr.core.chat.HeldMessage("held-1", "Also add it to the widget", Demo.NOW - 40_000))))
    }
}

/** devbox's app agent done with its second ask: what you send goes in at once, not held. */
private fun SceneData.appDone() {
    host(DEVBOX).snapshot = Demo.devbox(appStatus = "done")
    host(DEVBOX).transcripts = host(DEVBOX).transcripts + (Demo.APP_SESSION to Demo.appTranscriptDone())
}

/** devbox on Shepherdr 3 (DemoV2): its spaces and their Assistants. */
private fun SceneData.shepherdr() {
    host(DEVBOX).shepherdr = DemoV2.board()
}

/** A long talk in website's Assistant chat on Shepherdr 3, longer than the screen. */
private fun SceneData.longWebsiteChat() {
    val v2 = host(DEVBOX).shepherdr!!
    val talk = (1..6).flatMap { i ->
        listOf(obj("id" to "w$i-q", "who" to "you", "at" to i, "text" to "Question $i: is the install page up to date?"), obj("id" to "w$i-a", "who" to "agent", "at" to i, "text" to "Check $i done: the install page matches 0.9, and its links all answer."))
    }
    v2.chats = v2.chats + (WEBSITE_CHAT to obj("key" to WEBSITE_CHAT, "cli" to "Claude Code", "working" to false, "messages" to JsonArray(talk)))
}

private const val WEBSITE_CHAT = "p:sp-website"

/**
 * herdr-android's Assistant chat with 3.1's conveniences in use: reactions on its last reply (yours and one you both
 * gave) and your answer to it quoting it, whose line goes back to it.
 */
private fun SceneData.chatParity(between: Int = 0) {
    shepherdr()
    val v2 = host(DEVBOX).shepherdr!!
    val key = DemoV2.chat(DemoV2.APP)
    val c = v2.chats.getValue(key)
    val msgs = c.getValue("messages").jsonArray.map { m ->
        if (m.jsonObject["id"]?.jsonPrimitive?.content != "m6") m
        else JsonObject(m.jsonObject + ("reactions" to JsonArray(listOf(obj("emoji" to "👍", "by" to "me"), obj("emoji" to "🙌", "by" to "both")))))
    }
    val reply = obj(
        "id" to "m8", "who" to "you", "at" to 8, "text" to "Great. Merge it once the release notes say so too.",
        "reply" to obj("id" to "m6", "who" to "agent", "text" to "AND-13 passed its checks…"),
    )
    // Talk between the two, so the one quoted is off the screen.
    val talk = (1..between).flatMap { i ->
        listOf(obj("id" to "b$i-q", "who" to "you", "at" to 7, "text" to "Check $i: does the card hold on a slow network?"), obj("id" to "b$i-a", "who" to "agent", "at" to 7, "text" to "Check $i passed: it stays put."))
    }
    v2.chats = v2.chats + (key to JsonObject(c + ("messages" to JsonArray(msgs + talk + reply))))
}

/** An Assistant's chat whose log Shepherdr can't find on its machine: its notice, and the news lines it still has. */
private fun SceneData.chatNotFound() {
    shepherdr()
    val v2 = host(DEVBOX).shepherdr!!
    val key = DemoV2.chat(DemoV2.APP)
    val c = v2.chats.getValue(key)
    v2.chats = v2.chats + (key to JsonObject(c + mapOf("notice" to JsonPrimitive("none"), "working" to JsonPrimitive(false),
        "messages" to JsonArray(c.getValue("messages").jsonArray.filter { it.jsonObject.containsKey("news") }))))
}

/** A message of a kind newer than the app among the replies, and one whose parts don't read: the rest still shows. */
private fun SceneData.unknownMessage() {
    shepherdr()
    val v2 = host(DEVBOX).shepherdr!!
    val c = v2.chats.getValue(DemoV2.chat(DemoV2.HOME))
    val more = Json.parseToJsonElement(
        """[
          {"id":"h4","who":"agent","at":4,"poll":{"question":"Which night for the NAS check?","options":["Tue","Thu"]}},
          {"id":"h5","who":"agent","at":5,"text":"Done: the NAS check now runs on Thursdays.","reactions":"👍"}
        ]""",
    ).jsonArray
    v2.chats = v2.chats + (DemoV2.chat(DemoV2.HOME) to JsonObject(c + ("messages" to JsonArray(c.getValue("messages").jsonArray + more))))
}

/** A new message in a space's Assistant chat on Shepherdr 3. */
private fun FakeMachine.v2Says(chat: String, id: String, text: String) {
    val v2 = shepherdr!!
    val c = v2.chats.getValue(chat)
    v2.chats = v2.chats + (chat to JsonObject(c + ("messages" to JsonArray(c.getValue("messages").jsonArray + obj("id" to id, "who" to "agent", "at" to 7, "text" to text)))))
}

/** Lets a chat read the machine again (the Coordinators read every few seconds). */
private fun androidx.compose.ui.test.ComposeUiTest.waitForRead(seconds: Int = 9) = repeat(seconds * 2) { Thread.sleep(500); settle(1) }

/** The shell's test run selected, and what herdr reads for it. */
private fun SceneData.selectTests() {
    selection = com.makefunstuff.herdr.ui.terminal.CellPoint(16, 0) to com.makefunstuff.herdr.ui.terminal.CellPoint(19, 39)
    host(DEVBOX).on("pane.selection.read") {
        obj(
            "text" to "BoardTest > coordinatorChatMerges PASSED\nChatTest > claudeTranscriptReadsTools PASSED\n" +
                "ChatTest > blockedPromptParsesMenu PASSED\nPainterTest > drawsWideCharacters PASSED",
        )
    }
}

/** The quick replies turned on, as they start. */
private fun SceneData.replies() {
    composer = (composer ?: com.makefunstuff.herdr.data.ComposerStore.State()).copy(replies = com.makefunstuff.herdr.core.chat.QuickReplies(on = true))
}

/** The app agent's conversation after two screenshots were sent to it, which devbox still has. */
private fun SceneData.sentPictures() {
    host(DEVBOX).transcripts = host(DEVBOX).transcripts + (Demo.APP_SESSION to Demo.appTranscriptWithPictures())
    host(DEVBOX).pictures = Demo.appPictures()
}

/**
 * The Agents screen's world: devbox's agents (and one more at work on AND-6), and a profile gpu-box
 * finished in the night.
 */
private fun SceneData.home() {
    host(DEVBOX).snapshot = Demo.devbox(home = true)
    host(GPU).snapshot = Demo.gpu(home = true)
}

/** Shepherdr 3 on devbox: its board, its spaces and their Assistants. */
private fun SceneData.v2() {
    host(DEVBOX).shepherdr = DemoV2.board()
}

/** Shepherdr 3 on devbox, its home Assistant also asking for a secret ([DemoV2.SECRET_NEED]). */
private fun SceneData.secretAsk() {
    val v2 = DemoV2.board()
    v2.snapshot = JsonObject(v2.snapshot + ("needs" to JsonArray(v2.snapshot.getValue("needs").jsonArray + Json.parseToJsonElement(DemoV2.SECRET_NEED))))
    host(DEVBOX).shepherdr = v2
}

/** Where a 540×1200 screenshot is drawn in the editor's [w]×[h] canvas: as large as fits, in the middle. */
private fun screenshotIn(w: Float, h: Float): androidx.compose.ui.geometry.Rect {
    val scale = minOf(w / 540f, h / 1200f)
    val sw = 540f * scale
    val sh = 1200f * scale
    return androidx.compose.ui.geometry.Rect((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2)
}

fun scenes(): List<Scene> = listOf(
    // Tasks (3.1's Tasks list; the scenes keep the Agents list's names): what needs you across the machines first,
    // answerable on the card (an agent's question, a task's pick, a Ready task's Accept); then the work by status, the
    // agents at work as one line with a bar, finished ones new until seen. The tall one shows all of it; then answering
    // from the card, the filter row, Mark seen, the + menu, and the agent's long-press sheet.
    Scene("agents", setup = { home() }),
    Scene("agents-all", heightDp = 1500, setup = { home() }),
    // devbox on Shepherdr 3: its tasks that ask or are ready wait on Needs you too, answered or accepted from the card.
    Scene("agents-v2", heightDp = 1700, setup = { home(); v2() }),
    Scene("agents-v2-answer", heightDp = 1700, setup = { home(); v2() }, actions = { settle(); tapText("Last answered 2 min ago") }),
    // A Ready task's Accept on its card: the Accept card first.
    Scene("agents-v2-accept", heightDp = 1500, setup = { home(); v2() }, actions = { settle(); tapExact("Accept") }),
    Scene("agents-reply", setup = { home() }, actions = { tapText("Reply") }),
    // Answered from its card: the question is read again and answered, the approved task leaves the list.
    Scene("agents-yes", setup = { home() }, actions = { tapText("Yes") }),
    // A two-option menu with a permanent allow on a card: it carries the fingerprint, and asks before it presses.
    Scene("agents-always-confirm", setup = {
        home(); screenLock = false
        host(DEVBOX).screens += API_AGENT to " Allow the extension to run git push?\n\n→ Always allow\n  Deny\n\n ↑↓ navigate · enter select\n"
    }, actions = { tapText("Always allow") }),
    Scene("agents-filter", heightDp = 1500, setup = { home() }, actions = { tapText("Working 3") }),
    Scene("tasks-new", setup = { home(); shepherdr() }, actions = { tapLabel("New task, Assistant or agent") }),
    // An agent asking for a secret by name: its own item under Needs you, then the masked box (empty, then dots: a
    // made-up value, never a real one), and the list once it is kept.
    Scene("tasks-secret", heightDp = 1700, setup = { home(); secretAsk() }),
    Scene("secret-sheet", setup = { home(); secretAsk() }, actions = { settle(); tapExact("Give it") }),
    Scene("secret-sheet-typed", setup = { home(); secretAsk() }, actions = { settle(); tapExact("Give it"); type("demo-not-a-real-token") }),
    Scene("secret-kept", heightDp = 1700, setup = { home(); secretAsk() }, actions = { settle(); tapExact("Give it"); type("demo-not-a-real-token"); tapExact("Save") }),
    Scene("agents-seen", heightDp = 1500, setup = { home() }, actions = { tapText("Mark all seen") }),
    // Triage: the eval pinned on top, its space (llm-eval) set to Notify Off: a crossed-out bell; the profile still new in Done.
    Scene("agents-triage", heightDp = 1500, setup = { home(); triage = Demo.triage(); push = Demo.notify() }),
    // A row swiped left shows Pin under it; one swiped right is seen and leaves Done.
    Scene("agents-swipe", heightDp = 1500, setup = { home() }, actions = { drag("Long-context eval", 0.85f to 0.5f, 0.15f to 0.5f) }),
    Scene("agents-swiped-seen", heightDp = 1500, setup = { home() }, actions = { drag("Profile the data loader", 0.1f to 0.5f, 0.7f to 0.5f) }),
    // The same in the long-press sheet: Mark seen, Pin, and its space's Notify (All / Needs me / Off), here picked Off.
    Scene("agent-triage", heightDp = 1500, setup = { home() }, actions = { longPressText("Profile the data loader") }),
    Scene("agent-notify", heightDp = 1500, setup = { home() }, actions = { longPressText("Profile the data loader"); tapExact("Off") }),
    Scene("agent-actions", setup = { home() }, actions = { longPressText("Speed up /jobs") }),
    // TalkBack: everything behind a long press offered as actions on the card or row (read from the app's semantics,
    // drawn as TalkBack's Actions menu): a question's answers first, then its terminal, why this state, the sheet;
    // a row's triage; a board card's actions. One carried out: why this state.
    Scene("agent-talkback", setup = { home() }, actions = { talkback("Speed up /jobs") }),
    Scene("agent-line-talkback", heightDp = 1500, setup = { home() }, actions = { talkback("Profile the data loader") }),
    Scene("agent-why", setup = { home(); host(DEVBOX).on("agent.explain") { Demo.explain() } }, actions = { talkbackDo("Speed up /jobs", "Why this state?") }),
    // In a chat, another agent starts asking: one line drops from the top (never for the chat that is open).
    Scene("pill", route = Routes.agent(DEVBOX, APP_AGENT), setup = { host(DEVBOX).snapshot = Demo.devbox(apiStatus = "working") }, actions = { ctx ->
        ctx.data.host(DEVBOX).snapshot = Demo.devbox()
        ctx.refresh(DEVBOX)
        settle(4)
    }),
    // The spaces drawer (3.1's sidebar, as the workbench's: each machine a numbered bus with its meter, each space a
    // numbered slot with its light), and one machine: its places in one row, its spaces under one heading.
    Scene("machines", setup = { home() }, actions = { tapLabel("Spaces") }),
    // A space's Notify from a long press in the drawer; llm-eval, set to Off, is dim beside a crossed-out bell.
    Scene("machines-notify", setup = { home(); push = Demo.notify() }, actions = { tapLabel("Spaces"); longPressText("HERDR-ANDROID") }),
    Scene("machine", route = Routes.machine(DEVBOX)),
    // A workspace with two tabs keeps its tab chips and its layout; with one tab, its actions are in the workspace's ⋯.
    Scene("machine-tabs", route = Routes.machine(DEVBOX), setup = { host(DEVBOX).snapshot = Demo.devboxTabs() }),
    Scene("machine-one-tab", route = Routes.machine(DEVBOX), actions = { tapLabel("Workspace actions") }),
    // A machine without the work board: Health and Usage are its tiles.
    Scene("machine-no-board", route = Routes.machine(GPU)),

    // The Herdr desktop app's herdr-core (DemoCore): devbox runs it next to classic herdr, mac-mini alone. Its agents are on
    // the Agents list and in Needs you, answered on the card (only its Claude asks here); the machines show its spaces,
    // tabs and terminals; its Claude's chat is its screen; its shell opens as a live terminal.
    Scene("agents-desktop", heightDp = 1700, setup = { home(); desktopApp() }),
    Scene("agents-desktop-yes", setup = { home(); host(DEVBOX).snapshot = Demo.devbox(home = true, apiStatus = "working"); desktopApp() }, actions = { tapExact("Yes") }),
    Scene("machines-desktop", setup = { desktopApp() }, actions = { tapLabel("Spaces") }),
    Scene("machine-desktop", route = Routes.machine(DEVBOX), heightDp = 1300, setup = { desktopApp() }),
    Scene("machine-desktop-only", route = Routes.machine(Demo.MINI), setup = { desktopApp() }),
    Scene("chat-desktop", route = Routes.agent(DEVBOX, DemoCore.CLAUDE), setup = { desktopApp() }),
    Scene("terminal-desktop", route = Routes.terminal(DEVBOX, DemoCore.SHELL), setup = { desktopApp() }),
    // Its history is the phone's own (filled from terminal.read), found in as classic herdr's scrollback is: the match marked and scrolled to.
    Scene("terminal-desktop-find", route = Routes.terminal(DEVBOX, DemoCore.SHELL), setup = { desktopApp() }, actions = { tapLabel("Find"); type("failed"); repeat(4) { tapLabel("Older") } }),
    // Shepherdr 3 on devbox: its agents run in herdr-core beside classic herdr, called by their task or space (from
    // Shepherdr's labels); on the Agents list with the rest (tapping one opens its task's chat), and on the machine screen.
    Scene("agents-shepherdr3", heightDp = 1700, setup = { home(); shepherdr3() }),
    Scene("agents-shepherdr3-open", heightDp = 1700, setup = { home(); shepherdr3() }, actions = { tapText("AND-19 · claude") }),
    Scene("machine-shepherdr3", route = Routes.machine(DEVBOX), heightDp = 1300, setup = { shepherdr3() }),
    Scene("machines-shepherdr3", setup = { shepherdr3() }, actions = { tapLabel("Spaces") }),
    // The Chat tab: the shown space's Assistant (Shepherdr), or its agent (classic herdr); its ⋯ with the space's tabs
    // and the + menu's rows; and the Artifacts tab, the shown space's.
    Scene("chat-tab", route = Routes.CHAT, setup = { shepherdr3() }),
    Scene("chat-tab-agent", route = Routes.CHAT, setup = { home() }),
    Scene("chat-tab-menu", route = Routes.CHAT, setup = { home() }, actions = { tapLabel("Tabs and new") }),
    Scene("artifacts-tab", route = Routes.ARTIFACTS_TAB, setup = { shepherdr() }),
    Scene("artifacts-tab-none", route = Routes.ARTIFACTS_TAB, setup = { home() }),
    // A task's chat takes voice messages too (kept by Shepherdr's service, then the chat as its file), as the Assistant's.
    Scene("task-v2-voice-sent", route = Routes.board(DEVBOX, "AND-19"), setup = { shepherdr3() }, actions = {
        recordVoice()
        tapLabel("Send voice message")
    }),
    // Every agent's chat keeps its whole composer (text, attach, mic, Send): a herdr-core agent at work, Shepherdr 3's
    // task worker and Assistant opened as agents, and the task's own chat.
    Scene("chat-core-working", route = Routes.agent(DEVBOX, DemoCore.CODEX), setup = { desktopApp() }),
    // A herdr-core agent whose transcript isn't found: its chat is its screen, with the question's card.
    Scene("chat-core-screen", route = Routes.agent(DEVBOX, DemoCore.CLAUDE), setup = { desktopApp() }),
    Scene("chat-shepherdr3-agent", route = Routes.agent(DEVBOX, "hc:t4"), setup = { shepherdr3() }),
    Scene("chat-shepherdr3-asks", route = Routes.agent(DEVBOX, "hc:t2"), setup = { shepherdr3() }),
    Scene("chat-shepherdr3-coordinator", route = Routes.agent(DEVBOX, "hc:t1"), setup = { shepherdr3() }),
    // The tab menu (3.1's): the Chat tab's ⋯ starts with the shown tab's rows; an agent's More sheet with its tab's.
    Scene("tab-menu", route = Routes.CHAT, setup = { shepherdr3Tabs() }, actions = { tapLabel("Tabs and new") }),
    Scene("tab-restart", route = Routes.CHAT, setup = { shepherdr3Tabs() }, actions = { tapLabel("Tabs and new"); tapExact("Restart") }),
    Scene("tab-close-assistant", route = Routes.CHAT, setup = { shepherdr3Tabs() }, actions = { tapLabel("Tabs and new"); tapExact("Close tab") }),
    Scene("tab-assistant-more", route = Routes.agent(DEVBOX, "hc:t1"), setup = { shepherdr3Tabs() }, actions = { tapLabel("More") }),
    Scene("tab-agent-more", route = Routes.agent(DEVBOX, "hc:t8"), setup = { shepherdr3Tabs() }, actions = { tapLabel("More") }),
    Scene("tab-rename", route = Routes.agent(DEVBOX, "hc:t8"), setup = { shepherdr3Tabs() }, actions = { tapLabel("More"); tapExact("Rename…") }),
    Scene("tab-switch", route = Routes.agent(DEVBOX, "hc:t8"), setup = { shepherdr3Tabs() }, actions = { tapLabel("More"); tapExact("Switch agent") }),
    Scene("tab-switch-confirm", route = Routes.agent(DEVBOX, "hc:t8"), setup = { shepherdr3Tabs() }, actions = { tapLabel("More"); tapExact("Switch agent"); tapExact("Codex") }),
    Scene("tab-model", route = Routes.agent(DEVBOX, "hc:t8"), setup = { shepherdr3Tabs() }, actions = { tapLabel("More"); tapExact("Model…") }),
    Scene("tab-model-change", route = Routes.agent(DEVBOX, "hc:t8"), setup = { shepherdr3Tabs() }, actions = { tapLabel("More"); tapExact("Model…"); tapExact("Sonnet 5.5") }),
    Scene("tab-close", route = Routes.agent(DEVBOX, "hc:t8"), setup = { shepherdr3Tabs() }, actions = { tapLabel("More"); tapExact("Close tab") }),
    Scene("task-shepherdr3", route = Routes.board(DEVBOX, "AND-19"), setup = { shepherdr3() }),
    // Its chat not read yet (or not readable): the composer is whole and writable all the same; and attaching a file.
    Scene("task-shepherdr3-unread", route = Routes.board(DEVBOX, "AND-19"), setup = { shepherdr3(); host(DEVBOX).shepherdr?.chatRefused = "Couldn't read the chat" }),
    Scene("task-shepherdr3-attach", route = Routes.board(DEVBOX, "AND-19"), setup = { shepherdr3() }, actions = { tapLabel("Attach files") }),
    // Add machine: scanning the code herdr-push pair shows (the phone's own scanner is a system screen; the stand-in
    // reads studio's code), studio found on Tailscale, studio answering nowhere, and typing the address instead.
    Scene("add-machine", route = Routes.edit()),
    Scene("pair-found", route = Routes.edit(), actions = { tapText("Point at the code"); settle() }),
    Scene("pair-unreachable", route = Routes.edit(), setup = { reachable = emptyMap() }, actions = { tapText("Point at the code"); settle() }),
    Scene("add-machine-typed", route = Routes.edit(), actions = { tapText("Type the address instead") }),

    // A machine's health: meters, the last hour, the biggest processes.
    // Taller, to show the whole screen (scrolling never settles in the desktop harness).
    Scene("health", route = Routes.health(DEVBOX), heightDp = 2000),
    // Low on memory: the warning names what uses the most.
    Scene("health-low", route = Routes.health(DEVBOX), setup = { host(DEVBOX).health.availKb = 3_600_000 }),
    Scene("health-process", route = Routes.health(DEVBOX), actions = { tapText("Android emulator · Pixel_8") }),

    // A machine's AI providers: Claude Code near its 5-hour limit, Codex, the API accounts, a local model;
    // each with its 7-day chart and the tasks and agents that used it most. Taller, to show every card.
    Scene("usage", route = Routes.usage(DEVBOX), heightDp = 3400),
    // Out of the 5-hour allowance: red, with when it comes back.
    Scene("usage-out", route = Routes.usage(DEVBOX), setup = { host(DEVBOX).usage!!.claudeSession = 100.0 }),
    // Read at 98% before its 07:00 reset (it is 07:42 now, nothing newer read): the session counts as fresh,
    // the weekly limit is the tightest, and the chip says so.
    Scene("usage-reset", route = Routes.usage(DEVBOX), setup = { host(DEVBOX).usage!!.apply { claudeSession = 98.0; claudeResetHour = 7 } }),
    Scene("usage-reset-chip", setup = { host(DEVBOX).usage!!.apply { claudeSession = 98.0; claudeResetHour = 7 } }),
    // Reading failed and nothing kept: why, with a retry.
    Scene("usage-error", route = Routes.usage(DEVBOX), setup = {
        host(DEVBOX).usage!!.refuse = "Reading usage needs python3 on devbox, and it isn't installed"
    }),

    // A machine's agent logins: each CLI signed in or not, how and until when, and the keys in ~/.bashrc.
    Scene("logins", route = Routes.logins(DEVBOX)),
    // Claude's login ran out two days ago: red, with Sign in.
    Scene("logins-expired", route = Routes.logins(DEVBOX), setup = { host(DEVBOX).logins!!.claudeDays = -2 }),
    Scene("logins-error", route = Routes.logins(DEVBOX), setup = { host(DEVBOX).logins!!.refuse = "Reading the logins needs python3 on devbox, and it isn't installed" }),
    // A CLI's actions; signing Codex in again with its device code (the code to type on the phone); Claude's browser
    // code pasted back; a key for a pi provider with none yet; how to move the keys out of ~/.bashrc.
    Scene("logins-menu", route = Routes.logins(DEVBOX), actions = { tapExact("Codex") }),
    Scene("logins-codex", route = Routes.logins(DEVBOX), actions = { tapExact("Codex"); tapText("Sign in again") }),
    Scene("logins-claude", route = Routes.logins(DEVBOX), actions = { tapExact("Claude Code"); tapText("Sign in again") }),
    Scene("logins-key", route = Routes.logins(DEVBOX), actions = {
        tapExact("pi"); tapText("Add a provider's key"); typeInto("Provider", "groq"); typeInto("Key", "gsk_demo0000000000000000")
    }),
    Scene("logins-profile", route = Routes.logins(DEVBOX), actions = { tapText("Show me how to move them") }),
    // opencode: each login signed out by its id; its sign-in (menus the phone can't answer) shown as steps.
    Scene("logins-opencode", route = Routes.logins(DEVBOX), actions = { tapExact("opencode") }),

    // An agent's chat: its transcript, a blocked agent's question, a Codex transcript.
    Scene("chat", route = Routes.agent(DEVBOX, APP_AGENT)),
    Scene("chat-blocked", route = Routes.agent(DEVBOX, API_AGENT)),
    // An agent at work on a board task: the title bar names the task, the agent and where it runs under it.
    Scene("chat-task", route = Routes.agent(DEVBOX, APP_AGENT), setup = { home() }),
    // The question docked over the composer, made for thumbs: "No, tell it…" folds it while the reply is written;
    // on a phone with no screen lock, "Always for npm run" asks for a tap on Confirm instead of the fingerprint.
    Scene("prompt-tell", route = Routes.agent(DEVBOX, API_AGENT), actions = { tapText("No, tell it…") }),
    Scene("prompt-confirm", route = Routes.agent(DEVBOX, API_AGENT), setup = { screenLock = false }, actions = { tapText("Always for npm run") }),
    // Claude's own questions: one with descriptions and a recommended pick, and a form of three with a multiple choice.
    Scene("prompt-question", route = Routes.agent(DEVBOX, API_AGENT), setup = { host(DEVBOX).screens += API_AGENT to Demo.resource("screen-claude-question.txt") }),
    Scene("prompt-checks", route = Routes.agent(DEVBOX, API_AGENT), setup = { host(DEVBOX).screens += API_AGENT to Demo.resource("screen-claude-checks.txt") }),
    Scene("chat-codex", route = Routes.agent(DEVBOX, SITE_AGENT)),
    // Back from the lock screen with a long model name: while it reconnects, "Not connected" keeps to one line.
    Scene("chat-reconnecting", route = Routes.agent(DEVBOX, SITE_AGENT), widthDp = 360, heightDp = 780, setup = {
        host(DEVBOX).transcripts = host(DEVBOX).transcripts.mapValues { it.value.replace("\"gpt-5.5\"", "\"remote-magpie/zhishu/gpt-6.1-sol\"") }
    }, actions = { ctx ->
        ctx.data.host(DEVBOX).refuse = com.makefunstuff.herdr.core.ssh.ConnectError.Auth(null)
        FakeMachines.closeAll()
        settle()
    }),
    // An edit opened: its file and its lines removed and added, while Claude's runs; Codex's patch of a page; a
    // whole new file, folded to its first lines until "Show all".
    Scene("chat-edit", route = Routes.agent(DEVBOX, APP_AGENT), actions = { tapExact("Edit") }),
    Scene("chat-codex-edit", route = Routes.agent(DEVBOX, SITE_AGENT), actions = { tapText("Worked for"); tapExact("Edit") }),
    // A script that applies a patch and runs a check: its diff, and its script and output beside it.
    Scene("chat-codex-exec", route = Routes.agent(DEVBOX, SITE_AGENT), setup = {
        host(DEVBOX).transcripts = host(DEVBOX).transcripts + (Demo.SITE_SESSION to Demo.siteTranscriptExec())
    }, actions = { tapText("Worked for"); tapExact("Run") }),
    Scene("chat-edit-long", route = Routes.agent(DEVBOX, APP_AGENT), setup = {
        host(DEVBOX).transcripts = host(DEVBOX).transcripts + (Demo.APP_SESSION to Demo.appTranscriptWrite())
    }, actions = { tapExact("Write") }),
    // Chats you can skim: a settled turn opened from its "Worked for" header; a turn done with a failed test run on
    // the way, counted in red on its folded header; back after 44 minutes away, what happened since in three lines;
    // scrolled up while the agent wrote two more messages, the pill that counts them.
    Scene("chat-turn-open", route = Routes.agent(DEVBOX, APP_AGENT), actions = { tapText("Worked for 2m 36s") }),
    Scene("chat-done", route = Routes.agent(DEVBOX, APP_AGENT), setup = {
        host(DEVBOX).snapshot = Demo.devbox(appStatus = "done")
        host(DEVBOX).transcripts = host(DEVBOX).transcripts + (Demo.APP_SESSION to Demo.appTranscriptDone())
    }),
    // The agent's reasoning: folded to a line above the answer it led to, opened by a tap; live while it thinks.
    Scene("chat-reasoning", route = Routes.agent(DEVBOX, APP_AGENT), setup = {
        host(DEVBOX).snapshot = Demo.devbox(appStatus = "done")
        host(DEVBOX).transcripts = host(DEVBOX).transcripts + (Demo.APP_SESSION to Demo.appTranscriptReasoning())
    }),
    Scene("chat-reasoning-open", route = Routes.agent(DEVBOX, APP_AGENT), setup = {
        host(DEVBOX).snapshot = Demo.devbox(appStatus = "done")
        host(DEVBOX).transcripts = host(DEVBOX).transcripts + (Demo.APP_SESSION to Demo.appTranscriptReasoning())
    }, actions = { tapExact("Thought") }),
    Scene("chat-thinking", route = Routes.agent(DEVBOX, APP_AGENT), setup = {
        host(DEVBOX).transcripts = host(DEVBOX).transcripts + (Demo.APP_SESSION to Demo.appTranscriptThinking())
    }),
    // Claude handed part of the work to a subagent: its call offers the subagent's own steps.
    Scene("chat-subagent", route = Routes.agent(DEVBOX, APP_AGENT), setup = { subagent() }, actions = { settle(); tapText("Find what reads the snapshot time"); settle() }),
    Scene("subagent", route = Routes.subagent(DEVBOX, APP_AGENT, Demo.APP_SUBAGENT, "Find what reads the snapshot time"), setup = { subagent() }),
    // Two messages sent while it works: it queued them, and takes them once done with this step.
    Scene("chat-queued", route = Routes.agent(DEVBOX, APP_AGENT), setup = {
        host(DEVBOX).transcripts = host(DEVBOX).transcripts + (Demo.APP_SESSION to Demo.appTranscriptWaiting().trimEnd() + "\n" +
            """{"type":"queue-operation","operation":"enqueue","timestamp":"2026-10-17T07:40:01.000Z","sessionId":"${Demo.APP_SESSION}","content":"Also show it on the machine's own screen"}""" + "\n" +
            """{"type":"queue-operation","operation":"enqueue","timestamp":"2026-10-17T07:40:09.000Z","sessionId":"${Demo.APP_SESSION}","content":"And keep the wording short"}""" + "\n")
    }),
    Scene("chat-thinking-wait", route = Routes.agent(DEVBOX, APP_AGENT), setup = {
        host(DEVBOX).transcripts = host(DEVBOX).transcripts + (Demo.APP_SESSION to Demo.appTranscriptWaiting())
    }),
    // pi (here on a local model) streams its thought to its screen and writes it only with its next call:
    // the chat reads it off the screen, in italics over the editor, while it works.
    Scene("chat-pi-thinking", route = Routes.agent(DEVBOX, APP_AGENT), setup = {
        host(DEVBOX).snapshot = Demo.devbox(appKind = "pi")
        host(DEVBOX).transcripts = host(DEVBOX).transcripts + (Demo.APP_SESSION to Demo.resource("pi-app.jsonl"))
        host(DEVBOX).screens = host(DEVBOX).screens + (APP_AGENT to Demo.resource("screen-pi-app.ansi"))
    }),
    Scene("chat-away", route = Routes.agent(DEVBOX, APP_AGENT), setup = { looked = mapOf("$DEVBOX/$APP_AGENT" to Demo.NOW - 44 * 60_000) }),
    Scene("chat-new-messages", route = Routes.agent(DEVBOX, APP_AGENT), setup = {
        host(DEVBOX).transcripts = host(DEVBOX).transcripts + (Demo.APP_SESSION to Demo.appTranscriptDone())
    }, actions = { ctx ->
        scrollTo(4)
        ctx.data.host(DEVBOX).append(Demo.APP_SESSION, Demo.APP_NEXT)
        settle()
    }),

    // A composer that knows the agent: what you sent while it worked, held above the box with Send now and Discard
    // (and once it is done, waiting for you to review and send); the quick replies, when on; `/` listing Claude's
    // own commands with the project's; `@` completing a file in its folder; the model pill and its details.
    Scene("chat-held", route = Routes.agent(DEVBOX, APP_AGENT), setup = { held(); replies() }),
    Scene("chat-held-ready", route = Routes.agent(DEVBOX, APP_AGENT), setup = {
        held()
        host(DEVBOX).snapshot = Demo.devbox(appStatus = "done")
        host(DEVBOX).transcripts = host(DEVBOX).transcripts + (Demo.APP_SESSION to Demo.appTranscriptDone())
    }),
    Scene("chat-commands", route = Routes.agent(DEVBOX, APP_AGENT), setup = { held(); replies() }, actions = { type("/") }),
    Scene("chat-files", route = Routes.agent(DEVBOX, APP_AGENT), actions = { type("Look at @mach") }),
    Scene("chat-model", route = Routes.agent(DEVBOX, APP_AGENT), actions = { tapLabel("Model Opus 5.5, context 62% full") }),
    // The menu offers to restart it on the same conversation: asked first, with words to send once it's back,
    // or in one tap that then tells it to continue (its steps over the chat).
    Scene("chat-restart", route = Routes.agent(DEVBOX, APP_AGENT), actions = { tapLabel("More"); tapText("Quit it and open the same"); settle() }),
    Scene("chat-restarting", route = Routes.agent(DEVBOX, APP_AGENT), actions = { tapLabel("More"); tapText("Restart and continue") }),
    Scene("quick-replies", route = Routes.COMPOSER, setup = { replies() }),
    // Voice messages: the mic held while you speak (the time, how loud, slide to cancel), slid away far enough
    // that letting go cancels, tapped to go on hands-free; sent (copied to the machine, then to the agent as its
    // file) and played back; one whose copy failed, to retry; one held while the agent works; too short to send;
    // why the microphone is needed while Android asks for it, and again once refused.
    Scene("chat-voice-recording", route = Routes.agent(DEVBOX, APP_AGENT), actions = { hold("Talk to type", 0.5f to 0.5f) }),
    Scene("chat-voice-cancel", route = Routes.agent(DEVBOX, APP_AGENT), actions = { hold("Talk to type", 0.5f to 0.5f, -2.5f to 0.6f) }),
    Scene("chat-voice-tap", route = Routes.agent(DEVBOX, APP_AGENT), actions = { recordVoice() }),
    Scene("chat-voice-sent", route = Routes.agent(DEVBOX, APP_AGENT), setup = { appDone() }, actions = {
        recordVoice()
        tapLabel("Send voice message")
    }),
    Scene("chat-voice-playing", route = Routes.agent(DEVBOX, APP_AGENT), setup = { appDone() }, actions = {
        recordVoice()
        tapLabel("Send voice message")
        tapLabel("Play voice message")
    }),
    Scene("chat-voice-failed", route = Routes.agent(DEVBOX, APP_AGENT), setup = {
        appDone()
        host(DEVBOX).uploadFails = "No space left on device"
    }, actions = {
        recordVoice()
        tapLabel("Send voice message")
    }),
    Scene("chat-voice-held", route = Routes.agent(DEVBOX, APP_AGENT), actions = {
        recordVoice()
        tapLabel("Send voice message")
    }),
    Scene("chat-voice-short", route = Routes.agent(DEVBOX, APP_AGENT), setup = { mic = PreviewMic.Setup(spokeFor = 400) }, actions = {
        recordVoice()
        tapLabel("Send voice message")
    }),
    Scene("chat-voice-denied", route = Routes.agent(DEVBOX, APP_AGENT), setup = {
        mic = PreviewMic.Setup(allowed = false, answer = false)
    }, actions = { tapLabel("Talk to type") }),
    Scene("chat-voice-asking", route = Routes.agent(DEVBOX, APP_AGENT), setup = {
        mic = PreviewMic.Setup(allowed = false, answer = null)
    }, actions = { tapLabel("Talk to type") }),
    // Talk to type, a tap on the mic: listening (the box gives way to how loud you are), the phone's recogniser's
    // words as you speak, Done putting them in the box after what was typed, to edit and send; nothing heard. A task's
    // chat on Shepherdr 3 has its machine write it down (whisper.cpp there), as 3.1's web app does.
    Scene("chat-dictate", route = Routes.agent(DEVBOX, APP_AGENT), actions = { tapLabel("Talk to type") }),
    Scene("chat-dictate-heard", route = Routes.agent(DEVBOX, APP_AGENT), setup = {
        mic = PreviewMic.Setup(heard = "run the failing test again and")
    }, actions = { tapLabel("Talk to type") }),
    Scene("chat-dictate-done", route = Routes.agent(DEVBOX, APP_AGENT), actions = {
        type("Before you merge,")
        tapLabel("Talk to type")
        tapLabel("Done: put it in the box")
    }),
    Scene("chat-dictate-nothing", route = Routes.agent(DEVBOX, APP_AGENT), setup = { mic = PreviewMic.Setup(said = "") }, actions = {
        tapLabel("Talk to type")
        tapLabel("Done: put it in the box")
    }),
    Scene("task-v2-dictate", route = Routes.board(DEVBOX, "AND-19"), setup = { shepherdr3(); mic = PreviewMic.Setup(recognises = false) }, actions = {
        tapLabel("Talk to type")
        tapLabel("Done: put it in the box")
    }),
    // The chats' own text size (Settings → Text size), larger than the phone's.
    Scene("chat-large-text", route = Routes.agent(DEVBOX, APP_AGENT), setup = { textSizes = com.makefunstuff.herdr.data.TextSizeStore.Sizes(chat = 1.3f) }),

    // Pictures for an agent: what the attach button offers, two screenshots in the composer (copied to devbox,
    // each with its ×), one opened to mark up (an arrow, a highlight, a ring drawn round), one being cropped;
    // then the message sent with them, as thumbnails in the chat, and one shown full size.
    Scene("attach-menu", route = Routes.agent(DEVBOX, APP_AGENT), actions = { tapLabel("Attach files") }),
    Scene("chat-pictures", route = Routes.agent(DEVBOX, APP_AGENT), setup = { pictures() }),
    Scene("picture-markup", route = Routes.agent(DEVBOX, APP_AGENT), setup = { pictures() }, actions = {
        tapLabel("Picture 1 of 2, $SHOT_1")
        tapText("Arrow")
        drag("The picture", 0.85f to 0.62f, 0.55f to 0.42f, within = ::screenshotIn)
        tapText("Highlight")
        drag("The picture", 0.06f to 0.335f, 0.6f to 0.335f, within = ::screenshotIn)
        tapText("Draw")
        drag(
            "The picture", 0.5f to 0.2f, 0.85f to 0.22f, 0.95f to 0.28f, 0.85f to 0.34f, 0.5f to 0.35f, 0.08f to 0.33f,
            0.04f to 0.27f, 0.12f to 0.21f, 0.52f to 0.19f, within = ::screenshotIn,
        )
    }),
    Scene("picture-crop", route = Routes.agent(DEVBOX, APP_AGENT), setup = { pictures() }, actions = {
        tapLabel("Picture 1 of 2, $SHOT_1")
        tapText("Crop")
        // The top left corner down and in, the bottom edge up: the cards around the Board card are kept.
        drag("The picture", 0.01f to 0.01f, 0.05f to 0.13f, within = ::screenshotIn)
        drag("The picture", 0.5f to 0.99f, 0.5f to 0.55f, within = ::screenshotIn)
    }),
    Scene("chat-sent-pictures", route = Routes.agent(DEVBOX, APP_AGENT), setup = { sentPictures() }),
    Scene("picture-view", route = Routes.agent(DEVBOX, APP_AGENT), setup = { sentPictures() }, actions = {
        tapLabel("Picture 2 of 2, Screenshot_20261018_101230.png")
    }),

    // Search: one box for agents, tasks, machines, workspaces and words in the chats, with its chips and actions.
    // "jobs" across the machines; nothing typed (Start work, then what waits); from devbox, only its own, with a
    // workspace's terminal; and a chip chosen.
    Scene("search", route = Routes.search(q = "jobs"), setup = { home() }, actions = { settle() }),
    Scene("search-empty", route = Routes.search(), setup = { home() }),
    Scene("search-machine", route = Routes.search(DEVBOX, "api"), setup = { home() }, actions = { settle() }),
    // Shepherdr's tasks are found too, and open as their agents' chats.
    Scene("search-v2", route = Routes.search(q = "export"), setup = { home(); v2() }, actions = { settle() }),
    Scene("search-v2-open", route = Routes.search(q = "export"), setup = { home(); v2() }, actions = { settle(); tapText("Rate-limit the export endpoint") }),
    Scene("search-needs", route = Routes.search(q = "jobs"), setup = { home() }, actions = { settle(); tapText("Needs you") }),

    // Start work from a sentence: as a board task on devbox; as an agent now (its folders and the agents
    // installed there); and the folder browser, one level down.
    Scene("start", route = Routes.start(text = "Add a “last answered 2 min ago” line to each machine card, and a test for it"), setup = { home() }),
    Scene("start-v2", route = Routes.start(text = "Add a “last answered 2 min ago” line to each machine card, and a test for it"), setup = { home(); v2() }),
    Scene(
        "start-agent", route = Routes.start(text = "Add a “last answered 2 min ago” line to each machine card, and a test for it"), setup = { home() },
        actions = { tapExact("Agent now") },
    ),
    Scene(
        "start-folders", route = Routes.start(text = "Look into the flaky upload test"), setup = { home() },
        actions = { tapExact("Agent now"); tapText("Browse"); settle(); tapExact("scratch") },
    ),

    // On Shepherdr 3 each space has its own Assistant: herdr-android's chat with its tasks' news lines, a task's plan
    // headed by its task to approve, and a ready line with Accept; the home space's quiet one; one at work with your
    // message on its way and Shepherdr's working line; and a plan approved from here.
    Scene("coordinator-v2", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = { shepherdr() }),
    // A task's news line (its agent chip until HERD-22) opens that task's chat.
    Scene("coordinator-v2-chips", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = { host(DEVBOX).shepherdr = FakeShepherdr() }),
    Scene("coordinator-v2-agent", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = { host(DEVBOX).shepherdr = FakeShepherdr() }, actions = { settle(); tapText("Fix the flaky upload test") }),
    Scene("coordinator-v2-home", route = Routes.coordinator(DEVBOX), setup = { shepherdr() }),
    // Autopilot, as 3.1's Assistant header: the switch in the bar; on, what it waits for under it, and "Write a
    // heartbeat" while the heartbeat is still herdr-task's starter; switched on from here.
    Scene("coordinator-v2-autopilot", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = {
        shepherdr(); DemoV2.autopilotOn(host(DEVBOX).shepherdr!!, DemoV2.APP, blank = true)
    }),
    Scene("coordinator-v2-autopilot-on", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = { shepherdr() }, actions = { settle(); tapLabel("Autopilot"); settle() }),
    // A message the app can't read yet: a quiet line among replies that still show. Its log not found on the machine:
    // Shepherdr's notice, over what it still has.
    Scene("coordinator-v2-unknown", route = Routes.coordinator(DEVBOX), setup = { unknownMessage() }),
    Scene("coordinator-v2-notice", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = { chatNotFound() }),
    // A ready line's Accept: the Accept card, what you approve before it merges.
    Scene("coordinator-v2-accept", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = { shepherdr() }, actions = { settle(); tapExact("Accept") }),
    // A voice call with the Assistant (Shepherdr's voice agent on the machine): its bar over the message box.
    Scene("coordinator-v2-call", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = {
        shepherdr()
        call = CallState(on = true, machineId = DEVBOX, space = DemoV2.APP, who = "the herdr-android Assistant", phase = CallState.Phase.Listening)
    }),
    Scene("coordinator-v2-call-ended", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = {
        shepherdr()
        call = CallState(on = false, machineId = DEVBOX, space = DemoV2.APP, who = "the herdr-android Assistant", problem = "No ElevenLabs key. Add ELEVENLABS_API_KEY in Settings › Secrets.")
    }),
    Scene("coordinator-v2-working", route = Routes.coordinator(DEVBOX, DemoV2.API), setup = {
        shepherdr()
        drafts[com.makefunstuff.herdr.data.DraftStore.coordinator(DEVBOX, DemoV2.API)] = "Also check the /jobs/export endpoint"
    }, actions = { tapLabel("Send") }),
    Scene("coordinator-v2-answered", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = { shepherdr() }, actions = { settle(); tapExact("Approve") }),
    // Shepherdr 3.1's artifacts, recipes the app draws itself: a card in an Assistant's chat and in a task's, full
    // screen (text, lists, a callout and a choice; numbers, a chart, a table, before and after), its versions and an
    // older one that is a panel only the Herdr window draws, Mark up on one block, and a space's Artifacts list.
    Scene("coordinator-v2-artifact", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = { artifacts() }),
    Scene("task-artifact", route = Routes.board(DEVBOX, "AND-19"), setup = { artifacts() }),
    Scene("artifact", route = Routes.artifact(DEVBOX, "rn09"), setup = { artifacts() }),
    Scene("artifact-versions", route = Routes.artifact(DEVBOX, "rn09"), setup = { artifacts() }, actions = { settle(); tapLabel("Versions") }),
    Scene("artifact-older", route = Routes.artifact(DEVBOX, "rn09", 1), setup = { artifacts() }),
    Scene("artifact-recipe", route = Routes.artifact(DEVBOX, "bl3x"), heightDp = 1800, setup = { artifacts() }),
    Scene("artifact-gpui", route = Routes.artifact(DEVBOX, "bl3x", 1), setup = { artifacts() }),
    Scene("artifact-markup", route = Routes.artifact(DEVBOX, "bl3x"), setup = { artifacts() }, actions = { settle(); tapLabel("Mark up"); tapText("Board load times") }),
    Scene("artifact-answered", route = Routes.artifact(DEVBOX, "rn09"), setup = { artifacts() }, actions = { settle(); tapExact("Yes") }),
    Scene("artifacts", route = Routes.artifacts(DEVBOX, DemoV2.APP), setup = { artifacts() }),
    // An earlier post of one folds to a line; one deleted, a version no longer kept, a space with none yet.
    Scene("coordinator-v2-artifact-folded", route = Routes.coordinator(DEVBOX, DemoV2.APP), heightDp = 1300, setup = { artifactPostedTwice() }),
    Scene("artifact-gone", route = Routes.artifact(DEVBOX, "zz00"), setup = { artifacts() }),
    Scene("artifact-not-kept", route = Routes.artifact(DEVBOX, "rn09", 9), setup = { artifacts() }),
    Scene("artifacts-empty", route = Routes.artifacts(DEVBOX, "sp-website"), setup = { artifacts() }),
    // A voice message to a space's Assistant: recording, then kept by Shepherdr's service and in the chat as its file.
    Scene("coordinator-v2-voice", route = Routes.coordinator(DEVBOX), setup = { shepherdr() }, actions = { recordVoice() }),
    Scene("coordinator-v2-voice-sent", route = Routes.coordinator(DEVBOX), setup = { shepherdr() }, actions = {
        recordVoice()
        tapLabel("Send voice message")
    }),
    // The Assistant's voice: turned on with the headphones, reading a reply (a tap stops it), hearing you
    // hands-free, and the phone's own voice while the server is away, muted.
    Scene("coordinator-v2-hear", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = { shepherdr() }, actions = { tapLabel("Hear the Assistant") }),
    Scene("coordinator-v2-hear-speaking", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = {
        shepherdr()
        voice = VoiceState(on = true, machineId = DEVBOX, space = DemoV2.APP, spaceName = "herdr-android", phase = VoiceState.Phase.Speaking, level = 0.6f, speed = 1.2f)
    }),
    Scene("coordinator-v2-hear-listening", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = {
        shepherdr()
        voice = VoiceState(on = true, machineId = DEVBOX, space = DemoV2.APP, spaceName = "herdr-android", phase = VoiceState.Phase.Listening, handsFree = true, level = 0.8f)
    }),
    Scene("coordinator-v2-hear-phone", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = {
        shepherdr()
        voice = VoiceState(on = true, machineId = DEVBOX, space = DemoV2.APP, spaceName = "herdr-android", muted = true, engine = VoiceState.Engine.Phone)
    }),
    // 3.1's chat conveniences: reactions and a reply's line, a held message's bar, a reaction given, a reply's line
    // pressed (it goes to what it answers, lit), and Find in chat with its count and lit matches.
    Scene("coordinator-v2-reactions", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = { chatParity() }),
    Scene("coordinator-v2-hold", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = { chatParity() }, actions = { settle(); longPressText("The card stays put now") }),
    Scene("coordinator-v2-reacted", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = { chatParity() }, actions = {
        settle(); longPressText("The card stays put now"); tapLabel("React ✅"); waitForRead(4)
    }),
    Scene("coordinator-v2-replying", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = { chatParity() }, actions = {
        settle(); longPressText("The card stays put now"); tapExact("Reply")
    }),
    Scene("coordinator-v2-reply-jump", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = { chatParity(between = 4) }, actions = { settle(); tapText("AND-13 passed its checks…") }),
    Scene("coordinator-v2-find", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = { chatParity() }, actions = { settle(); tapLabel("Find in chat"); type("AND-13") }),
    Scene("coordinator-v2-find-none", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = { chatParity() }, actions = { settle(); tapLabel("Find in chat"); type("zebra") }),
    Scene("task-shepherdr3-find", route = Routes.board(DEVBOX, "AND-19"), setup = { shepherdr3() }, actions = { settle(); tapLabel("Find in chat"); type("index") }),
    Scene("coordinator-v2-up", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = { shepherdr() }, actions = { scrollTo(4) }),
    Scene("coordinator-v2-new-message", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = { shepherdr() }, actions = { ctx ->
        scrollTo(4)
        ctx.data.host(DEVBOX).v2Says(DemoV2.chat(DemoV2.APP), "m8", "AND-15 is on its way: the recognizer is wired, the composer's mic comes next.")
        waitForRead()
    }),
    Scene("coordinator-v2-switch", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = { shepherdr(); longWebsiteChat() }, actions = {
        scrollTo(4)
        tapText("website")
    }),
    // Reaching the Coordinator from outside the app: a share (a log line and a screenshot) choosing its machine,
    // the tile or widget when it can't tell which, and the chat it lands in with the shared words in its box.
    Scene("share", route = Routes.ask(Ask.SHARE), setup = { shepherdr(); shared = Demo.shared() }),
    Scene("ask", route = Routes.ask(Ask.COORDINATOR), setup = { shepherdr() }),
    Scene(
        "share-sent", route = Routes.ask(Ask.SHARE),
        setup = { shepherdr(); shared = Demo.shared().let { s -> s.copy(files = s.files.map { f -> f.copy(path = Demo.sharedFile().path) }) } },
        actions = { tapText("devbox"); settle() },
    ),
    // Shared to a machine that isn't connected: the words wait in the box, the screenshot to be copied once it is.
    Scene(
        "share-waiting", route = Routes.ask(Ask.SHARE),
        setup = { shepherdr(); shared = Demo.shared().let { s -> s.copy(files = s.files.map { f -> f.copy(path = Demo.sharedFile().path) }) } },
        actions = { tapText("mac-mini"); settle() },
    ),
    // Shepherdr 3: the board through its service (what needs you first, then each space, each task with its agent's
    // chip, Shepherdr's own second line and the minutes left), a task as its agent's chat with its cards answered in
    // it, Accept's card, and adding a task, which goes to a space's Assistant.
    Scene("board-v2", route = Routes.board(DEVBOX), setup = { v2() }),
    Scene("board-v2-tall", route = Routes.board(DEVBOX), heightDp = 1700, setup = { v2() }, actions = { tapText("DONE · 2") }),
    Scene("task-v2-asks", route = Routes.board(DEVBOX, "AND-21"), setup = { v2() }),
    Scene("task-v2-answered", route = Routes.board(DEVBOX, "AND-21"), setup = { v2() }, actions = { tapText("Seen 2 min ago") }),
    Scene("task-v2-multi", route = Routes.board(DEVBOX, "AND-24"), setup = { v2() }, actions = { tapText("Key bar"); tapText("Alert rules") }),
    Scene("task-v2-ready", route = Routes.board(DEVBOX, "AND-20"), setup = { v2() }),
    Scene("task-v2-accept", route = Routes.board(DEVBOX, "AND-20"), heightDp = 1300, setup = { v2() }, actions = { settle(); tapExact("Accept") }),
    // A task's Finished alert ("AND-20 · Finished") tapped: the task, its Accept card already up.
    Scene("task-v2-alert-accept", route = Routes.board(DEVBOX, "AND-20", alone = true, accept = true), heightDp = 1300, setup = { v2() }),
    // A call with a task's agent.
    Scene("task-v2-call", route = Routes.board(DEVBOX, "AND-19"), setup = {
        v2()
        call = CallState(on = true, machineId = DEVBOX, space = "sp-herdr-android", task = "AND-19", who = "AND-19", phase = CallState.Phase.Speaking)
    }),
    // A task opens as its agent's chat: its turns' steps folded to a line (one opened), its question cards
    // answered in it, the strip with its PR and Accept, and ⋯ for what it did, its brief and facts, and Close.
    Scene("task-v2-close", route = Routes.board(DEVBOX, "AND-20"), setup = { v2() }, actions = { tapLabel("Details and more"); tapText("Close, nothing merged") }),
    Scene("task-v2-menu", route = Routes.board(DEVBOX, "AND-20"), setup = { v2() }, actions = { tapLabel("Details and more") }),
    Scene("task-v2-brief", route = Routes.board(DEVBOX, "AND-20"), setup = { v2() }, actions = { tapLabel("Details and more"); tapText("Brief") }),
    Scene("task-v2-working", route = Routes.board(DEVBOX, "AND-19"), setup = { v2() }),
    Scene("task-v2-steps", route = Routes.board(DEVBOX, "AND-21"), setup = { v2() }, actions = { tapText("Worked for") }),
    Scene("task-v2-reply", route = Routes.board(DEVBOX, "AND-20"), setup = { v2() }, actions = { type("Make the limit 20 for the enterprise plan"); tapLabel("Send") }),
    // One not started yet has no chat: its details, with Close in ⋯ and nothing under the thumb.
    // 3.1's task view tabs: the task's worktree's Files and Changes (a file opens full screen, where chosen lines go to
    // the task's agent), its pull request with its checks, and a task whose folder is gone.
    Scene("task-v2-files", route = Routes.board(DEVBOX, "AND-20"), setup = { v2() }, actions = { tapText("FILES") }),
    Scene("task-v2-changes", route = Routes.board(DEVBOX, "AND-20"), setup = { v2() }, actions = { tapText("CHANGES") }),
    Scene("task-v2-pr", route = Routes.board(DEVBOX, "AND-20"), setup = { v2() }, actions = { tapText("PULL REQUEST") }),
    Scene(
        "task-v2-comment", route = Routes.codeFile(CodeSpot(DEVBOX, FakeCode.TASK, task = "AND-20", taskName = "AND-20"), CARD, line = 33), setup = { v2() },
        actions = { tapLabel("line 37"); tapLabel("line 38"); tapText("Comment"); type("Say when it was last answered, not when it was read.") },
    ),
    Scene("task-v2-no-folder", route = Routes.board(DEVBOX, "AND-18"), setup = { v2() }, actions = { tapText("FILES") }),
    Scene("task-v2-todo", route = Routes.board(DEVBOX, "AND-23"), setup = { v2() }),
    Scene("task-v2-todo-menu", route = Routes.board(DEVBOX, "AND-23"), setup = { v2() }, actions = { tapLabel("More") }),
    Scene("board-v2-add", route = Routes.board(DEVBOX), setup = { v2() }, actions = { tapLabel("Add a task") }),
    // A space's heading opens that space's Assistant chat.
    Scene("board-v2-coordinator", route = Routes.board(DEVBOX), setup = { v2() }, actions = { tapText("HERDR-ANDROID · 2") }),

    // A chat floating over other apps as a bubble: offered in the agent's menu and the Coordinator's bar, Android's
    // bubbles not yet allowed for the app, and the bubble's own window (an agent's chat, the Coordinator), which
    // has no back arrow and opens the chat in the app instead.
    Scene("chat-float", route = Routes.agent(DEVBOX, APP_AGENT), actions = { tapLabel("More") }),

    // A jump to a failure the Edits filter hides: the filter gives way and the failed step shows.
    Scene("activity-agent", route = Routes.activity(DEVBOX, pane = APP_AGENT), heightDp = 1600),
    Scene("bubble", bubble = BubbleChat.Agent(DEVBOX, APP_AGENT), widthDp = 392, heightDp = 640),
    Scene("bubble-menu", bubble = BubbleChat.Agent(DEVBOX, APP_AGENT), widthDp = 392, heightDp = 640, actions = { tapLabel("More") }),
    Scene("bubble-coordinator", bubble = BubbleChat.Coordinator(DEVBOX), widthDp = 392, heightDp = 640, setup = { shepherdr() }),

    // The brief: what happened overnight across the machines (devbox's and gpu-box's herdr-push journals; ci-runner
    // compared with what the app showed at 23:10; mac-mini refuses the key), taller to show all of it. Then read
    // aloud, on its fourth sentence (the review that failed, marked in the list), and what devbox shows without a
    // journal. When it comes: two times on weekdays, read aloud in the car; adding a time.
    Scene("brief", route = Routes.BRIEF, heightDp = 2100, setup = { brief = Demo.brief() }, actions = { settle() }),
    Scene("brief-v2", route = Routes.BRIEF, heightDp = 2100, setup = { brief = Demo.brief(); v2() }, actions = { settle() }),
    Scene(
        "brief-reading", route = Routes.BRIEF, setup = { brief = Demo.brief() },
        actions = { ctx ->
            settle()
            tapText("Read aloud")
            repeat(2) { ctx.container.briefWork.voice.next() }
            settle()
        },
    ),
    Scene(
        "brief-no-journal", route = Routes.BRIEF, heightDp = 1500,
        setup = { brief = Demo.brief(); host(DEVBOX).journal = null; host(GPU).journal = null },
        actions = { settle() },
    ),
    Scene("brief-settings", route = Routes.BRIEF_SETTINGS, heightDp = 1100, setup = { brief = Demo.brief() }),
    Scene("brief-add-time", route = Routes.BRIEF_SETTINGS, setup = { brief = Demo.brief() }, actions = { tapText("Add a time") }),

    // The code in a checkout, reached from a task's view or an agent's or a workspace's menu: task AND-9's worktree:
    // its files, Go to file, a file, two lines chosen and a comment on them, the view's settings, wrapped at a larger
    // size, a Markdown file as it reads; its changes against main and one file's diff; its history and a commit; a
    // search across it; the comments kept to send together.
    Scene("code-files", route = Routes.codeAt(TASK_CODE)),
    Scene("code-goto", route = Routes.codeAt(TASK_CODE), actions = { type("mcard") }),
    Scene("code-file", route = Routes.codeFile(TASK_CODE, CARD)),
    Scene("code-lines", route = Routes.codeFile(TASK_CODE, CARD, line = 33), actions = { tapLabel("line 37"); tapLabel("line 38") }),
    Scene(
        "code-comment", route = Routes.codeFile(TASK_CODE, CARD, line = 33),
        actions = {
            tapLabel("line 37"); tapLabel("line 38"); tapText("Comment")
            type("Show this in the notice colour when it is older than an hour, so a stale card stands out.")
        },
    ),
    Scene("code-view", route = Routes.codeFile(TASK_CODE, CARD), actions = { tapLabel("Text size and wrapping") }),
    Scene("code-wrapped", route = Routes.codeFile(TASK_CODE, CARD), setup = { codeView = CodeView(wrap = true, textSize = 15) }),
    Scene("code-markdown", route = Routes.codeFile(TASK_CODE, "README.md")),
    Scene("code-changes", route = Routes.codeAt(TASK_CODE, CheckoutTab.Changes)),
    Scene("code-diff", route = Routes.codeDiff(TASK_CODE, DiffSource.base("main"), CARD)),
    Scene("code-history", route = Routes.codeAt(TASK_CODE, CheckoutTab.History)),
    Scene("code-commit", route = Routes.codeCommit(TASK_CODE, FakeCode.commit(1))),
    Scene("code-search", route = Routes.codeSearch(TASK_CODE, "answered")),
    Scene("code-notes", route = Routes.codeNotes(TASK_CODE), heightDp = 1000, setup = { notes = Demo.notes() }),

    // A pane as a live terminal.
    Scene("terminal", route = Routes.terminal(DEVBOX, APP_SHELL)),
    // A swipe back: the phone's own copy of the history, the computer's window left where it is.
    Scene("terminal-history", route = Routes.terminal(DEVBOX, APP_SHELL), actions = { scrollBack() }),
    // Its ⋯: find, select, copy, the key bar, keep the screen on, reconnect.
    Scene("terminal-menu", route = Routes.terminal(DEVBOX, APP_SHELL), actions = { tapLabel("More") }),
    // sudo asks: a masked field over the keys; its own text size.
    Scene("terminal-password", route = Routes.terminal(DEVBOX, APP_SHELL), setup = { host(DEVBOX).screens += APP_SHELL to Demo.shellAsking() }),
    Scene("terminal-large-text", route = Routes.terminal(DEVBOX, APP_SHELL), setup = { textSizes = com.makefunstuff.herdr.data.TextSizeStore.Sizes(terminal = 15f) }),
    // The key bar: Ctrl kept on (tapped twice) and Alt for the next key; its page of F-keys; its editor
    // (tall, to show what can be added); a bar of one's own, with Keep screen on.
    Scene("terminal-sticky", route = Routes.terminal(DEVBOX, APP_SHELL), actions = { tapLabel("Ctrl"); tapLabel("Ctrl"); tapLabel("Alt") }),
    Scene("terminal-fkeys", route = Routes.terminal(DEVBOX, APP_SHELL), actions = { tapLabel("F-keys") }),
    Scene("terminal-keys", route = Routes.terminal(DEVBOX, APP_SHELL), heightDp = 2000, actions = { tapLabel("Edit the key bar") }),
    Scene(
        "terminal-own-keys", route = Routes.terminal(DEVBOX, APP_SHELL),
        setup = { keyBar = com.makefunstuff.herdr.data.KeyBarStore.Saved(keys = listOf("@ctrl", "@alt", "Esc", "Tab", "S-Tab", "Enter", "C-d", "C-z", "C-l", "M-b", "M-f", "@keyboard", "Home", "End", "Up", "Down", "Left", "Right", "/", "-"), keepScreenOn = true) },
    ),
    // Selected text: Copy, Quote in chat and Send to Coordinator; quoted, it is in the herdr-android agent's
    // message box (the workspace's one agent); with two agents there, the app asks which; sent to the Coordinator.
    Scene("terminal-select", route = Routes.terminal(DEVBOX, APP_SHELL), setup = { selectTests() }, actions = { tapLabel("More"); tapText("Select text") }),
    Scene("terminal-quote", route = Routes.terminal(DEVBOX, APP_SHELL), setup = { selectTests() }, actions = { tapLabel("More"); tapText("Select text"); tapText("Quote in chat") }),
    Scene(
        "terminal-quote-pick", route = Routes.terminal(DEVBOX, APP_SHELL),
        setup = { selectTests(); host(DEVBOX).snapshot = Demo.devbox(reviewer = true) },
        actions = { tapLabel("More"); tapText("Select text"); tapText("Quote in chat") },
    ),
    Scene("terminal-to-coordinator", route = Routes.terminal(DEVBOX, APP_SHELL), setup = { selectTests(); shepherdr() }, actions = { tapLabel("More"); tapText("Select text"); tapText("Send to the Assistant") }),

    // A window 840 dp wide or more (a tablet, an open foldable, a large window): the spaces stay on the left, as 3.1's
    // sidebar, the app beside them. A landscape tablet on Tasks, then with a chat open from it; an open foldable with
    // a terminal; the Chat tab beside the spaces.
    Scene("wide-empty", widthDp = 1280, heightDp = 800, setup = { home() }),
    Scene("wide", widthDp = 1280, heightDp = 800, setup = { home() }, actions = { tapText("Speed up /jobs") }),
    Scene("wide-terminal", route = Routes.terminal(DEVBOX, APP_SHELL), widthDp = 840, heightDp = 940),
    Scene("wide-machines", widthDp = 1280, heightDp = 800, setup = { home() }, actions = { tapLabel("Chat") }),
    // Rotating across the line keeps what is open as it was: a chat with its key row shown, turned from a phone
    // to a tablet's width and from the tablet back to the phone.
    Scene(
        "rotate-wide", route = Routes.agent(DEVBOX, APP_AGENT), resize = 1280 to 800,
        actions = { tapLabel("More"); tapText("Show keys") },
    ),
    Scene(
        "rotate-narrow", widthDp = 1280, heightDp = 800, resize = 412 to 892,
        actions = { tapText("Speed up /jobs"); tapLabel("More"); tapText("Show keys") },
    ),

    // Settings (at the foot of the spaces drawer) and the SSH key's dialogs.
    Scene("settings", actions = { tapLabel("Spaces"); tapText("SETTINGS") }),
    // Settings › Autopilot on devbox (Settings lists a row for each machine on Shepherdr 3): its heartbeat, schedule and
    // events; a schedule of its own words; one herdr-task can't read, refused in its words.
    Scene("settings-autopilot", route = Routes.SETTINGS, heightDp = 1900, setup = { shepherdr() }),
    // Changing the app from the phone: the ⋯ menu's row, the sheet a long press on a title opens, the change's chat
    // with its shots and Accept, the update ready in it, and Settings › Change the app (all there, no SDK, the update).
    Scene("change-action", route = Routes.machine(DEVBOX), setup = { appChange() }, actions = { settle(); tapLabel("More") }),
    Scene("change-sheet", route = Routes.board(DEVBOX), setup = { appChange() }, actions = {
        settle(); longPressText("Tasks"); settle(); typeInto("What should change?", "Show each task's age on its row, after its status"); settle()
    }),
    Scene("change-chat", route = Routes.coordinator(DEVBOX, DemoV2.APP), heightDp = 1600, setup = { appChange() }),
    Scene("change-proof", route = Routes.artifact(DEVBOX, "pf31"), setup = { appChange() }),
    Scene("change-accept", route = Routes.coordinator(DEVBOX, DemoV2.APP), heightDp = 1600, setup = { appChange() }, actions = { settle(); tapExact("Accept") }),
    Scene("change-update", route = Routes.coordinator(DEVBOX, DemoV2.APP), setup = { appChange(update = DemoChange.UPDATE) }),
    Scene("settings-change", route = Routes.CHANGE_APP, heightDp = 1500, setup = { appChange() }),
    Scene("settings-change-nosdk", route = Routes.CHANGE_APP, heightDp = 1500, setup = { appChange(FakeAppBuild.CHECK_NO_SDK) }),
    Scene("settings-change-update", route = Routes.CHANGE_APP, heightDp = 1500, setup = { appChange(update = DemoChange.UPDATE) }),
    Scene("autopilot", route = Routes.autopilot(DEVBOX), heightDp = 1200, setup = { shepherdr() }),
    Scene("autopilot-refused", route = Routes.autopilot(DEVBOX), heightDp = 1200, setup = { shepherdr() }, actions = {
        settle(); tapExact(com.makefunstuff.herdr.core.shepherdr.Autopilot.CUSTOM)
        val box = onAllNodes(androidx.compose.ui.test.hasSetTextAction(), useUnmergedTree = true)
        box.onLast().performTextInput("every fortnight")
        box.onLast().performImeAction()
        settle()
    }),
    // Text size: the chats' and the terminal's, each its own.
    Scene("settings-text", route = Routes.SETTINGS, heightDp = 1300, setup = { textSizes = com.makefunstuff.herdr.data.TextSizeStore.Sizes(chat = 1.3f, terminal = 14f) }),
    Scene("settings-voice", route = Routes.SETTINGS, heightDp = 1700, setup = {
        voiceSettings = com.makefunstuff.herdr.data.VoiceSettingsStore.Settings(server = "http://devbox:18101", key = "sk-demo-key", speed = 1.2f)
    }, actions = { tapText("Test") }),
    Scene("settings-new-key", route = Routes.SETTINGS, actions = { tapText("New key") }),
    Scene("key-setup", key = IdentityStore.State.Missing),
    // The fingerprint before what can't be undone, and the app lock: its settings (one action turned off, the lock
    // on), the same on a phone with no screen lock, the app locked, and a merge confirmed in the app without a screen lock.
    Scene(
        "settings-guard", route = Routes.GUARD,
        setup = { guard = com.makefunstuff.herdr.data.GuardState(off = setOf("stop"), appLock = true, lockAfter = 5) },
    ),
    Scene("settings-guard-nolock", route = Routes.GUARD, setup = { screenLock = false }),
    Scene("app-lock", setup = { guard = com.makefunstuff.herdr.data.GuardState(appLock = true); unlockAnswer = null }),

    // Notifications while the app is closed: each machine's herdr-push, and before a push app is there.
    // A taller window shows the whole section under the SSH key (scrolling never settles in the desktop test harness).
    Scene("settings-notifications", route = Routes.SETTINGS, heightDp = 1580, setup = { push = Demo.push() }),
    // What reaches the phone: a switch per kind (Finished off), silent while you are at a machine (devbox, now), the spaces with their own Notify.
    Scene("settings-rules", heightDp = 1100, setup = { push = Demo.notify() }, actions = { ctx ->
        ctx.container.quietWork.presence.record(DEVBOX, "devbox", com.makefunstuff.herdr.core.push.Presence(typedAgo = 20), System.currentTimeMillis())
        runOnUiThread { ctx.nav.navigate(Routes.RULES) }
        settle()
    }),
    Scene("settings-rules-empty", route = Routes.RULES, setup = { push = Demo.push() }),
    // Live Updates turned off for the app in Android's settings: they show as ordinary notifications.
    Scene("settings-live-off", route = Routes.SETTINGS, heightDp = 1580, setup = { push = Demo.push(); liveAllowed = false }),
    // Quiet hours: off; then on, the phone in Do Not Disturb with two finished agents held, and picking a time.
    Scene("quiet-hours-off", route = Routes.QUIET, setup = { push = Demo.push() }),
    Scene(
        "quiet-hours", route = Routes.QUIET, heightDp = 1100,
        setup = { push = Demo.push().copy(quiet = Demo.quiet()); quietMode = PhoneQuiet.DoNotDisturb; held = Demo.held() },
    ),
    Scene(
        "quiet-hours-time", route = Routes.QUIET,
        setup = { push = Demo.push().copy(quiet = Demo.quiet()); quietMode = PhoneQuiet.DoNotDisturb },
        actions = { tapText("From") },
    ),
    Scene(
        "settings-no-push-app", route = Routes.SETTINGS, heightDp = 1320,
        setup = { push = Demo.push().copy(endpoint = null, distributor = null); pushApps = emptyList(); notificationsAllowed = false },
    ),
)
