// simple-herdr-projects' store and commands on a fake machine: the app's real ShpClient and ShpBoard
// read it over the fake SSH connection, so a scene renders the board, a task's chat and Accept.
package preview

import com.makefunstuff.herdr.core.shp.ShpCommands
import com.makefunstuff.herdr.core.ssh.CommandResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One task in [FakeShp]'s store. */
class ShpCard(
    val id: String,
    var title: String,
    var status: String,
    val branch: String = "task/${id.lowercase()}",
    val worktree: String = "~/.herdr/worktrees/app/$id",
    var step: String? = null,
    var left: String? = null,
    var progress: Int? = null,
    var summary: String? = null,
    var asks: MutableList<Pair<String, String>> = mutableListOf(),
    /** Its worker's terminal transcript, for `hc read`; null where it has no live worker. */
    var transcript: String? = null,
    val brief: String = "",
    /** Its pull request's link, once it has one. */
    val pr: String? = null,
) {
    fun head(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("title", JsonPrimitive(title))
        put("status", JsonPrimitive(status))
        put("branch", JsonPrimitive(branch))
        put("worktree", JsonPrimitive(worktree))
        put("asks", JsonArray(asks.map { (askId, text) ->
            buildJsonObject {
                put("id", JsonPrimitive(askId))
                put("text", JsonPrimitive(text))
                put("time", JsonPrimitive("2026-10-17T09:50:00+00:00"))
            }
        }))
        step?.let { put("step", JsonPrimitive(it)) }
        left?.let { put("left", JsonPrimitive(it)) }
        progress?.let { put("progress", JsonPrimitive(it)) }
        summary?.let { put("summary", JsonPrimitive(it)) }
        pr?.let { put("pr", JsonPrimitive(it)) }
    }
}

/** A machine's `~/.herdr/simple`: spaces of tasks, answered as shp answers them. */
class FakeShp(spaces: Map<String, List<ShpCard>>, var refuseAccept: String? = null) {
    val spaces: MutableMap<String, List<ShpCard>> = spaces.toMutableMap()

    /** The task [id] in any space. */
    fun card(id: String): ShpCard? = spaces.values.flatten().firstOrNull { it.id == id }

    fun run(command: String, stdin: String): CommandResult? {
        // The login shell is already unwrapped (FakeMachine): the list is its own loop and one `shp list --json`.
        if (command.endsWith("shp list --json")) {
            val out = StringBuilder()
            spaces.forEach { (name, cards) -> out.append(ShpCommands.SPACE_MARK).append(name).append('\n') }
            spaces.forEach { (name, cards) -> cards.forEach { out.append(ShpCommands.TASK_MARK).append(name).append(' ').append(it.id).append('\n') } }
            out.append(ShpCommands.LIST_MARK).append('\n')
            out.append(JsonArray(spaces.values.flatten().map { it.head() }))
            return CommandResult(0, out.toString(), "")
        }
        val id = Regex("""^shp (?:show --json|accept --yes) '([^']*)'""").find(command)?.groupValues?.get(1)
        if (command.startsWith("shp show --json ") && id != null) {
            val c = card(id) ?: return CommandResult(1, "", "there is no task $id")
            return CommandResult(0, c.head().toString() + "\n@@shp-file@@\n" + file(c), "")
        }
        if (command.startsWith("shp accept --yes ") && id != null) {
            val c = card(id) ?: return CommandResult(1, "", "there is no task $id")
            refuseAccept?.let { return CommandResult(1, "", it) }
            if (c.status != "ready") return CommandResult(1, "", "$id is ${c.status}; only a ready task accepts")
            c.status = "done"
            c.transcript = null
            c.asks.clear()
            return CommandResult(0, "$id accepted\n", "")
        }
        val said = Regex("""^shp say(?: --ask '([^']*)')? -- '([^']*)' '(.*)'$""", RegexOption.DOT_MATCHES_ALL).find(command) ?: return null
        val (ask, task, text) = said.destructured
        val c = card(task) ?: return CommandResult(1, "", "there is no task $task")
        if (c.transcript == null) return CommandResult(1, "", "$task has no live worker; edit its status to todo or queued and start it")
        c.transcript = (c.transcript.orEmpty().trimEnd() + "\n\n❯ " + text + "\n\n● Noted. I'll carry on with that.\n")
        if (ask.isNotEmpty()) c.asks.removeAll { it.first == ask }
        if (c.status == "needs" && c.asks.isEmpty()) c.status = "working"
        return CommandResult(0, "$task: message sent\n", "")
    }

    /** `hc ls --json` for the task's worker, and `hc read --json` of its terminal. */
    fun hc(command: String): CommandResult? {
        if (command.startsWith("hc ls ")) {
            val task = Regex("""herdr\.task='([^']*)'""").find(command)?.groupValues?.get(1)
            val live = task?.let { card(it) }?.takeIf { it.transcript != null }
            val body = if (live == null) "[]" else """[{"address":"${live.id}","state":"live","labels":{"herdr.task":"${live.id}"}}]"""
            return CommandResult(0, """{"terminals":$body}""", "")
        }
        if (command.startsWith("hc read ")) {
            val terminal = Regex("""'([^']*)'""").find(command)?.groupValues?.get(1).orEmpty()
            val live = card(terminal)?.takeIf { it.transcript != null }
            return if (live == null) CommandResult(1, "", """{"error":{"message":"no terminal"}}""")
            else CommandResult(0, buildJsonObject { put("text", JsonPrimitive(live.transcript.orEmpty())) }.toString(), "")
        }
        return null
    }

    private fun file(c: ShpCard): String {
        val header = c.head().entries.joinToString("\n") { (k, v) -> "$k: ${(v as? JsonPrimitive)?.content ?: v}" }
        return "---\n$header\n---\n\n${c.brief.trim()}\n"
    }
}

/** devbox's simple-herdr-projects tasks: what needs you, work under way, and one done. */
object DemoShp {
    fun board(): FakeShp = FakeShp(
        mapOf(
            "app" to listOf(
                ShpCard(
                    id = "APP-1", title = "Show the machine on the task card", status = "needs",
                    step = "Waiting for your answer", progress = 55, left = "10m",
                    asks = mutableListOf("q1" to "Should the card show the machine too?"),
                    brief = "Put the machine under the task's title when more than one is connected, and leave it off a single machine's board.",
                    transcript = """
                        ● I started on the card. It needs a decision before I go on.
                          Should the card show the machine too?
                          ❯ 1. Yes, always
                            2. Only with several machines
                            3. No, the board already says it
                    """.trimIndent(),
                ),
                ShpCard(
                    id = "APP-2", title = "Ship the smaller board rows", status = "ready",
                    summary = "The rows are compact now, and the empty state fits on one screen.",
                    brief = "Make each board row two lines at most, and keep the status word once.",
                    progress = null, pr = "https://github.com/demo/app/pull/57",
                ),
                ShpCard(
                    id = "APP-5", title = "Split the settings card", status = "working",
                    step = "Moving the switches into their own card", progress = 35, left = "12m",
                    brief = "The settings card holds too much: give the switches their own card, above the key.",
                    transcript = """
                        ● I read the settings screen. The card holds the key, the switches and the
                          backups, so I'll move the switches up into a card of their own.
                        ● Settings.kt now draws the switches from a new card. Running the checks.
                        ● ./gradlew -p core test passed. Rendering the settings scenes next.
                    """.trimIndent(),
                ),
                ShpCard(id = "APP-7", title = "Answer asks from the phone", status = "done", summary = "Asks are answered in place."),
            ),
            "website" to listOf(
                ShpCard(
                    id = "WEB-3", title = "Rewrite the install page", status = "todo",
                    brief = "The install page still names the old service. Say how to pair a phone by hand.",
                ),
            ),
        ),
    )
}
