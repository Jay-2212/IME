package com.groqvoice.keyboard.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.groqvoice.keyboard.model.RecordingMode
import com.groqvoice.keyboard.model.RecordingState
import com.groqvoice.keyboard.utils.FileCacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Arrays

/**
 * Manages the full audio recording lifecycle including the state machine,
 * AudioRecord configuration, and hands-free VAD integration.
 *
 * Architecture: Owns a [StateFlow] of [RecordingState] that the UI observes.
 * Audio data is buffered in memory during recording; on stop it is encoded to
 * a WAV/FLAC file and passed to [onRecordingComplete].
 *
 * TSD Section 4.1, 4.2, 4.3.
 */
class AudioRecordingManager(
    private val fileCacheManager: FileCacheManager,
    private val vad: VoiceActivityDetector = VoiceActivityDetector()
) {

    companion object {
        /** Optimal sample rate for Groq Whisper (TSD 4.3). */
        const val SAMPLE_RATE_HZ = 16_000

        /** Minimum buffer size multiplier to prevent overflow (TSD 4.3). */
        private const val BUFFER_MULTIPLIER = 2

        /** Maximum recording duration in milliseconds (TSD 4.3 / 6.1). */
        private const val MAX_DURATION_MS = 5 * 60 * 1_000L

        /** Tap-vs-hold threshold in milliseconds (TSD 4.2). */
        const val HOLD_THRESHOLD_MS = 800L

        /** Debounce window to prevent state machine corruption (TSD 5.4). */
        const val DEBOUNCE_MS = 300L
    }

    // ── State machine ──────────────────────────────────────────────────────────
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    // ── Coroutine scope ────────────────────────────────────────────────────────
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordingJob: Job? = null

    // ── AudioRecord ────────────────────────────────────────────────────────────
    private var audioRecord: AudioRecord? = null
    private var minBufferSize: Int = 0

    // ── PCM data accumulator ───────────────────────────────────────────────────
    private val pcmBuffer = mutableListOf<ByteArray>()

    // ── Callbacks ──────────────────────────────────────────────────────────────
    /** Called on the IO thread once recording stops and encoding is complete. */
    var onRecordingComplete: ((File) -> Unit)? = null

    /** Called when an error occurs; passes a user-facing message. */
    var onError: ((String) -> Unit)? = null

    // ── Debounce ───────────────────────────────────────────────────────────────
    private var lastInteractionMs = 0L

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Starts a new recording session.
     * A debounce guard prevents rapid taps from corrupting the state machine (TSD 5.4).
     *
     * @param mode [RecordingMode.PUSH_TO_TALK] or [RecordingMode.HANDS_FREE].
     */
    fun startRecording(mode: RecordingMode) {
        val now = System.currentTimeMillis()
        if (now - lastInteractionMs < DEBOUNCE_MS) return
        lastInteractionMs = now

        if (_state.value !is RecordingState.Idle) return

        _state.value = RecordingState.Recording(mode, now)
        recordingJob = scope.launch { doRecord(mode) }
    }

    /**
     * Signals the recording loop to stop and triggers encoding.
     * No-op if not currently recording.
     */
    fun stopRecording() {
        val now = System.currentTimeMillis()
        if (now - lastInteractionMs < DEBOUNCE_MS) return
        lastInteractionMs = now

        if (_state.value !is RecordingState.Recording) return

        recordingJob?.cancel()
        finishRecording()
    }

    /** Releases all hardware resources; call from [android.inputmethodservice.InputMethodService.onDestroy]. */
    fun release() {
        recordingJob?.cancel()
        audioRecord?.release()
        audioRecord = null
        clearPcmBuffer()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun doRecord(mode: RecordingMode) {
        minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            transitionToError("Microphone configuration error.")
            return
        }

        val bufferSize = minBufferSize * BUFFER_MULTIPLIER
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            transitionToError("Microphone unavailable. Check app permissions.")
            return
        }

        audioRecord?.startRecording()
        val startTime = System.currentTimeMillis()
        val chunk = ByteArray(bufferSize)

        while (_state.value is RecordingState.Recording) {
            val read = audioRecord?.read(chunk, 0, bufferSize) ?: break
            if (read <= 0) continue

            val pcmChunk = chunk.copyOf(read)
            pcmBuffer.add(pcmChunk)

            // Hands-free: check VAD for silence (TSD 4.3)
            if (mode == RecordingMode.HANDS_FREE && vad.isSilence(pcmChunk)) {
                break
            }

            // Enforce maximum recording duration (TSD 4.3 / 6.1)
            if (System.currentTimeMillis() - startTime > MAX_DURATION_MS) {
                break
            }
        }

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        finishRecording()
    }

    private fun finishRecording() {
        _state.value = RecordingState.Processing

        val allPcm = pcmBuffer.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        clearPcmBuffer()

        val outputFile = fileCacheManager.createTempWavFile()
        AudioEncoder.writePcmToWav(allPcm, outputFile, SAMPLE_RATE_HZ)

        onRecordingComplete?.invoke(outputFile)
    }

    private fun transitionToError(message: String) {
        audioRecord?.release()
        audioRecord = null
        clearPcmBuffer()
        _state.value = RecordingState.Error(message)
        onError?.invoke(message)
    }

    /** Zero-fills all PCM chunks before clearing to reduce sensitive data in memory (TSD 7.1). */
    private fun clearPcmBuffer() {
        pcmBuffer.forEach { Arrays.fill(it, 0) }
        pcmBuffer.clear()
    }
}
