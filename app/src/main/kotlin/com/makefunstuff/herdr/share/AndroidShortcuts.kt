package com.makefunstuff.herdr.share

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.makefunstuff.herdr.MainActivity
import com.makefunstuff.herdr.R
import com.makefunstuff.herdr.bubble.AndroidBubbles
import com.makefunstuff.herdr.bubble.BubbleChat
import com.makefunstuff.herdr.ui.share.Ask
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * [Shortcuts] through Android's ShortcutManager, as dynamic shortcuts: the three fixed ones ranked
 * first, then the recent agents. A recent agent's shortcut is its chat's own (the one its bubble
 * uses), so the launcher, the share sheet and the bubble know it as one conversation. Only the
 * shortcuts made here are ever removed; a floating chat's stays with its bubble.
 */
class AndroidShortcuts(private val context: Context) : Shortcuts {
    @Serializable
    private data class Recent(val machineId: String, val paneId: String, val title: String, val machine: String)

    private val prefs = context.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Recent.serializer())
    private var recent: List<Recent> = runCatching { json.decodeFromString(serializer, prefs.getString(RECENT_KEY, null) ?: "[]") }.getOrDefault(emptyList())
    private var fixed: Pair<Int, String?>? = null

    @Synchronized
    override fun update(needYou: Int, coordinator: String?) {
        if (fixed == needYou to coordinator) return
        val list = listOf(
            fixedShortcut(Shortcuts.NEEDS_YOU, if (needYou > 0) "Needs you ($needYou)" else "Needs you", R.drawable.ic_needs_you, 0) {
                putExtra(MainActivity.OPEN_NEEDS, true)
            },
            fixedShortcut(Shortcuts.COORDINATOR, coordinator?.let { "Assistant · $it" } ?: "Assistant", R.drawable.ic_coordinator, 1) {
                putExtra(MainActivity.OPEN_ASK, Ask.COORDINATOR)
            },
            fixedShortcut(Shortcuts.START, "Start work", R.drawable.ic_start_work, 2) { putExtra(MainActivity.OPEN_START, true) },
        )
        // Android limits how often an app in the background may change them: tried again on the next change when refused.
        if (runCatching { ShortcutManagerCompat.addDynamicShortcuts(context, list) }.getOrDefault(false)) fixed = needYou to coordinator
    }

    @Synchronized
    override fun opened(machineId: String, paneId: String, title: String, machine: String) {
        val r = Recent(machineId, paneId, title, machine)
        if (recent.firstOrNull() == r) return
        val next = (listOf(r) + recent.filter { it.machineId != machineId || it.paneId != paneId }).take(Shortcuts.RECENT)
        publish(next)
    }

    @Synchronized
    override fun keep(machines: Set<String>) {
        val next = recent.filter { it.machineId in machines }
        if (next != recent) publish(next)
    }

    private fun publish(next: List<Recent>) {
        val gone = recent.filter { o -> next.none { it.machineId == o.machineId && it.paneId == o.paneId } }
        recent = next
        prefs.edit().putString(RECENT_KEY, json.encodeToString(serializer, next)).apply()
        runCatching {
            if (gone.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(context, gone.map { BubbleChat.Agent(it.machineId, it.paneId).id })
            next.forEachIndexed { i, r ->
                ShortcutManagerCompat.pushDynamicShortcut(context, AndroidBubbles.chatShortcut(context, BubbleChat.Agent(r.machineId, r.paneId), r.title, r.machine, rank = 3 + i))
            }
        }
    }

    private fun fixedShortcut(id: String, label: String, icon: Int, rank: Int, extras: Intent.() -> Unit): ShortcutInfoCompat {
        val intent = Intent(context, MainActivity::class.java)
            .setAction("com.makefunstuff.herdr.SHORTCUT.$id")
            .putExtra(MainActivity.OPEN_SHORTCUT, id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply(extras)
        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(AndroidBubbles.glyphIcon(context, icon))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }

    private companion object {
        const val RECENT_KEY = "recent"
    }
}
