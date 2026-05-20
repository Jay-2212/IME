package com.groqvoice.keyboard.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat

/**
 * Abstraction for network reachability checks.
 *
 * Repository logic uses this to decide whether a failed upload should be queued for WorkManager
 * retry (offline) or retried in-process (transient online failure).
 */
interface NetworkStatusProvider {
    fun isNetworkAvailable(): Boolean
}

/**
 * Android implementation backed by [ConnectivityManager].
 */
class AndroidNetworkStatusProvider(
    context: Context
) : NetworkStatusProvider {

    private val connectivityManager =
        ContextCompat.getSystemService(context, ConnectivityManager::class.java)

    override fun isNetworkAvailable(): Boolean {
        val cm = connectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
