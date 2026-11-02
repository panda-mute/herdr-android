@file:Suppress("unused", "UNUSED_PARAMETER")

package android.view

import android.content.Context
import androidx.compose.runtime.mutableLongStateOf

/**
 * A View the preview can draw: [android.view.View] subclasses in the preview's stand-ins draw
 * themselves in Compose ([androidx.compose.ui.viewinterop.AndroidView] calls [Content]).
 */
open class View(val context: Context) {
    var width = 0
        internal set
    var height = 0
        internal set

    /** Ticks on [invalidate], so a Compose drawing of the view reads it and redraws. */
    val drawRevision = mutableLongStateOf(0L)
    fun invalidate() { drawRevision.longValue++ }
    fun performHapticFeedback(constant: Int): Boolean = true
    /** Keeps the screen on while the view shows (the terminal's Keep screen on). */
    var keepScreenOn = false
    var isFocusable = true
    var isFocusableInTouchMode = true

    @androidx.compose.runtime.Composable
    open fun Content(modifier: androidx.compose.ui.Modifier) {}

    fun setSize(w: Int, h: Int) {
        if (w == width && h == height) return
        val ow = width; val oh = height
        width = w; height = h
        onSizeChanged(w, h, ow, oh)
    }

    protected open fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {}
}

object HapticFeedbackConstants {
    const val CONFIRM = 16
    const val KEYBOARD_TAP = 3
}
