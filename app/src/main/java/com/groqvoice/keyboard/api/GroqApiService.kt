package com.groqvoice.keyboard.api

import com.groqvoice.keyboard.model.TranscriptionResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/**
 * Retrofit interface for the Groq audio transcription endpoint.
 *
 * TSD Appendix A — Groq API Specifications.
 * Endpoint: POST https://api.groq.com/openai/v1/audio/transcriptions
 */
interface GroqApiService {

    /**
     * Transcribes an audio file using Groq's Whisper API.
     *
     * @param file     The audio file part (WAV or FLAC, max 25 MB).
     * @param model    Whisper model identifier (e.g. "whisper-large-v3-turbo").
     * @param language Optional BCP-47 language code (e.g. "en"). Pass null for auto-detect.
     * @param temperature Sampling temperature 0.0–1.0 (default 0.0 for accuracy).
     * @param responseFormat Response format; use "json" (default) or "verbose_json".
     * @return Retrofit [Response] wrapping [TranscriptionResponse] (or error body on failure).
     */
    @Multipart
    @POST("audio/transcriptions")
    suspend fun transcribeAudio(
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("language") language: RequestBody? = null,
        @Part("temperature") temperature: RequestBody? = null,
        @Part("response_format") responseFormat: RequestBody? = null
    ): Response<TranscriptionResponse>

    /**
     * Fetches the list of available models (used for API key validation in onboarding).
     *
     * TSD Section 2.1 Step 2 — Live validation test call to /models endpoint.
     */
    @GET("models")
    suspend fun listModels(): Response<Unit>
}
