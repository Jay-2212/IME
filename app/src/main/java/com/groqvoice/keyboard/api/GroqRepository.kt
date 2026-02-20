package com.groqvoice.keyboard.api

import com.groqvoice.keyboard.model.TranscriptionResult
import com.groqvoice.keyboard.utils.FileCacheManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Single-source-of-truth for all Groq API interactions.
 *
 * Responsibilities:
 *  - Configure Retrofit + OkHttp (connection pool, timeouts, interceptors).
 *  - Implement retry/backoff logic for transient errors (TSD Section 5.1, 6.2).
 *  - Map HTTP errors to user-facing [TranscriptionResult] types.
 *  - Delete temporary audio files after a successful or failed upload.
 *
 * TSD Section 1.2, Appendix A.
 */
class GroqRepository(
    private val apiKeyProvider: () -> String?,
    private val fileCacheManager: FileCacheManager,
    baseUrl: String = BASE_URL,
    isDebug: Boolean = false
) {

    companion object {
        const val BASE_URL = "https://api.groq.com/openai/v1/"

        // Retry configuration (TSD Section 5.1 / 6.2)
        private const val MAX_RETRIES = 3
        private val RETRY_DELAYS_MS = longArrayOf(2_000, 4_000, 8_000)

        // OkHttp connection pool (TSD Section 6.2)
        private const val POOL_MAX_IDLE = 5
        private val POOL_KEEP_ALIVE_MINUTES = 5L

        private val AUDIO_CONTENT_TYPE = "audio/wav".toMediaType()
        private val TEXT_CONTENT_TYPE = "text/plain".toMediaType()
    }

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(
            okhttp3.ConnectionPool(POOL_MAX_IDLE, POOL_KEEP_ALIVE_MINUTES, TimeUnit.MINUTES)
        )
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(ApiKeyInterceptor(apiKeyProvider))
        .apply {
            if (isDebug) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    // Log headers only in debug; NEVER log body (could contain the API key)
                    level = HttpLoggingInterceptor.Level.HEADERS
                })
            }
        }
        .build()

    private val api: GroqApiService = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(GroqApiService::class.java)

    /**
     * Uploads [audioFile] to Groq and returns a [TranscriptionResult].
     *
     * Implements exponential backoff for 429/5xx errors (TSD Section 5.1).
     * The [audioFile] is deleted after the call regardless of outcome (TSD Section 6.1).
     *
     * @param audioFile Temporary WAV/FLAC file produced by [com.groqvoice.keyboard.audio.AudioEncoder].
     * @param model     Whisper model identifier.
     * @param language  Optional ISO-639-1 language hint.
     */
    suspend fun transcribe(
        audioFile: File,
        model: String = "whisper-large-v3-turbo",
        language: String? = null
    ): TranscriptionResult {
        var lastResult: TranscriptionResult = TranscriptionResult.Failure("Unknown error")

        repeat(MAX_RETRIES) { attempt ->
            try {
                val filePart = MultipartBody.Part.createFormData(
                    name = "file",
                    filename = audioFile.name,
                    body = audioFile.asRequestBody(AUDIO_CONTENT_TYPE)
                )
                val modelPart = model.toRequestBody(TEXT_CONTENT_TYPE)
                val langPart = language?.toRequestBody(TEXT_CONTENT_TYPE)

                val response = api.transcribeAudio(
                    file = filePart,
                    model = modelPart,
                    language = langPart
                )

                lastResult = when {
                    response.isSuccessful -> {
                        val body = response.body()
                        if (body != null) {
                            TranscriptionResult.Success(body.text, body.xGroq)
                        } else {
                            TranscriptionResult.Failure("Empty response from server.")
                        }
                    }
                    response.code() == 401 -> {
                        TranscriptionResult.Failure("Invalid API key.", 401)
                    }
                    response.code() == 413 -> {
                        TranscriptionResult.Failure("Recording too large (max 25 MB).", 413)
                    }
                    response.code() == 429 -> {
                        // Rate limited — honour retry-after header or use backoff
                        val retryAfter = response.headers()["retry-after"]?.toLongOrNull()
                            ?: RETRY_DELAYS_MS.getOrElse(attempt) { 8_000L }
                        delay(retryAfter * 1_000)
                        TranscriptionResult.Failure("Rate limit exceeded.", 429)
                    }
                    response.code() >= 500 -> {
                        delay(RETRY_DELAYS_MS.getOrElse(attempt) { 8_000L })
                        TranscriptionResult.Failure("Server error (${response.code()}).", response.code())
                    }
                    else -> TranscriptionResult.Failure("Unexpected error (${response.code()}).", response.code())
                }

                // Do not retry on success or permanent client errors
                if (lastResult is TranscriptionResult.Success ||
                    (lastResult as? TranscriptionResult.Failure)?.httpCode in setOf(401, 413)
                ) return@repeat

            } catch (e: java.io.IOException) {
                lastResult = TranscriptionResult.Failure("Network error: ${e.message}")
                delay(RETRY_DELAYS_MS.getOrElse(attempt) { 8_000L })
            }
        }

        // Always clean up temp file (TSD 6.1)
        fileCacheManager.deleteFile(audioFile)

        return lastResult
    }

    /**
     * Validates the API key by making a lightweight test call to the /models endpoint.
     * Returns true if the key is accepted (2xx), false otherwise.
     *
     * TSD Section 2.1 Step 2.
     */
    suspend fun validateApiKey(): Boolean {
        return try {
            val response = api.listModels()
            response.code() != 401
        } catch (e: Exception) {
            false
        }
    }
}
