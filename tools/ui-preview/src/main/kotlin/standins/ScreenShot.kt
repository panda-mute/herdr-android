// Stand-in for app/…/ui/change/ScreenShot.kt (Android's PixelCopy of the window): the README's board screenshot.
package com.makefunstuff.herdr.ui.change

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
fun rememberScreenShot(): suspend () -> ByteArray? = remember { { preview.Demo.picture("board").readBytes() } }
