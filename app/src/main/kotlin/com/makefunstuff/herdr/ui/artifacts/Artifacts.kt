package com.makefunstuff.herdr.ui.artifacts

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.makefunstuff.herdr.core.board.WorkBoard
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.shepherdr.V2Artifact
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.hairline
import com.makefunstuff.herdr.ui.images.ImageCodec
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What artifacts sit on in their pages: Shepherdr's own `--bg`, light or dark, which they are made and checked on. */
fun shepherdrBackground(dark: Boolean): Color = if (dark) Color(0xFF0B0B0D) else Color.White

/**
 * A Shepherdr chat's artifacts on [machineId]: [all] of them (null where the machine keeps none), how a
 * card reads their pictures ([board]) and opens one ([open], at a version, or its latest for null).
 */
class ChatArtifacts(
    val machineId: String,
    val all: List<V2Artifact>?,
    val board: WorkBoard?,
    /** Shepherdr answered: [all] null then means it keeps none. */
    val read: Boolean,
    val open: (id: String, v: Int?) -> Unit,
)

/** The artifacts of the chat on screen, for its artifact cards; none outside a Shepherdr chat. */
val LocalChatArtifacts = staticCompositionLocalOf<ChatArtifacts?> { null }

/**
 * [session]'s work board and its artifacts, as Shepherdr sends them: null until it answers, and
 * [ArtifactShelf.all] null where it keeps none.
 */
class ArtifactShelf(val board: WorkBoard?, val all: List<V2Artifact>?, val problem: String?, val read: Boolean)

@Composable
fun rememberArtifactShelf(session: HostSession): ArtifactShelf {
    val state by session.state.collectAsStateWithLifecycle()
    var board by remember(session) { mutableStateOf<WorkBoard?>(null) }
    var all by remember(session) { mutableStateOf<List<V2Artifact>?>(null) }
    var read by remember(session) { mutableStateOf(false) }
    var problem by remember(session) { mutableStateOf<String?>(null) }
    LaunchedEffect(session, state.online) {
        if (!state.online) return@LaunchedEffect
        while (true) {
            try {
                val b = board ?: session.awaitWorkBoard().also { board = it }
                b.overviews().collect {
                    all = it.artifacts
                    read = true
                    problem = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                problem = t.message ?: "Couldn't reach Shepherdr"
            }
            delay(5_000)
        }
    }
    return ArtifactShelf(board, all, problem, read)
}

/**
 * [a]'s tile: Shepherdr 3 takes no pictures of a recipe, so a quiet tile with its icon and what it is (a recipe the app
 * draws, or a panel only the Herdr window runs), filling [modifier]'s box.
 */
@Composable
fun ArtifactThumb(machineId: String, board: WorkBoard?, a: V2Artifact, modifier: Modifier, icon: Dp = 20.dp) {
    val c = Herdr.colors
    Column(
        modifier.clip(HerdrShapes.Tile).background(c.card).hairline(HerdrShapes.Tile, c.line),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(HerdrIcons.LayoutDashboard, null, Modifier.size(icon), tint = c.muted)
        if (!a.drawn(a.v)) Text("Opens in the Herdr window", style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.padding(top = Space.xs))
    }
}

/**
 * An artifact posted in a Shepherdr chat, as its web UI posts it: its picture under a row with its
 * title, version and when, the whole card opening it full screen. An earlier post of one posted again
 * further down folds to a line ("v1 · newer below"), so a chat never repeats big cards; a deleted one
 * says so. Where the machine keeps no artifacts the card only names it.
 */
@Composable
fun ArtifactPost(id: String, version: Int, intro: String, later: Boolean) {
    val c = Herdr.colors
    val host = LocalChatArtifacts.current
    val known = host?.all
    val a = known?.firstOrNull { it.id == id }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (intro.isNotEmpty()) MarkdownText(intro, Modifier.padding(horizontal = Space.xs), style = MaterialTheme.typography.bodyLarge)
        when {
            known == null -> QuietCard(padded = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(HerdrIcons.LayoutDashboard, null, Modifier.size(18.dp), tint = c.muted)
                    Spacer(Modifier.width(Space.s))
                    Text("Artifact · version $version", style = MaterialTheme.typography.titleSmall, color = c.strong)
                }
                Text(
                    if (host?.read == true) "It opens in Shepherdr on the machine." else "Reading it from Shepherdr…",
                    style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(start = 26.dp),
                )
            }
            a == null -> Row(Modifier.padding(horizontal = Space.xs).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(HerdrIcons.LayoutDashboard, null, Modifier.size(16.dp), tint = c.muted)
                Spacer(Modifier.width(Space.s))
                Text("This artifact was deleted.", style = MaterialTheme.typography.bodyMedium, color = c.muted)
            }
            later -> QuietCard(onClick = { host.open(a.id, version) }, onClickLabel = "Open version $version") {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = Space.l), verticalAlignment = Alignment.CenterVertically) {
                    Icon(HerdrIcons.LayoutDashboard, null, Modifier.size(16.dp), tint = c.muted)
                    Spacer(Modifier.width(Space.s))
                    Text(a.title, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(Space.s))
                    Text("v$version · newer below", style = MaterialTheme.typography.labelMedium, color = c.muted, maxLines = 1)
                }
            }
            else -> QuietCard(
                Modifier.padding(end = 32.dp),
                onClick = { host.open(a.id, null) }, onClickLabel = "Open full screen",
            ) {
                Row(Modifier.fillMaxWidth().padding(start = Space.l, end = Space.m, top = Space.m, bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Icon(HerdrIcons.LayoutDashboard, null, Modifier.size(16.dp), tint = c.muted)
                    Spacer(Modifier.width(Space.s))
                    Text(a.title, style = MaterialTheme.typography.titleMedium, color = c.strong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(Space.s))
                    Text(listOf("v${a.v}", a.`when`).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = c.muted, maxLines = 1)
                    Icon(HerdrIcons.Maximize2, null, Modifier.padding(start = Space.s).size(16.dp), tint = c.muted)
                }
                ArtifactThumb(
                    host.machineId, host.board, a,
                    Modifier.fillMaxWidth().padding(start = Space.s, end = Space.s, bottom = Space.s).height(200.dp),
                    icon = 28.dp,
                )
            }
        }
    }
}
