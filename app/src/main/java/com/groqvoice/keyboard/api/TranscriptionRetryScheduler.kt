package com.groqvoice.keyboard.api

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Schedules deferred transcription uploads for network-unavailable scenarios.
 *
 * TSD Section 5.1 and 6.3 require queuing failed uploads and retrying when network is restored.
 */
interface TranscriptionRetryScheduler {
    fun enqueue(audioFile: File, model: String, language: String?): UUID
}

/**
 * WorkManager-backed retry scheduler that survives process death and Doze mode.
 */
class WorkManagerTranscriptionRetryScheduler(
    context: Context
) : TranscriptionRetryScheduler {

    private val appContext = context.applicationContext
    private val workManager: WorkManager = WorkManager.getInstance(appContext)

    override fun enqueue(audioFile: File, model: String, language: String?): UUID {
        val workName = "groq_transcription_retry_${audioFile.name}"
        val request = OneTimeWorkRequestBuilder<TranscriptionRetryWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .setInputData(
                workDataOf(
                    TranscriptionRetryWorker.KEY_AUDIO_FILE_PATH to audioFile.absolutePath,
                    TranscriptionRetryWorker.KEY_MODEL to model,
                    TranscriptionRetryWorker.KEY_LANGUAGE to language
                )
            )
            .addTag(TranscriptionRetryWorker.TAG)
            .build()

        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request)
        return request.id
    }
}
