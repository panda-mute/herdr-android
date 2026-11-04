package preview

import com.makefunstuff.herdr.core.voice.CallState
import com.makefunstuff.herdr.voice.VoiceCalls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Voice calls in the previews: no sound and no microphone, the state a scene sets in `call`. */
class PreviewVoiceCalls(start: CallState = CallState()) : VoiceCalls {
    private val _state = MutableStateFlow(start)
    override val state: StateFlow<CallState> = _state

    override fun start(machineId: String, space: String, task: String?, who: String) {
        _state.value = CallState(on = true, machineId = machineId, space = space, task = task, who = who, phase = CallState.Phase.Listening)
    }

    override fun stop() {
        _state.value = _state.value.copy(on = false)
    }

    companion object {
        /** What the next scene's call starts as (`SceneData.call`). */
        @Volatile var next = CallState()
    }
}
