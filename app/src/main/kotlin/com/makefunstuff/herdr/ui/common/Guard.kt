package com.makefunstuff.herdr.ui.common

import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.makefunstuff.herdr.core.guard.Guarded
import com.makefunstuff.herdr.data.GuardStore
import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The fingerprint before an action that can't be taken back ([Guarded]): `guard.run(Guarded.Merge,
 * "Approve and merge AND-9?") { … }` runs the block at once when that action doesn't ask, after
 * the fingerprint (or the phone's PIN) when it does, and after a tap on Confirm on a phone with no
 * screen lock for the one-tap actions (the others have their own dialog already).
 */
class Guard internal constructor(private val store: GuardStore, private val scope: CoroutineScope) {
    internal var confirming by mutableStateOf<Confirming?>(null)

    internal class Confirming(val title: String, val detail: String?, val block: () -> Unit)

    fun run(action: Guarded, title: String, detail: String? = null, block: () -> Unit) {
        when {
            !store.wants(action) -> block()
            store.unlock.canAsk() -> scope.launch { if (store.ask(title, detail)) block() }
            action.confirmed -> block()
            else -> confirming = Confirming(title, detail, block)
        }
    }

    /** Asks for the fingerprint before turning a protection off, so the open app alone can't. */
    fun check(title: String, block: () -> Unit) {
        if (!store.unlock.canAsk()) return block()
        scope.launch { if (store.ask(title, null)) block() }
    }
}

val LocalGuard = staticCompositionLocalOf<Guard> { error("no GuardHost") }

/**
 * The app's screens under the fingerprint: gives them [LocalGuard], shows its in-app confirmation,
 * and the app lock over them while it is up (they stay where they were behind it).
 */
@Composable
fun GuardHost(store: GuardStore, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val guard = remember(store) { Guard(store, scope) }
    val locked by store.locked.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalGuard provides guard) {
        Box(Modifier.fillMaxSize()) {
            Box(if (locked) Modifier.clearAndSetSemantics {} else Modifier) { content() }
            if (locked) LockScreen(onUnlock = { scope.launch { store.open() } })
        }
        // It asks by itself as it comes up; Unlock asks again after a cancel.
        LaunchedEffect(locked) { if (locked) store.open() }
        guard.confirming?.let { c ->
            HerdrDialog(
                onDismissRequest = { guard.confirming = null },
                title = { Text(c.title) },
                text = {
                    Text(
                        listOfNotNull(
                            c.detail,
                            "This phone has no screen lock, so the app asks here instead of for your fingerprint. Set one in Android's settings to protect this.",
                        ).joinToString("\n\n"),
                    )
                },
                dismissButton = { QuietButton(onClick = { guard.confirming = null }) { Text("Cancel") } },
                confirmButton = { QuietButton(onClick = { guard.confirming = null; c.block() }) { Text("Confirm") } },
            )
        }
    }
}

/** The app lock: the screens are hidden until the fingerprint (or PIN) is given. */
@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    val c = Herdr.colors
    Box(
        // Opaque, and it takes every touch: nothing behind it can be read or pressed.
        Modifier.fillMaxSize().background(c.ground).pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 360.dp).padding(Space.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Box(Modifier.size(72.dp).clip(RectangleShape).background(c.accentTint), contentAlignment = Alignment.Center) {
                Icon(HerdrIcons.Lock, null, tint = c.accent, modifier = Modifier.size(32.dp))
            }
            Text("Herdr is locked", style = MaterialTheme.typography.titleLarge, color = c.strong)
            Text(
                "It holds the SSH key to your machines. Unlock it with your fingerprint or the phone's PIN.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.muted,
                textAlign = TextAlign.Center,
            )
            PrimaryButton(onClick = onUnlock, modifier = Modifier.padding(top = Space.s)) {
                Icon(HerdrIcons.FingerprintPattern, null, Modifier.size(18.dp)); Text("Unlock")
            }
        }
    }
}
