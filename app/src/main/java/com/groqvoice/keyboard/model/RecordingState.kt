package com.groqvoice.keyboard.model

/**
 * Sealed class representing all possible states of the audio recording pipeline.
 * Used by [com.groqvoice.keyboard.audio.AudioRecordingManager] as its state machine type.
 *
 * TSD Section 4.1 — Recording State Machine.
 */
sealed class RecordingState {

    /** Mic is idle; no recording or processing in progress. */
    object Idle : RecordingState()

    /**
     * Audio is actively being recorded.
     *
     * @param mode Whether the user is holding (PUSH_TO_TALK) or used a single tap (HANDS_FREE).
     * @param startTime Epoch milliseconds when recording began (used for max-duration enforcement).
     */
    data class Recording(
        val mode: RecordingMode,
        val startTime: Long
    ) : RecordingState()

    /** Audio has been captured and the Groq API call is in-flight. */
    object Processing : RecordingState()

    /**
     * A recoverable error occurred.
     *
     * @param message Human-readable description shown in the keyboard banner.
     */
    data class Error(val message: String) : RecordingState()
}

/**
 * The two recording interaction modes supported by GroqVoice.
 *
 * TSD Section 4.1 / 4.2 — touch event processing.
 */
enum class RecordingMode {
    /** User must hold the mic button; release triggers transcription. */
    PUSH_TO_TALK,

    /** User taps mic to start; recording stops automatically on silence or a second tap. */
    HANDS_FREE
}
