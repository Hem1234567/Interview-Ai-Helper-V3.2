package com.interviewcopilot.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Fallback: Android's built-in recognizer. It ends on every silence, so it is restarted automatically and every
 * finished phrase is appended: pauses never stop listening. Only [stop] ends the session.
 */
class PlatformRecognizer(
    private val ctx: Context,
    private val languageCode: String,
    private val listener: SttListener,
) : SttSession {
    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var lastPartial = ""
    private var stopRequested = false
    private var finished = false
    private var active = false
    private var errorsInRow = 0

    private fun tag(): String =
        if (languageCode == "auto") Locale.getDefault().toLanguageTag() else languageCode

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) = listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = first(partialResults)
            if (!t.isNullOrBlank()) {
                lastPartial = t
                listener.onPartial(t)
            }
        }

        override fun onResults(results: Bundle?) {
            cycleDone(first(results) ?: lastPartial)
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    fail("Microphone permission is missing.")
                }

                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    cycleDone(lastPartial)
                }

                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    fail("The phone's recognizer needs internet. Download a Whisper model in Settings to work offline.")
                }

                else -> {
                    active = false
                    if (stopRequested) {
                        finish()
                    } else if (++errorsInRow > 4) {
                        fail("Speech recognition failed (code $error).")
                    } else {
                        restartLater(300)
                    }
                }
            }
        }
    }

    private fun first(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun cycleDone(text: String) {
        active = false
        val t = text.trim()
        lastPartial = ""
        if (t.isNotBlank()) {
            errorsInRow = 0
            listener.onSegment(t)
        }
        if (stopRequested) finish() else restartLater(100)
    }

    private fun restartLater(ms: Long) {
        main.postDelayed({
            if (finished) return@postDelayed
            if (stopRequested) finish() else startCycle()
        }, ms)
    }

    private fun finish() {
        if (finished) return
        finished = true
        destroy()
        listener.onLevel(0f)
        listener.onEnded()
    }

    private fun fail(message: String) {
        if (finished) return
        finished = true
        destroy()
        listener.onError(message)
    }

    private fun destroy() {
        rec?.destroy()
        rec = null
    }

    private fun startCycle() {
        destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        r.setRecognitionListener(callbacks)
        rec = r
        active = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
        }
        r.startListening(intent)
    }

    override fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            fail("Speech recognition is not available on this phone.")
            return
        }
        finished = false
        stopRequested = false
        startCycle()
    }

    override fun stop() {
        stopRequested = true
        if (active) rec?.stopListening() else finish()
    }

    override fun cancel() {
        finished = true
        stopRequested = true
        main.removeCallbacksAndMessages(null)
        destroy()
    }

    override fun discard() {
        lastPartial = ""
        rec?.cancel()
        active = false
        listener.onPartial("")
        if (!stopRequested) restartLater(100)
    }
}
