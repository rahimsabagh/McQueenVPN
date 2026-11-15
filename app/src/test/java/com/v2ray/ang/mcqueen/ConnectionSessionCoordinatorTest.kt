package com.v2ray.ang.mcqueen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionSessionCoordinatorTest {

    @Test
    fun `transitions across all 5 states during automatic failover and connection`() {
        val coordinator = ConnectionSessionCoordinator()
        assertEquals(VpnConnectionState.DISCONNECTED, coordinator.currentState())

        // Start attempt with 3 candidates
        val first = coordinator.startAttempt(listOf("srv-1", "srv-2", "srv-3"))
        assertEquals("srv-1", first)
        assertEquals(VpnConnectionState.CONNECTING, coordinator.currentState())

        // Candidate 1 fails -> RECONNECTING to Candidate 2
        val retry1 = coordinator.onStartFailure("timeout")
        assertTrue(retry1 is FailoverDecision.RetryNext)
        assertEquals("srv-2", (retry1 as FailoverDecision.RetryNext).guid)
        assertEquals(VpnConnectionState.RECONNECTING, coordinator.currentState())

        // Service stopped broadcast during failover gap must stay RECONNECTING
        assertEquals(VpnConnectionState.RECONNECTING, coordinator.onServiceStopped())

        // Candidate 2 succeeds -> CONNECTED
        assertEquals(VpnConnectionState.CONNECTED, coordinator.onStartSuccess())
        assertEquals("srv-2", coordinator.currentGuid())

        // User disconnects -> DISCONNECTED
        assertEquals(VpnConnectionState.DISCONNECTED, coordinator.onUserDisconnect())
        assertNull(coordinator.currentGuid())
    }

    @Test
    fun `enters FAILED state once all candidates are exhausted and preserves FAILED across stop broadcast`() {
        val coordinator = ConnectionSessionCoordinator()
        coordinator.startAttempt(listOf("srv-1", "srv-2"))

        val firstFailure = coordinator.onStartFailure("handshake failed")
        assertTrue(firstFailure is FailoverDecision.RetryNext)
        assertEquals(VpnConnectionState.RECONNECTING, coordinator.currentState())

        val exhausted = coordinator.onStartFailure("all blocked")
        assertTrue(exhausted is FailoverDecision.Exhausted)
        assertEquals("all blocked", (exhausted as FailoverDecision.Exhausted).reason)
        assertEquals(VpnConnectionState.FAILED, coordinator.currentState())

        // Trailing STOP_SUCCESS/NOT_RUNNING with preserveFailure=true must keep FAILED state
        assertEquals(VpnConnectionState.FAILED, coordinator.onServiceStopped(preserveFailure = true))
    }

    @Test
    fun `recovers onto alternate server when established tunnel drops and enforces hard retry cap`() {
        val coordinator = ConnectionSessionCoordinator()
        coordinator.startAttempt(listOf("srv-1", "srv-2"))
        assertEquals(VpnConnectionState.CONNECTED, coordinator.onStartSuccess())

        // Established tunnel unexpectedly fails -> fail over to srv-2
        val recovery1 = coordinator.onStartFailure("aether exited")
        assertTrue(recovery1 is FailoverDecision.RetryNext)
        assertEquals("srv-2", (recovery1 as FailoverDecision.RetryNext).guid)
        assertEquals(VpnConnectionState.RECONNECTING, coordinator.currentState())

        // srv-2 connects, then later drops again -> second recovery episode allowed within session cap
        assertEquals(VpnConnectionState.CONNECTED, coordinator.onStartSuccess())
        val recovery2 = coordinator.onStartFailure("reload failed")
        assertTrue(recovery2 is FailoverDecision.RetryNext)
        assertEquals("srv-1", (recovery2 as FailoverDecision.RetryNext).guid)

        // Attempt 4 tries remaining candidate srv-2 before hitting session cap (max 4 for 2 servers)
        val recovery3 = coordinator.onStartFailure("srv-1 down")
        assertTrue(recovery3 is FailoverDecision.RetryNext)
        assertEquals("srv-2", (recovery3 as FailoverDecision.RetryNext).guid)

        // Further failure hits hard retry cap without infinite retry loop
        val finalFailure = coordinator.onStartFailure("still broken")
        assertTrue(finalFailure is FailoverDecision.Exhausted)
        assertEquals(VpnConnectionState.FAILED, coordinator.currentState())
    }

    @Test
    fun `ignores failure broadcast after explicit user disconnect`() {
        val coordinator = ConnectionSessionCoordinator()
        coordinator.startAttempt(listOf("srv-1", "srv-2"))
        assertEquals(VpnConnectionState.DISCONNECTED, coordinator.onUserDisconnect())

        val decision = coordinator.onStartFailure("late failure")
        assertTrue(decision is FailoverDecision.Exhausted)
        assertEquals(VpnConnectionState.FAILED, coordinator.currentState())
    }

    @Test
    fun `formats elapsed duration as HH MM SS`() {
        assertEquals("00:00:00", ConnectionSessionCoordinator.formatDuration(0L))
        assertEquals("00:01:05", ConnectionSessionCoordinator.formatDuration(65_000L))
        assertEquals("01:02:03", ConnectionSessionCoordinator.formatDuration(3_723_900L))
    }

    @Test
    fun `supports PREPARING state before connecting and clean cancellation back to DISCONNECTED`() {
        val coordinator = ConnectionSessionCoordinator()
        assertEquals(VpnConnectionState.PREPARING, coordinator.startPreparing())
        assertEquals(VpnConnectionState.PREPARING, coordinator.onServiceRunningQuery())

        // User cancels during first-run identity setup
        assertEquals(VpnConnectionState.DISCONNECTED, coordinator.onUserDisconnect())

        // Second attempt prepares then proceeds to CONNECTING
        assertEquals(VpnConnectionState.PREPARING, coordinator.startPreparing())
        assertEquals("aether-us-1", coordinator.startAttempt(listOf("aether-us-1")))
        assertEquals(VpnConnectionState.CONNECTING, coordinator.currentState())

        // Registration failure during PREPARING enters FAILED directly
        assertEquals(VpnConnectionState.PREPARING, coordinator.startPreparing())
        val exhausted = coordinator.onStartFailure("Cannot reach registration service")
        assertTrue(exhausted is FailoverDecision.Exhausted)
        assertEquals(VpnConnectionState.FAILED, coordinator.currentState())
    }

    @Test
    fun `country selection while disconnected or failed does not trigger automatic VPN start`() {
        val coordinator = ConnectionSessionCoordinator()
        assertEquals(VpnConnectionState.DISCONNECTED, coordinator.currentState())
        assertEquals(false, coordinator.shouldTriggerCountrySwitch("DE"))

        coordinator.startAttempt(listOf("srv-1"))
        coordinator.onStartFailure("failed")
        assertEquals(VpnConnectionState.FAILED, coordinator.currentState())
        assertEquals(false, coordinator.shouldTriggerCountrySwitch("NL"))
    }

    @Test
    fun `country selection while connected or connecting triggers serialized switch and invalidates stale operations`() {
        val coordinator = ConnectionSessionCoordinator()
        coordinator.startAttempt(listOf("de-1", "de-2"), countryCode = "DE")
        assertEquals(VpnConnectionState.CONNECTED, coordinator.onStartSuccess())
        assertEquals("DE", coordinator.activeCountryCode)

        // Selecting the same country while connected does not trigger redundant restart
        assertEquals(false, coordinator.shouldTriggerCountrySwitch("DE"))

        // Selecting a different country while connected triggers switch
        assertEquals(true, coordinator.shouldTriggerCountrySwitch("NL"))
        val opNl = coordinator.beginCountrySwitch("NL")
        assertEquals(VpnConnectionState.RECONNECTING, coordinator.currentState())
        assertEquals("NL", coordinator.activeCountryCode)
        assertTrue(coordinator.isSwitchingCountry)

        // Rapidly selecting US before NL finishes invalidates NL's operation ID
        assertEquals(true, coordinator.shouldTriggerCountrySwitch("US"))
        val opUs = coordinator.beginCountrySwitch("US")
        assertEquals(false, coordinator.isCurrentOperation(opNl))
        assertEquals(true, coordinator.isCurrentOperation(opUs))
        assertEquals("US", coordinator.activeCountryCode)

        // Old service stop broadcast during switch keeps state in RECONNECTING
        assertEquals(VpnConnectionState.RECONNECTING, coordinator.onServiceStopped())

        // Attach US candidates and complete connection
        val firstUsGuid = coordinator.attachSwitchCandidates(opUs, listOf("us-1", "us-2"), "US")
        assertEquals("us-1", firstUsGuid)
        assertEquals(VpnConnectionState.CONNECTED, coordinator.onStartSuccess())
        assertEquals("us-1", coordinator.currentGuid())
        assertEquals("US", coordinator.activeCountryCode)
        assertEquals(false, coordinator.isSwitchingCountry)
    }
}

