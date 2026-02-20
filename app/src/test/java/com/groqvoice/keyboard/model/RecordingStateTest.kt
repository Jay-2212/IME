package com.groqvoice.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RecordingState] sealed class and [RecordingMode] enum.
 *
 * TSD Section 8.1 — StateMachine: Test all state transitions (Idle→Recording→Processing→Idle).
 */
class RecordingStateTest {

    @Test
    fun `Idle state is a singleton object`() {
        val a = RecordingState.Idle
        val b = RecordingState.Idle
        assertEquals(a, b)
    }

    @Test
    fun `Recording state captures mode and startTime`() {
        val startTime = System.currentTimeMillis()
        val state = RecordingState.Recording(RecordingMode.PUSH_TO_TALK, startTime)
        assertEquals(RecordingMode.PUSH_TO_TALK, state.mode)
        assertEquals(startTime, state.startTime)
    }

    @Test
    fun `Recording states with different modes are not equal`() {
        val now = 1_000L
        val ptt = RecordingState.Recording(RecordingMode.PUSH_TO_TALK, now)
        val hf = RecordingState.Recording(RecordingMode.HANDS_FREE, now)
        assertNotEquals(ptt, hf)
    }

    @Test
    fun `Processing state is a singleton object`() {
        assertEquals(RecordingState.Processing, RecordingState.Processing)
    }

    @Test
    fun `Error state captures message`() {
        val msg = "Microphone unavailable"
        val state = RecordingState.Error(msg)
        assertEquals(msg, state.message)
    }

    @Test
    fun `when expression covers all branches`() {
        val states: List<RecordingState> = listOf(
            RecordingState.Idle,
            RecordingState.Recording(RecordingMode.PUSH_TO_TALK, 0L),
            RecordingState.Processing,
            RecordingState.Error("err")
        )

        val labels = states.map { state ->
            when (state) {
                is RecordingState.Idle -> "idle"
                is RecordingState.Recording -> "recording"
                is RecordingState.Processing -> "processing"
                is RecordingState.Error -> "error"
            }
        }

        assertEquals(listOf("idle", "recording", "processing", "error"), labels)
    }

    @Test
    fun `RecordingMode has exactly two values`() {
        assertEquals(2, RecordingMode.entries.size)
        assertTrue(RecordingMode.entries.contains(RecordingMode.PUSH_TO_TALK))
        assertTrue(RecordingMode.entries.contains(RecordingMode.HANDS_FREE))
    }
}
