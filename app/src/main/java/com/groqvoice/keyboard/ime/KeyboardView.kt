package com.groqvoice.keyboard.ime

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.groqvoice.keyboard.R
import com.groqvoice.keyboard.model.RecordingMode
import com.groqvoice.keyboard.model.RecordingState

/**
 * Custom [FrameLayout] that inflates [R.layout.keyboard_view] and wires up
 * all UI state transitions for the mic button, backspace, and spacebar.
 *
 * Delegates touch events to [onMicTouchListener] so that [VoiceInputMethodService]
 * can control the state machine without tight coupling to the view.
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
    private val stateLabel: TextView
    private val transcriptionPreviewText: TextView
    private val transcriptionPreviewContainer: View
    private val bannerMessage: TextView

    // ── Listeners exposed to VoiceInputMethodService ───────────────────────────
    var onMicTouchListener: ((event: MotionEvent) -> Boolean)? = null
    var onBackspaceClick: (() -> Unit)? = null
    var onBackspaceLongClick: (() -> Boolean)? = null
    var onSpacebarClick: (() -> Unit)? = null
    var onSettingsClick: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.keyboard_view, this, true)

        btnMic = findViewById(R.id.btn_mic)
        btnBackspace = findViewById(R.id.btn_backspace)
        btnSpacebar = findViewById(R.id.btn_spacebar)
        btnSettings = findViewById(R.id.btn_settings)
        stateLabel = findViewById(R.id.state_label)
        transcriptionPreviewText = findViewById(R.id.transcription_preview_text)
        transcriptionPreviewContainer = findViewById(R.id.transcription_preview_container)
        bannerMessage = findViewById(R.id.banner_message)

        setupListeners()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Updates all visual elements to reflect the new [RecordingState].
     * Must be called on the main thread.
     *
     * TSD Section 3.2 — Mic Button States.
     */
    fun applyState(state: RecordingState) {
        when (state) {
            is RecordingState.Idle -> {
                btnMic.backgroundTintList =
                    context.getColorStateList(R.color.accent_secondary)
                stateLabel.text = context.getString(R.string.state_idle)
                stateLabel.setTextColor(context.getColor(R.color.disabled))
                transcriptionPreviewContainer.visibility = View.GONE
            }
            is RecordingState.Recording -> {
                val labelRes = if (state.mode == RecordingMode.HANDS_FREE)
                    R.string.state_hands_free else R.string.state_recording
                btnMic.backgroundTintList =
                    context.getColorStateList(R.color.accent_primary)
                stateLabel.text = context.getString(labelRes)
                stateLabel.setTextColor(context.getColor(R.color.accent_primary))
            }
            is RecordingState.Processing -> {
                btnMic.backgroundTintList =
                    context.getColorStateList(R.color.disabled)
                stateLabel.text = context.getString(R.string.state_processing)
                stateLabel.setTextColor(context.getColor(R.color.disabled))
            }
            is RecordingState.Error -> {
                btnMic.backgroundTintList =
                    context.getColorStateList(R.color.error)
                stateLabel.text = context.getString(R.string.state_error)
                stateLabel.setTextColor(context.getColor(R.color.error))
                showBanner(state.message)
            }
        }
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

    /** Displays a contextual banner message (TSD Section 2.2). */
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
        btnMic.setOnTouchListener { _, event ->
            onMicTouchListener?.invoke(event) ?: false
        }

        btnBackspace.setOnClickListener { onBackspaceClick?.invoke() }
        btnBackspace.setOnLongClickListener { onBackspaceLongClick?.invoke() ?: false }
        btnSpacebar.setOnClickListener { onSpacebarClick?.invoke() }
        btnSettings.setOnClickListener { onSettingsClick?.invoke() }
    }
}
