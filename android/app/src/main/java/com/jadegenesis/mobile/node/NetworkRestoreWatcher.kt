package com.jadegenesis.mobile.node

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock

/**
 * Listens to the system default network and calls [onRestored] when a validated
 * network comes back after a loss (see [NetworkRestoreDetector] for the rules).
 *
 * The callback runs on a ConnectivityManager thread: the receiver must hand the
 * work over to its own scope. Registration failures are reported, never thrown:
 * without this watcher Jade still re-checks its nodes on open, on "Actualiser"
 * and before each message, exactly as in 0.1.24.
 */
class NetworkRestoreWatcher(
    context: Context,
    private val onRestored: () -> Unit,
    private val onRegistrationFailed: (Throwable) -> Unit = {}
) {
    private val connectivity =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val detector = NetworkRestoreDetector()
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (usable) {
                if (detector.onNetworkValidated(SystemClock.elapsedRealtime())) onRestored()
            } else {
                detector.onNetworkLost()
            }
        }

        override fun onLost(network: Network) {
            detector.onNetworkLost()
        }
    }

    @Synchronized
    fun start() {
        if (registered) return
        val manager = connectivity ?: run {
            onRegistrationFailed(IllegalStateException("connectivity_service_unavailable"))
            return
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { registered = true }
            .onFailure(onRegistrationFailed)
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
        registered = false
    }
}
