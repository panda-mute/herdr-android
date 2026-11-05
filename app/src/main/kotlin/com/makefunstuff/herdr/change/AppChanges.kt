package com.makefunstuff.herdr.change

import com.makefunstuff.herdr.core.board.WorkBoard
import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.change.AppChange
import com.makefunstuff.herdr.core.change.BuildState
import com.makefunstuff.herdr.core.change.BuilderCheck
import com.makefunstuff.herdr.core.change.HerdrAppBuild
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.data.HostManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Changing the app from the phone (Settings › Change the app, "Change this screen"): which machine builds it,
 * what that machine has for it, the tasks accepted from here, and the update it built, fetched over SSH and
 * offered as "Update ready". While the app is in front it watches the accepted tasks: once one is merged it asks
 * the herdr-android Assistant there to build, then reads the build's state until its APK can be copied here.
 * Kept through restarts in [file]; the fetched APKs go in [updates].
 */
class AppChanges(
    private val scope: CoroutineScope,
    private val hosts: HostManager,
    private val file: File,
    private val updates: File,
    val app: ThisApp,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    data class Saved(
        /** The machine picked in Settings to build the app. */
        val builder: String? = null,
        /** The machine "Change this screen" went to last: the builder until one is picked. */
        val last: String? = null,
        /** Tasks accepted from the phone on the builder, oldest first: built once merged, and what Undo takes back. */
        val accepted: List<String> = emptyList(),
        /** The accepted tasks the build was asked for. */
        val asked: List<String> = emptyList(),
        /** A build was asked for at this time (the phone's clock) and nothing came of it yet. */
        val waitingSince: Long? = null,
        /** The update fetched from the builder, ready to install. */
        val update: Update? = null,
    ) {
        val builderId: String? get() = builder ?: last
    }

    @Serializable
    data class Update(val versionCode: Long, val versionName: String, val path: String, val machine: String, val source: String? = null)

    /** What a machine has for building the app, as last looked at. */
    data class Look(
        val looking: Boolean = false,
        val check: BuilderCheck? = null,
        /** Its herdr-android space on Shepherdr, which takes the changes. */
        val space: WorkSpace? = null,
        val problem: String? = null,
    ) {
        /** What it lacks, in words: the space too. */
        val missing: List<String>
            get() = if (check == null) emptyList() else check.missing + listOfNotNull("the herdr-android space on Shepherdr".takeIf { space == null })

        val ok: Boolean get() = check != null && problem == null && missing.isEmpty()
    }

    data class State(
        val saved: Saved = Saved(),
        val looks: Map<String, Look> = emptyMap(),
        /** The builder's last build, as last read there. */
        val build: BuildState? = null,
        /** The update's APK is being copied here. */
        val fetching: Boolean = false,
        /** Why the update couldn't be fetched or offered. */
        val problem: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(State(saved = load()))
    val state: StateFlow<State> = _state.asStateFlow()
    private val ticking = Mutex()

    /** Watches the accepted tasks and the build while the app is in front. */
    fun start() {
        forgetInstalled()
        scope.launch {
            while (true) {
                if (hosts.isForeground) runCatching { tick() }.onFailure { if (it is CancellationException) throw it }
                delay(TICK_MS)
            }
        }
    }

    /** [machineId] builds the app from now on. */
    fun pick(machineId: String) = change { it.copy(builder = machineId) }

    /** A change went to [machineId]'s Assistant: it builds until one is picked. */
    fun sent(machineId: String) = change { it.copy(last = machineId) }

    /** [task] was accepted from the phone on [machineId]: built once merged, if that machine builds the app and it is a change to it. */
    fun accepted(machineId: String, task: WorkTask) {
        if (machineId != _state.value.saved.builderId) return
        scope.launch {
            runCatching {
                val board = hosts.session(machineId)?.workBoardOrNull(10_000) ?: return@runCatching
                if (AppChange.space(board.overview().spaces)?.id != task.space) return@runCatching
                change { it.copy(accepted = (it.accepted - task.name + task.name).takeLast(MAX_TASKS)) }
                tick()
            }
        }
    }

    /** The machine to send a change to: the builder, else the first one online whose Shepherdr has the herdr-android space. */
    suspend fun builder(): String? {
        _state.value.saved.builderId?.let { return it }
        for (h in hosts.hosts.value.filter { it.online && it.onShepherdr }) {
            val board = hosts.session(h.machine.id)?.workBoardOrNull(5_000) ?: continue
            if (runCatching { AppChange.space(board.overview().spaces) }.getOrNull() != null) return h.machine.id
        }
        return null
    }

    /** Looks at what [machineId] has for building the app (and puts herdr-app-build there for it). */
    suspend fun look(machineId: String): Look {
        _state.update { s -> s.copy(looks = s.looks + (machineId to (s.looks[machineId] ?: Look()).copy(looking = true))) }
        val look = try {
            val session = hosts.session(machineId) ?: throw IOException("That machine was taken out of the app")
            val board = session.workBoardOrNull(10_000)
            val space = board?.let { b -> AppChange.space(b.overview().spaces) }
            Look(check = client(session).check(space?.let(AppChange::folder)), space = space)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Look(problem = t.message ?: "Couldn't look there")
        }
        _state.update { s -> s.copy(looks = s.looks + (machineId to look)) }
        return look
    }

    /** Asks the builder's Assistant to build main now (for "Build now" and "Try again"). */
    suspend fun buildNow() {
        val (board, space) = builderSpace() ?: throw IOException("The builder's herdr-android space isn't reachable")
        board.say(space.id, AppChange.buildMessage(null, AppChange.folder(space)))
        change { it.copy(waitingSince = clock()) }
        _state.update { it.copy(problem = null) }
    }

    /** Asks for [task] to be taken back, as a new task; accepted from here, it ships as the next build. */
    suspend fun undo(machineId: String, space: String, task: WorkTask) {
        val board = hosts.session(machineId)?.awaitWorkBoard() ?: throw IOException("Shepherdr isn't reachable")
        board.say(space, AppChange.undoMessage(task))
    }

    /** The change Undo takes back on the builder: the newest task accepted from here that is merged. */
    fun lastChange(tasks: List<WorkTask>): WorkTask? = AppChange.lastChange(_state.value.saved.accepted, tasks)

    /** Installs the update (Android asks the person first), or opens the setting that lets the app install it. */
    fun install() {
        val u = _state.value.saved.update ?: return
        val f = File(u.path)
        if (!f.isFile) {
            change { it.copy(update = null) }
            return
        }
        if (app.mayInstall()) app.install(f) else app.askToInstall()
    }

    /** One look: ask for the build of what was merged, then follow the build until its APK is here. */
    private suspend fun tick() = ticking.withLock {
        val s = _state.value.saved
        val id = s.builderId ?: return@withLock
        if (s.accepted.none { it !in s.asked } && s.waitingSince == null) return@withLock
        val session = hosts.session(id) ?: return@withLock
        if (!session.state.value.online) return@withLock
        if (s.accepted.any { it !in s.asked }) {
            val board = session.workBoardOrNull(5_000) ?: return@withLock
            val ov = board.overview()
            val space = AppChange.space(ov.spaces) ?: return@withLock
            val merged = s.accepted.filter { it !in s.asked }.mapNotNull { t -> ov.tasks.firstOrNull { (it.id == t || it.name == t) && it.status == WorkStatus.Done } }
            if (merged.isNotEmpty()) {
                board.say(space.id, AppChange.buildMessage(merged.joinToString(" and ") { it.name }, AppChange.folder(space)))
                change { it.copy(asked = (it.asked + merged.map { m -> m.name }).takeLast(MAX_TASKS), waitingSince = clock()) }
            }
        }
        val since = _state.value.saved.waitingSince ?: return@withLock
        val b = client(session).state()
        _state.update { it.copy(build = b) }
        val n = b.number ?: 0
        when {
            b.phase == BuildState.Phase.Ready && n > app.versionCode && _state.value.saved.update?.versionCode != n -> fetch(session, b)
            b.phase == BuildState.Phase.Ready && n <= app.versionCode && (b.at ?: 0) >= since -> change { it.copy(waitingSince = null) }
            // A failure from before the ask is the last build's; this one hasn't started yet.
            b.phase == BuildState.Phase.Failed && (b.at ?: 0) >= since -> change { it.copy(waitingSince = null) }
        }
    }

    /** Copies the built APK here over SSH, checks it, and offers it. */
    private suspend fun fetch(session: HostSession, b: BuildState) {
        val remote = b.apk ?: return
        val n = b.number ?: return
        _state.update { it.copy(fetching = true, problem = null) }
        try {
            val f = withContext(Dispatchers.IO) {
                updates.mkdirs()
                updates.listFiles()?.forEach { it.delete() }
                val f = File(updates, "herdr-android-$n.apk")
                val conn = session.awaitConnection()
                conn.start(HerdrAppBuild.fetchCommand(remote)).use { p ->
                    p.stdin.close()
                    f.outputStream().use { out -> p.stdout.copyTo(out) }
                }
                if (b.size != null && f.length() != b.size) throw IOException("The update came over cut short (${f.length()} of ${b.size} bytes)")
                if (b.sha256 != null && sha256(f) != b.sha256) throw IOException("The update came over damaged")
                f
            }
            app.refuse(f)?.let { why ->
                f.delete()
                throw IOException(why)
            }
            change { it.copy(update = Update(n, b.versionName ?: "0.1.$n", f.path, session.machine.id, b.source), waitingSince = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            change { it.copy(waitingSince = null) }
            _state.update { it.copy(problem = t.message ?: "Couldn't copy the update") }
        } finally {
            _state.update { it.copy(fetching = false) }
        }
    }

    /** The builder's board and its herdr-android space, when both answer. */
    private suspend fun builderSpace(): Pair<WorkBoard, WorkSpace>? {
        val id = _state.value.saved.builderId ?: return null
        val board = hosts.session(id)?.workBoardOrNull(10_000) ?: return null
        val space = AppChange.space(board.overview().spaces) ?: return null
        return board to space
    }

    private suspend fun client(session: HostSession): HerdrAppBuild.Client {
        val conn = session.awaitConnection()
        return HerdrAppBuild.Client(
            run = { c, t -> conn.run(c, t) },
            runWithInput = { c, input, t -> conn.runWithInput(c, input.byteInputStream(), t) },
            label = session.machine.label,
            allowInstall = session.machine.allowHostScripts,
        )
    }

    /** An update offered before is installed now (the app runs it): its APK goes. */
    private fun forgetInstalled() {
        val u = _state.value.saved.update ?: return
        if (u.versionCode > app.versionCode) return
        runCatching { File(u.path).delete() }
        change { it.copy(update = null) }
    }

    @Synchronized
    private fun change(f: (Saved) -> Saved) {
        val before = _state.value.saved
        _state.update { it.copy(saved = f(it.saved)) }
        val next = _state.value.saved
        if (next == before) return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(Saved.serializer(), next))
            tmp.renameTo(file)
        }
    }

    private fun load(): Saved =
        runCatching { if (file.exists()) json.decodeFromString(Saved.serializer(), file.readText()) else null }.getOrNull() ?: Saved()

    private fun sha256(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                d.update(buf, 0, n)
            }
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TICK_MS = 20_000L
        const val MAX_TASKS = 20
    }
}
