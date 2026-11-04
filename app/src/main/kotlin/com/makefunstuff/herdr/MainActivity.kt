package com.makefunstuff.herdr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.makefunstuff.herdr.ui.share.Ask

/** The app. A FragmentActivity, as the fingerprint prompt (androidx.biometric) shows over one. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as HerdrApp).container
        // With the app lock on, the recent apps show no picture of the screens either.
        if (Build.VERSION.SDK_INT >= 33) lifecycleScope.launch { container.guard.state.collect { setRecentsScreenshotEnabled(!it.appLock) } }
        setContent { HerdrRoot(container) }
        if (savedInstanceState == null && !open(intent) && container.shares.pending.value != null) {
            // Something shared was waiting for a machine when Android ended the app: ask again.
            Links.open(Routes.ask(Ask.SHARE))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open(intent)
    }

    /** Ctrl+K from a hardware keyboard opens the search, also when nothing on screen has the focus. */
    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_K && event.isCtrlPressed) {
            Links.open(Routes.search())
            return true
        }
        return super.onKeyShortcut(keyCode, event)
    }

    /** The keyboard's shortcuts as Android lists them (Meta+/): Ctrl+K. */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data += KeyboardShortcutGroup("Herdr", listOf(KeyboardShortcutInfo("Search agents, tasks and chats", KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON)))
    }

    /**
     * A launcher shortcut: Needs you, Start work, the Coordinator ([Ask]) or a recent agent's chat. A tapped notification: its agent's chat, its task on the board (or its review card), its machine's health or usage, a quick command's run, or the brief. The tile,
     * a widget or a share: [Ask]. A floating chat's "Open in the app" or its shortcut: that chat. False
     * when it opens nothing.
     */
    private fun open(intent: Intent?): Boolean {
        // A launcher shortcut: the launcher learns which ones are used.
        intent?.getStringExtra(OPEN_SHORTCUT)?.let { runCatching { androidx.core.content.pm.ShortcutManagerCompat.reportShortcutUsed(this, it) } }
        if (intent?.getBooleanExtra(OPEN_NEEDS, false) == true) { Links.open(Routes.AGENTS); return true }
        if (intent?.getBooleanExtra(OPEN_START, false) == true) { Links.open(Routes.start()); return true }
        intent?.getStringExtra(OPEN_ASK)?.takeIf { it in ASKS }?.let { Links.open(Routes.ask(it)); return true }
        if (intent?.getBooleanExtra(OPEN_BRIEF, false) == true) { Links.open(Routes.BRIEF); return true }
        val machine = intent?.getStringExtra(OPEN_MACHINE)?.takeIf { it.isNotEmpty() } ?: return false
        val pane = intent.getStringExtra(OPEN_PANE).orEmpty()
        val task = intent.getStringExtra(OPEN_TASK).orEmpty()
        val quick = intent.getStringExtra(OPEN_QUICK).orEmpty()
        when {
            intent.getBooleanExtra(OPEN_HEALTH, false) -> Links.open(Routes.health(machine))
            intent.getBooleanExtra(OPEN_USAGE, false) -> Links.open(Routes.usage(machine))
            quick.isNotEmpty() -> Links.open(Routes.quickRuns(machine, quick))
            intent.getBooleanExtra(OPEN_COORDINATOR, false) -> Links.open(Routes.coordinator(machine))
            pane.isNotEmpty() -> Links.open(agentRoute(machine, pane))
            // Finished work that waits for a yes: its review card, with the others after it. A Shepherdr 3
            // task has none: it opens on its board as its agent's chat, Merge on it.
            task.isNotEmpty() && intent.getBooleanExtra(OPEN_REVIEW, false) && !onShepherdr(machine, task) -> Links.open(Routes.review(machine, task))
            task.isNotEmpty() -> Links.open(Routes.board(machine, task))
            else -> Links.open(Routes.SETTINGS)
        }
        return true
    }

    /**
     * Where a notification about [pane] on [machine] opens: for an agent Shepherdr 3 started, its
     * task's chat or its space's Coordinator, where its cards are ([Panes.openAgent]); else its chat.
     */
    private fun agentRoute(machine: String, pane: String): String {
        val host = (application as HerdrApp).container.hosts.hosts.value.firstOrNull { it.machine.id == machine }
        val agent = host?.takeIf { it.onShepherdr }?.view?.agents?.firstOrNull { it.paneId == pane }
        return agent?.shepherdrTask?.let { Routes.board(machine, it, alone = true) }
            ?: agent?.shepherdrSpace?.let { Routes.coordinator(machine, it) }
            ?: Routes.agent(machine, pane)
    }

    /** Whether [task] is a Shepherdr 3 task on [machine], as the app last read its board. */
    private fun onShepherdr(machine: String, task: String): Boolean =
        (application as HerdrApp).container.boards.lists.value[machine]?.work?.tasks?.any { it.id == task } == true

    companion object {
        const val OPEN_MACHINE = "open_machine"
        const val OPEN_PANE = "open_pane"
        const val OPEN_TASK = "open_task"
        const val OPEN_REVIEW = "open_review"
        const val OPEN_HEALTH = "open_health"
        const val OPEN_USAGE = "open_usage"
        const val OPEN_QUICK = "open_quick"
        const val OPEN_COORDINATOR = "open_coordinator"
        const val OPEN_ASK = "open_ask"
        const val OPEN_BRIEF = "open_brief"
        /** The launcher's shortcuts: Needs you (the Agents screen), Start work, and which shortcut it was. */
        const val OPEN_NEEDS = "open_needs"
        const val OPEN_START = "open_start"
        const val OPEN_SHORTCUT = "open_shortcut"
        private val ASKS = setOf(Ask.COORDINATOR, Ask.BOARD, Ask.SHARE)

        /** Opens the app on [to] (an [Ask]), in its own task, from the tile, a widget or a share. */
        fun ask(context: Context, to: String): Intent = Intent(context, MainActivity::class.java)
            .setAction("com.makefunstuff.herdr.ASK.$to")
            .putExtra(OPEN_ASK, to)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
