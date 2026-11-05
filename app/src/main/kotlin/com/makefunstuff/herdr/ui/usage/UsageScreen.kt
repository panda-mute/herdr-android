package com.makefunstuff.herdr.ui.usage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.LocalPanes
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.usage.DayUse
import com.makefunstuff.herdr.core.usage.ProviderKind
import com.makefunstuff.herdr.core.usage.ProviderUsage
import com.makefunstuff.herdr.core.usage.Spend
import com.makefunstuff.herdr.core.usage.UsageLimit
import com.makefunstuff.herdr.core.usage.UsageRules
import com.makefunstuff.herdr.core.usage.UsageWords
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.Pill
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.design.AgentMark
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.IconTile
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.hairline
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** A reading younger than this isn't read again as the page opens (the machine caches its own for a few minutes). */
private const val OPEN_FRESH_MS = 60_000L

/**
 * One machine's AI providers (its own logins and keys): each subscription's limits and when they
 * reset, each API account's spend or balance, the local models; and per provider, which board tasks
 * and agents used it most this week, with a 7-day chart. It shows the last reading at once and reads
 * the machine again (herdr-usage, over SSH) when that is older than a minute.
 */
@Composable
fun UsageScreen(nav: NavController, machineId: String) {
    val container = LocalContainer.current
    val session = container.hosts.session(machineId)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val store = container.usage
    val scope = rememberCoroutineScope()
    val state by session.state.collectAsStateWithLifecycle()
    val latest by store.latest.collectAsStateWithLifecycle()
    val problems by store.problems.collectAsStateWithLifecycle()
    val reading by store.reading.collectAsStateWithLifecycle()
    val lists by container.boards.lists.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val entry = latest[machineId]
    val now by rememberNow()
    val zone = store.zone()

    LaunchedEffect(machineId, state.online) {
        if (!state.online) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (store.stale(machineId, OPEN_FRESH_MS)) store.read(session)
        }
    }
    // A limit's notification has done its job once the page is on screen.
    LaunchedEffect(machineId, entry?.report) {
        entry?.report?.providers?.forEach { p -> p.limits.forEach { l -> container.inbox.dismiss(com.makefunstuff.herdr.core.push.Alerts.usageTag(machineId, p.id, l.id)) } }
    }
    val keys = remember(lists, machineId) { lists[machineId]?.tasks.orEmpty().associate { it.id to it.name } }

    Scaffold(
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar(
                "Usage",
                subtitle = listOf(
                    state.machine.label,
                    when {
                        machineId in reading -> "reading…"
                        entry != null && entry.readMs > 0 -> "read ${ago(store.clock() - entry.readMs)}"
                        machineId in problems -> "couldn't read it"
                        !state.online -> state.status.label()
                        else -> "reading…"
                    },
                ).joinToString(" · "),
                onBack = { nav.popBackStack() },
            ) {
                BarIcon(HerdrIcons.RefreshCw, "Read again", { session.retry(); scope.launch { store.read(session, fresh = true) } }, enabled = machineId !in reading)
            }
        },
    ) { padding ->
        if (entry == null) {
            Box(Modifier.padding(padding)) {
                val problem = problems[machineId]
                if (problem != null) ErrorMessage(problem, Modifier.padding(Space.screen)) { scope.launch { store.read(session, fresh = true) } }
                else LoadingState("Reading ${state.machine.label}'s providers")
            }
            return@Scaffold
        }
        val report = entry.report
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.s, bottom = Space.xxxl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            problems[machineId]?.let { p ->
                val from = if (entry.readMs > 0) "the reading from ${ago(store.clock() - entry.readMs)}" else "the last limits it sent"
                item(key = "problem") { Notice("Couldn't read it again: $p. This is $from.", color = Herdr.colors.notice, icon = HerdrIcons.Clock) }
            }
            if (report.partial) item(key = "partial") {
                Notice("Not every session record was read yet: what the agents used fills in with the next look.", icon = HerdrIcons.Hourglass)
            }
            if (report.providers.isEmpty()) item(key = "none") {
                EmptyState(
                    "No providers found",
                    "Claude Code, Codex, pi or opencode logins and API keys on ${state.machine.label} show here.",
                    icon = HerdrIcons.Gauge,
                )
            }
            items(report.providers, key = { it.id }) { p ->
                // As it stands now: a limit whose reset passed since the reading starts again.
                ProviderCard(p.at(now), now, zone, keys) { task -> nav.navigate(Routes.board(machineId, task)) }
            }
            item(key = "foot") {
                Text(
                    "Read on ${state.machine.label} with its own logins and keys, which stay there: Claude Code's and Codex's usage APIs, " +
                        "the providers' balance and spend APIs, and the agents' session records for what each task and agent used " +
                        "(tokens without cache reads).",
                    style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted, modifier = Modifier.padding(top = Space.s),
                )
            }
        }
    }
}

/**
 * The phone's time in seconds ([com.makefunstuff.herdr.data.UsageStore.clock]), again each minute: a
 * limit whose reset time passes while it is shown starts again from nothing.
 */
@Composable
private fun rememberNow(): androidx.compose.runtime.State<Long> {
    val store = LocalContainer.current.usage
    return androidx.compose.runtime.produceState(store.nowSeconds()) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            value = store.nowSeconds()
        }
    }
}

/** "now", "4 min ago", "2 h ago". */
private fun ago(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 * 2 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}

/** A limit's colour: amber near it (80%), red when out; quiet below. */
@Composable
fun limitColor(percent: Double): Color = when (UsageRules.level(percent)) {
    100 -> Herdr.colors.red
    95, 80 -> Herdr.colors.notice
    else -> Herdr.colors.text.copy(alpha = 0.55f)
}

/** What an agent has left, as [UsageRules.Left.level] colours it: amber near a limit, red when out. */
@Composable
fun levelColor(level: Int): Color = when (level) {
    100 -> Herdr.colors.red
    95, 80 -> Herdr.colors.notice
    else -> Herdr.colors.muted
}

// ---- one provider ---------------------------------------------------------------------------------

@Composable
private fun ProviderCard(p: ProviderUsage, now: Long, zone: ZoneId, keys: Map<String, String>, onTask: (String) -> Unit) {
    val c = Herdr.colors
    val tight = p.tightest?.takeIf { UsageRules.level(it.percent) >= 80 }
    QuietCard(padded = true, edge = tight?.let { limitColor(it.percent) } ?: c.line) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderMark(p)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, color = c.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(kindWords(p), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(Space.s))
            Pill(
                when (p.kind) {
                    ProviderKind.Subscription -> p.plan.ifEmpty { "subscription" }
                    ProviderKind.Api -> "API"
                    ProviderKind.Local -> "local"
                },
            )
        }
        if (p.error.isNotEmpty()) {
            Text(
                p.error, style = MaterialTheme.typography.bodyMedium, color = if (p.limits.isEmpty() && p.spend == null) c.red else c.notice,
                modifier = Modifier.padding(top = Space.s),
            )
        }
        if (p.note.isNotEmpty() || p.stale) {
            Text(
                p.note.ifEmpty { "An older reading: the provider couldn't be asked now" },
                style = MaterialTheme.typography.bodySmall, color = c.notice, modifier = Modifier.padding(top = Space.xs),
            )
        }
        if (p.limits.isNotEmpty()) Column(Modifier.padding(top = Space.s)) { p.limits.forEach { LimitMeter(it, now, zone) } }
        p.spend?.let { SpendRows(it, Modifier.padding(top = Space.s)) }
        if (p.kind == ProviderKind.Local) LocalRows(p)

        if (p.days.any { it.tokens > 0 }) WeekChart(p.days, if (p.kind == ProviderKind.Api && p.weekCost > 0) p.spend?.currency ?: "USD" else null)
        val tasks = p.tasks.take(3)
        if (tasks.isNotEmpty()) {
            Heading("Board tasks that used it most")
            tasks.forEach { t ->
                val name = if (t.coordinator) "Assistant" else keys[t.id] ?: t.id.substringAfterLast('/').let { "#$it" }
                UseRow(
                    if (t.coordinator) "The Assistant" else "$name · ${t.title.ifEmpty { t.id }}",
                    useWords(t.tokens, t.cost, p),
                    onClick = if (t.coordinator) null else ({ onTask(t.id) }),
                )
            }
        }
        val agents = p.agents.take(3)
        if (agents.isNotEmpty()) {
            Heading("Agents")
            agents.forEach { a -> UseRow(a.name, useWords(a.tokens, a.cost, p), mark = a.name.substringBefore(' ')) }
        }
        val readAt = p.readAt.takeIf { it > 0 }?.let { "read ${UsageWords.at(it, now, zone)}" }
        Text(
            listOfNotNull(p.source.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() }, readAt).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.padding(top = Space.m),
        )
    }
}

@Composable
private fun ProviderMark(p: ProviderUsage) {
    when {
        p.id == "claude" || p.id == "codex" -> Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { AgentMark(p.id, size = 26.dp) }
        p.kind == ProviderKind.Local -> IconTile(HerdrIcons.Server)
        else -> IconTile(letter = p.name.firstOrNull()?.uppercase() ?: "?")
    }
}

private fun kindWords(p: ProviderUsage): String = when (p.kind) {
    ProviderKind.Subscription -> "Subscription" + if (p.limits.isNotEmpty()) " · ${p.limits.size} limits" else ""
    ProviderKind.Api -> "API account" + if (p.key.isNotEmpty()) " · key from ${p.key}" else ""
    ProviderKind.Local -> "Local model" + if (p.url.isNotEmpty()) " · ${p.url.removePrefix("http://").removePrefix("https://")}" else ""
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Herdr.colors.muted, modifier = Modifier.padding(top = Space.l, bottom = Space.xs))
}

@Composable
private fun LimitMeter(l: UsageLimit, now: Long, zone: ZoneId) {
    val c = Herdr.colors
    val color = limitColor(l.percent)
    val reset = l.resets?.let { "resets ${UsageWords.at(it, now, zone)}" }
        ?: l.wasReset?.let { "reset at ${UsageWords.at(it, now, zone)}" }
        ?: if (l.window != null) "rolling" else null
    Column(Modifier.fillMaxWidth().padding(vertical = Space.xs).semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(l.name, style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Space.s))
            Text(
                listOfNotNull("${UsageRules.percent(l.percent)} used", reset).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = if (UsageRules.level(l.percent) >= 80) color else c.muted, maxLines = 1,
                textAlign = TextAlign.End,
            )
        }
        Box(Modifier.padding(top = 6.dp, bottom = Space.xs).fillMaxWidth().height(6.dp).clip(HerdrShapes.Pill).background(c.hover)) {
            Box(Modifier.fillMaxWidth((l.percent / 100).toFloat().coerceIn(0.01f, 1f)).height(6.dp).clip(HerdrShapes.Pill).background(color))
        }
    }
}

@Composable
private fun SpendRows(s: Spend, modifier: Modifier = Modifier) {
    val est = if (s.estimated) " (this machine)" else ""
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        s.balance?.let { MoneyRow("Balance left", UsageWords.money(it, s.currency), if (it <= 0) Herdr.colors.red else null) }
        val limit = s.limit
        val left = s.left
        if (limit != null && left != null) MoneyRow("Key limit", "${UsageWords.money(left, s.currency)} of ${UsageWords.money(limit, s.currency)} left")
        s.today?.let { MoneyRow("Spent today$est", UsageWords.money(it, s.currency)) }
        s.month?.let { MoneyRow("This month$est", UsageWords.money(it, s.currency)) }
    }
}

@Composable
private fun MoneyRow(label: String, value: String, color: Color? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium, color = color ?: Herdr.colors.strong)
    }
}

@Composable
private fun LocalRows(p: ProviderUsage) {
    val c = Herdr.colors
    Row(Modifier.padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (p.running == false) HerdrIcons.CircleX else HerdrIcons.CircleCheck, null, tint = if (p.running == false) c.notice else c.done, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Space.s))
        Text(
            when (p.running) {
                false -> "Not answering now · no limit"
                else -> "Runs locally · no limit"
            },
            style = MaterialTheme.typography.bodyMedium, color = c.text,
        )
    }
    if (p.models.isNotEmpty()) {
        Text(p.models.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun useWords(tokens: Long, cost: Double, p: ProviderUsage): String =
    listOfNotNull("${UsageWords.tokens(tokens)} tokens", cost.takeIf { it >= 0.005 && p.kind == ProviderKind.Api }?.let { UsageWords.money(it, p.spend?.currency ?: "USD") })
        .joinToString(" · ")

@Composable
private fun UseRow(title: String, value: String, mark: String? = null, onClick: (() -> Unit)? = null) {
    val c = Herdr.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(HerdrShapes.Tile)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Open on the board", onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mark != null) {
            AgentMark(mark, size = 16.dp)
            Spacer(Modifier.width(Space.s))
        }
        Text(title, style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Space.s))
        Text(value, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1)
    }
}

// ---- the week ---------------------------------------------------------------------------------------

/**
 * Seven days of what the agents used, one bar a day (the machine's days), today on the right with its
 * value written over it; a touch on a bar reads that day. With [currency], what it cost is said too.
 */
@Composable
private fun WeekChart(days: List<DayUse>, currency: String?) {
    val c = Herdr.colors
    var picked by remember(days) { mutableStateOf<Int?>(null) }
    val max = days.maxOf { it.tokens }.coerceAtLeast(1)
    val shown = picked?.let { days.getOrNull(it) } ?: days.last()
    val total = days.sumOf { it.tokens }
    Heading("Last 7 days · ${UsageWords.tokens(total)} tokens" + (currency?.let { " · ${UsageWords.money(days.sumOf { d -> d.cost }, it)}" } ?: ""))
    Text(
        (if (picked == null) "Today" else dayName(shown.day, long = true)) + ": ${UsageWords.tokens(shown.tokens)} tokens" +
            (currency?.let { " · ${UsageWords.money(shown.cost, it)}" } ?: ""),
        style = MaterialTheme.typography.bodySmall, color = c.text, modifier = Modifier.padding(bottom = Space.xs),
    )
    val bar = c.chart1
    val dim = c.chart1.copy(alpha = 0.45f)
    Canvas(
        Modifier.fillMaxWidth().height(72.dp)
            .semantics { contentDescription = "Tokens per day, last 7 days. Today ${UsageWords.tokens(days.last().tokens)}" }
            .pointerInput(days) {
                detectTapGestures(onPress = { o ->
                    picked = (o.x / (size.width / days.size.toFloat())).toInt().coerceIn(0, days.size - 1)
                    tryAwaitRelease()
                    picked = null
                })
            },
    ) {
        val w = size.width / days.size
        val gap = 2.dp.toPx() * 2
        val r = 4.dp.toPx()
        days.forEachIndexed { i, d ->
            if (d.tokens <= 0) return@forEachIndexed
            val h = (d.tokens.toFloat() / max * size.height).coerceAtLeast(2.dp.toPx())
            val left = i * w + gap / 2
            val top = size.height - h
            val bw = w - gap
            // Rounded at the data end, square on the baseline.
            val path = Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(left, top, left + bw, size.height, CornerRadius(r.coerceAtMost(h / 2), r.coerceAtMost(h / 2))))
                addRect(androidx.compose.ui.geometry.Rect(left, (top + r).coerceAtMost(size.height), left + bw, size.height))
            }
            drawPath(path, if (picked == null || picked == i) bar else dim)
        }
        drawLine(c.line, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
    }
    Row(Modifier.fillMaxWidth().padding(top = Space.xs)) {
        days.forEach { d ->
            Text(
                dayName(d.day), style = MaterialTheme.typography.labelMedium, color = c.muted, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** "Mon", or with [long] "Monday"; the date itself when it isn't one. */
private fun dayName(day: String, long: Boolean = false): String =
    runCatching { LocalDate.parse(day).dayOfWeek.getDisplayName(if (long) TextStyle.FULL else TextStyle.SHORT, Locale.ENGLISH) }.getOrDefault(day)

// ---- the way in, and the glance ------------------------------------------------------------------

/**
 * The way into a machine's usage from its screen, under its tiles: one 48dp row with the tightest
 * limit, in amber near it, and a word on every provider. With [tile] it is one of the tiles (a machine
 * with no board has room in their row). It reads the machine when its reading is older than ten minutes.
 */
@Composable
fun UsageRow(session: HostSession, onOpen: () -> Unit, tile: Boolean = false, modifier: Modifier = Modifier) {
    val store = LocalContainer.current.usage
    val latest by store.latest.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val id = session.machine.id
    LaunchedEffect(id, state.online) {
        if (state.online && store.stale(id, 10 * 60_000L)) store.read(session)
    }
    val now by rememberNow()
    val report = latest[id]?.report?.let { r -> r.copy(providers = r.providers.map { it.at(now) }) }
    val zone = store.zone()
    val tight = report?.let { UsageRules.tightest(mapOf(id to it), now) }
    val warn = tight?.takeIf { it.level >= 80 }
    val c = Herdr.colors
    val tint = warn?.let { limitColor(it.limit.percent) }
    val line = warn?.let { UsageRules.glance(it, now, zone) } ?: summary(report?.providers, now, zone)
    if (tile) {
        com.makefunstuff.herdr.ui.machines.PlaceTile(
            HerdrIcons.Gauge, "Usage", line, tint ?: c.muted, modifier, onOpen, "Open usage and limits",
            edge = tint ?: c.line, iconTint = tint ?: c.text,
        )
        return
    }
    QuietCard(
        modifier.semantics(mergeDescendants = true) { contentDescription = "Usage: $line" },
        onClick = onOpen, onClickLabel = "Open usage and limits", edge = tint ?: c.line,
    ) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = Space.m), verticalAlignment = Alignment.CenterVertically) {
            Icon(HerdrIcons.Gauge, null, tint = tint ?: c.text, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.m))
            Text("Usage", style = MaterialTheme.typography.titleSmall, color = c.strong, maxLines = 1)
            Spacer(Modifier.width(Space.s))
            Text(
                line, style = MaterialTheme.typography.bodySmall, color = tint ?: c.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Icon(HerdrIcons.ChevronRight, null, tint = c.faint, modifier = Modifier.padding(start = Space.s).size(18.dp))
        }
    }
}

/** "Claude 82% · Codex 25% · DeepSeek $7.63 left · llama.cpp local". */
private fun summary(providers: List<ProviderUsage>?, now: Long, zone: ZoneId): String {
    if (providers == null) return "Claude, Codex and API limits, spend and balances"
    if (providers.isEmpty()) return "No AI provider found on this machine"
    return providers.joinToString(" · ") { p ->
        val balance = p.spend?.balance
        when {
            p.error.isNotEmpty() -> "${p.short} unavailable"
            p.tightest != null -> "${p.short} ${UsageRules.percent(p.tightest!!.percent)}"
            p.kind == ProviderKind.Local -> "${p.short} local"
            balance != null -> "${p.short} ${UsageWords.money(balance, p.spend?.currency ?: "USD")} left"
            else -> p.short
        }
    }
}

/**
 * The glance in the top bar: the tightest limit across the machines ("Claude 82% · resets 17:00"),
 * amber near it and red when out. A tap opens that machine's usage page. Nothing while no limit is known.
 * [compact] leaves the reset out ("Claude 82%"), for a narrow pane where the screen's title needs the room.
 */
@Composable
fun UsageChip(nav: NavController, compact: Boolean = false) {
    val container = LocalContainer.current
    val store = container.usage
    val latest by store.latest.collectAsStateWithLifecycle()
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val panes = LocalPanes.current
    val ids = hosts.map { it.machine.id }.toSet()
    val now by rememberNow()
    val tight = remember(latest, ids, now) { UsageRules.tightest(latest.filterKeys { it in ids }.mapValues { it.value.report }, now) } ?: return
    val c = Herdr.colors
    val color = if (tight.level >= 80) limitColor(tight.limit.percent) else c.muted
    val words = UsageRules.glance(tight, now, store.zone())
    val shown = if (compact) words.substringBefore(" · ") else words
    Row(
        Modifier.widthIn(max = 216.dp).heightIn(min = 32.dp).clip(HerdrShapes.Pill)
            .background(if (tight.level >= 80) c.tint(color) else c.card)
            .then(if (tight.level < 80) Modifier.hairline(HerdrShapes.Pill) else Modifier)
            .clickable(onClickLabel = "Open usage and limits") { panes.open(nav, Routes.usage(tight.machineId)) }
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Tightest limit: $words. Opens usage and limits." },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HerdrIcons.Gauge, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(shown, style = MaterialTheme.typography.labelMedium, color = if (tight.level >= 80) color else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
