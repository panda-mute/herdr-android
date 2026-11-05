package preview

import com.makefunstuff.herdr.BuildConfig
import com.makefunstuff.herdr.change.ThisApp
import java.io.File

/** The installed app in the previews: the shim's fixed version, every update accepted, nothing installed. */
object PreviewThisApp : ThisApp {
    override val versionName = BuildConfig.VERSION_NAME
    override val versionCode = BuildConfig.VERSION_CODE.toLong()
    override fun refuse(apk: File): String? = null
    override fun mayInstall() = true
    override fun askToInstall() = Unit
    override fun install(apk: File) = Unit
}
