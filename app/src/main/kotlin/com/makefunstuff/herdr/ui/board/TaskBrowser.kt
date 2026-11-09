package com.makefunstuff.herdr.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.shepherdr.BrowserState
import com.makefunstuff.herdr.core.shepherdr.BrowserView
import com.makefunstuff.herdr.core.shepherdr.TaskBrowser
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.StateBlock
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.images.ImageCodec
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [t]'s Browser tab, as 3.1's: its agent's browser live through the service's relay while its agent runs. Another
 * machine's task, or one with no agent running, says why there is nothing to watch.
 */
@Composable
internal fun TaskBrowserView(session: HostSession, t: WorkTask) {
    val b = t.browser ?: return
    if (!b.ready) return TaskViewNote(HerdrIcons.Server, "On another machine", b.hint ?: "This task's browser can't be watched from here.")
    BrowserView.noAgent(t.name, t.running, t.status == WorkStatus.Done)?.let { return TaskViewNote(HerdrIcons.Globe, "No browser", it) }
    LiveBrowser(session, t)
}

/** What the phone's keyboard box holds, so its Backspace has something to take (3.1's HELD). */
private const val HELD = " "

/**
 * The page, fitted whole, with its bar under it: who drives and where it is, Hand back, Back, Reload and the keyboard.
 * The first touch or key takes it over (the relay tells the agent to hold off); a minute without input gives it back.
 * When the view stops it starts again by itself, at most every 10 s.
 */
@Composable
private fun LiveBrowser(session: HostSession, t: WorkTask) {
    val c = Herdr.colors
    val owner = LocalLifecycleOwner.current
    var state by remember(t.id) { mutableStateOf<BrowserState?>(null) }
    var stopped by remember(t.id) { mutableStateOf(false) }
    var opening by remember(t.id) { mutableStateOf(false) }
    var gone by remember(t.id) { mutableStateOf<String?>(null) }
    var picture by remember(t.id) { mutableStateOf<ImageBitmap?>(null) }
    var pageWidth by remember(t.id) { mutableStateOf(0) }
    // What goes up, in order, on the link of the moment; with no link it goes nowhere (as 3.1's), never replayed later.
    var outbox by remember(t.id) { mutableStateOf<Channel<(TaskBrowser) -> Unit>?>(null) }
    var lastInput by remember(t.id) { mutableLongStateOf(0L) }
    fun send(input: Boolean = false, m: (TaskBrowser) -> Unit) {
        val box = outbox ?: return
        if (input) lastInput = System.currentTimeMillis()
        box.trySend(m)
    }

    LaunchedEffect(t.id) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (gone == null) {
                val at = System.currentTimeMillis()
                try {
                    val link = session.awaitWorkBoard().browser(t)
                    stopped = false
                    coroutineScope {
                        // The relay's reads block: they end when the link is closed below.
                        val reader = launch(Dispatchers.IO) {
                            while (isActive) when (val e = link.next() ?: break) {
                                is TaskBrowser.Event.Frame -> ImageCodec.load(e.jpeg, 4096)?.let {
                                    picture = it
                                    pageWidth = e.width.takeIf { w -> w > 0 } ?: state?.width ?: it.width
                                }
                                is TaskBrowser.Event.State -> {
                                    state = e.state
                                    // Open browser answered: open, or why not (its error, shown with Open browser again).
                                    if (e.state.connected || e.state.error != null) opening = false
                                }
                                is TaskBrowser.Event.Gone -> gone = e.message.ifBlank { "Its browser closed with the task." }
                            }
                        }
                        val box = Channel<(TaskBrowser) -> Unit>(Channel.UNLIMITED)
                        outbox = box
                        val writer = launch(Dispatchers.IO) { for (m in box) m(link) }
                        try {
                            reader.join()
                        } finally {
                            outbox = null
                            box.close()
                            writer.cancel()
                            withContext(NonCancellable + Dispatchers.IO) { runCatching { link.close() } }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Couldn't link (the service down, the connection lost): as a view that stopped.
                }
                if (gone != null) break
                stopped = true
                delay(maxOf(500L, BrowserView.RETRY_MS - (System.currentTimeMillis() - at)))
            }
        }
    }

    val st = state
    val driving = st?.driving == true
    // A view that stopped shows its last page under its note, but takes no input until it is back.
    val live = !stopped && st?.connected == true && picture != null
    // Open browser that never comes back: its note again after a while, with Open browser.
    LaunchedEffect(opening) {
        if (opening) {
            delay(BrowserView.OPENING_MS)
            opening = false
        }
    }
    // A minute without your input while you drive: handed back (the window's).
    LaunchedEffect(driving) {
        while (driving) {
            delay(5_000)
            if (System.currentTimeMillis() - lastInput > BrowserView.IDLE_DRIVE_MS) send { it.handBack() }
        }
    }

    gone?.let { return TaskViewNote(HerdrIcons.Globe, "No browser", it) }
    val note = BrowserView.note(st, stopped, opening, live)
    val keys = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var held by remember { mutableStateOf(TextFieldValue(HELD, TextRange(HELD.length))) }
    // A word the keyboard is still composing goes where it was typed, before a touch or a button moves the page on.
    fun settle() {
        if (held.composition == null) return
        held.text.removePrefix(HELD).takeIf { it.isNotEmpty() }?.let { w -> send(input = true) { it.text(w) } }
        held = TextFieldValue(HELD, TextRange(HELD.length))
    }

    Column(Modifier.fillMaxSize()) {
        var box by remember { mutableStateOf(IntSize.Zero) }
        val pic = picture
        // The page in CSS px: its width as agent-browser says, its height from the picture.
        val pageHeight = if (pic != null && pageWidth > 0) pic.height * pageWidth / pic.width else 0
        val rect = BrowserView.fit(box.width.toFloat(), box.height.toFloat(), pageWidth.toFloat(), pageHeight.toFloat(), top = true)
        Box(
            Modifier.weight(1f).fillMaxWidth().background(c.ground).onSizeChanged { box = it }
                .semantics { contentDescription = "The task's browser" + (st?.url?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: "") }
                .pointerInput(live, rect.toList(), pageWidth, pageHeight) {
                    if (!live) return@pointerInput
                    fun at(o: Offset) = BrowserView.toPage(o.x, o.y, rect, pageWidth, pageHeight)
                    // Touches as a phone's browser sends them: the page scrolls and taps as on a phone.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val start = at(down.position) ?: return@awaitEachGesture
                        down.consume()
                        settle()
                        send(input = true) { it.touch("touchStart", listOf(start)) }
                        var moved = 0L
                        while (true) {
                            val ev = awaitPointerEvent()
                            val p = ev.changes.firstOrNull { it.id == down.id } ?: break
                            p.consume()
                            if (!p.pressed) break
                            val now = System.currentTimeMillis()
                            if (now - moved < 30) continue
                            moved = now
                            at(p.position)?.let { pt -> send(input = true) { it.touch("touchMove", listOf(pt)) } }
                        }
                        send(input = true) { it.touch("touchEnd", emptyList()) }
                    }
                },
        ) {
            if (pic != null) Canvas(Modifier.fillMaxSize()) {
                drawImage(
                    pic, dstOffset = IntOffset(rect[0].toInt(), rect[1].toInt()), dstSize = IntSize(rect[2].toInt(), rect[3].toInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
            // While you drive, the page is framed in amber (the console's own action).
            if (driving && live) Canvas(Modifier.fillMaxSize()) {
                drawRect(c.accent, Offset(rect[0], rect[1]), androidx.compose.ui.geometry.Size(rect[2], rect[3]), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
            // The box the phone's keyboard types into: out of sight, its words go to the page.
            BasicTextField(
                held,
                onValueChange = { v ->
                    // A word the keyboard is still composing goes in once it is committed.
                    if (v.composition != null) {
                        held = v
                        return@BasicTextField
                    }
                    if (!v.text.startsWith(HELD)) send(input = true) { it.key("Backspace") }
                    else v.text.removePrefix(HELD).split('\n').forEachIndexed { i, part ->
                        if (i > 0) send(input = true) { it.key("Enter") }
                        if (part.isNotEmpty()) send(input = true) { it.text(part) }
                    }
                    held = TextFieldValue(HELD, TextRange(HELD.length))
                },
                modifier = Modifier.size(1.dp).alpha(0f).focusRequester(keys),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            )
            // Over the picture, which may still hold the last page.
            if (note != null) Box(Modifier.fillMaxSize().background(c.ground), contentAlignment = Alignment.Center) {
                val title = note.title
                if (title == null) LoadingState(note.text)
                else StateBlock(
                    if (note.error) HerdrIcons.CircleAlert else HerdrIcons.Globe, title, note.text, iconTint = if (note.error) c.red else null,
                    action = if (note.open) ({ QuietButton(onClick = { opening = true; send { it.open() } }) { Text(BrowserView.OPEN) } }) else null,
                )
            }
        }

        // Its bar, as 3.1's: who drives and where it is; Hand back, Back, Reload, and the keyboard for the page.
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).background(c.ground)
                .drawBehind { drawLine(c.lineStrong, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                .padding(start = Space.screen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBadge(BrowserView.mark(live, driving), if (!live) c.idle else if (driving) c.working else c.done)
            Text(
                if (live) BrowserView.shortUrl(st?.url.orEmpty()) else "", style = MaterialTheme.typography.bodySmall, color = c.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = Space.s),
            )
            if (live) {
                if (driving) BarIcon(HerdrIcons.Bot, BrowserView.HAND_BACK, { settle(); send { it.handBack() } })
                BarIcon(HerdrIcons.ArrowLeft, BrowserView.BACK, { settle(); send(input = true) { it.back() } })
                BarIcon(HerdrIcons.RotateCw, BrowserView.RELOAD, { settle(); send(input = true) { it.reload() } })
                BarIcon(HerdrIcons.Keyboard, BrowserView.KEYBOARD, {
                    keys.requestFocus()
                    keyboard?.show()
                })
            }
        }
    }
}
