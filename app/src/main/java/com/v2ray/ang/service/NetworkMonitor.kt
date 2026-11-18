package com.v2ray.ang.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.v2ray.ang.AppConfig
import com.v2ray.ang.extension.delay
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * Watches the network that carries the tunnel and reports topology changes.
 *
 * Cellular -> Wi-Fi is a make-before-break handover: the new network is announced while the old one
 * is still connected, so the socket to the server is never reset and the core keeps using a dead
 * connection. Deciding that a handover happened is what this class is for, acting on it is not.
 *
 * Only used from Android P and above, see CoreServiceManager.startNetworkMonitor().
 * After the debounce window, on a background thread, a handover is first claimed with [claimHandover]
 * while this monitor is registered, under the gate that [unregister] closes first: it has to be short,
 * and once unregister() has returned no handover is claimed. Only a claimed handover goes on to
 * [onHandover], which may block. Both are passed this monitor. [describe] names the run mode and the
 * profile of the service it watches, for its failure logs.
 */
class NetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val onUnderlyingNetworksChanged: (Array<Network>?) -> Unit,
    private val claimHandover: (NetworkMonitor) -> Boolean,
    private val onHandover: (NetworkMonitor) -> Unit,
    private val describe: () -> String,
    private val buildRequest: () -> NetworkRequest = {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    },
    private val handoverDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val handoverDebounceMs: Long = HANDOVER_DEBOUNCE_MS,
    private val logInfo: (String) -> Unit = { message -> LogUtil.i(AppConfig.TAG, message) },
) {
    private companion object {
        const val HANDOVER_DEBOUNCE_MS = 1000L
    }

    // PattNG: the network callbacks run on a thread of ConnectivityManager, register and unregister on the main
    // thread, so the state below is read and written under this lock.
    private val lock = Any()
    private var upstream: Network? = null
    private var lastReportedUnderlyingNetwork: Network? = null
    private var hasDispatchedNull = false
    private var handoverJob: Job? = null
    private var registered = false

    /** PattNG: lets a handover be claimed from register() until unregister(), see [claimHandover]. */
    private val gate = HandoverGate()

    /** PattNG: owns the handover work, which [unregister] cancels. */
    private val handoverScope = CoroutineScope(SupervisorJob() + handoverDispatcher)

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private val request by lazy { buildRequest() }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous: Network?
            val shouldDispatch: Boolean
            synchronized(lock) {
                if (!registered) return
                previous = upstream
                upstream = network
                shouldDispatch = lastReportedUnderlyingNetwork != network
                if (shouldDispatch) {
                    lastReportedUnderlyingNetwork = network
                    hasDispatchedNull = false
                }
            }
            if (shouldDispatch) {
                onUnderlyingNetworksChanged(arrayOf(network))
            }
            if (previous != null && previous != network) {
                scheduleHandover(network)
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val shouldDispatch = synchronized(lock) {
                if (!registered) return
                val activeUpstream = upstream
                if (activeUpstream != null && activeUpstream != network) {
                    return
                }
                if (activeUpstream == null) {
                    upstream = network
                }
                if (lastReportedUnderlyingNetwork != network) {
                    lastReportedUnderlyingNetwork = network
                    hasDispatchedNull = false
                    true
                } else {
                    false
                }
            }
            if (shouldDispatch) {
                onUnderlyingNetworksChanged(arrayOf(network))
            }
        }

        override fun onLost(network: Network) {
            val shouldDispatchNull = synchronized(lock) {
                if (!registered) return
                val activeUpstream = upstream
                if (activeUpstream != null && activeUpstream != network) {
                    // Ignore delayed onLost from an old network after a new upstream already took over.
                    return
                }
                upstream = null
                if (lastReportedUnderlyingNetwork != null || !hasDispatchedNull) {
                    lastReportedUnderlyingNetwork = null
                    hasDispatchedNull = true
                    true
                } else {
                    false
                }
            }
            if (shouldDispatchNull) {
                onUnderlyingNetworksChanged(null)
            }
        }
    }

    /**
     * Starts watching. Safe to call more than once, only the first call registers.
     */
    fun register() {
        synchronized(lock) {
            if (registered) return
            try {
                connectivity.requestNetwork(request, callback)
                registered = true
                lastReportedUnderlyingNetwork = null
                hasDispatchedNull = false
                gate.open()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "NetworkMonitor: ${describe()}, register: requesting the network failed", e)
            }
        }
    }

    /**
     * Stops watching and drops the tracked state. Safe to call more than once. No handover is claimed once
     * it has returned: it closes the gate first, waiting for a claim under way, see [claimHandover].
     */
    fun unregister() {
        synchronized(lock) {
            // PattNG: closed under the lock it was opened under; a claim never takes this lock, so the wait is short.
            gate.close()
            handoverJob?.cancel()
            handoverJob = null
            handoverScope.coroutineContext.cancelChildren()
            upstream = null
            lastReportedUnderlyingNetwork = null
            hasDispatchedNull = false
            if (!registered) return
            registered = false
        }
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "NetworkMonitor: ${describe()}, unregister: unregistering the callback failed", e)
        }
    }

    private fun scheduleHandover(network: Network) {
        logInfo("NetworkMonitor: Upstream is now $network")
        synchronized(lock) {
            // PattNG: a network callback that arrives after unregister() schedules nothing.
            if (!registered) return
            handoverJob?.cancel()
            handoverJob = handoverScope.launch {
                try {
                    delay(handoverDebounceMs)
                    // PattNG: cancelling ends the wait above, not a handover that has just come out of it. The handover
                    // goes on only once claimed under the gate, which unregister() closes first: an unregistered monitor
                    // claims nothing and calls nothing.
                    gate.runClaimed({ claimHandover(this@NetworkMonitor) }) { onHandover(this@NetworkMonitor) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "NetworkMonitor: ${describe()}, handover: handling the upstream change failed", e)
                }
            }
        }
    }
}
