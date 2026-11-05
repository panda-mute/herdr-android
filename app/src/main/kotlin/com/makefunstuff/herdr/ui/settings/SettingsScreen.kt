package com.makefunstuff.herdr.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.makefunstuff.herdr.BuildConfig
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.data.IdentityStore
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.copyToClipboard
import com.makefunstuff.herdr.ui.design.ErrorMessage
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.MonoBlock
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val keyState by container.identity.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<KeyDialog?>(null) }

    val c = Herdr.colors
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = c.ground,
        topBar = { HerdrTopBar("Settings", onBack = { nav.popBackStack() }) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).padding(top = Space.xs, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            QuietCard(padded = true) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    Text("SSH key", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text(
                        "The app signs in to your machines with this key. Its private half is encrypted with a key kept in this phone's secure hardware, so it stays through app updates. Back it up to keep it when you reinstall the app or move to a new phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.muted,
                    )
                    when (val state = keyState) {
                        IdentityStore.State.Loading -> LoadingState("Reading the key")
                        is IdentityStore.State.Ready -> {
                            MonoBlock(state.publicKey)
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                SecondaryButton(onClick = { context.copyToClipboard("public key", state.publicKey, "Public key copied") }) {
                                    Icon(HerdrIcons.Copy, null, Modifier.size(18.dp)); Text("Copy")
                                }
                                SecondaryButton(onClick = { dialog = KeyDialog.Export }) {
                                    Icon(HerdrIcons.Upload, null, Modifier.size(18.dp)); Text("Back up")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                                QuietButton(onClick = { dialog = KeyDialog.Import(replacing = true) }) { Text("Restore or import") }
                                QuietButton(onClick = { dialog = KeyDialog.New(replacing = true) }) { Text("New key") }
                            }
                        }
                        IdentityStore.State.Missing, is IdentityStore.State.Unreadable -> {
                            val unreadable = (state as? IdentityStore.State.Unreadable)?.reason
                            if (unreadable != null) ErrorMessage("The saved key can't be read ($unreadable). It is kept until you restore or replace it.")
                            else Text("No key yet.", style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                SecondaryButton("Restore or import") { dialog = KeyDialog.Import(replacing = unreadable != null) }
                                QuietButton(onClick = { dialog = KeyDialog.New(replacing = unreadable != null) }) { Text("New key") }
                            }
                        }
                    }
                }
            }
            QuietCard(padded = true) { GuardRow(onOpen = { nav.navigate(Routes.GUARD) }) }
            QuietCard(padded = true) { QuickRepliesRow(onOpen = { nav.navigate(Routes.COMPOSER) }) }
            QuietCard(padded = true) { TextSizeCard() }
            QuietCard(padded = true) { VoiceCard() }
            QuietCard(padded = true) { NotificationsCard(onQuietHours = { nav.navigate(Routes.QUIET) }, onRules = { nav.navigate(Routes.RULES) }) }
            AutopilotCard(onOpen = { nav.navigate(Routes.autopilot(it)) })
            ChangeAppCard(onOpen = { nav.navigate(Routes.CHANGE_APP) })
            QuietCard(padded = true) { BriefCard(onOpen = { nav.navigate(Routes.BRIEF) }, onSchedule = { nav.navigate(Routes.BRIEF_SETTINGS) }) }
            QuietCard(padded = true) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text("About", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text("Herdr for Android ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Talks to herdr over SSH using its socket API. Machines stay connected while the app is open.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.muted,
                    )
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        KeyDialog.Export -> ExportKeyDialog(onDismiss = { dialog = null })
        is KeyDialog.Import -> ImportKeyDialog(replacing = d.replacing, onDismiss = { dialog = null })
        is KeyDialog.New -> NewKeyDialog(replacing = d.replacing, onDismiss = { dialog = null })
    }
}

private sealed interface KeyDialog {
    data object Export : KeyDialog
    data class Import(val replacing: Boolean) : KeyDialog
    data class New(val replacing: Boolean) : KeyDialog
}

