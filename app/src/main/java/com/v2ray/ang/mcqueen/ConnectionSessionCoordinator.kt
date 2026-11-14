package com.v2ray.ang.mcqueen

import java.util.Locale

enum class VpnConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED,
}

sealed class FailoverDecision {
    data class RetryNext(val guid: String, val attemptNumber: Int, val totalAttempts: Int) : FailoverDecision()
    data class Exhausted(val reason: String?) : FailoverDecision()
}

/**
 * Pure JVM-testable state machine governing McQueen VPN's 5 connection states and
 * automatic server failover progression across a country's ranked candidates.
 */
class ConnectionSessionCoordinator {

    private var candidateQueue: List<String> = emptyList()
    private var currentIndex: Int = -1
    private var state: VpnConnectionState = VpnConnectionState.DISCONNECTED
    private var lastFailureReason: String? = null

    fun currentState(): VpnConnectionState = state

    fun currentGuid(): String? = candidateQueue.getOrNull(currentIndex)

    fun startAttempt(candidateGuids: List<String>): String? {
        candidateQueue = candidateGuids
        currentIndex = if (candidateGuids.isNotEmpty()) 0 else -1
        lastFailureReason = null
        state = if (candidateGuids.isNotEmpty()) {
            VpnConnectionState.CONNECTING
        } else {
            VpnConnectionState.FAILED
        }
        return currentGuid()
    }

    fun onConnectingBroadcast(): VpnConnectionState {
        state = if (currentIndex > 0) {
            VpnConnectionState.RECONNECTING
        } else {
            VpnConnectionState.CONNECTING
        }
        return state
    }

    fun onStartSuccess(): VpnConnectionState {
        candidateQueue = emptyList()
        currentIndex = -1
        lastFailureReason = null
        state = VpnConnectionState.CONNECTED
        return state
    }

    fun onServiceRunningQuery(): VpnConnectionState {
        // Preserve CONNECTING/RECONNECTING if an active handshake (e.g. Aether warm-up) is underway.
        if (state == VpnConnectionState.DISCONNECTED || state == VpnConnectionState.FAILED) {
            state = VpnConnectionState.CONNECTED
        }
        return state
    }

    fun onStartFailure(reason: String?): FailoverDecision {
        val cleanReason = reason?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanReason != null) {
            lastFailureReason = cleanReason
        }
        val nextIndex = currentIndex + 1
        return if (candidateQueue.isNotEmpty() && nextIndex in candidateQueue.indices) {
            currentIndex = nextIndex
            state = VpnConnectionState.RECONNECTING
            FailoverDecision.RetryNext(
                guid = candidateQueue[nextIndex],
                attemptNumber = nextIndex + 1,
                totalAttempts = candidateQueue.size,
            )
        } else {
            candidateQueue = emptyList()
            currentIndex = -1
            state = VpnConnectionState.FAILED
            FailoverDecision.Exhausted(lastFailureReason)
        }
    }

    fun onUserDisconnect(): VpnConnectionState {
        candidateQueue = emptyList()
        currentIndex = -1
        lastFailureReason = null
        state = VpnConnectionState.DISCONNECTED
        return state
    }

    fun onServiceStopped(preserveFailure: Boolean = true): VpnConnectionState {
        if (state == VpnConnectionState.RECONNECTING) {
            // Failover transition is waiting to start the next candidate; do not drop to DISCONNECTED.
            return state
        }
        if (preserveFailure && state == VpnConnectionState.FAILED) {
            return state
        }
        candidateQueue = emptyList()
        currentIndex = -1
        state = VpnConnectionState.DISCONNECTED
        return state
    }

    companion object {
        fun formatDuration(elapsedMs: Long): String {
            val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        }
    }
}
