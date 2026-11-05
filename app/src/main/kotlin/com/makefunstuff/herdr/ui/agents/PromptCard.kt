package com.makefunstuff.herdr.ui.agents

import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makefunstuff.herdr.core.chat.BlockedPrompt
import com.makefunstuff.herdr.core.chat.HintKey
import com.makefunstuff.herdr.core.chat.OptionRole
import com.makefunstuff.herdr.core.chat.PromptStep
import com.makefunstuff.herdr.core.chat.PromptTag
import com.makefunstuff.herdr.ui.common.CodeBlock
import com.makefunstuff.herdr.ui.common.Pill
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Radius
import com.makefunstuff.herdr.ui.theme.Space

/** A press on the prompt card; the chat carries it out, on the same question only. */
sealed interface PromptPress {
    /** Picks an option: Yes, No, any other; "Always" is guarded by the chat. */
    data class Option(val index: Int) : PromptPress
    /** "No, tell it…": opens the reply; the option is pressed when the words go. */
    data class Tell(val index: Int) : PromptPress
    /** A multiple choice: these ticked, then sent. */
    data class Ticks(val indices: Set<Int>) : PromptPress
    /** A key the footer names (Tab to amend, Esc to cancel); [reply] opens the composer after it. */
    data class Key(val key: String, val reply: Boolean) : PromptPress
    /** Bare keys, for a menu the app couldn't read. */
    data class Keys(val keys: List<String>) : PromptPress
}

/**
 * The question a blocked agent is asking, docked over the composer so it can't scroll away, as the
 * web UI's prompt card (`--bg-elevated`, a strong hairline, `--radius-xl`, its title the card's one
 * red) made for thumbs: the choices are buttons, Yes first and filled; a permanent "always" carries
 * the fingerprint when that asks; "No, tell it…" opens the reply; the agent's footer hints are real
 * Amend (Tab) and Cancel (Esc) buttons. A tag says what kind of action it is. A question of its own
 * (Claude's form) lists its options with their descriptions, boxes to tick and Submit for a multiple
 * choice, and chips for the steps of a form of several questions.
 *
 * The question and its details scroll inside the card when it is taller than the room it gets; the
 * buttons stay. [answering] holds the buttons while an answer is on its way.
 */
@Composable
fun PromptCard(
    prompt: BlockedPrompt?,
    screen: String,
    answering: Boolean,
    alwaysGuarded: Boolean,
    onPress: (PromptPress) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Herdr.colors
    val shape = RectangleShape
    Surface(
        color = c.card,
        // Framed in the blocked red: the chat's status line over it already says it needs you.
        border = BorderStroke(1.dp, c.red),
        shape = shape,
        modifier = modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            val tag = remember(prompt) { prompt?.let { PromptTag.of(it) } }
            if (answering || tag != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (answering) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.muted)
                else if (tag != null) {
                    Pill(
                        tag.text,
                        color = when (tag.risk) {
                            PromptTag.Risk.Danger -> c.red
                            PromptTag.Risk.Caution -> c.notice
                            PromptTag.Risk.Plain -> null
                        },
                    )
                }
            }
            if (prompt == null) {
                UnreadMenu(screen, onPress)
                return@Column
            }
            if (prompt.steps.isNotEmpty()) Steps(prompt.steps)
            // The words give way when the card is short: they scroll, the buttons stay.
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(prompt.question, style = MaterialTheme.typography.bodyLarge, color = c.strong)
                Details(prompt.details)
            }
            if (approval(prompt)) Approval(prompt, answering, alwaysGuarded, onPress)
            else Options(prompt, answering, onPress)
        }
    }
}

/**
 * The docked card folded to one line: while the keyboard is up, or while the reply to "No, tell
 * it…" is being written ([telling]), so the composer keeps its room. A tap opens it again; for a
 * reply, X takes back the no.
 */
@Composable
fun PromptBar(question: String, telling: Boolean, onOpen: () -> Unit, onCancelTell: () -> Unit, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    val shape = RectangleShape
    Surface(
        color = c.card,
        border = BorderStroke(1.dp, c.lineStrong),
        shape = shape,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onOpen).padding(start = Space.l, end = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (telling) Icon(HerdrIcons.Reply, null, tint = c.red, modifier = Modifier.size(16.dp)) else StatusDot(c.red)
            Spacer(Modifier.width(Space.s))
            Column(Modifier.weight(1f).padding(vertical = Space.s)) {
                Text(
                    if (telling) "Saying no · tell it what to do instead" else "Needs you",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.red,
                )
                Text(question, style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (telling) {
                Box(
                    Modifier.size(48.dp).clip(RectangleShape).clickable(role = Role.Button, onClick = onCancelTell)
                        .semantics { contentDescription = "Keep the question" },
                    contentAlignment = Alignment.Center,
                ) { Icon(HerdrIcons.X, null, tint = c.muted, modifier = Modifier.size(18.dp)) }
            } else {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(HerdrIcons.ChevronUp, "Show the question", tint = c.muted, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** A permission to give: few short choices with a yes among them, as buttons. Claude's own questions list their options. */
private fun approval(p: BlockedPrompt): Boolean =
    !p.multiSelect && p.steps.isEmpty() && p.customIndex == null && p.options.size <= 4 &&
        p.options.none { it.description != null } && p.approveIndex != null

/** The command or the folder it asks about, in the one mono box; three lines until it is opened. */
@Composable
private fun Details(lines: List<String>) {
    if (lines.isEmpty()) return
    val c = Herdr.colors
    // Claude's tool header ("Bash command", "Edit file") names the box rather than sitting in it.
    val header = lines.first().takeIf { lines.size > 1 && TOOL_HEADER.matches(it) }
    val details = if (header != null) lines.drop(1) else lines
    if (header != null) Text(header, style = MaterialTheme.typography.labelMedium, color = c.muted)
    var all by remember(details) { mutableStateOf(false) }
    val shown = if (all) details else details.take(3)
    SelectionContainer {
        Text(
            shown.joinToString("\n"),
            style = CodeStyle.copy(fontSize = 12.sp, lineHeight = 18.sp),
            color = c.text,
            modifier = Modifier.fillMaxWidth().clip(RectangleShape).background(c.ground)
                .border(1.dp, c.line, RectangleShape).padding(horizontal = Space.m, vertical = Space.s),
        )
    }
    if (details.size > 3 && !all) {
        Text(
            "Show more",
            style = MaterialTheme.typography.labelLarge,
            color = c.accent,
            modifier = Modifier.heightIn(min = 48.dp).clickable { all = true }.padding(vertical = Space.m),
        )
    }
}

/**
 * Yes first and filled, the permanent allow beside it (with the fingerprint when that asks), then
 * a row of the rest: "No, tell it…", a plain no, any other option, and the footer's keys (Amend,
 * Cancel). Each is a 48dp button; long labels take two lines.
 */
@Composable
private fun Approval(p: BlockedPrompt, answering: Boolean, alwaysGuarded: Boolean, onPress: (PromptPress) -> Unit) {
    val yes = p.approveIndex!!
    val always = p.options.indices.filter { it != yes && p.role(it) == OptionRole.Always }
    val rest = p.options.indices.filter { it != yes && it !in always }
    val keys = p.hintKeys
    Column(Modifier.padding(top = Space.xs), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        ButtonRow {
            PrimaryButton(onClick = { onPress(PromptPress.Option(yes)) }, modifier = Modifier.weight(1f), enabled = !answering, contentPadding = Tight) {
                Icon(HerdrIcons.Check, null, Modifier.size(16.dp)); Label(p.shortLabel(yes))
            }
            always.forEach { i ->
                SecondaryButton(
                    onClick = { onPress(PromptPress.Option(i)) }, enabled = !answering, contentPadding = Tight,
                    modifier = Modifier.weight(1f).semantics { if (alwaysGuarded) stateDescription = "Asks for your fingerprint" },
                ) {
                    Icon(if (alwaysGuarded) HerdrIcons.FingerprintPattern else HerdrIcons.ShieldCheck, null, Modifier.size(16.dp)); Label(p.shortLabel(i))
                }
            }
        }
        val others = buildList<@Composable RowScope.() -> Unit> {
            rest.forEach { i ->
                when (p.role(i)) {
                    OptionRole.TellInstead -> add { Choice(p.shortLabel(i), HerdrIcons.Reply, answering) { onPress(PromptPress.Tell(i)) } }
                    OptionRole.No -> add { Choice(p.shortLabel(i), HerdrIcons.CircleX, answering) { onPress(PromptPress.Option(i)) } }
                    else -> add { Choice(p.shortLabel(i), null, answering) { onPress(PromptPress.Option(i)) } }
                }
            }
            keys.forEach { k -> add { KeyChoice(k, answering, onPress) } }
        }
        others.chunked(3).forEach { row -> ButtonRow { row.forEach { it() } } }
    }
}

/**
 * Claude's own question: each option a row with its number as a keycap and its description, a tap
 * answering it; a multiple choice ticks boxes, then Submit. "Type something." is the composer below,
 * "Chat about this" a button with the footer's keys.
 */
@Composable
private fun Options(p: BlockedPrompt, answering: Boolean, onPress: (PromptPress) -> Unit) {
    val c = Herdr.colors
    var ticked by remember(p) { mutableStateOf(p.options.indices.filter { p.options[it].checked == true }.toSet()) }
    val shown = p.options.indices.filter { p.role(it) != OptionRole.Custom && p.role(it) != OptionRole.Chat }
    Column(Modifier.padding(top = Space.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        shown.forEach { i ->
            val option = p.options[i]
            val box = p.multiSelect && option.checked != null
            val on = i in ticked
            val pointed = i == p.selected
            val row = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RectangleShape)
                .background(if (pointed && !p.multiSelect) c.hover else Color.Transparent)
            Row(
                (if (box) row.toggleable(on, enabled = !answering, role = Role.Checkbox) { ticked = if (it) ticked + i else ticked - i }
                else row.clickable(enabled = !answering, role = Role.Button) { onPress(PromptPress.Option(i)) })
                    .padding(horizontal = Space.s, vertical = Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (box) {
                    Box(
                        Modifier.size(22.dp).clip(RectangleShape)
                            .background(if (on) c.primary else Color.Transparent)
                            .border(1.dp, if (on) c.primary else c.lineStrong, RectangleShape),
                        contentAlignment = Alignment.Center,
                    ) { if (on) Icon(HerdrIcons.Check, null, tint = c.onPrimary, modifier = Modifier.size(14.dp)) }
                } else {
                    // The menu's number as a keycap (`.prompt-card-option`).
                    Box(
                        Modifier.defaultMinSize(minWidth = 22.dp, minHeight = 22.dp).clip(RectangleShape)
                            .border(1.dp, c.lineStrong, RectangleShape).padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(option.number?.toString() ?: "${i + 1}", style = CodeStyle.copy(fontSize = 11.sp, lineHeight = 14.sp), color = c.muted) }
                }
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    // Claude marks its own pick "(Recommended)": a tag reads better than the suffix.
                    val recommended = option.label.endsWith("(Recommended)", ignoreCase = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            option.label.removeSuffix("(Recommended)").removeSuffix("(recommended)").trim(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (pointed || on) c.strong else c.text,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (recommended) { Spacer(Modifier.width(Space.s)); Pill("Recommended", color = c.done) }
                    }
                    option.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
                }
            }
        }
        if (p.customIndex != null && !p.multiSelect) {
            Text(
                "Or type your own answer below",
                style = MaterialTheme.typography.labelMedium,
                color = c.muted,
                modifier = Modifier.padding(horizontal = Space.s, vertical = Space.xs),
            )
        }
    }
    val chat = p.options.indices.firstOrNull { p.role(it) == OptionRole.Chat }
    if (p.multiSelect) {
        PrimaryButton(onClick = { onPress(PromptPress.Ticks(ticked)) }, modifier = Modifier.fillMaxWidth(), enabled = !answering, contentPadding = Tight) {
            Icon(HerdrIcons.Send, null, Modifier.size(16.dp)); Label(if (ticked.isEmpty()) "Submit" else "Submit (${ticked.size})")
        }
    }
    val buttons = buildList<@Composable RowScope.() -> Unit> {
        if (chat != null) add { Choice("Chat about it", HerdrIcons.MessageSquare, answering) { onPress(PromptPress.Option(chat)) } }
        p.hintKeys.forEach { k -> add { KeyChoice(k, answering, onPress) } }
    }
    buttons.chunked(3).forEach { row -> ButtonRow { row.forEach { it() } } }
}

/** The steps of a form of several questions, as the web UI's step chips: ✓ answered, the one asked now strong. */
@Composable
private fun Steps(steps: List<PromptStep>) {
    val c = Herdr.colors
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        steps.forEachIndexed { i, s ->
            Row(
                Modifier.clip(RectangleShape).background(if (s.current) c.hover else Color.Transparent)
                    .border(1.dp, if (s.current) c.lineStrong else c.line, RectangleShape)
                    .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
                    .semantics { stateDescription = when { s.answered -> "answered"; s.current -> "asked now"; else -> "" } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(20.dp).clip(RectangleShape).background(if (s.answered) c.tint(c.done) else c.card),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        s.answered -> Icon(HerdrIcons.Check, null, tint = c.done, modifier = Modifier.size(12.dp))
                        s.submit -> Icon(HerdrIcons.Send, null, tint = if (s.current) c.strong else c.muted, modifier = Modifier.size(11.dp))
                        else -> Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = if (s.current) c.strong else c.muted)
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text(s.label, style = MaterialTheme.typography.labelMedium, color = if (s.current) c.strong else c.muted, maxLines = 1)
            }
        }
    }
}

/** A menu the app couldn't read: the bottom of the screen and the usual keys. */
@Composable
private fun UnreadMenu(screen: String, onPress: (PromptPress) -> Unit) {
    val tail = screen.lines().filter { it.isNotBlank() }.takeLast(12).joinToString("\n")
    if (tail.isNotEmpty()) CodeBlock(tail, maxLines = 12)
    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        listOf("1", "2", "3", "y", "n").forEach { k -> KeyButton(k) { onPress(PromptPress.Keys(listOf(k))) } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        KeyButton("Enter") { onPress(PromptPress.Keys(listOf("enter"))) }
        KeyButton("↑") { onPress(PromptPress.Keys(listOf("up"))) }
        KeyButton("↓") { onPress(PromptPress.Keys(listOf("down"))) }
        KeyButton("Esc") { onPress(PromptPress.Keys(listOf("esc"))) }
    }
}

private val TOOL_HEADER = Regex("^(bash command|(edit|create|write|read|update) file|fetch|web ?search|network request|tool use|notebook edit|mcp tool)$", RegexOption.IGNORE_CASE)

private val Tight = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp)

@Composable
private fun ButtonRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically, content = content)


@Composable
private fun Label(text: String) = Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)

@Composable
private fun RowScope.Choice(label: String, icon: ImageVector?, answering: Boolean, onClick: () -> Unit) {
    SecondaryButton(onClick = onClick, modifier = Modifier.weight(1f), enabled = !answering, contentPadding = Tight) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp))
        Label(label)
    }
}

/** A key the footer names: Amend (Tab) opens the reply after it; Cancel (Esc) and the others just press it. */
@Composable
private fun RowScope.KeyChoice(k: HintKey, answering: Boolean, onPress: (PromptPress) -> Unit) {
    val amend = k.key == "tab" || k.label.equals("Amend", ignoreCase = true)
    val icon = when {
        k.key == "esc" -> HerdrIcons.X
        amend -> HerdrIcons.Pencil
        else -> HerdrIcons.Keyboard
    }
    SecondaryButton(
        onClick = { onPress(PromptPress.Key(k.key, reply = amend)) }, enabled = !answering, contentPadding = Tight,
        modifier = Modifier.weight(1f).semantics { contentDescription = "${k.label} (${keyName(k.key)})" },
    ) {
        Icon(icon, null, Modifier.size(16.dp)); Label(k.label)
    }
}

private fun keyName(key: String) = when (key) {
    "esc" -> "Esc"
    "tab" -> "Tab"
    "shift+tab" -> "Shift+Tab"
    else -> key.split('+').joinToString("+") { it.replaceFirstChar { c -> c.uppercase() } }
}
