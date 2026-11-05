package com.makefunstuff.herdr.ui.settings

import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.makefunstuff.herdr.core.chat.QuickReplies
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.SwitchRow
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

/** Settings' row for the quick replies: on or off, and how many. */
@Composable
fun QuickRepliesRow(onOpen: () -> Unit) {
    val st by LocalContainer.current.composer.state.collectAsStateWithLifecycle()
    val c = Herdr.colors
    val n = st.replies.shown.size
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onOpen).padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HerdrIcons.MessageSquare, null, tint = c.muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text("Quick replies", style = MaterialTheme.typography.titleMedium, color = c.strong)
            Text(
                if (st.replies.on) "On · ${if (n == 1) "1 reply" else "$n replies"} over an agent's message box" else "Off",
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
            )
        }
        Spacer(Modifier.width(Space.s))
        Icon(HerdrIcons.ChevronRight, null, tint = c.muted, modifier = Modifier.size(18.dp))
    }
}

/**
 * The quick replies: one tap on one sends it to the agent (held while it works, like anything
 * typed). Off until turned on; each can be edited, taken out or added to, and the three it starts
 * with come back with Restore.
 */
@Composable
fun QuickRepliesScreen(nav: NavHostController) {
    val store = LocalContainer.current.composer
    val st by store.state.collectAsStateWithLifecycle()
    val replies = st.replies
    val c = Herdr.colors
    fun set(list: List<String>) = store.setReplies(replies.copy(replies = list))

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = c.ground,
        topBar = { HerdrTopBar("Quick replies", onBack = { nav.popBackStack() }) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = Space.screen).padding(top = Space.xs, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            QuietCard(padded = true) {
                SwitchRow(
                    "Show quick replies",
                    detail = "Over an agent's message box while it is empty. One tap sends one; while the agent works it waits with the held messages.",
                    checked = replies.on,
                    onChange = { store.setReplies(replies.copy(on = it)) },
                )
            }
            QuietCard(padded = true) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text("Replies", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    replies.replies.forEachIndexed { i, r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HerdrField(
                                value = r,
                                onValueChange = { v -> set(replies.replies.toMutableList().also { it[i] = v.replace('\n', ' ').take(QuickReplies.MAX_LENGTH) }) },
                                singleLine = true,
                                placeholder = { Text("A reply") },
                                modifier = Modifier.weight(1f).semantics { contentDescription = "Reply ${i + 1}" },
                            )
                            Box(
                                Modifier.size(48.dp).clip(RectangleShape)
                                    .clickable(role = Role.Button, onClick = { set(replies.replies.toMutableList().also { it.removeAt(i) }) })
                                    .semantics { contentDescription = "Remove “$r”" },
                                contentAlignment = Alignment.Center,
                            ) { Icon(HerdrIcons.X, null, tint = c.muted, modifier = Modifier.size(18.dp)) }
                        }
                    }
                    if (replies.replies.isEmpty()) Text("No replies yet.", style = MaterialTheme.typography.bodyMedium, color = c.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                        SecondaryButton(onClick = { set(replies.replies + "") }, enabled = replies.replies.size < 12) {
                            Icon(HerdrIcons.Plus, null, Modifier.size(18.dp)); Text("Add a reply")
                        }
                        if (replies.replies != QuickReplies.DEFAULT) QuietButton(onClick = { set(QuickReplies.DEFAULT) }) { Text("Restore") }
                    }
                }
            }
        }
    }
}
