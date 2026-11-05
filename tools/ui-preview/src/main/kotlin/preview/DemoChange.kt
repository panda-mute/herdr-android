// Changing the app from the phone on devbox: herdr-app-build there (what `check` finds and the last build's
// `state`), a change asked for from the Board and shipped as AND-31 in herdr-android's Assistant chat (its proof's
// before and after shots, its Accept card), and the phone's side (AppChanges.Saved) as the scene starts.
package preview

import com.makefunstuff.herdr.change.AppChanges
import com.makefunstuff.herdr.core.change.AppChange
import com.makefunstuff.herdr.core.ssh.CommandResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** herdr-app-build on a fake machine: [check]'s and [state]'s one JSON line each. */
class FakeAppBuild(
    var check: String = CHECK_OK,
    var state: String = """{"state":"none"}""",
) {
    /** `HerdrAppBuild.command(...)`: `sh -c '…herdr-app-build…' sh 'check' …`; null for any other command. */
    fun run(command: String): CommandResult? {
        if ("herdr-app-build" !in command) return null
        val args = Shell.words(command).drop(4)
        val body = when (args.firstOrNull()) {
            "check" -> check
            "state" -> state
            else -> "{}"
        }
        return CommandResult(0, body.dropLast(1) + ""","herdrAppBuild":1,"version":"1"}""" + "\n", "")
    }

    companion object {
        const val CHECK_OK = """{"checkout":"/home/demo/src/herdr-android","repo":"example/herdr-android","sdk":"/home/demo/Android/Sdk","java":21,"javaHome":"/usr/lib/jvm/java-21","key":"here","git":true}"""
        const val CHECK_NO_SDK = """{"checkout":"/home/demo/src/herdr-android","repo":"example/herdr-android","sdk":null,"java":21,"javaHome":"/usr/lib/jvm/java-21","key":"none","git":true}"""
    }
}

object DemoChange {
    /** Where devbox keeps the picture of the Board the change was asked with. */
    const val SHOT = "${Demo.UPLOADS}/screen-board.png"

    /** The Board before and after AND-31, as its agent rendered them, where it keeps them on devbox. */
    const val BEFORE = "/home/demo/src/herdr-android/.worktrees/AND-31/tools/ui-preview/build/shots/board-before.png"
    const val AFTER = "/home/demo/src/herdr-android/.worktrees/AND-31/tools/ui-preview/build/shots/board.png"

    /** The first message, as the sheet sends it from the Board in 0.1.123 (the preview's BuildConfig). */
    val FIRST = AppChange.firstMessage(
        AppChange.Screen("Board", "board/{mid}?task={task}&alone={alone}"), "0.1.123", 123,
        "Show each task's age on its row, after its status, so I see what has waited longest.", SHOT,
    )

    /** AND-30, merged earlier from the phone (what Undo takes back), and AND-31, the change ready to accept. */
    val TASKS = """
        [{"id":"AND-30","name":"A bigger Accept card","status":"done","place":"${DemoV2.APP}","listName":"herdr-android","sub":"Merged 2h ago","age":"2h"},
         {"id":"AND-31","name":"Each task's age on its row","status":"ready","place":"${DemoV2.APP}","listName":"herdr-android","sub":"Ready 2m ago","age":"2m",
          "agent":{"chat":"AND-31/worker","role":"worker","cli":"claude","status":"idle","running":true},"pr":{"number":231,"url":"https://github.com/example/herdr-android/pull/231"},
          "report":{"msg":"Each row shows its age","progress":100},"said":"The Board's rows show how long each task has waited, after its status, in both themes."}]
    """.trimIndent()

    /** Its proof: what changed, the Board before and after, and what isn't proven. */
    const val PROOF_LIST = """{"id":"pf31","title":"AND-31: Each task's age on its row · Proof","kind":"recipe","place":"sp-herdr-android","store":"|/home/demo/src/herdr-android","task":"AND-31",
          "v":1,"at":1793229100000,"when":"2m","by":"AND-31 agent","keeper":"AND-31 agent","chats":["p:sp-herdr-android"],"versions":[{"n":1,"when":"2m","by":"AND-31 agent","html":1}]}"""

    const val PROOF = """
        {"recipe":1,"blocks":[
         {"type":"heading","text":"Each task's age on its row","level":1},
         {"type":"text","md":"The Board's rows say how long each task has waited, after its status."},
         {"type":"pair","before":{"src":"before.png","caption":"Before: no age"},"after":{"src":"after.png","caption":"After: 2m, 1h, yesterday"}},
         {"type":"code","text":"./gradlew -p tools/ui-preview render -Ponly=board\n./gradlew :app:testDebugUnitTest","lang":"sh"},
         {"type":"callout","tone":"warn","title":"Not proven","md":"How it looks with a hundred tasks."}
        ]}
    """

    /** herdr-android's Assistant chat: the change asked, AND-31 made, ready, and its proof posted. */
    val CHAT = """
        [{"id":"c1","who":"you","at":1793228700000,"text":${JsonPrimitive(FIRST)}},
         {"id":"c2","who":"agent","at":1793228760000,"text":"I made it **AND-31**: Each task's age on its row. Its agent works in a worktree of its own, runs the tests and renders the Board before and after."},
         {"id":"tn:AND-31:started:3","who":"agent","at":1793228770000,"news":{"task":"AND-31","ref":"AND-31","mark":"started","what":"started","text":"Each task's age on its row"}},
         {"id":"tn:AND-31:ready:4","who":"agent","at":1793229090000,"news":{"task":"AND-31","ref":"AND-31","mark":"ready","what":"is ready","text":"Each row shows its age","accept":true}},
         {"id":"c5","who":"agent","at":1793229100000,"text":"AND-31 passed its checks and its review. The Board before and after:"},
         {"id":"c5b","who":"agent","at":1793229100000,"file":{"name":"board-before.png","path":"$BEFORE"}},
         {"id":"c5c","who":"agent","at":1793229100000,"file":{"name":"board.png","path":"$AFTER"}},
         {"id":"c6","who":"agent","at":1793229100000,"artifact":{"id":"pf31","v":1}}]
    """.trimIndent()

    /** The phone's side: devbox builds the app, AND-30 was accepted from here and merged. */
    fun saved(update: AppChanges.Update? = null) = AppChanges.Saved(builder = Demo.DEVBOX, accepted = listOf("AND-30"), asked = listOf("AND-30"), update = update)

    /** 0.1.131 fetched from devbox, built there with the release key. */
    val UPDATE = AppChanges.Update(131, "0.1.131", "/nonexistent/herdr-android-131.apk", Demo.DEVBOX, "host")
}

/**
 * devbox builds the app ([check], herdr-app-build's answer), on Shepherdr 3.1 with artifacts, and herdr-android's
 * Assistant chat is the change asked from the Board: AND-31 ready to accept, with its proof's shots.
 */
fun SceneData.appChange(check: String = FakeAppBuild.CHECK_OK, update: AppChanges.Update? = null) {
    artifacts()
    val v2 = host(Demo.DEVBOX).shepherdr!!
    val tasks = Json.parseToJsonElement(DemoChange.TASKS).jsonArray
    val list = (v2.snapshot["artifacts"] as? JsonArray).orEmpty()
    v2.snapshot = JsonObject(
        v2.snapshot + mapOf(
            "tasks" to JsonArray((v2.snapshot["tasks"] as? JsonArray).orEmpty() + tasks),
            "artifacts" to JsonArray(listOf(Json.parseToJsonElement(DemoChange.PROOF_LIST)) + list),
        ),
    )
    val key = DemoV2.chat(DemoV2.APP)
    v2.chats[key]?.let { c -> v2.chats = v2.chats + (key to JsonObject(c + ("messages" to Json.parseToJsonElement(DemoChange.CHAT).jsonArray))) }
    host(Demo.DEVBOX).appBuild = FakeAppBuild(check)
    fun shot(after: Boolean) = kotlin.io.path.createTempFile("board", ".png").toFile().apply { writeBytes(DemoArtifacts.picture("pf31", if (after) "after.png" else "before.png")!!) }
    host(Demo.DEVBOX).pictures = host(Demo.DEVBOX).pictures + mapOf(DemoChange.SHOT to Demo.picture("board"), DemoChange.BEFORE to shot(false), DemoChange.AFTER to shot(true))
    appChange = DemoChange.saved(update)
}
