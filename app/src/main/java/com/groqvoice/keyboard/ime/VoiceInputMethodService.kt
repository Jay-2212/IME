package com.groqvoice.keyboard.ime

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
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
 * Lifecycle:
 *  - [onCreateInputView]  → inflate [KeyboardView], wire listeners
 *  - [onStartInput]       → inspect [EditorInfo], reset state
 *  - [onFinishInputView]  → stop recording immediately (privacy)
 *  - [onDestroy]          → release all resources
 *
 * Delegates audio recording to [AudioRecordingManager] and text insertion to
 * [InputConnectionHelper].  Coroutine scope is tied to the service lifecycle.
 *
 * TSD Section 1.1, 1.2, 4.2, 5.3.
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

    // ── Current editor state ──────────────────────────────────────────────────
    private var isPasswordField = false
    private var isIncognitoMode = false

    companion object {
        private const val BACKSPACE_HOLD_TRIGGER_MS = 500L
        private const val BACKSPACE_REPEAT_INTERVAL_MS = 100L
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        securePrefs = SecurePrefs(this)
        fileCacheManager = FileCacheManager(this)
        audioManager = AudioRecordingManager(fileCacheManager)
        groqRepository = GroqRepository(
            apiKeyProvider = { securePrefs.getApiKey() },
            fileCacheManager = fileCacheManager,
            isDebug = false // TODO: Wire to BuildConfig.DEBUG
        )
        observeRecordingState()
    }

    override fun onCreateInputView(): View {
        keyboardView = KeyboardView(this).also { view ->
            wireMicButton(view)
            view.onBackspaceClick = { performBackspace() }
            view.onBackspaceLongClick = {
                mainHandler.postDelayed(backspaceRepeatRunnable, BACKSPACE_HOLD_TRIGGER_MS)
                true
            }
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
        mainHandler.removeCallbacks(handsFreeRunnable)
        mainHandler.removeCallbacks(backspaceRepeatRunnable)
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        audioManager.release()
        serviceScope.cancel()
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
