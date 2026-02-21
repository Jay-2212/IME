package com.groqvoice.keyboard

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.groqvoice.keyboard.utils.FileCacheManager

/**
 * Application entry point.
 * Performs one-time initialization on app startup.
 */
class GroqVoiceApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Apply Material You dynamic colors to activities on Android 12+ when available.
        DynamicColors.applyToActivitiesIfAvailable(this)
        // Clean up any orphaned temp audio files from a previous crashed session (TSD 6.1)
        FileCacheManager(this).cleanOrphanedFiles()
    }
}
