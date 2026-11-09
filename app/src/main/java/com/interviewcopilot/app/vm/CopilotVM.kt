package com.interviewcopilot.app.vm

import android.app.Application
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.interviewcopilot.app.core.EduCodec
import com.interviewcopilot.app.core.EduLevel
import com.interviewcopilot.app.core.EduRecord
import com.interviewcopilot.app.core.EduSummary
import com.interviewcopilot.app.core.EngineOrder
import com.interviewcopilot.app.core.Failure
import com.interviewcopilot.app.core.MarksheetAi
import com.interviewcopilot.app.core.MarksheetParser
import com.interviewcopilot.app.core.ProviderHealth
import com.interviewcopilot.app.core.Stage
import com.interviewcopilot.app.core.ThinkFilter
import com.interviewcopilot.app.core.VoiceEvent
import com.interviewcopilot.app.core.VoiceFlow
import com.interviewcopilot.app.core.VoiceState
import com.interviewcopilot.app.data.Defaults
import com.interviewcopilot.app.data.JobProfile
import com.interviewcopilot.app.data.QA
import com.interviewcopilot.app.data.Store
import com.interviewcopilot.app.edu.MarksheetReader
import com.interviewcopilot.app.llm.DeviceProbe
import com.interviewcopilot.app.llm.DownloadState
import com.interviewcopilot.app.llm.EngineState
import com.interviewcopilot.app.llm.LlamaEngine
import com.interviewcopilot.app.llm.LocalModel
import com.interviewcopilot.app.llm.ModelAssessment
import com.interviewcopilot.app.llm.ModelCatalog
import com.interviewcopilot.app.llm.ModelDownloader
import com.interviewcopilot.app.llm.ModelStore
import com.interviewcopilot.app.llm.Recommender
import com.interviewcopilot.app.llm.SpeechModel
import com.interviewcopilot.app.logic.GeminiProvider
import com.interviewcopilot.app.logic.GroqProvider
import com.interviewcopilot.app.logic.Http
import com.interviewcopilot.app.logic.LlmProvider
import com.interviewcopilot.app.logic.LocalProvider
import com.interviewcopilot.app.logic.OpenRouterProvider
import com.interviewcopilot.app.logic.Prompts
import com.interviewcopilot.app.logic.ProviderError
import com.interviewcopilot.app.logic.Vocab
import com.interviewcopilot.app.voice.GroqTranscriber
import com.interviewcopilot.app.voice.LocalWhisperTranscriber
import com.interviewcopilot.app.voice.NativeWhisper
import com.interviewcopilot.app.voice.PlatformRecognizer
import com.interviewcopilot.app.voice.SegmentTranscriber
import com.interviewcopilot.app.voice.SegmentedRecognizer
import com.interviewcopilot.app.voice.SttListener
import com.interviewcopilot.app.voice.SttSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Calendar
import java.util.Locale
import java.util.UUID

class CopilotVM(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val store = Store(ctx)

    // ---------- settings ----------
    val prefs = mutableStateMapOf<String, String>().apply { putAll(store.all()) }
    fun g(key: String): String = prefs[key] ?: Defaults.map[key] ?: ""
    fun b(key: String): Boolean = g(key) == "true"
    fun put(key: String, value: String) {
        prefs[key] = value
        store.put(key, value)
    }

    // ---------- models, device, downloads ----------
    val modelStore = ModelStore(ctx)
    val downloader = ModelDownloader()
    val engine = LlamaEngine(ctx)
    val device = DeviceProbe.read(ctx)

    var assessments by mutableStateOf<List<ModelAssessment>>(emptyList())
        private set
    var downloadStates by mutableStateOf<Map<String, DownloadState>>(emptyMap())
        private set
    var engineState by mutableStateOf<EngineState>(EngineState.Unloaded)
        private set
    private var downloadedTick by mutableStateOf(0)

    // ---------- voice flow ----------
    var voice by mutableStateOf(VoiceState())
        private set
    var level by mutableFloatStateOf(0f)
        private set
    var thinking by mutableStateOf(false)
        private set
    var engineUsed by mutableStateOf("")
        private set
    var firstTokenMs by mutableStateOf(0L)
        private set
    var mode by mutableStateOf("Interview")
    var finishing by mutableStateOf(false)
        private set
    var speechNotice by mutableStateOf<String?>(null)
        private set

    // ---------- answer engines ----------
    private val health = ProviderHealth()
    var engineTick by mutableIntStateOf(0)
        private set
    var engineNotice by mutableStateOf("")
        private set
    val testResults = mutableStateMapOf<String, String>()
    val testing = mutableStateMapOf<String, Boolean>()
    private var activeProvider: LlmProvider? = null

    // ---------- personal details ----------
    val eduBusy = mutableStateMapOf<String, Boolean>()
    val eduMessage = mutableStateMapOf<String, String>()

    val session = mutableStateListOf<QA>()
    private var sessionId = UUID.randomUUID().toString()
    val history = mutableStateListOf<QA>().apply { addAll(store.loadHistory()) }
    val jobs = mutableStateListOf<JobProfile>().apply { addAll(store.loadJobs()) }

    // ---------- practice / jobs ----------
    var practiceOut by mutableStateOf("")
    var practiceBusy by mutableStateOf(false)
    var jobBusyId by mutableStateOf("")
    var error by mutableStateOf<String?>(null)

    private var genJob: Job? = null
    private var stt: SttSession? = null
    private var tts: TextToSpeech? = null
    private var loadedWhisper: String? = null
    private var endSignal: CompletableDeferred<Unit>? = null

    init {
        mode = g("mode")
        refreshAssessments()
        ModelCatalog.models.forEach { downloader.restore(modelStore.spec(it)) }
        ModelCatalog.speech.forEach { downloader.restore(modelStore.spec(it)) }
        downloader.states.onEach { st ->
            val finished = st.any { (k, v) -> v is DownloadState.Done && downloadStates[k] !is DownloadState.Done }
            downloadStates = st
            if (finished) {
                downloadedTick++
                onDownloadFinished()
            }
        }.launchIn(viewModelScope)
        engine.state.onEach { engineState = it }.launchIn(viewModelScope)
        applyLowEndFallback()
        warmUpSelected()
    }

    // ---------- derived ----------
    fun isDownloaded(m: LocalModel): Boolean {
        @Suppress("UNUSED_EXPRESSION")
        downloadedTick
        return modelStore.isDownloaded(m)
    }

    fun isDownloaded(m: SpeechModel): Boolean {
        @Suppress("UNUSED_EXPRESSION")
        downloadedTick
        return modelStore.isDownloaded(m)
    }

    val selectedModel: LocalModel? get() = ModelCatalog.find(g("localModelId"))
    val selectedSpeech: SpeechModel? get() = ModelCatalog.findSpeech(g("whisperModelId"))
    val hasLocalModel: Boolean get() = selectedModel?.let { isDownloaded(it) } == true
    val recommendedModel: LocalModel? get() = assessments.firstOrNull { it.recommended }?.model

    fun stateOf(id: String): DownloadState = downloadStates[id] ?: DownloadState.Idle

    fun refreshAssessments() {
        assessments = Recommender.assess(device).map { Recommender.withStorage(it, device.freeStorageMb) }
    }

    private fun applyLowEndFallback() {
        val downloaded = ModelCatalog.models.filter { modelStore.isDownloaded(it) }.map { it.id }.toSet()
        val better = Recommender.fallbackFor(g("localModelId").ifBlank { null }, downloaded, device)
        if (better != null) put("localModelId", better.id)
    }

    private fun warmUpSelected() {
        val m = selectedModel ?: return
        val wantLocal = g("provider") == "Local" || (g("provider") == "Auto" && !hasOnlineKey())
        if (modelStore.isDownloaded(m) && wantLocal) engine.warmUp(m, modelStore.file(m))
        val sp = selectedSpeech ?: return
        if (modelStore.isDownloaded(sp)) viewModelScope.launch(Dispatchers.IO) { ensureWhisper(sp) }
    }

    private fun onDownloadFinished() {
        if (selectedModel == null) {
            // First model ever downloaded: select it automatically.
            ModelCatalog.models.firstOrNull { modelStore.isDownloaded(it) }?.let { selectModel(it, switchEngine = false) }
        }
        val sp = selectedSpeech
        if (sp != null && modelStore.isDownloaded(sp) && loadedWhisper != sp.id) {
            viewModelScope.launch(Dispatchers.IO) { ensureWhisper(sp) }
        }
    }

    // ---------- model actions ----------
    fun download(m: LocalModel) = downloader.start(modelStore.spec(m))
    fun download(m: SpeechModel) = downloader.start(modelStore.spec(m))
    fun pause(id: String) = downloader.pause(id)

    fun selectModel(m: LocalModel, switchEngine: Boolean = true) {
        if (!modelStore.isDownloaded(m)) return
        put("localModelId", m.id)
        if (switchEngine) put("provider", EngineOrder.LOCAL)
        engine.warmUp(m, modelStore.file(m))
    }

    fun selectSpeech(m: SpeechModel) {
        put("whisperModelId", m.id)
        if (modelStore.isDownloaded(m)) viewModelScope.launch(Dispatchers.IO) { ensureWhisper(m) }
    }

    fun delete(m: LocalModel) {
        if (g("localModelId") == m.id) {
            engine.unload()
            put("localModelId", "")
        }
        downloader.cancelAndDelete(modelStore.spec(m))
        downloadedTick++
    }

    fun delete(m: SpeechModel) {
        if (loadedWhisper == m.id) {
            NativeWhisper.nativeFree()
            loadedWhisper = null
        }
        downloader.cancelAndDelete(modelStore.spec(m))
        downloadedTick++
    }

    private fun ensureWhisper(m: SpeechModel): Boolean {
        if (loadedWhisper == m.id && NativeWhisper.nativeIsLoaded()) return true
        return try {
            NativeWhisper.ensureBackends(ctx.applicationInfo.nativeLibraryDir)
            val ok = NativeWhisper.nativeLoad(modelStore.file(m).absolutePath)
            loadedWhisper = if (ok) m.id else null
            ok
        } catch (e: LinkageError) {
            false
        }
    }

    // ---------- voice: step 1 and 2, speak and edit ----------
    fun updateMode(m: String) {
        mode = m
        put("mode", m)
    }

    private fun dispatch(e: VoiceEvent) {
        voice = VoiceFlow.reduce(voice, e)
    }

    /** Mic button: start listening, or finish the current capture. */
    fun micTapped() {
        when (voice.stage) {
            Stage.Listening -> stt?.stop() ?: dispatch(VoiceEvent.SpeechEnded)
            Stage.Generating -> Unit
            else -> startListening()
        }
    }

    private fun transcribers(): Pair<SegmentTranscriber?, List<SegmentTranscriber>>? {
        val lang = g("sttLang")
        val base = "Interview conversation. Clear, correctly punctuated English sentences."
        val terms = g("vocab").lines().mapNotNull { l -> l.substringAfter('=', "").trim().ifEmpty { null } }.distinct().take(30).joinToString(", ")
        val prompt = if (terms.isEmpty()) base else "$base Terms: $terms."
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        val whisper = selectedSpeech?.takeIf { modelStore.isDownloaded(it) }
        val groqKey = g("groqKey").trim()
        val localPartial = whisper?.let { LocalWhisperTranscriber(lang, prompt, threads, accurate = false) }
        val localFinal = whisper?.let { LocalWhisperTranscriber(lang, prompt, threads, accurate = true) }
        val cloud = if (groqKey.isNotEmpty()) GroqTranscriber(groqKey, lang, prompt) else null
        return when (g("sttEngine")) {
            "Platform" -> {
                null
            }

            "Whisper" -> {
                localFinal?.let { localPartial to listOf<SegmentTranscriber>(it) }
            }

            "Groq" -> {
                cloud?.let { localPartial to listOf<SegmentTranscriber>(it) + listOfNotNull(localFinal) }
            }

            else -> {
                val finals = listOfNotNull(cloud, localFinal)
                if (finals.isEmpty()) null else localPartial to finals
            }
        }
    }

    private fun startListening() {
        speechNotice = null
        dispatch(VoiceEvent.MicTapped)
        if (voice.stage != Stage.Listening) return
        warmConnections()
        val listener = object : SttListener {
            override fun onLevel(level: Float) {
                this@CopilotVM.level = level
            }

            override fun onPartial(text: String) = dispatch(VoiceEvent.Partial(text))

            override fun onSegment(text: String) {
                speechNotice = null
                dispatch(VoiceEvent.SegmentFinal(Vocab.apply(text, g("vocab"))))
            }

            override fun onNotice(message: String) {
                speechNotice = message
            }

            override fun onEnded() {
                level = 0f
                stt = null
                dispatch(VoiceEvent.SpeechEnded)
                endSignal?.complete(Unit)
            }

            override fun onError(message: String) {
                level = 0f
                stt = null
                dispatch(VoiceEvent.SpeechFailed(message))
                endSignal?.complete(Unit)
            }
        }
        val engineMode = g("sttEngine")
        val chosen = transcribers()
        if (chosen == null && engineMode != "Platform" && engineMode != "Auto") {
            val why = when (engineMode) {
                "Groq" -> "Add a Groq key in the AI tab first, or switch the speech engine in Settings."
                else -> "Download a Whisper model in Settings > Speech first, or switch to the phone's recognizer."
            }
            dispatch(VoiceEvent.SpeechFailed(why))
            return
        }
        if (chosen != null) {
            val whisper = selectedSpeech?.takeIf { modelStore.isDownloaded(it) }
            viewModelScope.launch {
                if (whisper != null) {
                    val ok = withContext(Dispatchers.IO) { ensureWhisper(whisper) }
                    if (!ok && chosen.second.all { it is LocalWhisperTranscriber }) {
                        dispatch(VoiceEvent.SpeechFailed("The Whisper model could not be loaded."))
                        return@launch
                    }
                }
                if (voice.stage != Stage.Listening) return@launch
                val partial = if (whisper != null && loadedWhisper == whisper.id) chosen.first else null
                val finals = if (whisper != null && loadedWhisper == whisper.id) chosen.second else chosen.second.filter { it !is LocalWhisperTranscriber }
                if (finals.isEmpty()) {
                    dispatch(VoiceEvent.SpeechFailed("No speech engine is ready."))
                    return@launch
                }
                val s = SegmentedRecognizer(g("vocab"), listener, partial, finals)
                stt = s
                s.start()
            }
        } else {
            val s = PlatformRecognizer(ctx, g("sttLang"), listener)
            stt = s
            s.start()
        }
    }

    fun micDenied() = dispatch(VoiceEvent.SpeechFailed("Microphone permission is needed to hear you."))

    fun editText(text: String) = dispatch(VoiceEvent.TextEdited(text))

    /** Clear button: empties the text box. If the mic is listening it keeps listening. */
    fun clearText() {
        stt?.discard()
        speechNotice = null
        dispatch(VoiceEvent.ClearTapped)
    }

    // ---------- voice: step 4 to 6, generate ----------
    private fun activeJob(): String = jobs.firstOrNull { it.active }?.let { it.title + "\n" + it.description } ?: ""

    /** The ONLY way an answer is started: the user taps Generate Answer. Works while still listening. */
    fun generateTapped(extra: String = "") {
        if (voice.stage == Stage.Listening) {
            if (!voice.canGenerate || finishing) return
            val session = stt
            if (session == null) {
                dispatch(VoiceEvent.SpeechEnded)
                startGeneration(extra)
                return
            }
            finishing = true
            val signal = CompletableDeferred<Unit>()
            endSignal = signal
            session.stop()
            viewModelScope.launch {
                val done = withTimeoutOrNull(10_000) { signal.await() }
                if (done == null && voice.stage == Stage.Listening) {
                    stt?.cancel()
                    stt = null
                    dispatch(VoiceEvent.SpeechEnded)
                }
                endSignal = null
                finishing = false
                startGeneration(extra)
            }
            return
        }
        startGeneration(extra)
    }

    private fun profileText(): String {
        val records = EduLevel.entries.associateWith { edu(it) }
        val parts = listOfNotNull(
            g("name").trim().takeIf { it.isNotEmpty() }?.let { "Name: $it" },
            g("about").trim().takeIf { it.isNotEmpty() },
            EduSummary.profile(records).takeIf { it.isNotEmpty() }?.let { "Education: $it" },
        )
        return parts.joinToString("\n")
    }

    private fun startGeneration(extra: String) {
        if (!voice.canGenerate) return
        dispatch(VoiceEvent.GenerateTapped)
        val question = voice.question
        val startedAt = System.currentTimeMillis()
        val currentMode = mode
        thinking = false
        firstTokenMs = 0
        engineNotice = ""
        genJob?.cancel()
        genJob = viewModelScope.launch {
            val filter = ThinkFilter()
            try {
                val memory = if (b("memory")) session.map { it.question to it.answer } else emptyList()
                val (sys, user, max) = Prompts.build(
                    currentMode,
                    g("style"),
                    g("speed"),
                    g("resume"),
                    activeJob(),
                    g("vocab"),
                    profileText(),
                    memory,
                    question,
                    extra,
                )
                var gotFirst = false
                runProviders(
                    sys,
                    user,
                    max,
                    onSwitch = {
                        filter.reset()
                        gotFirst = false
                        dispatch(VoiceEvent.Token(""))
                    },
                ) { tok ->
                    val shown = filter.push(tok)
                    thinking = filter.thinking
                    if (shown.isNotEmpty() && !gotFirst) {
                        gotFirst = true
                        firstTokenMs = System.currentTimeMillis() - startedAt
                    }
                    dispatch(VoiceEvent.Token(shown))
                }
                val clean = filter.visible.trim()
                if (clean.isBlank()) throw IOException("The model returned an empty answer. Try again.")
                val qa = QA(UUID.randomUUID().toString(), sessionId, question, clean, System.currentTimeMillis(), currentMode)
                session.add(qa)
                history.add(qa)
                store.saveHistory(history.toList())
                thinking = false
                dispatch(VoiceEvent.GenerationFinished)
                dispatch(VoiceEvent.ResetDone) // mic and text box are ready for the next question
            } catch (e: CancellationException) {
                thinking = false
                throw e
            } catch (e: Exception) {
                thinking = false
                dispatch(VoiceEvent.GenerationFailed(e.message ?: "Could not get an answer."))
            }
        }
    }

    fun stopGeneration() {
        genJob?.cancel()
        activeProvider?.abort()
        engine.cancel()
        thinking = false
        dispatch(VoiceEvent.StopTapped)
    }

    /** Quick actions on the last answer ("Explain more" ...). Still requires a tap, never automatic. */
    fun refine(instruction: String) {
        val q = voice.question
        if (q.isBlank() || voice.stage != Stage.Idle) return
        val prev = voice.answer
        val extra = if (prev.isNotBlank()) "$instruction\n\nPrevious answer for reference:\n${prev.take(700)}" else instruction
        dispatch(VoiceEvent.TextEdited(q))
        generateTapped(extra)
    }

    fun newSession() {
        stt?.cancel()
        stt = null
        finishing = false
        genJob?.cancel()
        activeProvider?.abort()
        engine.cancel()
        voice = VoiceState()
        session.clear()
        sessionId = UUID.randomUUID().toString()
    }

    fun dismissError() {
        voice = voice.copy(error = null)
    }

    // ---------- providers ----------
    private fun hasOnlineKey(): Boolean =
        g("groqKey").isNotBlank() || g("geminiKey").isNotBlank() || g("orKey").isNotBlank()

    val selectedEngine: String get() = EngineOrder.normalize(g("provider"))

    fun engineReady(name: String): Boolean = when (name) {
        EngineOrder.LOCAL -> hasLocalModel
        EngineOrder.OPENROUTER -> g("orKey").isNotBlank()
        EngineOrder.GEMINI -> g("geminiKey").isNotBlank()
        EngineOrder.GROQ -> g("groqKey").isNotBlank()
        else -> hasLocalModel || hasOnlineKey()
    }

    /** One short line for the engine picker. */
    fun engineStatus(name: String): String {
        @Suppress("UNUSED_EXPRESSION")
        engineTick
        if (name == EngineOrder.AUTO) return "Fastest first, switches by itself, local model last"
        if (!engineReady(name)) return if (name == EngineOrder.LOCAL) "No model downloaded" else "Add an API key"
        val cool = health.coolingMs(name)
        if (cool > 0) {
            val what = when (health.failureOf(name)) {
                Failure.RateLimit -> "Limit reached"
                Failure.Auth -> "Key or model rejected"
                else -> "Not responding"
            }
            return "$what · retry in ${(cool + 999) / 1000} s"
        }
        return if (name == EngineOrder.LOCAL) "Ready · ${selectedModel?.name.orEmpty()}" else "Ready"
    }

    fun selectEngine(name: String) {
        put("provider", EngineOrder.normalize(name))
        if (name == EngineOrder.LOCAL || (name == EngineOrder.AUTO && !hasOnlineKey())) warmUpSelected()
    }

    private fun allProviders(): Map<String, LlmProvider> {
        val all = LinkedHashMap<String, LlmProvider>()
        selectedModel?.let { m ->
            if (modelStore.isDownloaded(m)) all[EngineOrder.LOCAL] = LocalProvider(engine, m, modelStore.file(m))
        }
        val groq = g("groqKey").trim()
        val gem = g("geminiKey").trim()
        val or = g("orKey").trim()
        if (groq.isNotEmpty()) all[EngineOrder.GROQ] = GroqProvider(groq, g("groqModel").trim())
        if (gem.isNotEmpty()) all[EngineOrder.GEMINI] = GeminiProvider(gem, g("geminiModel").trim())
        if (or.isNotEmpty()) all[EngineOrder.OPENROUTER] = OpenRouterProvider(or, g("orModel").trim())
        return all
    }

    private fun chain(): List<LlmProvider> {
        val all = allProviders()
        return EngineOrder.chain(selectedEngine, all.keys, health).mapNotNull { all[it] }
    }

    /** Opens connections to the online engines before the answer is requested. */
    fun warmConnections() {
        val urls = ArrayList<String>()
        if (g("groqKey").isNotBlank()) urls.add("https://api.groq.com/")
        if (g("geminiKey").isNotBlank()) urls.add("https://generativelanguage.googleapis.com/")
        if (g("orKey").isNotBlank()) urls.add("https://openrouter.ai/")
        if (urls.isNotEmpty()) Http.warm(urls)
    }

    private fun failureOf(e: Exception): Pair<Failure, Long?> = when (e) {
        is ProviderError -> e.failure to e.retryAfterMs
        is SocketTimeoutException -> Failure.Timeout to null
        is java.net.UnknownHostException, is java.net.ConnectException -> Failure.Network to null
        else -> (if (e.message.orEmpty().contains("in time")) Failure.Timeout else Failure.Other) to null
    }

    private suspend fun runProviders(sys: String, user: String, max: Int, onSwitch: () -> Unit = {}, onToken: (String) -> Unit) {
        val list = chain()
        if (list.isEmpty()) {
            throw IOException(
                if (selectedEngine == EngineOrder.AUTO || selectedEngine == EngineOrder.LOCAL) {
                    "No answer engine is ready. Add an API key in the AI tab, or download a model in Settings > Local Models."
                } else {
                    "${EngineOrder.display(selectedEngine)} has no key yet. Add it in the AI tab or switch to Auto."
                },
            )
        }
        val failed = ArrayList<String>()
        var last: Exception? = null
        for (p in list) {
            try {
                engineUsed = p.name
                activeProvider = p
                attempt(p, sys, user, max, onToken)
                health.markOk(p.name)
                engineTick++
                engineNotice = if (failed.isEmpty()) "" else "${failed.joinToString(", ")} unavailable, answered by ${EngineOrder.display(p.name)}"
                return
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) throw e
                p.abort()
                val (kind, retry) = failureOf(e)
                health.markFailed(p.name, kind, retry)
                engineTick++
                failed.add(EngineOrder.display(p.name))
                last = e
                onSwitch()
            }
        }
        val reason = last?.message ?: "Could not get an answer."
        throw IOException(if (list.size > 1) "Every engine failed (${failed.joinToString(", ")}). Last: $reason" else reason)
    }

    private suspend fun attempt(p: LlmProvider, sys: String, user: String, max: Int, onToken: (String) -> Unit) {
        var got = false
        coroutineScope {
            val job = launch {
                p.stream(sys, user, max).collect {
                    got = true
                    onToken(it)
                }
            }
            val watchdog = launch {
                delay(p.firstTokenTimeoutMs)
                if (!got) {
                    p.abort()
                    job.cancel(CancellationException("slow"))
                }
            }
            job.join()
            watchdog.cancel()
        }
        if (!got) throw IOException("${p.name} did not respond in time.")
    }

    /** Tiny request that shows whether an engine works with the saved key and model, and how fast. */
    fun testEngine(name: String) {
        val p = allProviders()[name] ?: run {
            testResults[name] = if (name == EngineOrder.LOCAL) "No model downloaded." else "Add an API key first."
            return
        }
        if (testing[name] == true) return
        testing[name] = true
        testResults[name] = "Testing…"
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            try {
                val text = withTimeoutOrNull(if (name == EngineOrder.LOCAL) 120_000L else 8_000L) {
                    val sb = StringBuilder()
                    p.stream("Reply with exactly: OK", "Say OK.", 8).collect { sb.append(it) }
                    sb.toString()
                }
                if (text == null) {
                    p.abort()
                    testResults[name] = "No answer within 8 s."
                } else {
                    health.markOk(name)
                    testResults[name] = "Works · ${"%.1f".format((System.currentTimeMillis() - started) / 1000.0)} s"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val (kind, retry) = failureOf(e)
                health.markFailed(name, kind, retry)
                testResults[name] = e.message ?: "Failed."
            } finally {
                testing[name] = false
                engineTick++
            }
        }
    }

    /** One quick answer from the online engines only (used to double-check marksheets). Null when none is available. */
    private suspend fun askOnline(sys: String, user: String, max: Int): String? {
        val all = allProviders().filterKeys { it != EngineOrder.LOCAL }
        for (name in EngineOrder.chain(EngineOrder.AUTO, all.keys, health)) {
            val p = all.getValue(name)
            try {
                val text = withTimeoutOrNull(12_000) {
                    val sb = StringBuilder()
                    p.stream(sys, user, max).collect { sb.append(it) }
                    sb.toString()
                }
                if (text != null && text.isNotBlank()) {
                    health.markOk(name)
                    return text
                }
                p.abort()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val (kind, retry) = failureOf(e)
                health.markFailed(name, kind, retry)
            }
        }
        return null
    }

    private fun runPlain(sys: String, user: String, max: Int, onText: (String) -> Unit, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                var acc = ""
                val filter = ThinkFilter()
                runProviders(sys, user, max) { tok ->
                    acc += tok
                    onText(filter.apply { reset() }.push(acc))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
                onText("Error: ${e.message}")
            } finally {
                onDone()
            }
        }
    }

    // ---------- practice ----------
    fun scorePractice(question: String, answer: String) {
        if (answer.isBlank() || practiceBusy) return
        practiceBusy = true
        practiceOut = ""
        runPlain(
            Prompts.SCORE,
            "Interview question: $question\n\nCandidate answer: $answer",
            600,
            onText = { practiceOut = it },
            onDone = {
                practiceBusy = false
                val m = Regex("Score:\\s*(\\d+(?:\\.\\d+)?)\\s*/\\s*10").find(practiceOut)
                if (m != null) {
                    val old = g("pscores")
                    put("pscores", if (old.isBlank()) m.groupValues[1] else "$old,${m.groupValues[1]}")
                }
            },
        )
    }

    fun practiceScores(): List<Double> = g("pscores").split(",").mapNotNull { it.trim().toDoubleOrNull() }

    fun speak(text: String) {
        val existing = tts
        if (existing != null) {
            existing.speak(text, TextToSpeech.QUEUE_FLUSH, null, "q")
        } else {
            tts = TextToSpeech(ctx) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "q")
                }
            }
        }
    }

    // ---------- jobs ----------
    fun addJob(title: String, description: String) {
        if (title.isBlank() || description.isBlank()) return
        jobs.add(0, JobProfile(UUID.randomUUID().toString(), title.trim(), description.trim()))
        store.saveJobs(jobs.toList())
    }

    fun setActiveJob(id: String) {
        val updated = jobs.map { it.copy(active = it.id == id && !it.active) }
        jobs.clear()
        jobs.addAll(updated)
        store.saveJobs(jobs.toList())
    }

    fun deleteJob(id: String) {
        jobs.removeAll { it.id == id }
        store.saveJobs(jobs.toList())
    }

    fun analyzeJob(id: String) {
        val job = jobs.firstOrNull { it.id == id } ?: return
        if (jobBusyId.isNotEmpty()) return
        jobBusyId = id
        runPlain(
            Prompts.JOB_ANALYSIS,
            "Job title: ${job.title}\n\n${job.description.take(2500)}",
            600,
            onText = { text ->
                val idx = jobs.indexOfFirst { it.id == id }
                if (idx >= 0) jobs[idx] = jobs[idx].copy(keywords = text)
            },
            onDone = {
                jobBusyId = ""
                store.saveJobs(jobs.toList())
            },
        )
    }

    // ---------- personal details ----------
    fun edu(level: EduLevel): EduRecord? = EduCodec.decode(g("edu_${level.key}"))

    fun eduRaw(level: EduLevel): String = g("eduraw_${level.key}")

    fun saveEdu(level: EduLevel, record: EduRecord) = put("edu_${level.key}", EduCodec.encode(record))

    fun clearEdu(level: EduLevel) {
        put("edu_${level.key}", "")
        put("eduraw_${level.key}", "")
        eduMessage.remove(level.key)
    }

    /** Reads the marksheet(s), finds the marks, calculates the percentage or CGPA and saves it. */
    fun importMarksheet(level: EduLevel, uris: List<Uri>) {
        if (uris.isEmpty() || eduBusy[level.key] == true) return
        eduBusy[level.key] = true
        eduMessage[level.key] = "Reading the marksheet…"
        viewModelScope.launch {
            try {
                val rows = withContext(Dispatchers.Default) { MarksheetReader(ctx).read(uris) }
                if (rows.isEmpty()) {
                    eduMessage[level.key] = "No text found. Use a clear, well-lit photo or the original PDF, or enter the marks below."
                    return@launch
                }
                put("eduraw_${level.key}", rows.joinToString("\n").take(6000))
                val year = Calendar.getInstance().get(Calendar.YEAR)
                var record = MarksheetParser.parse(level, rows, year + 1)
                if (b("eduAi")) {
                    eduMessage[level.key] = "Double-checking the marks…"
                    val reply = askOnline(MarksheetAi.SYSTEM, MarksheetAi.userPrompt(level, rows), 500)
                    if (reply != null) record = MarksheetAi.merge(record, MarksheetAi.parseReply(reply))
                }
                saveEdu(level, record)
                eduMessage[level.key] = if (record.hasData) {
                    "Done. Check the numbers and correct anything that is wrong."
                } else {
                    "I could not find the marks on this sheet. Enter them below (the scanned text is shown for reference)."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                eduMessage[level.key] = "Could not read the file: ${e.message ?: "unknown error"}"
            } finally {
                eduBusy[level.key] = false
            }
        }
    }

    // ---------- history ----------
    fun toggleFavorite(id: String) {
        val idx = history.indexOfFirst { it.id == id }
        if (idx >= 0) {
            history[idx] = history[idx].copy(favorite = !history[idx].favorite)
            store.saveHistory(history.toList())
        }
    }

    fun deleteQA(id: String) {
        history.removeAll { it.id == id }
        store.saveHistory(history.toList())
    }

    fun wipeAll() {
        newSession()
        health.reset()
        engine.unload()
        NativeWhisperSafe.free()
        loadedWhisper = null
        ModelCatalog.models.forEach { downloader.cancelAndDelete(modelStore.spec(it)) }
        ModelCatalog.speech.forEach { downloader.cancelAndDelete(modelStore.spec(it)) }
        modelStore.wipe()
        store.wipe()
        prefs.clear()
        history.clear()
        jobs.clear()
        downloadedTick++
    }

    override fun onCleared() {
        stt?.cancel()
        activeProvider?.abort()
        tts?.shutdown()
        super.onCleared()
    }
}

/** Frees Whisper without crashing on devices where the native library failed to load. */
private object NativeWhisperSafe {
    fun free() {
        try {
            NativeWhisper.nativeFree()
        } catch (e: LinkageError) {
            // nothing was loaded
        }
    }
}
