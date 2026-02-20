package com.groqvoice.keyboard.audio

import kotlin.math.sqrt

/**
 * Simple amplitude-based Voice Activity Detector (VAD).
 *
 * Implements the "500ms silence = stop" logic described in TSD Section 4.3 (Hands-Free Mode).
 * Does NOT use ML; suitable for the v1 implementation.  Can be replaced with WebRTC VAD or
 * Silero VAD in a future phase.
 *
 * Algorithm:
 *  1. Compute RMS of a PCM chunk.
 *  2. Compare against [silenceThresholdRms].
 *  3. Track consecutive silent chunks using a sliding window of duration [silenceDurationMs].
 */
class VoiceActivityDetector(
    /** RMS amplitude below which a chunk is considered silence. Tunable via Settings. */
    private val silenceThresholdRms: Double = DEFAULT_SILENCE_THRESHOLD,

    /** Duration in milliseconds of continuous silence required to trigger stop. */
    private val silenceDurationMs: Long = DEFAULT_SILENCE_DURATION_MS,

    /** Sample rate used to convert chunk sizes to time. */
    private val sampleRateHz: Int = AudioRecordingManager.SAMPLE_RATE_HZ
) {

    companion object {
        /**
         * RMS threshold for 16-bit audio. ~-40 dBFS maps to approximately 100 on a 0–32767 scale.
         * TSD Section 5.2 — Silent Recording: "RMS < -40dB for 3s → discard".
         */
        const val DEFAULT_SILENCE_THRESHOLD = 100.0

        /** 500ms of silence stops hands-free recording (TSD 4.3). */
        const val DEFAULT_SILENCE_DURATION_MS = 500L
    }

    private var silentMs = 0L

    /**
     * Feed a raw 16-bit PCM [chunk] (little-endian byte array).
     *
     * @return `true` if the accumulated silence has exceeded [silenceDurationMs].
     */
    fun isSilence(chunk: ByteArray): Boolean {
        val rms = computeRms(chunk)
        val chunkDurationMs = (chunk.size.toLong() * 1000L) / (sampleRateHz * 2L) // 2 bytes/sample

        if (rms < silenceThresholdRms) {
            silentMs += chunkDurationMs
        } else {
            silentMs = 0L // Reset on speech activity
        }

        return silentMs >= silenceDurationMs
    }

    /** Resets the internal silence accumulator. Call when a new recording starts. */
    fun reset() {
        silentMs = 0L
    }

    /**
     * Computes the Root Mean Square amplitude of a 16-bit little-endian PCM [chunk].
     *
     * @return RMS value in the range [0, 32767].
     */
    fun computeRms(chunk: ByteArray): Double {
        if (chunk.size < 2) return 0.0
        var sumSquares = 0.0
        var sampleCount = 0

        var i = 0
        while (i + 1 < chunk.size) {
            val sample = (chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xFF)
            sumSquares += sample.toDouble() * sample.toDouble()
            sampleCount++
            i += 2
        }

        return if (sampleCount == 0) 0.0 else sqrt(sumSquares / sampleCount)
    }
}
