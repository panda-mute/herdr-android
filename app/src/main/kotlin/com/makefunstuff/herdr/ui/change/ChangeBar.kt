package com.makefunstuff.herdr.ui.change

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.change.AppChange
import com.makefunstuff.herdr.core.change.BuildState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.Spinner
import com.makefunstuff.herdr.ui.manage.ConfirmDialog
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * Over the herdr-android Assistant's composer on the machine that builds the app: where the app's update is, as
 * one line ("HERD-31 accepted", "Building 0.1.131", "Update ready" with Install, a failure with Try again), else
 * the last change accepted from here with Undo. Nothing in any other chat.
 */
@Composable
fun ChangeBar(machineId: String, space: WorkSpace, spaces: List<WorkSpace>, tasks: List<WorkTask>, modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val changes = container.changes
    val state by changes.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var undoing by remember { mutableStateOf<WorkTask?>(null) }
    val saved = state.saved
    if (saved.builderId != machineId || AppChange.space(spaces)?.id != space.id) return
    val machine = container.hosts.session(machineId)?.machine?.label ?: "the machine"
    val c = Herdr.colors
    val update = saved.update
    val build = state.build
    val waiting = saved.accepted.filter { it !in saved.asked }
    val failed = state.problem ?: build?.takeIf { it.phase == BuildState.Phase.Failed && saved.waitingSince == null }?.let { it.error ?: "The build failed" }
    val last = changes.lastChange(tasks)

    fun retry() = scope.launch { runCatching { changes.buildNow() }.onFailure { context.toast(it.message ?: "Couldn't ask for the build") } }

    when {
        update != null -> Line(
            HerdrIcons.Download, c.accent, "Update ready · ${update.versionName}",
            if (update.source == "release") "CI's release of the merge, copied from $machine" else "Built on $machine with the release key",
            modifier,
        ) { PrimaryButton("Install", onClick = changes::install) }
        state.fetching -> Line(null, c.muted, "Copying the update", "${build?.versionName ?: "The new build"} from $machine", modifier)
        saved.waitingSince != null -> Line(null, c.muted, build?.takeIf { it.running }?.words ?: "Asked for the build", "On $machine · it installs from there", modifier)
        failed != null -> Line(
            HerdrIcons.CircleAlert, c.red, "The update didn't come", failed, modifier,
        ) { QuietButton(onClick = { retry() }, color = c.accent) { Text("Try again") } }
        waiting.isNotEmpty() -> Line(HerdrIcons.Hourglass, c.muted, "${waiting.joinToString(", ")} accepted", "The update is built once it is merged", modifier)
        last != null -> Line(HerdrIcons.Undo2, c.muted, "Last change · ${last.name}", last.title, modifier) {
            QuietButton(onClick = { undoing = last }, color = c.accent) { Text("Undo") }
        }
    }
    undoing?.let { t ->
        ConfirmDialog(
            "Undo ${t.name}?",
            "The Assistant reverts its merge as a new task. Once you accept that one, it ships as the next update: Android never installs an older build.",
            "Ask to undo",
            onDismiss = { undoing = null },
        ) {
            undoing = null
            scope.launch { runCatching { changes.undo(machineId, space.id, t) }.onFailure { context.toast(it.message ?: "Couldn't ask") } }
        }
    }
}

@Composable
private fun Line(icon: ImageVector?, tint: Color, title: String, line: String, modifier: Modifier, action: (@Composable () -> Unit)? = null) {
    val c = Herdr.colors
    QuietCard(modifier) {
        Row(Modifier.padding(start = Space.l, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = tint) else Spinner(size = 14.dp)
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f).padding(vertical = Space.s)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = c.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.labelMedium, color = if (tint == c.red) c.red else c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (action != null) Box(Modifier.padding(start = Space.s, top = Space.xs, bottom = Space.xs)) { action() }
        }
    }
}
