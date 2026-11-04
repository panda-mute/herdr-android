// herdr-core's demo data: the terminals the Herdr desktop app keeps in herdr-core on devbox (next to
// classic herdr) and on mac-mini (the desktop app alone), as hc lists them.
package preview

import kotlinx.serialization.json.JsonObject
import preview.Demo.NOW

object DemoCore {
    /** devbox's Claude in the desktop app, asking to run a migration. */
    const val CLAUDE = "hc:t1"
    const val SHELL = "hc:t2"
    const val CODEX = "hc:t3"

    private const val MIN = 60_000L
    private const val HOME = "/home/demo"

    private fun status(state: String, app: String, kind: String? = null, msg: String = "", title: String = "") =
        obj("state" to state, "app" to app, "kind" to kind, "msg" to msg.ifEmpty { null }, "title" to title.ifEmpty { null })

    private val PROMPT = "\u001b[1;32mdemo@devbox\u001b[0m:\u001b[1;34m~/src/herdr-android\u001b[0m$ "

    /** devbox's desktop app: herdr-android (review: Claude and a shell; docs: Codex), notes (pi), and a dev server `hc run` started. */
    fun devbox() = FakeCore(
        terminals = listOf(
            FakeCore.terminal(
                "t1", listOf("claude"), "$HOME/src/herdr-android", NOW - 50 * MIN, space = "sp1" to "herdr-android", tab = "tb1" to "review",
                status = status("blocked", "claude-code", kind = "permission", msg = "Run npm run db:migrate?"), statusMs = NOW - 2 * MIN,
                title = "Jobs migration", turn = 3,
            ),
            FakeCore.terminal("t2", listOf("zsh"), "$HOME/src/herdr-android", NOW - 49 * MIN, space = "sp1" to "herdr-android", tab = "tb1" to "review"),
            FakeCore.terminal(
                "t3", listOf("codex"), "$HOME/src/herdr-android", NOW - 30 * MIN, space = "sp1" to "herdr-android", tab = "tb2" to "docs",
                status = status("working", "codex", title = "Updating the README"), statusMs = NOW - 4 * MIN, turn = 1,
            ),
            FakeCore.terminal(
                "t4", listOf("pi"), "$HOME/notes", NOW - 20 * MIN, space = "sp2" to "notes", tab = "tb3" to "main",
                status = status("done", "pi", msg = "Summed up this week's notes"), statusMs = NOW - 6 * MIN, turn = 2,
            ),
            FakeCore.terminal("t5", listOf("npm", "run", "dev"), "$HOME/src/website", NOW - 10 * MIN, name = "website-dev", foreground = "node"),
        ),
        screens = mapOf(
            "t1" to Demo.resource("screen-claude-api.txt"),
            "t2" to PROMPT + "git status --short\n M app/src/main/kotlin/demo/herdr/data/Jobs.kt\n?? migrations/0042_jobs_created_at.sql\n" + PROMPT,
            "t3" to Demo.resource("screen-codex.txt"),
            "t4" to "Summed up this week's notes in notes/2026-w42.md.\n",
            "t5" to "\u001b[32m  VITE v6.2.0\u001b[0m  ready in 412 ms\n\n  ➜  Local:   http://localhost:5173/\n",
        ),
        name = "devbox",
    )

    /** mac-mini with the desktop app and no classic herdr: the website's Claude at work, Codex done. */
    fun mini() = FakeCore(
        terminals = listOf(
            FakeCore.terminal(
                "t1", listOf("claude"), "/Users/demo/src/website", NOW - 15 * MIN, space = "sp1" to "website", tab = "tb1" to "main",
                status = status("working", "claude-code", title = "Checking every link"), statusMs = NOW - 3 * MIN, turn = 1,
            ),
            FakeCore.terminal(
                "t2", listOf("codex"), "/Users/demo/src/website", NOW - 40 * MIN, space = "sp1" to "website", tab = "tb2" to "blog",
                status = status("done", "codex", title = "Draft of the release post"), statusMs = NOW - 9 * MIN, turn = 1,
            ),
        ),
        screens = mapOf("t1" to Demo.resource("screen-claude-app.txt"), "t2" to Demo.resource("screen-codex.txt")),
        name = "mac-mini",
    )

    /**
     * devbox's herdr-core, where Shepherdr 3 runs its agents (DemoV2.board()'s): each space's Assistant and the tasks'
     * workers, labelled as Shepherdr 3.1 labels them (`service/actions.ts`, herdr-task's), named as herdr-task names
     * a worker (`and-21-key-bar-layouts`).
     */
    fun shepherdr(): FakeCore {
        fun agent(address: String, space: String, task: String?, status: JsonObject, statusMs: Long, title: String, parent: String? = null, argv: String = "claude") =
            FakeCore.terminal(
                address, listOf(argv), if (task == null) "$HOME/src/$space" else "$HOME/.herdr/worktrees/$space/task-${task.substringAfter('-')}", NOW - 60 * MIN,
                name = task?.let { "${it.lowercase()}-" + title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') },
                status = status, statusMs = statusMs, title = title, turn = 1, parent = parent,
                labels = buildMap {
                    if (task == null) {
                        put("herdr.role", "coordinator")
                        put("herdr.coordinator.space", "sp-$space")
                        put("herdr.coordinator.tab", "tb-$space")
                        put("herdr.space", "sp-$space")
                        put("herdr.tab", "tb-$space")
                    } else {
                        put("herdr.agent", argv)
                        put("herdr.task", "home-demo-src-$space#${task.substringAfter('-')}")
                        put("herdr.task.space", "sp-$space")
                        put("herdr.coordinator", "tb-$space")
                    }
                    put("where", "$space / Assistant")
                },
            )
        return FakeCore(
            terminals = listOf(
                agent("t1", "herdr-android", null, status("done", "claude-code", msg = "Queued AND-22 after AND-19"), NOW - 12 * MIN, "herdr-android"),
                agent("t2", "herdr-android", "AND-21", status("blocked", "claude-code", kind = "question", msg = "Which key bar layout?"), NOW - 3 * MIN, "Key bar layouts", parent = "t1"),
                agent("t3", "herdr-android", "AND-24", status("blocked", "codex", kind = "question"), NOW - 5 * MIN, "Alert rules", parent = "t1", argv = "codex"),
                agent("t4", "herdr-android", "AND-19", status("working", "claude-code", msg = "Adding an index on jobs.created_at"), NOW - 1 * MIN, "Speed up /jobs on large tables", parent = "t1"),
                agent("t5", "herdr-android", "AND-20", status("done", "claude-code", msg = "Ready: PR #212"), NOW - 20 * MIN, "Quiet hours", parent = "t1"),
                agent("t6", "website", null, status("done", "claude-code"), NOW - 30 * MIN, "website"),
                agent("t7", "website", "AND-18", status("working", "claude-code", msg = "Merging"), NOW - 2 * MIN, "Blog layout", parent = "t6"),
            ),
            name = "devbox",
        )
    }
}

/** devbox runs Shepherdr 3: its board, and its agents in herdr-core beside classic herdr; no desktop app. */
fun SceneData.shepherdr3() {
    host(Demo.DEVBOX).shepherdr = DemoV2.board()
    host(Demo.DEVBOX).core = DemoCore.shepherdr()
}

/** devbox runs the desktop app's herdr-core next to classic herdr; mac-mini runs only the desktop app. */
fun SceneData.desktopApp() {
    host(Demo.DEVBOX).core = DemoCore.devbox()
    host(Demo.MINI).apply {
        refuse = null
        classic = false
        core = DemoCore.mini()
    }
}
