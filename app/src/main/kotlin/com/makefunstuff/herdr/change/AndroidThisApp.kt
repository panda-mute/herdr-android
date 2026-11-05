package com.makefunstuff.herdr.change

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.makefunstuff.herdr.BuildConfig
import java.io.File

/**
 * The installed app and Android's PackageInstaller: an update installs only when it is this app, signed with the
 * same key and newer, and Android always asks the person before it does ([InstallReceiver] shows its prompt).
 */
class AndroidThisApp(private val app: Context) : ThisApp {
    override val versionName: String = BuildConfig.VERSION_NAME
    override val versionCode: Long = BuildConfig.VERSION_CODE.toLong()

    override fun refuse(apk: File): String? {
        val pm = app.packageManager
        val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return "Android can't read the update: it isn't an app"
        if (info.packageName != app.packageName) return "The update is another app (${info.packageName}), not this one"
        if (info.longVersionCode <= versionCode) return "The update (${info.versionName}) isn't newer than this app ($versionName)"
        // Where Android reads the archive's signers, they must be this app's: else it would refuse the update anyway.
        val theirs = info.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet()
        val ours = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            ?.map { it.toByteArray().toList() }?.toSet()
        if (!theirs.isNullOrEmpty() && !ours.isNullOrEmpty() && theirs != ours) {
            return "The update is signed with another key than this app, so Android won't install it over it"
        }
        return null
    }

    override fun mayInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    override fun askToInstall() {
        app.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun install(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            // The person always confirms, even where Android could skip it for an app updating itself.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("herdr-android.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val done = PendingIntent.getBroadcast(
                app, id, Intent(app, InstallReceiver::class.java).setPackage(app.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(done.intentSender)
        }
    }
}

/** PackageInstaller's answers: its prompt for the person, or why it didn't install. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val prompt = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT))
                    ?: return
                context.startActivity(prompt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> Toast.makeText(context, "Update not installed", Toast.LENGTH_SHORT).show()
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Toast.makeText(context, "Android didn't install the update" + (why?.let { ": $it" } ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }
}
