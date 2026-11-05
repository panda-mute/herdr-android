package com.makefunstuff.herdr.ui.code

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.code.CodeClient
import com.makefunstuff.herdr.core.code.FileChange
import com.makefunstuff.herdr.core.code.FileDiff
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.data.CodeCache
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where the code browser is: a machine, a folder on it (a checkout once it is opened), and where it
 * was opened from: a Shepherdr task's view, an agent's pane. Every code screen's route carries it, so a
 * comment written anywhere in it can go to that task's or that agent's attention.
 */
@Immutable
data class CodeSpot(
    val machine: String,
    val dir: String = "",
    /** The board task it was opened from: its id and the name it shows (`AND-9`). */
    val task: String? = null,
    val taskName: String? = null,
    /** The agent's pane it was opened from. */
    val pane: String? = null,
    /** The machine whose Shepherdr board holds [task], when it isn't [machine] (the task works on another machine). */
    val board: String? = null,
) {
    /** The route's query: `dir=…&task=…&tname=…&pane=…&board=…`. */
    fun query(): String = listOfNotNull(
        "dir=" + Uri.encode(dir),
        task?.let { "task=" + Uri.encode(it) },
        taskName?.let { "tname=" + Uri.encode(it) },
        pane?.let { "pane=" + Uri.encode(it) },
        board?.let { "board=" + Uri.encode(it) },
    ).joinToString("&")

    companion object {
        /** The query's part of a route pattern. */
        const val ARGS = "dir={dir}&task={task}&tname={tname}&pane={pane}&board={board}"

        fun of(machine: String, arg: (String) -> String?) = CodeSpot(
            machine,
            dir = arg("dir")?.let(Uri::decode).orEmpty(),
            task = arg("task")?.let(Uri::decode)?.ifEmpty { null },
            taskName = arg("tname")?.let(Uri::decode)?.ifEmpty { null },
            pane = arg("pane")?.let(Uri::decode)?.ifEmpty { null },
            board = arg("board")?.let(Uri::decode)?.ifEmpty { null },
        )
    }
}

/** The machine's code, read over its SSH connection. */
fun HostSession.code(): CodeClient = CodeClient { command, timeout -> awaitConnection().run(command, timeout) }

/** What a screen reads from a machine: being read, read, or why it couldn't be. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(override val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>

    val value: T? get() = (this as? Ready<T>)?.value
}

/**
 * Reads [key] with [read] once, and again each time [again] changes; while it reads again, what
 * was read stays on screen. What was read is kept in [cache] for the next screen that asks.
 */
@Composable
fun <T : Any> rememberLoad(key: String, cache: CodeCache, again: Int = 0, read: suspend () -> T): Load<T> {
    var state by remember(key) { mutableStateOf<Load<T>>(cache.get<T>(key)?.let { Load.Ready(it) } ?: Load.Loading) }
    LaunchedEffect(key, again) {
        if (again == 0 && state is Load.Ready) return@LaunchedEffect
        if (state !is Load.Ready) state = Load.Loading
        state = try {
            withContext(Dispatchers.IO) { read() }.also { cache.put(key, it) }.let { Load.Ready(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Load.Failed(t.message ?: "Couldn't read it")
        }
    }
    return state
}

/** [path] with the account's home folder as `~`, as people say it. */
fun HostState.shortPath(path: String): String {
    val user = machine.user
    for (home in listOf("/home/$user", "/Users/$user", "/root".takeIf { user == "root" })) {
        if (home == null) continue
        if (path == home) return "~"
        if (path.startsWith("$home/")) return "~" + path.removePrefix(home)
    }
    return path
}

/** "3 min ago", "5 h ago", "2 days ago": [at] in seconds since the epoch, [nowMillis] the clock's time. */
fun ago(at: Long, nowMillis: Long): String {
    val s = (nowMillis / 1000 - at).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        s < 86400 * 60 -> "${s / 86400} day${if (s / 86400 == 1L) "" else "s"} ago"
        else -> java.time.Instant.ofEpochSecond(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
    }
}

/** The icon a file is drawn with. */
fun fileIcon(path: String): ImageVector {
    val name = path.substringAfterLast('/').lowercase()
    val ext = name.substringAfterLast('.', "")
    return when {
        ext in setOf("md", "markdown", "txt", "rst", "adoc") || name.startsWith("readme") || name.startsWith("license") -> HerdrIcons.FileText
        com.makefunstuff.herdr.core.code.Syntax.language(path) != null -> HerdrIcons.FileCode
        else -> HerdrIcons.File
    }
}

/** A diff's file: its letter (A, M, D, R) and its colour, a state's. */
@Composable
fun FileChange.letter(): Pair<String, Color> = Herdr.colors.let { c ->
    when (this) {
        FileChange.Added -> "A" to c.done
        FileChange.Deleted -> "D" to c.red
        FileChange.Renamed -> "R" to c.working
        FileChange.Copied -> "C" to c.working
        FileChange.Modified -> "M" to c.idle
    }
}

fun FileChange.word(): String = when (this) {
    FileChange.Added -> "added"
    FileChange.Deleted -> "deleted"
    FileChange.Renamed -> "renamed"
    FileChange.Copied -> "copied"
    FileChange.Modified -> "modified"
}

/** The change's letter on its state's tint, as a badge. */
@Composable
fun ChangeLetter(change: FileChange, modifier: Modifier = Modifier) {
    val (letter, color) = change.letter()
    val c = Herdr.colors
    Box(
        modifier.size(24.dp).background(if (change == FileChange.Modified) c.hover else c.tint(color), HerdrShapes.Badge),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = CodeStyle.copy(fontWeight = FontWeight.SemiBold), color = if (change == FileChange.Modified) c.muted else color)
    }
}

/** "+12 −3" in the done and blocked colours. */
@Composable
fun AddedRemoved(added: Int, removed: Int, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (added > 0 || removed == 0) Text("+$added", style = MaterialTheme.typography.labelMedium, color = c.done)
        if (added > 0 && removed > 0) Spacer(Modifier.width(6.dp))
        if (removed > 0) Text("−$removed", style = MaterialTheme.typography.labelMedium, color = c.red)
    }
}

/** A file of a diff, as a row: its change, its name and folder, how many lines. */
@Composable
fun DiffFileRow(f: FileDiff, onClick: () -> Unit) {
    val c = Herdr.colors
    com.makefunstuff.herdr.ui.design.ListRow(
        title = f.path.substringAfterLast('/'),
        subtitle = buildString {
            append(f.path.substringBeforeLast('/', "").ifEmpty { "top folder" })
            if (f.change == FileChange.Renamed && f.oldPath != null) append(" · from ").append(f.oldPath)
        },
        leading = { ChangeLetter(f.change) },
        trailing = {
            when {
                f.binary -> Text("binary", style = MaterialTheme.typography.labelMedium, color = c.muted)
                f.modeOnly -> Text("mode", style = MaterialTheme.typography.labelMedium, color = c.muted)
                else -> AddedRemoved(f.added, f.removed)
            }
        },
        onClickLabel = "Show the changes",
        onClick = onClick,
    )
}

/** A count in a quiet pill beside a title. */
@Composable
fun CountPill(text: String) {
    val c = Herdr.colors
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = c.muted,
        modifier = Modifier.defaultMinSize(minWidth = 20.dp).background(c.hover, HerdrShapes.Pill).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** The agents at work in [top] (their folder is in it), the one of [pane] first. */
fun HostState.agentsIn(top: String, pane: String? = null): List<com.makefunstuff.herdr.core.api.AgentInfo> {
    val snap = snapshot ?: return emptyList()
    fun inside(dir: String?) = dir != null && top.isNotEmpty() && (dir == top || dir.startsWith(top.trimEnd('/') + "/"))
    return snap.agents.filter { a ->
        val p = snap.panes.firstOrNull { it.paneId == a.paneId }
        a.paneId == pane || inside(a.cwd) || inside(p?.foregroundCwd) || inside(p?.cwd)
    }.sortedBy { if (it.paneId == pane) 0 else 1 }
}

/** An agent's name to show: its own name, or its kind. */
val com.makefunstuff.herdr.core.api.AgentInfo.shownName: String get() = name?.takeIf { it.isNotBlank() } ?: displayName

/** Whether an agent waits for an answer, so a message is typed into its question rather than sent as a prompt. */
val com.makefunstuff.herdr.core.api.AgentInfo.blocked: Boolean get() = agentStatus == AgentStatus.Blocked
