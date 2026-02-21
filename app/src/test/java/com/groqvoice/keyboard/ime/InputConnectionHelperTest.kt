package com.groqvoice.keyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for [InputConnectionHelper].
 *
 * TSD Section 8.1 — InputConnectionHelper: Mock InputConnection, verify text insertion/deletion.
 */
class InputConnectionHelperTest {

    // ── commitTranscription ────────────────────────────────────────────────────

    @Test
    fun `commitTranscription finishes composing then commits text`() {
        val ic: InputConnection = mock()
        InputConnectionHelper.commitTranscription(ic, "hello world")
        verify(ic).finishComposingText()
        verify(ic).commitText("hello world", 1)
    }

    // ── deleteCharacter ────────────────────────────────────────────────────────

    @Test
    fun `deleteCharacter deletes single character`() {
        val ic: InputConnection = mock()
        whenever(ic.getTextBeforeCursor(2, 0)).thenReturn("ab")
        InputConnectionHelper.deleteCharacter(ic)
        verify(ic).deleteSurroundingText(1, 0)
    }

    @Test
    fun `deleteCharacter handles surrogate pair (emoji)`() {
        val ic: InputConnection = mock()
        // U+1F600 GRINNING FACE — surrogate pair: \uD83D\uDE00
        val emoji = "\uD83D\uDE00"
        whenever(ic.getTextBeforeCursor(2, 0)).thenReturn(emoji)
        InputConnectionHelper.deleteCharacter(ic)
        verify(ic).deleteSurroundingText(2, 0)
    }

    // ── handleSpaceKey ─────────────────────────────────────────────────────────

    @Test
    fun `handleSpaceKey inserts plain space on first tap`() {
        val ic: InputConnection = mock()
        val result = InputConnectionHelper.handleSpaceKey(ic, 0L, true)
        verify(ic).commitText(" ", 1)
        assertFalse(result)
    }

    @Test
    fun `handleSpaceKey inserts period+space on rapid double-tap`() {
        val ic: InputConnection = mock()
        val recentMs = System.currentTimeMillis() - 100L // 100ms ago = within 300ms window
        val result = InputConnectionHelper.handleSpaceKey(ic, recentMs, true)
        verify(ic).deleteSurroundingText(1, 0)
        verify(ic).commitText(". ", 1)
        assertTrue(result)
    }

    @Test
    fun `handleSpaceKey does not insert period when preference disabled`() {
        val ic: InputConnection = mock()
        val recentMs = System.currentTimeMillis() - 100L
        val result = InputConnectionHelper.handleSpaceKey(ic, recentMs, false)
        verify(ic).commitText(" ", 1)
        assertFalse(result)
    }

    // ── isPasswordField ────────────────────────────────────────────────────────

    @Test
    fun `isPasswordField returns true for TYPE_TEXT_VARIATION_PASSWORD`() {
        val editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        assertTrue(InputConnectionHelper.isPasswordField(editorInfo))
    }

    @Test
    fun `isPasswordField returns true for TYPE_NUMBER_VARIATION_PASSWORD`() {
        val editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        assertTrue(InputConnectionHelper.isPasswordField(editorInfo))
    }

    @Test
    fun `isPasswordField returns false for regular text field`() {
        val editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
        }
        assertFalse(InputConnectionHelper.isPasswordField(editorInfo))
    }

    // ── isIncognitoMode ────────────────────────────────────────────────────────

    @Test
    fun `isIncognitoMode returns true when IME_FLAG_NO_PERSONALIZED_LEARNING is set`() {
        val editorInfo = EditorInfo().apply {
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        assertTrue(InputConnectionHelper.isIncognitoMode(editorInfo))
    }

    @Test
    fun `isIncognitoMode returns false for standard field`() {
        val editorInfo = EditorInfo().apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        assertFalse(InputConnectionHelper.isIncognitoMode(editorInfo))
    }
}
