package com.groqvoice.keyboard.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for [AudioEncoder].
 *
 * TSD Section 8.1 — AudioEncoder: Verify WAV header format, sample rate conversion.
 */
class AudioEncoderTest {

    @Test
    fun `buildWavHeader contains RIFF marker`() {
        val header = AudioEncoder.buildWavHeader(
            totalChunkSize = 36 + 1000,
            dataSize = 1000,
            sampleRate = 16_000,
            byteRate = 32_000
        )
        // First 4 bytes should spell "RIFF"
        val riff = String(header.slice(0..3).toByteArray(), Charsets.US_ASCII)
        assertEquals("RIFF", riff)
    }

    @Test
    fun `buildWavHeader contains WAVE marker`() {
        val header = AudioEncoder.buildWavHeader(36 + 100, 100, 16_000, 32_000)
        val wave = String(header.slice(8..11).toByteArray(), Charsets.US_ASCII)
        assertEquals("WAVE", wave)
    }

    @Test
    fun `buildWavHeader total chunk size field is correct`() {
        val dataSize = 500
        val expectedChunkSize = 36 + dataSize
        val header = AudioEncoder.buildWavHeader(expectedChunkSize, dataSize, 16_000, 32_000)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(4)
        val chunkSize = buf.int
        assertEquals(expectedChunkSize, chunkSize)
    }

    @Test
    fun `buildWavHeader sample rate field matches input`() {
        val sampleRate = 16_000
        val header = AudioEncoder.buildWavHeader(36 + 100, 100, sampleRate, 32_000)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(24)
        assertEquals(sampleRate, buf.int)
    }

    @Test
    fun `buildWavHeader is exactly 44 bytes`() {
        val header = AudioEncoder.buildWavHeader(36, 0, 16_000, 32_000)
        assertEquals(44, header.size)
    }

    @Test
    fun `writePcmToWav produces a valid WAV file`() {
        val pcm = ByteArray(3200) { it.toByte() } // 0.1s of silence at 16kHz mono 16-bit
        val outputFile = File.createTempFile("test_wav", ".wav")
        try {
            AudioEncoder.writePcmToWav(pcm, outputFile, 16_000)

            assertTrue(outputFile.exists())
            assertTrue(outputFile.length() > 44) // Header (44) + PCM data

            // Verify WAV header in the output file
            val bytes = outputFile.readBytes()
            val header = bytes.slice(0..3).toByteArray()
            assertEquals("RIFF", String(header, Charsets.US_ASCII))
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun `writePcmToFile with WAV format returns true`() {
        val pcm = ByteArray(1024) { 1 }
        val outputFile = File.createTempFile("test_wav_format", ".wav")

        try {
            val success = AudioEncoder.writePcmToFile(
                data = pcm,
                outputFile = outputFile,
                sampleRate = 16_000,
                format = AudioEncoder.OutputFormat.WAV
            )

            assertTrue(success)
            assertTrue(outputFile.exists())
            assertTrue(outputFile.length() > 44L)
        } finally {
            outputFile.delete()
        }
    }
}
