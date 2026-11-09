package com.interviewcopilot.app.voice

/** Callbacks from a speech session. All are invoked on the main thread. */
interface SttListener {
    fun onLevel(level: Float)

    /** Words of the sentence being spoken right now (not final yet). */
    fun onPartial(text: String)

    /** A finished piece of speech. Pauses never end the session; more pieces keep coming. */
    fun onSegment(text: String)

    /** Something went wrong with one piece but listening continues. */
    fun onNotice(message: String)

    /** The session is over (the user stopped it). Every piece has been delivered before this. */
    fun onEnded()
    fun onError(message: String)
}

/** One continuous capture. [stop] ends capture, still delivers everything said, then calls onEnded. */
interface SttSession {
    fun start()
    fun stop()
    fun cancel()

    /** Forget everything heard but not yet delivered (the user pressed Clear) and keep listening. */
    fun discard()
}

/** "auto" lets Whisper detect the language. */
data class SttLanguage(val code: String, val label: String)

object SttLanguages {
    val all = listOf(
        SttLanguage("auto", "Auto-detect"),
        SttLanguage("en", "English"),
        SttLanguage("hi", "Hindi"),
        SttLanguage("ta", "Tamil"),
        SttLanguage("te", "Telugu"),
        SttLanguage("es", "Spanish"),
        SttLanguage("fr", "French"),
        SttLanguage("de", "German"),
        SttLanguage("pt", "Portuguese"),
        SttLanguage("it", "Italian"),
        SttLanguage("ja", "Japanese"),
        SttLanguage("ko", "Korean"),
        SttLanguage("zh", "Chinese"),
        SttLanguage("ar", "Arabic"),
    )

    fun label(code: String) = all.firstOrNull { it.code == code }?.label ?: code
}
