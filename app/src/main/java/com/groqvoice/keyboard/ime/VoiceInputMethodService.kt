package com.groqvoice.keyboard.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.google.android.material.color.DynamicColors
import com.groqvoice.keyboard.BuildConfig
import com.groqvoice.keyboard.R
import com.groqvoice.keyboard.api.AndroidNetworkStatusProvider
import com.groqvoice.keyboard.api.GroqRepository
import com.groqvoice.keyboard.api.WorkManagerTranscriptionRetryScheduler
import com.groqvoice.keyboard.audio.AudioRecordingManager
import com.groqvoice.keyboard.model.RecordingMode
import com.groqvoice.keyboard.model.RecordingState
import com.groqvoice.keyboard.model.TranscriptionResult
import com.groqvoice.keyboard.utils.AuditLogger
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
 * Android IME service that orchestrates touch input, recording, and transcription delivery.
 *
 * Phase 3 updates:
 * - Handles queued uploads (`TranscriptionResult.Queued`) from WorkManager fallback.
 * - Enforces incognito/password no-cloud behavior before recording starts.
 * - Fixes backspace repeat lifecycle so unrelated handler callbacks are not cancelled.
 * - Uses secure preference-backed toggles for haptics and double-tap period.
 */
class VoiceInputMethodService : android.inputmethodservice.InputMethodService() {

    private lateinit var securePrefs: SecurePrefs
    private lateinit var fileCacheManager: FileCacheManager
    private lateinit var audioManager: AudioRecordingManager
    private lateinit var groqRepository: GroqRepository
    private lateinit var auditLogger: AuditLogger

    private var keyboardView: KeyboardView? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var touchDownTimeMs = 0L
    private var lastMicInteractionMs = 0L
    private var ignoreCurrentMicTouch = false
    private var didStartPushToTalkForTouch = false
    private var lastSpaceMs = 0L
    private val handsFreeRunnable = Runnable {
        if (ignoreCurrentMicTouch || shouldBlockVoiceInput()) return@Runnable
        didStartPushToTalkForTouch = true
        audioManager.startRecording(RecordingMode.PUSH_TO_TALK)
    }

    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            performBackspace()
            mainHandler.postDelayed(this, BACKSPACE_REPEAT_INTERVAL_MS)
        }
    }
    private var backspaceStartRunnable: Runnable? = null
    private var isBackspaceRepeating = false

    private var audioRoutingReceiver: BroadcastReceiver? = null

    private var isPasswordField = false
    private var isIncognitoMode = false

    companion object {
        private const val BACKSPACE_HOLD_TRIGGER_MS = 350L
        private const val BACKSPACE_REPEAT_INTERVAL_MS = 60L
    }

    override fun onCreate() {
        super.onCreate()
        securePrefs = SecurePrefs(this)
        fileCacheManager = FileCacheManager(this)
        auditLogger = AuditLogger(this)
        audioManager = AudioRecordingManager(this, fileCacheManager)
        groqRepository = GroqRepository(
            apiKeyProvider = { securePrefs.getApiKey() },
            fileCacheManager = fileCacheManager,
            retryScheduler = WorkManagerTranscriptionRetryScheduler(this),
            networkStatusProvider = AndroidNetworkStatusProvider(this),
            isDebug = BuildConfig.DEBUG
        )
        observeRecordingState()
        registerAudioRoutingReceiver()
    }

    override fun onCreateInputView(): View {
        val dynamicContext = DynamicColors.wrapContextIfAvailable(this)
        val themedContext = ContextThemeWrapper(dynamicContext, R.style.Theme_GroqVoiceKeyboard)
        keyboardView = KeyboardView(themedContext).also { view ->
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

        when {
            isPasswordField -> keyboardView?.showBanner(getString(R.string.banner_voice_disabled_security))
            isIncognitoMode -> keyboardView?.showBanner(getString(R.string.banner_incognito_warning))
            !securePrefs.hasApiKey() -> keyboardView?.showBanner(getString(R.string.banner_no_api_key))
            else -> keyboardView?.hideBanner()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        audioManager.cancelRecordingForModeSwitch()
        stopBackspaceRepeat()
        mainHandler.removeCallbacks(handsFreeRunnable)
        super.onFinishInputView(finishingInput)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        audioManager.onTrimMemory(level)
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            stopBackspaceRepeat()
            keyboardView?.btnMic?.clearAnimation()
        }
    }

    override fun onDestroy() {
        audioManager.release()
        serviceScope.cancel()
        unregisterAudioRoutingReceiver()
        super.onDestroy()
    }

    private fun wireMicButton(view: KeyboardView) {
        view.onMicTouchListener = { event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val now = System.currentTimeMillis()
                    if (now - lastMicInteractionMs < AudioRecordingManager.DEBOUNCE_MS) {
                        ignoreCurrentMicTouch = true
                        true
                    } else {
                        ignoreCurrentMicTouch = false
                        lastMicInteractionMs = now

                        touchDownTimeMs = System.currentTimeMillis()
                        didStartPushToTalkForTouch = false

                        if (shouldBlockVoiceInput()) {
                            showVoiceInputBlockedBanner()
                        } else {
                            performHaptic()
                            mainHandler.postDelayed(
                                handsFreeRunnable,
                                AudioRecordingManager.HOLD_THRESHOLD_MS
                            )
                        }
                        true
                    }
                }

                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(handsFreeRunnable)
                    if (ignoreCurrentMicTouch) {
                        ignoreCurrentMicTouch = false
                        didStartPushToTalkForTouch = false
                        true
                    } else {
                        if (!shouldBlockVoiceInput()) {
                            val durationMs = System.currentTimeMillis() - touchDownTimeMs

                            if (durationMs < AudioRecordingManager.HOLD_THRESHOLD_MS) {
                                val current = audioManager.state.value
                                val isHandsFreeRunning = current is RecordingState.Recording &&
                                    current.mode == RecordingMode.HANDS_FREE

                                if (isHandsFreeRunning) {
                                    audioManager.stopRecording()
                                } else {
                                    audioManager.startRecording(RecordingMode.HANDS_FREE)
                                }
                            } else if (didStartPushToTalkForTouch) {
                                audioManager.stopRecording()
                            }
                        }
                        didStartPushToTalkForTouch = false
                        true
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(handsFreeRunnable)
                    ignoreCurrentMicTouch = false
                    if (didStartPushToTalkForTouch) {
                        audioManager.cancelRecordingForModeSwitch()
                    }
                    didStartPushToTalkForTouch = false
                    true
                }

                else -> false
            }
        }
    }

    private fun wireBackspaceButton(view: KeyboardView) {
        view.onBackspaceTouchListener = { event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    performBackspace()
                    startBackspaceRepeat()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val isInsideButton = event.x >= 0f &&
                            event.x <= view.btnBackspace.width &&
                            event.y >= 0f &&
                            event.y <= view.btnBackspace.height
                    if (!isInsideButton) {
                        stopBackspaceRepeat()
                    }
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    stopBackspaceRepeat()
                    true
                }

                else -> false
            }
        }
    }

    private fun startBackspaceRepeat() {
        stopBackspaceRepeat()
        isBackspaceRepeating = true
        backspaceStartRunnable = Runnable {
            if (!isBackspaceRepeating) return@Runnable
            mainHandler.post(backspaceRepeatRunnable)
        }.also { runnable ->
            mainHandler.postDelayed(runnable, BACKSPACE_HOLD_TRIGGER_MS)
        }
    }

    private fun stopBackspaceRepeat() {
        isBackspaceRepeating = false
        backspaceStartRunnable?.let { mainHandler.removeCallbacks(it) }
        backspaceStartRunnable = null
        mainHandler.removeCallbacks(backspaceRepeatRunnable)
    }

    private fun observeRecordingState() {
        audioManager.state
            .onEach { state ->
                keyboardView?.applyState(state)
            }
            .launchIn(serviceScope)

        audioManager.onRecordingComplete = { audioFile ->
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val result = groqRepository.transcribe(
                        audioFile = audioFile,
                        model = securePrefs.getModel()
                    )

                    serviceScope.launch(Dispatchers.Main) {
                        val ic = currentInputConnection
                        when (result) {
                            is TranscriptionResult.Success -> {
                                ic?.let { InputConnectionHelper.commitTranscription(it, result.text) }
                                if (result.isPartial) {
                                    keyboardView?.showBanner(
                                        result.warning ?: getString(R.string.banner_partial_transcription)
                                    )
                                } else {
                                    keyboardView?.hideBanner()
                                    keyboardView?.playSuccessAnimation()
                                }
                                auditLogger.logTranscription()
                                audioManager.completeProcessing()
                            }

                            is TranscriptionResult.Queued -> {
                                ic?.let { InputConnectionHelper.clearComposing(it) }
                                keyboardView?.showBanner(getString(R.string.banner_no_network))
                                audioManager.completeProcessing()
                            }

                            is TranscriptionResult.Failure -> {
                                ic?.let { InputConnectionHelper.clearComposing(it) }
                                if (result.httpCode == 401) {
                                    securePrefs.clearApiKey()
                                    openOnboarding()
                                    keyboardView?.showBanner(getString(R.string.banner_no_api_key))
                                    audioManager.completeProcessing()
                                } else if (result.isQuotaExceeded) {
                                    showTransientError(getString(R.string.banner_quota_exceeded))
                                } else {
                                    showTransientError(result.message)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    serviceScope.launch(Dispatchers.Main) {
                        currentInputConnection?.let { InputConnectionHelper.clearComposing(it) }
                        showTransientError("Transcription failed unexpectedly.")
                    }
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
                currentInputConnection?.let { InputConnectionHelper.clearComposing(it) }
                showTransientError(message)
            }
        }

        audioManager.onError = { message ->
            serviceScope.launch(Dispatchers.Main) {
                currentInputConnection?.let { InputConnectionHelper.clearComposing(it) }
                showTransientError(message)
            }
        }
    }

    private fun showTransientError(message: String) {
        keyboardView?.applyState(RecordingState.Error(message))
        mainHandler.postDelayed(
            {
                keyboardView?.hideBanner()
                audioManager.completeProcessing()
                keyboardView?.applyState(RecordingState.Idle)
            },
            3_000L
        )
    }

    private fun shouldBlockVoiceInput(): Boolean {
        return isPasswordField || isIncognitoMode || !securePrefs.hasApiKey()
    }

    private fun showVoiceInputBlockedBanner() {
        when {
            isPasswordField ->
                keyboardView?.showBanner(getString(R.string.banner_voice_disabled_security))
            isIncognitoMode ->
                keyboardView?.showBanner(getString(R.string.banner_incognito_warning))
            !securePrefs.hasApiKey() ->
                keyboardView?.showBanner(getString(R.string.banner_no_api_key))
        }
    }

    private fun registerAudioRoutingReceiver() {
        audioRoutingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) {
                    // UI updates are already driven by recording state callbacks.
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
                // Receiver may already be unregistered.
            }
            audioRoutingReceiver = null
        }
    }

    private fun performBackspace() {
        currentInputConnection?.let { InputConnectionHelper.deleteCharacter(it) }
    }

    private fun handleSpaceKey() {
        val ic = currentInputConnection ?: return
        val doubleTapEnabled = securePrefs.isDoubleTapPeriodEnabled()
        val wasDoubleTap = InputConnectionHelper.handleSpaceKey(ic, lastSpaceMs, doubleTapEnabled)
        if (!wasDoubleTap) {
            lastSpaceMs = System.currentTimeMillis()
        }
    }

    private fun performHaptic() {
        if (!securePrefs.isHapticFeedbackEnabled()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService<VibratorManager>()
                    ?.defaultVibrator
                    ?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                getSystemService<Vibrator>()
                    ?.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {
            // Haptics are optional UX; ignore runtime failures.
        }
    }

    private fun openSettings() {
        startActivity(
            Intent(this, com.groqvoice.keyboard.ui.settings.SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun openOnboarding() {
        startActivity(
            Intent(this, com.groqvoice.keyboard.ui.onboarding.WelcomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }
}
