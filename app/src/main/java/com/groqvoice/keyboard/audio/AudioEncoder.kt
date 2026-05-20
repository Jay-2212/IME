package com.groqvoice.keyboard.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes raw PCM byte arrays into uploadable audio files (WAV / FLAC).
 *
 * WAV format reference: http://soundfile.sapp.org/doc/WaveFormat/
 *
 * TSD Section 4.3, Appendix A — file upload format.
 * TSD Section 6.2 — FLAC compression (50% size reduction target).
 */
object AudioEncoder {

    /**
     * Supported output containers for transcription upload.
     */
    enum class OutputFormat {
        WAV,
        FLAC
    }

    private const val BITS_PER_SAMPLE = 16
    private const val NUM_CHANNELS = 1 // Mono (TSD 4.3)
    private const val BYTE_RATE_MULTIPLIER = NUM_CHANNELS * (BITS_PER_SAMPLE / 8)
    private const val BLOCK_ALIGN = NUM_CHANNELS * (BITS_PER_SAMPLE / 8)
    private const val CODEC_TIMEOUT_US = 10_000L

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
     * Writes [data] using the requested [format].
     *
     * - [OutputFormat.WAV] always succeeds (unless IO fails).
     * - [OutputFormat.FLAC] uses `MediaCodec` FLAC encoder; returns false when unavailable.
     *
     * @return true if encoding succeeded and [outputFile] is non-empty.
     */
    fun writePcmToFile(
        data: ByteArray,
        outputFile: File,
        sampleRate: Int,
        format: OutputFormat
    ): Boolean {
        return when (format) {
            OutputFormat.WAV -> {
                writePcmToWav(data, outputFile, sampleRate)
                true
            }
            OutputFormat.FLAC -> writePcmToFlac(data, outputFile, sampleRate)
        }
    }

    /**
     * Encodes [data] to FLAC via Android's platform encoder (`MediaCodec`).
     *
     * This method is best-effort and intentionally conservative:
     * - returns `false` instead of throwing for codec unavailability / runtime failure.
     * - caller should fallback to WAV when this returns `false`.
     */
    fun writePcmToFlac(data: ByteArray, outputFile: File, sampleRate: Int): Boolean {
        val codec = runCatching {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        }.getOrNull() ?: return false

        return try {
            val format = MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_FLAC)
                setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                setInteger(MediaFormat.KEY_CHANNEL_COUNT, NUM_CHANNELS)
                setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    sampleRate * NUM_CHANNELS * (BITS_PER_SAMPLE / 8)
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                }
            }

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            FileOutputStream(outputFile).use { fos ->
                drainEncodedFlac(codec, data, fos)
            }

            outputFile.exists() && outputFile.length() > 0L
        } catch (_: Exception) {
            false
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    /**
     * Feeds PCM bytes to [codec] and writes all emitted FLAC packets to [outputStream].
     */
    private fun drainEncodedFlac(
        codec: MediaCodec,
        inputPcm: ByteArray,
        outputStream: FileOutputStream
    ) {
        var inputOffset = 0
        var inputDone = false
        var outputDone = false
        val bufferInfo = MediaCodec.BufferInfo()
        var noProgressCount = 0

        while (!outputDone) {
            var madeProgress = false

            if (!inputDone) {
                val inputBufferIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: continue
                    inputBuffer.clear()

                    val bytesRemaining = inputPcm.size - inputOffset
                    if (bytesRemaining <= 0) {
                        codec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        val bytesToWrite = minOf(inputBuffer.remaining(), bytesRemaining)
                        inputBuffer.put(inputPcm, inputOffset, bytesToWrite)
                        codec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            bytesToWrite,
                            0L,
                            0
                        )
                        inputOffset += bytesToWrite
                    }
                    madeProgress = true
                }
            }

            var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
            if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED
            ) {
                madeProgress = true
            }

            while (outputBufferIndex >= 0) {
                madeProgress = true
                val encodedBuffer = codec.getOutputBuffer(outputBufferIndex)
                if (bufferInfo.size > 0 && encodedBuffer != null) {
                    encodedBuffer.position(bufferInfo.offset)
                    encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    val chunk = ByteArray(bufferInfo.size)
                    encodedBuffer.get(chunk)
                    outputStream.write(chunk)
                }

                outputDone = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                codec.releaseOutputBuffer(outputBufferIndex, false)
                outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }

            if (madeProgress) {
                noProgressCount = 0
            } else {
                noProgressCount++
                if (noProgressCount > 1000) {
                    throw IllegalStateException("FLAC encoder hang detected: no progress for 1000 iterations")
                }
            }

            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                // Prevent tight loop once EOS is queued but output has not yet been flushed.
                Thread.yield()
            }
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
