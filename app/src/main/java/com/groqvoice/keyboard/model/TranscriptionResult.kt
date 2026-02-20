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
    val xGroq: GroqMetadata? = null
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
    /** Successful transcription with committed text. */
    data class Success(val text: String, val metadata: GroqMetadata? = null) : TranscriptionResult()

    /** A known API/network error with an appropriate user-facing [message]. */
    data class Failure(val message: String, val httpCode: Int? = null) : TranscriptionResult()
}
