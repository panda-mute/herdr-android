// Stand-in for app/…/ui/common/SecureWindow.kt, which sets Android's FLAG_SECURE on the app's window: the desktop
// render has no such window, so it does nothing here and says the box may show.
package com.makefunstuff.herdr.ui.common

import androidx.compose.runtime.Composable

@Composable
fun SecureWindow(): Boolean = true
