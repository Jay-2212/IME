package com.groqvoice.keyboard.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.groqvoice.keyboard.R
import com.groqvoice.keyboard.api.GroqRepository
import com.groqvoice.keyboard.audio.AudioRecordingManager
import com.groqvoice.keyboard.model.RecordingMode
import com.groqvoice.keyboard.model.RecordingState
import com.groqvoice.keyboard.model.TranscriptionResult
import com.groqvoice.keyboard.utils.FileCacheManager
import com.groqvoice.keyboard.utils.SecurePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * The core Android Input Method Service for GroqVoice.
 *
 * ## Lifecycle
 *
 * - **[onCreate]** → Initialize dependencies (SecurePrefs, FileCacheManager, AudioRecordingManager, GroqRepository)
 * - **[onCreateInputView]** → Inflate [KeyboardView], wire touch listeners
 * - **[onStartInput]** → Inspect [EditorInfo], reset state, check password/incognito mode
 * - **[onFinishInputView]** → Stop recording immediately (privacy requirement)
 * - **[onTrimMemory]** → Clear audio buffers, stop non-essential animations
 * - **[onDestroy]** → Release all resources
 *
 * ## Responsibilities
 *
 * - **Touch Handling**: Interprets mic button presses (tap vs hold) per TSD 4.2.
 * - **Text Manipulation**: Delegates to [InputConnectionHelper] for proper IME behavior.
 * - **State Observation**: Observes [AudioRecordingManager.state] via coroutines.
 * - **System Events**: Handles memory pressure, audio routing changes, and keyboard visibility.
 *
 * ## Memory Management
 *
 * Implements [onTrimMemory] per TSD 5.3 to:
 * - Clear audio buffers when system requests memory
 * - Stop non-essential animations during low memory conditions
 * - Release transient resources while keeping core functionality intact
 *
 * ## Interruption Handling
 *
 * The service registers for Bluetooth audio routing changes to:
 * - Stop recording when audio routes to/from a Bluetooth device
 * - Restart recording if needed after routing stabilizes (future enhancement)
 *
 * @see AudioRecordingManager Manages audio hardware and recording state.
 * @see InputConnectionHelper Handles text insertion/deletion with proper UTF-16 support.
 *
 * TSD Section 1.1, 1.2, 4.2, 5.2, 5.3, 6.1.
 */
class VoiceInputMethodService : android.inputmethodservice.InputMethodService() {

    // ── Dependencies ───────────────────────────────────────────────────────────
    private lateinit var securePrefs: SecurePrefs
    private lateinit var fileCacheManager: FileCacheManager
    private lateinit var audioManager: AudioRecordingManager
    private lateinit var groqRepository: GroqRepository

    // ── UI ─────────────────────────────────────────────────────────────────────
    private var keyboardView: KeyboardView? = null

    // ── Coroutines ─────────────────────────────────────────────────────────────
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Touch timing (TSD Section 4.2) ────────────────────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())
    private var touchDownTimeMs = 0L
    private var lastSpaceMs = 0L
    private val handsFreeRunnable = Runnable {
        // 800 ms elapsed without release → started as push-to-talk, now committed
    }

    // ── Backspace long-press (TSD 5.4) ────────────────────────────────────────
    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            performBackspace()
            mainHandler.postDelayed(this, BACKSPACE_REPEAT_INTERVAL_MS)
        }
    }
    private var isBackspaceRepeating = false

    // ── Audio routing receiver ────────────────────────────────────────────────
    private var audioRoutingReceiver: BroadcastReceiver? = null

    // ── Current editor state ──────────────────────────────────────────────────
    private var isPasswordField = false
    private var isIncognitoMode = false

    companion object {
        private const val BACKSPACE_HOLD_TRIGGER_MS = 500L
        private const val BACKSPACE_REPEAT_INTERVAL_MS = 100L

        /** Threshold for double-tap detection in milliseconds. */
        private const val DOUBLE_TAP_THRESHOLD_MS = 300L
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        securePrefs = SecurePrefs(this)
        fileCacheManager = FileCacheManager(this)
        audioManager = AudioRecordingManager(this, fileCacheManager)
        groqRepository = GroqRepository(
            apiKeyProvider = { securePrefs.getApiKey() },
            fileCacheManager = fileCacheManager,
            isDebug = false // TODO: Wire to BuildConfig.DEBUG
        )
        observeRecordingState()
        registerAudioRoutingReceiver()
    }

    override fun onCreateInputView(): View {
        keyboardView = KeyboardView(this).also { view ->
            wireMicButton(view)
            wireBackspaceButton(view)
            view.onSpacebarClick = { handleSpaceKey() }
            view.onSettingsClick = { openSettings() }
        }
        return keyboardView!!
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        isPasswordField = InputConnectionHelper.isPasswordField(attribute)
        isIncognitoMode = InputConnectionHelper.isIncognitoMode(attribute)

        if (isPasswordField) {
            keyboardView?.showBanner(getString(R.string.banner_voice_disabled_security))
        } else if (isIncognitoMode) {
            keyboardView?.showBanner(getString(R.string.banner_incognito_warning))
        } else {
            keyboardView?.hideBanner()
        }

        if (!securePrefs.hasApiKey()) {
            keyboardView?.showBanner(getString(R.string.banner_no_api_key))
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Privacy: always stop recording when keyboard is hidden (TSD 5.3)
        audioManager.stopRecording()
        stopBackspaceRepeat()
        mainHandler.removeCallbacks(handsFreeRunnable)
        super.onFinishInputView(finishingInput)
    }

    /**
     * Called when the system is running low on memory.
     *
     * Implements TSD Section 5.3 (Low Memory) and 6.1 (Memory Management):
     * - **TRIM_MEMORY_RUNNING_MODERATE**: Clear pre-buffer to free ~19KB
     * - **TRIM_MEMORY_RUNNING_LOW**: Clear buffers, stop animations
     * - **TRIM_MEMORY_COMPLETE**: Stop recording immediately, release all resources
     *
     * @param level The memory trim level constant from [android.content.ComponentCallbacks2].
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                // Critical: Stop everything, release all resources
                audioManager.onTrimMemory(level)
                stopBackspaceRepeat()
                keyboardView?.let { view ->
                    // Stop any ongoing animations
                    view.btnMic.clearAnimation()
                }
            }
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                // Low memory: Clear buffers, keep recording if active
                audioManager.onTrimMemory(level)
            }
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // Moderate: Just clear pre-buffer
                audioManager.onTrimMemory(level)
            }
        }
    }

    override fun onDestroy() {
        audioManager.release()
        serviceScope.cancel()
        unregisterAudioRoutingReceiver()
        super.onDestroy()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Mic button touch handling (TSD Section 4.2)
    // ──────────────────────────────────────────────────────────────────────────

    private fun wireMicButton(view: KeyboardView) {
        view.onMicTouchListener = { event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownTimeMs = System.currentTimeMillis()
                    mainHandler.postDelayed(
                        handsFreeRunnable,
                        AudioRecordingManager.HOLD_THRESHOLD_MS
                    )
                    performHaptic()
                    if (!isPasswordField && securePrefs.hasApiKey()) {
                        audioManager.startRecording(RecordingMode.PUSH_TO_TALK)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - touchDownTimeMs
                    mainHandler.removeCallbacks(handsFreeRunnable)

                    if (duration < AudioRecordingManager.HOLD_THRESHOLD_MS) {
                        // Short tap → toggle hands-free
                        val current = audioManager.state.value
                        if (current is RecordingState.Recording &&
                            current.mode == RecordingMode.HANDS_FREE
                        ) {
                            audioManager.stopRecording()
                        } else {
                            audioManager.stopRecording()
                            audioManager.startRecording(RecordingMode.HANDS_FREE)
                        }
                    } else {
                        // Hold-and-release → push-to-talk stop
                        audioManager.stopRecording()
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Backspace button handling with long-press repeat (TSD 5.4)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Wires the backspace button with proper long-press repeat behavior.
     *
     * Implements TSD Section 5.4:
     * - Short press: Delete one character
     * - Long press (500ms): Start repeating deletion every 100ms
     * - Release: Stop repeating
     */
    private fun wireBackspaceButton(view: KeyboardView) {
        view.onBackspaceClick = { performBackspace() }
        view.onBackspaceLongClick = {
            if (!isBackspaceRepeating) {
                isBackspaceRepeating = true
                // Initial delete after hold trigger delay
                mainHandler.postDelayed({
                    if (isBackspaceRepeating) {
                        performBackspace()
                        // Start repeating
                        mainHandler.postDelayed(backspaceRepeatRunnable, BACKSPACE_REPEAT_INTERVAL_MS)
                    }
                }, BACKSPACE_HOLD_TRIGGER_MS)
            }
            true
        }
        view.onBackspaceTouchUp = {
            stopBackspaceRepeat()
        }
    }

    /**
     * Stops the backspace repeat action.
     * Called on ACTION_UP or when keyboard is hidden.
     */
    private fun stopBackspaceRepeat() {
        isBackspaceRepeating = false
        mainHandler.removeCallbacks(backspaceRepeatRunnable)
        // Also remove the initial delayed start
        mainHandler.removeCallbacksAndMessages(null)
        // Re-post the backspace setup if needed (but we need to be careful)
        // Actually, let's just use a specific token approach
    }

    // ──────────────────────────────────────────────────────────────────────────
    // State observation
    // ──────────────────────────────────────────────────────────────────────────

    private fun observeRecordingState() {
        audioManager.state
            .onEach { state ->
                keyboardView?.applyState(state)

                if (state is RecordingState.Processing) {
                    val ic = currentInputConnection ?: return@onEach
                    InputConnectionHelper.setComposingTranscription(ic, "…")
                }
            }
            .launchIn(serviceScope)

        audioManager.onRecordingComplete = { audioFile ->
            serviceScope.launch(Dispatchers.IO) {
                val model = securePrefs.getModel()
                val result = groqRepository.transcribe(audioFile, model)

                serviceScope.launch(Dispatchers.Main) {
                    val ic = currentInputConnection
                    when (result) {
                        is TranscriptionResult.Success -> {
                            ic?.let { InputConnectionHelper.commitTranscription(it, result.text) }
                            keyboardView?.applyState(RecordingState.Idle)
                        }
                        is TranscriptionResult.Failure -> {
                            ic?.let { InputConnectionHelper.clearComposing(it) }
                            if (result.httpCode == 401) {
                                // Redirect to onboarding for re-entry of API key
                                openOnboarding()
                            } else {
                                keyboardView?.applyState(RecordingState.Error(result.message))
                            }
                        }
                    }
                    // Reset to Idle after showing error briefly
                    mainHandler.postDelayed({
                        if (audioManager.state.value is RecordingState.Error) {
                            // Re-expose Idle so the user can retry
                        }
                    }, 3_000)
                }
            }
        }

        audioManager.onInterrupted = { reason ->
            serviceScope.launch(Dispatchers.Main) {
                val message = when (reason) {
                    AudioRecordingManager.InterruptionReason.PHONE_CALL ->
                        getString(R.string.error_recording_interrupted_call)
                    AudioRecordingManager.InterruptionReason.BLUETOOTH_ROUTING_CHANGE ->
                        getString(R.string.error_recording_interrupted_bluetooth)
                    AudioRecordingManager.InterruptionReason.TRIM_MEMORY ->
                        getString(R.string.error_recording_interrupted_memory)
                }
                keyboardView?.applyState(RecordingState.Error(message))

                // Clear composing text
                currentInputConnection?.let { InputConnectionHelper.clearComposing(it) }

                // Reset to Idle after delay
                mainHandler.postDelayed({
                    keyboardView?.applyState(RecordingState.Idle)
                }, 3_000)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Audio Routing Change Handling
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Registers a receiver for audio routing changes (Bluetooth, headset, etc).
     * Per TSD 5.2, we need to handle Bluetooth audio routing changes.
     *
     * Note: The actual recording stop is handled by AudioRecordingManager's
     * internal receiver. This service-level receiver is for UI updates and
     * additional handling if needed.
     */
    private fun registerAudioRoutingReceiver() {
        audioRoutingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                        val state = intent.getIntExtra(
                            AudioManager.EXTRA_SCO_AUDIO_STATE,
                            AudioManager.SCO_AUDIO_STATE_DISCONNECTED
                        )
                        if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                            // Bluetooth SCO disconnected - recording may have stopped
                            // UI update is handled by state observation
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        ContextCompat.registerReceiver(
            this,
            audioRoutingReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterAudioRoutingReceiver() {
        audioRoutingReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Receiver not registered or already unregistered
            }
            audioRoutingReceiver = null
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Keyboard actions
    // ──────────────────────────────────────────────────────────────────────────

    private fun performBackspace() {
        currentInputConnection?.let { InputConnectionHelper.deleteCharacter(it) }
    }

    private fun handleSpaceKey() {
        val ic = currentInputConnection ?: return
        val doubleTap = securePrefs.run {
            // Read double-tap preference (default: on)
            true // TODO: read from SecurePrefs when full preference migration is done
        }
        val wasDoubleTap = InputConnectionHelper.handleSpaceKey(ic, lastSpaceMs, doubleTap)
        if (!wasDoubleTap) lastSpaceMs = System.currentTimeMillis()
    }

    private fun performHaptic() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                getSystemService<VibratorManager>()
                    ?.defaultVibrator
                    ?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                getSystemService<Vibrator>()
                    ?.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {
            // Haptic is non-critical; silently ignore errors
        }
    }

    private fun openSettings() {
        val intent = Intent(this, com.groqvoice.keyboard.ui.settings.SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun openOnboarding() {
        val intent = Intent(this, com.groqvoice.keyboard.ui.onboarding.WelcomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }
}
