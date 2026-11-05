package com.makefunstuff.herdr.ui.common

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.makefunstuff.herdr.core.chat.Dictation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.log10

/**
 * The phone's side of talk to type: its own speech recogniser (on the phone where it can), and a
 * recording for a machine that writes it down. Each start and failure is logged (tag `HerdrDictate`).
 */
class PhoneEars(
    /** A new recogniser's ears, or null on a phone without speech recognition. */
    val recogniser: (() -> Dictation.Ears)?,
    /** A new recording of what you say, for the machine. */
    val tape: () -> Dictation.Tape,
)

@Composable
fun rememberPhoneEars(): PhoneEars {
    val context = LocalContext.current
    return remember(context) {
        val app = context.applicationContext
        PhoneEars(
            recogniser = if (SpeechRecognizer.isRecognitionAvailable(app)) ({ Recogniser(app) }) else null,
            tape = { Recording(app) },
        )
    }
}

/**
 * Android's recogniser, kept listening until Done: it ends each sentence by itself after a pause, so
 * it is started again, and what each heard is put together. Partial results show while you speak.
 */
private class Recogniser(private val context: Context) : Dictation.Ears {
    private var r: SpeechRecognizer? = null
    private val said = mutableListOf<String>()
    @Volatile private var partial = ""
    @Volatile private var loud = 0f
    @Volatile private var stopped: String? = null
    private var listening = false
    private var finishing: CompletableDeferred<Unit>? = null

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { said += it }
            partial = ""
            next()
        }
        override fun onPartialResults(partialResults: Bundle) {
            partial = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        }
        override fun onError(error: Int) {
            // A pause with nothing said ends one turn too: it goes on. Anything else stops it.
            if (partial.isNotBlank()) said += partial.trim()
            partial = ""
            if (finishing == null && error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                Log.w(TAG, "recogniser error $error")
                listening = false
                stopped = why(error)
                return
            }
            next()
        }
        override fun onRmsChanged(rmsdB: Float) { loud = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** One turn over: Done waits for it, else it listens again. */
    private fun next() {
        listening = false
        finishing?.let { it.complete(Unit); return }
        listen()
    }

    private fun listen() {
        listening = true
        runCatching { r?.startListening(intent) }.onFailure { Log.w(TAG, "couldn't listen", it); listening = false; stopped = why(SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun start(): Boolean = try {
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else SpeechRecognizer.createSpeechRecognizer(context)
        rec.setRecognitionListener(listener)
        r = rec
        listen()
        Log.i(TAG, "listening on the phone")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "no recogniser", t)
        false
    }

    override suspend fun finish(): String = withContext(Dispatchers.Main) {
        if (listening) {
            val done = CompletableDeferred<Unit>().also { finishing = it }
            runCatching { r?.stopListening() }
            // The last words take a moment; a recogniser that never answers keeps what it had.
            withTimeoutOrNull(FINISH_MS) { done.await() }
        }
        if (partial.isNotBlank()) said += partial.trim()
        partial = ""
        release()
        said.joinToString(" ")
    }

    override fun cancel() {
        finishing = CompletableDeferred()
        runCatching { r?.cancel() }
        release()
    }

    private fun release() {
        runCatching { r?.destroy() }
        r = null
    }

    override fun level() = loud
    override fun heard() = (said + partial.trim()).filter { it.isNotEmpty() }.joinToString(" ")
    override fun trouble() = stopped

    private fun why(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "The phone's speech recognition needs the network, or its offline language pack."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "The phone's speech recognition isn't allowed the microphone."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The phone's speech recognition is busy. Try again."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone didn't start. Another app may be using it."
        else -> "The phone's speech recognition stopped (error $error). Try again."
    }
}

/** A recording for the machine to write down, as voice messages record: AAC in an `.m4a`, kept only until it is sent. */
private class Recording(private val context: Context) : Dictation.Tape {
    private var media: MediaRecorder? = null
    private var file: File? = null
    override val type = "audio/mp4"

    override fun start(): Boolean {
        val f = File(context.cacheDir, "dictation.m4a").apply { delete() }
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
            r.setOutputFile(f.path)
            r.prepare()
            r.start()
            media = r
            file = f
            Log.i(TAG, "recording for the machine")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "couldn't record", t)
            r.release()
            f.delete()
            false
        }
    }

    override fun stop(): ByteArray? {
        val r = media ?: return null
        val f = file
        media = null
        file = null
        val ok = runCatching { r.stop() }.onFailure { Log.w(TAG, "stop failed", it) }.isSuccess
        r.release()
        val audio = if (ok && f != null && f.length() > 0) f.readBytes() else null
        f?.delete()
        return audio
    }

    override fun cancel() {
        media?.let { r -> runCatching { r.stop() }; r.release() }
        media = null
        file?.delete()
        file = null
    }

    override fun level(): Float {
        val amp = runCatching { media?.maxAmplitude ?: 0 }.getOrDefault(0)
        if (amp <= 0) return 0f
        return ((20 * log10(amp / 32767.0) + 50) / 50).toFloat().coerceIn(0f, 1f)
    }
}

private const val FINISH_MS = 4_000L
private const val TAG = "HerdrDictate"
