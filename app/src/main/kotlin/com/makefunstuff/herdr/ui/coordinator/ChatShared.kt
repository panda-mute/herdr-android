package com.makefunstuff.herdr.ui.coordinator

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.core.host.Images
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.StateBlock
import com.makefunstuff.herdr.ui.design.Toasts
import com.makefunstuff.herdr.ui.images.ImageCodec
import com.makefunstuff.herdr.ui.images.ImageTray
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.theme.color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Files shared to the app for a machine's Coordinator: one is being copied ([copying]); [waitNote] says what waits for it to connect. */
internal class SharedFiles(val copying: Boolean, val waitNote: String?)

/**
 * Files shared to the app for this Coordinator (a screenshot, a log): a picture goes to the composer's
 * pictures ([tray]) at once, any other file is copied over as an attached one once connected ([onFile] its path there).
 */
@Composable
internal fun rememberSharedFiles(machineId: String, session: HostSession, state: HostState, tray: ImageTray, onFile: (String) -> Unit): SharedFiles {
    val container = LocalContainer.current
    val context = LocalContext.current
    val file by androidx.compose.runtime.rememberUpdatedState(onFile)
    var copyingShared by remember { mutableStateOf(false) }
    val sharedWaiting by remember(machineId) { container.shares.files(machineId) }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(machineId) { container.shares.used(machineId) }
    LaunchedEffect(machineId, state.online) {
        val online = state.online
        container.shares.files(machineId).collect { files ->
            for (f in files) {
                if (!online && !Images.isImage(f.name)) continue
                copyingShared = true
                try {
                    // Android may have cleared the app's cache while the share waited.
                    if (!java.io.File(f.path).exists()) throw java.io.IOException("${f.name} was no longer on the phone, so it wasn't attached. Share it again.")
                    if (!sharePicture(f, tray, container.images)) {
                        // Not a picture the phone can read after all: it goes as a file.
                        if (!online) continue
                        file(withContext(Dispatchers.IO) { java.io.File(f.path).inputStream().use { session.upload(f.name, it) } })
                    }
                } catch (e: CancellationException) {
                    copyingShared = false
                    throw e
                } catch (t: Throwable) {
                    context.toast(t.message ?: "Couldn't attach ${f.name}")
                }
                container.shares.done(machineId, f)
            }
            copyingShared = false
        }
    }
    // Shared files (not pictures, which wait in the composer) that wait for this machine to connect, said where the chat shows what is going on.
    val filesWaiting = sharedWaiting.filterNot { Images.isImage(it.name) }
    val waitNote = if (state.online || filesWaiting.isEmpty()) null else {
        val one = filesWaiting.singleOrNull()
        if (one != null) "${one.name} waits to be copied to ${state.machine.label}, and is attached to your message once it connects."
        else "${filesWaiting.size} shared files wait to be copied to ${state.machine.label}, and are attached to your message once it connects."
    }
    return SharedFiles(copyingShared, waitNote)
}

/**
 * A shared file that is a picture, made smaller into [tray]: true when it is there now (or the
 * message was full, which says so), false when the phone can't read it as a picture.
 */
private suspend fun sharePicture(f: com.makefunstuff.herdr.core.share.SharedFile, tray: ImageTray, store: com.makefunstuff.herdr.data.ImageStore): Boolean {
    if (!Images.isImage(f.name)) return false
    val ready = withContext(Dispatchers.IO) { ImageCodec.prepare(java.io.File(f.path), null, f.name, store) } ?: return false
    if (!tray.add(ready)) Toasts.show("A message takes at most ${com.makefunstuff.herdr.data.ImageStore.MAX_IMAGES} pictures, so ${f.name} was left out")
    return true
}

/** The screen's own empty state: an icon, a heading and a sentence, centred in the space, with its one action when there is one. */
@Composable
internal fun ChatEmpty(icon: ImageVector, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { StateBlock(icon, title, body, action = action) }
}

@Composable
internal fun Working(who: String = "Assistant", doing: String? = null) {
    val t = rememberInfiniteTransition(label = "working")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dots")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Space.xs)) {
        StatusDot(Herdr.colors.working, Modifier.alpha(a), size = 8)
        Spacer(Modifier.width(Space.s))
        // Shepherdr's working line: what it does now, in its CLI's words, where it says.
        Text(doing?.takeIf { it.isNotBlank() } ?: "$who is working…", style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** A plain sentence on a tinted tile, in place of a raw error dump; [tint] null is neutral. */
@Composable
internal fun Notice(text: String, tint: Color?, action: Pair<String, () -> Unit>? = null) {
    Column {
        com.makefunstuff.herdr.ui.design.Notice(text, color = tint, icon = if (tint == Herdr.colors.red) HerdrIcons.CircleAlert else HerdrIcons.Info)
        if (action != null) QuietButton(onClick = action.second, color = Herdr.colors.accent) { Text(action.first) }
    }
}
