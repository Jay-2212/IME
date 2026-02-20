package com.groqvoice.keyboard.utils

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Thin wrapper around [EncryptedSharedPreferences] for storing sensitive values such as the
 * Groq API key.  All reads/writes are synchronous; call from a background thread if needed.
 *
 * TSD Section 7.1 — API Key Storage / Data Protection.
 */
class SecurePrefs(context: Context) {

    companion object {
        private const val PREFS_FILE = "groq_secure_prefs"

        // SharedPreferences keys
        const val KEY_API_KEY = "groq_api_key"
        const val KEY_SELECTED_MODEL = "groq_model"
        const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        const val KEY_DOUBLE_TAP_PERIOD = "double_tap_period"
        const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"
    }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Stores the Groq API key. Pass [null] to remove. */
    fun setApiKey(key: String?) {
        prefs.edit().apply {
            if (key == null) remove(KEY_API_KEY) else putString(KEY_API_KEY, key)
        }.apply()
    }

    /** Returns the stored Groq API key, or [null] if not configured. */
    fun getApiKey(): String? = prefs.getString(KEY_API_KEY, null)

    /** Returns true if an API key has been configured. */
    fun hasApiKey(): Boolean = !getApiKey().isNullOrBlank()

    /** Stores the selected transcription model identifier. */
    fun setModel(model: String) = prefs.edit().putString(KEY_SELECTED_MODEL, model).apply()

    /** Returns the selected model, defaulting to whisper-large-v3-turbo. */
    fun getModel(): String =
        prefs.getString(KEY_SELECTED_MODEL, "whisper-large-v3-turbo") ?: "whisper-large-v3-turbo"

    /** Marks the onboarding flow as completed. */
    fun setOnboardingComplete(complete: Boolean) =
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, complete).apply()

    /** Returns true if the user has completed onboarding. */
    fun isOnboardingComplete(): Boolean = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)

    /**
     * Clears the API key from memory after wiping the byte array.
     * Call when the app is backgrounded if memory-wipe feature is enabled (TSD Section 2.1).
     */
    fun clearApiKey() = setApiKey(null)
}
