package com.makefunstuff.herdr.ui.images

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.host.ImageSizing
import com.makefunstuff.herdr.data.ImageStore
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.Spinner
import com.makefunstuff.herdr.ui.design.hairline
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Pictures read from the phone's files, smallest first out: the chat scrolls back over the same few. */
private object Decoded {
    private val cache = object : LinkedHashMap<String, ImageBitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > 48
    }

    fun key(file: File, maxEdge: Int) = "${file.path}|${file.lastModified()}|$maxEdge"
    @Synchronized fun get(key: String): ImageBitmap? = cache[key]
    @Synchronized fun put(key: String, image: ImageBitmap) { cache[key] = image }
}

/** The picture at [path] on the phone, at most [maxEdge] on its long side; null while it is read, or if it can't be. */
@Composable
fun rememberImage(path: String?, maxEdge: Int): ImageBitmap? {
    val file = path?.let(::File)
    val key = file?.let { Decoded.key(it, maxEdge) }
    val image by produceState(key?.let(Decoded::get), key) {
        if (file == null || key == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) { ImageCodec.load(file, maxEdge) }?.also { Decoded.put(key, it) }
    }
    return image
}

/**
 * Where a chat's pictures come from: the machine its messages went to. A picture sent from this
 * phone is kept on it ([ImageStore.sentFile]); any other one named in a message is read back from
 * the machine once and kept as well.
 */
class ChatImages(val machineId: String, val session: HostSession, val store: ImageStore, val online: Boolean)

/** The chat being shown, for its messages' pictures; null shows them by name only. */
val LocalChatImages = staticCompositionLocalOf<ChatImages?> { null }

private sealed interface Found {
    data object Looking : Found
    data class Here(val file: File) : Found
    data class Gone(val why: String) : Found
}

@Composable
private fun rememberSent(images: ChatImages?, remote: String): Found {
    val found by produceState<Found>(Found.Looking, images?.machineId, remote, images?.online) {
        if (images == null) { value = Found.Gone("Not on this phone"); return@produceState }
        val kept = images.store.sentFile(images.machineId, remote)
        if (kept.exists()) { value = Found.Here(kept); return@produceState }
        if (!images.online) { value = Found.Gone("Shown once ${images.session.machine.label} connects"); return@produceState }
        value = try {
            val bytes = images.session.fetchImage(remote)
            Found.Here(withContext(Dispatchers.IO) { images.store.keepSent(images.machineId, remote, bytes) })
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Found.Gone(t.message ?: "Couldn't read it")
        }
    }
    return found
}

/**
 * The pictures a message names ([paths] on the chat's machine), as thumbnails in your chat turn:
 * one picture large, several in a grid. A tap shows it full size.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageImages(paths: List<String>, modifier: Modifier = Modifier) {
    if (paths.isEmpty()) return
    val images = LocalChatImages.current
    var open by remember { mutableStateOf<String?>(null) }
    // Decoded at the pixels they are shown at (as Alma does): a tall screenshot fit to 720px and
    // stretched to a 300dp tile on a dense screen drew its text soft and grainy.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val singleEdge = with(density) { 300.dp.roundToPx() }
    // A grid square crops the picture's short side to 88dp: its long side can be twice that and more.
    val gridEdge = with(density) { 88.dp.roundToPx() } * 3
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        paths.forEachIndexed { i, remote ->
            val found = rememberSent(images, remote)
            val name = remote.substringAfterLast('/')
            val single = paths.size == 1
            val bitmap = rememberImage((found as? Found.Here)?.file?.path, if (single) singleEdge else gridEdge)
            // One picture as it is shaped, within 220×300dp; several as a grid of squares.
            val tile = when {
                !single -> Modifier.size(88.dp)
                bitmap != null -> {
                    val scale = minOf(220f / bitmap.width, 300f / bitmap.height)
                    Modifier.size((bitmap.width * scale).coerceAtLeast(64f).dp, (bitmap.height * scale).coerceAtLeast(64f).dp)
                }
                else -> Modifier.size(160.dp, 120.dp)
            }
            Box(
                tile.clip(HerdrShapes.Tile).background(Herdr.colors.hover).hairline(HerdrShapes.Tile)
                    .clickable(enabled = found is Found.Here, role = Role.Image, onClickLabel = "Show full size") { open = remote }
                    .semantics { contentDescription = "Picture ${i + 1} of ${paths.size}, $name" + ((found as? Found.Gone)?.let { ". ${it.why}" } ?: "") },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    bitmap != null -> Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium)
                    found is Found.Gone -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(Space.xs)) {
                        Icon(HerdrIcons.Image, null, tint = Herdr.colors.muted, modifier = Modifier.size(20.dp))
                        Text(name, style = MaterialTheme.typography.labelSmall, color = Herdr.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    else -> Spinner(size = 18.dp)
                }
            }
        }
    }
    val shown = open
    if (shown != null) {
        val file = images?.store?.sentFile(images.machineId, shown)
        ImageViewer(file?.path, shown.substringAfterLast('/'), onDismiss = { open = null })
    }
}

/**
 * A picture full size, over everything: pinch or double-tap to zoom in, drag to look around, the
 * close button or Back to leave.
 */
@Composable
fun ImageViewer(path: String?, name: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val image = rememberImage(path, ImageSizing.MAX_EDGE)
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        LaunchedEffect(path) { scale = 1f; offset = Offset.Zero }
        Box(Modifier.fillMaxSize().background(Herdr.colors.ground)) {
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(path) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(path) {
                        detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f })
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (image != null) {
                    Image(
                        image, name,
                        Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                        contentScale = ContentScale.Fit,
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                    )
                } else Spinner()
            }
            Row(
                Modifier.fillMaxWidth().background(Herdr.colors.panel.copy(alpha = 0.92f)).statusBarsPadding()
                    .padding(horizontal = Space.xs, vertical = Space.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarIcon(HerdrIcons.X, "Close", onDismiss)
                Spacer(Modifier.size(Space.xs))
                Text(
                    name, style = MaterialTheme.typography.titleMedium, color = Herdr.colors.strong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                image?.let {
                    Text("${it.width} × ${it.height}", style = MaterialTheme.typography.labelMedium, color = Herdr.colors.muted, modifier = Modifier.padding(end = Space.m))
                }
            }
        }
    }
}
