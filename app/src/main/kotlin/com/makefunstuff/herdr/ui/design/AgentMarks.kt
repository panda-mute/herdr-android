package com.makefunstuff.herdr.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The leading cell of an agent or program row: its chip-package icon, as the Herdr desktop's
 * workbench draws programs (Claude and Codex their own, other agents the generic agent chip, shells
 * and tools theirs). No brand logos: the console marks everything the same way.
 */
@Composable
fun AgentMark(agent: String?, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    ChipIcon(chipFor(agent ?: "agent"), modifier, size)
}
