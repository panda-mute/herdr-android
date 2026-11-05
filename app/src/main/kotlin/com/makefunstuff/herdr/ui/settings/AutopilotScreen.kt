package com.makefunstuff.herdr.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.makefunstuff.herdr.core.board.WorkBoard
import com.makefunstuff.herdr.core.shepherdr.Autopilot
import com.makefunstuff.herdr.core.shepherdr.AutopilotSettings
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.ActionRow
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings › Autopilot on one machine, as 3.1's page (Everywhere's; `docs/parity/autopilot.md`): how it works in every
 * space there, its heartbeat, schedule and events. No switch here: a space turns it on in its Assistant's header.
 * herdr-task's refusal shows in red under the row that was set.
 */
@Composable
fun AutopilotScreen(nav: NavController, machineId: String) {
    val container = LocalContainer.current
    val session = container.hosts.session(machineId)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val state by session.state.collectAsStateWithLifecycle()
    var board by remember { mutableStateOf<WorkBoard?>(null) }
    var settings by remember { mutableStateOf<AutopilotSettings?>(null) }
    var read by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(machineId, state.online) {
        if (!state.online) return@LaunchedEffect
        while (board == null) {
            try {
                board = session.awaitWorkBoard()
                problem = null
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                problem = t.message ?: "Couldn't reach Shepherdr"
                delay(5_000)
            }
        }
    }
    LaunchedEffect(board) {
        val b = board ?: return@LaunchedEffect
        while (true) {
            try {
                b.overviews().collect {
                    settings = it.autopilot
                    read = true
                    problem = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                problem = t.message ?: "Couldn't read Shepherdr's settings"
            }
            delay(5_000)
        }
    }

    val c = Herdr.colors
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = c.ground,
        topBar = { HerdrTopBar(Autopilot.TITLE, subtitle = state.machine.label, onBack = { nav.popBackStack() }) },
    ) { padding ->
        val s = settings
        val b = board
        when {
            s != null && b != null -> AutopilotPage(b, s, Modifier.padding(padding))
            read -> EmptyState("No Autopilot here", "Shepherdr on ${state.machine.label} has its Autopilot add-on off.", HerdrIcons.Info)
            problem != null -> Column(Modifier.padding(padding).padding(Space.screen)) { ErrorMessage(problem!!) }
            else -> LoadingState("Reading Autopilot", Modifier.padding(padding))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutopilotPage(board: WorkBoard, s: AutopilotSettings, modifier: Modifier) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val c = Herdr.colors
    /** herdr-task's refusal, by the row's key; a row's own until it is set again. */
    var refused by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    fun warn(key: String) = refused[key] ?: s.warns[key]

    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).padding(top = Space.s, bottom = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text(s.about, style = MaterialTheme.typography.bodyMedium, color = c.muted)

        // The heartbeat: saved a moment after typing stops, on leaving the box and on leaving the page.
        var beat by remember { mutableStateOf(s.heartbeat) }
        var saved by remember { mutableStateOf(s.heartbeat) }
        var showSaved by remember { mutableStateOf(false) }
        fun saveBeat(onPage: Boolean = true) {
            val words = beat
            if (words == saved) return
            saved = words
            val save = suspend {
                try {
                    board.autopilotSetting(Autopilot.HEARTBEAT, words)
                    if (onPage) {
                        refused = refused - Autopilot.HEARTBEAT
                        showSaved = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    if (onPage) refused = refused + (Autopilot.HEARTBEAT to (t.message ?: Autopilot.NO_HEARTBEAT))
                }
            }
            // Leaving the page: the app's own scope, so the save outlives the screen.
            if (onPage) scope.launch { save() } else container.scope.launch { save() }
        }
        LaunchedEffect(beat) {
            delay(Autopilot.SAVE_AFTER_MS)
            saveBeat()
        }
        LaunchedEffect(showSaved) {
            if (showSaved) {
                delay(1_500)
                showSaved = false
            }
        }
        val leave by rememberUpdatedState { saveBeat(onPage = false) }
        DisposableEffect(Unit) { onDispose { leave() } }
        QuietCard(padded = true) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                RowHead(Autopilot.HEARTBEAT, trailing = if (showSaved) Autopilot.SAVED else null)
                s.file?.let { Text(it, style = CodeStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize), color = c.muted) }
                HerdrField(
                    value = beat,
                    onValueChange = { beat = it },
                    textStyle = CodeStyle,
                    minLines = 4,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) saveBeat() },
                    isError = warn(Autopilot.HEARTBEAT) != null,
                    supportingText = warn(Autopilot.HEARTBEAT)?.let { { Text(it) } },
                )
            }
        }

        // The schedule: a pick is set at once; Custom's words when the box is left.
        var every by remember(s.every) { mutableStateOf(s.every) }
        val preset = Autopilot.presetOf(every)
        var custom by remember { mutableStateOf(preset == null) }
        var words by remember(s.every) { mutableStateOf(if (Autopilot.presetOf(s.every) == null) s.every else "") }
        fun setEvery(v: String) {
            val before = every
            every = v
            refused = refused - Autopilot.EVERY
            scope.launch {
                try {
                    board.autopilotSetting(Autopilot.EVERY, v)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    if (every == v) every = before
                    refused = refused + (Autopilot.EVERY to (t.message ?: "Autopilot didn't change."))
                }
            }
        }
        QuietCard(padded = true) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                RowHead(Autopilot.EVERY)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Autopilot.PRESETS.forEach { (value, label) ->
                        HerdrChip(label, selected = !custom && preset == value, onClick = {
                            custom = false
                            if (preset != value) setEvery(value)
                        })
                    }
                    HerdrChip(Autopilot.CUSTOM, selected = custom, onClick = { custom = true })
                }
                if (custom) {
                    fun saveWords() {
                        val v = words.trim()
                        if (v.isNotEmpty() && v != every.trim()) setEvery(v)
                    }
                    HerdrField(
                        value = words,
                        onValueChange = { words = it },
                        singleLine = true,
                        placeholder = { Text(Autopilot.CUSTOM_PLACEHOLDER) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) saveWords() },
                        isError = warn(Autopilot.EVERY) != null,
                        supportingText = { Text(warn(Autopilot.EVERY) ?: Autopilot.CUSTOM_HINT) },
                    )
                } else warn(Autopilot.EVERY)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.red) }
            }
        }

        // The events: each chip on or off, set at once.
        var events by remember(s.events) { mutableStateOf(s.events) }
        QuietCard(padded = true) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                RowHead(Autopilot.EVENTS)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Autopilot.EVENT_PICKS.forEach { (value, label) ->
                        val on = value in events
                        HerdrChip(label, selected = on, onClick = {
                            val before = events
                            events = Autopilot.eventsIn(if (on) events - value else events + value)
                            val after = events
                            refused = refused - Autopilot.EVENTS
                            scope.launch {
                                try {
                                    board.autopilotSetting(Autopilot.EVENTS, after.joinToString(","))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (t: Throwable) {
                                    if (events == after) events = before
                                    refused = refused + (Autopilot.EVENTS to (t.message ?: "Autopilot didn't change."))
                                }
                            }
                        })
                    }
                }
                warn(Autopilot.EVENTS)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.red) }
            }
        }
    }
}

/** A row's label, its line under it, and [trailing] dim at its end ("Saved"). */
@Composable
private fun RowHead(key: String, trailing: String? = null) {
    val c = Herdr.colors
    val (label, about) = Autopilot.ROWS.getValue(key)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = c.strong, modifier = Modifier.weight(1f).semantics { heading() })
            if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = c.muted)
        }
        Text(about, style = MaterialTheme.typography.bodySmall, color = c.muted)
    }
}

/**
 * Settings' Autopilot card: one row for each machine on Shepherdr 3, each opening its Autopilot ([AutopilotScreen]).
 * None while no machine runs it.
 */
@Composable
fun AutopilotCard(onOpen: (String) -> Unit) {
    val container = LocalContainer.current
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val on = hosts.filter { it.onShepherdr }
    if (on.isEmpty()) return
    val c = Herdr.colors
    QuietCard(padded = true) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(Autopilot.TITLE, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(
                "Each machine's heartbeat, schedule and events, the same in all its spaces. Turn it on in a space with the switch in its Assistant's header.",
                style = MaterialTheme.typography.bodyMedium, color = c.muted,
            )
            on.forEachIndexed { i, h ->
                if (i > 0) RowDivider(inset = 0.dp)
                ActionRow(h.machine.label, icon = HerdrIcons.Monitor, chevron = true, onClick = { onOpen(h.machine.id) })
            }
        }
    }
}
