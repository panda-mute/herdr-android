package com.makefunstuff.herdr.ui.machines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.health.HealthTile
import com.makefunstuff.herdr.ui.logins.LoginsRow
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.usage.UsageRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * A machine's places, over its spaces: Health, Usage and Agent logins as one row of tiles, each
 * with its live line; a tile is framed in a state colour only when something there is wrong.
 */
@Composable
internal fun MachinePlaces(
    session: HostSession,
    onHealth: () -> Unit,
    onUsage: () -> Unit,
    onLogins: () -> Unit = {},
) {
    val state by session.state.collectAsStateWithLifecycle()
    if (!state.online) return
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        val tile = Modifier.weight(1f).fillMaxHeight()
        HealthTile(session, tile, onHealth)
        UsageRow(session, onUsage, tile = true, modifier = tile)
        LoginsRow(session, onLogins, tile = true, modifier = tile)
    }
}

/**
 * A place on a machine as a tile: its icon, its name and one live line in [color]. [edge] marks one
 * that wants a look (low on memory, near a limit).
 */
@Composable
internal fun PlaceTile(
    icon: ImageVector,
    title: String,
    line: String,
    color: Color,
    modifier: Modifier,
    onOpen: () -> Unit,
    openLabel: String,
    edge: Color = Herdr.colors.lineStrong,
    iconTint: Color = Herdr.colors.text,
) {
    val c = Herdr.colors
    QuietCard(
        modifier.semantics(mergeDescendants = true) { contentDescription = "$title: $line" },
        edge = edge, onClick = onOpen, onClickLabel = openLabel,
    ) {
        Column(Modifier.padding(horizontal = Space.s, vertical = Space.s)) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(Space.xs))
            Text(title.uppercase(), style = MaterialTheme.typography.titleSmall, color = c.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
