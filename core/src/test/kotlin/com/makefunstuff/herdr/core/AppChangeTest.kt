package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.change.AppChange
import com.makefunstuff.herdr.core.change.BuildState
import com.makefunstuff.herdr.core.change.BuilderCheck
import com.makefunstuff.herdr.core.change.HerdrAppBuild
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Changing the app from the phone: the messages to the herdr-android Assistant, and herdr-app-build run for real
 * against a checkout of its own, a fake Gradle and a fake herdr-secret (never the real key, never GitHub).
 */
class AppChangeTest {
    private val script = File(System.getProperty("herdrAppBuild") ?: "../host/herdr-app-build/herdr-app-build")

    // ---- messages -------------------------------------------------------------------------------

    @Test
    fun theFirstMessageCarriesTheScreenTheBuildAndTheShot() {
        val m = AppChange.firstMessage(
            AppChange.Screen("Board", "board/{mid}?task={task}&alone={alone}"), "0.1.130", 130,
            "  Show each task's age on its row  ", "/home/demo/.cache/herdr-android/uploads/x/screen board.png",
        )
        assertTrue(m.startsWith("@\"/home/demo/.cache/herdr-android/uploads/x/screen board.png\"\n\nChange this screen of the app: Show each task's age on its row\n"), m)
        assertTrue("- Screen: Board (route `board/{mid}?task={task}&alone={alone}`)" in m, m)
        assertTrue("- App: 0.1.130 (version code 130)" in m, m)
        assertTrue("herdr-task on herdr-android" in m, m)
        assertTrue(AppChange.firstMessage(AppChange.Screen("", null), "0.1.1", 1, "x", null).startsWith("Change this screen"))
    }

    @Test
    fun theBuildAndUndoMessages() {
        val b = AppChange.buildMessage("HERD-31", "/home/demo/src/herdr-android")
        assertTrue(b.startsWith("HERD-31 is merged. "), b)
        assertTrue("python3 ~/.local/share/herdr-android/herdr-app-build build --checkout '/home/demo/src/herdr-android'" in b, b)
        val u = AppChange.undoMessage(WorkTask("HERD-31", "HERD-31", "Task ages on the board", "sp", WorkStatus.Done))
        assertTrue(u.startsWith("Undo the last change to the app, HERD-31 (“Task ages on the board”): revert its merge"), u)
        assertTrue("new, higher one" in u, u)
    }

    @Test
    fun findsTheSpaceAndTheLastChange() {
        val spaces = listOf(
            WorkSpace("home", "Home", home = true, store = "|/home/demo"),
            WorkSpace("sp-web", "website", store = "|/home/demo/src/website"),
            WorkSpace("sp-app", "Phone app", store = "|/home/demo/src/herdr-android"),
        )
        assertEquals("sp-app", AppChange.space(spaces)?.id)
        assertEquals("/home/demo/src/herdr-android", AppChange.folder(spaces[2]))
        assertEquals("sp-named", AppChange.space(spaces + WorkSpace("sp-named", "herdr-android"))?.id)
        assertNull(AppChange.space(spaces.take(2)))

        val tasks = listOf(
            WorkTask("HERD-30", "HERD-30", "a", "sp-app", WorkStatus.Done),
            WorkTask("HERD-31", "HERD-31", "b", "sp-app", WorkStatus.Merging),
        )
        // HERD-31 is still on its way in: Undo takes back HERD-30.
        assertEquals("HERD-30", AppChange.lastChange(listOf("HERD-30", "HERD-31"), tasks)?.id)
        assertEquals("HERD-31", AppChange.lastChange(listOf("HERD-30", "HERD-31"), tasks.map { it.copy(status = WorkStatus.Done) })?.id)
        assertNull(AppChange.lastChange(emptyList(), tasks))
    }

    @Test
    fun readsTheCheckAndTheState() {
        val missing = BuilderCheck.parse(Json.parseToJsonElement("""{"checkout":"/h/herdr-android","repo":"o/herdr-android","sdk":null,"java":21,"key":"none","git":true,"herdrAppBuild":1}""").jsonObject)
        assertEquals(listOf("the Android SDK"), missing.missing)
        assertFalse(missing.ok)
        assertFalse(missing.signsHere)
        val ok = BuilderCheck.parse(Json.parseToJsonElement("""{"checkout":"/h/a","sdk":"/sdk","java":17,"key":"main"}""").jsonObject)
        assertTrue(ok.ok)
        assertEquals(BuilderCheck.Key.Main, ok.key)

        val s = BuildState.parse(Json.parseToJsonElement("""{"state":"building","number":131,"source":"host","sha":"abc"}""").jsonObject)
        assertEquals(BuildState.Phase.Building, s.phase)
        assertEquals("Building 0.1.131", s.words)
        assertTrue(s.running)
        assertEquals(BuildState.Phase.None, BuildState.parse(Json.parseToJsonElement("""{"state":"none"}""").jsonObject).phase)
        assertNotNull(HerdrAppBuild.answer("motd\n{\"state\":\"none\",\"herdrAppBuild\":1}\n"))
        assertNull(HerdrAppBuild.answer("{\"other\":1}\n"))
    }

    // ---- the script -----------------------------------------------------------------------------

    private fun python(): Boolean = runCatching {
        ProcessBuilder("python3", "--version").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
    }.getOrDefault(false)

    /** A machine of its own: a home, a bin, an origin with a herdr-android main, its checkout and an SDK. */
    private class Box(val home: File) {
        val bin = File(home, "bin").apply { mkdirs() }
        val origin = File(home, "origin.git")
        val checkout = File(home, "src/herdr-android")
        val sdk = File(home, "sdk").apply { File(this, "platforms").mkdirs(); File(this, "build-tools").mkdirs() }
        val releases = File(home, "releases")
        val cache = File(home, "cache")

        fun exe(name: String, body: String) = File(bin, name).apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }
    }

    private fun sh(dir: File, vararg cmd: String) {
        val p = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { "${cmd.joinToString(" ")}: $out" }
    }

    private fun box(): Box {
        val b = Box(Files.createTempDirectory("herdr-app-build").toFile())
        val work = File(b.home, "work").apply { mkdirs() }
        File(work, "settings.gradle.kts").writeText("rootProject.name = \"herdr-android\"\n")
        File(work, "app").mkdirs()
        File(work, "app/build.gradle.kts").writeText("// the app\n")
        // Gradle's stand-in: the APK says which version code and key it was built with.
        File(work, "gradlew").apply {
            writeText("#!/bin/sh\nmkdir -p app/build/outputs/apk/release\nprintf 'apk %s key=%s alias=%s\\n' \"\$GITHUB_RUN_NUMBER\" \"\${HERDR_KEYSTORE_FILE:-none}\" \"\${HERDR_KEY_ALIAS:-none}\" > app/build/outputs/apk/release/app-release.apk\n")
            setExecutable(true)
        }
        val git = arrayOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "init.defaultBranch=main")
        sh(work, *git, "init", "-q")
        sh(work, *git, "add", "-A")
        sh(work, *git, "commit", "-q", "-m", "HERD-31: Task ages on the board")
        sh(b.home, *git, "clone", "-q", "--bare", work.absolutePath, b.origin.absolutePath)
        sh(b.home, *git, "clone", "-q", b.origin.absolutePath, b.checkout.absolutePath)
        // origin reads as GitHub's; fetching it reaches the local one.
        sh(b.checkout, "git", "remote", "set-url", "origin", "https://github.com/demo/herdr-android.git")
        sh(b.checkout, "git", "config", "url.${b.origin.absolutePath}.insteadOf", "https://github.com/demo/herdr-android.git")
        return b
    }

    private fun run(b: Box, vararg args: String, sdk: Boolean = true): Pair<Int, String> {
        val pb = ProcessBuilder(listOf("python3", script.absolutePath) + args).directory(b.home)
        pb.environment().apply {
            clear()
            put("HOME", b.home.absolutePath)
            put("SHELL", "/bin/sh")
            put("PATH", b.bin.absolutePath + ":/usr/bin:/bin")
            put("JAVA_HOME", System.getProperty("java.home"))
            if (sdk) put("ANDROID_HOME", b.sdk.absolutePath)
            put("HERDR_APP_BUILD_HOME", b.cache.absolutePath)
            put("HERDR_APP_BUILD_RELEASES", b.releases.toURI().toString().trimEnd('/'))
            put("HERDR_APP_BUILD_RELEASE_WAIT", "0")
        }
        val p = pb.start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(p.waitFor(120, TimeUnit.SECONDS))
        return p.exitValue() to out + err
    }

    private fun state(b: Box) = BuildState.parse(assertNotNull(HerdrAppBuild.answer(run(b, "state").second)))

    @Test
    fun checksWhatTheMachineHas() {
        assumeTrue(python())
        val b = box()
        val ok = BuilderCheck.parse(assertNotNull(HerdrAppBuild.answer(run(b, "check").second)))
        assertEquals(b.checkout.canonicalPath, File(assertNotNull(ok.checkout)).canonicalPath)
        assertEquals("demo/herdr-android", ok.repo)
        assertEquals(b.sdk.absolutePath, ok.sdk)
        assertTrue((ok.java ?: 0) >= 17)
        assertEquals(BuilderCheck.Key.None, ok.key)
        assertTrue(ok.ok, ok.missing.toString())

        val noSdk = BuilderCheck.parse(assertNotNull(HerdrAppBuild.answer(run(b, "check", sdk = false).second)))
        assertEquals(listOf("the Android SDK"), noSdk.missing)
        val elsewhere = BuilderCheck.parse(assertNotNull(HerdrAppBuild.answer(run(b, "check", "--checkout", "/nowhere").second)))
        assertTrue("a herdr-android checkout" in elsewhere.missing)

        b.exe("herdr-secret", """[ "$1" = names ] && echo '[{"name":"HERDR_KEYSTORE_FILE"},{"name":"HERDR_KEYSTORE_PASSWORD"},{"name":"HERDR_KEY_ALIAS"}]'""")
        assertEquals(BuilderCheck.Key.Here, BuilderCheck.parse(assertNotNull(HerdrAppBuild.answer(run(b, "check").second))).key)
    }

    @Test
    fun buildsTheMergeWithTheKeyFromHerdrSecret() {
        assumeTrue(python())
        val b = box()
        // herdr-secret's stand-in: it knows the key's names, and `run` hands the command their values.
        b.exe(
            "herdr-secret",
            """
            case "$1" in
              names) echo '[{"name":"HERDR_KEYSTORE_FILE"},{"name":"HERDR_KEYSTORE_PASSWORD"},{"name":"HERDR_KEY_ALIAS"}]' ;;
              run) shift 3; HERDR_KEYSTORE_FILE=/keys/release.jks HERDR_KEYSTORE_PASSWORD=pw HERDR_KEY_ALIAS=herdr exec "$@" ;;
            esac
            """.trimIndent(),
        )
        assertEquals(BuildState.Phase.None, state(b).phase)
        val (code, said) = run(b, "build", "--checkout", b.checkout.absolutePath, "--number", "131")
        assertEquals(0, code, said)
        val s = state(b)
        assertEquals(BuildState.Phase.Ready, s.phase)
        assertEquals(131L, s.number)
        assertEquals("host", s.source)
        assertEquals("HERD-31: Task ages on the board", s.subject)
        val apk = File(assertNotNull(s.apk))
        assertEquals("apk 131 key=/keys/release.jks alias=herdr\n", apk.readText())
        assertEquals(apk.length(), s.size)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }, s.sha256)
        // The build ran in a worktree of its own; the checkout is as it was.
        assertFalse(File(b.checkout, "app/build").exists())
    }

    @Test
    fun withoutTheKeyItFetchesCisRelease() {
        assumeTrue(python())
        val b = box()
        File(b.releases, "demo/herdr-android/releases/download/v0.1.132").apply { mkdirs() }
            .resolve("herdr-android.apk").writeText("ci's apk 132\n")
        run(b, "build", "--number", "132").let { (code, said) -> assertEquals(0, code, said) }
        val s = state(b)
        assertEquals(BuildState.Phase.Ready, s.phase)
        assertEquals("release", s.source)
        assertEquals("ci's apk 132\n", File(assertNotNull(s.apk)).readText())

        // No release for the next one (CI hasn't published it): the build fails and says why.
        assertEquals(1, run(b, "build", "--number", "133").first)
        val failed = state(b)
        assertEquals(BuildState.Phase.Failed, failed.phase)
        assertTrue("v0.1.133" in failed.error.orEmpty(), failed.error)
    }
}
