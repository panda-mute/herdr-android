package com.makefunstuff.herdr.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

/** By window, how many shown screens asked for [SecureWindow], so the last one gone clears it, not the first. */
private val holders = WeakHashMap<Window, Int>()

/**
 * While this is shown, the app's window is secure (`FLAG_SECURE`): no screenshot, screen recording, cast or recent-apps
 * thumbnail shows it. For a box a secret is typed in. False where there is no window to secure (not in an activity):
 * then the box isn't shown.
 */
@Composable
fun SecureWindow(): Boolean {
    val window = LocalContext.current.activity()?.window ?: return false
    DisposableEffect(window) {
        val n = holders[window] ?: 0
        holders[window] = n + 1
        if (n == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            val left = (holders[window] ?: 1) - 1
            if (left > 0) holders[window] = left
            else {
                holders.remove(window)
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
    return true
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
