package com.interviewcopilot.app

import com.interviewcopilot.app.core.SpeechText
import com.interviewcopilot.app.core.Wav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    @Test
    fun joinUsesOneSpaceAndLosesNothing() {
        assertEquals("a b", SpeechText.join("a", "b"))
        assertEquals("a b", SpeechText.join(" a ", " b "))
        assertEquals("b", SpeechText.join("", "b"))
        assertEquals("a", SpeechText.join("a", "  "))
    }

    @Test
    fun tidyRemovesSoundTagsAndNotes() {
        assertEquals("", SpeechText.tidy("[BLANK_AUDIO]"))
        assertEquals("hello world", SpeechText.tidy("hello (music) world"))
        assertEquals("hello", SpeechText.tidy("\u266A hello \u266A"))
        assertEquals("one two", SpeechText.tidy("one    two"))
    }

    @Test
    fun tidyCollapsesRunawayRepeats() {
        assertEquals("you", SpeechText.tidy("you you you you you you"))
        assertEquals("I think I think so", SpeechText.tidy("I think I think so"))
    }

    @Test
    fun ghostsAreOnlyDroppedWhenTheAudioWasQuiet() {
        assertTrue(SpeechText.isGhost("Thank you.", quiet = true))
        assertFalse(SpeechText.isGhost("Thank you.", quiet = false))
        assertTrue(SpeechText.isGhost("...", quiet = false))
        assertFalse(SpeechText.isGhost("Explain polymorphism", quiet = true))
    }

    @Test
    fun tailCutsAtAWordBoundary() {
        assertEquals("short text", SpeechText.tail("short text", 50))
        assertEquals("three four", SpeechText.tail("one two three four", 12))
    }

    @Test
    fun wavHeaderAndSizeAreCorrect() {
        val bytes = Wav.encode(FloatArray(100) { 0.5f })
        assertEquals(44 + 200, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        // 0.5 -> 16383 little endian
        assertEquals(0xFF, bytes[44].toInt() and 0xFF)
        assertEquals(0x3F, bytes[45].toInt() and 0xFF)
    }
}
