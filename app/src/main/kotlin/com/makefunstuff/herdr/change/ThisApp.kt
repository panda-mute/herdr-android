package com.makefunstuff.herdr.change

import java.io.File

/**
 * The installed app as Android knows it, and its installer: what an update fetched from the builder is checked
 * against, and how it is installed (`AndroidThisApp`; the preview's stands still).
 */
interface ThisApp {
    val versionName: String
    val versionCode: Long

    /** Why [apk] can't update this app (another app, another key, not newer), or null when it can. */
    fun refuse(apk: File): String?

    /** Android lets the app install updates (Settings › Install unknown apps). */
    fun mayInstall(): Boolean

    /** Opens that setting for the person. */
    fun askToInstall()

    /** Hands [apk] to Android's installer, which asks the person before it installs. */
    fun install(apk: File)
}
