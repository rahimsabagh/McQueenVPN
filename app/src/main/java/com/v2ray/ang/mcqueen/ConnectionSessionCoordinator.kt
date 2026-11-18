package com.v2ray.ang.mcqueen

import java.util.Locale

enum class VpnConnectionState {
    DISCONNECTED,
    PREPARING,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED,
}

sealed class FailoverDecision {
    data class RetryNext(val guid: String) : FailoverDecision()
    data class Exhausted(val reason: String) : FailoverDecision()
}

/**
 * Coordinates connection state transitions, candidate failover, tunnel verification,
 * and serialized country switching without race conditions.
 */
class ConnectionSessionCoordinator {
    private var state: VpnConnectionState = VpnConnectionState.DISCONNECTED
    private var candidates: List<String> = emptyList()
    private var currentIndex: Int = 0
    private var attemptsUsed: Int = 0
    private var hasConnectedInSession: Boolean = false
    private var serviceLaunchedForAttempt: Boolean = false

    var activeOperationId: Long = 0L
        private set

    var activeCountryCode: String? = null
        private set

    var isSwitchingCountry: Boolean = false
        private set

    var userRequestedDisconnect: Boolean = false
        private set

    @Synchronized
    fun currentState(): VpnConnectionState = state

    @Synchronized
    fun currentGuid(): String? = candidates.getOrNull(currentIndex)

    /**
     * Determines whether selecting [newCountryCode] should trigger an automatic VPN core switch/restart.
     *
     * - When [currentState] is DISCONNECTED or FAILED, selecting a country only updates the selection
     *   and must NOT start the VPN.
     * - When [currentState] is CONNECTED, PREPARING, CONNECTING, or RECONNECTING, selecting a different
     *   country triggers a serialized core switch.
     */
    fun shouldTriggerCountrySwitch(
        newCountryCode: String,
        currentState: VpnConnectionState = state,
        currentCountryCode: String? = activeCountryCode,
    ): Boolean {
        if (newCountryCode.isBlank()) return false
        return when (currentState) {
            VpnConnectionState.DISCONNECTED,
            VpnConnectionState.FAILED -> false
            VpnConnectionState.CONNECTED,
            VpnConnectionState.PREPARING,
            VpnConnectionState.CONNECTING,
            VpnConnectionState.RECONNECTING -> {
                !currentCountryCode.equals(newCountryCode, ignoreCase = true)
            }
        }
    }

    /**
     * Begins a serialized country switch operation, invalidating any in-flight connection,
     * failover, or tunnel verification callbacks from the previous server.
     *
     * @return The new monotonic [activeOperationId] token for this switch request.
     */
    @Synchronized
    fun beginCountrySwitch(targetCountryCode: String): Long {
        activeOperationId++
        activeCountryCode = targetCountryCode
        userRequestedDisconnect = false
        isSwitchingCountry = true
        candidates = emptyList()
        currentIndex = 0
        attemptsUsed = 0
        hasConnectedInSession = false
        serviceLaunchedForAttempt = false
        state = VpnConnectionState.RECONNECTING
        return activeOperationId
    }

    /**
     * Attaches the ranked candidate pool for an active country switch if [operationId] is still current.
     * Returns the first candidate GUID to start, or null if superseded/cancelled or empty.
     */
    @Synchronized
    fun attachSwitchCandidates(
        operationId: Long,
        guids: List<String>,
        countryCode: String? = null,
    ): String? {
        if (!isCurrentOperation(operationId)) return null
        if (!countryCode.isNullOrBlank()) {
            activeCountryCode = countryCode
        }
        val distinct = guids.distinct()
        if (distinct.isEmpty()) {
            candidates = emptyList()
            currentIndex = 0
            attemptsUsed = 0
            isSwitchingCountry = false
            state = VpnConnectionState.FAILED
            return null
        }
        candidates = distinct
        currentIndex = 0
        attemptsUsed = 1
        hasConnectedInSession = false
        serviceLaunchedForAttempt = false
        state = VpnConnectionState.RECONNECTING
        return candidates.first()
    }

    /**
     * Checks whether [operationId] is still the latest active operation and not cancelled by user disconnect.
     */
    @Synchronized
    fun isCurrentOperation(operationId: Long): Boolean {
        return operationId == activeOperationId && !userRequestedDisconnect
    }

    @Synchronized
    fun markServiceLaunched(operationId: Long = activeOperationId): Boolean {
        if (!isCurrentOperation(operationId)) return false
        serviceLaunchedForAttempt = true
        return true
    }

    @Synchronized
    fun hasLaunchedOrActiveService(): Boolean =
        serviceLaunchedForAttempt ||
            state == VpnConnectionState.CONNECTED ||
            state == VpnConnectionState.RECONNECTING

    @Synchronized
    fun isAwaitingSwitchStop(): Boolean =
        state == VpnConnectionState.RECONNECTING && isSwitchingCountry && candidates.isEmpty()

    @Synchronized
    fun canAcceptRuntimeFailure(): Boolean {
        if (userRequestedDisconnect) return false
        return when (state) {
            VpnConnectionState.DISCONNECTED,
            VpnConnectionState.FAILED,
            VpnConnectionState.PREPARING -> false
            VpnConnectionState.CONNECTING -> serviceLaunchedForAttempt
            VpnConnectionState.RECONNECTING -> candidates.isNotEmpty()
            VpnConnectionState.CONNECTED -> true
        }
    }

    @Synchronized
    fun recoverConnecting(countryCode: String? = activeCountryCode) {
        if (state != VpnConnectionState.DISCONNECTED) return
        activeOperationId++
        if (!countryCode.isNullOrBlank()) {
            activeCountryCode = countryCode
        }
        userRequestedDisconnect = false
        isSwitchingCountry = false
        candidates = emptyList()
        currentIndex = 0
        attemptsUsed = 0
        hasConnectedInSession = false
        serviceLaunchedForAttempt = true
        state = VpnConnectionState.CONNECTING
    }

    @Synchronized
    fun startPreparing(countryCode: String? = activeCountryCode): VpnConnectionState {
        activeOperationId++
        if (countryCode != null) {
            activeCountryCode = countryCode
        }
        userRequestedDisconnect = false
        isSwitchingCountry = false
        candidates = emptyList()
        currentIndex = 0
        attemptsUsed = 0
        hasConnectedInSession = false
        serviceLaunchedForAttempt = false
        state = VpnConnectionState.PREPARING
        return state
    }

    @Synchronized
    fun onServiceRunningQuery(): VpnConnectionState {
        return state
    }

    @Synchronized
    fun startAttempt(guids: List<String>, countryCode: String? = activeCountryCode): String? {
        activeOperationId++
        if (countryCode != null) {
            activeCountryCode = countryCode
        }
        userRequestedDisconnect = false
        isSwitchingCountry = false
        val distinct = guids.distinct()
        if (distinct.isEmpty()) {
            candidates = emptyList()
            currentIndex = 0
            attemptsUsed = 0
            hasConnectedInSession = false
            serviceLaunchedForAttempt = false
            state = VpnConnectionState.FAILED
            return null
        }
        candidates = distinct
        currentIndex = 0
        attemptsUsed = 1
        hasConnectedInSession = false
        serviceLaunchedForAttempt = false
        state = VpnConnectionState.CONNECTING
        return candidates.first()
    }

    @Synchronized
    fun onStartSuccess(): VpnConnectionState {
        hasConnectedInSession = true
        isSwitchingCountry = false
        serviceLaunchedForAttempt = true
        state = VpnConnectionState.CONNECTED
        return state
    }

    @Synchronized
    fun onStartFailure(reason: String): FailoverDecision {
        val poolSize = candidates.size
        if (poolSize == 0) {
            isSwitchingCountry = false
            state = VpnConnectionState.FAILED
            return FailoverDecision.Exhausted(reason)
        }

        val maxAllowedAttempts = if (hasConnectedInSession) {
            poolSize * 2
        } else {
            poolSize
        }

        if (attemptsUsed < maxAllowedAttempts && poolSize > 1) {
            currentIndex = (currentIndex + 1) % poolSize
            attemptsUsed++
            state = VpnConnectionState.RECONNECTING
            return FailoverDecision.RetryNext(candidates[currentIndex])
        }

        isSwitchingCountry = false
        state = VpnConnectionState.FAILED
        return FailoverDecision.Exhausted(reason)
    }

    /**
     * Handles passive [AppConfig.MSG_STATE_NOT_RUNNING] status replies (e.g. when querying service
     * state on host resume or during permission/provisioning before the daemon has started).
     * Never aborts in-flight [VpnConnectionState.PREPARING], [VpnConnectionState.CONNECTING],
     * or [VpnConnectionState.RECONNECTING] attempts.
     */
    @Synchronized
    fun onServiceNotRunningQuery(preserveFailure: Boolean = true): VpnConnectionState {
        return when (state) {
            VpnConnectionState.PREPARING,
            VpnConnectionState.CONNECTING,
            VpnConnectionState.RECONNECTING -> state

            VpnConnectionState.FAILED -> {
                if (preserveFailure) VpnConnectionState.FAILED else VpnConnectionState.DISCONNECTED
            }

            VpnConnectionState.CONNECTED,
            VpnConnectionState.DISCONNECTED -> {
                if (isSwitchingCountry) {
                    state = VpnConnectionState.RECONNECTING
                    VpnConnectionState.RECONNECTING
                } else {
                    state = VpnConnectionState.DISCONNECTED
                    VpnConnectionState.DISCONNECTED
                }
            }
        }
    }

    @Synchronized
    fun onServiceStopped(preserveFailure: Boolean = false): VpnConnectionState {
        if (state == VpnConnectionState.RECONNECTING || isSwitchingCountry) {
            return VpnConnectionState.RECONNECTING
        }
        if (state == VpnConnectionState.PREPARING ||
            (state == VpnConnectionState.CONNECTING && !serviceLaunchedForAttempt)
        ) {
            // Delayed stop broadcast from an earlier session must not abort a fresh pre-launch attempt.
            return state
        }
        if (preserveFailure && state == VpnConnectionState.FAILED) {
            return VpnConnectionState.FAILED
        }
        serviceLaunchedForAttempt = false
        state = if (state == VpnConnectionState.FAILED) {
            VpnConnectionState.FAILED
        } else {
            VpnConnectionState.DISCONNECTED
        }
        return state
    }

    @Synchronized
    fun onUserDisconnect(): VpnConnectionState {
        activeOperationId++
        userRequestedDisconnect = true
        isSwitchingCountry = false
        candidates = emptyList()
        currentIndex = 0
        attemptsUsed = 0
        hasConnectedInSession = false
        serviceLaunchedForAttempt = false
        state = VpnConnectionState.DISCONNECTED
        return state
    }

    companion object {
        fun formatDuration(elapsedMs: Long): String {
            val totalSeconds = (elapsedMs.coerceAtLeast(0L)) / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        }
    }
}
