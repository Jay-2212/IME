package com.groqvoice.keyboard.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Centralises all runtime permission checks and requests for GroqVoice.
 *
 * TSD Section 2.1 Step 1 — Permissions Required:
 *  - RECORD_AUDIO (runtime)
 *  - POST_NOTIFICATIONS (runtime, API 33+)
 *  - INTERNET (manifest-only, not runtime)
 *  - VIBRATE (manifest-only, not runtime)
 */
class PermissionManager(private val context: Context) {

    companion object {
        const val REQUEST_CODE_AUDIO = 1001
        const val REQUEST_CODE_NOTIFICATIONS = 1002
        const val REQUEST_CODE_ALL = 1003
    }

    /** Returns true if RECORD_AUDIO has been granted. */
    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    /** Returns true if POST_NOTIFICATIONS has been granted (only relevant on API 33+). */
    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Permission not required below API 33
        }
    }

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
     * Requests POST_NOTIFICATIONS on API 33+; no-op on earlier versions.
     */
    fun requestNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_CODE_NOTIFICATIONS
            )
        }
    }

    /** Requests all runtime permissions at once. */
    fun requestAllPermissions(activity: Activity) {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

        ActivityCompat.requestPermissions(activity, permissions, REQUEST_CODE_ALL)
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
