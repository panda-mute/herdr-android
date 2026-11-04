package com.makefunstuff.herdr.ui.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.board.WorkBoard
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.shepherdr.Accept
import com.makefunstuff.herdr.core.shepherdr.V2Evidence
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrSheet
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Shepherdr's Accept card, as its web app slides it up on a phone (`web/src/screens/Accept.tsx`, the words of
 * `core/accept.js`): what you approve before a Ready task merges. Its summary, then Changes (commits and files),
 * Checks, Proof and folded Details, all from `herdr-task show --evidence`; Accept runs `herdr-task accept`, which
 * merges first when there is something to merge. A task no longer Ready says where it is now, with only Close.
 * [onProof] opens its proof artifact; [onAccepted] once it went through.
 */
@Composable
fun AcceptSheet(
    board: WorkBoard?,
    task: WorkTask,
    onDismiss: () -> Unit,
    onProof: (String) -> Unit,
    onAccepted: (String) -> Unit = {},
    /** What runs Accept: the fingerprint first, where the app asks for it before a merge. */
    confirm: (run: () -> Unit) -> Unit = { it() },
    /** The evidence already read (the previews'); null reads it from [board]. */
    shown: V2Evidence? = null,
) {
    val c = Herdr.colors
    val t = MaterialTheme.typography
    val scope = rememberCoroutineScope()
    var e by remember(task.id) { mutableStateOf(shown) }
    var error by remember(task.id) { mutableStateOf<String?>(null) }
    var busy by remember(task.id) { mutableStateOf(false) }
    var details by remember(task.id) { mutableStateOf(false) }
    LaunchedEffect(board, task.id) {
        if (e != null || board == null) return@LaunchedEffect
        try {
            e = board.evidence(task)
        } catch (x: CancellationException) {
            throw x
        } catch (x: Exception) {
            error = x.message ?: "Couldn't read the task"
        }
    }
    val now = e
    val ready = now?.ready == true
    val settled = now != null && !ready
    val ref = now?.ref ?: task.name

    fun accept() {
        val b = board ?: return
        if (!ready || busy) return
        busy = true
        error = null
        scope.launch {
            try {
                onAccepted(b.approve(task))
                onDismiss()
            } catch (x: CancellationException) {
                throw x
            } catch (x: Exception) {
                error = x.message ?: "It wasn't accepted"
                // Accepted (or changed) from elsewhere meanwhile: where it is now, not the refusal.
                runCatching { b.evidence(task) }.getOrNull()?.takeIf { !it.ready }?.let { e = it; error = null }
                busy = false
            }
        }
    }

    HerdrSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Column {
                Text(if (settled) ref else Accept.title(ref), style = t.titleLarge, color = c.strong, modifier = Modifier.semantics { heading() })
                Text(now?.title?.ifEmpty { null } ?: task.title, style = t.bodySmall, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (now == null) {
                Text(error ?: Accept.READING, style = t.bodyLarge, color = if (error != null) c.red else c.muted)
            } else {
                val ev = now.evidence
                val (summary, more) = Accept.words(ev)
                if (summary.isEmpty()) Text(Accept.NO_SUMMARY, style = t.bodyLarge, color = c.muted)
                else MarkdownText(summary, style = t.bodyLarge)
                Section(Accept.CHANGES) { Changes(now) }
                Section(Accept.CHECKS) {
                    val k = ev?.checks
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (k != null && k.state != "none") {
                            StatusDot(when (k.state) { "passed" -> c.done; "running" -> c.working; else -> c.red }, size = 7)
                            Spacer(Modifier.width(Space.s))
                        }
                        Text(k?.text ?: Accept.NO_CHECKS, style = t.bodyMedium, color = if (k == null || k.state == "none") c.muted else c.text)
                    }
                }
                Section(Accept.PROOF) {
                    val p = ev?.proof
                    if (p == null) Text(Accept.NO_PROOF, style = t.bodyMedium, color = c.muted)
                    else QuietCard(onClick = { onProof(p.id) }, onClickLabel = Accept.OPEN) {
                        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = Space.m), verticalAlignment = Alignment.CenterVertically) {
                            Text(Accept.proofTitle(p.title), style = t.bodyLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(Accept.OPEN, style = t.labelMedium, color = c.muted)
                            Spacer(Modifier.width(Space.xs))
                            Icon(HerdrIcons.Maximize2, null, Modifier.size(14.dp), tint = c.muted)
                        }
                    }
                }
                if (more.isNotEmpty()) {
                    RowDivider(0.dp)
                    Row(Modifier.clickable { details = !details }.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (details) HerdrIcons.ChevronDown else HerdrIcons.ChevronRight, null, Modifier.size(14.dp), tint = c.muted)
                        Spacer(Modifier.width(Space.xs))
                        Text(Accept.DETAILS, style = t.labelLarge, color = c.muted)
                    }
                    if (details) MarkdownText(Accept.readable(more), Modifier.padding(start = 20.dp))
                }
                if (error != null) Text(error!!, style = t.bodyMedium, color = c.red)
                if (settled) Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (now.state == "done") c.done else c.idle, size = 7)
                    Spacer(Modifier.width(Space.s))
                    Text(Accept.whereNow(now), style = t.bodyLarge, color = c.text)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = Space.s, bottom = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End)) {
                SecondaryButton(if (settled) Accept.CLOSE else Accept.CANCEL, onClick = onDismiss)
                // Accept shows while it is read too, not yet pressable; once settled only Close is left.
                if (!settled) PrimaryButton(if (busy || task.accepting) Accept.ACCEPTING else Accept.ACCEPT, enabled = ready && !busy && board != null) { confirm(::accept) }
            }
        }
    }
}

@Composable
private fun Section(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RowDivider(0.dp)
        Text(label, style = MaterialTheme.typography.labelLarge, color = Herdr.colors.muted, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = Space.xs))
        content()
    }
}

@Composable
private fun Changes(now: V2Evidence) {
    val c = Herdr.colors
    val t = MaterialTheme.typography
    val ev = now.evidence
    if (ev?.git == false) return Text(Accept.NOT_GIT, style = t.bodyMedium, color = c.muted)
    val commits = ev?.commits
    val d = ev?.diff
    if ((commits?.count ?: 0) == 0 && (d?.files ?: 0) == 0) return Text(Accept.NO_COMMITS, style = t.bodyMedium, color = c.muted)
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Accept.commitsLine(commits?.count ?: 0), style = t.bodyMedium, color = c.text)
            if (d != null) {
                Text(" · ", style = t.bodyMedium, color = c.muted)
                Text(Accept.filesLine(d.files), style = t.bodyMedium, color = c.text)
                Spacer(Modifier.width(Space.s))
                Text("+${d.added}", style = CodeStyle, color = c.done)
                Spacer(Modifier.width(Space.xs))
                Text("−${d.removed}", style = CodeStyle, color = c.red)
            } else Text(" " + Accept.NO_FILES, style = t.bodyMedium, color = c.red)
        }
        commits?.list?.forEach { x ->
            Row {
                Text(x.sha, style = CodeStyle, color = c.muted)
                Spacer(Modifier.width(Space.s))
                Text(x.subject, style = t.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        commits?.let { if (it.count > it.list.size) Text(Accept.moreLine(it.count - it.list.size), style = t.labelMedium, color = c.muted) }
        d?.list?.takeIf { it.isNotEmpty() }?.let { files ->
            Box(Modifier.padding(top = Space.xs)) {
                Column {
                    files.forEach { f -> Text(f.path, style = CodeStyle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (d.files > files.size) Text(Accept.moreLine(d.files - files.size), style = t.labelMedium, color = c.muted)
                }
            }
        }
    }
}
