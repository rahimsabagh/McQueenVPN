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
        assertNull(coordinator.currentGuid())

        // User disconnects -> DISCONNECTED
        assertEquals(VpnConnectionState.DISCONNECTED, coordinator.onUserDisconnect())
    }

    @Test
    fun `enters FAILED state once all candidates are exhausted`() {
        val coordinator = ConnectionSessionCoordinator()
        coordinator.startAttempt(listOf("srv-1", "srv-2"))

        val firstFailure = coordinator.onStartFailure("handshake failed")
        assertTrue(firstFailure is FailoverDecision.RetryNext)
        assertEquals(VpnConnectionState.RECONNECTING, coordinator.currentState())

        val exhausted = coordinator.onStartFailure("all blocked")
        assertTrue(exhausted is FailoverDecision.Exhausted)
        assertEquals("all blocked", (exhausted as FailoverDecision.Exhausted).reason)
        assertEquals(VpnConnectionState.FAILED, coordinator.currentState())

        // Stale not-running broadcast preserves FAILED state until user acts
        assertEquals(VpnConnectionState.FAILED, coordinator.onServiceStopped(preserveFailure = true))
    }

    @Test
    fun `formats elapsed duration as HH MM SS`() {
        assertEquals("00:00:00", ConnectionSessionCoordinator.formatDuration(0L))
        assertEquals("00:01:05", ConnectionSessionCoordinator.formatDuration(65_000L))
        assertEquals("01:02:03", ConnectionSessionCoordinator.formatDuration(3_723_900L))
    }
}
