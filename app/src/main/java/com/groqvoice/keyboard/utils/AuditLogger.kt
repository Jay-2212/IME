package com.groqvoice.keyboard.utils

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility for appending local transcription history to internal storage.
 * Data stays on-device and is user-clearable from settings.
 */
class AuditLogger(private val context: Context) {

    data class Entry(
        val timestamp: String,
        val transcription: String
    )

    private val logFile: File by lazy {
        File(context.filesDir, "transcription_audit_log.txt")
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Appends timestamp + transcription text to the audit log. */
    fun logTranscription(text: String) {
        try {
            val normalizedText = text
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
            if (normalizedText.isBlank()) return
            val timestamp = dateFormat.format(Date())
            logFile.appendText("$timestamp\t$normalizedText\n")
        } catch (_: Exception) {
            // Ignore logging errors to prevent breaking the core UX
        }
    }

    /** Clears the audit log. */
    fun clearLog() {
        try {
            if (logFile.exists()) {
                logFile.writeText("")
            }
        } catch (_: Exception) {
            // Ignore
        }
    }

    fun readEntries(): List<Entry> {
        return try {
            if (!logFile.exists()) return emptyList()
            logFile.readLines()
                .mapNotNull { line ->
                    if (line.isBlank()) return@mapNotNull null
                    val split = line.split('\t', limit = 2)
                    if (split.size == 2) {
                        Entry(timestamp = split[0], transcription = split[1])
                    } else {
                        Entry(timestamp = dateFormat.format(Date()), transcription = line)
                    }
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Returns a user-facing formatted transcription history string. */
    fun readLog(): String {
        val entries = readEntries()
        if (entries.isEmpty()) return ""
        return entries.joinToString("\n\n") { entry ->
            "${entry.timestamp}\n${entry.transcription}"
        }
    }
}
