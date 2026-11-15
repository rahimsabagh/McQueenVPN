package com.v2ray.ang.mcqueen

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.handler.VpnTrafficTracker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VpnTrafficTrackerTest {

    @Test
    fun `computes live speed and cumulative session bytes from monotonic elapsed time`() {
        var nowMs = 1_000L
        var currentDeltas = emptyList<OutboundTrafficStat>()
        val tracker = VpnTrafficTracker(
            source = { currentDeltas },
            monotonicClockMs = { nowMs }
        )

        tracker.onSessionStart(sessionStartMs = 100_000L)

        // After 1 second (1000ms), 2048 B uplink and 8192 B downlink
        nowMs = 2_000L
        currentDeltas = listOf(
            OutboundTrafficStat(AppConfig.TAG_PROXY, "uplink", 2_048L),
            OutboundTrafficStat(AppConfig.TAG_PROXY, "downlink", 8_192L),
        )
        val first = tracker.sample().snapshot

        assertEquals(2_048L, first.uplinkBps)
        assertEquals(8_192L, first.downlinkBps)
        assertEquals(2_048L, first.sessionUplinkBytes)
        assertEquals(8_192L, first.sessionDownlinkBytes)
        assertEquals(10_240L, first.sessionTotalBytes)

        // After another 2 seconds (2000ms), 4000 B uplink and 10000 B downlink
        nowMs = 4_000L
        currentDeltas = listOf(
            OutboundTrafficStat(AppConfig.TAG_PROXY, "uplink", 4_000L),
            OutboundTrafficStat(AppConfig.TAG_PROXY, "downlink", 10_000L),
        )
        val second = tracker.sample().snapshot

        assertEquals(2_000L, second.uplinkBps)
        assertEquals(5_000L, second.downlinkBps)
        assertEquals(6_048L, second.sessionUplinkBytes)
        assertEquals(18_192L, second.sessionDownlinkBytes)
    }

    @Test
    fun `clamps negative or corrupt stats and excludes exit node and blocked tags from double counting`() {
        var nowMs = 5_000L
        var currentDeltas = emptyList<OutboundTrafficStat>()
        val tracker = VpnTrafficTracker(
            source = { currentDeltas },
            monotonicClockMs = { nowMs }
        )

        tracker.onSessionStart(sessionStartMs = 200_000L)

        nowMs = 6_000L
        currentDeltas = listOf(
            OutboundTrafficStat(AppConfig.TAG_PROXY, "uplink", -500L), // corrupt negative
            OutboundTrafficStat(AppConfig.TAG_PROXY, "downlink", 4_096L),
            OutboundTrafficStat(AppConfig.TAG_EXIT_NODE, "downlink", 99_999L), // excluded
            OutboundTrafficStat(AppConfig.TAG_BLOCKED, "uplink", 88_888L), // excluded
        )
        val snapshot = tracker.sample().snapshot

        assertEquals(0L, snapshot.uplinkBps)
        assertEquals(4_096L, snapshot.downlinkBps)
        assertEquals(0L, snapshot.sessionUplinkBytes)
        assertEquals(4_096L, snapshot.sessionDownlinkBytes)
    }

    @Test
    fun `preserves cumulative session totals across core reload while resetting rate window`() {
        var nowMs = 1_000L
        var currentDeltas = emptyList<OutboundTrafficStat>()
        val tracker = VpnTrafficTracker(
            source = { currentDeltas },
            monotonicClockMs = { nowMs }
        )

        tracker.onSessionStart(sessionStartMs = 300_000L)

        nowMs = 2_000L
        currentDeltas = listOf(
            OutboundTrafficStat(AppConfig.TAG_PROXY, "uplink", 1_000L),
            OutboundTrafficStat(AppConfig.TAG_PROXY, "downlink", 3_000L),
        )
        tracker.sample()

        // Simulate network handover / core reload at 2_500ms with 200B pre-reload tail
        nowMs = 2_500L
        currentDeltas = listOf(
            OutboundTrafficStat(AppConfig.TAG_PROXY, "uplink", 200L),
            OutboundTrafficStat(AppConfig.TAG_PROXY, "downlink", 400L),
        )
        tracker.onCoreReloaded()
        val reloadSnapshot = tracker.currentSnapshot()
        assertEquals(0L, reloadSnapshot.uplinkBps)
        assertEquals(0L, reloadSnapshot.downlinkBps)
        assertEquals(1_200L, reloadSnapshot.sessionUplinkBytes)
        assertEquals(3_400L, reloadSnapshot.sessionDownlinkBytes)

        // Next sample 1 second after reload adds to cumulative totals
        nowMs = 3_500L
        currentDeltas = listOf(
            OutboundTrafficStat(AppConfig.TAG_PROXY, "uplink", 500L),
            OutboundTrafficStat(AppConfig.TAG_PROXY, "downlink", 1_500L),
        )
        val afterReload = tracker.sample().snapshot
        assertEquals(500L, afterReload.uplinkBps)
        assertEquals(1_500L, afterReload.downlinkBps)
        assertEquals(1_700L, afterReload.sessionUplinkBytes)
        assertEquals(4_900L, afterReload.sessionDownlinkBytes)
    }

    @Test
    fun `prevents speed spike after long screen-off doze interval while preserving session bytes`() {
        var nowMs = 1_000L
        var currentDeltas = emptyList<OutboundTrafficStat>()
        val tracker = VpnTrafficTracker(
            source = { currentDeltas },
            monotonicClockMs = { nowMs }
        )

        tracker.onSessionStart(sessionStartMs = 400_000L)

        // Screen off for 60 seconds (> MAX_RATE_WINDOW_MS)
        nowMs = 61_000L
        currentDeltas = listOf(
            OutboundTrafficStat(AppConfig.TAG_PROXY, "uplink", 10_000L),
            OutboundTrafficStat(AppConfig.TAG_PROXY, "downlink", 50_000L),
        )
        val wakeSample = tracker.sample().snapshot

        // Session bytes accumulate accurately, instantaneous rate resets cleanly to 0 for the wake tick
        assertEquals(0L, wakeSample.uplinkBps)
        assertEquals(0L, wakeSample.downlinkBps)
        assertEquals(10_000L, wakeSample.sessionUplinkBytes)
        assertEquals(50_000L, wakeSample.sessionDownlinkBytes)
    }

    @Test
    fun `parses Go semicolon and comma outbound traffic payload and feeds VpnTrafficTracker`() {
        var nowMs = 1_000L
        var rawPayload = ""
        val tracker = VpnTrafficTracker(
            source = { com.v2ray.ang.core.CoreServiceManager.parseOutboundTrafficStats(rawPayload) },
            monotonicClockMs = { nowMs }
        )

        tracker.onSessionStart(sessionStartMs = 500_000L)

        nowMs = 2_000L
        rawPayload = "proxy,uplink,2048;proxy,downlink,8192;direct,uplink,512;direct,downlink,1024;exit-node,downlink,99999;block,uplink,88888;"
        val sample = tracker.sample()

        assertEquals(2_048L, sample.proxyUplinkBps)
        assertEquals(8_192L, sample.proxyDownlinkBps)
        assertEquals(512L, sample.directUplinkBps)
        assertEquals(1_024L, sample.directDownlinkBps)
        assertEquals(2_560L, sample.snapshot.uplinkBps)
        assertEquals(9_216L, sample.snapshot.downlinkBps)
        assertEquals(2_560L, sample.snapshot.sessionUplinkBytes)
        assertEquals(9_216L, sample.snapshot.sessionDownlinkBytes)
        assertEquals(11_776L, sample.snapshot.sessionTotalBytes)
    }
}
