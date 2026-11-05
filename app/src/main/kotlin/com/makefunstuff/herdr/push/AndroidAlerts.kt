package com.makefunstuff.herdr.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.makefunstuff.herdr.MainActivity
import com.makefunstuff.herdr.R
import com.makefunstuff.herdr.bubble.BubbleChat
import com.makefunstuff.herdr.bubble.FloatActivity
import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.AlertAction
import com.makefunstuff.herdr.guard.ConfirmActivity
import com.makefunstuff.herdr.core.push.QuietSummary
import kotlinx.serialization.json.Json

/**
 * [AlertSink] on Android: notifications with their buttons, on two channels. With the app lock on
 * ([appLock]), a locked phone shows only that something needs you: no question, no command, no buttons.
 */
class AndroidAlerts(private val context: Context, private val appLock: () -> Boolean = { false }) : AlertSink {
    private val nm = NotificationManagerCompat.from(context)

    init {
        val system = context.getSystemService(NotificationManager::class.java)
        // The one-shot watches became a space's Notify: All, and their channel went with them.
        system.deleteNotificationChannel(WATCHES)
        system.createNotificationChannels(
            listOf(
                NotificationChannel(NEEDS_YOU, "Needs you", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "An agent asks something, or a machine runs low"
                },
                NotificationChannel(FINISHED, "Finished", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "An agent finished its work"
                },
                NotificationChannel(WENT_WRONG, "Went wrong", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A review or a check failed"
                },
                NotificationChannel(LIMITS, "Limits", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "An AI provider near or at a limit"
                },
                NotificationChannel(WORKING, "Working", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "While the app answers from a notification or looks at your machines"
                    setShowBadge(false)
                },
            ),
        )
    }

    override fun show(alert: Alert, quiet: Boolean, note: String?) {
        val b = base(alert, quiet || note != null)
            .setContentText(note ?: alert.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(note, alert.detail.ifEmpty { alert.text }).joinToString("\n\n")))
        alert.actions.take(3).forEachIndexed { i, a ->
            val action = NotificationCompat.Action.Builder(0, a.label, actionIntent(context, alert, i))
            if (a.reply) {
                action.addRemoteInput(RemoteInput.Builder(WorkService.REPLY).setLabel(a.hint.ifEmpty { a.label }).build())
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setAllowGeneratedReplies(false)
            }
            b.addAction(action.build())
        }
        // An agent's chat floats over other apps as a bubble, where a button is still free (Android shows three).
        if (alert.paneId.isNotEmpty() && alert.actions.size < 3 && Build.VERSION.SDK_INT >= 30) {
            b.addAction(0, "Float chat", floatIntent(context, BubbleChat.Agent(alert.machineId, alert.paneId), alert.agentName, alert.tag))
        }
        post(alert.tag, b)
    }

    override fun working(alert: Alert, words: String) {
        post(alert.tag, base(alert, quiet = true).setContentText(words).setProgress(0, 0, true).setOngoing(true))
    }

    override fun finished(alert: Alert, words: String) {
        post(alert.tag, base(alert, quiet = true).setContentText(words).setStyle(NotificationCompat.BigTextStyle().bigText(words)).setTimeoutAfter(6_000))
    }

    override fun summary(held: List<Alert>, summary: QuietSummary, quiet: Boolean) {
        val style = NotificationCompat.InboxStyle().setBigContentTitle(summary.title)
        summary.lines.forEach { style.addLine(it) }
        val open = Intent(context, MainActivity::class.java)
            .setData(Uri.parse("herdr://alert/$SUMMARY"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val b = NotificationCompat.Builder(context, FINISHED)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFF0A830.toInt())
            .setContentTitle(summary.title)
            .setContentText(summary.text)
            .setStyle(style)
            .setNumber(held.size)
            .setContentIntent(PendingIntent.getActivity(context, SUMMARY.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .setSilent(quiet)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        post(SUMMARY, b)
    }

    override fun cancel(tag: String) = nm.cancel(tag, ID)

    private fun base(alert: Alert, quiet: Boolean): NotificationCompat.Builder {
        val urgent = alert.kind != Alert.Kind.Finished && alert.kind != Alert.Kind.Test
        return NotificationCompat.Builder(context, channel(alert.kind))
            .apply { if (appLock()) setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(redacted(alert)) }
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFF0A830.toInt())
            .setContentTitle(alert.title)
            .setContentIntent(openIntent(context, alert))
            .setAutoCancel(true)
            .setSilent(quiet)
            .setCategory(if (alert.kind == Alert.Kind.NeedsYou) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
    }

    private fun channel(kind: Alert.Kind) = when (kind) {
        Alert.Kind.NeedsYou -> NEEDS_YOU
        Alert.Kind.WentWrong -> WENT_WRONG
        Alert.Kind.Limits -> LIMITS
        Alert.Kind.Finished, Alert.Kind.Test -> FINISHED
    }

    private fun post(tag: String, b: NotificationCompat.Builder) {
        // Without the permission there is nothing to post; the app's Settings say so.
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { nm.notify(tag, ID, b.build()) }
    }

    /** What a locked phone shows with the app lock on: the app's name and the kind of news, nothing of what it is about. */
    private fun redacted(alert: Alert) = NotificationCompat.Builder(context, channel(alert.kind))
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(0xFFF0A830.toInt())
        .setContentTitle("Herdr")
        .setContentText(
            when (alert.kind) {
                Alert.Kind.NeedsYou -> "Something needs you."
                Alert.Kind.WentWrong -> "Something went wrong."
                Alert.Kind.Limits -> "A limit is near."
                Alert.Kind.Finished, Alert.Kind.Test -> "Something finished."
            } + " Unlock to see it.",
        )
        .build()

    companion object {
        /**
         * A tap (on the notification, or its row in the Needs you widget): the agent's chat, a task (its Accept card
         * when it is ready), Needs you, or the machine's health or usage.
         */
        fun openIntent(context: Context, alert: Alert): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setData(Uri.parse("herdr://alert/${Uri.encode(alert.tag)}"))
                .putExtra(MainActivity.OPEN_MACHINE, alert.machineId)
                .putExtra(MainActivity.OPEN_PANE, alert.paneId)
                .putExtra(MainActivity.OPEN_TASK, alert.task)
                .putExtra(MainActivity.OPEN_ACCEPT, alert.accept)
                .putExtra(MainActivity.OPEN_NEEDS, alert.chat.isNotEmpty())
                .putExtra(MainActivity.OPEN_HEALTH, alert.health)
                .putExtra(MainActivity.OPEN_USAGE, alert.usage)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(context, alert.tag.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /**
         * Button [index] of [alert] (on the notification, or its row in the Needs you widget): carried out by
         * [WorkService], which may start from either while the app is closed. One that can't be undone (Always allow,
         * Accept) opens [ConfirmActivity] instead, which asks for the fingerprint first when that is on.
         */
        fun actionIntent(context: Context, alert: Alert, index: Int): PendingIntent {
            val a = alert.actions[index]
            val reply = a.reply
            if (a.guard.isNotEmpty() && !reply) {
                return PendingIntent.getActivity(
                    context, alert.tag.hashCode() * 31 + index,
                    ConfirmActivity.intent(context, json.encodeToString(Alert.serializer(), alert), index)
                        .setData(Uri.parse("herdr://alert/${Uri.encode(alert.tag)}/$index")),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
            val intent = Intent(context, WorkService::class.java)
                .setAction(WorkService.ACT)
                .setData(Uri.parse("herdr://alert/${Uri.encode(alert.tag)}/$index"))
                .putExtra(WorkService.ALERT, json.encodeToString(Alert.serializer(), alert))
                .putExtra(WorkService.INDEX, index)
            // A reply's intent must be mutable for Android to add the words to it.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (reply) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getForegroundService(context, alert.tag.hashCode() * 31 + index, intent, flags)
        }

        /** Float on an agent's notification (this one, or its Live Update [tag]): opens [chat] as a bubble, named [name]. */
        fun floatIntent(context: Context, chat: BubbleChat, name: String, tag: String): PendingIntent =
            PendingIntent.getActivity(
                context, tag.hashCode() * 31 + 7,
                FloatActivity.intent(context, chat, name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        const val NEEDS_YOU = "needs-you"
        const val FINISHED = "finished"
        const val WENT_WRONG = "went-wrong"
        const val WATCHES = "watches"
        const val LIMITS = "limits"
        const val WORKING = "working"
        /** The one notification for what was held during quiet hours. */
        const val SUMMARY = "quiet:summary"
        private const val ID = 1
        val json = Json { ignoreUnknownKeys = true }
    }
}
