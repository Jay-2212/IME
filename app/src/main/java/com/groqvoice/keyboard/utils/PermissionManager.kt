package com.groqvoice.keyboard.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Centralises all runtime permission checks and requests for GroqVoice.
 *
 * TSD Section 2.1 Step 1 — Permissions Required:
 *  - RECORD_AUDIO (runtime)
 *  - INTERNET (manifest-only, not runtime)
 *  - VIBRATE (manifest-only, not runtime)
 */
class PermissionManager(private val context: Context) {

    companion object {
        const val REQUEST_CODE_AUDIO = 1001
    }

    /** Returns true if RECORD_AUDIO has been granted. */
    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Requests RECORD_AUDIO from the given [activity].
     * Show rationale before calling this if [shouldShowRationale] returns true.
     */
    fun requestAudioPermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_CODE_AUDIO
        )
    }

    /**
     * Returns true if the system "Don't ask again" flag has been set for RECORD_AUDIO.
     * In this case the user must navigate to app settings manually.
     */
    fun shouldShowAudioRationale(activity: Activity): Boolean =
        ActivityCompat.shouldShowRequestPermissionRationale(
            activity, Manifest.permission.RECORD_AUDIO
        )
}
