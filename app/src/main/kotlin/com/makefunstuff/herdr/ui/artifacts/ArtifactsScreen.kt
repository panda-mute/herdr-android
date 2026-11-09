package com.makefunstuff.herdr.ui.artifacts

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.core.shepherdr.Artifacts
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.coordinator.ChatEmpty
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

/**
 * A space's artifacts on Shepherdr, newest first, as its web UI's Artifacts list: each with its picture,
 * title, version, who made it and when. One opens full screen at its latest; its older versions are
 * under its Versions there.
 */
@Composable
fun ArtifactsScreen(nav: NavController, machineId: String, space: String?, inTab: Boolean = false) {
    val container = LocalContainer.current
    val session = container.hosts.session(machineId)
    if (session == null) {
        ChatEmpty(HerdrIcons.Monitor, "No such machine", "It was removed from the app.")
        return
    }
    val shelf = rememberArtifactShelf(session)
    // The space's name, from the board, for the bar.
    var spaces by remember(space) { mutableStateOf<List<WorkSpace>?>(null) }
    LaunchedEffect(shelf.board, space) {
        val b = shelf.board ?: return@LaunchedEffect
        spaces = runCatching { b.overview().spaces }.getOrNull()
    }
    val spaceName = spaces?.firstOrNull { it.id == space }?.let { if (it.home) "Home" else it.name }
    val c = Herdr.colors
    // A home space lists its machine's too, which needs where each space is: its own only until that's read.
    val all = shelf.all?.let { all -> if (space == null) all.sortedByDescending { it.at } else Artifacts.inSpace(all, space, spaces.orEmpty()) }
    // 3.1's phone has a filter over the shelf; with real titles it is the only way to find one.
    var query by remember { mutableStateOf("") }
    val list = all?.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) || it.by.contains(query.trim(), ignoreCase = true) }
    Scaffold(
        containerColor = c.ground,
        topBar = {
            HerdrTopBar(
                "Artifacts",
                subtitle = listOfNotNull(spaceName, session.machine.label).joinToString(" · "),
                onBack = if (inTab) null else ({ nav.popBackStack() }),
            ) {
                BarIcon(HerdrIcons.FileText, "Docs on ${session.machine.label}", { nav.navigate(Routes.docs(machineId)) })
            }
        },
    ) { padding ->
        Box(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize().navigationBarsPadding()) {
            when {
                list == null && shelf.read -> ChatEmpty(HerdrIcons.LayoutDashboard, "No artifacts here", "Shepherdr on ${session.machine.label} keeps none.")
                list == null -> shelf.problem?.let { ChatEmpty(HerdrIcons.CircleAlert, "Can't reach Shepherdr", it) }
                    ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingState("Reading the artifacts") }
                all.isNullOrEmpty() -> ChatEmpty(
                    HerdrIcons.LayoutDashboard, "No artifacts yet",
                    "Ask ${spaceName?.let { "$it's Assistant" } ?: "an Assistant"} for one: a report, a dashboard, a draft screen. Every agent can make them.",
                )
                else -> Column(Modifier.fillMaxSize()) {
                    HerdrField(
                        query, { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = Space.screen).padding(top = Space.s, bottom = Space.xs),
                        placeholder = { Text("Filter artifacts") }, leadingIcon = { Icon(HerdrIcons.Search, null, Modifier.size(18.dp)) },
                        singleLine = true,
                    )
                    if (list.isEmpty()) {
                        Text(
                            "No artifact matches “${query.trim()}”", style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted,
                            modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.m),
                        )
                    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(list, key = { it.id }) { a ->
                            ListRow(
                                a.title,
                                subtitle = listOf("v${a.v}", a.by, a.`when`).filter { it.isNotBlank() }.joinToString(" · "),
                                leading = { ArtifactThumb(machineId, shelf.board, a, Modifier.size(width = 56.dp, height = 44.dp)) },
                                onClickLabel = "Open",
                                onClick = { nav.navigate(Routes.artifact(machineId, a.id)) },
                            )
                        }
                    }
                }
            }
        }
    }
}
