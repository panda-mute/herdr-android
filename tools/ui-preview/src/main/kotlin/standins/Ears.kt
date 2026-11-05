// Stand-in for app/…/ui/common/Ears.kt, which listens with Android's speech recogniser and records with its
// MediaRecorder. The phone's recogniser hears what a scene puts in PreviewMic; the machine's recording is a few bytes.
package com.makefunstuff.herdr.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.makefunstuff.herdr.core.chat.Dictation
import preview.PreviewMic

class PhoneEars(
    val recogniser: (() -> Dictation.Ears)?,
    val tape: () -> Dictation.Tape,
)

@Composable
fun rememberPhoneEars(): PhoneEars = remember {
    PhoneEars(
        recogniser = if (PreviewMic.recognises) ({ PreviewRecogniser() }) else null,
        tape = { PreviewTape() },
    )
}

private class PreviewRecogniser : Dictation.Ears {
    override fun start() = true
    override suspend fun finish() = PreviewMic.said
    override fun cancel() {}
    override fun level() = PreviewMic.level
    override fun heard() = PreviewMic.heard
}

private class PreviewTape : Dictation.Tape {
    override val type = "audio/mp4"
    override fun start() = true
    override fun stop() = ByteArray(64)
    override fun cancel() {}
    override fun level() = PreviewMic.level
}
