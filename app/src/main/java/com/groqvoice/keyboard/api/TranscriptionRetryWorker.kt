package com.groqvoice.keyboard.api

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.groqvoice.keyboard.model.TranscriptionResult
import com.groqvoice.keyboard.utils.FileCacheManager
import com.groqvoice.keyboard.utils.SecurePrefs
import java.io.File

/**
 * Background worker that retries a deferred transcription upload.
 *
 * Design notes:
 * - Uses the same repository stack as the IME for consistent response mapping.
 * - Disables re-queuing inside the worker to avoid recursive work creation.
 * - Returns WorkManager retry for transient failures (429, 5xx, connectivity glitches).
 */
class TranscriptionRetryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val TAG = "groq_transcription_retry"

        const val KEY_AUDIO_FILE_PATH = "audio_file_path"
        const val KEY_MODEL = "model"
        const val KEY_LANGUAGE = "language"

        const val KEY_RESULT_TEXT = "result_text"
        const val KEY_RESULT_WARNING = "result_warning"
        const val KEY_RESULT_IS_PARTIAL = "result_is_partial"
        const val KEY_RESULT_ERROR = "result_error"

        /** Upper bound on WorkManager retry attempts so failed uploads do not resend forever. */
        const val MAX_RETRY_ATTEMPTS = 5
    }

    override suspend fun doWork(): Result {
        val path = inputData.getString(KEY_AUDIO_FILE_PATH) ?: return Result.failure(
            Data.Builder().putString(KEY_RESULT_ERROR, "Missing audio file path.").build()
        )

        if (runAttemptCount >= MAX_RETRY_ATTEMPTS) {
            File(path).let { FileCacheManager(applicationContext).deleteFile(it) }
            return Result.failure(
                Data.Builder().putString(KEY_RESULT_ERROR, "Retry attempts exhausted.").build()
            )
        }
        val model = inputData.getString(KEY_MODEL) ?: "whisper-large-v3-turbo"
        val language = inputData.getString(KEY_LANGUAGE)

        val audioFile = File(path)
        if (!audioFile.exists()) {
            return Result.failure(
                Data.Builder().putString(KEY_RESULT_ERROR, "Audio file missing.").build()
            )
        }

        val securePrefs = SecurePrefs(applicationContext)
        if (!securePrefs.hasApiKey()) {
            // Without a valid key we cannot recover; delete orphaned file and stop retrying.
            FileCacheManager(applicationContext).deleteFile(audioFile)
            return Result.failure(
                Data.Builder().putString(KEY_RESULT_ERROR, "API key missing.").build()
            )
        }

        val repository = GroqRepository(
            apiKeyProvider = { securePrefs.getApiKey() },
            fileCacheManager = FileCacheManager(applicationContext),
            retryScheduler = null,
            networkStatusProvider = AndroidNetworkStatusProvider(applicationContext)
        )

        return when (val result = repository.transcribe(
            audioFile = audioFile,
            model = model,
            language = language,
            shouldQueueOnNetworkFailure = false
        )) {
            is TranscriptionResult.Success -> {
                Result.success(
                    Data.Builder()
                        .putString(KEY_RESULT_TEXT, result.text)
                        .putString(KEY_RESULT_WARNING, result.warning)
                        .putBoolean(KEY_RESULT_IS_PARTIAL, result.isPartial)
                        .build()
                )
            }

            is TranscriptionResult.Queued -> Result.retry()

            is TranscriptionResult.Failure -> {
                val shouldRetry = result.httpCode == 429 ||
                    (result.httpCode != null && result.httpCode >= 500) ||
                    result.httpCode == null

                if (shouldRetry) {
                    Result.retry()
                } else {
                    Result.failure(
                        Data.Builder().putString(KEY_RESULT_ERROR, result.message).build()
                    )
                }
            }
        }
    }
}
