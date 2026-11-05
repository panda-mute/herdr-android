package com.makefunstuff.herdr.ui.common

import androidx.compose.ui.graphics.RectangleShape

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.AppContainer
import com.makefunstuff.herdr.core.host.HostStatus
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.hairline
import com.makefunstuff.herdr.ui.design.StateBlock
import com.makefunstuff.herdr.ui.theme.Herdr

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("no container") }

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Int = 8) {
    Box(modifier.size(size.dp).background(color, RectangleShape))
}

/**
 * A small label. Neutral: dim text in a framed cell. With [color], the state colour as text on its
 * tint. Square, as everything on the workbench.
 */
@Composable
fun Pill(text: String, color: Color? = null, container: Color? = null) {
    val c = Herdr.colors
    val tone = color?.takeIf { it != MaterialTheme.colorScheme.onSurfaceVariant && it != c.muted }
    val shape = if (tone == null && container == null) HerdrShapes.Pill else HerdrShapes.Badge
    Text(
        text,
        color = tone ?: c.muted,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(shape).background(container ?: tone?.let { c.tint(it) } ?: Color.Transparent)
            .hairline(shape, if (tone == null && container == null) c.lineStrong else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** A whole screen with nothing to show: icon, title, a sentence, maybe a button, over the quiet circuit board. */
@Composable
fun EmptyState(title: String, body: String, icon: ImageVector = HerdrIcons.Inbox, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        com.makefunstuff.herdr.ui.design.CircuitBackdrop(Modifier.matchParentSize())
        StateBlock(icon, title, body, action = action)
    }
}

fun HostStatus.label(): String = when (this) {
    HostStatus.Stopped -> "Paused"
    HostStatus.Connecting -> "Connecting"
    HostStatus.Online -> "Online"
    is HostStatus.Reconnecting -> "Reconnecting in ${retryInSeconds}s"
    is HostStatus.Attention -> when (kind) {
        HostStatus.Attention.Kind.Auth -> "Key refused"
        HostStatus.Attention.Kind.HostKey -> "Host key changed"
        HostStatus.Attention.Kind.Herdr -> "herdr problem"
        HostStatus.Attention.Kind.Other -> "Can't connect"
    }
}

@Composable
@ReadOnlyComposable
fun HostStatus.color(): Color = Herdr.colors.let { c ->
    when (this) {
        // The web UI's PC dot: done = connected, working = connecting, blocked = error, idle = off.
        HostStatus.Online -> c.done
        HostStatus.Connecting, is HostStatus.Reconnecting -> c.working
        is HostStatus.Attention -> c.red
        HostStatus.Stopped -> c.idle
    }
}

fun HostStatus.detail(): String? = when (this) {
    is HostStatus.Reconnecting -> message
    is HostStatus.Attention -> message
    else -> null
}

fun Context.copyToClipboard(label: String, text: String, toast: String = "Copied") {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    com.makefunstuff.herdr.ui.design.Toasts.show(toast, error = false)
}

/** The clipboard's first text item, if it has one. */
fun Context.clipboardText(): String? {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
}

fun Context.toast(text: String) = com.makefunstuff.herdr.ui.design.Toasts.show(text)

/** Opens [url] in whatever app handles it (the browser for links). False when nothing can. */
fun Context.openUrl(url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { startActivity(intent) }
        .onFailure { toast("No app can open ${url.take(80)}") }
        .isSuccess
}
