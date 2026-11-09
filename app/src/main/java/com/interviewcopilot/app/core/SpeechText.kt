package com.interviewcopilot.app.core

/** Pure helpers that clean and join the text pieces coming out of speech recognition. */
object SpeechText {
    /** Joins two pieces of speech with exactly one space and no loss of words. */
    fun join(a: String, b: String): String {
        val x = a.trim()
        val y = b.trim()
        return when {
            x.isEmpty() -> y
            y.isEmpty() -> x
            else -> "$x $y"
        }
    }

    private val soundTag = Regex("""\[[^\]]{0,40}]|\((?:music|applause|laughter|laughs|silence|noise|inaudible|coughs?)[^)]{0,30}\)""", RegexOption.IGNORE_CASE)
    private val noteSymbols = Regex("[\u266A\u266B\u2669]+")
    private val spaces = Regex("""\s+""")
    private val loop = Regex("""\b([\p{L}\p{N}']+)(?:[\s,.]+\1\b){3,}""", RegexOption.IGNORE_CASE)

    /** Removes sound tags such as [BLANK_AUDIO], music notes and runaway word loops, then normalises spaces. */
    fun tidy(raw: String): String {
        var s = raw.replace(soundTag, " ").replace(noteSymbols, " ")
        s = loop.replace(s) { it.groupValues[1] }
        return s.replace(spaces, " ").trim()
    }

    private val ghosts = setOf(
        "thank you",
        "thanks",
        "thanks for watching",
        "thank you for watching",
        "thank you so much for watching",
        "you",
        "bye",
        "bye bye",
        "please subscribe",
        "like and subscribe",
        "subscribe",
        "amara org",
        "subtitles by the amara org community",
    )

    /**
     * Whisper invents short phrases such as "Thank you." on silence. A phrase from this list is only
     * treated as an invention when the audio it came from was very quiet ([quiet] = true).
     */
    fun isGhost(text: String, quiet: Boolean): Boolean {
        val bare = text.lowercase().replace(Regex("""[^\p{L}\p{N}\s]"""), " ").replace(spaces, " ").trim()
        if (bare.isEmpty()) return true
        return quiet && bare in ghosts
    }

    /** The last [maxChars] characters, cut at a word boundary. Used as context for the next piece. */
    fun tail(text: String, maxChars: Int): String {
        val t = text.trim()
        if (t.length <= maxChars) return t
        val cut = t.substring(t.length - maxChars)
        val space = cut.indexOf(' ')
        return if (space in 0 until cut.length - 1) cut.substring(space + 1) else cut
    }
}
