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

class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    val btnMic: FloatingActionButton
    val btnBackspace: MaterialButton
    val btnSpacebar: MaterialButton
    val btnEnter: MaterialButton
    val btnSettings: MaterialButton
    private val keyboardRoot: View
    private val bottomRow: View
    private val micPulseLayer: View
    private val stateLabel: TextView
    private val transcriptionPreviewText: TextView
    private val transcriptionPreviewContainer: View
    private val bannerMessage: TextView
    private var pulseAnimatorSet: AnimatorSet? = null
    private var hasPlayedEntryAnimation = false

    var onMicTouchListener: ((event: MotionEvent) -> Boolean)? = null
    var onBackspaceTouchListener: ((event: MotionEvent) -> Boolean)? = null
    var onSpacebarClick: (() -> Unit)? = null
    var onEnterClick: (() -> Unit)? = null
    var onSettingsClick: (() -> Unit)? = null

    private val pressScale: Float by lazy {
        resources.getFraction(R.fraction.oe_press_scale, 1, 1)
    }
    private val motionFast: Long by lazy {
        resources.getInteger(R.integer.oe_motion_fast).toLong()
    }
    private val motionStandard: Long by lazy {
        resources.getInteger(R.integer.oe_motion_standard).toLong()
    }
    private val motionScreen: Long by lazy {
        resources.getInteger(R.integer.oe_motion_screen).toLong()
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.keyboard_view, this, true)

        btnMic = findViewById(R.id.btn_mic)
        btnBackspace = findViewById(R.id.btn_backspace)
        btnSpacebar = findViewById(R.id.btn_spacebar)
        btnEnter = findViewById(R.id.btn_enter)
        btnSettings = findViewById(R.id.btn_settings)
        keyboardRoot = findViewById(R.id.keyboard_root)
        bottomRow = findViewById(R.id.bottom_row)
        micPulseLayer = findViewById(R.id.mic_pulse_layer)
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
                micPulseLayer.animate().alpha(0f).setDuration(motionStandard).start()
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

                val maxScale = if (state.mode == RecordingMode.HANDS_FREE) 1.08f else 1.05f
                startPulseAnimation(minScale = 1.0f, maxScale = maxScale, durationMs = 760L)
                micPulseLayer.alpha = 0.12f
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
                micPulseLayer.animate().alpha(0f).setDuration(motionStandard).start()
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
                micPulseLayer.animate().alpha(0f).setDuration(motionFast).start()
            }
        }
    }

    fun playSuccessAnimation() {
        cancelMicAnimations(resetScale = false)
        btnMic.animate()
            .scaleX(1.12f).scaleY(1.12f)
            .setDuration(motionFast)
            .withEndAction {
                btnMic.animate()
                    .scaleX(1.0f).scaleY(1.0f)
                    .setDuration(motionFast)
                    .start()
            }.start()
    }

    fun showTranscriptionPreview(text: String) {
        transcriptionPreviewContainer.visibility = View.VISIBLE
        transcriptionPreviewText.text = text
    }

    fun hideTranscriptionPreview() {
        transcriptionPreviewContainer.visibility = View.GONE
    }

    fun showBanner(message: String) {
        bannerMessage.text = message
        bannerMessage.visibility = View.VISIBLE
    }

    fun hideBanner() {
        bannerMessage.visibility = View.GONE
    }

    fun onMicPressDown() {
        micPulseLayer.apply {
            alpha = 0.24f
            scaleX = 0.84f
            scaleY = 0.84f
        }
        micPulseLayer.animate()
            .alpha(0f)
            .scaleX(1.16f)
            .scaleY(1.16f)
            .setDuration(160L)
            .start()
        btnMic.animate()
            .scaleX(pressScale)
            .scaleY(pressScale)
            .setDuration(motionFast)
            .start()
    }

    fun onMicPressRelease() {
        btnMic.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(140L)
            .start()
        micPulseLayer.animate()
            .alpha(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(130L)
            .start()
    }

    private fun setupListeners() {
        btnMic.setOnTouchListener { _, event ->
            onMicTouchListener?.invoke(event) ?: false
        }

        btnBackspace.setOnTouchListener { _, event ->
            animateKeyPress(btnBackspace, event.actionMasked)
            onBackspaceTouchListener?.invoke(event) ?: false
        }

        btnSpacebar.setOnTouchListener { _, event ->
            animateKeyPress(btnSpacebar, event.actionMasked)
            false
        }
        btnSpacebar.setOnClickListener { onSpacebarClick?.invoke() }

        btnEnter.setOnTouchListener { _, event ->
            animateKeyPress(btnEnter, event.actionMasked)
            false
        }
        btnEnter.setOnClickListener { onEnterClick?.invoke() }

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

        val gradientTop = MaterialColors.layer(surface, primary, 0.10f)
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
        val secondaryContainer = MaterialColors.layer(surface, secondary, 0.14f)
        val outlinedContainer = MaterialColors.layer(surface, onSurface, 0.04f)
        val outline = ColorStateList.valueOf(ColorUtils.setAlphaComponent(onSurface, 56))
        val keyRipple = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 44))

        btnSpacebar.backgroundTintList = ColorStateList.valueOf(primaryContainer)
        btnSpacebar.setTextColor(onSurface)
        btnSpacebar.strokeColor = outline
        btnSpacebar.strokeWidth = resources.getDimensionPixelSize(R.dimen.key_stroke_width)
        btnSpacebar.rippleColor = keyRipple

        btnBackspace.backgroundTintList = ColorStateList.valueOf(outlinedContainer)
        btnBackspace.setTextColor(onSurface)
        btnBackspace.iconTint = ColorStateList.valueOf(onSurface)
        btnBackspace.strokeColor = outline
        btnBackspace.strokeWidth = resources.getDimensionPixelSize(R.dimen.key_stroke_width)
        btnBackspace.rippleColor = keyRipple

        btnEnter.backgroundTintList = ColorStateList.valueOf(primary)
        btnEnter.setTextColor(
            resolveColor(
                com.google.android.material.R.attr.colorOnPrimary,
                R.color.on_accent_primary
            )
        )
        btnEnter.iconTint = ColorStateList.valueOf(
            resolveColor(
                com.google.android.material.R.attr.colorOnPrimary,
                R.color.on_accent_primary
            )
        )
        btnEnter.strokeColor = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        btnEnter.strokeWidth = 0
        btnEnter.rippleColor = keyRipple
        btnEnter.iconSize = resources.getDimensionPixelSize(R.dimen.enter_action_icon_size)

        btnSettings.backgroundTintList = ColorStateList.valueOf(outlinedContainer)
        btnSettings.setTextColor(onSurface)
        btnSettings.iconTint = ColorStateList.valueOf(onSurface)
        btnSettings.strokeColor = outline
        btnSettings.strokeWidth = resources.getDimensionPixelSize(R.dimen.key_stroke_width)
        btnSettings.rippleColor = keyRipple
        btnSettings.iconSize = resources.getDimensionPixelSize(R.dimen.key_icon_size)
        btnBackspace.iconSize = resources.getDimensionPixelSize(R.dimen.key_icon_size)

        btnMic.rippleColor = ColorUtils.setAlphaComponent(primary, 56)
        btnMic.backgroundTintList = ColorStateList.valueOf(primary)
        btnMic.imageTintList = ColorStateList.valueOf(
            resolveColor(
                com.google.android.material.R.attr.colorOnPrimary,
                R.color.on_accent_primary
            )
        )
        micPulseLayer.backgroundTintList = ColorStateList.valueOf(secondaryContainer)
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
                .setDuration(motionScreen)
                .setInterpolator(OvershootInterpolator(0.65f))
                .start()
        }
    }

    private fun animateKeyPress(target: View, action: Int) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                target.animate()
                    .scaleX(pressScale)
                    .scaleY(pressScale)
                    .setDuration(motionFast)
                    .start()
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                target.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(motionFast)
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
            .setDuration(motionFast / 2)
            .withEndAction {
                btnMic.animate()
                    .translationXBy(-shakeOffset * 2)
                    .setDuration(motionFast / 2)
                    .withEndAction {
                        btnMic.animate()
                            .translationXBy(shakeOffset * 2)
                            .setDuration(motionFast / 2)
                            .withEndAction {
                                btnMic.animate().translationX(0f).setDuration(motionFast / 2).start()
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
