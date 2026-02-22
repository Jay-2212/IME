package com.groqvoice.keyboard

import android.app.Application
import android.os.Build
import com.google.android.material.color.DynamicColors
import com.groqvoice.keyboard.utils.FileCacheManager
import com.groqvoice.keyboard.utils.SecurePrefs

/**
 * Application entry point.
 * Performs one-time initialization on app startup.
 */
class GroqVoiceApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SecurePrefs(this).isSystemColorsEnabled()
        ) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
        // Clean up any orphaned temp audio files from a previous crashed session (TSD 6.1)
        FileCacheManager(this).cleanOrphanedFiles()
    }
}
