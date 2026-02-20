package com.groqvoice.keyboard.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that injects the Groq API key as a Bearer token into every request.
 *
 * The key is loaded lazily from [keyProvider] so that the [GroqApiService] can be
 * constructed once at app startup and still pick up key changes at runtime (e.g. after
 * the user updates their key in Settings).
 *
 * TSD Section 1.2 — ApiKeyInterceptor / TSD Section 7.1 — never log the key.
 */
class ApiKeyInterceptor(
    /** Lambda that returns the current API key, or null if not configured. */
    private val keyProvider: () -> String?
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val apiKey = keyProvider()

        val request = if (!apiKey.isNullOrBlank()) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $apiKey")
                .build()
        } else {
            chain.request()
        }

        return chain.proceed(request)
    }
}
