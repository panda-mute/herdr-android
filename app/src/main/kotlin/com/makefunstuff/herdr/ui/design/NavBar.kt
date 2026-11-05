package com.makefunstuff.herdr.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.theme.caps

/**
 * The bottom bar of the top-level screens, as the desktop's tab strip: chrome under a 1dp rule.
 * Each tab is a glyph over its name in mono capitals; the chosen one is amber, with an amber bar
 * along its top edge, as the desktop marks what is shown.
 */
@Composable
fun HerdrNavBar(content: @Composable RowScope.() -> Unit) {
    val c = Herdr.colors
    Column(Modifier.fillMaxWidth().background(c.panel).drawBehind {
        drawLine(c.lineStrong, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx())
    }) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().heightIn(min = 60.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
fun RowScope.NavItem(label: String, icon: ImageVector, selected: Boolean, badge: Int = 0, onClick: () -> Unit) {
    val c = Herdr.colors
    val bar by animateColorAsState(if (selected) c.accent else Color.Transparent, tween(180), label = "nav")
    Column(
        Modifier.weight(1f).heightIn(min = 60.dp)
            .drawBehind { drawRect(bar, size = androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx())) }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = if (badge > 0) "$label, $badge need you" else label
                this.selected = selected
                role = Role.Tab
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.width(52.dp).height(26.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (selected) c.accent else c.muted, modifier = Modifier.size(20.dp))
            }
            if (badge > 0) CountBadge(badge, Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = (-2).dp))
        }
        Spacer(Modifier.height(Space.xs))
        Text(
            label.caps(), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) c.accent else c.muted,
        )
    }
}

/**
 * The views of one thing (a task's Chat, Files, Changes and Pull request), as the desktop's tab strip under a
 * header: on `panel` over a 1dp rule, each name in mono capitals, the shown one amber with an amber bar along its
 * bottom edge. It scrolls sideways when they don't fit.
 */
@Composable
fun ViewTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    Row(
        modifier.fillMaxWidth().background(c.panel)
            .drawBehind { drawLine(c.lineStrong, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .horizontalScroll(rememberScrollState()).padding(horizontal = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.heightIn(min = 44.dp)
                    .drawBehind { if (on) drawRect(c.accent, Offset(0f, size.height - 2.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx())) }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab, onClick = { onSelect(i) })
                    .semantics { this.selected = on }
                    .padding(horizontal = Space.m),
                contentAlignment = Alignment.Center,
            ) {
                Text(label.caps(), style = MaterialTheme.typography.labelLarge, maxLines = 1, color = if (on) c.accent else c.muted)
            }
        }
    }
}
