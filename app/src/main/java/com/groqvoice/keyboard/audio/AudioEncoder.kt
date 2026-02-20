package com.groqvoice.keyboard.audio

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes raw PCM byte arrays into WAV files suitable for upload to the Groq API.
 *
 * WAV format reference: http://soundfile.sapp.org/doc/WaveFormat/
 *
 * TSD Section 4.3, Appendix A — file upload format.
 * TSD Section 6.2 — FLAC compression (placeholder; FLAC encoding requires a native library).
 */
object AudioEncoder {

    private const val BITS_PER_SAMPLE = 16
    private const val NUM_CHANNELS = 1 // Mono (TSD 4.3)
    private const val BYTE_RATE_MULTIPLIER = NUM_CHANNELS * (BITS_PER_SAMPLE / 8)
    private const val BLOCK_ALIGN = NUM_CHANNELS * (BITS_PER_SAMPLE / 8)

    /**
     * Writes raw 16-bit PCM [data] into a valid WAV file at [outputFile].
     *
     * @param data   Raw PCM bytes (little-endian, 16-bit signed).
     * @param outputFile Destination file (created/overwritten).
     * @param sampleRate Sample rate in Hz (e.g. 16000).
     */
    fun writePcmToWav(data: ByteArray, outputFile: File, sampleRate: Int) {
        val totalDataLen = data.size
        val totalChunkSize = 36 + totalDataLen
        val byteRate = sampleRate * BYTE_RATE_MULTIPLIER

        FileOutputStream(outputFile).use { fos ->
            fos.write(buildWavHeader(totalChunkSize, totalDataLen, sampleRate, byteRate))
            fos.write(data)
        }
    }

    /**
     * Builds the 44-byte WAV file header.
     *
     * @param totalChunkSize RIFF chunk size = 36 + PCM data size.
     * @param dataSize       PCM data size in bytes.
     * @param sampleRate     Sample rate in Hz.
     * @param byteRate       SampleRate × NumChannels × BitsPerSample / 8.
     * @return 44-byte header array.
     */
    fun buildWavHeader(
        totalChunkSize: Int,
        dataSize: Int,
        sampleRate: Int,
        byteRate: Int
    ): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk descriptor
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalChunkSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))

        // "fmt " sub-chunk
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                       // Subchunk1Size (PCM = 16)
        header.putShort(1)                      // AudioFormat (PCM = 1)
        header.putShort(NUM_CHANNELS.toShort()) // NumChannels
        header.putInt(sampleRate)               // SampleRate
        header.putInt(byteRate)                 // ByteRate
        header.putShort(BLOCK_ALIGN.toShort())  // BlockAlign
        header.putShort(BITS_PER_SAMPLE.toShort())

        // "data" sub-chunk
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)

        return header.array()
    }
}
