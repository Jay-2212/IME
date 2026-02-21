package com.groqvoice.keyboard.audio

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.groqvoice.keyboard.model.RecordingMode
import com.groqvoice.keyboard.model.RecordingState
import com.groqvoice.keyboard.utils.FileCacheManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns microphone capture lifecycle, recording state transitions, and interruption policy.
 *
 * Core guarantees:
 * - Never emits duplicate "recording finished" callbacks for a single session.
 * - Zero-fills in-memory PCM buffers before release (TSD 7.1).
 * - Releases wake lock and listeners on all stop paths.
 * - Keeps UI thread free by recording/encoding on `Dispatchers.IO`.
 *
 * TSD Section 4.1, 4.3, 5.2, 5.3, 6.1, 6.3, 7.1.
 */
class AudioRecordingManager(
    private val context: Context,
    private val fileCacheManager: FileCacheManager,
    private val vad: VoiceActivityDetector = VoiceActivityDetector()
) {

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val HOLD_THRESHOLD_MS = 800L
        const val DEBOUNCE_MS = 300L

        private const val BUFFER_MULTIPLIER = 2
        private const val MAX_DURATION_MS = 5 * 60 * 1_000L
        private const val WAKE_LOCK_TAG = "GroqVoice::RecordingLock"
        private const val WAKE_LOCK_TIMEOUT_MS = MAX_DURATION_MS + 30_000L
    }

    enum class InterruptionReason {
        PHONE_CALL,
        BLUETOOTH_ROUTING_CHANGE,
        TRIM_MEMORY
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state = _state.asStateFlow()

    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val pcmBuffer = mutableListOf<ByteArray>()

    /**
     * Pre-buffer retained for future enhancement. Phase 2 introduced this class; Phase 3 keeps it
     * safe by never running a second `AudioRecord` concurrently with active recording.
     */
    private var preBuffer: CircularByteBuffer? = null

    private val stopRequested = AtomicBoolean(false)
    private val finishInProgress = AtomicBoolean(false)

    private val powerManager = ContextCompat.getSystemService(context, PowerManager::class.java)
    private var wakeLock: PowerManager.WakeLock? = null

    private val telephonyManager = ContextCompat.getSystemService(context, TelephonyManager::class.java)
    private var phoneStateListener: PhoneStateListener? = null

    private var audioRoutingReceiver: BroadcastReceiver? = null

    var onRecordingComplete: ((File) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onInterrupted: ((InterruptionReason) -> Unit)? = null

    /**
     * Starts a new recording session.
     */
    fun startRecording(mode: RecordingMode) {
        if (_state.value !is RecordingState.Idle) return

        stopRequested.set(false)
        finishInProgress.set(false)
        vad.reset()

        if (mode == RecordingMode.HANDS_FREE) {
            acquireWakeLock()
            registerInterruptionListeners()
        }

        val startTime = System.currentTimeMillis()
        _state.value = RecordingState.Recording(mode = mode, startTime = startTime)

        recordingJob?.cancel()
        recordingJob = scope.launch {
            doRecord(mode = mode, recordingStartTimeMs = startTime)
        }
    }

    /**
     * Requests graceful stop of the active recording.
     *
     * `finishRecording()` is invoked exactly once from recording coroutine teardown.
     */
    fun stopRecording() {
        if (_state.value !is RecordingState.Recording) return
        stopRequested.set(true)
        stopAudioRecordSafely()
        recordingJob?.cancel()
    }

    /**
     * Stops recording and discards captured PCM without invoking interruption callbacks.
     *
     * Used for tap-to-hands-free mode switches where the initial short press should not be
     * uploaded as a separate transcription.
     */
    fun cancelRecordingForModeSwitch() {
        if (_state.value !is RecordingState.Recording) return

        stopRequested.set(true)
        stopAudioRecordSafely()
        recordingJob?.cancel()

        if (!finishInProgress.compareAndSet(false, true)) return

        clearPcmBuffer()
        preBuffer?.clear()
        releaseAudioRecord()
        releaseWakeLock()
        unregisterInterruptionListeners()
        _state.value = RecordingState.Idle
    }

    /**
     * Stops recording immediately and discards buffered audio for privacy.
     */
    fun stopForInterruption(reason: InterruptionReason) {
        if (_state.value !is RecordingState.Recording) return

        stopRequested.set(true)
        stopAudioRecordSafely()
        recordingJob?.cancel()

        if (!finishInProgress.compareAndSet(false, true)) return

        clearPcmBuffer()
        preBuffer?.clear()
        releaseAudioRecord()
        releaseWakeLock()
        unregisterInterruptionListeners()

        _state.value = RecordingState.Idle
        onInterrupted?.invoke(reason)
    }

    /**
     * Handles system memory pressure.
     */
    fun onTrimMemory(level: Int) {
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> stopForInterruption(InterruptionReason.TRIM_MEMORY)
            else -> preBuffer?.clear()
        }
    }

    /**
     * Releases all resources. Manager must not be reused after this call.
     */
    fun release() {
        stopRequested.set(true)
        recordingJob?.cancel()
        releaseAudioRecord()
        releaseWakeLock()
        unregisterInterruptionListeners()
        clearPcmBuffer()
        preBuffer?.close()
        preBuffer = null
        _state.value = RecordingState.Idle
        scope.cancel()
    }

    private fun doRecord(mode: RecordingMode, recordingStartTimeMs: Long) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            transitionToError("Microphone configuration error.")
            return
        }

        val bufferSize = minBufferSize * BUFFER_MULTIPLIER
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            transitionToError("Microphone unavailable. Check app permissions.")
            return
        }

        audioRecord = record

        val chunk = ByteArray(bufferSize)

        try {
            record.startRecording()
            drainPreBuffer()

            while (scope.isActive && !stopRequested.get() && _state.value is RecordingState.Recording) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) continue

                val pcmChunk = chunk.copyOf(read)
                pcmBuffer.add(pcmChunk)

                val elapsed = System.currentTimeMillis() - recordingStartTimeMs
                val silenceDetected = mode == RecordingMode.HANDS_FREE && vad.isSilence(pcmChunk)
                if (silenceDetected || elapsed >= MAX_DURATION_MS) break
            }
        } catch (_: CancellationException) {
            // Expected during user stop/cancel; teardown continues in finally.
        } catch (_: Exception) {
            transitionToError("Audio capture failed unexpectedly.")
            return
        } finally {
            releaseAudioRecord()
            if (_state.value !is RecordingState.Error) {
                finishRecording()
            }
        }
    }

    /**
     * Encodes captured PCM and emits [onRecordingComplete].
     */
    private fun finishRecording() {
        if (!finishInProgress.compareAndSet(false, true)) return

        if (stopRequested.get() && pcmBuffer.isEmpty()) {
            releaseWakeLock()
            unregisterInterruptionListeners()
            _state.value = RecordingState.Idle
            return
        }

        _state.value = RecordingState.Processing
        releaseWakeLock()
        unregisterInterruptionListeners()

        val allPcm = flattenPcmChunks()
        clearPcmBuffer()

        if (allPcm.isEmpty()) {
            transitionToError("No speech detected.")
            return
        }

        var outputFile = fileCacheManager.createTempFlacFile()
        val flacEncoded = AudioEncoder.writePcmToFile(
            data = allPcm,
            outputFile = outputFile,
            sampleRate = SAMPLE_RATE_HZ,
            format = AudioEncoder.OutputFormat.FLAC
        )

        if (!flacEncoded) {
            fileCacheManager.deleteFile(outputFile)
            outputFile = fileCacheManager.createTempWavFile()
            AudioEncoder.writePcmToFile(
                data = allPcm,
                outputFile = outputFile,
                sampleRate = SAMPLE_RATE_HZ,
                format = AudioEncoder.OutputFormat.WAV
            )
        }

        Arrays.fill(allPcm, 0)
        onRecordingComplete?.invoke(outputFile)
    }

    private fun flattenPcmChunks(): ByteArray {
        if (pcmBuffer.isEmpty()) return ByteArray(0)

        val totalSize = pcmBuffer.sumOf { it.size }
        val output = ByteArrayOutputStream(totalSize)
        pcmBuffer.forEach { output.write(it) }
        return output.toByteArray()
    }

    private fun transitionToError(message: String) {
        finishInProgress.set(true)
        releaseAudioRecord()
        clearPcmBuffer()
        releaseWakeLock()
        unregisterInterruptionListeners()
        _state.value = RecordingState.Error(message)
        onError?.invoke(message)
    }

    private fun clearPcmBuffer() {
        pcmBuffer.forEach { Arrays.fill(it, 0) }
        pcmBuffer.clear()
    }

    private fun drainPreBuffer() {
        val bytes = preBuffer?.readAll() ?: return
        if (bytes.isNotEmpty()) {
            pcmBuffer.add(bytes)
        }
        preBuffer?.clear()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
            }
        }
        wakeLock?.takeIf { !it.isHeld }?.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun registerInterruptionListeners() {
        if (phoneStateListener == null) {
            phoneStateListener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    if (state == TelephonyManager.CALL_STATE_OFFHOOK ||
                        state == TelephonyManager.CALL_STATE_RINGING
                    ) {
                        scope.launch(Dispatchers.Main) {
                            stopForInterruption(InterruptionReason.PHONE_CALL)
                        }
                    }
                }
            }
        }

        try {
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (_: SecurityException) {
            // READ_PHONE_STATE may be denied; recording still proceeds without call interruption.
        }

        if (audioRoutingReceiver == null) {
            audioRoutingReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED,
                        AudioManager.ACTION_HEADSET_PLUG,
                        Intent.ACTION_HEADSET_PLUG -> {
                            scope.launch(Dispatchers.Main) {
                                stopForInterruption(InterruptionReason.BLUETOOTH_ROUTING_CHANGE)
                            }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(Intent.ACTION_HEADSET_PLUG)
        }

        try {
            ContextCompat.registerReceiver(
                context,
                audioRoutingReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {
            // Receiver registration failure should not crash IME.
        }
    }

    private fun unregisterInterruptionListeners() {
        phoneStateListener?.let { listener ->
            telephonyManager?.listen(listener, PhoneStateListener.LISTEN_NONE)
        }
        phoneStateListener = null

        audioRoutingReceiver?.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered.
            }
        }
        audioRoutingReceiver = null
    }

    private fun stopAudioRecordSafely() {
        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
            // Ignore; recorder may already be stopped.
        }
    }

    private fun releaseAudioRecord() {
        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
            // Ignore; recorder may already be stopped.
        } finally {
            audioRecord?.release()
            audioRecord = null
        }
    }
}
