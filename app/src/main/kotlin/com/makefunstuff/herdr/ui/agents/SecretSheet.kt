package com.makefunstuff.herdr.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.makefunstuff.herdr.core.home.Waiting
import com.makefunstuff.herdr.ui.board.BoardPanel
import com.makefunstuff.herdr.ui.board.PanelCancel
import com.makefunstuff.herdr.ui.common.SecureWindow
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.SegmentedPills
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The masked box for [w], an agent's secret ask, as 3.1's web card has it: why it asks, the value (dots, the
 * keyboard's password mode, so it neither shows nor learns it), where it is kept (this space, its machine,
 * Everywhere) and Save. [keep] sends it once to the machine's vault ([com.makefunstuff.herdr.core.board.WorkBoard.keepSecret])
 * and says where it went; the agent hears its name only. The value lives in this sheet's memory alone: no draft, no
 * saved state, never logged, and the window is secure while it shows (no screenshot, recording or recents thumbnail);
 * where it can't be, there is no box. It doesn't close while the value is on its way, so its answer is always seen.
 */
@Composable
fun SecretSheet(
    w: Waiting.Secret,
    onDismiss: () -> Unit,
    onKept: (String) -> Unit,
    keep: suspend (level: String, value: String) -> String,
) {
    val secure = SecureWindow()
    val c = Herdr.colors
    val scope = rememberCoroutineScope()
    val ask = w.ask
    // Not rememberSaveable: saved state is written to disk when Android stops the app.
    var typed by remember(w.key) { mutableStateOf("") }
    var level by remember(w.key) { mutableStateOf(ask.levels.firstOrNull()?.id ?: "all") }
    var busy by remember(w.key) { mutableStateOf(false) }
    var error by remember(w.key) { mutableStateOf<String?>(null) }
    fun save() {
        if (typed.isEmpty() || busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val said = keep(level, typed)
                typed = ""
                onKept(said)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "That didn't go through"
            } finally {
                busy = false
            }
        }
    }
    val close = { if (!busy) onDismiss() }
    BoardPanel(
        "Secret: ${ask.name}",
        onDismiss = close,
        buttons = {
            PanelCancel(close)
            if (secure) PrimaryButton(if (busy) "Saving" else "Save", enabled = typed.isNotEmpty() && !busy) { save() }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Text(
                listOfNotNull(w.task?.let { "${it.name} · ${it.title}" } ?: "${w.space?.name ?: "A space"}'s Assistant", w.machineLabel).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            ask.why?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.text) }
            if (!secure) {
                Text("This screen can't be hidden from screenshots here, so give it on the machine.", style = MaterialTheme.typography.bodyMedium, color = c.red)
                return@Column
            }
            HerdrField(
                value = typed,
                // As the web's password box: a pasted line break is no part of a key.
                onValueChange = { typed = it.replace("\r", "").replace("\n", "") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                textStyle = CodeStyle,
                placeholder = { Text("Paste or type it") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
            )
            if (ask.levels.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text("For", style = MaterialTheme.typography.labelMedium, color = c.muted)
                    SegmentedPills(
                        ask.levels.map { it.label.ifEmpty { it.id } },
                        selected = ask.levels.indexOfFirst { it.id == level }.coerceAtLeast(0),
                        onSelect = { i -> if (!busy) level = ask.levels[i].id },
                    )
                }
            }
            Text(
                error ?: "Agents use it by name, never seeing it.",
                style = MaterialTheme.typography.bodySmall, color = if (error != null) c.red else c.muted,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
