package preview

/**
 * Voice messages in the previews: the stand-in for `ui/common/Mic.kt` records without a microphone.
 * [allowed] is the microphone permission and [answer] what the phone's question gives (null: it
 * stays up; [forever] when Android won't ask again). Recording, it is [level] loud; finished, the
 * recording is [spokeFor] ms long on the phone's clock. A voice message played stands at [playedTo]
 * (`standins/Playback.kt`). Talk to type (`standins/Ears.kt`): the phone's recogniser is there when
 * [recognises], has heard [heard] so far and writes down [said]; a machine writes down what
 * `FakeShepherdr` answers. Scenes set them with `mic`.
 */
object PreviewMic {
    /** What the phone's recogniser writes down in a scene. */
    const val SAID = "run the failing test again and tell me why it fails"

    @Volatile var allowed: Boolean = true
    @Volatile var answer: Boolean? = true
    @Volatile var forever: Boolean = false
    @Volatile var level: Float = 0.6f
    @Volatile var spokeFor: Long = 7_000
    @Volatile var playedTo: Long = 3_000
    @Volatile var recognises: Boolean = true
    @Volatile var heard: String = ""
    @Volatile var said: String = SAID

    /** The scene's microphone, set before the app starts. */
    data class Setup(
        val allowed: Boolean = true,
        val answer: Boolean? = true,
        val forever: Boolean = false,
        val level: Float = 0.6f,
        val spokeFor: Long = 7_000,
        val playedTo: Long = 3_000,
        val recognises: Boolean = true,
        val heard: String = "",
        val said: String = SAID,
    )

    fun set(s: Setup) {
        allowed = s.allowed; answer = s.answer; forever = s.forever; level = s.level; spokeFor = s.spokeFor; playedTo = s.playedTo
        recognises = s.recognises; heard = s.heard; said = s.said
    }
}
