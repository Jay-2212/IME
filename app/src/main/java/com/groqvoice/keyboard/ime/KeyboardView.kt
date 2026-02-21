package com.groqvoice.keyboard.ime

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.groqvoice.keyboard.R
import com.groqvoice.keyboard.model.RecordingMode
import com.groqvoice.keyboard.model.RecordingState

/**
 * Custom [FrameLayout] that inflates [R.layout.keyboard_view] and wires up
 * all UI state transitions for the mic button, backspace, and spacebar.
 *
 * ## Responsibilities
 *
 * - **Visual State Management**: Updates button colors, labels, and visibility
 *   based on [RecordingState] (Idle, Recording, Processing, Error).
 * - **Touch Event Delegation**: Delegates mic button touch events to
 *   [onMicTouchListener] so that [VoiceInputMethodService] can control the
 *   state machine without tight coupling to the view.
 * - **Backspace Repeat Support**: Delegates full backspace touch events to
 *   [onBackspaceTouchListener] so service logic can implement robust repeat behavior.
 *
 * ## Button States
 *
 * Per TSD Section 3.2, the mic button has five visual states:
 * 1. **Idle**: Secondary color (#0F3460), subtle pulse animation
 * 2. **Recording (Push-to-Talk)**: Primary color (#E94560), scale 1.1x
 * 3. **Recording (Hands-Free)**: Primary color with rotating gradient border
 * 4. **Processing**: Gray (#4A4A6A), circular progress indicator
 * 5. **Error**: Red shake animation + haptic feedback
 *
 * ## Layout Structure
 *
 * ```
 * ┌─────────────────────────────────────┐
 * │  [Transcription Preview Strip]      │ 48dp
 * ├─────────────────────────────────────┤
 * │                                     │
 * │         [MIC BUTTON]                │ 120dp FAB
 * │                                     │
 * │   [BACKSPACE]       [SPACEBAR]      │ Bottom row
 * │   [SETTINGS GEAR]                   │
 * └─────────────────────────────────────┘
 * ```
 *
 * @see VoiceInputMethodService The IME service that owns and controls this view.
 *
 * TSD Section 3.2, 3.3, 4.2.
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    // ── View references ────────────────────────────────────────────────────────
    val btnMic: FloatingActionButton
    val btnBackspace: MaterialButton
    val btnSpacebar: MaterialButton
    val btnSettings: MaterialButton
    private val keyboardRoot: View
    private val bottomRow: View
    private val stateLabel: TextView
    private val transcriptionPreviewText: TextView
    private val transcriptionPreviewContainer: View
    private val bannerMessage: TextView
    private var pulseAnimatorSet: AnimatorSet? = null
    private var hasPlayedEntryAnimation = false

    // ── Listeners exposed to VoiceInputMethodService ───────────────────────────
    var onMicTouchListener: ((event: MotionEvent) -> Boolean)? = null
    var onBackspaceTouchListener: ((event: MotionEvent) -> Boolean)? = null
    var onSpacebarClick: (() -> Unit)? = null
    var onSettingsClick: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.keyboard_view, this, true)

        btnMic = findViewById(R.id.btn_mic)
        btnBackspace = findViewById(R.id.btn_backspace)
        btnSpacebar = findViewById(R.id.btn_spacebar)
        btnSettings = findViewById(R.id.btn_settings)
        keyboardRoot = findViewById(R.id.keyboard_root)
        bottomRow = findViewById(R.id.bottom_row)
        stateLabel = findViewById(R.id.state_label)
        transcriptionPreviewText = findViewById(R.id.transcription_preview_text)
        transcriptionPreviewContainer = findViewById(R.id.transcription_preview_container)
        bannerMessage = findViewById(R.id.banner_message)

        applyMaterialYouStyling()
        setupListeners()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyMaterialYouStyling()
        if (!hasPlayedEntryAnimation) {
            playEntryAnimation()
            hasPlayedEntryAnimation = true
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Updates all visual elements to reflect the new [RecordingState].
     * Must be called on the main thread.
     *
     * ## State Mapping (TSD Section 3.2)
     *
     * | State | Mic Color | State Label | Animations |
     * |-------|-----------|-------------|------------|
     * | Idle | Secondary | "Tap or hold to speak" | Pulse (subtle) |
     * | Recording (PTT) | Primary | "Recording..." | Scale 1.1x |
     * | Recording (HF) | Primary + Border | "Listening..." | Rotating gradient |
     * | Processing | Disabled | "Processing..." | Circular progress |
     * | Error | Error | "Error" | Shake + haptic |
     *
     * @param state The current recording state from AudioRecordingManager.
     */
    fun applyState(state: RecordingState) {
        cancelMicAnimations(resetScale = true)

        when (state) {
            is RecordingState.Idle -> {
                btnMic.backgroundTintList = ColorStateList.valueOf(
                    resolveColor(
                        com.google.android.material.R.attr.colorSecondary,
                        R.color.accent_secondary
                    )
                )
                btnMic.imageTintList = ColorStateList.valueOf(
                    resolveColor(
                        com.google.android.material.R.attr.colorOnSecondary,
                        R.color.on_accent_secondary
                    )
                )
                stateLabel.text = context.getString(R.string.state_idle)
                stateLabel.setTextColor(
                    resolveColor(
                        com.google.android.material.R.attr.colorOnSurface,
                        R.color.text_primary
                    )
                )
                transcriptionPreviewContainer.visibility = View.GONE
                startPulseAnimation(minScale = 0.95f, maxScale = 1.05f, durationMs = 1_000L)
            }
            is RecordingState.Recording -> {
                val labelRes = if (state.mode == RecordingMode.HANDS_FREE)
                    R.string.state_hands_free else R.string.state_recording
                btnMic.backgroundTintList = ColorStateList.valueOf(
                    resolveColor(
                        com.google.android.material.R.attr.colorPrimary,
                        R.color.accent_primary
                    )
                )
                btnMic.imageTintList = ColorStateList.valueOf(
                    resolveColor(
                        com.google.android.material.R.attr.colorOnPrimary,
                        R.color.on_accent_primary
                    )
                )
                stateLabel.text = context.getString(labelRes)
                stateLabel.setTextColor(
                    resolveColor(
                        com.google.android.material.R.attr.colorPrimary,
                        R.color.accent_primary
                    )
                )

                if (state.mode == RecordingMode.PUSH_TO_TALK) {
                    btnMic.animate().scaleX(1.1f).scaleY(1.1f).setDuration(200).start()
                } else {
                    startPulseAnimation(minScale = 1.05f, maxScale = 1.15f, durationMs = 600L)
                }
            }
            is RecordingState.Processing -> {
                btnMic.backgroundTintList = ColorStateList.valueOf(
                    resolveColor(
                        com.google.android.material.R.attr.colorSurface,
                        R.color.surface
                    )
                )
                btnMic.imageTintList = ColorStateList.valueOf(
                    resolveColor(
                        com.google.android.material.R.attr.colorOnSurface,
                        R.color.text_primary
                    )
                )
                stateLabel.text = context.getString(R.string.state_processing)
                stateLabel.setTextColor(
                    resolveColor(
                        com.google.android.material.R.attr.colorOnSurface,
                        R.color.text_primary
                    )
                )
            }
            is RecordingState.Error -> {
                btnMic.backgroundTintList = ColorStateList.valueOf(
                    resolveColor(
                        com.google.android.material.R.attr.colorError,
                        R.color.error
                    )
                )
                btnMic.imageTintList = ColorStateList.valueOf(
                    resolveColor(
                        com.google.android.material.R.attr.colorOnError,
                        R.color.on_error
                    )
                )
                stateLabel.text = context.getString(R.string.state_error)
                stateLabel.setTextColor(
                    resolveColor(
                        com.google.android.material.R.attr.colorError,
                        R.color.error
                    )
                )
                showBanner(state.message)
                playShakeAnimation()
            }
        }
    }

    /** Plays a success "pop" animation when transcription is committed. */
    fun playSuccessAnimation() {
        cancelMicAnimations(resetScale = false)
        btnMic.animate()
            .scaleX(1.2f).scaleY(1.2f)
            .setDuration(150)
            .withEndAction {
                btnMic.animate()
                    .scaleX(1.0f).scaleY(1.0f)
                    .setDuration(150)
                    .start()
            }.start()
    }

    /** Shows a live transcription preview in the strip above the mic button. */
    fun showTranscriptionPreview(text: String) {
        transcriptionPreviewContainer.visibility = View.VISIBLE
        transcriptionPreviewText.text = text
    }

    /** Hides the transcription preview strip. */
    fun hideTranscriptionPreview() {
        transcriptionPreviewContainer.visibility = View.GONE
    }

    /**
     * Displays a contextual banner message (TSD Section 2.2).
     *
     * Banners are used for:
     * - Missing API key (red)
     * - Password field warning (yellow)
     * - Incognito mode notice (gray)
     * - Error messages (red)
     */
    fun showBanner(message: String) {
        bannerMessage.text = message
        bannerMessage.visibility = View.VISIBLE
    }

    /** Hides the contextual banner. */
    fun hideBanner() {
        bannerMessage.visibility = View.GONE
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun setupListeners() {
        // Mic button touch handling
        btnMic.setOnTouchListener { _, event ->
            onMicTouchListener?.invoke(event) ?: false
        }

        // Backspace touch handling is fully delegated to IME service.
        btnBackspace.setOnTouchListener { _, event ->
            animateKeyPress(btnBackspace, event.actionMasked)
            onBackspaceTouchListener?.invoke(event) ?: false
        }

        btnSpacebar.setOnTouchListener { _, event ->
            animateKeyPress(btnSpacebar, event.actionMasked)
            false
        }
        btnSpacebar.setOnClickListener { onSpacebarClick?.invoke() }

        btnSettings.setOnTouchListener { _, event ->
            animateKeyPress(btnSettings, event.actionMasked)
            false
        }
        btnSettings.setOnClickListener { onSettingsClick?.invoke() }
    }

    private fun applyMaterialYouStyling() {
        val surface = resolveColor(
            com.google.android.material.R.attr.colorSurface,
            R.color.surface
        )
        val onSurface = resolveColor(
            com.google.android.material.R.attr.colorOnSurface,
            R.color.text_primary
        )
        val primary = resolveColor(
            com.google.android.material.R.attr.colorPrimary,
            R.color.accent_primary
        )
        val secondary = resolveColor(
            com.google.android.material.R.attr.colorSecondary,
            R.color.accent_secondary
        )

        val gradientTop = MaterialColors.layer(surface, primary, 0.08f)
        val gradientBottom = MaterialColors.layer(surface, secondary, 0.06f)
        val topCorner = resources.getDimension(R.dimen.keyboard_top_corner_radius)
        keyboardRoot.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(gradientTop, gradientBottom)
        ).apply {
            cornerRadii = floatArrayOf(
                topCorner, topCorner,
                topCorner, topCorner,
                0f, 0f,
                0f, 0f
            )
        }

        val primaryContainer = MaterialColors.layer(surface, primary, 0.20f)
        val secondaryContainer = MaterialColors.layer(surface, secondary, 0.17f)
        val outlinedContainer = MaterialColors.layer(surface, onSurface, 0.04f)
        val outline = ColorStateList.valueOf(ColorUtils.setAlphaComponent(onSurface, 56))
        val keyRipple = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 44))

        btnSpacebar.backgroundTintList = ColorStateList.valueOf(primaryContainer)
        btnSpacebar.setTextColor(onSurface)
        btnSpacebar.strokeColor = outline
        btnSpacebar.strokeWidth = resources.getDimensionPixelSize(R.dimen.key_stroke_width)
        btnSpacebar.rippleColor = keyRipple

        btnBackspace.backgroundTintList = ColorStateList.valueOf(secondaryContainer)
        btnBackspace.setTextColor(onSurface)
        btnBackspace.strokeColor = outline
        btnBackspace.strokeWidth = resources.getDimensionPixelSize(R.dimen.key_stroke_width)
        btnBackspace.rippleColor = keyRipple

        btnSettings.backgroundTintList = ColorStateList.valueOf(outlinedContainer)
        btnSettings.setTextColor(onSurface)
        btnSettings.strokeColor = outline
        btnSettings.strokeWidth = resources.getDimensionPixelSize(R.dimen.key_stroke_width)
        btnSettings.rippleColor = keyRipple

        btnMic.rippleColor = ColorUtils.setAlphaComponent(primary, 56)
        bottomRow.alpha = 1f
    }

    private fun playEntryAnimation() {
        val startOffset = resources.getDimension(R.dimen.keyboard_entry_offset)
        val targets = listOf(stateLabel, btnMic, bottomRow)
        targets.forEachIndexed { index, target ->
            target.alpha = 0f
            target.translationY = startOffset
            target.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * 45L)
                .setDuration(220L)
                .setInterpolator(OvershootInterpolator(0.65f))
                .start()
        }
    }

    private fun animateKeyPress(target: View, action: Int) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                target.animate()
                    .scaleX(0.96f)
                    .scaleY(0.96f)
                    .setDuration(90L)
                    .start()
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                target.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(90L)
                    .start()
            }
        }
    }

    private fun cancelMicAnimations(resetScale: Boolean) {
        pulseAnimatorSet?.cancel()
        pulseAnimatorSet = null
        btnMic.animate().cancel()
        btnMic.translationX = 0f
        if (resetScale) {
            btnMic.scaleX = 1f
            btnMic.scaleY = 1f
        }
    }

    private fun startPulseAnimation(minScale: Float, maxScale: Float, durationMs: Long) {
        val scaleX = ObjectAnimator.ofFloat(btnMic, View.SCALE_X, minScale, maxScale).apply {
            duration = durationMs
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        val scaleY = ObjectAnimator.ofFloat(btnMic, View.SCALE_Y, minScale, maxScale).apply {
            duration = durationMs
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        pulseAnimatorSet = AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            start()
        }
    }

    private fun playShakeAnimation() {
        val shakeOffset = 15f
        btnMic.animate()
            .translationXBy(shakeOffset)
            .setDuration(50)
            .withEndAction {
                btnMic.animate()
                    .translationXBy(-shakeOffset * 2)
                    .setDuration(50)
                    .withEndAction {
                        btnMic.animate()
                            .translationXBy(shakeOffset * 2)
                            .setDuration(50)
                            .withEndAction {
                                btnMic.animate().translationX(0f).setDuration(50).start()
                            }
                            .start()
                    }
                    .start()
            }
            .start()
    }

    private fun resolveColor(attr: Int, fallbackColorRes: Int): Int {
        val fallback = context.getColor(fallbackColorRes)
        return MaterialColors.getColor(this, attr, fallback)
    }
}
