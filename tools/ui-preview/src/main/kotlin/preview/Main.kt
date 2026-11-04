// Renders the app's real screens (app/ and core/, compiled for the desktop) to PNGs, without an
// Android SDK or an emulator. The app's real HostSession, HerdrApi and board client talk to
// FakeMachine, and scenes move through the app by its routes and by tapping what is on screen.
package preview

import com.makefunstuff.herdr.push.PushState
import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.PhoneQuiet
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.makefunstuff.herdr.AppContainer
import com.makefunstuff.herdr.HerdrRoot
import com.makefunstuff.herdr.bubble.BubbleChat
import com.makefunstuff.herdr.bubble.BubbleRoot
import com.makefunstuff.herdr.core.host.HostStatus
import com.makefunstuff.herdr.core.ssh.Machine
import com.makefunstuff.herdr.data.IdentityStore
import com.makefunstuff.herdr.ui.design.Toasts
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.imageio.ImageIO

/** Pixels per dp: the shots are 2× (a 412×892 dp phone is 824×1784 px). */
const val SCALE = 2f

class Scene(
    val name: String,
    /** Where the app is when the scene's [actions] start (`Routes.…`); null stays on Agents. */
    val route: String? = null,
    val widthDp: Int = 412,
    val heightDp: Int = 892,
    val fontScale: Float = 1f,
    /** The app's SSH key; [IdentityStore.State.Missing] opens the first-run key dialog. */
    val key: IdentityStore.State = IdentityStore.State.Ready(Demo.PUBLIC_KEY),
    /** Changes the demo machines before the app starts. */
    val setup: SceneData.() -> Unit = {},
    /** Taps and types on the running app. */
    val actions: ComposeUiTest.(SceneContext) -> Unit = {},
    /** Shows this chat as its floating bubble's window does (BubbleRoot) instead of the whole app. */
    val bubble: BubbleChat? = null,
    /** After the [actions], the window becomes this size (dp, width to height), as a rotation or a resize makes it; the shot is that size. */
    val resize: Pair<Int, Int>? = null,
)

/** What the app finds when it starts: the saved machines and what each answers. */
class SceneData {
    var machines: List<Machine> = Demo.machines()
    val hosts: MutableMap<String, FakeMachine> = Demo.hosts().toMutableMap()
    /** Chat drafts saved before the app opens, by DraftStore key. */
    val drafts = HashMap<String, String>()
    /** Notifications while closed, as saved before the app opens (PushStore). */
    var push: PushState? = null
    /** The phone's push apps (packages) and whether it lets the app notify. */
    var pushApps: List<String> = listOf("io.heckel.ntfy")
    var notificationsAllowed: Boolean = true
    /** Whether Android shows Live Updates as such (null: before Android 16). */
    var liveAllowed: Boolean? = true
    /** Do Not Disturb (or Bedtime) or car mode on the phone, for quiet hours. */
    var quietMode: PhoneQuiet? = null
    /** Notifications held during quiet hours, as saved before the app opens (Inbox). */
    var held: List<Alert> = emptyList()
    /** Whether Android lets the app's chats float as bubbles. */
    var bubblesAllowed: Boolean = true
    /** Which actions ask for the fingerprint and the app lock, as saved before the app opens (GuardStore). */
    var guard: com.makefunstuff.herdr.data.GuardState? = null
    /** Whether the phone has a screen lock (or a fingerprint) to ask with; and what its prompt answers (null: it stays up). */
    var screenLock: Boolean = true
    var unlockAnswer: Boolean? = true
    /** The brief's part as saved before the app opens (BriefStore): its schedule, when you last looked, what you saw. */
    var brief: com.makefunstuff.herdr.brief.BriefState? = null
    /** Shared to the app from elsewhere (the share sheet), waiting for a machine. */
    var shared: com.makefunstuff.herdr.core.share.Shared? = null
    /** Comments on code kept for later (CodeStore), and how code is shown. */
    var notes: List<com.makefunstuff.herdr.core.code.ReviewNote> = emptyList()
    var codeView: com.makefunstuff.herdr.data.CodeView? = null
    /** The Agents list's pins, snoozes and mutes (TriageStore), as saved before the app opens. */
    var triage: com.makefunstuff.herdr.core.home.Triage? = null
    /** Quick commands pinned, per machine (`Project:Run unit tests`). */
    var quickPins: Map<String, List<String>> = emptyMap()
    /** When each agent's chat was last on screen (LookedStore), by `<machine>/<pane>`: its "While you were away" card counts from it. */
    var looked: Map<String, Long> = emptyMap()
    /** The messages held over the agents' composers and the quick replies (ComposerStore), as saved before the app opens. */
    var composer: com.makefunstuff.herdr.data.ComposerStore.State? = null
    /** Pictures waiting in a chat's composer, by DraftStore key: each a name and the picture it is (made smaller as the app does). */
    val images = HashMap<String, List<Pair<String, File>>>()
    /** The chats' and the terminal's text sizes (TextSizeStore), as saved before the app opens. */
    var textSizes: com.makefunstuff.herdr.data.TextSizeStore.Sizes? = null
    /** What the phone's code scanner reads when Scan is tapped (`herdr-push pair`'s code), and the host key each address shows. */
    var scanned: String? = Demo.PAIR_LINK
    var reachable: Map<String, String> = Demo.pairReachable()
    /** The terminal's key bar and Keep screen on (KeyBarStore), as saved before the app opens. */
    var keyBar: com.makefunstuff.herdr.data.KeyBarStore.Saved? = null
    /** The terminal's selection once "Select text" starts one, in viewport cells (anchor, cursor): what a drag would select. */
    var selection: Pair<com.makefunstuff.herdr.ui.terminal.CellPoint, com.makefunstuff.herdr.ui.terminal.CellPoint>? = null
    /** The phone's microphone and how long it records, for voice messages (PreviewMic). */
    var mic: PreviewMic.Setup = PreviewMic.Setup()
    /** The coordinator's voice as the scene starts (PreviewCoordinatorVoice): off, or on in a chat and what it does. */
    var voice: com.makefunstuff.herdr.core.voice.VoiceState = com.makefunstuff.herdr.core.voice.VoiceState()
    /** A voice call the scene starts in (`PreviewVoiceCalls`). */
    var call: com.makefunstuff.herdr.core.voice.CallState = com.makefunstuff.herdr.core.voice.CallState()
    /** Its settings (VoiceSettingsStore), as saved before the app opens. */
    var voiceSettings: com.makefunstuff.herdr.data.VoiceSettingsStore.Settings? = null
    fun host(id: String): FakeMachine = hosts.getValue(id)
}

class SceneContext(val container: AppContainer, val nav: NavHostController, val data: SceneData) {
    /** Makes the app read [machineId]'s snapshot again now, as an event from herdr would. */
    fun refresh(machineId: String) = container.hosts.session(machineId)?.refresh()
}

fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "build/shots" }).apply { mkdirs() }
    val only = args.getOrElse(1) { "" }.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val all = scenes()
    val names = all.map { it.name }
    val unknown = only.filter { o -> names.none { it == o || it == o.removeSuffix("-light") } }
    if (unknown.isNotEmpty()) {
        System.err.println("No scene called ${unknown.joinToString()}. Scenes: ${names.joinToString()}")
        kotlin.system.exitProcess(2)
    }
    // Shard k of n (`k/n`, from 1): every n-th scene, both its themes, so CI renders them on n runners at once.
    val (shard, shards) = args.getOrElse(2) { "" }.ifEmpty { "1/1" }.split('/').map { it.trim().toInt() }
    require(shards >= 1 && shard in 1..shards) { "shard is k/n with 1 ≤ k ≤ n, not ${args[2]}" }
    var failed = 0
    for ((index, scene) in all.withIndex()) for (dark in listOf(true, false)) {
        if (index % shards != shard - 1) continue
        val name = if (dark) scene.name else scene.name + "-light"
        if (only.isNotEmpty() && name !in only && scene.name !in only) continue
        val file = File(out, "$name.png")
        val started = System.currentTimeMillis()
        val watch = Watchdog.start(name)
        runCatching { render(scene, dark, file) }
            .onSuccess { println("ok   $name  (${System.currentTimeMillis() - started} ms)") }
            .onFailure { failed++; println("FAIL $name: $it"); it.printStackTrace() }
        watch.interrupt()
    }
    println("Shots in ${out.absolutePath}")
    // Leftover coroutines and the fake machines' threads would keep the JVM alive.
    kotlin.system.exitProcess(if (failed > 0) 1 else 0)
}

/**
 * A shot that takes longer than [LIMIT_MS] is stuck (the slowest take about 25 s): what every thread
 * was doing goes to the log and the run fails at once, instead of waiting for the CI job's timeout.
 */
object Watchdog {
    const val LIMIT_MS = 120_000L

    fun start(name: String): Thread = Thread {
        try {
            Thread.sleep(LIMIT_MS)
        } catch (_: InterruptedException) {
            return@Thread
        }
        println("HANG $name: no shot after ${LIMIT_MS / 1000} s; every thread's stack follows")
        for ((thread, stack) in Thread.getAllStackTraces()) {
            println("\n\"${thread.name}\" ${thread.state}")
            stack.forEach { println("    at $it") }
        }
        System.out.flush()
        Runtime.getRuntime().halt(1)
    }.apply { isDaemon = true; this.name = "watchdog $name"; start() }
}

fun render(scene: Scene, dark: Boolean, file: File) {
    val root = kotlin.io.path.createTempDirectory("herdr-preview").toFile()
    val data = SceneData().apply(scene.setup)
    val context = Context(root)
    context.filesDir.resolve("machines.json").writeText(Json.encodeToString(ListSerializer(Machine.serializer()), data.machines))
    data.drafts.forEach { (k, v) -> context.getSharedPreferences("drafts", 0).edit().putString(k, v).apply() }
    data.push?.let { context.filesDir.resolve("push.json").writeText(Json.encodeToString(PushState.serializer(), it)) }
    data.triage?.let { context.filesDir.resolve("triage.json").writeText(Json.encodeToString(com.makefunstuff.herdr.core.home.Triage.serializer(), it)) }
    data.brief?.let { context.filesDir.resolve("brief.json").writeText(Json.encodeToString(com.makefunstuff.herdr.brief.BriefState.serializer(), it)) }
    PreviewPhone.apps = data.pushApps
    PreviewPhone.allowed = data.notificationsAllowed
    PreviewPhone.liveAllowed = data.liveAllowed
    PreviewPhone.quietMode = data.quietMode
    PreviewBubbles.allowed = data.bubblesAllowed
    PreviewScanner.code = data.scanned
    PreviewTerminal.selection = data.selection
    PreviewMic.set(data.mic)
    PreviewCoordinatorVoice.next = data.voice
    PreviewVoiceCalls.next = data.call
    PreviewScanner.hostKeys = data.reachable
    PreviewUnlock.screenLock = data.screenLock
    PreviewUnlock.answer = data.unlockAnswer
    synchronized(PreviewUnlock.asked) { PreviewUnlock.asked.clear() }
    data.looked.forEach { (key, at) -> com.makefunstuff.herdr.data.LookedStore(context.filesDir.resolve("looked.json")).mark(key.substringBefore('/'), key.substringAfter('/'), at) }
    data.composer?.let { context.filesDir.resolve("composer.json").writeText(Json.encodeToString(com.makefunstuff.herdr.data.ComposerStore.State.serializer(), it)) }
    data.keyBar?.let { context.filesDir.resolve("key-bar.json").writeText(Json.encodeToString(com.makefunstuff.herdr.data.KeyBarStore.Saved.serializer(), it)) }
    data.voiceSettings?.let { context.filesDir.resolve("voice-settings.json").writeText(Json.encodeToString(com.makefunstuff.herdr.data.VoiceSettingsStore.Settings.serializer(), it)) }
    data.textSizes?.let { context.filesDir.resolve("text-sizes.json").writeText(Json.encodeToString(com.makefunstuff.herdr.data.TextSizeStore.Sizes.serializer(), it)) }
    data.guard?.let { context.filesDir.resolve("guard.json").writeText(Json.encodeToString(com.makefunstuff.herdr.data.GuardState.serializer(), it)) }
    if (data.held.isNotEmpty()) context.filesDir.resolve("held.json").writeText(Json.encodeToString(ListSerializer(Alert.serializer()), data.held))
    IdentityStore.initial = scene.key
    FakeMachines.machines = data.hosts
    Toasts.dismiss()
    TalkBack.shown = null
    val container = AppContainer(context)
    data.shared?.let { container.shares.share(it) }
    data.notes.forEach { container.code.add(it) }
    data.codeView?.let { container.code.setView(it) }
    data.quickPins.forEach { (m, keys) -> keys.forEach { container.quick.setPinned(m, it, true) } }
    data.images.forEach { (key, pictures) ->
        pictures.forEach { (name, file) ->
            com.makefunstuff.herdr.ui.images.ImageCodec.prepare(file, null, name, container.images)?.let { container.images.add(key, it) }
        }
    }
    try {
        // Every machine has answered (or refused) before the first frame, so a shot never catches "Connecting".
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val states = container.hosts.hosts.value
            if (states.size == data.machines.size && states.all { it.status != HostStatus.Connecting && it.status != HostStatus.Stopped }) break
            Thread.sleep(20)
        }
        // The machines' usage, as the app keeps it from its last reading (read at the demo's time, so never stale).
        data.hosts.forEach { (id, m) ->
            if (m.usage != null) container.hosts.session(id)?.let { s -> kotlinx.coroutines.runBlocking { container.usage.read(s) } }
        }
        // The window is as big as the scene ever gets; the app is drawn in its top left corner at the scene's size.
        val size = mutableStateOf(DpSize(scene.widthDp.dp, scene.heightDp.dp))
        val w = (maxOf(scene.widthDp, scene.resize?.first ?: 0) * SCALE).toInt()
        val h = (maxOf(scene.heightDp, scene.resize?.second ?: 0) * SCALE).toInt()
        runDesktopComposeUiTest(w, h) {
            mainClock.autoAdvance = false
            lateinit var nav: NavHostController
            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(SCALE, scene.fontScale),
                    LocalContext provides context,
                    LocalView provides android.view.View(context),
                    LocalLifecycleOwner provides Resumed,
                    LocalViewModelStoreOwner provides Resumed,
                    LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) {} },
                ) {
                    nav = rememberNavController()
                    Box(Modifier.size(size.value)) {
                        val chat = scene.bubble
                        if (chat != null) BubbleRoot(container, chat, dark = dark, nav = nav) else HerdrRoot(container, dark = dark, nav = nav)
                        TalkBackOverlay(dark)
                    }
                }
            }
            settle()
            scene.route?.let { route ->
                runOnUiThread { nav.navigate(route) }
                settle()
            }
            scene.actions(this, SceneContext(container, nav, data))
            settle()
            scene.resize?.let { (rw, rh) ->
                size.value = DpSize(rw.dp, rh.dp)
                settle()
            }
            val shown = size.value
            ImageIO.write(capture().getSubimage(0, 0, (shown.width.value * SCALE).toInt(), (shown.height.value * SCALE).toInt()), "png", file)
        }
    } finally {
        container.close()
        FakeMachines.closeAll()
        root.deleteRecursively()
    }
}

/** A lifecycle that is always resumed and a ViewModel store, which an Activity gives the app on the phone. */
private object Resumed : LifecycleOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore = ViewModelStore()
}

/**
 * Lets the app catch up: advances the test clock (animations, LaunchedEffect delays) and gives the
 * fake machines' threads real time to answer, a few times over.
 */
fun ComposeUiTest.settle(rounds: Int = 12) {
    repeat(rounds) {
        mainClock.advanceTimeBy(200)
        Thread.sleep(60)
        waitForIdle()
    }
}

private fun ComposeUiTest.node(matcher: SemanticsMatcher) = onAllNodes(matcher, useUnmergedTree = true).onFirst()

/** Taps the first element showing [text] (exactly, or as its start when it is longer). */
fun ComposeUiTest.tapText(text: String) { tap(node(hasText(text, substring = true))) }

/** Taps the first element showing exactly [text] (a button, where a longer line also starts with its words). */
fun ComposeUiTest.tapExact(text: String) { tap(node(hasText(text, substring = false))) }

/** Taps the first element whose content description (an icon button's label) is [label]. */
fun ComposeUiTest.tapLabel(label: String) { tap(node(hasContentDescription(label))) }

/** A tap, on the UI thread: one that opens another screen moves the navigation's lifecycles, which only that thread may. */
private fun ComposeUiTest.tap(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
    runOnUiThread { node.performClick() }
    settle()
}

fun ComposeUiTest.longPressText(text: String) { node(hasText(text, substring = true)).performTouchInput { longClick() }; settle() }

/**
 * A finger on the element whose content description starts with [label], down on the first of
 * [points], through the others, and up: each a point as a fraction (0 to 1) of the element's width
 * and height, or of [within] when given (a picture's place in it, from its size).
 */
fun ComposeUiTest.drag(label: String, vararg points: Pair<Float, Float>, within: ((Float, Float) -> androidx.compose.ui.geometry.Rect)? = null) {
    node(hasContentDescription(label, substring = true)).performTouchInput {
        val area = within?.invoke(width.toFloat(), height.toFloat()) ?: androidx.compose.ui.geometry.Rect(0f, 0f, width.toFloat(), height.toFloat())
        val at = points.map { (x, y) -> androidx.compose.ui.geometry.Offset(area.left + x * area.width, area.top + y * area.height) }
        down(at.first())
        // In small steps, as a finger moves.
        at.zipWithNext().forEach { (a, b) -> for (i in 1..8) moveTo(a + (b - a) * (i / 8f), delayMillis = 16) }
        up()
    }
    settle()
}

/**
 * A finger on the element whose content description starts with [label], down on the first of
 * [points] and through the others as [drag] does, but still down when the shot is taken: a hold.
 */
fun ComposeUiTest.hold(label: String, vararg points: Pair<Float, Float>) {
    node(hasContentDescription(label, substring = true)).performTouchInput {
        val at = points.map { (x, y) -> androidx.compose.ui.geometry.Offset(x * width, y * height) }
        down(at.first())
        at.zipWithNext().forEach { (a, b) -> for (i in 1..8) moveTo(a + (b - a) * (i / 8f), delayMillis = 16) }
        // Held long enough that letting go would finish, not leave it hearing as a tap does.
        advanceEventTime(1_000)
        moveBy(androidx.compose.ui.geometry.Offset.Zero)
    }
    settle()
}

/** Scrolls the first scrollable list to its item [index] at once (in a chat, which runs bottom up, 0 is the newest). */
fun ComposeUiTest.scrollTo(index: Int) {
    runOnUiThread { node(androidx.compose.ui.test.hasScrollToIndexAction()).performScrollToIndex(index) }
    settle()
}

/** Types into the first text field (or the one showing [field]). */
fun ComposeUiTest.type(value: String, field: String? = null) {
    val matcher = androidx.compose.ui.test.hasSetTextAction().let { if (field != null) it and hasText(field, substring = true) else it }
    node(matcher).performTextInput(value)
    settle()
}

/** Types into the text field labelled [label] (a form's field, whose label is its content description). */
fun ComposeUiTest.typeInto(label: String, value: String) {
    node(androidx.compose.ui.test.hasSetTextAction() and hasContentDescription(label)).performTextInput(value)
    settle()
}

/** The main window plus any popups (menus, dialogs, sheets) drawn at their places in it. */
fun ComposeUiTest.capture(): java.awt.image.BufferedImage {
    val roots = onAllNodes(isRoot())
    val nodes = roots.fetchSemanticsNodes()
    val base = roots[0].captureToImage().toAwtImage()
    val out = java.awt.image.BufferedImage(base.getWidth(null), base.getHeight(null), java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.drawImage(base, 0, 0, null)
    for (i in 1 until nodes.size) {
        val b = nodes[i].boundsInWindow
        g.drawImage(roots[i].captureToImage().toAwtImage(), b.left.toInt(), b.top.toInt(), null)
    }
    g.dispose()
    return out
}
