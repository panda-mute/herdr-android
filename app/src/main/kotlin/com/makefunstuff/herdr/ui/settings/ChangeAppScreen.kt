package com.makefunstuff.herdr.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.change.AppChanges
import com.makefunstuff.herdr.core.change.BuilderCheck
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.ActionRow
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.launch

/** Settings' card for changing the app: which machine builds it, and its update when one is ready. */
@Composable
fun ChangeAppCard(onOpen: () -> Unit) {
    val container = LocalContainer.current
    val state by container.changes.state.collectAsStateWithLifecycle()
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val builder = state.saved.builderId?.let { id -> hosts.firstOrNull { it.machine.id == id }?.machine?.label }
    QuietCard(padded = true) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text("Change the app", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(
                "Long-press a screen's title to ask for a change to it. A machine of yours does the work and builds the update.",
                style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted,
            )
            ActionRow(
                "Builds on", detail = state.saved.update?.let { "${builder ?: "?"} · Update ready, ${it.versionName}" } ?: builder ?: "Not picked yet",
                icon = HerdrIcons.Hammer, chevron = true, onClick = onOpen,
            )
        }
    }
}

/**
 * Settings › Change the app: the machine that builds it (any machine in the app with a herdr-android checkout and
 * the Android SDK; it looks there over SSH and says what's missing), whether it signs the update itself, and the
 * update, with Install.
 */
@Composable
fun ChangeAppScreen(nav: NavController) {
    val container = LocalContainer.current
    val changes = container.changes
    val state by changes.state.collectAsStateWithLifecycle()
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val builder = state.saved.builderId
    val look = builder?.let { state.looks[it] }
    val builderOnline = hosts.firstOrNull { it.machine.id == builder }?.online == true
    LaunchedEffect(builder, builderOnline) { if (builder != null && builderOnline) changes.look(builder) }
    val c = Herdr.colors
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = c.ground,
        topBar = { HerdrTopBar("Change the app", subtitle = "Settings", onBack = { nav.popBackStack() }) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).padding(top = Space.m, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(
                "Long-press a screen's title, or pick Change this screen in its ⋯ menu, and say what should change. The machine picked here does all the work: its herdr-android Assistant makes it a task, with tests and before and after shots, and once you accept it and it is merged, it builds the update this app installs. Nothing is built on the phone.",
                style = MaterialTheme.typography.bodyMedium, color = c.muted,
            )
            QuietCard(padded = true) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text("Builds on", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    if (hosts.isEmpty()) Text("No machines yet.", style = MaterialTheme.typography.bodyMedium, color = c.muted)
                    hosts.forEachIndexed { i, h ->
                        if (i > 0) RowDivider(inset = 0.dp)
                        ActionRow(
                            h.machine.label, detail = if (h.online) null else "Offline", icon = HerdrIcons.Monitor, selected = h.machine.id == builder,
                            onClick = { changes.pick(h.machine.id) },
                        )
                    }
                }
            }
            if (builder != null) QuietCard(padded = true) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    val label = hosts.firstOrNull { it.machine.id == builder }?.machine?.label ?: "The machine"
                    Text("What $label has", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    when {
                        !builderOnline && look?.check == null -> Text("$label is offline: it is looked at once it answers.", style = MaterialTheme.typography.bodyMedium, color = c.muted)
                        look == null || (look.looking && look.check == null && look.problem == null) -> LoadingState("Looking at $label")
                        look.problem != null -> ErrorMessage(look.problem, onRetry = { scope.launch { changes.look(builder) } })
                        else -> Checks(look, label)
                    }
                }
            }
            QuietCard(padded = true) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text("Update", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text("This app: ${changes.app.versionName}", style = MaterialTheme.typography.bodyMedium)
                    val u = state.saved.update
                    when {
                        u != null -> {
                            Text(
                                "Update ready: ${u.versionName}, " + if (u.source == "release") "CI's release of the merge." else "built with the release key.",
                                style = MaterialTheme.typography.bodyMedium, color = c.strong,
                            )
                            Text("Android asks before it installs it, and keeps the app's machines and SSH key.", style = MaterialTheme.typography.bodySmall, color = c.muted)
                            PrimaryButton("Install the update", onClick = changes::install)
                        }
                        state.fetching -> LoadingState("Copying the update")
                        state.saved.waitingSince != null -> Text(state.build?.takeIf { it.running }?.words ?: "Asked for the build", style = MaterialTheme.typography.bodyMedium, color = c.muted)
                        else -> {
                            state.problem?.let { ErrorMessage(it) }
                            if (look?.ok == true) SecondaryButton("Build main now") {
                                scope.launch { runCatching { changes.buildNow() }.onFailure { context.toast(it.message ?: "Couldn't ask for the build") } }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Each thing the builder needs: found (where), or missing (what to do). */
@Composable
private fun Checks(look: AppChanges.Look, label: String) {
    val c = Herdr.colors
    val k = look.check ?: return
    fun row(ok: Boolean, title: String, detail: String) =
        @Composable { ActionRow(title, detail = detail, icon = if (ok) HerdrIcons.CircleCheck else HerdrIcons.CircleX, iconTint = if (ok) c.done else c.red) }
    listOf(
        row(k.checkout != null, "herdr-android checkout", k.checkout ?: "None found: clone the repository there"),
        row(k.sdk != null, "Android SDK", k.sdk ?: "Not found: install it, and set ANDROID_HOME or the checkout's sdk.dir"),
        row(k.java != null, "JDK", k.java?.let { "Java $it" } ?: "None 17 or newer"),
        row(look.space != null, "Assistant", look.space?.let { "The ${it.name} space on Shepherdr" } ?: "No herdr-android space on Shepherdr there"),
    ).forEachIndexed { i, r ->
        if (i > 0) RowDivider(inset = 0.dp)
        r()
    }
    RowDivider(inset = 0.dp)
    ActionRow(
        "Release key", icon = HerdrIcons.KeyRound, iconTint = if (k.signsHere) c.done else c.muted,
        detail = when (k.key) {
            BuilderCheck.Key.Here -> "In herdr-secret: updates are signed there"
            BuilderCheck.Key.Main -> "From the main machine's herdr-secret, asked as it builds"
            BuilderCheck.Key.None -> "Not kept there: each update is CI's release of the merge"
        },
    )
    val missing = look.missing
    Text(
        if (missing.isEmpty()) "$label can build the app." else "$label can't build the app yet: it lacks ${words(missing)}.",
        style = MaterialTheme.typography.bodyMedium, color = if (missing.isEmpty()) c.text else c.red, modifier = Modifier.padding(top = Space.s),
    )
}

private fun words(list: List<String>): String = when (list.size) {
    0 -> ""
    1 -> list[0]
    else -> list.dropLast(1).joinToString(", ") + " and " + list.last()
}
