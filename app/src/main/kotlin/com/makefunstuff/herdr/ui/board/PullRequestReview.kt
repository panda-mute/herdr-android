package com.makefunstuff.herdr.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.github.PrNote
import com.makefunstuff.herdr.core.github.PrReview
import com.makefunstuff.herdr.core.github.PrThread
import com.makefunstuff.herdr.core.github.ReviewClient
import com.makefunstuff.herdr.core.github.ReviewVerdict
import com.makefunstuff.herdr.core.github.Reviews
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.ui.code.ago
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrSheet
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.design.SheetTitle
import com.makefunstuff.herdr.ui.theme.caps
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** gh on the task's machine, with the person's own login there: what reviews its pull request. */
internal fun HostSession.github(): ReviewClient = ReviewClient { awaitConnection() }

/** What the review sheet writes: a reply in a thread, a comment on the conversation, or a review with its verdict. */
internal sealed interface ReviewWrite {
    data class Reply(val thread: PrThread) : ReviewWrite
    data object Comment : ReviewWrite
    data class Verdict(val verdict: ReviewVerdict) : ReviewWrite
}

/**
 * A pull request to review, as the desktop's PR panel shows it: its head, Approve, Request changes and
 * Comment, its checks, the open review threads (each with the code it is on, a reply and Resolve), the
 * resolved ones folded, and the conversation. [onWrite] opens the sheet, [onResolve] resolves or reopens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReviewBody(
    r: PrReview,
    onWrite: (ReviewWrite) -> Unit,
    onResolve: (PrThread) -> Unit,
    resolving: Set<String> = emptySet(),
    note: String? = null,
) {
    val c = Herdr.colors
    val now = LocalContainer.current.code.now()
    var opened by rememberSaveable(r.ref.toString()) { mutableStateOf(setOf<String>()) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).padding(top = Space.m, bottom = Space.xxl),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        PrHead(r.pr)
        // Who opened it, and where its review stands (one word, in its colour).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("by ${r.author}", style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.weight(1f, fill = false))
            Reviews.decision(r.decision)?.let { d ->
                Text(" · ", style = MaterialTheme.typography.bodySmall, color = c.muted)
                Text(d.caps(), style = MaterialTheme.typography.labelSmall, color = decisionColor(r.decision))
            }
        }
        if (r.open) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                if (!r.mine) {
                    // Framed, not filled: the task's Accept above is the screen's one amber action.
                    SecondaryButton(onClick = { onWrite(ReviewWrite.Verdict(ReviewVerdict.Approve)) }) {
                        Icon(HerdrIcons.Check, null, Modifier.size(16.dp))
                        Text(ReviewVerdict.Approve.label)
                    }
                    SecondaryButton(onClick = { onWrite(ReviewWrite.Verdict(ReviewVerdict.RequestChanges)) }) {
                        Icon(HerdrIcons.CircleX, null, Modifier.size(16.dp))
                        Text(ReviewVerdict.RequestChanges.label)
                    }
                }
                SecondaryButton(onClick = { onWrite(ReviewWrite.Comment) }) {
                    Icon(HerdrIcons.MessageSquarePlus, null, Modifier.size(16.dp))
                    Text(Reviews.COMMENT)
                }
            }
            if (r.mine) Text(Reviews.OWN_PR, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        PrLinks(r.pr)
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.red) }

        if (r.body.isNotEmpty()) {
            SectionHeading("Description", top = Space.s)
            Folded(r.body)
        }

        PrChecks(r.pr)

        val open = r.threads.filter { !it.resolved }
        val done = r.threads.filter { it.resolved }
        SectionHeading(
            Reviews.OPEN_THREADS, top = Space.s, color = if (open.isNotEmpty()) c.accent else c.muted,
            trailing = if (r.threads.isNotEmpty()) ({ Text(Reviews.threadsLine(r.threads), style = MaterialTheme.typography.labelMedium, color = c.muted) }) else null,
        )
        if (open.isEmpty()) Text(if (r.threads.isEmpty()) Reviews.NO_THREADS else Reviews.ALL_RESOLVED, style = MaterialTheme.typography.bodyMedium, color = c.muted)
        open.forEach { th ->
            QuietCard { ThreadBody(th, now, r.open, onWrite, onResolve, busy = th.id in resolving) }
        }
        if (done.isNotEmpty()) {
            SectionHeading(Reviews.RESOLVED, top = Space.s, trailing = { Text("${done.size}", style = MaterialTheme.typography.labelMedium, color = c.muted) })
            // Folded to a line each; a tap opens one in place.
            QuietCard {
                done.forEachIndexed { i, th ->
                    if (i > 0) RowDivider(inset = 0.dp)
                    val shown = th.id in opened
                    ListRow(
                        title = th.place,
                        subtitle = "${th.comments.first().author}: ${th.comments.first().body.lineSequence().firstOrNull().orEmpty()}",
                        leading = { Icon(HerdrIcons.CircleCheck, null, tint = c.done, modifier = Modifier.size(20.dp)) },
                        trailing = { Icon(if (shown) HerdrIcons.ChevronUp else HerdrIcons.ChevronDown, null, tint = c.muted, modifier = Modifier.size(16.dp)) },
                        onClickLabel = if (shown) "Fold it" else "Show the thread",
                        onClick = { opened = if (shown) opened - th.id else opened + th.id },
                    )
                    if (shown) ThreadBody(th, now, r.open, onWrite, onResolve, head = false, busy = th.id in resolving)
                }
            }
        }

        SectionHeading(Reviews.CONVERSATION, top = Space.s, trailing = { Text("${r.activity.size}", style = MaterialTheme.typography.labelMedium, color = c.muted) })
        if (r.activity.isNotEmpty()) QuietCard {
            r.activity.forEachIndexed { i, n ->
                if (i > 0) RowDivider(inset = 0.dp)
                NoteBody(n, now)
            }
        }
        if (r.open) QuietButton(onClick = { onWrite(ReviewWrite.Comment) }) {
            Icon(HerdrIcons.MessageSquarePlus, null, Modifier.size(16.dp))
            Text("Leave a comment")
        }
    }
}

/** A thread's place, the last lines of the code it is on, its comments, then Reply and Resolve (or Reopen). */
@Composable
private fun ThreadBody(
    th: PrThread,
    now: Long,
    open: Boolean,
    onWrite: (ReviewWrite) -> Unit,
    onResolve: (PrThread) -> Unit,
    head: Boolean = true,
    busy: Boolean = false,
) {
    val c = Herdr.colors
    if (head) Row(Modifier.fillMaxWidth().padding(horizontal = Space.m).padding(top = Space.m), verticalAlignment = Alignment.CenterVertically) {
        val dir = th.path.substringBeforeLast('/', "")
        Text(
            (if (dir.isNotEmpty()) "$dir/" else ""), style = MaterialTheme.typography.labelMedium, color = c.muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        Text(th.place, style = MaterialTheme.typography.labelLarge, color = c.strong, maxLines = 1)
        Spacer(Modifier.weight(1f))
        if (th.outdated) Text("Outdated".caps(), style = MaterialTheme.typography.labelSmall, color = c.muted, modifier = Modifier.padding(start = Space.s))
    }
    if (th.hunk.isNotEmpty()) Hunk(th.hunk, Modifier.padding(top = Space.s))
    Column(Modifier.padding(horizontal = Space.m).padding(top = Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        th.comments.forEach { cm ->
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Who(cm.author, null, cm.at, now)
                if (cm.body.isNotEmpty()) MarkdownText(cm.body, color = c.text)
            }
        }
    }
    if (open) Row(Modifier.padding(horizontal = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        // A reply goes under the thread's first comment, by its id; without one there is nowhere to post it.
        if (th.comments.first().id != 0L) QuietButton(onClick = { onWrite(ReviewWrite.Reply(th)) }, Modifier.semantics { contentDescription = "Reply to ${th.place}" }) {
            Icon(HerdrIcons.Reply, null, Modifier.size(16.dp))
            Text(Reviews.REPLY)
        }
        if (th.id.isNotEmpty()) QuietButton(
            onClick = { onResolve(th) },
            modifier = Modifier.semantics { contentDescription = "${if (th.resolved) Reviews.REOPEN else Reviews.RESOLVE} ${th.place}" },
            enabled = !busy,
        ) {
            Icon(if (th.resolved) HerdrIcons.RotateCcw else HerdrIcons.Check, null, Modifier.size(16.dp))
            Text(if (th.resolved) Reviews.REOPEN else Reviews.RESOLVE)
        }
    } else Spacer(Modifier.padding(bottom = Space.m))
}

/** The code a thread is on: its last lines, added green and removed red, scrolling sideways, on the ground. */
@Composable
private fun Hunk(hunk: String, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    val lines = hunk.lines().filterNot { it.startsWith("@@") }.takeLast(4).map { it.replace("\t", "    ") }
    Column(
        modifier.fillMaxWidth().background(c.ground).horizontalScroll(rememberScrollState()).padding(horizontal = Space.m, vertical = Space.s),
    ) {
        lines.forEach { l ->
            Text(
                l.ifEmpty { " " },
                style = CodeStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize, lineHeight = MaterialTheme.typography.bodySmall.lineHeight),
                color = when (l.firstOrNull()) {
                    '+' -> c.done
                    '-' -> c.red
                    else -> c.text
                },
                maxLines = 1, softWrap = false,
            )
        }
    }
}

/** A review or a comment on the conversation: who, what their review did, when, and its words. */
@Composable
private fun NoteBody(n: PrNote, now: Long) {
    Column(Modifier.fillMaxWidth().padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Who(n.author, n.state, n.at, now)
        if (n.body.isNotEmpty()) MarkdownText(n.body, color = Herdr.colors.text)
    }
}

/** "ana · REQUESTED CHANGES · 2 h ago". */
@Composable
private fun Who(author: String, state: String?, at: Long, now: Long) {
    val c = Herdr.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(author, style = MaterialTheme.typography.labelLarge, color = c.strong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Reviews.verdict(state)?.let { v ->
            Text(v.caps(), style = MaterialTheme.typography.labelSmall, color = decisionColor(state), modifier = Modifier.padding(start = Space.s))
        }
        if (at > 0) Text(ago(at, now), style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(start = Space.s))
    }
}

/** A description, folded to its first lines when long, with More and Less. */
@Composable
private fun Folded(text: String) {
    val long = text.length > 600 || text.lines().size > 12
    var all by rememberSaveable(text) { mutableStateOf(false) }
    MarkdownText(if (!long || all) text else text.lines().take(12).joinToString("\n").take(600) + " …", color = Herdr.colors.text)
    if (long) QuietButton(onClick = { all = !all }) { Text(if (all) "Less" else "More") }
}

/** Approved green, changes requested red, the rest dim. */
@Composable
private fun decisionColor(state: String?): Color = Herdr.colors.let { c ->
    when (state) {
        "APPROVED" -> c.done
        "CHANGES_REQUESTED" -> c.red
        else -> c.muted
    }
}

/**
 * The sheet that writes to GitHub: a reply under the thread's last words, a comment, or a review with its
 * verdict. Posted as the machine's gh login only on its button; what was written stays if it fails.
 */
@Composable
internal fun ReviewSheet(r: PrReview, write: ReviewWrite, client: ReviewClient, scope: CoroutineScope, onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val key = when (write) {
        is ReviewWrite.Reply -> "reply:${write.thread.id}:${write.thread.comments.first().id}"
        ReviewWrite.Comment -> "comment"
        is ReviewWrite.Verdict -> write.verdict.name
    }
    var text by rememberSaveable(key) { mutableStateOf("") }
    // Whether the sheet is still up: a post outlives it (it runs in the screen's [scope]) and then says how it went in a toast.
    val up = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { up.set(false) } }
    var sending by remember { mutableStateOf(false) }
    // Why GitHub refused it, shown in the sheet with what was written kept.
    var refused by remember(write) { mutableStateOf<String?>(null) }
    val (title, subtitle, action) = when (write) {
        is ReviewWrite.Reply -> Triple("Reply", write.thread.path + (write.thread.line?.let { ":$it" } ?: ""), "Post reply")
        ReviewWrite.Comment -> Triple("Comment", "${r.ref.repo} #${r.ref.number}", "Post comment")
        is ReviewWrite.Verdict -> Triple(write.verdict.label, "${r.ref.repo} #${r.ref.number}", write.verdict.label)
    }
    val needsWords = write !is ReviewWrite.Verdict || write.verdict == ReviewVerdict.RequestChanges
    HerdrSheet(onDismiss) {
        SheetTitle(title, subtitle)
        Column(Modifier.padding(horizontal = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            if (write is ReviewWrite.Reply) write.thread.comments.last().let { last ->
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(last.author, style = MaterialTheme.typography.labelLarge, color = Herdr.colors.strong)
                    Text(last.body, style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            HerdrField(
                text, { text = it }, minLines = 3, maxLines = 8,
                placeholder = {
                    Text(
                        when {
                            write is ReviewWrite.Verdict && write.verdict == ReviewVerdict.Approve -> "A note with the approval (optional)"
                            write is ReviewWrite.Verdict -> "What needs changing"
                            else -> "Write a ${if (write is ReviewWrite.Reply) "reply" else "comment"}"
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            refused?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Herdr.colors.red) }
            Text(
                "Posted to GitHub as ${r.viewer.ifEmpty { "this machine's gh login" }}, only when you press ${action.replaceFirstChar { it.lowercase() }}.",
                style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted,
            )
            Row(Modifier.fillMaxWidth().padding(top = Space.xs, bottom = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End)) {
                QuietButton(onClick = onDismiss) { Text("Cancel") }
                PrimaryButton(if (sending) "Posting…" else action, enabled = !sending && (!needsWords || text.isNotBlank())) {
                    sending = true
                    refused = null
                    scope.launch {
                        try {
                            when (write) {
                                is ReviewWrite.Reply -> client.reply(r.ref, write.thread.comments.first().id, text)
                                ReviewWrite.Comment -> client.comment(r.ref, text)
                                is ReviewWrite.Verdict -> client.review(r.ref, write.verdict, text, r.headOid)
                            }
                            context.toast(
                                when (write) {
                                    is ReviewWrite.Reply -> "Reply posted"
                                    ReviewWrite.Comment -> "Comment posted"
                                    is ReviewWrite.Verdict -> write.verdict.done
                                },
                            )
                            onDone()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            val why = e.message ?: "GitHub didn't take it"
                            if (up.get()) refused = why else context.toast("Not posted: $why")
                        }
                        sending = false
                    }
                }
            }
        }
    }
}
