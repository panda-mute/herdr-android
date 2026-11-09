package com.makefunstuff.herdr.ui.health

import com.makefunstuff.herdr.core.guard.Guarded

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.core.health.HealthPoint
import com.makefunstuff.herdr.core.health.HealthReport
import com.makefunstuff.herdr.core.health.HealthRules
import com.makefunstuff.herdr.core.health.HealthSample
import com.makefunstuff.herdr.core.health.LabeledProc
import com.makefunstuff.herdr.core.health.ProcKind
import com.makefunstuff.herdr.core.health.Sizes
import com.makefunstuff.herdr.core.push.Alerts
import com.makefunstuff.herdr.core.push.HealthWords
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.AgentMark
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.DangerButton
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.FieldLabel
import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.HerdrSheet
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.IconTile
import com.makefunstuff.herdr.ui.design.InfoLine
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.MonoBlock
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.design.SegmentedPills
import com.makefunstuff.herdr.ui.design.SheetTitle
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** How often the screen looks while it is in front. */
private const val EVERY_MS = 5_000L

/** What a confirmation is about. */
private sealed interface Confirm {
    data class Stop(val procs: List<LabeledProc>, val title: String, val what: String) : Confirm
    data object Gradle : Confirm
}

/**
 * A machine's health: RAM, swap, CPU and disks now and over the last hour, the biggest processes with
 * whose they are.
 */
@Composable
fun HealthScreen(nav: NavController, machineId: String) {
    val container = LocalContainer.current
    val session = container.hosts.session(machineId)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by session.state.collectAsStateWithLifecycle()
    val model = remember(session) { HealthModel(session, container.health, scope) }
    val history by container.health.history.collectAsStateWithLifecycle()
    var byCpu by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var openProc by remember { mutableStateOf<LabeledProc?>(null) }
    val owner = LocalLifecycleOwner.current

    // A low-memory notification has done its job once its machine's health is on screen.
    LaunchedEffect(machineId) { container.inbox.dismiss(Alerts.healthTag(machineId)) }
    // Look every few seconds while the screen is in front, never while it is backgrounded.
    LaunchedEffect(machineId, state.online) {
        if (!state.online) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.look()
            while (true) {
                delay(EVERY_MS)
                model.look()
            }
        }
    }
    LaunchedEffect(model.message) { model.message?.let { context.toast(it); model.message = null } }

    val sample = model.sample
    Scaffold(
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar(
                "Health",
                subtitle = listOf(state.machine.label, if (state.online) (if (sample != null) "live" else "reading…") else state.status.label())
                    .joinToString(" · "),
                onBack = { nav.popBackStack() },
            ) {
                BarIcon(HerdrIcons.RefreshCw, "Refresh", { session.retry(); scope.launchLook(model) })
            }
        },
    ) { padding ->
        if (sample == null) {
            Box(Modifier.padding(padding)) {
                val problem = model.problem
                if (problem != null) ErrorMessage(problem, Modifier.padding(Space.screen)) { scope.launchLook(model) }
                else LoadingState("Reading ${state.machine.label}")
            }
            return@Scaffold
        }
        val report = model.report
        val shown = remember(model.procs, byCpu) {
            if (byCpu) model.procs.filter { it.proc.cpu >= 0.5 }.sortedByDescending { it.proc.cpu }.take(15)
            else model.procs.sortedByDescending { it.proc.rssKb }.take(15)
        }
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.s, bottom = Space.xxxl),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            model.busy?.let { b -> item(key = "busy") { Busy(b) } }
            model.problem?.let { p -> item(key = "problem") { Notice("Last look failed: $p", color = Herdr.colors.notice, icon = HerdrIcons.Clock) } }
            if (report != null) item(key = "low") { LowNotice(state.machine.label, report) }
            item(key = "now") { NowCard(sample) }
            item(key = "hour") { HourCard(history[machineId].orEmpty(), sample.at) }

            val gradle = model.gradle
            if (gradle.isNotEmpty()) {
                item(key = "gradle") {
                    QuietCard(Modifier.padding(top = Space.s)) {
                        ListRow(
                            if (gradle.size == 1) "1 Gradle daemon" else "${gradle.size} Gradle daemons",
                            subtitle = "${Sizes.kb(gradle.sumOf { it.proc.rssKb })} · the next build starts one again",
                            leading = { IconTile(HerdrIcons.Zap) },
                            trailing = { SecondaryButton("Stop", enabled = model.busy == null) { confirm = Confirm.Gradle } },
                        )
                    }
                }
            }

            item(key = "procs-head") {
                SectionHeading("Processes") {
                    SegmentedPills(listOf("Memory", "CPU"), if (byCpu) 1 else 0, { byCpu = it == 1 }, Modifier.width(180.dp))
                }
            }
            if (shown.isEmpty()) {
                item(key = "procs-none") { Text("Nothing busy right now.", style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted) }
            } else {
                item(key = "procs") {
                    QuietCard {
                        shown.forEachIndexed { i, p ->
                            if (i > 0) RowDivider(inset = 68.dp)
                            ProcRow(p, sample.cores, byCpu) { openProc = p }
                        }
                    }
                }
            }
            item(key = "foot") {
                Text(
                    "Big and busy processes, and your own in herdr worktrees. Read over SSH every ${EVERY_MS / 1000} s while this screen is open.",
                    style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted, modifier = Modifier.padding(top = Space.s),
                )
            }
        }
    }

    openProc?.let { p ->
        // Follow the process while its sheet is open; it may have ended.
        val live = model.procs.firstOrNull { it.proc.pid == p.proc.pid } ?: p
        ProcSheet(live, sample?.cores ?: 1, onDismiss = { openProc = null }, onStop = {
            openProc = null
            confirm = Confirm.Stop(listOf(live), live.name, live.name)
        })
    }
    confirm?.let { c -> ConfirmDialog(c, state.machine.label, model) { confirm = null } }
}

private fun CoroutineScope.launchLook(model: HealthModel) = launch { model.look() }

// ---- now ------------------------------------------------------------------------------------------

@Composable
private fun LowNotice(machine: String, r: HealthReport) {
    val (title, _, detail) = HealthWords.words(machine, r)
    Notice(title + "\n" + detail.replace("\n", " · "), color = Herdr.colors.red, icon = HerdrIcons.TriangleAlert)
}

@Composable
private fun NowCard(s: HealthSample) {
    val c = Herdr.colors
    QuietCard(padded = true) {
        val m = s.memory
        if (m != null) {
            Meter(
                HerdrIcons.MemoryStick, "RAM",
                "${Sizes.kb(m.availableKb)} free of ${Sizes.kb(m.totalKb)} · ${Sizes.percent(m.availablePercent)}",
                m.usedPercent / 100, level(low = HealthRules.ramLow(m), warn = m.availablePercent < HealthRules.RAM_OK + 5),
            )
            if (m.swapTotalKb > 0) {
                Meter(HerdrIcons.History, "Swap", "${Sizes.kb(m.swapUsedKb)} of ${Sizes.kb(m.swapTotalKb)} used", m.swapUsedPercent / 100, c.muted)
            }
        }
        val cpu = s.cpuBusy
        Meter(
            HerdrIcons.Cpu, "CPU",
            listOfNotNull(cpu?.let { "${Sizes.percent(it)} busy" }, "load ${"%.1f".format(Locale.ROOT, s.load1)} / ${s.cores} cores").joinToString(" · "),
            ((cpu ?: s.loadPercent) / 100).coerceIn(0.0, 1.0), level(low = false, warn = (cpu ?: s.loadPercent) > 90),
        )
        s.disks.forEach { d ->
            Meter(
                HerdrIcons.HardDrive, d.mount,
                "${Sizes.kb(d.availKb)} free of ${Sizes.kb(d.usedKb + d.availKb)}",
                d.usedPercent / 100, level(low = HealthRules.diskLow(d), warn = d.availKb < HealthRules.diskLimitKb(d) * 2),
            )
        }
    }
}

/** A meter's colour: red when low, the warning colour near it, else quiet. */
@Composable
private fun level(low: Boolean, warn: Boolean): Color = when {
    low -> Herdr.colors.red
    warn -> Herdr.colors.notice
    else -> Herdr.colors.text.copy(alpha = 0.55f)
}

@Composable
private fun Meter(icon: ImageVector, label: String, value: String, fraction: Double, color: Color) {
    val c = Herdr.colors
    Column(Modifier.fillMaxWidth().padding(vertical = Space.xs).semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = c.muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Space.s))
            Text(label, style = MaterialTheme.typography.titleMedium, color = c.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(Space.s))
            Text(
                value, style = MaterialTheme.typography.bodySmall, color = if (color == c.red) c.red else c.muted, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        Box(Modifier.padding(top = 6.dp, bottom = Space.xs).fillMaxWidth().height(6.dp).clip(HerdrShapes.Pill).background(c.hover)) {
            Box(Modifier.fillMaxWidth(fraction.toFloat().coerceIn(0.01f, 1f)).height(6.dp).clip(HerdrShapes.Pill).background(color))
        }
    }
}

// ---- the last hour --------------------------------------------------------------------------------

@Composable
private fun HourCard(points: List<HealthPoint>, now: Long) {
    val c = Herdr.colors
    var picked by remember { mutableStateOf<HealthPoint?>(null) }
    QuietCard(padded = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Last hour", style = MaterialTheme.typography.titleMedium, color = c.strong, modifier = Modifier.weight(1f))
            Legend(c.chart1, "RAM", dashed = false)
            Spacer(Modifier.width(Space.m))
            Legend(c.chart2, "CPU", dashed = true)
        }
        // The values at the picked moment (touch the chart), else now: the chart's direct labels.
        val at = picked ?: points.lastOrNull()
        Text(
            if (at == null) "Collecting…" else listOfNotNull(
                if (picked == null) "Now" else "${(now - at.at) / 60} min ago",
                "RAM ${Sizes.percent(at.memUsed)}",
                at.cpu?.let { "CPU ${Sizes.percent(it)}" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 2.dp, bottom = Space.s),
        )
        // The y axis in a gutter of its own, so its labels never sit on a line.
        Row {
            Box(Modifier.width(GUTTER).height(120.dp)) {
                Text("100%", style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.align(Alignment.TopStart).offset(y = (-8).dp))
                Text("50%", style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.align(Alignment.CenterStart))
                Text("0", style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.align(Alignment.BottomStart).offset(y = 8.dp))
            }
            Chart(points, now, picked) { picked = it }
        }
        Row(Modifier.fillMaxWidth().padding(top = Space.s, start = GUTTER)) {
            Text("60 min ago", style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.weight(1f))
            Text("30", style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("now", style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        if (points.size < 3) {
            Text(
                "The chart fills while this screen is open. With notifications on, herdr-push keeps the hour on the machine.",
                style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = Space.s),
            )
        }
    }
}

private val GUTTER = 40.dp

@Composable
private fun Legend(color: Color, label: String, dashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(16.dp).height(8.dp)) {
            drawLine(
                color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
            )
        }
        Spacer(Modifier.width(Space.xs))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Herdr.colors.text)
    }
}

@Composable
private fun Chart(points: List<HealthPoint>, now: Long, picked: HealthPoint?, onPick: (HealthPoint?) -> Unit) {
    val c = Herdr.colors
    val span = 3600f
    fun nearest(x: Float, width: Float): HealthPoint? {
        if (points.isEmpty()) return null
        // Seconds back from now, as a Float only once small: epoch seconds don't fit a Float's precision.
        val back = span - x / width * span
        return points.minByOrNull { kotlin.math.abs((now - it.at) - back) }
    }
    val described = points.lastOrNull()?.let { "RAM ${Sizes.percent(it.memUsed)} used now" } ?: "No readings yet"
    Canvas(
        Modifier.fillMaxWidth().height(120.dp)
            .semantics { contentDescription = "Last hour of RAM and CPU. $described" }
            .pointerInput(points, now) {
                detectTapGestures(onPress = { o ->
                    onPick(nearest(o.x, size.width.toFloat()))
                    tryAwaitRelease()
                    onPick(null)
                })
            }
            .pointerInput(points, now) {
                detectDragGestures(onDragEnd = { onPick(null) }, onDragCancel = { onPick(null) }) { change, _ ->
                    onPick(nearest(change.position.x, size.width.toFloat()))
                }
            },
    ) {
        val w = size.width
        val h = size.height
        fun x(at: Long) = ((span - (now - at).toFloat()) / span * w).coerceIn(0f, w)
        fun y(p: Double) = (h - (p / 100.0).toFloat().coerceIn(0f, 1f) * h)
        // Recessive grid: 0, 50 and 100%.
        listOf(0.0, 50.0, 100.0).forEach { g -> drawLine(c.line, Offset(0f, y(g)), Offset(w, y(g)), strokeWidth = 1.dp.toPx()) }
        fun line(values: List<Pair<Long, Double>>, color: Color, dashed: Boolean) {
            if (values.isEmpty()) return
            val path = Path()
            values.forEachIndexed { i, (at, v) -> if (i == 0) path.moveTo(x(at), y(v)) else path.lineTo(x(at), y(v)) }
            drawPath(
                path, color,
                style = Stroke(
                    width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())) else null,
                ),
            )
            if (values.size == 1) drawCircle(color, 3.dp.toPx(), Offset(x(values[0].first), y(values[0].second)))
        }
        line(points.mapNotNull { p -> p.cpu?.let { p.at to it } }, c.chart2, dashed = true)
        line(points.map { it.at to it.memUsed }, c.chart1, dashed = false)
        picked?.let { p ->
            drawLine(c.lineStrong, Offset(x(p.at), 0f), Offset(x(p.at), h), strokeWidth = 1.dp.toPx())
            drawCircle(c.card, 6.dp.toPx(), Offset(x(p.at), y(p.memUsed)))
            drawCircle(c.chart1, 4.dp.toPx(), Offset(x(p.at), y(p.memUsed)))
            p.cpu?.let { v ->
                drawCircle(c.card, 6.dp.toPx(), Offset(x(p.at), y(v)))
                drawCircle(c.chart2, 4.dp.toPx(), Offset(x(p.at), y(v)))
            }
        }
    }
}

// ---- processes ------------------------------------------------------------------------------------

private fun ProcKind.icon(): ImageVector = when (this) {
    ProcKind.Emulator -> HerdrIcons.Smartphone
    ProcKind.Vm -> HerdrIcons.Monitor
    ProcKind.Gradle, ProcKind.GradleWorker, ProcKind.KotlinDaemon -> HerdrIcons.Zap
    ProcKind.DevServer -> HerdrIcons.Network
    ProcKind.Node -> HerdrIcons.Code
    ProcKind.Herdr -> HerdrIcons.SquareTerminal
    ProcKind.Agent -> HerdrIcons.Bot
    ProcKind.Other -> HerdrIcons.Cpu
}

/** CPU as a share of the whole machine: one busy core of eight is 13%. */
private fun cpuText(cpu: Double, cores: Int) = Sizes.percent(cpu / cores.coerceAtLeast(1))

@Composable
private fun ProcRow(p: LabeledProc, cores: Int, byCpu: Boolean, onClick: () -> Unit) {
    val c = Herdr.colors
    val mem = Sizes.kb(p.proc.rssKb)
    val cpu = cpuText(p.proc.cpu, cores)
    ListRow(
        p.name,
        subtitle = p.ownerLine,
        meta = if (byCpu) "$cpu · $mem" else "$mem · $cpu",
        leading = {
            if (p.kind == ProcKind.Agent) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { AgentMark(p.name, size = 28.dp) }
            else IconTile(p.kind.icon())
        },
        onClickLabel = "Show the process",
        onClick = onClick,
    )
}

@Composable
private fun ProcSheet(p: LabeledProc, cores: Int, onDismiss: () -> Unit, onStop: () -> Unit) {
    val c = Herdr.colors
    HerdrSheet(onDismissRequest = onDismiss) {
        SheetTitle(p.name, p.ownerLine)
        Column(Modifier.padding(horizontal = Space.xl).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            InfoLine("Memory", Sizes.kb(p.proc.rssKb))
            InfoLine("CPU", "${cpuText(p.proc.cpu, cores)} of the machine")
            InfoLine("Running for", Sizes.age(p.proc.ageSeconds))
            InfoLine("Process id", p.proc.pid.toString(), mono = true)
            p.owner.agent.takeIf { it.isNotEmpty() }?.let { InfoLine("Agent", it) }
            p.owner.where.takeIf { it.isNotEmpty() }?.let { InfoLine("Pane", it) }
            p.proc.cwd.takeIf { it.isNotEmpty() }?.let { PathLine("Folder", it) }
            p.proc.launchDir.takeIf { it.isNotEmpty() && it != p.proc.cwd }?.let { PathLine("Started from", it) }
            FieldLabel("Command")
            SelectionContainer { MonoBlock(p.proc.args.ifEmpty { p.proc.comm }, maxLines = 6) }
            Spacer(Modifier.height(Space.s))
            when {
                !p.own -> Text("Another account's process: the app can stop only yours.", style = MaterialTheme.typography.bodySmall, color = c.muted)
                p.kind == ProcKind.Herdr -> Text(
                    "This is herdr itself; stopping it here would cut the app off. Use Server and updates instead.",
                    style = MaterialTheme.typography.bodySmall, color = c.muted,
                )
                else -> DangerButton("Stop process", Modifier.fillMaxWidth(), onClick = onStop)
            }
            Spacer(Modifier.height(Space.m))
        }
    }
}

/** A label over a path: paths are too long to share a line with it. */
@Composable
private fun PathLine(label: String, path: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = Space.xs)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted)
        Text(path.shortPath(), style = CodeStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize), color = Herdr.colors.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

// ---- confirmations and progress -----------------------------------------------------------------

@Composable
private fun ConfirmDialog(c: Confirm, machine: String, model: HealthModel, onDone: () -> Unit) {
    val guard = com.makefunstuff.herdr.ui.common.LocalGuard.current
    val (title, body, yes) = when (c) {
        is Confirm.Stop -> Triple(
            "Stop ${c.title}?",
            c.procs.joinToString("\n") { "${it.name} · ${Sizes.kb(it.proc.rssKb)} · pid ${it.proc.pid}" } +
                "\n\n" + stopNote(machine, model.windows),
            "Stop",
        )
        Confirm.Gradle -> Triple(
            "Stop Gradle daemons?",
            model.gradle.joinToString("\n") { "${it.name} · ${Sizes.kb(it.proc.rssKb)} · ${it.ownerLine}" } +
                "\n\nA build running in one of them fails; the next build starts a new daemon.",
            "Stop",
        )
    }
    HerdrDialog(
        onDismissRequest = onDone,
        title = { Text(title) },
        text = { Text(body) },
        dismissButton = { QuietButton(onClick = onDone) { Text("Cancel") } },
        confirmButton = {
            QuietButton(onClick = {
                onDone()
                // Then the fingerprint, when that is on: none of these can be taken back.
                guard.run(Guarded.StopProcess, title) {
                    when (c) {
                        is Confirm.Stop -> model.stop(c.procs, c.what)
                        Confirm.Gradle -> model.stopGradle()
                    }
                }
            }, color = Herdr.colors.red) { Text(yes) }
        },
    )
}

/** Your action in flight: the 3dp amber bar and what it is. */
@Composable
private fun Busy(words: String) {
    val c = Herdr.colors
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = c.primary, trackColor = c.hover)
        Text(words, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = Space.xs))
    }
}

/** A home-relative path, shorter on a phone screen. */
private fun String.shortPath(): String = replace(Regex("^/(home|Users)/[^/]+"), "~")
