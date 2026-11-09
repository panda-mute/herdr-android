package com.makefunstuff.herdr.ui.docs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.docs.DocEntry
import com.makefunstuff.herdr.core.docs.DocsClient
import com.makefunstuff.herdr.core.docs.HerdrDoc
import com.makefunstuff.herdr.core.docs.HerdrDocs
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.ui.code.ago
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.coordinator.ChatEmpty
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.IconTile
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.images.ChatImages
import com.makefunstuff.herdr.ui.images.LocalChatImages
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.makefunstuff.herdr.core.docs.DocUnreadable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** A machine's herdr.doc documents, read over its SSH connection. */
fun HostSession.docs(): DocsClient = DocsClient { awaitConnection() }

/** How often an open list and an open document look for a change: an agent fills a draft in while it is read. */
private const val LIST_EVERY_MS = 15_000L
private const val DOC_EVERY_MS = 5_000L

/** The list shows its filter from this many documents up. */
private const val FILTER_FROM = 8

/**
 * A machine's docs, newest first, as the desktop's Docs panel lists them: investigations, incident notes, runbooks and
 * reports agents write to `~/.herdr/docs` with herdr-doc. One opens full screen. [inTab]: the Artifacts tab shows it
 * where the space's machine has no Shepherdr.
 */
@Composable
fun DocsScreen(nav: NavController, machineId: String, inTab: Boolean = false) {
    val container = LocalContainer.current
    val session = container.hosts.session(machineId)
    if (session == null) {
        ChatEmpty(HerdrIcons.Monitor, "No such machine", "It was removed from the app.")
        return
    }
    val state by session.state.collectAsStateWithLifecycle()
    var list by remember(session) { mutableStateOf<List<DocEntry>?>(null) }
    var problem by remember(session) { mutableStateOf<String?>(null) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(session, state.online) {
        if (!state.online) return@LaunchedEffect
        val client = session.docs()
        // Only while the app is in front: no SSH commands from a screen nobody sees.
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    list = client.list()
                    problem = null
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    problem = t.message ?: "Couldn't read the docs"
                }
                delay(LIST_EVERY_MS)
            }
        }
    }
    var query by remember { mutableStateOf("") }
    val c = Herdr.colors
    val now = container.clock()
    Scaffold(
        containerColor = c.ground,
        topBar = { HerdrTopBar("Docs", subtitle = session.machine.label, onBack = if (inTab) null else ({ nav.popBackStack() })) },
    ) { padding ->
        Box(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize().navigationBarsPadding()) {
            val all = list
            when {
                all == null && !state.online -> ChatEmpty(HerdrIcons.Cloud, "Offline", "The docs show once ${session.machine.label} connects.")
                all == null -> problem?.let { ChatEmpty(HerdrIcons.CircleAlert, "Can't read the docs", it) }
                    ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingState("Reading the docs") }
                all.isEmpty() -> ChatEmpty(
                    HerdrIcons.FileText, "No docs yet",
                    "Ask an agent for a write-up: an investigation, a runbook, a report. It writes ~/.herdr/docs on ${session.machine.label} with herdr-doc, and it shows here.",
                )
                else -> Column(Modifier.fillMaxSize()) {
                    problem?.let { Notice(it, Modifier.padding(horizontal = Space.screen, vertical = Space.xs), color = c.notice, icon = HerdrIcons.Clock) }
                    if (all.size >= FILTER_FROM) HerdrField(
                        query, { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = Space.screen).padding(top = Space.s, bottom = Space.xs),
                        placeholder = { Text("Filter docs") }, leadingIcon = { Icon(HerdrIcons.Search, null, Modifier.size(18.dp)) },
                        singleLine = true,
                    )
                    val shown = all.filter { e -> query.isBlank() || listOfNotNull(e.title, e.kind, e.slug).any { it.contains(query.trim(), ignoreCase = true) } }
                    if (shown.isEmpty()) {
                        Text(
                            "No doc matches “${query.trim()}”", style = MaterialTheme.typography.bodyMedium, color = c.muted,
                            modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.m),
                        )
                    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(shown, key = { it.path }) { e ->
                            val status = DocLook.status(c, e.status)
                            ListRow(
                                e.title,
                                subtitle = e.line(now),
                                subtitleColor = if (e.broken) c.red else androidx.compose.ui.graphics.Color.Unspecified,
                                titleLines = 2,
                                leading = { IconTile(DocLook.icon(e.kind), size = 40.dp) },
                                trailing = status?.let { (word, color) -> { StatusBadge(word, color) } },
                                onClickLabel = "Open",
                                onClick = { nav.navigate(Routes.doc(machineId, e.path)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One document full screen, drawn as the desktop draws it. It is read again while open, whenever its file changes, so a
 * draft an agent is writing fills in on the phone too.
 */
@Composable
fun DocScreen(nav: NavController, machineId: String, path: String) {
    val container = LocalContainer.current
    val session = container.hosts.session(machineId)
    if (session == null) {
        ChatEmpty(HerdrIcons.Monitor, "No such machine", "It was removed from the app.")
        return
    }
    val state by session.state.collectAsStateWithLifecycle()
    var doc by remember(session, path) { mutableStateOf<HerdrDoc?>(null) }
    var mtime by remember(session, path) { mutableStateOf<Long?>(null) }
    var problem by remember(session, path) { mutableStateOf<String?>(null) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(session, path, state.online) {
        if (!state.online) return@LaunchedEffect
        val client = session.docs()
        // The version last seen, shown or not: a file that can't be shown (too large, half-written) isn't fetched
        // again until it changes, and an unchanged one keeps what it showed.
        var seen: String? = null
        // Why the version seen can't be shown: it stays until the file changes; a failed look clears on the next.
        var unreadable: String? = null
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    val read = client.read(path, since = seen)
                    seen = read.stamp
                    mtime = read.mtime
                    read.doc?.let { doc = it; unreadable = null }
                    problem = unreadable
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DocUnreadable) {
                    seen = e.stamp
                    unreadable = e.message
                    problem = e.message
                } catch (t: Throwable) {
                    problem = t.message ?: "Couldn't read it"
                }
                delay(DOC_EVERY_MS)
            }
        }
    }
    val c = Herdr.colors
    val slug = path.substringAfterLast('/').removeSuffix(".hdoc.json")
    Scaffold(
        containerColor = c.ground,
        topBar = { HerdrTopBar("Document", subtitle = "$slug · ${session.machine.label}", onBack = { nav.popBackStack() }) },
    ) { padding ->
        Box(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
            val d = doc
            when {
                d == null && !state.online -> ChatEmpty(HerdrIcons.Cloud, "Offline", "It shows once ${session.machine.label} connects.")
                d == null -> problem?.let { ChatEmpty(HerdrIcons.CircleAlert, "Can't open it", it) }
                    ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingState("Reading the document") }
                else -> CompositionLocalProvider(LocalChatImages provides ChatImages(machineId, session, container.images, state.online)) {
                    val now = container.clock()
                    val updated = (updatedAt(d.updated) ?: mtime?.takeIf { it > 0 })?.let { "updated " + ago(it, now) }
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.m, bottom = Space.xxxl),
                        verticalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        problem?.let { p -> item(key = "problem") { Notice("$p · showing the last read", color = c.notice, icon = HerdrIcons.Clock) } }
                        item(key = "head") { DocHead(d, updated, Modifier.padding(bottom = Space.xs)) }
                        if (d.blocks.isEmpty()) item(key = "empty") {
                            Text("Nothing written yet.", style = MaterialTheme.typography.bodyMedium, color = c.muted)
                        }
                        // Each item keyed by its name, a section by its title and how many before it share it: a section's
                        // fold (its saved state) stays with it when an agent writes a block above it.
                        val names = HerdrDocs.names(d.blocks)
                        itemsIndexed(d.blocks, key = { i, _ -> names[i] }) { i, b -> DocBlockView(b, names[i], machineId) }
                    }
                }
            }
        }
    }
}

/** A document's `updated` (ISO 8601) in seconds since the epoch; null where it isn't one. */
internal fun updatedAt(text: String?): Long? = text?.let { t ->
    runCatching { java.time.OffsetDateTime.parse(t).toEpochSecond() }.getOrNull()
        ?: runCatching { java.time.Instant.parse(t).epochSecond }.getOrNull()
}
