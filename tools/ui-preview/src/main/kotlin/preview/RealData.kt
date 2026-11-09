// Scenes with the shape of the person's own work, not a tidy demo: eleven spaces in one drawer, thirteen
// tasks at different lengths and ages, one Assistant asking for a secret, a task waiting to be accepted,
// an Assistant chat with long paragraphs and a task's news in it, and a shelf of artifacts with long
// titles. Read-only snapshots of hc and herdr-task, with the project names changed.
package preview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The person's own work, as the app sees it, made up but shaped like the real thing. */
object Real {
    private const val PC = "pc"          // the machine the service runs on; the only one in the phone's list

    const val VOICE = "sp-voice"
    const val LFM = "sp-lfm"
    const val PAIR = "sp-pairing"
    const val HOME = "sp-home"
    const val ANDROID = "sp-herdr-android"
    const val SEMANTIC = "sp-semantic-grep"
    const val SLOP = "sp-slopbot"
    const val HW = "sp-hardware-mmwave"
    const val DESKTOP = "sp-personal-herdr-desktop"
    const val WORK = "sp-work"

    /** The eleven spaces of the drawer, in the order the person keeps them. */
    private val PLACES = listOf(
        Triple(VOICE, "voice-computer", "~/Work/voice-computer"),
        Triple(LFM, "lfm-pixel", "~/Work/lfm-pixel"),
        Triple(PAIR, "pairing-buddy", "~/Work/pairing-buddy"),
        Triple("sp-homelab", "homelab", "~/Work/homelab"),
        Triple(ANDROID, "herdr-android", "~/Work/herdr-android"),
        Triple(SEMANTIC, "semantic-grep.nvim", "~/Work/semantic-grep.nvim"),
        Triple(SLOP, "slopbot", "~/Work/slopbot"),
        Triple(HW, "hardware-mmWave-lab", "~/Work/hardware-mmWave-lab"),
        Triple(HOME, "home", "~"),
        Triple(DESKTOP, "personal-herdr-desktop", "~/Work/personal-herdr-desktop"),
        Triple(WORK, "work", "~/Work/work"),
    )

    private fun place(id: String, name: String, folder: String, status: String = "idle") = obj(
        "id" to id, "name" to name, "machine" to "pc", "level" to 0, "status" to status, "mode" to "regular",
        "chat" to "p:$id", "header" to "Assistant · Claude Code", "where" to folder,
        "store" to obj("folder" to folder.replace("~", "/home/me"), "key" to "|" + folder.replace("~", "/home/me"), "name" to name),
        "assistantTab" to obj("name" to "Assistant", "named" to true, "state" to if (status == "need") "blocked" else if (status == "work") "working" else "done", "live" to true, "cli" to "claude"),
    )

    private fun task(
        id: String,
        name: String,
        status: String,
        place: String,
        sub: String,
        age: String,
        agent: String = "claude",
        branch: String? = null,
        pr: Int? = null,
        said: String? = null,
        facts: String? = null,
        list: String = "herdr-android",
    ) = obj(
        "id" to id, "name" to name, "status" to status, "place" to place, "sub" to sub, "age" to age,
        "branch" to branch, "listName" to list,
        "agent" to obj("chat" to "$id/worker", "role" to "worker", "cli" to agent, "status" to status, "age" to age, "running" to true),
        "pr" to pr?.let { obj("number" to it, "url" to "https://github.com/example/$list/pull/$it") },
        "said" to said, "facts" to facts,
        "report" to obj("msg" to sub, "progress" to if (status == "ready") 100 else 45),
    )

    private fun question(id: String, chat: String, place: String, task: String, title: String, choices: List<String>, other: Boolean = false) = obj(
        "id" to id, "chat" to chat, "place" to place, "task" to task, "at" to Demo.NOW - 12 * 60_000,
        "question" to obj(
            "id" to id, "title" to title, "kind" to "Form", "to" to chat, "other" to other.takeIf { it },
            "choices" to choices.map { obj("label" to it) },
        ),
    )

    /** The two tasks asking a question, the task ready to accept, the two at work, and the rest in their lists. */
    private val TASKS = listOf(
        task("HERD-24", "Pull request review from the phone", "need", ANDROID, "Asks which checks to show", "12m", branch = "work/24-pr-review"),
        task("SEMA-4", "Context for small pieces", "need", SEMANTIC, "Asks how much context to send", "1h", agent = "codex", branch = "work/4-context", list = "semantic-grep.nvim"),
        task("HMWL-1", "Runbook: live OOB to dev broker over VPN", "ready", HW, "Ready 43m ago", "43m", branch = "work/1-oob-runbook", pr = 18,
            said = "The runbook now walks from the Lab VLAN to the dev broker over the VPN, with the commands and what each step should print. Every step was run on the bench.",
            facts = "PR #18 · commit 4c81e2a · checks passed (3/3) · review: 2 rounds, 1 major fixed", list = "hardware-mmWave-lab"),
        task("HERD-31", "UX rework against the 3.1 phone layout and real data", "work", ANDROID, "Reading the repo and the 3.1 reference", "3h", branch = "task/31-ux-rework"),
        task("PHD-1", "Sidebar: started tasks as sub-workspaces under their space", "work", DESKTOP, "Reading the repo and the bug report", "2h", agent = "codex", branch = "work/1-sidebar", list = "personal-herdr-desktop"),
        task("HERD-32", "Docs viewer for herdr.doc", "queued", ANDROID, "After HERD-31", "1h"),
        task("HERD-25", "Live browser of a task", "todo", ANDROID, "Waits for a go", "6h"),
        task("HERD-26", "Keep the chat's place when the keyboard opens", "todo", ANDROID, "Waits for a go", "1d"),
        task("HOME-17", "Find something interesting", "todo", HOME, "Waits for a go", "2d", agent = "codex", list = "homelab"),
        task("WORK-3", "Sort out the invoicing script before Friday", "todo", WORK, "Waits for a go", "2d", list = "work"),
        task("PAIR-18", "Cut a release with the new pairing flow", "todo", PAIR, "Waits for a go", "3d", list = "pairing-buddy"),
        task("HERD-30", "Chats show the right conversation and their context", "done", ANDROID, "Done 16m ago", "16m", branch = "task/30-chat-context"),
        task("HERD-29", "Terminal view is reachable again", "done", ANDROID, "Done 55m ago", "55m"),
        task("HERD-28", "Fix main's red render check: coordinator-v2-agent", "done", ANDROID, "Done 2h ago", "2h"),
        task("HERD-27", "Change the app from the phone, built on a host", "done", ANDROID, "Done 13h ago", "13h"),
    )

    private val NEEDS = listOf(
        question("q-herd24", "HERD-24/worker", ANDROID, "HERD-24", "Which checks should a pull request show on the phone?", listOf("All of them", "Only the failing ones", "One summary line"), other = true),
        question("q-sema4", "SEMA-4/worker", SEMANTIC, "SEMA-4", "How much context should a small piece get?", listOf("The open file and its tests", "The whole folder", "Ask me each time")),
        obj("id" to "secret:home:MQTT_PASSWORD", "chat" to "p:$HOME", "place" to HOME, "at" to Demo.NOW - 6 * 60_000,
            "secret" to obj("id" to "secret:home:MQTT_PASSWORD", "name" to "MQTT_PASSWORD",
                "why" to "To join the dev broker from the bench rig",
                "levels" to listOf(obj("id" to "p::$HOME", "label" to "This space"), obj("id" to "m:", "label" to "This computer"), obj("id" to "all", "label" to "Everywhere")))),
    )

    private val ARTIFACTS = listOf(
        obj("id" to "rn-hw", "title" to "Runbook: live OOB to dev broker over VPN · Proof", "kind" to "recipe", "place" to HW,
            "store" to "|/home/me/Work/hardware-mmWave-lab", "folder" to "/home/me/Work/hardware-mmWave-lab", "folderName" to "hardware-mmWave-lab",
            "v" to 1, "at" to Demo.NOW - 7 * 60_000, "when" to "7m", "by" to "hardware-mmWave-lab Assistant", "keeper" to "hardware-mmWave-lab Assistant",
            "versions" to listOf(obj("n" to 1, "when" to "7m", "by" to "hardware-mmWave-lab Assistant", "html" to 1))),
        obj("id" to "rn-chat", "title" to "Chats show the right conversation and their context · Proof", "kind" to "recipe", "place" to ANDROID,
            "store" to "|/home/me/Work/herdr-android", "folder" to "/home/me/Work/herdr-android", "folderName" to "herdr-android",
            "v" to 2, "at" to Demo.NOW - 24 * 60_000, "when" to "24m", "by" to "herdr-android Assistant", "keeper" to "herdr-android Assistant",
            "versions" to listOf(obj("n" to 2, "when" to "24m", "by" to "herdr-android Assistant", "html" to 2), obj("n" to 1, "when" to "1h", "by" to "herdr-android Assistant", "html" to 1))),
        obj("id" to "rn-terminal", "title" to "Terminal view is reachable again · Proof", "kind" to "recipe", "place" to ANDROID,
            "store" to "|/home/me/Work/herdr-android", "folder" to "/home/me/Work/herdr-android", "folderName" to "herdr-android",
            "v" to 2, "at" to Demo.NOW - 55 * 60_000, "when" to "1h", "by" to "HERD-29 agent", "keeper" to "HERD-29 agent",
            "versions" to listOf(obj("n" to 2, "when" to "1h", "by" to "HERD-29 agent", "html" to 2))),
        obj("id" to "rn-render", "title" to "HERD-28: Fix main's red render check (coordinator-v2-agent) · Proof", "kind" to "recipe", "place" to ANDROID,
            "store" to "|/home/me/Work/herdr-android", "folder" to "/home/me/Work/herdr-android", "folderName" to "herdr-android",
            "v" to 1, "at" to Demo.NOW - 2 * 3_600_000, "when" to "2h", "by" to "HERD-28 agent", "keeper" to "HERD-28 agent",
            "versions" to listOf(obj("n" to 1, "when" to "2h", "by" to "HERD-28 agent", "html" to 1))),
        obj("id" to "rn-app", "title" to "Change the app from the phone, built on a host · Proof", "kind" to "recipe", "place" to ANDROID,
            "store" to "|/home/me/Work/herdr-android", "folder" to "/home/me/Work/herdr-android", "folderName" to "herdr-android",
            "v" to 2, "at" to Demo.NOW - 13 * 3_600_000, "when" to "13h", "by" to "HERD-27 agent", "keeper" to "HERD-27 agent",
            "versions" to listOf(obj("n" to 2, "when" to "13h", "by" to "HERD-27 agent", "html" to 2))),
        obj("id" to "rn-needs", "title" to "Needs you widget with buttons · Proof", "kind" to "recipe", "place" to ANDROID,
            "store" to "|/home/me/Work/herdr-android", "folder" to "/home/me/Work/herdr-android", "folderName" to "herdr-android",
            "v" to 1, "at" to Demo.NOW - 14 * 3_600_000, "when" to "14h", "by" to "HERD-23 agent", "keeper" to "HERD-23 agent",
            "versions" to listOf(obj("n" to 1, "when" to "14h", "by" to "HERD-23 agent", "html" to 1))),
        obj("id" to "rn-alerts", "title" to "Task alerts with Accept and answer buttons · Proof", "kind" to "recipe", "place" to ANDROID,
            "store" to "|/home/me/Work/herdr-android", "folder" to "/home/me/Work/herdr-android", "folderName" to "herdr-android",
            "v" to 1, "at" to Demo.NOW - 14 * 3_600_000, "when" to "14h", "by" to "herdr-android Assistant", "keeper" to "herdr-android Assistant",
            "versions" to listOf(obj("n" to 1, "when" to "14h", "by" to "herdr-android Assistant", "html" to 1))),
    )

    private val SNAPSHOT = obj(
        "name" to "Shepherdr", "host" to PC, "user" to "Me", "terminalTransport" to "core",
        "machines" to listOf(obj("id" to "m:pc", "machine" to PC, "name" to "This computer", "ownName" to "This computer", "status" to "idle", "here" to true)),
        "places" to PLACES.map { (id, name, folder) ->
            place(id, name, folder, when (id) {
                ANDROID -> "need"; SEMANTIC -> "need"; HOME -> "need"; HW -> "ready"; DESKTOP -> "work"; else -> "idle"
            })
        },
        "tasks" to TASKS,
        "needs" to NEEDS,
        "asks" to emptyMap<String, String>(),
        "artifacts" to ARTIFACTS,
        "last" to emptyMap<String, String>(), "read" to emptyMap<String, String>(),
    )

    /** The herdr-android Assistant's chat: long paragraphs, a plan, a task's news, and a reply in your own words. */
    private val ANDROID_CHAT = obj(
        "key" to "p:$ANDROID", "cli" to "claude", "working" to false, "top" to true,
        "agent" to obj("kind" to "claude", "words" to "Opus 5.5 · High", "modelLabel" to "Opus 5.5"),
        "messages" to listOf(
            obj("id" to "r1", "who" to "you", "at" to 1, "text" to "The app still feels worse than 3.1 on the phone: the header cuts the space name off, the task screen says Ready in three places, and I have to go through a menu to reach a terminal. Can you take a pass against the real 3.1 at phone size?"),
            obj("id" to "r2", "who" to "agent", "at" to 2, "text" to "I looked at the 3.1 web app on this machine at **390×844**, in dark and light, and at the app's own screens with the demo data. The three you named are real, and there are four more:\n\n- The top bar carries up to five actions, so the title gets a third of the width and truncates.\n- The Tasks list has no filter field, and every space's tasks sit in one long list: with eleven spaces it is a scroll to find one.\n- Settings opens on a 300 dp SSH key card; the list of sections is below the fold.\n- The artifacts list has no filter, and its rows say less than 3.1's.\n\nI'll fix those first, then look at the composer and the empty states."),
            obj("id" to "tn:HMWL-1:ready:3", "who" to "agent", "at" to 3,
                "news" to obj("task" to "HMWL-1", "ref" to "HMWL-1", "mark" to "ready", "what" to "is ready", "text" to "Runbook: live OOB to dev broker over VPN", "accept" to true)),
            obj("id" to "r4", "who" to "you", "at" to 4, "text" to "Leave the workbench look alone: the palette, the frames and the type stay. Follow 3.1 where it is better at being a phone, and keep the terminal, chat context and reactions work from the last three tasks."),
            obj("id" to "r5", "who" to "agent", "at" to 5, "text" to "Understood. My plan:\n\n1. One bar, one menu: the space or task name on the title line, who and where under it, Autopilot and at most two icons beside it.\n2. Tasks: a filter field at the top, then 3.1's groups (Needs you, Ready, Working, To do, Done) with counts, and rows compact enough to see a screenful.\n3. A task's Terminal as a tab, not a menu row.\n4. Settings as a list of sections, one tap each, with the key card folded away at the bottom.\n\nI'll render the real-data scenes before and after, and put both next to the 3.1 shots."),
            obj("id" to "r6", "who" to "you", "at" to 6, "text" to "Good. Use HERD-31's own worktree and keep the commits small."),
        ),
    )

    private val HOME_CHAT = obj(
        "key" to "p:$HOME", "cli" to "claude", "working" to false,
        "messages" to listOf(
            obj("id" to "h1", "who" to "agent", "at" to 1, "text" to "Morning. Nothing on this computer needs you from here: herdr-android has one task to accept and two at work, and the bench rig is waiting on the secret for the dev broker."),
            obj("id" to "h2", "who" to "you", "at" to 2, "text" to "Is the nightly backup still running?"),
            obj("id" to "h3", "who" to "agent", "at" to 3, "text" to "Yes, it ran at 02:00 and finished in 14 minutes."),
        ),
    )

    private val OTHER_CHATS = (PLACES.map { it.first } - setOf(ANDROID, HOME)).associate { id ->
        "p:$id" to obj("key" to "p:$id", "cli" to "claude", "working" to false, "messages" to JsonArray(emptyList()))
    }

    fun board(): FakeShepherdr {
        val chats = linkedMapOf("p:$ANDROID" to ANDROID_CHAT, "p:$HOME" to HOME_CHAT) + OTHER_CHATS
        return FakeShepherdr(snapshot = SNAPSHOT, chats = chats)
    }
}
