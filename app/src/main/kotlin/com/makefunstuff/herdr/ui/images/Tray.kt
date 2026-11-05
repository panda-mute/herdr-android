package com.makefunstuff.herdr.ui.images

import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.data.ImageStore
import com.makefunstuff.herdr.data.TrayImage
import com.makefunstuff.herdr.ui.common.Attacher
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.Spinner
import com.makefunstuff.herdr.ui.design.hairline
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The pictures going with a chat's next message ([ImageStore], under the chat's draft key): each
 * is copied to the chat's machine as soon as it is added, or once the machine connects, one at a
 * time, and the message names them when it is sent ([message]). Send waits while one is still on
 * its way ([waiting]); one that couldn't be copied says why and is tried again with a tap.
 */
class ImageTray internal constructor(
    val images: List<TrayImage>,
    /** The picture being copied now. */
    val uploading: String?,
    /** Why a picture couldn't be copied, by its id. */
    val failed: Map<String, String>,
    private val key: String,
    private val store: ImageStore,
    private val onRetry: (String) -> Unit,
) {
    /** Pictures not yet on the machine: Send waits for them. */
    val waiting: Boolean get() = images.any { it.remote == null }
    val isEmpty: Boolean get() = images.isEmpty()
    val isFull: Boolean get() = images.size >= ImageStore.MAX_IMAGES

    /** Adds a picture; false when the message has as many as it can take. */
    fun add(image: TrayImage): Boolean = store.add(key, image)
    fun remove(id: String) = store.remove(key, id)
    fun replace(id: String, edited: TrayImage) = store.replace(key, id, edited)
    fun retry(id: String) = onRetry(id)

    /** [typed] with the pictures named in front, as the message goes. */
    fun message(typed: String): String = store.message(key, typed)

    /** The message with [sent] in it went: they leave the composer. */
    fun sent(sent: List<TrayImage>) = store.sent(key, sent)
}

/**
 * The composer's pictures for the chat under [key] on [machineId], copied there through [session]
 * while it is [online].
 */
@Composable
fun rememberImageTray(key: String, machineId: String, session: HostSession, online: Boolean): ImageTray {
    val store = LocalContainer.current.images
    val images by remember(key) { store.tray(key) }.collectAsState(store.current(key))
    var failed by remember(key) { mutableStateOf(emptyMap<String, String>()) }
    var uploading by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key, machineId, online) {
        if (!online) return@LaunchedEffect
        while (true) {
            // The next picture not yet on the machine (and not one that failed until it is tapped again).
            val (list, bad) = combine(store.tray(key), snapshotFlow { failed }) { l, f -> l to f }
                .first { (l, f) -> l.any { it.remote == null && it.id !in f } }
            val next = list.first { it.remote == null && it.id !in bad }
            uploading = next.id
            try {
                val remote = withContext(Dispatchers.IO) { File(next.path).inputStream().use { session.upload(next.name, it) } }
                withContext(Dispatchers.IO) { store.uploaded(key, next.id, machineId, remote, next.path) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                failed = failed + (next.id to (t.message ?: "Couldn't copy ${next.name}"))
            } finally {
                uploading = null
            }
        }
    }
    return ImageTray(images, uploading, failed.filterKeys { id -> images.any { it.id == id } }, key, store) { id -> failed = failed - id }
}

/**
 * The composer's pictures, in a row over the message box: a thumbnail of each, which opens it
 * ([onOpen]: to look at it, crop it or draw on it), with a × that takes it out. One being copied
 * shows a spinner; one that couldn't be copied shows a red mark, and a tap tries it again.
 */
@Composable
fun TrayRow(tray: ImageTray, onOpen: (TrayImage) -> Unit, modifier: Modifier = Modifier) {
    if (tray.isEmpty) return
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = Space.m, end = Space.m, top = Space.m),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        tray.images.forEachIndexed { i, image ->
            val failed = tray.failed[image.id]
            TrayThumb(
                image,
                label = "Picture ${i + 1} of ${tray.images.size}, ${image.name}",
                state = when {
                    failed != null -> "Couldn't copy it: $failed. Tap to try again"
                    image.remote == null -> if (tray.uploading == image.id) "Copying to the machine" else "Waits to be copied"
                    else -> null
                },
                busy = tray.uploading == image.id,
                waits = image.remote == null && failed == null && tray.uploading != image.id,
                failed = failed != null,
                onClick = { if (failed != null) tray.retry(image.id) else onOpen(image) },
                onRemove = { tray.remove(image.id) },
            )
        }
    }
}

@Composable
private fun TrayThumb(
    image: TrayImage,
    label: String,
    state: String?,
    busy: Boolean,
    waits: Boolean,
    failed: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val c = Herdr.colors
    val bitmap = rememberImage(image.path, THUMB_EDGE)
    // The × sits on the tile's corner, inside the 72dp it takes, with a 32dp target.
    Box(Modifier.size(TILE)) {
        Box(
            Modifier.padding(top = 6.dp, end = 6.dp).size(TILE - 6.dp).align(Alignment.BottomStart)
                .clip(HerdrShapes.Tile).background(c.hover).hairline(HerdrShapes.Tile)
                .clickable(role = Role.Button, onClickLabel = if (failed) "Try again" else "Open", onClick = onClick)
                .semantics {
                    contentDescription = label
                    if (state != null) stateDescription = state
                },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(HerdrIcons.Image, null, tint = c.muted, modifier = Modifier.size(20.dp))
            // Being copied: a spinner; waiting for its turn or for the machine: a clock; couldn't be: a red mark.
            if (busy || waits || failed) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    when {
                        failed -> Icon(HerdrIcons.CircleAlert, null, tint = c.red, modifier = Modifier.size(22.dp))
                        busy -> Spinner(size = 20.dp)
                        else -> Icon(HerdrIcons.Clock, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).size(32.dp)
                .clip(RectangleShape).clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "Remove ${image.name}" }
                .padding(start = 6.dp, bottom = 6.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(
                Modifier.size(22.dp).clip(RectangleShape).background(c.strong).hairline(RectangleShape, c.ground),
                contentAlignment = Alignment.Center,
            ) { Icon(HerdrIcons.X, null, tint = c.ground, modifier = Modifier.size(13.dp)) }
        }
    }
}

private val TILE = 72.dp
private const val THUMB_EDGE = 256

/** The composer's pictures ([TrayRow]) and the editor a tap on one opens ([ImageEditor]). */
@Composable
fun ComposerTray(tray: ImageTray) {
    var editing by remember { mutableStateOf<String?>(null) }
    TrayRow(tray, onOpen = { editing = it.id })
    val image = tray.images.firstOrNull { it.id == editing } ?: return
    ImageEditor(
        image,
        onSave = { edited -> tray.replace(image.id, edited); editing = null },
        onRemove = { tray.remove(image.id); editing = null },
        onDismiss = { editing = null },
    )
}

/**
 * What the composer's attach button offers: a new photo, pictures from the gallery, the newest
 * screenshot (each shown in the composer before it goes, to crop or mark up), or any file, which
 * is copied at once and named in the message. A file needs the machine connected; pictures wait.
 */
@Composable
fun AttachMenu(attacher: Attacher, tray: ImageTray, online: Boolean, onDismiss: () -> Unit) {
    val full = tray.isFull
    HerdrMenu(
        true, onDismiss, title = "Attach",
        subtitle = if (full) "This message has ${ImageStore.MAX_IMAGES} pictures, as many as one can take" else "Pictures are made smaller, and shown here before they go",
    ) {
        MenuRow("Take a photo", HerdrIcons.Camera, { onDismiss(); attacher.camera() }, enabled = !full)
        MenuRow("Photos", HerdrIcons.Images, { onDismiss(); attacher.photos() }, enabled = !full)
        MenuRow("Latest screenshot", HerdrIcons.Smartphone, { onDismiss(); attacher.screenshot() }, enabled = !full)
        MenuRow(if (online) "File" else "File (once connected)", HerdrIcons.File, { onDismiss(); attacher.pick() }, enabled = online)
    }
}
