package com.groqvoice.keyboard.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [VoiceActivityDetector].
 */
class VoiceActivityDetectorTest {

    @Test
    fun `computeRms returns 0 for empty chunk`() {
        val vad = VoiceActivityDetector()
        val rms = vad.computeRms(ByteArray(0))
        assert(rms == 0.0)
    }

    @Test
    fun `computeRms returns non-zero for loud signal`() {
        val vad = VoiceActivityDetector()
        // Fill with max 16-bit signed value: 32767 → bytes 0xFF, 0x7F (little-endian)
        val chunk = ByteArray(200) { i -> if (i % 2 == 0) 0xFF.toByte() else 0x7F.toByte() }
        val rms = vad.computeRms(chunk)
        assertTrue(rms > 1000.0) // Well above silence threshold
    }

    @Test
    fun `isSilence returns false for loud audio`() {
        val vad = VoiceActivityDetector(
            silenceThresholdRms = 100.0,
            silenceDurationMs = 500L
        )
        val loudChunk = ByteArray(3200) { i -> if (i % 2 == 0) 0xFF.toByte() else 0x7F.toByte() }
        assertFalse(vad.isSilence(loudChunk))
    }

    @Test
    fun `isSilence returns true after sustained quiet audio`() {
        // 16kHz, 16-bit mono → 32000 bytes/sec → 500ms = 16000 bytes
        val vad = VoiceActivityDetector(
            silenceThresholdRms = 100.0,
            silenceDurationMs = 500L,
            sampleRateHz = 16_000
        )
        val loudChunk = ByteArray(3200) { i -> if (i % 2 == 0) 0xFF.toByte() else 0x7F.toByte() }
        // Feed 500ms of near-silence (amplitude = 1, well below threshold of 100)
        val silentChunk = ByteArray(16_000) { i -> if (i % 2 == 0) 0x01.toByte() else 0x00.toByte() }
        vad.isSilence(loudChunk)
        assertTrue(vad.isSilence(silentChunk))
    }

    @Test
    fun `reset clears accumulated silence`() {
        val vad = VoiceActivityDetector(
            silenceThresholdRms = 100.0,
            silenceDurationMs = 500L,
            sampleRateHz = 16_000
        )
        val loudChunk = ByteArray(3200) { i -> if (i % 2 == 0) 0xFF.toByte() else 0x7F.toByte() }
        val silentChunk = ByteArray(16_000) { i -> if (i % 2 == 0) 0x01.toByte() else 0x00.toByte() }
        vad.isSilence(loudChunk)
        vad.isSilence(silentChunk) // Accumulate silence
        vad.reset()
        // After reset, the same chunk should NOT immediately trigger (starts from 0)
        assertFalse(vad.isSilence(ByteArray(100))) // Very small chunk, not enough for 500ms
    }

    @Test
    fun `isSilence does not trigger before first speech`() {
        val vad = VoiceActivityDetector(
            silenceThresholdRms = 100.0,
            silenceDurationMs = 500L,
            sampleRateHz = 16_000
        )
        val silentChunk = ByteArray(16_000) { i -> if (i % 2 == 0) 0x01.toByte() else 0x00.toByte() }
        assertFalse(vad.isSilence(silentChunk))
    }
}
