package com.makefunstuff.herdr.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.makefunstuff.herdr.core.board.WorkBoard
import com.makefunstuff.herdr.core.chat.Dictation
import com.makefunstuff.herdr.core.chat.DictationCheck
import com.makefunstuff.herdr.core.chat.MachineEars
import com.makefunstuff.herdr.core.chat.VoiceNote
import com.makefunstuff.herdr.core.chat.VoiceNotes
import com.makefunstuff.herdr.core.chat.VoiceRecorder
import com.makefunstuff.herdr.ui.design.VoiceBubble
import com.makefunstuff.herdr.ui.design.VoiceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Voice messages in one chat: the composer's [recorder], this chat's [notes] (oldest first), and
 * playing them ([play], one at a time). A recording is a [VoiceNote] at once; it is copied to the
 * machine and handed to the agent from there, and one that couldn't go waits on the phone with why,
 * for [retry] (or [discard]). [online]: the machine is reachable, so one waiting goes now.
 */
class VoiceMessages internal constructor(
    /** For the composer's mic; null on a phone without a microphone. */
    val recorder: VoiceRecorder?,
    /** Talk to type, a tap on that mic; null on a phone without a microphone. */
    val dictation: Dictation?,
    val notes: List<VoiceNote>,
    val online: Boolean,
    val player: VoicePlayer,
    /** The voice messages being read back from the machine to play, by key. */
    val loading: Set<String>,
    val retry: (VoiceNote) -> Unit,
    /** Shepherdr's refusals, why by `cid`: the voice messages they were wait for a retry again. */
    val refused: (Map<String, String>) -> Unit,
    val discard: (VoiceNote) -> Unit,
    /** Plays (or pauses) the voice message at [remote] on the machine, or [note] still on the phone. */
    val play: (key: String, remote: String?, note: VoiceNote?) -> Unit,
) {
    fun note(remote: String): VoiceNote? = VoiceNotes.byRemote(notes, remote)
}

/** The open chat's voice messages, for its bubbles. */
val LocalVoiceMessages = staticCompositionLocalOf<VoiceMessages?> { null }

/**
 * Voice messages for the chat under [chat] (its DraftStore key): each recording is copied to the
 * machine with [upload] (its path there back), then handed to the agent with [deliver] (with the
 * note's id, which Shepherdr takes as the message's `cid`). Both are given the chat it is for, which
 * is still the screen's only while they run: one that changed (another space picked) refuses. A sent one
 * not on the phone any more is read back with [fetch] to play. Waiting ones go on their own when the
 * chat opens or the machine comes back ([online]), in the order recorded.
 *
 * A tap on the mic talks to type ([VoiceMessages.dictation]): written down by [board]'s machine where Shepherdr
 * has whisper.cpp there (asked once a while per [machine], [DictationCheck]), else by the phone's recogniser.
 */
@Composable
fun rememberVoiceMessages(
    chat: String,
    online: Boolean,
    upload: suspend (file: File, name: String) -> String,
    deliver: suspend (remote: String, id: String) -> Unit,
    fetch: suspend (remote: String) -> ByteArray,
    machine: String? = null,
    /** The machine's board, null where it has none (so no Shepherdr to write speech down). */
    board: (suspend () -> WorkBoard?)? = null,
): VoiceMessages {
    val container = LocalContainer.current
    val store = container.voice
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val up by rememberUpdatedState(upload)
    val give by rememberUpdatedState(deliver)
    val get by rememberUpdatedState(fetch)
    val isOnline by rememberUpdatedState(online)
    val current by rememberUpdatedState(chat)
    val all by store.notes.collectAsState()
    val notes = remember(all, chat) { all.filter { it.chat == chat } }
    // One at a time across chats too: a space switched mid-send never runs two.
    val lock = remember { Mutex() }
    val player = rememberVoicePlayer()
    var loading by remember { mutableStateOf(emptySet<String>()) }

    /** Sends what of this chat's waits, one at a time in the order recorded; one that fails says why, and the rest wait behind it. */
    fun pump() {
        if (!isOnline) return
        val mine = chat
        scope.launch {
            lock.withLock {
                for (n in store.notes.value.filter { it.chat == mine && !it.sent }) {
                    // The screen moved to another chat meanwhile: these wait until it is back on theirs.
                    if (current != mine) return@withLock
                    // One that failed holds the rest behind it, so they never go before it.
                    if (n.failed) return@withLock
                    try {
                        val remote = n.remote ?: withContext(Dispatchers.IO) { up(File(n.file), VoiceNotes.fileName(n.millis)) }
                            .also { r -> store.update(n.id) { it.copy(remote = r) } }
                        if (current != mine) return@withLock
                        // A new id each try: a refusal of an earlier one never marks this one.
                        val cid = UUID.randomUUID().toString()
                        store.update(n.id) { it.copy(cid = cid) }
                        give(remote, cid)
                        store.update(n.id) { it.copy(sent = true, sentAt = container.clock(), error = null) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        store.update(n.id) { it.copy(error = t.message ?: "Couldn't send it") }
                        return@withLock
                    }
                }
            }
        }
    }

    val recorder = rememberRecorder(store) { file, millis ->
        store.add(VoiceNote(UUID.randomUUID().toString(), chat, file.path, millis, container.clock()))
        pump()
    }
    // Talk to type: the machine writes it down where it can, else the phone does.
    val phone = rememberPhoneEars()
    val machineBoard by rememberUpdatedState(board)
    var writes by remember(machine) { mutableStateOf(machine?.let { DictationCheck.known(it, container.clock()) }) }
    LaunchedEffect(machine, online) {
        val b = machineBoard
        if (machine == null || b == null || !online || writes != null) return@LaunchedEffect
        // Not known (the machine didn't answer): asked again when the chat opens next.
        val can = try { b()?.canDictate() ?: false } catch (e: CancellationException) { throw e } catch (_: Throwable) { null } ?: return@LaunchedEffect
        DictationCheck.keep(machine, can, container.clock())
        writes = can
    }
    val writesNow by rememberUpdatedState(writes)
    val dictation = remember(recorder) {
        recorder?.let {
            Dictation(container.clock, scope) {
                val b = machineBoard
                val onMachine = b?.let { MachineEars(phone.tape()) { audio, type -> (b() ?: throw java.io.IOException("Shepherdr isn't on this machine")).dictate(audio, type) } }
                when {
                    writesNow == true && onMachine != null -> onMachine
                    phone.recogniser != null -> phone.recogniser.invoke()
                    // Not known yet and the phone can't: the machine may.
                    writesNow == null -> onMachine
                    else -> null
                }
            }
        }
    }
    // A recording belongs to the chat it began in: another one picked meanwhile (a space) drops it.
    LaunchedEffect(chat) { recorder?.cancel(); dictation?.cancel() }
    // Put away, the phone gives the app no microphone: what was said is dropped, off the screen too.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(dictation, lifecycle) {
        val away = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) dictation?.cancel() }
        lifecycle.addObserver(away)
        onDispose {
            lifecycle.removeObserver(away)
            dictation?.cancel()
        }
    }
    // The chat opened, or the machine is back: what waits goes, also the ones that failed before.
    LaunchedEffect(chat, online) {
        if (!online) return@LaunchedEffect
        store.notes.value.filter { it.chat == chat && !it.sent && it.failed }.forEach { n -> store.update(n.id) { it.copy(error = null) } }
        pump()
    }

    val fetched = remember(store) { File(store.dir, "fetched") }
    return VoiceMessages(
        recorder = recorder,
        dictation = dictation,
        notes = notes,
        online = online,
        player = player,
        loading = loading,
        retry = { n ->
            store.update(n.id) { it.copy(error = null) }
            pump()
        },
        refused = { failed ->
            VoiceNotes.refused(store.notes.value.filter { it.chat == chat }, failed).forEach { (n, why) ->
                store.update(n.id) { it.copy(sent = false, error = why) }
            }
        },
        discard = { n ->
            if (player.state.value?.key == n.id) player.release()
            store.drop(n.id)
        },
        play = { key, remote, note ->
            val own = note?.file?.let(::File)?.takeIf { it.exists() }
            if (own != null) {
                runCatching { player.toggle(key, own) }.onFailure { context.toast("Couldn't play it") }
            } else if (remote != null && key !in loading) {
                loading = loading + key
                scope.launch {
                    runCatching {
                        val f = File(fetched, sha1(remote) + ".m4a")
                        if (!f.exists()) {
                            val bytes = get(remote)
                            withContext(Dispatchers.IO) {
                                fetched.mkdirs()
                                // The newest few are kept, so playing one again needs no second copy.
                                fetched.listFiles()?.sortedByDescending { it.lastModified() }?.drop(FETCHED_KEEP)?.forEach { it.delete() }
                                File(fetched, f.name + ".part").apply { writeBytes(bytes) }.renameTo(f)
                            }
                        }
                        player.toggle(key, f)
                    }.onFailure { context.toast(it.message ?: "Couldn't play it") }
                    loading = loading - key
                }
            }
        },
    )
}

private const val FETCHED_KEEP = 30

private fun sha1(s: String): String =
    MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * A voice message in the open chat ([LocalVoiceMessages]): the one at [remote] on the machine, or
 * [note] still on the phone (with where it is, and Retry once it failed).
 */
@Composable
fun VoiceMessage(remote: String?, millis: Long, you: Boolean, note: VoiceNote?) {
    val voice = LocalVoiceMessages.current
    val key = note?.id ?: remote.orEmpty()
    val playing = voice?.player?.state?.collectAsState()?.value?.takeIf { it.key == key }
    val error = note?.error
    val status = when {
        note == null || note.sent -> VoiceStatus.Sent
        error != null -> VoiceStatus.Failed(error)
        voice?.online == false -> VoiceStatus.Offline
        voice != null && VoiceNotes.behindFailed(voice.notes, note) -> VoiceStatus.Behind
        else -> VoiceStatus.Sending
    }
    VoiceBubble(
        millis = note?.millis ?: millis,
        you = you,
        playing = playing?.playing == true,
        positionMs = playing?.positionMs,
        loading = voice != null && key in voice.loading,
        status = status,
        onPlay = { voice?.play?.invoke(key, remote, note) },
        onRetry = { if (note != null) voice?.retry?.invoke(note) },
        onDiscard = { if (note != null) voice?.discard?.invoke(note) },
    )
}
