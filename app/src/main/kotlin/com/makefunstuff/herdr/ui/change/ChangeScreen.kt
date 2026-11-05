package com.makefunstuff.herdr.ui.change

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.core.change.AppChange
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.HerdrSheet
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.LocalScreenChange
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.ScreenChange
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.SheetTitle
import com.makefunstuff.herdr.ui.design.hairline
import com.makefunstuff.herdr.ui.images.ImageCodec
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The screen a change is about, its picture (PNG bytes, null where none could be taken) and that picture shown. */
private class Asked(val screen: AppChange.Screen, val shot: ByteArray?, val picture: ImageBitmap?)

/**
 * Gives the screens "Change this screen" ([LocalScreenChange]): a long press on a bar's title or the ⋯ menus' row
 * ([ChangeScreenRow]) takes the app's own picture of the screen and opens [ChangeSheet] about it.
 */
@Composable
fun ScreenChangeHost(nav: NavHostController, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val shoot = rememberScreenShot()
    var asked by remember { mutableStateOf<Asked?>(null) }
    val change = remember {
        ScreenChange(
            open = {
                if (asked == null) scope.launch {
                    val shot = runCatching { shoot() }.getOrNull()
                    val picture = shot?.let { withContext(Dispatchers.Default) { ImageCodec.load(it, 900) } }
                    asked = Asked(AppChange.Screen("", nav.currentDestination?.route), shot, picture)
                }
            },
        )
    }
    CompositionLocalProvider(LocalScreenChange provides change) { content() }
    asked?.let { a ->
        ChangeSheet(nav, AppChange.Screen(change.title, a.screen.route), a.shot, a.picture, onDismiss = { asked = null })
    }
}

/** "Change this screen" in a screen's ⋯ menu; [onDone] closes the menu. Nothing where the app gives no such action. */
@Composable
fun ChangeScreenRow(onDone: () -> Unit) {
    val change = LocalScreenChange.current ?: return
    MenuRow("Change this screen", HerdrIcons.WandSparkles, onClick = {
        onDone()
        change.open()
    })
}

/**
 * What to change about [screen]: its picture, the person's words, and where they go (the herdr-android space's
 * Assistant on the machine that builds the app). Send copies the picture there and opens that chat.
 */
@Composable
private fun ChangeSheet(nav: NavHostController, screen: AppChange.Screen, shot: ByteArray?, picture: ImageBitmap?, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val changes = container.changes
    val scope = rememberCoroutineScope()
    var words by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    /** The builder and its herdr-android space, once found; [looked] when the look is over. */
    var target by remember { mutableStateOf<Pair<String, WorkSpace>?>(null) }
    var looked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            val id = changes.builder()
            val board = id?.let { container.hosts.session(it)?.workBoardOrNull(10_000) }
            val space = board?.let { AppChange.space(it.overview().spaces) }
            if (id != null && space != null) target = id to space
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            problem = t.message
        }
        looked = true
    }
    val machine = target?.first?.let { id -> container.hosts.session(id)?.machine?.label }
    val c = Herdr.colors
    HerdrSheet(onDismiss) {
        SheetTitle("Change this screen", listOfNotNull(screen.title.ifBlank { null }, machine?.let { "to herdr-android's Assistant on $it" }).joinToString(" · "))
        Column(Modifier.padding(horizontal = Space.xl).padding(bottom = Space.l).imePadding(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Box(Modifier.width(96.dp).height(208.dp).hairline(HerdrShapes.Field, c.lineStrong), contentAlignment = Alignment.Center) {
                    if (picture != null) Image(picture, "The screen as it is now", Modifier.padding(1.dp), contentScale = ContentScale.Fit)
                    else Text("No picture", style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(
                        "Its Assistant makes it a task there, with before and after shots for you to accept; then the app updates from that machine.",
                        style = MaterialTheme.typography.bodyMedium, color = c.muted,
                    )
                    screen.route?.let { Text(it, style = CodeStyle.copy(fontSize = 11.sp), color = c.faint, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    Text("This app: ${changes.app.versionName}", style = MaterialTheme.typography.bodySmall, color = c.faint)
                }
            }
            HerdrField(
                words, { words = it }, label = "What should change?", minLines = 3, maxLines = 6,
                placeholder = { Text("Show each task's age on its row") },
            )
            when {
                !looked -> LoadingState("Finding the machine that builds the app")
                target == null -> Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(
                        problem ?: "No machine with the herdr-android space builds the app yet. Pick one in Settings › Change the app.",
                        style = MaterialTheme.typography.bodyMedium, color = c.text,
                    )
                    SecondaryButton("Change the app settings") {
                        onDismiss()
                        nav.navigate(Routes.CHANGE_APP)
                    }
                }
                else -> problem?.let { ErrorMessage(it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(
                    onClick = {
                        val (id, space) = target ?: return@PrimaryButton
                        sending = true
                        problem = null
                        scope.launch {
                            try {
                                val session = container.hosts.session(id) ?: error("That machine was taken out of the app")
                                val path = shot?.let { bytes -> withContext(Dispatchers.IO) { session.upload(shotName(screen), bytes.inputStream()) } }
                                val board = session.awaitWorkBoard()
                                board.say(space.id, AppChange.firstMessage(screen, changes.app.versionName, changes.app.versionCode, words, path))
                                changes.sent(id)
                                onDismiss()
                                nav.navigate(Routes.coordinator(id, space.id))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                problem = t.message ?: "Couldn't send it"
                            } finally {
                                sending = false
                            }
                        }
                    },
                    enabled = target != null && words.isNotBlank() && !sending,
                ) {
                    Icon(HerdrIcons.Send, null, Modifier.size(16.dp))
                    Text(if (sending) "Sending…" else "Send")
                }
                QuietButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
}

/** The picture's file name there: the screen's route, as a name (`screen-board.png`). */
private fun shotName(screen: AppChange.Screen): String =
    "screen-" + (screen.route ?: "app").substringBefore('/').substringBefore('?').filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "app" } + ".png"
