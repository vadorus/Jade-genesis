package com.jadegenesis.mobile.node

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Listens to the system default network and calls [onRestored] when a validated
 * network comes back after a loss (see [NetworkRestoreDetector] for the rules).
 *
 * [onRestored] may run on a ConnectivityManager thread or on the main thread:
 * the receiver must be thread-safe and hand the work over to its own scope.
 * Registration failures are reported, never thrown: without this watcher Jade
 * still re-checks its nodes on open, on "Actualiser" and before each message,
 * exactly as in 0.1.24.
 */
class NetworkRestoreWatcher(
    context: Context,
    private val onRestored: () -> Unit,
    private val onRegistrationFailed: (Throwable) -> Unit = {}
) {
    private val connectivity =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val retryDue: Runnable = Runnable { detector.onRetryDue(SystemClock.elapsedRealtime()) }
    private val detector: NetworkRestoreDetector = NetworkRestoreDetector(
        scheduler = object : NetworkRestoreDetector.RetryScheduler {
            override fun schedule(delayMs: Long) {
                handler.postDelayed(retryDue, delayMs.coerceAtLeast(0L))
            }

            override fun cancel() {
                handler.removeCallbacks(retryDue)
            }
        },
        onRestored = onRestored
    )
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (isUsable(capabilities)) {
                detector.onNetworkValidated(SystemClock.elapsedRealtime())
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
        // Read the current state BEFORE registering: Android calls back
        // immediately only when a default network exists. Opening the app
        // with no network must count as a loss, otherwise the first network
        // that arrives would not trigger a re-check.
        val validatedNow = runCatching {
            manager.getNetworkCapabilities(manager.activeNetwork)?.let(::isUsable) ?: false
        }.getOrDefault(false)
        detector.onStart(validatedNow)
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { registered = true }
            .onFailure(onRegistrationFailed)
    }

    @Synchronized
    fun stop() {
        detector.stop()
        if (!registered) return
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
        registered = false
    }

    private fun isUsable(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
