package com.makefunstuff.herdr.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.makefunstuff.herdr.core.push.AlertRules
import com.makefunstuff.herdr.core.push.Notify
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.IconTile
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.design.SwitchRow
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

/**
 * What reaches the phone (Settings → Notifications): a switch for each kind (Needs you, Finished,
 * Went wrong), keeping quiet while you are at a machine, and the spaces that picked their own Notify
 * (in the drawer or an agent's long-press sheet), each with Reset.
 */
@Composable
fun AlertRulesScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val st by container.push.state.collectAsStateWithLifecycle()
    val machines by container.machines.machines.collectAsStateWithLifecycle()
    // Whether you are at a machine now, as of opening the screen (or coming back to it).
    var at by remember { mutableStateOf(container.quietWork.presence.where(System.currentTimeMillis())) }
    LifecycleResumeEffect(Unit) {
        at = container.quietWork.presence.where(System.currentTimeMillis())
        onPauseOrDispose {}
    }
    val r = st.rules
    fun change(f: (AlertRules) -> AlertRules) = container.push.change { it.copy(rules = f(it.rules)) }

    val c = Herdr.colors
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = c.ground,
        topBar = { HerdrTopBar("What reaches the phone", subtitle = "Notifications", onBack = { nav.popBackStack() }) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).padding(bottom = Space.xl),
        ) {
            if (!st.on) {
                Notice(
                    "Notifications while the app is closed are off: these apply while the app runs. Turn them on in Settings.",
                    modifier = Modifier.padding(top = Space.m), icon = HerdrIcons.BellOff,
                )
            }
            SectionHeading("Notify me about", top = Space.l)
            QuietCard(padded = true) {
                SwitchRow(
                    "Needs you",
                    detail = "An agent asks something, a board task waits for your yes or your answer, a machine runs low. Comes through quiet hours.",
                    checked = r.needsYou,
                ) { on -> change { it.copy(needsYou = on) } }
                RowDivider(inset = 0.dp)
                SwitchRow(
                    "Finished",
                    detail = "An agent ended its turn, an AI quick command is done. Off by default: for the spaces you care about, pick Notify: All instead.",
                    checked = r.finished,
                ) { on -> change { it.copy(finished = on) } }
                RowDivider(inset = 0.dp)
                SwitchRow(
                    "Went wrong",
                    detail = "A review or a check failed, or a task went wrong. Comes through quiet hours.",
                    checked = r.wentWrong,
                ) { on -> change { it.copy(wentWrong = on) } }
                RowDivider(inset = 0.dp)
                SwitchRow(
                    "Limits",
                    detail = "An AI provider at 80% or 95% of a limit or out of it, a task handed to another agent because of one. Waits through quiet hours.",
                    checked = r.limits,
                ) { on -> change { it.copy(limits = on) } }
            }

            SectionHeading("While you're at a computer")
            QuietCard(padded = true) {
                SwitchRow(
                    "Hold while I'm at the machine",
                    detail = "Nothing buzzes the phone while you typed in herdr in the last 2 minutes or a machine's screen is unlocked. " +
                        "The notifications still arrive, silently.",
                    checked = r.holdAtMachine,
                ) { on -> change { it.copy(holdAtMachine = on) } }
                if (r.holdAtMachine && at != null) {
                    Notice("Holding now: you're at $at.", modifier = Modifier.padding(top = Space.s), color = c.accent, icon = HerdrIcons.Monitor)
                }
            }
            Text(
                "herdr-push tells from the herdr window's last keystroke, the screen lock, or a touch of ~/.local/state/herdr-push/present.",
                style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = Space.s),
            )

            SectionHeading("Spaces")
            if (r.spaces.isEmpty()) {
                QuietCard(padded = true) {
                    Text(
                        "Every space follows the switches above (Notify: ${r.everywhere.label}). Long-press a space in the drawer, " +
                            "or an agent, to pick All, Needs me or Off for its space.",
                        style = MaterialTheme.typography.bodyMedium, color = c.muted,
                    )
                }
            } else {
                QuietCard {
                    r.spaces.entries.sortedBy { it.key }.forEachIndexed { i, (space, n) ->
                        if (i > 0) RowDivider()
                        val machine = machines.firstOrNull { space.startsWith("${it.id}/") }
                        SpaceRow(
                            space.removePrefix("${machine?.id}/"), n, machine?.label.orEmpty(), showMachine = machines.size > 1, follows = r.everywhere,
                        ) { change { it.withSpace(space, null) } }
                    }
                }
            }
        }
    }
}

/** A space that picked its own Notify: its name, its choice, and Reset, back to the switches' [follows]. */
@Composable
private fun SpaceRow(name: String, n: Notify, machine: String, showMachine: Boolean, follows: Notify, onReset: () -> Unit) {
    ListRow(
        name,
        subtitle = listOfNotNull("Notify: ${n.label}", machine.takeIf { showMachine && it.isNotEmpty() }).joinToString(" · "),
        leading = { IconTile(if (n == Notify.Off) HerdrIcons.BellOff else HerdrIcons.Bell) },
        trailing = { QuietButton(onClick = onReset, modifier = Modifier.semantics { contentDescription = "Reset $name to ${follows.label}" }) { Text("Reset") } },
    )
}

/** The way here from the Notifications card, with how the rules are set. */
internal fun rulesLabel(rules: AlertRules): String = listOfNotNull(
    rules.label,
    "held at the machine".takeIf { rules.holdAtMachine },
    when (rules.spaces.size) { 0 -> null; 1 -> "1 space its own"; else -> "${rules.spaces.size} spaces their own" },
).joinToString(" · ")
