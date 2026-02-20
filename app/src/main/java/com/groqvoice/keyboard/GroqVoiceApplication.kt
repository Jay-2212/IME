package com.groqvoice.keyboard

import android.app.Application
import com.groqvoice.keyboard.utils.FileCacheManager

/**
 * Application entry point.
 * Performs one-time initialization on app startup.
 */
class GroqVoiceApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Clean up any orphaned temp audio files from a previous crashed session (TSD 6.1)
        FileCacheManager(this).cleanOrphanedFiles()
    }
}
