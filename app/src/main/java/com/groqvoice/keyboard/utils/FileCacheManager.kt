package com.groqvoice.keyboard.utils

import android.content.Context
import java.io.File

/**
 * Manages temporary audio files produced during recording sessions.
 *
 * Responsibilities:
 *  - Provide a consistent [File] reference for the current recording session.
 *  - Delete the file immediately after a successful/failed transcription.
 *  - Clean up orphaned files on app startup (TSD Section 6.1 — File Cleanup).
 */
class FileCacheManager(private val context: Context) {

    companion object {
        private const val AUDIO_CACHE_DIR = "audio_cache"
        private const val TEMP_FILE_PREFIX = "groq_audio_"
        private const val WAV_EXTENSION = ".wav"
        private const val FLAC_EXTENSION = ".flac"

        /** Files older than this threshold are considered orphaned and safe to delete. */
        private const val ORPHAN_AGE_MS = 15 * 60 * 1000L // 15 minutes
    }

    private val cacheDir: File
        get() = File(context.cacheDir, AUDIO_CACHE_DIR).also { it.mkdirs() }

    /**
     * Creates and returns a new unique temp file for a WAV recording.
     * The caller is responsible for deleting it via [deleteFile] when done.
     */
    fun createTempWavFile(): File =
        File.createTempFile(TEMP_FILE_PREFIX, WAV_EXTENSION, cacheDir)

    /**
     * Creates and returns a new unique temp file for a FLAC recording.
     */
    fun createTempFlacFile(): File =
        File.createTempFile(TEMP_FILE_PREFIX, FLAC_EXTENSION, cacheDir)

    /**
     * Deletes a temp audio file and securely zeroes its contents before deletion.
     * Safe to call even if [file] does not exist.
     *
     * TSD Section 7.1 — Memory Safety.
     */
    fun deleteFile(file: File) {
        if (file.exists()) {
            // Overwrite with zeros before deletion to reduce data recovery risk
            try {
                file.writeBytes(ByteArray(file.length().toInt()))
            } catch (_: Exception) {
                // Best-effort; proceed with deletion regardless
            }
            file.delete()
        }
    }

    /**
     * Scans the audio cache directory and deletes any files older than [ORPHAN_AGE_MS].
     * Called by [com.groqvoice.keyboard.GroqVoiceApplication] on startup.
     */
    fun cleanOrphanedFiles() {
        val cutoff = System.currentTimeMillis() - ORPHAN_AGE_MS
        cacheDir.listFiles()
            ?.filter { it.isFile && it.lastModified() < cutoff }
            ?.forEach { deleteFile(it) }
    }
}
