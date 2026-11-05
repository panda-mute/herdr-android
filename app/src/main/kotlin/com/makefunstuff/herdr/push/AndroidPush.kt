package com.makefunstuff.herdr.push

import android.Manifest
import android.app.BroadcastOptions
import android.app.NotificationManager
import android.app.UiModeManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.makefunstuff.herdr.HerdrApp
import com.makefunstuff.herdr.core.push.PhoneQuiet
import java.util.UUID

/**
 * UnifiedPush (https://unifiedpush.org/developers/spec/android/), as Puggie does it: a push app on the
 * phone, such as ntfy, gives the app an address; machines post to it; the push app hands each post
 * here. No library: the spec is a handful of broadcasts.
 */
object UnifiedPush {
    private const val REGISTER = "org.unifiedpush.android.distributor.REGISTER"
    private const val UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
    private const val ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"
    const val NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
    const val MESSAGE = "org.unifiedpush.android.connector.MESSAGE"
    const val FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"
    const val UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
    const val NTFY = "io.heckel.ntfy"

    private fun prefs(context: Context) = context.getSharedPreferences("unifiedpush", Context.MODE_PRIVATE)

    /** The token the push app knows this app by; only broadcasts carrying it are taken. */
    fun token(context: Context): String = prefs(context).getString("token", null) ?: renew(context)

    fun renew(context: Context): String = UUID.randomUUID().toString().also { prefs(context).edit().putString("token", it).apply() }

    fun distributors(context: Context): List<String> =
        context.packageManager.queryBroadcastReceivers(Intent(REGISTER), 0).map { it.activityInfo.packageName }
            .filter { it != context.packageName }.distinct().sortedBy { if (it == NTFY) 0 else 1 }

    fun register(context: Context, store: PushStore): Boolean {
        val all = distributors(context)
        val pkg = store.state.value.distributor?.takeIf { it in all } ?: all.firstOrNull() ?: return false
        store.change { it.copy(distributor = pkg) }
        send(context, pkg, Intent(REGISTER).putExtra("token", token(context)).putExtra("application", context.packageName)
            .putExtra("message", "Tells you when an agent or a board task needs you"))
        return true
    }

    fun unregister(context: Context, store: PushStore) {
        store.state.value.distributor?.let { send(context, it, Intent(UNREGISTER).putExtra("token", token(context)).putExtra("application", context.packageName)) }
        store.change { it.copy(endpoint = null) }
    }

    fun ack(context: Context, pkg: String?, id: String) {
        pkg?.let { send(context, it, Intent(ACK).putExtra("token", token(context)).putExtra("id", id)) }
    }

    /** Sends to the push app with the app's identity: shared with the broadcast on Android 14+, a PendingIntent before. */
    private fun send(context: Context, pkg: String, intent: Intent) {
        intent.setPackage(pkg)
        if (Build.VERSION.SDK_INT >= 34) {
            context.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
        } else {
            intent.putExtra("pi", PendingIntent.getBroadcast(context, 0, Intent("org.unifiedpush.dummy_app"), PendingIntent.FLAG_IMMUTABLE))
            context.sendBroadcast(intent)
        }
    }
}

/** What the push app sends: an address, a push, or that it gave up. */
class PushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getStringExtra("token") != UnifiedPush.token(context)) return
        val container = (context.applicationContext as HerdrApp).container
        val store = container.push
        intent.getStringExtra("id")?.let { UnifiedPush.ack(context, store.state.value.distributor, it) }
        when (intent.action) {
            UnifiedPush.NEW_ENDPOINT -> {
                val endpoint = intent.getStringExtra("endpoint")?.takeIf { (it.startsWith("https://") || it.startsWith("http://")) && it.length <= 1000 }
                if (store.state.value.on) store.change { it.copy(endpoint = endpoint, pushProblem = null) }
            }
            UnifiedPush.MESSAGE -> {
                val bytes = intent.getByteArrayExtra("bytesMessage") ?: intent.getStringExtra("message")?.toByteArray()
                if (bytes != null && store.state.value.on) container.alertWork.received(bytes)
            }
            UnifiedPush.FAILED -> {
                UnifiedPush.renew(context)
                store.change { it.copy(endpoint = null, pushProblem = intent.getStringExtra("message") ?: "The push app refused to register") }
            }
            UnifiedPush.UNREGISTERED -> store.change { it.copy(endpoint = null) }
        }
    }
}

/** [PushPhone] on Android. */
class AndroidPhone(private val context: Context, private val store: PushStore) : PushPhone {
    override val name: String get() = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    override fun distributors(): List<String> = UnifiedPush.distributors(context)

    override fun label(pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    override fun register(): Boolean = UnifiedPush.register(context, store)

    override fun unregister() = UnifiedPush.unregister(context, store)

    override fun notificationsAllowed(): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    override fun openNotificationSettings() {
        val direct = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        runCatching { context.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun liveAllowed(): Boolean? {
        if (Build.VERSION.SDK_INT < 36) return null
        return runCatching { context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications() }.getOrNull()
    }

    override fun openLiveSettings() {
        val direct = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        runCatching { context.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { openNotificationSettings() }
    }

    override fun getPushApp() {
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${UnifiedPush.NTFY}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(store) }.onFailure {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ntfy.sh/docs/subscribe/phone/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    override fun planChecks() = Wakes.plan(context)

    override fun quietMode(): PhoneQuiet? {
        if (context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_CAR) return PhoneQuiet.Driving
        // Bedtime and Driving, as Android's modes, turn on Do Not Disturb in their own way: anything but "all".
        return when (context.getSystemService(NotificationManager::class.java).currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY, NotificationManager.INTERRUPTION_FILTER_ALARMS,
            NotificationManager.INTERRUPTION_FILTER_NONE -> PhoneQuiet.DoNotDisturb
            else -> null
        }
    }

    override fun planQuiet(at: Long?) = QuietWakes.plan(context, at)
}
