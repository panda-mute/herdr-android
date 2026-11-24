package com.makefunstuff.herdr.ui.agents

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/** Observe, without consuming, one deliberate downward drag at the history row of a reversed chat. */
@Composable
internal fun Modifier.pullEarlier(list: LazyListState, rowKey: String, load: (() -> Unit)?): Modifier {
    val action = rememberUpdatedState(load)
    return pointerInput(list, rowKey) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var requested = false
            var lastY = down.position.y
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val finger = event.changes.firstOrNull { it.id == down.id } ?: break
                val delta = finger.position - down.position
                if (!requested && finger.position.y > lastY && delta.y > viewConfiguration.touchSlop && delta.y > abs(delta.x) &&
                    list.layoutInfo.visibleItemsInfo.any { it.key == rowKey }) {
                    action.value?.let { requested = true; it() }
                }
                lastY = finger.position.y
            } while (finger.pressed)
        }
    }
}
