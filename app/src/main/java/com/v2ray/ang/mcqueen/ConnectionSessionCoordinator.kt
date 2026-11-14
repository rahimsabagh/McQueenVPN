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
    private var hasConnectedInSession: Boolean = false
    private var recoveryEpisodesUsed: Int = 0
    private var recoveryRemaining: Int = 0
    private var totalAttemptsInSession: Int = 0

    fun currentState(): VpnConnectionState = state

    fun currentGuid(): String? = candidateQueue.getOrNull(currentIndex)

    fun startAttempt(candidateGuids: List<String>): String? {
        candidateQueue = candidateGuids
        currentIndex = if (candidateGuids.isNotEmpty()) 0 else -1
        lastFailureReason = null
        hasConnectedInSession = false
        recoveryEpisodesUsed = 0
        recoveryRemaining = 0
        totalAttemptsInSession = if (candidateGuids.isNotEmpty()) 1 else 0
        state = if (candidateGuids.isNotEmpty()) {
            VpnConnectionState.CONNECTING
        } else {
            VpnConnectionState.FAILED
        }
        return currentGuid()
    }

    fun onConnectingBroadcast(): VpnConnectionState {
        if (state == VpnConnectionState.DISCONNECTED || state == VpnConnectionState.FAILED) {
            state = VpnConnectionState.CONNECTING
            return state
        }
        state = if (currentIndex > 0 || hasConnectedInSession || recoveryRemaining > 0) {
            VpnConnectionState.RECONNECTING
        } else {
            VpnConnectionState.CONNECTING
        }
        return state
    }

    fun onStartSuccess(): VpnConnectionState {
        hasConnectedInSession = true
        recoveryRemaining = 0
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

        if (state == VpnConnectionState.DISCONNECTED || candidateQueue.isEmpty()) {
            return exhaustFailure()
        }

        val sessionCap = maxAttemptsForSession(candidateQueue.size)
        if (totalAttemptsInSession >= sessionCap) {
            return exhaustFailure()
        }

        // Unexpected failure on an already-established tunnel initiates a strictly bounded recovery pass.
        if (state == VpnConnectionState.CONNECTED || hasConnectedInSession) {
            if (state == VpnConnectionState.CONNECTED) {
                if (recoveryEpisodesUsed >= MAX_RECOVERY_EPISODES) {
                    return exhaustFailure()
                }
                recoveryEpisodesUsed++
                recoveryRemaining = minOf(
                    maxOf(candidateQueue.size, 1),
                    MAX_RECOVERY_ATTEMPTS_PER_DROP,
                    sessionCap - totalAttemptsInSession,
                )
            }

            if (recoveryRemaining > 0) {
                recoveryRemaining--
                totalAttemptsInSession++
                currentIndex = if (currentIndex in candidateQueue.indices) {
                    (currentIndex + 1) % candidateQueue.size
                } else {
                    0
                }
                state = VpnConnectionState.RECONNECTING
                return FailoverDecision.RetryNext(
                    guid = candidateQueue[currentIndex],
                    attemptNumber = totalAttemptsInSession,
                    totalAttempts = minOf(candidateQueue.size + recoveryRemaining, sessionCap),
                )
            }
            return exhaustFailure()
        }

        val nextIndex = currentIndex + 1
        return if (nextIndex in candidateQueue.indices) {
            currentIndex = nextIndex
            totalAttemptsInSession++
            state = VpnConnectionState.RECONNECTING
            FailoverDecision.RetryNext(
                guid = candidateQueue[nextIndex],
                attemptNumber = totalAttemptsInSession,
                totalAttempts = minOf(candidateQueue.size, sessionCap),
            )
        } else {
            exhaustFailure()
        }
    }

    fun onUserDisconnect(): VpnConnectionState {
        resetSession(targetState = VpnConnectionState.DISCONNECTED, clearReason = true)
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
        resetSession(targetState = VpnConnectionState.DISCONNECTED, clearReason = false)
        return state
    }

    private fun exhaustFailure(): FailoverDecision.Exhausted {
        val reason = lastFailureReason
        resetSession(targetState = VpnConnectionState.FAILED, clearReason = false)
        return FailoverDecision.Exhausted(reason)
    }

    private fun resetSession(targetState: VpnConnectionState, clearReason: Boolean) {
        candidateQueue = emptyList()
        currentIndex = -1
        hasConnectedInSession = false
        recoveryEpisodesUsed = 0
        recoveryRemaining = 0
        totalAttemptsInSession = 0
        if (clearReason) {
            lastFailureReason = null
        }
        state = targetState
    }

    companion object {
        internal const val MAX_TOTAL_SESSION_ATTEMPTS = 10
        internal const val MAX_RECOVERY_EPISODES = 2
        internal const val MAX_RECOVERY_ATTEMPTS_PER_DROP = 4

        internal fun maxAttemptsForSession(candidateCount: Int): Int =
            minOf(MAX_TOTAL_SESSION_ATTEMPTS, maxOf(candidateCount, 1) * 2)

        fun formatDuration(elapsedMs: Long): String {
            val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        }
    }
}
