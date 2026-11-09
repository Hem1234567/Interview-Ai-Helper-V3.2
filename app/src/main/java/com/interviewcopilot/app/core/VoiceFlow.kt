package com.interviewcopilot.app.core

/** The only states the voice screen can be in. Order of the happy path: see [VoiceFlow]. */
enum class Stage { Idle, Listening, Transcribed, Generating, Answered }

data class VoiceState(
    val stage: Stage = Stage.Idle,
    /** The editable text box. Filled by speech, editable by the user, cleared after an answer. */
    val text: String = "",
    /** Live (not yet final) words of the sentence being spoken right now. */
    val partial: String = "",
    /** The question that the visible answer belongs to. */
    val question: String = "",
    val answer: String = "",
    val error: String? = null,
) {
    /** Everything said so far, including the words that are still being decoded. */
    val liveText: String get() = SpeechText.join(text, partial)

    /** "Generate Answer" works with any text, also while the mic is still listening. */
    val canGenerate: Boolean
        get() = when (stage) {
            Stage.Idle, Stage.Transcribed -> text.isNotBlank()
            Stage.Listening -> liveText.isNotBlank()
            else -> false
        }

    val canClear: Boolean
        get() = (stage == Stage.Idle || stage == Stage.Transcribed || stage == Stage.Listening) && liveText.isNotBlank()

    val canStop: Boolean get() = stage == Stage.Generating
    val micBusy: Boolean get() = stage == Stage.Generating
}

sealed interface VoiceEvent {
    data object MicTapped : VoiceEvent
    data class Partial(val text: String) : VoiceEvent

    /** One finished piece of speech (the speaker paused). It is appended; listening goes on. */
    data class SegmentFinal(val text: String) : VoiceEvent

    /** Capture is over (the user stopped it). All pieces have been delivered before this. */
    data object SpeechEnded : VoiceEvent
    data class SpeechFailed(val message: String) : VoiceEvent
    data class TextEdited(val text: String) : VoiceEvent
    data object ClearTapped : VoiceEvent
    data object GenerateTapped : VoiceEvent
    data class Token(val answerSoFar: String) : VoiceEvent
    data object GenerationFinished : VoiceEvent
    data object StopTapped : VoiceEvent
    data class GenerationFailed(val message: String) : VoiceEvent
    data object ResetDone : VoiceEvent
}

/**
 * Pure reducer for the voice flow. Speech and answer generation are separate steps:
 *
 * Idle -> Listening -> Transcribed -> Generating -> Answered -> Idle
 *
 * Listening is continuous: pauses never end it and every finished piece of speech is appended to the
 * text. Nothing in here ever starts generation by itself; only [VoiceEvent.GenerateTapped] does.
 */
object VoiceFlow {
    fun reduce(s: VoiceState, e: VoiceEvent): VoiceState = when (e) {
        VoiceEvent.MicTapped -> {
            when (s.stage) {
                Stage.Listening, Stage.Generating -> s

                // Starting again adds to what is already in the box; the Clear button empties it.
                else -> s.copy(stage = Stage.Listening, partial = "", error = null)
            }
        }

        is VoiceEvent.Partial -> {
            if (s.stage == Stage.Listening) s.copy(partial = e.text) else s
        }

        is VoiceEvent.SegmentFinal -> {
            if (s.stage == Stage.Listening) s.copy(text = SpeechText.join(s.text, e.text), partial = "") else s
        }

        VoiceEvent.SpeechEnded -> {
            if (s.stage != Stage.Listening) {
                s
            } else if (s.text.isBlank()) {
                s.copy(stage = Stage.Idle, partial = "", error = "I did not catch that. Tap the mic and try again.")
            } else {
                s.copy(stage = Stage.Transcribed, partial = "", error = null)
            }
        }

        is VoiceEvent.SpeechFailed -> {
            if (s.stage == Stage.Listening) {
                s.copy(stage = if (s.text.isBlank()) Stage.Idle else Stage.Transcribed, partial = "", error = e.message)
            } else {
                s
            }
        }

        is VoiceEvent.TextEdited -> {
            when (s.stage) {
                Stage.Idle, Stage.Transcribed -> {
                    s.copy(text = e.text, stage = if (e.text.isBlank()) Stage.Idle else Stage.Transcribed, error = null)
                }

                else -> {
                    s
                }
            }
        }

        VoiceEvent.ClearTapped -> {
            when (s.stage) {
                Stage.Listening -> s.copy(text = "", partial = "", error = null)
                Stage.Idle, Stage.Transcribed -> s.copy(stage = Stage.Idle, text = "", partial = "", error = null)
                else -> s
            }
        }

        VoiceEvent.GenerateTapped -> {
            if (s.canGenerate) {
                s.copy(stage = Stage.Generating, text = s.liveText, partial = "", question = s.liveText.trim(), answer = "", error = null)
            } else {
                s
            }
        }

        is VoiceEvent.Token -> {
            if (s.stage == Stage.Generating) s.copy(answer = e.answerSoFar) else s
        }

        VoiceEvent.GenerationFinished -> {
            if (s.stage == Stage.Generating) s.copy(stage = Stage.Answered) else s
        }

        VoiceEvent.StopTapped -> {
            // Cancelled: keep the text so the user can generate again or edit it.
            if (s.stage == Stage.Generating) s.copy(stage = Stage.Transcribed) else s
        }

        is VoiceEvent.GenerationFailed -> {
            if (s.stage == Stage.Generating) s.copy(stage = Stage.Transcribed, error = e.message) else s
        }

        VoiceEvent.ResetDone -> {
            // Mic and text box are ready for the next question; the answer stays on screen.
            if (s.stage == Stage.Answered) s.copy(stage = Stage.Idle, text = "", partial = "") else s
        }
    }
}
