package com.makefunstuff.herdr.push

import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.AlertChange
import com.makefunstuff.herdr.core.push.AlertRules
import com.makefunstuff.herdr.core.push.Alerts
import com.makefunstuff.herdr.core.push.PhoneQuiet
import com.makefunstuff.herdr.core.push.QuietHours
import com.makefunstuff.herdr.core.push.QuietSummary
import com.makefunstuff.herdr.core.push.Waiting
import com.makefunstuff.herdr.core.push.WaitingRows
import com.makefunstuff.herdr.core.board.WorkOverview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** Where notifications go: Android's notification shade on the phone, nowhere in the screen previews. */
interface AlertSink {
    /** Puts [alert] up, or replaces the one under its tag. [quiet]: no sound or pop-up. [note]: a line above its text. */
    fun show(alert: Alert, quiet: Boolean, note: String? = null)

    /** [alert] while one of its buttons is being carried out: [words] and no buttons. */
    fun working(alert: Alert, words: String)

    /** [alert] once its button was carried out: [words], gone after a moment. */
    fun finished(alert: Alert, words: String)

    /** The notifications held while it was quiet ([held]), as one. */
    fun summary(held: List<Alert>, summary: QuietSummary, quiet: Boolean)

    fun cancel(tag: String)
}

/** The phone's other half: what only Android has (the push app, the permission, alarms). */
interface PushPhone {
    /** The phone's name, which machines show for this subscription. */
    val name: String

    /** Push apps (UnifiedPush distributors) on the phone, by package, ntfy first. */
    fun distributors(): List<String>

    fun label(pkg: String): String

    /** Asks the push app for an address (it answers later); false when there is none. */
    fun register(): Boolean

    fun unregister()

    /** Whether Android lets the app post notifications. */
    fun notificationsAllowed(): Boolean

    fun openNotificationSettings()

    /** Whether Android shows the app's Live Updates as such (Android 16 and later; the person can turn them off). Null before Android 16. */
    fun liveAllowed(): Boolean?

    /** Opens Android's switch for the app's Live Updates. */
    fun openLiveSettings()

    /** Opens the store (or ntfy's page) to get a push app. */
    fun getPushApp()

    /** Sets or clears the alarm for the next look at the machines. */
    fun planChecks()

    /** What the phone is in now that makes it quiet: Do Not Disturb (Bedtime turns it on) or car mode; null when neither. */
    fun quietMode(): PhoneQuiet?

    /** Sets the alarm that looks whether quiet hours ended at [at] (ms), or clears it when null. */
    fun planQuiet(at: Long?)
}

/**
 * Turns alert changes into notifications, each state once: a push and the app's own look can both
 * report an agent that asks, and a notification swiped away stays away until something new happens.
 * What the notification rules switch off ([AlertRules]: the kinds, each space's Notify) isn't shown,
 * nor what an agent muted before Notify says ([muted]: by machine and pane, until it is moved onto its space). During quiet hours what can wait
 * is held instead ([QuietHours.holds]), and [release]d as one. What waits on you stays in [waiting] until it is
 * answered or over, swiped away or not: the Needs you widget lists it.
 */
class Inbox(
    private val file: File,
    private val sink: AlertSink,
    private val muted: (machineId: String, paneId: String) -> Boolean = { _, _ -> false },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Seen.serializer())
    private val seen: MutableMap<String, Seen> = load()
    private val heldFile = File(file.parentFile, "held.json")
    private val heldSerializer = ListSerializer(Alert.serializer())
    /** Held while it is quiet, oldest first, one per tag; kept in a file, since a night outlasts the app's process. */
    private val held: LinkedHashMap<String, Alert> = loadHeld()
    private val _heldCount = MutableStateFlow(held.size)
    /** How many notifications wait for quiet hours to end. */
    val heldCount: StateFlow<Int> = _heldCount.asStateFlow()
    private val waitingFile = File(file.parentFile, "waiting.json")
    private val waitingSerializer = ListSerializer(Waiting.serializer())
    /** What waits on you, one per tag; kept in a file for the widget while the app is closed. */
    private val waits: LinkedHashMap<String, Waiting> = loadWaiting()
    private val _waiting = MutableStateFlow(WaitingRows.order(waits.values))
    /** What waits on you now, newest first: held during quiet hours or not, its notification up or swiped away. */
    val waiting: StateFlow<List<Waiting>> = _waiting.asStateFlow()

    @Serializable
    data class Seen(val sig: String, val at: Long)

    /**
     * Shows what is new and takes down what is over. [quiet] while the app is on screen or you are at a
     * machine. [hold] during quiet hours: what can wait is held for [release], and only what needs you
     * shows. [rules]: what is switched off is noted as seen and not shown (turned on later, it starts from then).
     */
    @Synchronized
    fun apply(
        changes: List<AlertChange>,
        quiet: Boolean,
        hold: Boolean = false,
        rules: AlertRules = AlertRules.ALL,
        now: Long = System.currentTimeMillis(),
    ) {
        var dirty = false
        var heldDirty = false
        var waitDirty = false
        for (c in changes) when (c) {
            is AlertChange.Show -> {
                val a = c.alert
                if (a.sig.isNotEmpty() && seen[a.tag]?.sig == a.sig) continue
                // A newer state replaces a held one under the same tag, held or shown.
                if (held.remove(a.tag) != null) heldDirty = true
                // Waits on you as long as it may be shown at all; a newer state that doesn't (it finished) takes it off.
                val wanted = rules.allows(a) && !muted(a) && WaitingRows.waits(a)
                if (wanted) waits[a.tag] = Waiting(a, now)
                if (wanted || waits.remove(a.tag) != null) waitDirty = true
                if (!rules.allows(a) || muted(a)) {
                    // Switched off (or muted): one up from before (it was on then) comes down with it.
                    sink.cancel(a.tag)
                } else if (hold && QuietHours.holds(a)) {
                    held[a.tag] = a
                    heldDirty = true
                } else {
                    sink.show(a, quiet)
                }
                if (a.sig.isNotEmpty()) {
                    seen[a.tag] = Seen(a.sig, now)
                    dirty = true
                }
            }
            is AlertChange.Clear -> {
                // Over before quiet hours ended (an agent that finished is at work again): nothing to tell.
                if (held.remove(c.tag) != null) heldDirty = true
                if (waits.remove(c.tag) != null) waitDirty = true
                if (seen.remove(c.tag) != null) {
                    sink.cancel(c.tag)
                    dirty = true
                }
            }
        }
        if (dirty) save(now)
        if (heldDirty) saveHeld()
        if (waitDirty) saveWaiting(now)
    }

    /**
     * Quiet hours ended: what was held shows, as it would have (with its buttons) when it is one, or as
     * one summary. False when nothing was held.
     */
    @Synchronized
    fun release(quiet: Boolean): Boolean {
        if (held.isEmpty()) return false
        val all = held.values.toList()
        held.clear()
        saveHeld()
        if (all.size == 1) sink.show(all.single(), quiet) else sink.summary(all, QuietSummary.of(all), quiet)
        return true
    }

    /** Notes what the person already saw in the app, so a later look doesn't announce it. */
    @Synchronized
    fun markSeen(changes: List<AlertChange>, now: Long = System.currentTimeMillis()) {
        var heldDirty = false
        var waitDirty = false
        for (c in changes) {
            // The app showed it as it is now, waiting on you no more (the agent finished, or works again).
            if ((c !is AlertChange.Show || !WaitingRows.waits(c.alert)) && waits.remove(c.tag) != null) waitDirty = true
            if (c !is AlertChange.Show || c.alert.sig.isEmpty()) continue
            seen[c.tag] = Seen(c.alert.sig, now)
            // Held during quiet hours, and the app showed that agent since (as it is now): nothing to tell when they end.
            if (held.remove(c.tag) != null) heldDirty = true
        }
        save(now)
        if (heldDirty) saveHeld()
        if (waitDirty) saveWaiting(now)
    }

    /** [machineId]'s board as just read: its tasks' alerts that wait no more (accepted, answered elsewhere) leave [waiting]. */
    @Synchronized
    fun looked(machineId: String, o: WorkOverview, now: Long = System.currentTimeMillis()) {
        if (waits.values.removeAll { !WaitingRows.stillWaits(it.alert, machineId, o) }) saveWaiting(now)
    }

    /** What was shown last under each tag starting with [prefix], by its signature: what is up now, or was. */
    @Synchronized
    fun seen(prefix: String): Map<String, String> = seen.filterKeys { it.startsWith(prefix) }.mapValues { it.value.sig }

    /** Takes a notification down when its agent or task is opened in the app; it stays seen, and is no longer held. */
    @Synchronized
    fun dismiss(tag: String) {
        if (held.remove(tag) != null) saveHeld()
        sink.cancel(tag)
    }

    /** Forgets what was held without showing it: notifications while the app is closed were turned off. */
    @Synchronized
    fun dropHeld() {
        if (held.isEmpty()) return
        held.clear()
        saveHeld()
    }

    /** An agent's own news (it asks, finished) while it is muted from before Notify; a test always comes. */
    private fun muted(a: Alert): Boolean =
        a.tag == Alerts.agentTag(a.machineId, a.paneId) && muted(a.machineId, a.paneId)

    /** [alert] again after one of its buttons ([note]: how it went); in [waiting], with its buttons back. */
    fun show(alert: Alert, quiet: Boolean, note: String?) {
        sink.show(alert, quiet, note)
        mark(alert.tag) { it.copy(busy = "", note = note.orEmpty()) }
    }

    fun working(alert: Alert, words: String) {
        sink.working(alert, words)
        mark(alert.tag) { it.copy(busy = words, note = "") }
    }

    /** One of [alert]'s buttons was carried out: it waits on you no more. */
    fun finished(alert: Alert, words: String) {
        sink.finished(alert, words)
        synchronized(this) { if (waits.remove(alert.tag) != null) saveWaiting(System.currentTimeMillis()) }
    }

    @Synchronized
    private fun mark(tag: String, change: (Waiting) -> Waiting) {
        val w = waits[tag] ?: return
        waits[tag] = change(w)
        saveWaiting(System.currentTimeMillis())
    }

    private fun load(): MutableMap<String, Seen> =
        runCatching { if (file.exists()) json.decodeFromString(serializer, file.readText()).toMutableMap() else null }.getOrNull()
            ?: mutableMapOf()

    private fun loadHeld(): LinkedHashMap<String, Alert> {
        // One by one: one of a kind this app no longer has (a watch's) is left out, not the night's others with it.
        val saved = runCatching { if (heldFile.exists()) json.parseToJsonElement(heldFile.readText()) as? kotlinx.serialization.json.JsonArray else null }.getOrNull()
        val list = saved.orEmpty().mapNotNull { runCatching { json.decodeFromJsonElement(Alert.serializer(), it) }.getOrNull() }
        return LinkedHashMap<String, Alert>().apply { list.forEach { put(it.tag, it) } }
    }

    private fun loadWaiting(): LinkedHashMap<String, Waiting> {
        // One by one, as what was held: one this app can no longer read is left out, not the others with it.
        val saved = runCatching { if (waitingFile.exists()) json.parseToJsonElement(waitingFile.readText()) as? kotlinx.serialization.json.JsonArray else null }.getOrNull()
        val list = saved.orEmpty().mapNotNull { runCatching { json.decodeFromJsonElement(Waiting.serializer(), it) }.getOrNull() }
        // A button under way as the app was stopped didn't finish: its buttons come back.
        return LinkedHashMap<String, Waiting>().apply { list.forEach { put(it.alert.tag, it.copy(busy = "")) } }
    }

    private fun saveWaiting(now: Long) {
        // As long as what was seen is kept: a week old, the next look would show it again anyway.
        waits.values.removeAll { now - it.at > WAITING_MS }
        _waiting.value = WaitingRows.order(waits.values)
        runCatching {
            val tmp = File(file.parentFile, "waiting.json.tmp")
            tmp.writeText(json.encodeToString(waitingSerializer, waits.values.toList()))
            tmp.renameTo(waitingFile)
        }
    }

    private fun saveHeld() {
        _heldCount.value = held.size
        runCatching {
            val tmp = File(file.parentFile, "held.json.tmp")
            tmp.writeText(json.encodeToString(heldSerializer, held.values.toList()))
            tmp.renameTo(heldFile)
        }
    }

    private fun save(now: Long) {
        // A week is plenty: an agent's state changes long before.
        seen.entries.removeAll { now - it.value.at > WAITING_MS }
        runCatching {
            val tmp = File(file.parentFile, "alerts.json.tmp")
            tmp.writeText(json.encodeToString(serializer, seen))
            tmp.renameTo(file)
        }
    }

    private companion object {
        const val WAITING_MS = 7L * 24 * 3600 * 1000
    }
}
