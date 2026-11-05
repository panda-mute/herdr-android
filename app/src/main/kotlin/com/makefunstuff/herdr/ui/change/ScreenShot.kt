package com.makefunstuff.herdr.ui.change

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * The app's own window as it is on screen now, as a PNG, for "Change this screen": Android's PixelCopy of the
 * activity's window. Sheets and menus are windows of their own, so the ⋯ menu that asked isn't in it.
 */
@Composable
fun rememberScreenShot(): suspend () -> ByteArray? {
    val view = LocalView.current
    return remember(view) { { capture(view.context) } }
}

private suspend fun capture(context: Context): ByteArray? {
    val window = context.activity()?.window ?: return null
    val decor = window.decorView
    if (decor.width <= 0 || decor.height <= 0) return null
    val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
    val copied = suspendCancellableCoroutine { done ->
        runCatching {
            PixelCopy.request(window, bitmap, { result -> if (done.isActive) done.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
        }.onFailure { if (done.isActive) done.resume(false) }
    }
    if (!copied) return null
    return withContext(Dispatchers.Default) {
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
