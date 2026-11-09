package com.interviewcopilot.app

import com.interviewcopilot.app.core.Stage
import com.interviewcopilot.app.core.VoiceEvent
import com.interviewcopilot.app.core.VoiceFlow
import com.interviewcopilot.app.core.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFlowTest {
    private fun run(vararg events: VoiceEvent, from: VoiceState = VoiceState()): VoiceState =
        events.fold(from) { s, e -> VoiceFlow.reduce(s, e) }

    @Test
    fun speakingNeverStartsGeneration() {
        val s = run(VoiceEvent.MicTapped, VoiceEvent.Partial("hello"), VoiceEvent.SegmentFinal("Hello there."), VoiceEvent.SpeechEnded)
        assertEquals(Stage.Transcribed, s.stage)
        assertEquals("Hello there.", s.text)
        assertEquals("", s.answer)
        assertTrue(s.canGenerate)
    }

    @Test
    fun pausesNeverStopListeningAndEveryPieceIsKept() {
        val s = run(
            VoiceEvent.MicTapped,
            VoiceEvent.SegmentFinal("Tell me about yourself"),
            VoiceEvent.Partial("and your"),
            VoiceEvent.SegmentFinal("and your last project."),
            VoiceEvent.Partial("also"),
        )
        assertEquals(Stage.Listening, s.stage)
        assertEquals("Tell me about yourself and your last project.", s.text)
        assertEquals("Tell me about yourself and your last project. also", s.liveText)
    }

    @Test
    fun partialIsReplacedWhenItsSegmentIsFinal() {
        val s = run(VoiceEvent.MicTapped, VoiceEvent.Partial("what is"), VoiceEvent.SegmentFinal("What is a thread?"))
        assertEquals("", s.partial)
        assertEquals("What is a thread?", s.liveText)
    }

    @Test
    fun fullHappyPathEndsIdleWithAnswerKept() {
        val s = run(
            VoiceEvent.MicTapped,
            VoiceEvent.SegmentFinal("What is a thread?"),
            VoiceEvent.SpeechEnded,
            VoiceEvent.GenerateTapped,
            VoiceEvent.Token("A thread"),
            VoiceEvent.Token("A thread is a path of execution."),
            VoiceEvent.GenerationFinished,
            VoiceEvent.ResetDone,
        )
        assertEquals(Stage.Idle, s.stage)
        assertEquals("", s.text)
        assertEquals("What is a thread?", s.question)
        assertEquals("A thread is a path of execution.", s.answer)
    }

    @Test
    fun generateIsAllowedWhileStillListeningAndUsesAllWords() {
        val listening = run(VoiceEvent.MicTapped, VoiceEvent.SegmentFinal("Explain REST"), VoiceEvent.Partial("and its verbs"))
        assertTrue(listening.canGenerate)
        val s = VoiceFlow.reduce(listening, VoiceEvent.GenerateTapped)
        assertEquals(Stage.Generating, s.stage)
        assertEquals("Explain REST and its verbs", s.question)
    }

    @Test
    fun generateDisabledWhenEmptyOrBusy() {
        assertFalse(VoiceState().canGenerate)
        assertFalse(VoiceState(stage = Stage.Transcribed, text = "   ").canGenerate)
        assertFalse(VoiceState(stage = Stage.Generating, text = "hi").canGenerate)
        assertFalse(VoiceState(stage = Stage.Listening).canGenerate)
        assertTrue(VoiceState(stage = Stage.Listening, partial = "hi").canGenerate)
        assertTrue(VoiceState(stage = Stage.Transcribed, text = "hi").canGenerate)
    }

    @Test
    fun generateTapIgnoredWhenDisabled() {
        val s = run(VoiceEvent.GenerateTapped)
        assertEquals(Stage.Idle, s.stage)
    }

    @Test
    fun clearEmptiesTheTextAndKeepsListening() {
        val s = run(VoiceEvent.MicTapped, VoiceEvent.SegmentFinal("old words"), VoiceEvent.Partial("more"), VoiceEvent.ClearTapped)
        assertEquals(Stage.Listening, s.stage)
        assertEquals("", s.liveText)
        assertFalse(s.canClear)
    }

    @Test
    fun clearWhenNotListeningReturnsToIdle() {
        val s = run(VoiceEvent.MicTapped, VoiceEvent.SegmentFinal("old words"), VoiceEvent.SpeechEnded, VoiceEvent.ClearTapped)
        assertEquals(Stage.Idle, s.stage)
        assertEquals("", s.text)
    }

    @Test
    fun listeningAgainAddsToTheText() {
        val s = run(
            VoiceEvent.MicTapped,
            VoiceEvent.SegmentFinal("First part."),
            VoiceEvent.SpeechEnded,
            VoiceEvent.MicTapped,
            VoiceEvent.SegmentFinal("Second part."),
            VoiceEvent.SpeechEnded,
        )
        assertEquals("First part. Second part.", s.text)
    }

    @Test
    fun editingTextKeepsItReadyAndEmptyingDisables() {
        var s = run(VoiceEvent.MicTapped, VoiceEvent.SegmentFinal("helo wrld"), VoiceEvent.SpeechEnded)
        s = VoiceFlow.reduce(s, VoiceEvent.TextEdited("Hello world"))
        assertEquals("Hello world", s.text)
        assertTrue(s.canGenerate)
        s = VoiceFlow.reduce(s, VoiceEvent.TextEdited(""))
        assertFalse(s.canGenerate)
        assertEquals(Stage.Idle, s.stage)
    }

    @Test
    fun stopKeepsTextForRetry() {
        val s = run(
            VoiceEvent.MicTapped,
            VoiceEvent.SegmentFinal("Explain REST"),
            VoiceEvent.SpeechEnded,
            VoiceEvent.GenerateTapped,
            VoiceEvent.StopTapped,
        )
        assertEquals(Stage.Transcribed, s.stage)
        assertEquals("Explain REST", s.text)
        assertTrue(s.canGenerate)
    }

    @Test
    fun emptySpeechReturnsToIdleWithMessage() {
        val s = run(VoiceEvent.MicTapped, VoiceEvent.SegmentFinal("   "), VoiceEvent.SpeechEnded)
        assertEquals(Stage.Idle, s.stage)
        assertTrue(s.error != null)
    }

    @Test
    fun failureKeepsWhatWasAlreadyHeard() {
        val s = run(VoiceEvent.MicTapped, VoiceEvent.SegmentFinal("Half a question"), VoiceEvent.SpeechFailed("mic lost"))
        assertEquals(Stage.Transcribed, s.stage)
        assertEquals("Half a question", s.text)
        assertEquals("mic lost", s.error)
    }

    @Test
    fun lateSpeechEventsAreIgnoredOnceGeneratingHasStarted() {
        val s = run(
            VoiceEvent.MicTapped,
            VoiceEvent.SegmentFinal("Question"),
            VoiceEvent.GenerateTapped,
            VoiceEvent.SegmentFinal("late words"),
            VoiceEvent.Partial("late"),
            VoiceEvent.SpeechEnded,
        )
        assertEquals(Stage.Generating, s.stage)
        assertEquals("Question", s.question)
        assertEquals("", s.partial)
    }

    @Test
    fun editsAreIgnoredWhileGenerating() {
        val s = run(
            VoiceEvent.MicTapped,
            VoiceEvent.SegmentFinal("Question"),
            VoiceEvent.SpeechEnded,
            VoiceEvent.GenerateTapped,
            VoiceEvent.TextEdited("changed"),
        )
        assertEquals("Question", s.text)
    }

    @Test
    fun failureReturnsToTranscribedWithError() {
        val s = run(
            VoiceEvent.MicTapped,
            VoiceEvent.SegmentFinal("Q"),
            VoiceEvent.SpeechEnded,
            VoiceEvent.GenerateTapped,
            VoiceEvent.GenerationFailed("boom"),
        )
        assertEquals(Stage.Transcribed, s.stage)
        assertEquals("boom", s.error)
    }

    @Test
    fun secondQuestionIsIndependent() {
        val first = run(
            VoiceEvent.MicTapped,
            VoiceEvent.SegmentFinal("One"),
            VoiceEvent.SpeechEnded,
            VoiceEvent.GenerateTapped,
            VoiceEvent.Token("A1"),
            VoiceEvent.GenerationFinished,
            VoiceEvent.ResetDone,
        )
        val second = run(VoiceEvent.MicTapped, VoiceEvent.SegmentFinal("Two"), VoiceEvent.SpeechEnded, VoiceEvent.GenerateTapped, from = first)
        assertEquals("Two", second.question)
        assertEquals("", second.answer)
    }
}
