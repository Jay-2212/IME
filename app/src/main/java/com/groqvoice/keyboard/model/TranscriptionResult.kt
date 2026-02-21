package com.groqvoice.keyboard.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Top-level result returned by [com.groqvoice.keyboard.api.GroqApiService].
 * Maps to the Groq /audio/transcriptions JSON response (TSD Appendix A).
 */
@JsonClass(generateAdapter = true)
data class TranscriptionResponse(
    /** The transcribed text. Never null on a 200 response. */
    val text: String,

    /** Optional Groq-specific metadata envelope (usage stats, request ID). */
    @Json(name = "x_groq")
    val xGroq: GroqMetadata? = null,

    /**
     * Optional server warning.
     *
     * TSD Section 5.1 defines "Partial Transcription" as a 200 response with a warning field.
     * The repository inspects this field to classify a response as partial.
     */
    val warning: String? = null
)

/**
 * Groq metadata envelope returned inside the transcription response.
 */
@JsonClass(generateAdapter = true)
data class GroqMetadata(
    val id: String,
    val usage: UsageStats
)

/**
 * Token and timing usage stats for a transcription request.
 */
@JsonClass(generateAdapter = true)
data class UsageStats(
    @Json(name = "queue_time") val queueTime: Double,
    @Json(name = "prompt_tokens") val promptTokens: Int,
    @Json(name = "prompt_time") val promptTime: Double,
    @Json(name = "completion_tokens") val completionTokens: Int,
    @Json(name = "completion_time") val completionTime: Double,
    @Json(name = "total_tokens") val totalTokens: Int,
    @Json(name = "total_time") val totalTime: Double
)

/**
 * Domain-level wrapper that carries either a successful transcription or an error.
 * Used by [com.groqvoice.keyboard.api.GroqRepository] to communicate results upstream.
 */
sealed class TranscriptionResult {
    /**
     * Successful transcription with normalized text and optional warning/metadata.
     *
     * @param text Final text to commit to the editor (already post-processed by the repository).
     * @param metadata Optional Groq `x_groq` usage metadata.
     * @param warning Optional warning returned by Groq.
     * @param isPartial True when server warning indicates a partial transcription.
     */
    data class Success(
        val text: String,
        val metadata: GroqMetadata? = null,
        val warning: String? = null,
        val isPartial: Boolean = false
    ) : TranscriptionResult()

    /**
     * Upload was deferred and persisted for retry using WorkManager.
     *
     * @param message User-facing status text.
     * @param workRequestId Optional id for telemetry/debugging.
     */
    data class Queued(
        val message: String,
        val workRequestId: String? = null
    ) : TranscriptionResult()

    /** A known API/network error with an appropriate user-facing [message]. */
    data class Failure(
        val message: String,
        val httpCode: Int? = null,
        val retryAfterSeconds: Int? = null,
        val isQuotaExceeded: Boolean = false
    ) : TranscriptionResult()
}
