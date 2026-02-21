package com.groqvoice.keyboard.utils

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility for appending transcription timestamps to an audit log file in internal storage.
 * Per TSD Section 7.2, this logs timestamps only, never audio content or transcribed text.
 */
class AuditLogger(private val context: Context) {

    private val logFile: File by lazy {
        File(context.filesDir, "transcription_audit_log.txt")
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Appends the current date/time to the audit log. */
    fun logTranscription() {
        try {
            val timestamp = dateFormat.format(Date())
            logFile.appendText("Transcription at $timestamp\n")
        } catch (e: Exception) {
            // Ignore logging errors to prevent breaking the core UX
        }
    }

    /** Clears the audit log. */
    fun clearLog() {
        try {
            if (logFile.exists()) {
                logFile.writeText("")
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    /** Returns the contents of the audit log (or an empty string if it doesn't exist). */
    fun readLog(): String {
        return try {
            if (logFile.exists()) logFile.readText() else ""
        } catch (e: Exception) {
            ""
        }
    }
}
