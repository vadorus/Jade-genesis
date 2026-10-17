package com.jadegenesis.mobile.node

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Listens to the PHYSICAL networks (Wi-Fi, mobile data, Ethernet; never the
 * VPN) and calls [onRestored] when a validated one comes back after a loss
 * (see [PhysicalNetworkMonitor] and [NetworkRestoreDetector] for the rules).
 *
 * 0.1.26: 0.1.25 listened to the default network, which is the Tailscale VPN
 * on the Pixel, and never saw the cut. Every step is now written to the
 * diagnostic log through [log] (event codes and counters only).
 *
 * [onRestored] and [onPhysicalLost] may run on a ConnectivityManager thread or
 * on the main thread: the receiver must be thread-safe and hand the work over
 * to its own scope. Registration failures are logged and reported, never
 * thrown: without this watcher Jade still re-checks its nodes on open, on
 * "Actualiser" and before each message.
 */
class NetworkRestoreWatcher(
    context: Context,
    private val onRestored: () -> Unit,
    private val onPhysicalLost: () -> Unit = {},
    private val log: (event: String, metadata: Map<String, Any?>) -> Unit = { _, _ -> },
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
        onRestored = {
            log(EVENT_TRIGGERED, emptyMap())
            onRestored()
        },
        onDeferred = { delayMs ->
            log(EVENT_DEFERRED, mapOf("delay_ms" to delayMs))
        }
    )
    private val monitor: PhysicalNetworkMonitor = PhysicalNetworkMonitor(
        detector = detector,
        onPhysicalLost = onPhysicalLost,
        log = log
    )
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            monitor.onNetworkChanged(
                id = network.toString(),
                usable = isUsable(capabilities),
                transport = transportOf(capabilities),
                nowMs = SystemClock.elapsedRealtime()
            )
        }

        override fun onLost(network: Network) {
            monitor.onNetworkLost(network.toString(), SystemClock.elapsedRealtime())
        }
    }

    @Synchronized
    fun start() {
        if (registered) return
        val manager = connectivity ?: run {
            val error = IllegalStateException("connectivity_service_unavailable")
            log(EVENT_FAILED, mapOf("error" to "connectivity_service_unavailable"))
            onRegistrationFailed(error)
            return
        }
        // Read the current state BEFORE registering: opening the app with no
        // physical network must count as a loss, otherwise the first network
        // that arrives would not trigger a re-check.
        val initial = runCatching { validatedPhysicalNetworks(manager) }.getOrDefault(emptyList())
        monitor.start(initial)
        val defaultCaps = runCatching {
            manager.getNetworkCapabilities(manager.activeNetwork)
        }.getOrNull()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onSuccess {
                registered = true
                // Drop a snapshot network that vanished before the registration
                // (no callback will ever report its loss). Codex review of #79, P1.
                val presentNow = runCatching { validatedPhysicalNetworks(manager) }
                    .getOrDefault(emptyList())
                monitor.reconcileAfterRegistration(presentNow, SystemClock.elapsedRealtime())
                log(
                    EVENT_REGISTERED,
                    mapOf(
                        "physical_validated" to initial.size,
                        // Evidence for the VPN hypothesis of 0.1.26, nothing more.
                        "default_is_vpn" to (defaultCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true),
                        "default_validated" to (defaultCaps?.let(::isUsable) == true)
                    )
                )
            }
            .onFailure { error ->
                log(EVENT_FAILED, mapOf("error" to error.javaClass.simpleName))
                onRegistrationFailed(error)
            }
    }

    @Synchronized
    fun stop() {
        detector.stop()
        if (!registered) return
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
        registered = false
    }

    @Suppress("DEPRECATION") // allNetworks: still the only one-shot listing on API 31+.
    private fun validatedPhysicalNetworks(manager: ConnectivityManager): List<String> =
        manager.allNetworks.filter { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@filter false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                isUsable(caps)
        }.map { it.toString() }

    private fun isUsable(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private fun transportOf(capabilities: NetworkCapabilities): String = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }

    companion object {
        const val EVENT_REGISTERED = "network_watch_registered"
        const val EVENT_FAILED = "network_watch_failed"
        const val EVENT_DEFERRED = "network_restore_deferred"
        const val EVENT_TRIGGERED = "network_restore_triggered"
    }
}
