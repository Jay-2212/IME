package com.groqvoice.keyboard.api

import com.groqvoice.keyboard.model.TranscriptionResponse
import com.groqvoice.keyboard.model.TranscriptionResult
import com.groqvoice.keyboard.utils.FileCacheManager
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.delay
import okhttp3.ConnectionPool
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Repository responsible for all Groq API interactions.
 *
 * Phase 3 responsibilities (TSD Section 4.4, 5.1, 6.2, Appendix A):
 * - Multipart upload to `/audio/transcriptions`.
 * - `response_format=verbose_json` handling with `x_groq` metadata parsing.
 * - Client-side throttling (20 requests/minute).
 * - Resilient retry with exponential backoff + jitter for transient failures.
 * - Offline/timeout queueing via WorkManager.
 * - Strict temp-file cleanup and user-facing error mapping.
 */
class GroqRepository(
    private val apiKeyProvider: () -> String?,
    private val fileCacheManager: FileCacheManager,
    private val retryScheduler: TranscriptionRetryScheduler? = null,
    private val networkStatusProvider: NetworkStatusProvider? = null,
    baseUrl: String = BASE_URL,
    isDebug: Boolean = false,
    private val rateLimiter: RequestRateLimiter = GLOBAL_RATE_LIMITER,
    apiOverride: GroqApiService? = null
) {
    sealed class ApiKeyValidationResult {
        data object Valid : ApiKeyValidationResult()
        data object Unauthorized : ApiKeyValidationResult()
        data object NetworkError : ApiKeyValidationResult()
        data class HttpError(val code: Int) : ApiKeyValidationResult()
        data object UnknownError : ApiKeyValidationResult()
    }


    companion object {
        const val BASE_URL = "https://api.groq.com/openai/v1/"
        private const val MAX_RETRIES = 3
        private val RETRY_DELAYS_MS = longArrayOf(2_000L, 4_000L, 8_000L)

        private const val POOL_MAX_IDLE = 5
        private const val POOL_KEEP_ALIVE_MINUTES = 5L

        private const val GROQ_RATE_LIMIT_PER_MINUTE = 20
        private const val RATE_LIMIT_WINDOW_MS = 60_000L

        private const val RESPONSE_FORMAT_VERBOSE_JSON = "verbose_json"
        private const val DEFAULT_TEMPERATURE = "0"
        private const val DEFAULT_MODEL = "whisper-large-v3-turbo"

        private val WAV_CONTENT_TYPE = "audio/wav".toMediaType()
        private val FLAC_CONTENT_TYPE = "audio/flac".toMediaType()
        private val TEXT_CONTENT_TYPE = "text/plain".toMediaType()

        private val GLOBAL_RATE_LIMITER = RequestRateLimiter(
            maxRequests = GROQ_RATE_LIMIT_PER_MINUTE,
            windowMs = RATE_LIMIT_WINDOW_MS
        )
    }

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(
            ConnectionPool(
                POOL_MAX_IDLE,
                POOL_KEEP_ALIVE_MINUTES,
                TimeUnit.MINUTES
            )
        )
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(ApiKeyInterceptor(apiKeyProvider))
        .apply {
            if (isDebug) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    // Sensitive payloads/keys must never be logged.
                    level = HttpLoggingInterceptor.Level.HEADERS
                })
            }
        }
        .build()

    private val api: GroqApiService = apiOverride ?: Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(GroqApiService::class.java)

    /**
     * Uploads [audioFile] and returns typed transcription result states.
     *
     * @param audioFile WAV/FLAC temp file created by AudioEncoder.
     * @param model Groq model name.
     * @param language Optional language hint ("en", "es", etc.).
     * @param shouldQueueOnNetworkFailure Whether offline/timeout errors should enqueue WorkManager.
     */
    suspend fun transcribe(
        audioFile: File,
        model: String = DEFAULT_MODEL,
        language: String? = null,
        shouldQueueOnNetworkFailure: Boolean = true
    ): TranscriptionResult {
        if (!audioFile.exists()) {
            return TranscriptionResult.Failure("Recording file is missing.")
        }

        var deleteFileOnExit = true

        try {
            val throttleDecision = rateLimiter.tryAcquire()
            if (!throttleDecision.allowed) {
                return TranscriptionResult.Failure(
                    message = "Too many requests. Try again in ${throttleDecision.retryAfterSeconds}s.",
                    httpCode = 429,
                    retryAfterSeconds = throttleDecision.retryAfterSeconds
                )
            }

            // If device is currently offline, defer immediately instead of wasting retries.
            if (shouldQueueOnNetworkFailure && retryScheduler != null &&
                networkStatusProvider?.isNetworkAvailable() == false
            ) {
                val requestId = retryScheduler.enqueue(audioFile, model, language)
                deleteFileOnExit = false
                return TranscriptionResult.Queued(
                    message = "No network — retry queued.",
                    workRequestId = requestId.toString()
                )
            }

            val filePart = MultipartBody.Part.createFormData(
                name = "file",
                filename = audioFile.name,
                body = audioFile.asRequestBody(resolveAudioContentType(audioFile))
            )
            val modelPart = model.toRequestBody(TEXT_CONTENT_TYPE)
            val languagePart = language?.toRequestBody(TEXT_CONTENT_TYPE)
            val temperaturePart = DEFAULT_TEMPERATURE.toRequestBody(TEXT_CONTENT_TYPE)
            val responseFormatPart = RESPONSE_FORMAT_VERBOSE_JSON.toRequestBody(TEXT_CONTENT_TYPE)

            for (attempt in 0 until MAX_RETRIES) {
                try {
                    val response = api.transcribeAudio(
                        file = filePart,
                        model = modelPart,
                        language = languagePart,
                        temperature = temperaturePart,
                        responseFormat = responseFormatPart
                    )

                    if (response.isSuccessful) {
                        val body = response.body()
                        return if (body != null) {
                            mapSuccess(body)
                        } else {
                            TranscriptionResult.Failure("Empty response from server.")
                        }
                    }

                    val failure = mapHttpFailure(response)
                    if (shouldRetry(failure.httpCode) && attempt < MAX_RETRIES - 1) {
                        delay(resolveRetryDelayMs(attempt, response.headers()["retry-after"]))
                        continue
                    }
                    return failure
                } catch (io: IOException) {
                    val isTimeout = io is SocketTimeoutException
                    val isLastAttempt = attempt == MAX_RETRIES - 1

                    if (!isLastAttempt) {
                        delay(resolveRetryDelayMs(attempt, retryAfterHeader = null))
                        continue
                    }

                    if (shouldQueueOnNetworkFailure && retryScheduler != null &&
                        (networkStatusProvider?.isNetworkAvailable() == false || isTimeout)
                    ) {
                        val requestId = retryScheduler.enqueue(audioFile, model, language)
                        deleteFileOnExit = false
                        return TranscriptionResult.Queued(
                            message = "Network unavailable — retry queued.",
                            workRequestId = requestId.toString()
                        )
                    }

                    return TranscriptionResult.Failure(
                        message = "Network error: ${io.message ?: "Unable to reach Groq."}"
                    )
                }
            }

            return TranscriptionResult.Failure("Transcription failed after retries.")
        } finally {
            // Keep queued files for WorkManager; cleanup all other paths.
            if (deleteFileOnExit) {
                fileCacheManager.deleteFile(audioFile)
            }
        }
    }

    /**
     * Live API key validation used during onboarding and settings.
     */
    suspend fun validateApiKey(): Boolean {
        return validateApiKeyDetailed() is ApiKeyValidationResult.Valid
    }

    /**
     * Live API key validation with failure classification for onboarding UX messaging.
     */
    suspend fun validateApiKeyDetailed(): ApiKeyValidationResult {
        return try {
            val response = api.listModels()
            when {
                response.isSuccessful -> ApiKeyValidationResult.Valid
                response.code() == 401 -> ApiKeyValidationResult.Unauthorized
                else -> ApiKeyValidationResult.HttpError(response.code())
            }
        } catch (_: IOException) {
            ApiKeyValidationResult.NetworkError
        } catch (_: Exception) {
            ApiKeyValidationResult.UnknownError
        }
    }

    private fun mapSuccess(body: TranscriptionResponse): TranscriptionResult.Success {
        val warning = body.warning?.trim()?.takeIf { it.isNotEmpty() }
        val isPartial = isPartialWarning(warning)
        val text = if (isPartial) appendEllipsisIfMissing(body.text) else body.text

        return TranscriptionResult.Success(
            text = text,
            metadata = body.xGroq,
            warning = warning,
            isPartial = isPartial
        )
    }

    private fun mapHttpFailure(response: Response<TranscriptionResponse>): TranscriptionResult.Failure {
        val code = response.code()
        val errorMessage = parseErrorMessage(response)
        val retryAfterSeconds = parseRetryAfterSeconds(response.headers()["retry-after"])

        return when (code) {
            401 -> TranscriptionResult.Failure(
                message = "Invalid API key.",
                httpCode = 401
            )

            413 -> TranscriptionResult.Failure(
                message = "Recording too large (max 25 MB).",
                httpCode = 413
            )

            429 -> {
                val quotaExceeded = isQuotaExceeded(errorMessage)
                TranscriptionResult.Failure(
                    message = if (quotaExceeded) {
                        "Quota exceeded — upgrade your Groq plan."
                    } else {
                        "Rate limit exceeded. Please retry shortly."
                    },
                    httpCode = 429,
                    retryAfterSeconds = retryAfterSeconds,
                    isQuotaExceeded = quotaExceeded
                )
            }

            in 500..599 -> TranscriptionResult.Failure(
                message = "Server error ($code).",
                httpCode = code
            )

            else -> TranscriptionResult.Failure(
                message = errorMessage ?: "Unexpected error ($code).",
                httpCode = code
            )
        }
    }

    private fun parseErrorMessage(response: Response<TranscriptionResponse>): String? {
        val rawBody = response.errorBody()?.string()?.trim().orEmpty()
        if (rawBody.isBlank()) return null

        val adapter = moshi.adapter(GroqErrorEnvelope::class.java)
        val parsed = runCatching { adapter.fromJson(rawBody) }.getOrNull()
        return parsed?.error?.message?.takeIf { it.isNotBlank() } ?: rawBody
    }

    private fun shouldRetry(code: Int?): Boolean {
        return code == 429 || (code != null && code >= 500)
    }

    private fun parseRetryAfterSeconds(header: String?): Int? {
        val seconds = header?.trim()?.toDoubleOrNull() ?: return null
        return seconds.toInt().coerceAtLeast(1)
    }

    private fun resolveRetryDelayMs(attempt: Int, retryAfterHeader: String?): Long {
        val retryAfterSeconds = parseRetryAfterSeconds(retryAfterHeader)
        if (retryAfterSeconds != null) {
            return retryAfterSeconds * 1_000L
        }

        val base = RETRY_DELAYS_MS.getOrElse(attempt) { RETRY_DELAYS_MS.last() }
        val jitter = Random.nextLong(0, (base / 4).coerceAtLeast(1L))
        return base + jitter
    }

    private fun resolveAudioContentType(file: File): MediaType {
        return if (file.extension.equals("flac", ignoreCase = true)) {
            FLAC_CONTENT_TYPE
        } else {
            WAV_CONTENT_TYPE
        }
    }

    private fun appendEllipsisIfMissing(text: String): String {
        val trimmed = text.trimEnd()
        return if (trimmed.endsWith("...")) trimmed else "$trimmed..."
    }

    private fun isPartialWarning(warning: String?): Boolean {
        val normalized = warning?.lowercase(Locale.US).orEmpty()
        return normalized.contains("partial") ||
            normalized.contains("incomplete") ||
            normalized.contains("truncat")
    }

    private fun isQuotaExceeded(errorMessage: String?): Boolean {
        val normalized = errorMessage?.lowercase(Locale.US).orEmpty()
        return normalized.contains("quota") ||
            normalized.contains("billing") ||
            normalized.contains("limit reached")
    }

    @JsonClass(generateAdapter = true)
    internal data class GroqErrorEnvelope(
        val error: GroqErrorBody? = null
    )

    @JsonClass(generateAdapter = true)
    internal data class GroqErrorBody(
        val message: String? = null
    )
}
