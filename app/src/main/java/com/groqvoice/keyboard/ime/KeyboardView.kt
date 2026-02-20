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
 * ## Responsibilities
 *
 * - **Visual State Management**: Updates button colors, labels, and visibility
 *   based on [RecordingState] (Idle, Recording, Processing, Error).
 * - **Touch Event Delegation**: Delegates mic button touch events to
 *   [onMicTouchListener] so that [VoiceInputMethodService] can control the
 *   state machine without tight coupling to the view.
 * - **Backspace Long-Press**: Handles the complete touch lifecycle for backspace
 *   including down, long-press trigger, and up events for proper repeat behavior.
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
    private val stateLabel: TextView
    private val transcriptionPreviewText: TextView
    private val transcriptionPreviewContainer: View
    private val bannerMessage: TextView

    // ── Listeners exposed to VoiceInputMethodService ───────────────────────────
    var onMicTouchListener: ((event: MotionEvent) -> Boolean)? = null
    var onBackspaceClick: (() -> Unit)? = null
    var onBackspaceLongClick: (() -> Boolean)? = null
    var onBackspaceTouchUp: (() -> Unit)? = null
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
        when (state) {
            is RecordingState.Idle -> {
                btnMic.backgroundTintList =
                    context.getColorStateList(R.color.accent_secondary, null)
                stateLabel.text = context.getString(R.string.state_idle)
                stateLabel.setTextColor(context.getColor(R.color.disabled))
                transcriptionPreviewContainer.visibility = View.GONE
                // Reset mic button scale
                btnMic.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
            }
            is RecordingState.Recording -> {
                val labelRes = if (state.mode == RecordingMode.HANDS_FREE)
                    R.string.state_hands_free else R.string.state_recording
                btnMic.backgroundTintList =
                    context.getColorStateList(R.color.accent_primary, null)
                stateLabel.text = context.getString(labelRes)
                stateLabel.setTextColor(context.getColor(R.color.accent_primary))

                // Scale animation for recording state
                if (state.mode == RecordingMode.PUSH_TO_TALK) {
                    btnMic.animate().scaleX(1.1f).scaleY(1.1f).setDuration(200).start()
                }
            }
            is RecordingState.Processing -> {
                btnMic.backgroundTintList =
                    context.getColorStateList(R.color.disabled, null)
                stateLabel.text = context.getString(R.string.state_processing)
                stateLabel.setTextColor(context.getColor(R.color.disabled))
                // Reset scale
                btnMic.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
            }
            is RecordingState.Error -> {
                btnMic.backgroundTintList =
                    context.getColorStateList(R.color.error, null)
                stateLabel.text = context.getString(R.string.state_error)
                stateLabel.setTextColor(context.getColor(R.color.error))
                showBanner(state.message)
                // Reset scale
                btnMic.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
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

        // Backspace with full touch lifecycle for long-press repeat
        btnBackspace.setOnClickListener { onBackspaceClick?.invoke() }
        btnBackspace.setOnLongClickListener { onBackspaceLongClick?.invoke() ?: false }
        btnBackspace.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    onBackspaceTouchUp?.invoke()
                }
            }
            // Return false to allow long-click detection to work
            false
        }

        btnSpacebar.setOnClickListener { onSpacebarClick?.invoke() }
        btnSettings.setOnClickListener { onSettingsClick?.invoke() }
    }
}
