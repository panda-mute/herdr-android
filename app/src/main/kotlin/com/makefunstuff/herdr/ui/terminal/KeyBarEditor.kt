package com.makefunstuff.herdr.ui.terminal

import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.terminal.BarKey
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.core.terminal.KeyBar
import com.makefunstuff.herdr.data.KeyBarStore
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTextField
import com.makefunstuff.herdr.ui.design.MenuSection
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.SheetTitle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Mono
import com.makefunstuff.herdr.ui.theme.Radius
import com.makefunstuff.herdr.ui.theme.Space

/**
 * The key bar's editor: the keys on it, in order, each moved earlier or later or taken off; what can
 * be added, by kind (a tap puts a key on the bar or takes it off); a shortcut of one's own; and the
 * keys it started with.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyBarEditor(store: KeyBarStore, saved: KeyBarStore.Saved) {
    val keys = saved.bar
    SheetTitle("Key bar", "The keys over the keyboard, in this order. Ctrl, Alt and Shift stay on for the next key; tapped twice, until tapped again.")
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.s)) {
        MenuSection("On the bar · ${keys.size}")
        keys.forEachIndexed { i, key ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KeyChip(KeyBar.label(key))
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    Text(KeyBar.name(key), style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    KeyBar.purpose(key)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                BarIcon(HerdrIcons.ChevronUp, "Move ${KeyBar.name(key)} earlier", { store.move(key, -1) }, enabled = i > 0)
                BarIcon(HerdrIcons.ChevronDown, "Move ${KeyBar.name(key)} later", { store.move(key, 1) }, enabled = i < keys.lastIndex)
                BarIcon(HerdrIcons.X, "Take ${KeyBar.name(key)} off", { store.remove(key) })
            }
        }
        if (keys.isEmpty()) {
            Text(
                "No keys: only the edit key is on the bar.",
                style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted,
                modifier = Modifier.padding(horizontal = Space.m, vertical = Space.s),
            )
        }

        KeyBar.CATALOG.forEach { (group, specs) ->
            MenuSection(group)
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = Space.m),
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                specs.mapNotNull(BarKey::parse).forEach { key ->
                    val on = key in keys
                    HerdrChip(
                        on,
                        onClick = { if (on) store.remove(key) else store.add(key) },
                        label = { Text(KeyBar.label(key), fontFamily = Mono, maxLines = 1) },
                        modifier = Modifier.semantics { contentDescription = KeyBar.name(key) + if (on) ", on the bar" else "" },
                    )
                }
            }
        }

        MenuSection("A shortcut of your own")
        OwnShortcut(onAdd = { store.add(it) })

        Row(Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.m).navigationBarsPadding(), horizontalArrangement = Arrangement.End) {
            QuietButton(onClick = { store.reset() }) { Text("Back to the first keys") }
        }
    }
}

/** Ctrl, Alt and Shift to hold, and the key: a letter or sign, or a key's name (F5, Home, PgUp). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OwnShortcut(onAdd: (BarKey) -> Unit) {
    var ctrl by remember { mutableStateOf(true) }
    var alt by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    val spec = (if (ctrl) "C-" else "") + (if (alt) "M-" else "") + (if (shift) "S-" else "") + key.trim()
    val parsed = key.trim().takeIf { it.isNotEmpty() }?.let { BarKey.parse(spec) }
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            HerdrChip("Ctrl", ctrl, { ctrl = !ctrl })
            HerdrChip("Alt", alt, { alt = !alt })
            HerdrChip("Shift", shift, { shift = !shift })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            HerdrTextField(
                value = key,
                onValueChange = { key = it.take(8) },
                label = "Key",
                hint = when {
                    key.isBlank() -> "A letter or sign, or F5, Home, PgUp, Left…"
                    parsed == null -> "Not a key this app knows"
                    else -> KeyBar.name(parsed)
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Space.s))
            SecondaryButton(onClick = { parsed?.let(onAdd); key = "" }, enabled = parsed != null) {
                Text(parsed?.let { "Add " + KeyBar.label(it) } ?: "Add")
            }
        }
    }
}

/** A key as it looks on the bar, small. */
@Composable
private fun KeyChip(label: String) {
    Box(
        Modifier.widthIn(min = 44.dp).heightIn(min = 32.dp)
            .border(1.dp, Herdr.colors.line, RectangleShape)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Mono, style = MaterialTheme.typography.labelLarge, color = Herdr.colors.text, maxLines = 1)
    }
}
