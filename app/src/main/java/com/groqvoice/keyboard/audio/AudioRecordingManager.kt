package com.groqvoice.keyboard.audio

import android.content.BroadcastReceiver
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the full audio recording lifecycle including the state machine,
 * AudioRecord configuration, hands-free VAD integration, and system interruption handling.
 *
 * ## Responsibilities
 *
 * - **State Machine**: Owns a [StateFlow] of [RecordingState] that UI layers observe.
 * - **Audio Capture**: Configures [AudioRecord] with optimal settings for Groq Whisper.
 * - **Pre-buffering**: Uses [CircularByteBuffer] for 300ms lookback in hands-free mode.
 * - **Power Management**: Acquires [PowerManager.WakeLock] during hands-free recording.
 * - **Interruption Handling**: Monitors phone calls (TelephonyManager) and audio routing
 *   changes (Bluetooth) to auto-stop recording for privacy and correctness.
 *
 * ## Thread Safety
 *
 * All public methods are thread-safe. Recording runs on a dedicated [Dispatchers.IO]
 * coroutine. State updates are posted to the [StateFlow] which is thread-safe.
 *
 * ## System Interruptions
 *
 * Per TSD Section 5.2, the manager handles:
 * - **Phone Calls**: Recording stops immediately when call state changes to OFFHOOK.
 * - **Bluetooth Routing**: Recording stops when audio routing changes to/from BT device.
 * - **Low Memory**: External [onTrimMemory] callback clears buffers without stopping.
 *
 * ## Security
 *
 * - PCM buffers are zero-filled after use ([Arrays.fill]) per TSD 7.1.
 * - Wake lock is [PowerManager.PARTIAL_WAKE_LOCK] only (no screen wake).
 * - Recording stops automatically when keyboard is hidden (handled by service).
 *
 * @param context Application context for system service access.
 * @param fileCacheManager Manages temporary audio file creation/deletion.
 * @param vad Voice Activity Detector for hands-free silence detection.
 *
 * TSD Section 4.1, 4.2, 4.3, 5.2, 6.1, 6.3, 7.1.
 */
class AudioRecordingManager(
    private val context: Context,
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

        /** Pre-buffer duration for hands-free mode (TSD 4.3). */
        private const val PREBUFFER_DURATION_MS = 300L

        /** Wake lock tag for identification in dumpsys. */
        private const val WAKE_LOCK_TAG = "GroqVoice::RecordingLock"

        /** Wake lock timeout as safety net (max duration + 30s buffer). */
        private const val WAKE_LOCK_TIMEOUT_MS = MAX_DURATION_MS + 30_000L
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

    // ── Pre-buffering for hands-free mode ──────────────────────────────────────
    private var preBuffer: CircularByteBuffer? = null
    private var isPreBuffering = AtomicBoolean(false)

    // ── Power management ───────────────────────────────────────────────────────
    private val powerManager = ContextCompat.getSystemService(context, PowerManager::class.java)
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Telephony interruption handling ────────────────────────────────────────
    private val telephonyManager = ContextCompat.getSystemService(context, TelephonyManager::class.java)
    private var phoneStateListener: PhoneStateListener? = null

    // ── Bluetooth audio routing ────────────────────────────────────────────────
    private val audioManager = ContextCompat.getSystemService(context, AudioManager::class.java)
    private var audioRoutingReceiver: BroadcastReceiver? = null

    // ── Callbacks ──────────────────────────────────────────────────────────────
    /** Called on the IO thread once recording stops and encoding is complete. */
    var onRecordingComplete: ((File) -> Unit)? = null

    /** Called when an error occurs; passes a user-facing message. */
    var onError: ((String) -> Unit)? = null

    /** Called when recording is interrupted by phone call or routing change. */
    var onInterrupted: ((InterruptionReason) -> Unit)? = null

    // ── Debounce ───────────────────────────────────────────────────────────────
    private var lastInteractionMs = 0L

    // ── Recording configuration ────────────────────────────────────────────────
    private var currentRecordingMode: RecordingMode? = null

    /**
     * Enum representing reasons for recording interruption.
     */
    enum class InterruptionReason {
        PHONE_CALL,
        BLUETOOTH_ROUTING_CHANGE,
        TRIM_MEMORY
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Starts a new recording session.
     * A debounce guard prevents rapid taps from corrupting the state machine (TSD 5.4).
     *
     * For [RecordingMode.HANDS_FREE], this method also:
     * - Acquires a partial wake lock to prevent Doze mode (TSD 6.3)
     * - Starts pre-buffering if not already active
     * - Registers phone state and audio routing listeners (TSD 5.2)
     *
     * @param mode [RecordingMode.PUSH_TO_TALK] or [RecordingMode.HANDS_FREE].
     */
    fun startRecording(mode: RecordingMode) {
        val now = System.currentTimeMillis()
        if (now - lastInteractionMs < DEBOUNCE_MS) return
        lastInteractionMs = now

        if (_state.value !is RecordingState.Idle) return

        currentRecordingMode = mode

        // For hands-free: acquire wake lock and start pre-buffering
        if (mode == RecordingMode.HANDS_FREE) {
            acquireWakeLock()
            startPreBuffering()
            registerInterruptionListeners()
        }

        _state.value = RecordingState.Recording(mode, now)
        recordingJob = scope.launch { doRecord(mode) }
    }

    /**
     * Signals the recording loop to stop and triggers encoding.
     * No-op if not currently recording.
     *
     * This method also releases the wake lock and unregisters interruption listeners.
     */
    fun stopRecording() {
        val now = System.currentTimeMillis()
        if (now - lastInteractionMs < DEBOUNCE_MS) return
        lastInteractionMs = now

        if (_state.value !is RecordingState.Recording) return

        recordingJob?.cancel()
        finishRecording()
    }

    /**
     * Stops recording due to system interruption (phone call, BT routing, etc).
     * Discards the recording buffer for privacy (TSD 5.2).
     *
     * @param reason The cause of interruption for logging/telemetry.
     */
    fun stopForInterruption(reason: InterruptionReason) {
        if (_state.value !is RecordingState.Recording) return

        recordingJob?.cancel()

        // Zero-fill and discard buffers (privacy)
        clearPcmBuffer()
        preBuffer?.clear()

        // Release resources
        releaseAudioRecord()
        releaseWakeLock()
        unregisterInterruptionListeners()

        _state.value = RecordingState.Idle
        onInterrupted?.invoke(reason)
    }

    /**
     * Releases all hardware resources. Call from service [onDestroy].
     * After calling this method, the manager should not be reused.
     */
    fun release() {
        recordingJob?.cancel()
        releaseAudioRecord()
        releaseWakeLock()
        unregisterInterruptionListeners()
        stopPreBuffering()
        clearPcmBuffer()
        preBuffer?.close()
        preBuffer = null
    }

    /**
     * Called by the system when memory is low.
     * Clears pre-buffer and non-essential data without stopping active recording.
     * If recording is active in hands-free mode, the recording continues but
     * pre-buffer is discarded to free memory.
     *
     * @param level The memory trim level from [android.content.ComponentCallbacks2].
     */
    fun onTrimMemory(level: Int) {
        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                // Critical memory: clear everything
                stopForInterruption(InterruptionReason.TRIM_MEMORY)
            }
            else -> {
                // Moderate memory pressure: just clear pre-buffer
                preBuffer?.clear()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Pre-buffering (Hands-Free Mode)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Initializes and starts the circular pre-buffer for hands-free mode.
     * The pre-buffer continuously captures audio, allowing 300ms lookback
     * when the user starts speaking (TSD 4.3).
     */
    private fun startPreBuffering() {
        if (isPreBuffering.compareAndSet(false, true)) {
            if (preBuffer == null || preBuffer!!.closed) {
                preBuffer = CircularByteBuffer()
            }
            // Pre-buffering runs in a separate coroutine
            scope.launch { doPreBuffering() }
        }
    }

    /**
     * Stops the pre-buffering coroutine and releases the buffer.
     */
    private fun stopPreBuffering() {
        isPreBuffering.set(false)
    }

    /**
     * Continuously writes small audio chunks to the circular buffer.
     * This runs until [isPreBuffering] is set to false.
     */
    private fun doPreBuffering() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * BUFFER_MULTIPLIER

        val preBufferRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (preBufferRecord.state != AudioRecord.STATE_INITIALIZED) {
            preBufferRecord.release()
            return
        }

        preBufferRecord.startRecording()
        val chunk = ByteArray(bufferSize / 4) // Smaller chunks for pre-buffering

        while (isPreBuffering.get() && !Thread.currentThread().isInterrupted) {
            val read = preBufferRecord.read(chunk, 0, chunk.size)
            if (read > 0) {
                preBuffer?.write(chunk.copyOf(read))
            }
        }

        preBufferRecord.stop()
        preBufferRecord.release()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Interruption Handling (Phone Calls & Bluetooth)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Registers listeners for phone state and audio routing changes.
     * Called when hands-free recording starts.
     */
    private fun registerInterruptionListeners() {
        // Register phone state listener
        phoneStateListener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK,
                    TelephonyManager.CALL_STATE_RINGING -> {
                        // Stop recording immediately when call starts or rings
                        scope.launch(Dispatchers.Main) {
                            stopForInterruption(InterruptionReason.PHONE_CALL)
                        }
                    }
                }
            }
        }

        telephonyManager?.listen(
            phoneStateListener,
            PhoneStateListener.LISTEN_CALL_STATE
        )

        // Register Bluetooth audio routing receiver
        audioRoutingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED,
                    AudioManager.ACTION_HEADSET_PLUG,
                    Intent.ACTION_HEADSET_PLUG -> {
                        // Audio routing changed - stop recording to avoid corruption
                        scope.launch(Dispatchers.Main) {
                            stopForInterruption(InterruptionReason.BLUETOOTH_ROUTING_CHANGE)
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

        ContextCompat.registerReceiver(
            context,
            audioRoutingReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /**
     * Unregisters interruption listeners.
     * Called when recording stops or service is destroyed.
     */
    private fun unregisterInterruptionListeners() {
        phoneStateListener?.let { listener ->
            telephonyManager?.listen(listener, PhoneStateListener.LISTEN_NONE)
            phoneStateListener = null
        }

        audioRoutingReceiver?.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Receiver not registered or already unregistered
            }
            audioRoutingReceiver = null
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Wake Lock Management
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Acquires a partial wake lock for hands-free recording.
     * Uses [PowerManager.PARTIAL_WAKE_LOCK] to keep CPU running during Doze
     * without waking the screen (TSD 6.3).
     *
     * The wake lock has a timeout as a safety net to prevent battery drain
     * if release() is not called.
     */
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            )?.apply {
                setReferenceCounted(false)
            }
        }

        wakeLock?.takeIf { !it.isHeld }?.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    /**
     * Releases the wake lock if held.
     */
    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Recording Core
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

        // For hands-free mode, drain pre-buffer into main buffer
        if (mode == RecordingMode.HANDS_FREE) {
            drainPreBuffer()
        }

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

        releaseAudioRecord()
        finishRecording()
    }

    /**
     * Drains the pre-buffer into the main PCM buffer when hands-free recording starts.
     * This captures the 300ms of audio before speech was detected.
     */
    private fun drainPreBuffer() {
        val preBufferData = preBuffer?.readAll() ?: return
        if (preBufferData.isNotEmpty()) {
            pcmBuffer.add(preBufferData)
        }
        preBuffer?.clear()
    }

    private fun finishRecording() {
        _state.value = RecordingState.Processing

        // Stop pre-buffering if it was active
        stopPreBuffering()

        // Release wake lock and listeners
        releaseWakeLock()
        unregisterInterruptionListeners()

        val allPcm = pcmBuffer.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        clearPcmBuffer()

        val outputFile = fileCacheManager.createTempWavFile()
        AudioEncoder.writePcmToWav(allPcm, outputFile, SAMPLE_RATE_HZ)

        // Zero-fill PCM data after encoding for security
        Arrays.fill(allPcm, 0)

        onRecordingComplete?.invoke(outputFile)
    }

    private fun transitionToError(message: String) {
        releaseAudioRecord()
        clearPcmBuffer()
        releaseWakeLock()
        unregisterInterruptionListeners()
        stopPreBuffering()
        _state.value = RecordingState.Error(message)
        onError?.invoke(message)
    }

    private fun releaseAudioRecord() {
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    /** Zero-fills all PCM chunks before clearing to reduce sensitive data in memory (TSD 7.1). */
    private fun clearPcmBuffer() {
        pcmBuffer.forEach { Arrays.fill(it, 0) }
        pcmBuffer.clear()
    }
}
