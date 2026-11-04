package com.makefunstuff.herdr.ui.agents

import com.makefunstuff.herdr.core.hc.HcMapping
import com.makefunstuff.herdr.core.board.TaskLine
import com.makefunstuff.herdr.core.chat.OptionRole

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.board.BoardAction
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.WorkCard
import com.makefunstuff.herdr.core.chat.Markdown
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.shepherdr.V2Board
import com.makefunstuff.herdr.ui.board.prNumberOf
import com.makefunstuff.herdr.core.chat.BlockedPrompt
import com.makefunstuff.herdr.core.home.AgentRow
import com.makefunstuff.herdr.core.home.HomeAgent
import com.makefunstuff.herdr.core.home.Waiting
import com.makefunstuff.herdr.ui.board.BusyLine
import com.makefunstuff.herdr.ui.board.Progress
import com.makefunstuff.herdr.ui.board.color
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.design.AgentMark
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.statusWord
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.theme.color

/** What a blocked agent asks, read off its screen: the question, and the menu when the app could read one. */
data class Asked(val question: String, val prompt: BlockedPrompt?)

/** What [agent] asks now, read once per state change; null until read (or when nothing could be). */
@Composable
fun rememberAsked(agent: HomeAgent): Asked? {
    val container = LocalContainer.current
    val cache = remember { mutableStateMapOf<String, Asked>() }
    val key = "${agent.key}/${agent.agent.stateChangeSeq}"
    LaunchedEffect(key) {
        val session = container.hosts.session(agent.machineId) ?: return@LaunchedEffect
        runCatching {
            val text = session.awaitAgent(agent.agent.paneId, 5_000).read(agent.agent.paneId, lines = 40, visible = true)
            val prompt = BlockedPrompt.parse(text)
            // herdr-core's agents also say what they ask (OSC 7501's msg): the question when the screen shows none we know.
            val said = agent.agent.title?.takeIf { HcMapping.address(agent.agent.paneId) != null }
            cache[key] = Asked(prompt?.question ?: PromptGuess.question(text) ?: said ?: return@runCatching, prompt)
        }
    }
    return cache[key]
}

/** A board task's short key, in the board's mono face. */
@Composable
fun KeyTag(name: String, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    Text(
        name, style = CodeStyle.copy(fontSize = 12.sp, lineHeight = 16.sp), color = c.strong, maxLines = 1,
        modifier = modifier.clip(HerdrShapes.Badge).background(c.hover).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** The line under a card's or row's title: the task's key, then the rest joined by dots. */
@Composable
private fun MetaLine(key: String?, parts: List<String>, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = Space.xxs), verticalAlignment = Alignment.CenterVertically) {
        if (key != null) {
            KeyTag(key)
            Spacer(Modifier.width(Space.s))
        }
        Text(
            parts.filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Where an agent works, for a meta line: the agent, its workspace (its task's repository when it works
 * on one: the board names the workspace after the task, which the line already shows), and its
 * machine when there are several.
 */
private fun where(a: HomeAgent, task: BoardTask?, showMachine: Boolean) =
    listOfNotNull(a.agent.displayName, task?.repo ?: a.workspace.ifEmpty { a.location }, a.machineLabel.takeIf { showMachine })

/**
 * A card of the Needs you list: hairline card, chosen on a wide window when it is open beside the
 * list. [actions]: its buttons and what its long-press sheet offers, as TalkBack's actions on it.
 */
@Composable
private fun WaitingCard(
    selected: Boolean,
    onOpen: () -> Unit,
    onMenu: (() -> Unit)?,
    label: String,
    actions: List<CustomAccessibilityAction>,
    content: @Composable () -> Unit,
) {
    val c = Herdr.colors
    QuietCard(
        Modifier.padding(horizontal = Space.screen, vertical = Space.xs).semantics {
            this.selected = selected
            if (actions.isNotEmpty()) customActions = actions
        },
        padded = true, onClick = onOpen, onLongClick = onMenu, onClickLabel = label,
        color = if (selected) c.hover else c.card, edge = if (selected) c.lineStrong else c.line,
    ) { content() }
}

/** The title row of a card: its mark, the title over its meta line, a status badge. */
@Composable
private fun CardHead(mark: @Composable () -> Unit, title: String, key: String?, meta: List<String>, badge: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 1.dp)) { mark() }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Herdr.colors.strong, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MetaLine(key, meta)
        }
        Spacer(Modifier.width(Space.s))
        badge()
    }
}

/** A card's button: an icon and a word, the card's main answer filled. */
@Composable
private fun AnswerButton(text: String, icon: ImageVector?, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val body: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    // A little narrower than other buttons: Yes, No, Reply and Open fit one row on a phone.
    val padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    if (primary) PrimaryButton(onClick = onClick, enabled = enabled, contentPadding = padding, content = body)
    else SecondaryButton(onClick = onClick, enabled = enabled, contentPadding = padding, content = body)
}

/**
 * A blocked agent as a card that answers it: the question, and Yes and No (or the menu's two
 * choices), Reply and Open. A menu of more than that is answered in the chat. [busy] while an answer is sent.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuestionCard(
    q: Waiting.Question,
    showMachine: Boolean,
    selected: Boolean,
    busy: String?,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    onChoose: (BlockedPrompt, Int) -> Unit,
    onReply: () -> Unit,
    /** The rest of its long-press sheet (terminal, why this state, mute, …), for TalkBack after its answers. */
    more: List<CustomAccessibilityAction> = emptyList(),
) {
    val c = Herdr.colors
    val a = q.agent.agent
    val asked = rememberAsked(q.agent)
    val prompt = asked?.prompt
    // What its buttons do, first: TalkBack offers the answers without finding each button.
    val answers = if (busy != null) emptyList() else when {
        prompt != null && prompt.yesNo -> listOf(
            CustomAccessibilityAction("Answer yes") { onChoose(prompt, prompt.approveIndex!!); true },
            CustomAccessibilityAction("Answer no") { onChoose(prompt, prompt.denyIndex!!); true },
        )
        prompt != null && prompt.options.size <= 2 -> prompt.options.indices.map { i ->
            CustomAccessibilityAction("Answer: ${prompt.shortLabel(i)}") { onChoose(prompt, i); true }
        }
        else -> emptyList()
    } + CustomAccessibilityAction("Reply…") { onReply(); true }
    WaitingCard(selected, onOpen, onMenu, "Open chat", answers + more) {
        CardHead(
            mark = { AgentMark(a.agent ?: a.displayName) },
            title = q.task?.title ?: a.subtitle ?: a.displayName,
            key = q.task?.name,
            meta = where(q.agent, q.task, showMachine),
            badge = { StatusBadge("needs you", AgentStatus.Blocked.color()) },
        )
        Column(Modifier.padding(start = 34.dp, top = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(
                asked?.question ?: "Waiting for your answer",
                style = MaterialTheme.typography.bodyLarge, color = if (asked == null) c.muted else c.text,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            // The command or file it asks about, as the chat's prompt card shows it.
            asked?.prompt?.details?.takeIf { it.isNotEmpty() }?.let { d ->
                Text(
                    d.take(2).joinToString("\n"), style = CodeStyle.copy(fontSize = 12.sp, lineHeight = 17.sp), color = c.muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (busy != null) {
            Box(Modifier.padding(start = 22.dp, top = Space.s)) { BusyLine(busy) }
            return@WaitingCard
        }
        FlowRow(
            Modifier.padding(start = 34.dp, top = Space.m),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            when {
                prompt != null && prompt.yesNo -> {
                    AnswerButton("Yes", HerdrIcons.Check, primary = true, enabled = true) { onChoose(prompt, prompt.approveIndex!!) }
                    AnswerButton("No", HerdrIcons.X, primary = false, enabled = true) { onChoose(prompt, prompt.denyIndex!!) }
                }
                prompt != null && prompt.options.size <= 2 -> prompt.options.forEachIndexed { i, o ->
                    // A permanent allow carries the fingerprint, as it asks for one first.
                    val always = prompt.role(i) == OptionRole.Always
                    AnswerButton(
                        prompt.shortLabel(i).let { if (it.length <= 24) it else it.take(23).trimEnd() + "…" },
                        if (always) HerdrIcons.FingerprintPattern else null, primary = i == 0 && !always, enabled = true,
                    ) { onChoose(prompt, i) }
                }
            }
            AnswerButton("Reply", HerdrIcons.Reply, primary = false, enabled = true, onClick = onReply)
            // Always there at the end of the row, as on a task's card: a tap on the card's blank space isn't found.
            AnswerButton("Open", null, primary = false, enabled = true, onClick = onOpen)
        }
    }
}

/**
 * A board task waiting for you as a card: its key, status and what the board says of it, its agent's
 * mark when one runs; the answer it most likely needs ([answer]: approve and merge, fix, …) and Open
 * (the review card for finished work, else the task).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskCard(
    w: Waiting.Task,
    showMachine: Boolean,
    selected: Boolean,
    busy: String?,
    answer: Pair<BoardAction, String>?,
    onOpen: () -> Unit,
    onAnswer: () -> Unit,
) {
    val c = Herdr.colors
    val t = w.task
    val says = remember(t.msg) { TaskLine.line(t.msg) }
    val actions = listOfNotNull(answer?.takeIf { busy == null }?.let { CustomAccessibilityAction(it.second) { onAnswer(); true } })
    WaitingCard(selected, onOpen, null, "Open task", actions) {
        CardHead(
            mark = {
                w.agent?.agent?.let { AgentMark(it.agent ?: it.displayName) }
                    ?: Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { Icon(HerdrIcons.SquareKanban, null, tint = c.text, modifier = Modifier.size(20.dp)) }
            },
            title = t.title,
            key = t.name,
            meta = listOfNotNull(
                says.takeIf { it.isNotEmpty() },
                t.prNumber.takeIf { it.isNotEmpty() }?.let { "PR #$it" },
                (w.agent?.agent?.displayName ?: t.agent).takeIf { it.isNotEmpty() && says.isEmpty() },
                w.machineLabel.takeIf { showMachine },
            ),
            badge = { StatusBadge(t.status.word, t.status.color()) },
        )
        if (busy != null) {
            Box(Modifier.padding(start = 22.dp, top = Space.s)) { BusyLine(busy) }
            return@WaitingCard
        }
        FlowRow(
            Modifier.padding(start = 34.dp, top = Space.m),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            if (answer != null) {
                val icon = when (answer.first) {
                    BoardAction.Approve, BoardAction.ApprovePlan -> HerdrIcons.Check
                    BoardAction.Tell -> HerdrIcons.Reply
                    else -> HerdrIcons.RotateCcw
                }
                // Approve is the one filled: a fix or a reply is a step on the way, the merge is the yes.
                val primary = answer.first == BoardAction.Approve || answer.first == BoardAction.ApprovePlan
                AnswerButton(answer.second, icon, primary = primary, enabled = true, onClick = onAnswer)
            }
            AnswerButton("Open", null, primary = answer == null, enabled = true, onClick = onOpen)
        }
    }
}

/** A Shepherdr 3 space whose coordinator asks you something, as a card: Open goes to its chat, where the question is. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoordinatorCard(w: Waiting.Coordinator, showMachine: Boolean, onOpen: () -> Unit) {
    val c = Herdr.colors
    WaitingCard(false, onOpen, null, "Open the Assistant chat", emptyList()) {
        CardHead(
            mark = { Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { Icon(HerdrIcons.MessagesSquare, null, tint = c.text, modifier = Modifier.size(20.dp)) } },
            title = "${w.space.name}'s Assistant asks you",
            key = null,
            meta = listOfNotNull("Answer it in the Assistant chat", w.machineLabel.takeIf { showMachine }),
            badge = { StatusBadge(WorkStatus.Need.label, WorkStatus.Need.color()) },
        )
        FlowRow(Modifier.padding(start = 34.dp, top = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            AnswerButton("Open", null, primary = true, enabled = true, onClick = onOpen)
        }
    }
}

/**
 * A Shepherdr 3 task waiting for you as a card, as its task screen has it: the question its agent asks
 * ([card], once read) with a button per answer, or Merge when it is ready; and Open, its chat. A
 * question that needs more than a tap (several picks, a note, words of your own) is answered there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkWaitingCard(
    w: Waiting.Task,
    work: WorkTask,
    space: String,
    showMachine: Boolean,
    selected: Boolean,
    busy: String?,
    card: WorkCard?,
    onOpen: () -> Unit,
    onMerge: () -> Unit,
    onChoose: (WorkCard, Int) -> Unit,
) {
    val c = Herdr.colors
    val q = card?.question
    // A tap answers it only where the task screen would send that pick as it is.
    // A plan, or choices that explain themselves, are read on the task screen first.
    val taps = q != null && !q.multi && card.replyTo.isEmpty() && q.choices.size in 1..3 && q.kind != "Plan" && q.parts.isNullOrEmpty() && q.plan.isNullOrBlank() && q.steps.isNullOrEmpty()
    fun tap(i: Int) = if (q?.noteRequiredFor == i) onOpen() else onChoose(card!!, i)
    val actions = if (busy != null) emptyList() else when {
        work.status == WorkStatus.Ready -> listOf(CustomAccessibilityAction("Accept") { onMerge(); true })
        taps -> q!!.choices.mapIndexed { i, ch -> CustomAccessibilityAction("Answer: ${ch.label}") { tap(i); true } }
        else -> emptyList()
    }
    WaitingCard(selected, onOpen, null, "Open chat", actions) {
        CardHead(
            mark = {
                if (work.agent.isNotEmpty()) AgentMark(V2Board.agentKind(work.agent))
                else Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { Icon(HerdrIcons.SquareKanban, null, tint = c.text, modifier = Modifier.size(20.dp)) }
            },
            title = work.title,
            key = work.name,
            meta = listOfNotNull(
                work.line.takeIf { it.isNotEmpty() && q == null },
                prNumberOf(work.pr)?.let { "PR #$it" },
                space.takeIf { it.isNotEmpty() },
                w.machineLabel.takeIf { showMachine },
            ),
            badge = { StatusBadge(work.status.label, work.status.color()) },
        )
        if (q != null) Text(
            Markdown.plain(q.title.ifEmpty { q.asks ?: "Waiting for your answer" }),
            style = MaterialTheme.typography.bodyLarge, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 34.dp, top = Space.s),
        )
        if (busy != null) {
            Box(Modifier.padding(start = 22.dp, top = Space.s)) { BusyLine(busy) }
            return@WaitingCard
        }
        FlowRow(
            Modifier.padding(start = 34.dp, top = Space.m),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            when {
                work.status == WorkStatus.Ready -> AnswerButton("Accept", HerdrIcons.Check, primary = true, enabled = true, onClick = onMerge)
                taps -> q!!.choices.forEachIndexed { i, ch ->
                    AnswerButton(ch.label.let { if (it.length <= 24) it else it.take(23).trimEnd() + "…" }, null, primary = false, enabled = true) { tap(i) }
                }
            }
            // A question it can't answer here is answered in the chat: Open says so by being the main one.
            AnswerButton("Open", null, primary = work.status != WorkStatus.Ready && !taps && q != null, enabled = true, onClick = onOpen)
        }
    }
}

/**
 * An agent below Needs you as one line: its mark, what it works on over its key and where, and on the
 * right how far along it is (working), a dot for a finish not seen yet (done). A working one has a
 * thin bar under it: its task's progress when the board knows it, else one that only says it works.
 * The phone's triage shows last: how long it is snoozed, a crossed bell when muted, a pin.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgentLine(
    row: AgentRow,
    showMachine: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    /** How long it is snoozed, as the row says it ("until 18:00"); null when it isn't. */
    snoozed: String? = null,
    /** Its long-press sheet (triage, terminal, why this state, …) as TalkBack offers it. */
    actions: List<CustomAccessibilityAction> = emptyList(),
) {
    val c = Herdr.colors
    val a = row.agent.agent
    val t: BoardTask? = row.task
    val status = a.agentStatus
    val working = status == AgentStatus.Working
    val quiet = status == AgentStatus.Done && !row.new || status == AgentStatus.Idle || status == AgentStatus.Unknown || snoozed != null
    val title = t?.title ?: a.subtitle ?: a.displayName
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .background(if (selected) c.hover else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClickLabel = "Open chat", onClick = onOpen, onLongClickLabel = "show more actions", onLongClick = onMenu)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = listOfNotNull(
                    title, t?.name, a.displayName, if (row.new) "finished, new" else a.statusWord(),
                    "pinned".takeIf { row.pinned }, snoozed?.let { "snoozed $it" }, "muted".takeIf { row.muted },
                ).joinToString(", ")
                if (actions.isNotEmpty()) customActions = actions
            }
            .padding(horizontal = Space.screen + Space.xs, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentMark(a.agent ?: a.displayName, size = 20.dp)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (quiet) c.muted else c.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MetaLine(null, listOfNotNull(t?.name) + where(row.agent, t, showMachine))
            if (working) {
                val p = t?.progress
                Box(Modifier.padding(top = Space.xs)) {
                    if (p != null) Progress(p, status.color(), Modifier.fillMaxWidth().height(3.dp))
                    else LinearProgressIndicator(
                        Modifier.fillMaxWidth().height(3.dp).clip(HerdrShapes.Pill),
                        color = status.color(), trackColor = c.line, gapSize = 0.dp,
                    )
                }
            }
        }
        val trailing = when {
            // Snoozed, it says only until when.
            working && snoozed == null -> listOfNotNull(t?.progress?.let { "$it%" }, t?.eta?.let { "${it}m left" }).joinToString(" · ")
            else -> ""
        }
        if (trailing.isNotEmpty()) {
            Spacer(Modifier.width(Space.m))
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = c.muted, maxLines = 1)
        }
        if (row.new) {
            Spacer(Modifier.width(Space.m))
            Text("new", style = MaterialTheme.typography.labelMedium, color = c.done)
            Spacer(Modifier.width(Space.xs))
            StatusDot(c.done)
        }
        if (snoozed != null) {
            Spacer(Modifier.width(Space.m))
            Text(snoozed, style = MaterialTheme.typography.labelMedium, color = c.muted, maxLines = 1)
            Spacer(Modifier.width(Space.xs))
            Icon(HerdrIcons.Clock, null, tint = c.accent, modifier = Modifier.size(16.dp))
        }
        if (row.muted) {
            Spacer(Modifier.width(Space.s))
            Icon(HerdrIcons.BellOff, null, tint = c.muted, modifier = Modifier.size(16.dp))
        }
        if (row.pinned) {
            Spacer(Modifier.width(Space.s))
            Icon(HerdrIcons.Pin, null, tint = c.muted, modifier = Modifier.size(16.dp))
        }
    }
}
