package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TunnelProbePolicyTest {

    @Test
    fun `skips remote IP lookup for internal McQueen tunnel verification requests`() {
        assertFalse(
            TunnelProbePolicy.shouldFetchRemoteIpInfo(
                requestId = "verify_1234-abcd",
                coreRunning = true,
                localProxyKept = true,
                httpPort = 10808,
            )
        )
    }

    @Test
    fun `skips remote IP lookup when local proxy is disabled core is stopped or port invalid`() {
        assertFalse(
            TunnelProbePolicy.shouldFetchRemoteIpInfo(
                requestId = "ping_test",
                coreRunning = true,
                localProxyKept = false,
                httpPort = 10808,
            )
        )
        assertFalse(
            TunnelProbePolicy.shouldFetchRemoteIpInfo(
                requestId = "ping_test",
                coreRunning = false,
                localProxyKept = true,
                httpPort = 10808,
            )
        )
        assertFalse(
            TunnelProbePolicy.shouldFetchRemoteIpInfo(
                requestId = "ping_test",
                coreRunning = true,
                localProxyKept = true,
                httpPort = 0,
            )
        )
        assertFalse(
            TunnelProbePolicy.shouldFetchRemoteIpInfo(
                requestId = "",
                coreRunning = true,
                localProxyKept = true,
                httpPort = 70000,
            )
        )
    }

    @Test
    fun `allows remote IP lookup for explicit ping when core and local proxy are active`() {
        assertTrue(
            TunnelProbePolicy.shouldFetchRemoteIpInfo(
                requestId = "",
                coreRunning = true,
                localProxyKept = true,
                httpPort = 10808,
            )
        )
        assertTrue(
            TunnelProbePolicy.shouldFetchRemoteIpInfo(
                requestId = "manual_delay_check",
                coreRunning = true,
                localProxyKept = true,
                httpPort = 10809,
            )
        )
    }

    @Test
    fun `isVerificationSuccess trusts non-negative delayMs even for non-English or unitless messages`() {
        assertTrue(TunnelProbePolicy.isVerificationSuccess(delayMs = 0L, messageContent = ""))
        assertTrue(
            TunnelProbePolicy.isVerificationSuccess(
                delayMs = 154L,
                messageContent = "Connection succeeded in 154",
            )
        )
        assertTrue(
            TunnelProbePolicy.isVerificationSuccess(
                delayMs = 180L,
                messageContent = "اتصال در 180ms با موفقیت برقرار شد",
            )
        )
        assertFalse(
            TunnelProbePolicy.isVerificationSuccess(
                delayMs = -1L,
                messageContent = "Connection succeeded in 154ms",
            )
        )
    }

    @Test
    fun `isVerificationSuccess falls back to formatted message inspection when delayMs is absent`() {
        assertTrue(
            TunnelProbePolicy.isVerificationSuccess(
                delayMs = null,
                messageContent = "Connection succeeded in 154ms",
            )
        )
        assertTrue(
            TunnelProbePolicy.isVerificationSuccess(
                delayMs = null,
                messageContent = "Connection succeeded in 210 ms\n203.0.113.4",
            )
        )
        // Reproduces previous bug when delay was formatted without 'ms' and no numeric extra was attached
        assertFalse(
            TunnelProbePolicy.isVerificationSuccess(
                delayMs = null,
                messageContent = "Connection succeeded in 154",
            )
        )
        assertFalse(
            TunnelProbePolicy.isVerificationSuccess(
                delayMs = null,
                messageContent = "Connection test failed: timeout 500ms",
            )
        )
        assertFalse(
            TunnelProbePolicy.isVerificationSuccess(
                delayMs = null,
                messageContent = "Error: upstream 200ms",
            )
        )
        assertFalse(TunnelProbePolicy.isVerificationSuccess(delayMs = null, messageContent = ""))
    }
}
