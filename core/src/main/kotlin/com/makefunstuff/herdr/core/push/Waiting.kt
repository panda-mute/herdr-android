package com.makefunstuff.herdr.core.push

import com.makefunstuff.herdr.core.board.WorkOverview
import com.makefunstuff.herdr.core.board.WorkStatus
import kotlinx.serialization.Serializable

/**
 * One thing that waits on you, as the Needs you widget lists it: the notification's [alert] (its words, where a tap
 * goes, its buttons), since [at] (ms). [busy]: one of its buttons is being carried out; [note]: how the last one went
 * when it didn't work ("Took too long…").
 */
@Serializable
data class Waiting(
    val alert: Alert,
    val at: Long,
    val busy: String = "",
    val note: String = "",
)

/**
 * What the Needs you widget shows: the notifications that wait on you (a question, a task to Accept, a machine
 * running low), each with the buttons its notification has that work without typing (Approve, Deny, Accept). One
 * that is answered in words gets an Answer that opens it in the app; the widget has no reply box.
 */
object WaitingRows {
    /** A row has room for two buttons beside its words. */
    const val BUTTONS = 2

    /** Whether [a] waits on you until it is answered: what finished, a test or a limit only tells. */
    fun waits(a: Alert): Boolean = a.kind == Alert.Kind.NeedsYou

    /** The buttons a row of [a] shows, by their index in [Alert.actions]: those that need no words. */
    fun buttons(a: Alert): List<Int> = a.actions.withIndex().filter { !it.value.reply }.take(BUTTONS).map { it.index }

    /** The words of the button that opens [a] in the app instead, when it has none of [buttons] but is answered in words. */
    fun opens(a: Alert): String? = if (buttons(a).isEmpty()) a.actions.firstOrNull { it.reply }?.label else null

    /** Newest first. */
    fun order(all: Collection<Waiting>): List<Waiting> = all.sortedByDescending { it.at }

    /**
     * Whether a Shepherdr 3 task's alert from [machineId] still waits by its board as just read ([o]): its task still
     * Ready for an Accept, its question still open. The service sends no word when a task is accepted elsewhere.
     * True for what [o] can't tell (an agent's, a machine's).
     */
    fun stillWaits(a: Alert, machineId: String, o: WorkOverview): Boolean {
        if (a.machineId != machineId || !a.tag.startsWith(TaskAlerts.prefix(machineId))) return true
        if (a.accept) return o.tasks.any { it.id == a.task && it.status == WorkStatus.Ready }
        if (a.actions.none { it.type == AlertAction.Type.AnswerTask }) return true
        return o.needs.any { n -> if (a.task.isNotEmpty()) n.task == a.task else n.task == null && n.chat == a.chat }
    }
}
