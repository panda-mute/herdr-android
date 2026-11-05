package com.makefunstuff.herdr.ui.spaces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.makefunstuff.herdr.core.push.Notify
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.SegmentedPills
import com.makefunstuff.herdr.ui.manage.ManageSheet
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

/**
 * A space's one alert choice, as Shepherdr 3.1's: Notify All / Needs me / Off, picked in place, with a
 * line on what it lets through. [space] is its key ([Notify.key]); a space that picks what the
 * notification settings give it anyway follows them again.
 */
@Composable
fun NotifyPicker(space: String, modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val st by container.push.state.collectAsStateWithLifecycle()
    val now = st.rules.notifyOf(space)
    val c = Herdr.colors
    Column(modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(if (now == Notify.Off) HerdrIcons.BellOff else HerdrIcons.Bell, null, tint = c.muted, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(Space.m))
            Text("Notify", style = MaterialTheme.typography.bodyLarge, color = c.text)
        }
        SegmentedPills(
            Notify.entries.map { it.label }, selected = now.ordinal,
            onSelect = { i -> container.push.change { it.copy(rules = it.rules.withSpace(space, Notify.entries[i])) } },
            modifier = Modifier.padding(start = INDENT, top = Space.s),
        )
        Text(
            now.hint + if (space !in st.rules.spaces) ", as in Settings" else "",
            style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(start = INDENT, top = Space.xs),
        )
    }
}

/** Under the label, as a sheet row's lines sit: past its icon. */
private val INDENT = 24.dp + Space.m

/** A space's sheet from a long press in the drawer: its Notify. */
@Composable
fun SpaceSheet(slot: SpaceSlot, machineLabel: String, onDismiss: () -> Unit) {
    ManageSheet(slot.name, machineLabel, onDismiss) {
        NotifyPicker(slot.key)
    }
}
