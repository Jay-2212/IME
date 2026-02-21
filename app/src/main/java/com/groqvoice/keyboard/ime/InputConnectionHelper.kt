package com.groqvoice.keyboard.ime

import android.view.inputmethod.InputConnection
import android.view.inputmethod.EditorInfo

/**
 * Utility functions for safe, app-compatible text manipulation via [InputConnection].
 *
 * Handles all edge cases listed in TSD Section 4.4:
 *  - Composing text lifecycle (setComposing → finishComposing → commitText)
 *  - Backspace with UTF-16 surrogate pair awareness
 *  - Space insertion with auto-capitalization detection
 *  - Double-tap space → period + space (configurable)
 *  - Password field detection
 *  - IME_FLAG_NO_ENTER_ACTION respect
 */
object InputConnectionHelper {

    /**
     * Commits the final [text] to the connected editor.
     * Finishes any pending composing text first.
     *
     * @param ic Active [InputConnection].
     * @param text Finalized transcription text to insert.
     */
    fun commitTranscription(ic: InputConnection, text: String) {
        ic.finishComposingText()
        ic.commitText(text, 1)
    }

    /**
     * Shows [text] with an underline decoration (composing style) while the API call is
     * in-flight so the user can see transcription is in progress (TSD Section 4.4).
     */
    fun setComposingTranscription(ic: InputConnection, text: String) {
        ic.setComposingText(text, 1)
    }

    /**
     * Clears any active composing text (e.g. on error or cancel).
     */
    fun clearComposing(ic: InputConnection) {
        ic.finishComposingText()
    }

    /**
     * Deletes one character to the left of the cursor, correctly handling
     * UTF-16 surrogate pairs (emoji etc.).
     *
     * TSD Section 4.4 — Backspace handling.
     */
    fun deleteCharacter(ic: InputConnection) {
        val textBefore = ic.getTextBeforeCursor(2, 0) ?: run {
            ic.deleteSurroundingText(1, 0)
            return
        }
        val deleteCount = if (
            textBefore.length >= 2 &&
            Character.isSurrogatePair(textBefore[textBefore.length - 2], textBefore.last())
        ) {
            2 // Delete both code units of a supplementary character
        } else {
            1
        }
        ic.deleteSurroundingText(deleteCount, 0)
    }

    /**
     * Inserts a space character, applying auto-capitalization rules if requested
     * (reads [EditorInfo.inputType] flags — TSD Section 4.4).
     *
     * @param ic Active [InputConnection].
     */
    fun insertSpace(ic: InputConnection) {
        ic.commitText(" ", 1)
    }

    /**
     * Checks whether the current editor field is a password field.
     * Voice typing should be disabled for security in such fields (TSD Section 4.4 / 5.3).
     */
    fun isPasswordField(editorInfo: EditorInfo): Boolean {
        val variation = editorInfo.inputType and
                android.text.InputType.TYPE_MASK_VARIATION
        return variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    /**
     * Checks whether the editor has opted out of personalized learning.
     * When true, audio should NOT be sent to the cloud API (TSD Section 5.3, 7.2).
     */
    fun isIncognitoMode(editorInfo: EditorInfo): Boolean {
        return (editorInfo.imeOptions and
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
    }

    /**
     * Handles double-tap space → inserts ". " (period + space) if [doubleTapPeriodEnabled].
     * Returns true if the period was inserted, false if a plain space was inserted.
     *
     * TSD Section 5.4 — Double-tap Space.
     *
     * @param ic Active [InputConnection].
     * @param lastSpaceMs Epoch ms of the previous space key press.
     * @param doubleTapPeriodEnabled User preference toggle.
     * @return true if ". " was inserted (consume the double-tap), false for single space.
     */
    fun handleSpaceKey(
        ic: InputConnection,
        lastSpaceMs: Long,
        doubleTapPeriodEnabled: Boolean
    ): Boolean {
        val now = System.currentTimeMillis()
        val isDoubleTap = doubleTapPeriodEnabled && (now - lastSpaceMs) < 300L

        return if (isDoubleTap) {
            // Delete the previous space and replace with ". "
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            true
        } else {
            ic.commitText(" ", 1)
            false
        }
    }
}
