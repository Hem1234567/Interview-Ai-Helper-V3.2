package com.interviewcopilot.app.voice

import android.os.Handler
import android.os.Looper
import com.interviewcopilot.app.core.AudioDsp
import com.interviewcopilot.app.core.Failure
import com.interviewcopilot.app.core.SpeechText
import com.interviewcopilot.app.core.Vad
import com.interviewcopilot.app.core.VadEvent
import com.interviewcopilot.app.core.Wav
import com.interviewcopilot.app.logic.Http
import com.interviewcopilot.app.logic.ProviderError
import com.interviewcopilot.app.logic.httpError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Turns one piece of 16 kHz mono audio into text. Blocking; throws when the engine cannot do it. */
interface SegmentTranscriber {
    fun transcribe(audio: FloatArray, context: String): String
}

/** whisper.cpp on the phone. [accurate] = beam search (slower, better). */
class LocalWhisperTranscriber(
    private val language: String,
    private val promptBase: String,
    private val threads: Int,
    private val accurate: Boolean,
) : SegmentTranscriber {
    override fun transcribe(audio: FloatArray, context: String): String {
        val prompt = (if (context.isBlank()) promptBase else "$promptBase $context").toByteArray(Charsets.UTF_8)
        val bytes = NativeWhisper.nativeTranscribe(audio, language, prompt, threads, accurate)
        return String(bytes, Charsets.UTF_8).trim()
    }
}

/** Groq's hosted Whisper large-v3-turbo: the most accurate option, needs internet and a Groq key. */
class GroqTranscriber(
    private val key: String,
    private val language: String,
    private val promptBase: String,
) : SegmentTranscriber {
    override fun transcribe(audio: FloatArray, context: String): String {
        val prompt = (if (context.isBlank()) promptBase else "$promptBase $context").takeLast(500)
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("prompt", prompt)
        if (language != "auto") form.addFormDataPart("language", language)
        form.addFormDataPart("file", "speech.wav", Wav.encode(audio).toRequestBody("audio/wav".toMediaType()))
        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $key")
            .post(form.build())
            .build()
        Http.client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw httpError("Groq speech", resp.code, body, resp.header("retry-after"))
            return JSONObject(body).optString("text", "").trim()
        }
    }
}

/**
 * Continuous speech session. It listens until the user stops it: a pause only closes one piece of speech,
 * which is transcribed in the background and appended, while listening goes on. Live words are shown from the
 * on-device Whisper (when available); each finished piece is transcribed by the best engine that works.
 */
class SegmentedRecognizer(
    private val vocabularyHint: String,
    private val listener: SttListener,
    private val partialEngine: SegmentTranscriber?,
    private val finalEngines: List<SegmentTranscriber>,
) : SttSession {
    private class Segment(val id: Int, val epoch: Int, val audio: FloatArray, val rms: Float)

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<Segment>(Channel.UNLIMITED)
    private val pending = AtomicInteger(0)
    private val partialBusy = AtomicBoolean(false)
    private val lock = Any()
    private val inflight = LinkedHashMap<Int, String>()
    private val disabledUntil = LongArray(finalEngines.size)
    private var currentPartial = ""
    private var committedTail = ""

    @Volatile
    private var stopRequested = false

    @Volatile
    private var cancelled = false

    @Volatile
    private var epoch = 0

    @Volatile
    private var discardRequested = false

    @Volatile
    private var failure: String? = null

    override fun start() {
        scope.launch(Dispatchers.IO) { worker() }
        scope.launch(Dispatchers.IO) { capture() }
    }

    override fun stop() {
        stopRequested = true
    }

    override fun cancel() {
        cancelled = true
        stopRequested = true
        epoch++
        queue.close()
        scope.cancel()
    }

    override fun discard() {
        epoch++
        discardRequested = true
        synchronized(lock) {
            inflight.clear()
            currentPartial = ""
        }
        post { listener.onPartial("") }
    }

    private fun post(block: () -> Unit) {
        if (!cancelled) main.post { if (!cancelled) block() }
    }

    private fun publishPartial() {
        val text = synchronized(lock) { (inflight.values + currentPartial).fold("") { acc, s -> SpeechText.join(acc, s) } }
        post { listener.onPartial(text) }
    }

    private fun context(): String = synchronized(lock) { committedTail }

    private fun prepare(raw: FloatArray): FloatArray = AudioDsp.normalize(raw.copyOf())

    // ---------- capture ----------

    private fun flatten(frames: List<FloatArray>): FloatArray {
        val total = frames.sumOf { it.size }
        val limit = MAX_SEGMENT_MS / 1000 * AudioCapture.SAMPLE_RATE + AudioCapture.SAMPLE_RATE
        val skip = maxOf(0, total - limit)
        val out = FloatArray(total - skip)
        var seen = 0
        var pos = 0
        for (f in frames) {
            for (v in f) {
                if (seen++ >= skip) out[pos++] = v
            }
        }
        return out
    }

    private suspend fun capture() {
        val mic = AudioCapture()
        if (!mic.open()) {
            mic.close()
            failure = "Could not open the microphone."
            queue.close()
            return
        }
        val vad = Vad(endSilenceMs = SEGMENT_SILENCE_MS)
        val frame = ShortArray(vad.frameSamples)
        val frameF = FloatArray(vad.frameSamples)
        val preRoll = ArrayDeque<FloatArray>()
        var utterance = ArrayList<FloatArray>()
        var inSpeech = false
        var sincePartialMs = 0
        var elapsedMs = 0
        var segId = 0

        fun submit(frames: List<FloatArray>, trimTail: Int) {
            val usable = if (trimTail > 0 && frames.size > trimTail + 10) frames.dropLast(trimTail) else frames
            if (usable.size * FRAME_MS < MIN_SEGMENT_MS) return
            val audio = flatten(usable)
            val id = ++segId
            pending.incrementAndGet()
            synchronized(lock) {
                inflight[id] = currentPartial
                currentPartial = ""
            }
            queue.trySend(Segment(id, epoch, audio, Vad.rms(audio)))
        }

        try {
            while (scope.isActive && !cancelled) {
                val n = mic.read(frame, frame.size)
                if (n <= 0) {
                    failure = "Microphone stopped responding."
                    break
                }
                if (discardRequested) {
                    discardRequested = false
                    utterance = ArrayList()
                    preRoll.clear()
                }
                AudioDsp.toFloat(frame, n, frameF)
                val ev = vad.process(frameF, n)
                post { listener.onLevel(vad.level) }
                val copy = frameF.copyOf(n)
                elapsedMs += FRAME_MS

                if (!inSpeech) {
                    preRoll.addLast(copy)
                    if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                    if (ev == VadEvent.SpeechStarted) {
                        inSpeech = true
                        utterance.addAll(preRoll)
                        preRoll.clear()
                        sincePartialMs = 0
                    }
                } else {
                    utterance.add(copy)
                    sincePartialMs += FRAME_MS
                    val engine = partialEngine
                    if (engine != null && sincePartialMs >= PARTIAL_EVERY_MS && utterance.size > 20 &&
                        pending.get() == 0 && partialBusy.compareAndSet(false, true)
                    ) {
                        sincePartialMs = 0
                        val snapshot = prepare(flatten(utterance))
                        val myEpoch = epoch
                        scope.launch(Dispatchers.Default) {
                            try {
                                val t = SpeechText.tidy(engine.transcribe(snapshot, context()))
                                if (t.isNotBlank() && myEpoch == epoch) {
                                    synchronized(lock) { currentPartial = t }
                                    publishPartial()
                                }
                            } catch (e: Exception) {
                                // live words are optional
                            } finally {
                                partialBusy.set(false)
                            }
                        }
                    }
                    if (ev == VadEvent.SpeechEnded) {
                        submit(utterance, (SEGMENT_SILENCE_MS - KEEP_TAIL_MS) / FRAME_MS)
                        utterance = ArrayList()
                        inSpeech = false
                    } else if (utterance.size * FRAME_MS >= MAX_SEGMENT_MS) {
                        // Very long sentence: close this piece, the speaker is still talking so stay in speech.
                        submit(utterance, 0)
                        utterance = ArrayList()
                    }
                }
                if (stopRequested || elapsedMs >= MAX_SESSION_MS) break
            }
        } finally {
            mic.close()
        }
        if (!cancelled && inSpeech && utterance.isNotEmpty()) submit(utterance, 0)
        queue.close()
    }

    // ---------- background transcription ----------

    private fun decode(audio: FloatArray): String {
        val pcm = prepare(audio)
        val ctx = context()
        var last: Exception? = null
        val now = System.currentTimeMillis()
        for ((i, engine) in finalEngines.withIndex()) {
            if (disabledUntil[i] > now) continue
            try {
                return engine.transcribe(pcm, ctx)
            } catch (e: Exception) {
                last = e
                val pe = e as? ProviderError
                val wait = when (pe?.failure) {
                    Failure.RateLimit -> pe.retryAfterMs ?: 60_000L
                    Failure.Auth -> 3_600_000L
                    else -> 15_000L
                }
                disabledUntil[i] = now + wait
            }
        }
        throw last ?: IOException("No speech engine is ready.")
    }

    private suspend fun worker() {
        for (seg in queue) {
            if (cancelled) break
            var text = ""
            var problem: String? = null
            try {
                text = decode(seg.audio)
            } catch (e: Exception) {
                problem = e.message ?: "Speech engine failed."
            }
            val fresh = seg.epoch == epoch
            val clean = SpeechText.tidy(text).takeIf { !SpeechText.isGhost(it, quiet = seg.rms < QUIET_RMS) }.orEmpty()
            synchronized(lock) {
                inflight.remove(seg.id)
                if (fresh && clean.isNotEmpty()) committedTail = SpeechText.tail(SpeechText.join(committedTail, clean), 200)
            }
            pending.decrementAndGet()
            if (fresh) {
                if (clean.isNotEmpty()) post { listener.onSegment(clean) }
                if (problem != null) post { listener.onNotice("One phrase could not be transcribed: $problem") }
                publishPartial()
            }
        }
        if (!cancelled) {
            val f = failure
            post { if (f != null) listener.onError(f) else listener.onEnded() }
        }
    }

    private companion object {
        const val FRAME_MS = 30
        const val SEGMENT_SILENCE_MS = 900
        const val KEEP_TAIL_MS = 240
        const val PRE_ROLL_FRAMES = 15
        const val MIN_SEGMENT_MS = 400
        const val PARTIAL_EVERY_MS = 1_200
        const val MAX_SEGMENT_MS = 25_000
        const val MAX_SESSION_MS = 15 * 60_000
        const val QUIET_RMS = 0.02f
    }
}
