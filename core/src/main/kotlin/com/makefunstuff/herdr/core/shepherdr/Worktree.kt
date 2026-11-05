package com.makefunstuff.herdr.core.shepherdr

import kotlinx.serialization.Serializable

/** A check on a pull request, as Shepherdr sorts them (`core/worktree.js` `checkOf`): [state] `pass`, `fail`, `pending` or `skip`. */
@Serializable
data class V2PrCheck(val name: String = "", val state: String = "pending", val url: String? = null, val workflow: String? = null)

/** A task's pull request as gh tells it on the task's machine (`PullRequest` in 3.1's `web/shared/types.ts`). */
@Serializable
data class V2PullRequest(
    val number: Int = 0,
    val title: String = "",
    /** `open`, `draft`, `merged` or `closed`. */
    val state: String = "open",
    val url: String = "",
    val head: String? = null,
    val base: String? = null,
    val add: Int = 0,
    val del: Int = 0,
    val files: Int? = null,
    /** Failed first, then running, passed, skipped: what needs a look on top. */
    val checks: List<V2PrCheck> = emptyList(),
    val checksError: String? = null,
)

/** `GET /api/worktree?what=pr`'s answer: the [pr], or [why] there is none, or GitHub's [error]. */
@Serializable
data class V2PrAnswer(val pr: V2PullRequest? = null, val why: String? = null, val error: String? = null)

/** `GET /api/worktree?what=dir`'s answer, as far as the app reads it: where the task works ([root]), or why not. */
@Serializable
data class V2TaskDir(val root: String? = null, val error: String? = null)

/** Where a task works: its worktree, else its list's folder, on [machine] (null: the board's own machine). */
data class TaskFolder(val folder: String, val machine: String? = null)

/**
 * A task's Files, Changes and Pull request in 3.1's words (`VIEW_WORDS`, `PR_STATES`, `CHECK_WORDS` in its
 * `core/worktree.js`), so the phone's task view says what the window's and the web app's say.
 */
object Worktree {
    const val NO_PR = "This task has no pull request."
    const val ASKING_GITHUB = "Asking GitHub…"
    const val OPEN_ON_GITHUB = "Open on GitHub"
    const val COPY_LINK = "Copy link"
    const val CHECKS = "Checks"
    const val NO_CHECKS = "No checks on it."
    const val NO_FOLDER = "No folder for this task"

    fun state(s: String): String = when (s) {
        "draft" -> "Draft"
        "merged" -> "Merged"
        "closed" -> "Closed"
        else -> "Open"
    }

    fun check(s: String): String = when (s) {
        "pass" -> "Passed"
        "fail" -> "Failed"
        "skip" -> "Skipped"
        else -> "Running"
    }

    /** "1 failed · 2 running · 3 passed" (`checksLine`). */
    fun checksLine(checks: List<V2PrCheck>): String =
        listOf("fail" to "failed", "pending" to "running", "pass" to "passed", "skip" to "skipped")
            .map { (s, w) -> checks.count { it.state == s } to w }
            .filter { it.first > 0 }.joinToString(" · ") { "${it.first} ${it.second}" }

    /** "3 files" (`filesCount`). */
    fun filesCount(n: Int): String = "%,d %s".format(java.util.Locale.ENGLISH, n, if (n == 1) "file" else "files")

    /**
     * Where task [task] works, from its `dir` answer's [root]: the service names a folder on another machine as
     * `<machine>:<folder>` (that machine is the task id's own prefix, `vm:API-2`), and its own home as `~`.
     */
    fun folderOf(task: String, root: String): TaskFolder {
        val machine = task.substringBefore(':', "").ifEmpty { null }
        if (machine != null && root.startsWith("$machine:")) return TaskFolder(root.removePrefix("$machine:"), machine)
        return TaskFolder(root)
    }
}
