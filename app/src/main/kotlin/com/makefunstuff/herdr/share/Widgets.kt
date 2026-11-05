package com.makefunstuff.herdr.share

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import com.makefunstuff.herdr.HerdrApp
import com.makefunstuff.herdr.MainActivity
import com.makefunstuff.herdr.R
import com.makefunstuff.herdr.core.push.Counts
import com.makefunstuff.herdr.core.push.Waiting
import com.makefunstuff.herdr.core.push.WaitingRows
import com.makefunstuff.herdr.push.AndroidAlerts
import com.makefunstuff.herdr.core.usage.UsageRules
import com.makefunstuff.herdr.ui.share.Ask
import java.util.Date

/** The home-screen widget that opens the Coordinator chat (the one used last, or asks which). */
class AskWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.update(context)
}

/** The status widget: "2 need you · 3 working" across the machines; a tap opens the board. */
class StatusWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Also every half hour: what works and wasn't confirmed since stops counting.
        (context.applicationContext as HerdrApp).container.tally.recount()
        Widgets.update(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = Widgets.update(context)
}

/**
 * The Needs you widget: what waits on you, newest first, each with its notification's buttons (Accept, Approve, Deny),
 * as many rows as its height has room for. A tap on a row opens it, as its notification does.
 */
class NeedsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.update(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = Widgets.update(context)
}

/** Draws the widgets from what the app knows: on a change of the counts, of the Coordinator used last or of what waits on you. */
object Widgets {
    fun update(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val container = (context.applicationContext as HerdrApp).container
        val asks = manager.getAppWidgetIds(ComponentName(context, AskWidget::class.java))
        if (asks.isNotEmpty()) {
            val label = container.shares.last.value?.let { container.machines.get(it)?.label }
            manager.updateAppWidget(asks, ask(context, label))
        }
        val statuses = manager.getAppWidgetIds(ComponentName(context, StatusWidget::class.java))
        if (statuses.isNotEmpty()) {
            // As the limits stand now: one whose reset time passed since the reading counts as fresh.
            val usage = UsageRules.tightest(container.usage.reports().filterKeys { container.machines.get(it) != null }, container.usage.nowSeconds())
            manager.updateAppWidget(statuses, status(context, container.tally.counts.value, usage, container.usage.nowSeconds()))
        }
        val needs = manager.getAppWidgetIds(ComponentName(context, NeedsWidget::class.java))
        if (needs.isNotEmpty()) {
            // Of the machines still in the app: a removed one's buttons have nowhere to go.
            val waiting = container.inbox.waiting.value.filter { container.machines.get(it.alert.machineId) != null }
            // Each its own: one widget may be taller than another.
            for (id in needs) manager.updateAppWidget(id, needs(context, waiting, rows(context, manager.getAppWidgetOptions(id))))
        }
    }

    /**
     * How many rows a Needs you widget of the size [options] gives has room for: under its heading, 49dp each (a
     * row and its hairline). Its height upright is the larger the launcher names, on its side the smaller.
     */
    private fun rows(context: Context, options: Bundle): Int {
        val upright = context.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val key = if (upright) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
        val height = options.getInt(key).takeIf { it > 0 } ?: return 2
        return ((height - 16 - 28) / 49).coerceIn(1, 6)
    }

    /** The Needs you widget with [waiting], [fit] rows of it at most. */
    fun needs(context: Context, waiting: List<Waiting>, fit: Int) = RemoteViews(context.packageName, R.layout.widget_needs).apply {
        val shown = waiting.take(fit)
        setTextViewText(R.id.widget_count, if (waiting.isEmpty()) "" else waiting.size.toString())
        val more = waiting.size - shown.size
        setTextViewText(R.id.widget_more, if (more > 0) "$more more" else "")
        setViewVisibility(R.id.widget_empty, if (waiting.isEmpty()) View.VISIBLE else View.GONE)
        removeAllViews(R.id.widget_rows)
        shown.forEach { addView(R.id.widget_rows, row(context, it)) }
        setContentDescription(R.id.widget_head, if (waiting.isEmpty()) "Nothing waits on you. Opens Needs you." else "${waiting.size} need you. Opens Needs you.")
        // The heading (and the empty widget) opens Needs you, where the rest are.
        setOnClickPendingIntent(R.id.widget_head, needsYou(context))
        setOnClickPendingIntent(R.id.widget_root, needsYou(context))
    }

    private fun row(context: Context, w: Waiting) = RemoteViews(context.packageName, R.layout.widget_needs_row).apply {
        val a = w.alert
        setTextViewText(R.id.row_title, a.title)
        setTextViewText(R.id.row_text, w.busy.ifEmpty { a.text })
        // How the last button went, in red, in place of why it waits.
        setTextViewText(R.id.row_note, w.note)
        setViewVisibility(R.id.row_note, if (w.note.isNotEmpty() && w.busy.isEmpty()) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.row_text, if (w.note.isNotEmpty() && w.busy.isEmpty()) View.GONE else View.VISIBLE)
        setOnClickPendingIntent(R.id.row_words, AndroidAlerts.openIntent(context, a))
        setContentDescription(R.id.row_words, "${a.title}: ${w.note.ifEmpty { w.busy.ifEmpty { a.text } }}. Opens it.")
        // While one of its buttons is carried out, none: it is pressed once.
        val buttons = if (w.busy.isEmpty()) WaitingRows.buttons(a) else emptyList()
        val opens = if (w.busy.isEmpty()) WaitingRows.opens(a) else null
        listOf(R.id.row_first, R.id.row_second).forEachIndexed { slot, view ->
            val index = buttons.getOrNull(slot)
            val label = index?.let { a.actions[it].label } ?: opens.takeIf { slot == 0 }
            setViewVisibility(view, if (label != null) View.VISIBLE else View.GONE)
            if (label == null) return@forEachIndexed
            setTextViewText(view, label)
            setContentDescription(view, "$label: ${a.title}")
            // Answered in words: the widget has no box for them, so it opens where they are typed.
            setOnClickPendingIntent(view, if (index != null) AndroidAlerts.actionIntent(context, a, index) else AndroidAlerts.openIntent(context, a))
        }
    }

    private fun needsYou(context: Context): PendingIntent = PendingIntent.getActivity(
        context, "needs".hashCode(),
        Intent(context, MainActivity::class.java)
            .setAction("com.makefunstuff.herdr.NEEDS")
            .putExtra(MainActivity.OPEN_NEEDS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ask(context: Context, machine: String?) = RemoteViews(context.packageName, R.layout.widget_ask).apply {
        setTextViewText(R.id.widget_machine, machine ?: "Herdr")
        setContentDescription(R.id.widget_root, listOfNotNull("Ask the Assistant", machine?.let { "on $it" }).joinToString(" "))
        setOnClickPendingIntent(R.id.widget_root, open(context, Ask.COORDINATOR))
    }

    private fun status(context: Context, c: Counts, usage: UsageRules.Tight?, now: Long) = RemoteViews(context.packageName, R.layout.widget_status).apply {
        // The tightest limit, in the colour of how close it is (one of three texts shows).
        val words = usage?.let { UsageRules.glance(it, now) }
        val level = usage?.level ?: 0
        setTextViewText(R.id.widget_usage, words.orEmpty())
        setTextViewText(R.id.widget_usage_near, words.orEmpty())
        setTextViewText(R.id.widget_usage_out, words.orEmpty())
        setViewVisibility(R.id.widget_usage, if (words != null && level < 80) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.widget_usage_near, if (words != null && level in 80..99) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.widget_usage_out, if (words != null && level >= 100) View.VISIBLE else View.GONE)
        // Each count is in its colour when there is any, and dim at nought.
        setViewVisibility(R.id.widget_counts, if (c.known) View.VISIBLE else View.GONE)
        setTextViewText(R.id.widget_need, c.needYou.toString())
        setViewVisibility(R.id.widget_need, if (c.needYou > 0) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.widget_need_none, if (c.needYou > 0) View.GONE else View.VISIBLE)
        setTextViewText(R.id.widget_need_words, if (c.needYou == 1) "needs you" else "need you")
        setTextViewText(R.id.widget_work, c.working.toString())
        setViewVisibility(R.id.widget_work, if (c.working > 0) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.widget_work_none, if (c.working > 0) View.GONE else View.VISIBLE)
        val detail = when {
            !c.known -> "Nothing known yet · tap to open Herdr"
            else -> listOf(
                if (c.machines == 1) "on 1 machine" else "across ${c.machines} machines",
                at(context, c.at),
            ).joinToString(" · ")
        }
        setTextViewText(R.id.widget_detail, detail)
        // A one-cell widget has room for two lines: the limit takes the second when there is one.
        setViewVisibility(R.id.widget_detail, if (words != null) View.GONE else View.VISIBLE)
        val limit = words?.let { " Tightest limit: $it." }.orEmpty()
        setContentDescription(R.id.widget_root, if (c.known) "${c.words}, $detail.$limit Opens the board." else detail + limit)
        setOnClickPendingIntent(R.id.widget_root, open(context, Ask.BOARD))
    }

    /** When the counts were last known: "now" within a couple of minutes, else the time (and the day, when not today). */
    private fun at(context: Context, at: Long): String {
        val now = System.currentTimeMillis()
        if (now - at < 2 * 60_000) return "now"
        val time = DateFormat.getTimeFormat(context).format(Date(at))
        return if (DateUtils.isToday(at)) "as of $time" else "as of " + DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH) + " $time"
    }

    private fun open(context: Context, to: String): PendingIntent = PendingIntent.getActivity(
        context, to.hashCode(), MainActivity.ask(context, to), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
