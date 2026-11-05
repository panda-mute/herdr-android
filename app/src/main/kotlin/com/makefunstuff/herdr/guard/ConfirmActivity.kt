package com.makefunstuff.herdr.guard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.makefunstuff.herdr.HerdrApp
import com.makefunstuff.herdr.core.guard.Guarded
import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.AlertAction
import com.makefunstuff.herdr.push.AndroidAlerts
import com.makefunstuff.herdr.push.WorkService
import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.theme.HerdrTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * A notification's button that asks for the fingerprint first (Approve and merge, Always allow): it
 * asks over whatever is on the screen, then hands the button to [WorkService] as the button itself
 * would have, and closes. A service can't ask, so these buttons open this instead of the service.
 */
class ConfirmActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return finish()
        val container = (application as HerdrApp).container
        val json = intent.getStringExtra(WorkService.ALERT)
        val index = intent.getIntExtra(WorkService.INDEX, -1)
        val alert = json?.let { runCatching { AndroidAlerts.json.decodeFromString(Alert.serializer(), it) }.getOrNull() }
        val action = alert?.actions?.getOrNull(index) ?: return finish()
        val guarded = Guarded.of(action.guard)
        val store = container.guard
        lifecycleScope.launch {
            val go = when {
                guarded == null || !store.wants(guarded) -> true
                store.unlock.canAsk() -> store.ask(title(alert, action), alert.text)
                guarded.confirmed -> true
                else -> confirmHere(title(alert, action), alert.text)
            }
            if (go) startForegroundService(WorkService.act(this@ConfirmActivity, json, index, confirmed = true))
            finish()
        }
    }

    private fun title(alert: Alert, action: AlertAction): String = when (Guarded.of(action.guard)) {
        Guarded.Merge -> "Approve and merge ${alert.title}?"
        Guarded.AlwaysAllow -> "${action.label}?"
        else -> "${action.label}?"
    }

    /** On a phone with no screen lock: the app's own confirmation. */
    private suspend fun confirmHere(title: String, text: String): Boolean {
        val answer = CompletableDeferred<Boolean>()
        setContent {
            HerdrTheme {
                HerdrDialog(
                    onDismissRequest = { answer.complete(false) },
                    title = { Text(title) },
                    text = { Text("$text\n\nThis phone has no screen lock, so the app asks here instead of for your fingerprint.") },
                    dismissButton = { QuietButton(onClick = { answer.complete(false) }) { Text("Cancel") } },
                    confirmButton = { QuietButton(onClick = { answer.complete(true) }) { Text("Confirm") } },
                )
            }
        }
        return answer.await()
    }

    companion object {
        /** Opens for button [index] of the notification [alert] (its JSON). */
        fun intent(context: Context, alert: String, index: Int): Intent = Intent(context, ConfirmActivity::class.java)
            .putExtra(WorkService.ALERT, alert)
            .putExtra(WorkService.INDEX, index)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
