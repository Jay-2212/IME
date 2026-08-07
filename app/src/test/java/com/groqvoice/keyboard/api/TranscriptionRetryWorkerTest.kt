package com.groqvoice.keyboard.api

import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Regression coverage for the WorkManager retry attempt bound.
 *
 * Prior to this fix, [TranscriptionRetryWorker] returned [ListenableWorker.Result.retry] for
 * every transient failure with no cap, so a persistently-flaky (but not fully offline) network
 * would resend the same recording forever and keep its temp file on disk indefinitely.
 */
@RunWith(RobolectricTestRunner::class)
class TranscriptionRetryWorkerTest {

    @Test
    fun `doWork fails and deletes the file once retry attempts are exhausted`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val audioFile = File.createTempFile("groq_audio_", ".wav", context.cacheDir).apply {
            writeBytes(ByteArray(16) { 1 })
        }

        val worker = TestListenableWorkerBuilder<TranscriptionRetryWorker>(context)
            .setInputData(
                Data.Builder()
                    .putString(TranscriptionRetryWorker.KEY_AUDIO_FILE_PATH, audioFile.absolutePath)
                    .build()
            )
            .setRunAttemptCount(TranscriptionRetryWorker.MAX_RETRY_ATTEMPTS)
            .build()

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(
            "Retry attempts exhausted.",
            (result as ListenableWorker.Result.Failure).outputData.getString(TranscriptionRetryWorker.KEY_RESULT_ERROR)
        )
        assertFalse("temp file must be deleted once attempts are exhausted", audioFile.exists())
    }

    @Test
    fun `doWork does not short-circuit before the attempt cap is reached`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val audioFile = File.createTempFile("groq_audio_", ".wav", context.cacheDir).apply {
            writeBytes(ByteArray(16) { 1 })
        }

        val worker = TestListenableWorkerBuilder<TranscriptionRetryWorker>(context)
            .setInputData(
                Data.Builder()
                    .putString(TranscriptionRetryWorker.KEY_AUDIO_FILE_PATH, audioFile.absolutePath)
                    .build()
            )
            .setRunAttemptCount(0)
            .build()

        val result = worker.doWork()

        // No API key configured in this environment, so at attempt 0 the worker must reach the
        // normal "cannot recover without a key" failure path, not the attempt-cap path — proving
        // the cap check does not fire prematurely.
        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(
            "API key missing.",
            (result as ListenableWorker.Result.Failure).outputData.getString(TranscriptionRetryWorker.KEY_RESULT_ERROR)
        )
        assertFalse(audioFile.exists())
    }
}
