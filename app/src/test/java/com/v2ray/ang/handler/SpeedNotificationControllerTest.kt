package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.TrafficStatsSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeedNotificationControllerTest {

    private class TestHarness(testScope: TestScope) {
        var nowElapsedMs: Long = 1_000L
        var sessionStartWallMs: Long = 500_000L
        var serviceRunning: Boolean = true
        var speedNotificationEnabled: Boolean = true
        var pendingDeltas: MutableList<OutboundTrafficStat> = mutableListOf()

        var sourceQueryCount: Int = 0
        var speedPrefReadCount: Int = 0
        val emittedSnapshots = mutableListOf<TrafficStatsSnapshot>()
        val notificationUpdates = mutableListOf<Triple<String?, Long, Long>>()

        val tracker = VpnTrafficTracker(
            source = {
                sourceQueryCount++
                val drained = pendingDeltas.toList()
                pendingDeltas.clear()
                drained
            },
            monotonicClockMs = { nowElapsedMs }
        )

        val controller = SpeedNotificationController(
            scope = testScope.backgroundScope,
            trafficTracker = tracker,
            isServiceRunning = { serviceRunning },
            sessionStartMs = { if (serviceRunning) sessionStartWallMs else 0L },
            isSpeedNotificationEnabled = {
                speedPrefReadCount++
                speedNotificationEnabled
            },
            onEmitTrafficStats = { snapshot -> emittedSnapshots.add(snapshot) },
            onUpdateNotification = { text, upBps, downBps ->
                notificationUpdates.add(Triple(text, upBps, downBps))
            },
            pollIntervalMs = 1_000L,
        )
    }

    @Test
    fun `screen off suspends JNI queries preference reads broadcasts and notification updates`() = runTest(StandardTestDispatcher()) {
        val h = TestHarness(this)
        h.controller.onSessionStart(h.sessionStartWallMs, isInteractive = true)
        h.controller.startSpeedNotification("United States")

        // Tick at t=0s
        runCurrent()
        assertTrue(h.controller.isPollingActive())
        assertEquals(1, h.emittedSnapshots.size)

        // Advance 2s -> ticks at t=1s and t=2s
        h.nowElapsedMs += 1_000L
        h.pendingDeltas.add(OutboundTrafficStat(AppConfig.TAG_PROXY, "downlink", 2_048L))
        advanceTimeBy(1_000L)
        runCurrent()

        h.nowElapsedMs += 1_000L
        h.pendingDeltas.add(OutboundTrafficStat(AppConfig.TAG_PROXY, "downlink", 4_096L))
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(3, h.emittedSnapshots.size)
        val queriesBeforeOff = h.sourceQueryCount
        val prefReadsBeforeOff = h.speedPrefReadCount
        val broadcastsBeforeOff = h.emittedSnapshots.size
        val updatesBeforeOff = h.notificationUpdates.size

        // Screen turns OFF: polling must stop cleanly without clearing notification or stopping session
        h.controller.onScreenOff()
        assertFalse(h.controller.isPollingActive())
        assertFalse(h.controller.isInteractiveState())
        assertTrue(h.controller.isSessionActiveState())

        // Advance 60 seconds while screen is off
        h.nowElapsedMs += 60_000L
        h.pendingDeltas.add(OutboundTrafficStat(AppConfig.TAG_PROXY, "downlink", 500_000L))
        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(queriesBeforeOff, h.sourceQueryCount)
        assertEquals(prefReadsBeforeOff, h.speedPrefReadCount)
        assertEquals(broadcastsBeforeOff, h.emittedSnapshots.size)
        assertEquals(updatesBeforeOff, h.notificationUpdates.size)
    }

    @Test
    fun `screen on resumes polling immediately with fresh snapshot and notification update`() = runTest(StandardTestDispatcher()) {
        val h = TestHarness(this)
        h.controller.onSessionStart(h.sessionStartWallMs, isInteractive = true)
        h.controller.startSpeedNotification("Germany")
        runCurrent()

        h.controller.onScreenOff()
        val broadcastsWhileOff = h.emittedSnapshots.size
        val updatesWhileOff = h.notificationUpdates.size

        // Traffic flows while screen is off for 30s
        h.nowElapsedMs += 30_000L
        h.pendingDeltas.add(OutboundTrafficStat(AppConfig.TAG_PROXY, "uplink", 12_000L))
        h.pendingDeltas.add(OutboundTrafficStat(AppConfig.TAG_PROXY, "downlink", 48_000L))
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(broadcastsWhileOff, h.emittedSnapshots.size)

        // Screen turns ON: must resume polling and immediately emit fresh totals + refresh notification
        h.controller.onScreenOn("Germany")
        runCurrent()

        assertTrue(h.controller.isPollingActive())
        assertTrue(h.controller.isInteractiveState())
        assertEquals(broadcastsWhileOff + 1, h.emittedSnapshots.size)
        assertEquals(updatesWhileOff + 1, h.notificationUpdates.size)

        val resumed = h.emittedSnapshots.last()
        assertEquals(12_000L, resumed.sessionUplinkBytes)
        assertEquals(48_000L, resumed.sessionDownlinkBytes)
        assertEquals(h.sessionStartWallMs, resumed.sessionStartMs)

        // Continues normal 1-Hz polling while screen stays on
        h.nowElapsedMs += 1_000L
        h.pendingDeltas.add(OutboundTrafficStat(AppConfig.TAG_PROXY, "downlink", 10_000L))
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(broadcastsWhileOff + 2, h.emittedSnapshots.size)
        assertEquals(10_000L, h.emittedSnapshots.last().downlinkBps)
        assertEquals(58_000L, h.emittedSnapshots.last().sessionDownlinkBytes)
    }

    @Test
    fun `prevents duplicate polling jobs across repeated transitions and rapid toggles`() = runTest(StandardTestDispatcher()) {
        val h = TestHarness(this)
        h.controller.onSessionStart(h.sessionStartWallMs, isInteractive = true)
        h.controller.startSpeedNotification("Japan")
        // Duplicate calls should not launch a second polling loop
        h.controller.startSpeedNotification("Japan")
        h.controller.onScreenOn("Japan")
        h.controller.onScreenOn("Japan")
        runCurrent()

        assertEquals(1, h.emittedSnapshots.size)

        // Rapid SCREEN_OFF -> SCREEN_ON -> SCREEN_OFF before dispatcher runs cancels cleanly
        h.controller.onScreenOff()
        h.controller.onScreenOff()
        h.controller.onScreenOn("Japan")
        h.controller.onScreenOff()
        runCurrent()

        assertFalse(h.controller.isPollingActive())
        assertEquals(1, h.emittedSnapshots.size)

        // Final SCREEN_ON followed immediately by startSpeedNotification (matching CoreServiceManager)
        h.controller.onScreenOn("Japan")
        h.controller.startSpeedNotification("Japan")
        runCurrent()

        assertTrue(h.controller.isPollingActive())
        assertEquals(2, h.emittedSnapshots.size)

        h.nowElapsedMs += 1_000L
        advanceTimeBy(1_000L)
        runCurrent()

        // Exactly +1 emission over 1 second (no duplicate polling loop)
        assertEquals(3, h.emittedSnapshots.size)
    }

    @Test
    fun `vpn stays active during screen off and respects off state when starting or reloading`() = runTest(StandardTestDispatcher()) {
        val h = TestHarness(this)

        // Case 1: VPN starts while screen is ALREADY off (e.g. Tasker / Always-on VPN)
        h.controller.onSessionStart(h.sessionStartWallMs, isInteractive = false)
        h.controller.startSpeedNotification("Canada")
        runCurrent()

        assertTrue(h.controller.isSessionActiveState())
        assertFalse(h.controller.isPollingActive())
        assertTrue(h.emittedSnapshots.isEmpty())
        assertTrue(h.notificationUpdates.isEmpty())

        // Case 2: Core reloads on network handover while screen is off
        h.nowElapsedMs += 5_000L
        h.pendingDeltas.add(OutboundTrafficStat(AppConfig.TAG_PROXY, "uplink", 3_000L))
        h.pendingDeltas.add(OutboundTrafficStat(AppConfig.TAG_PROXY, "downlink", 7_000L))
        h.controller.onCoreReloaded()
        h.controller.startSpeedNotification("Canada")
        advanceTimeBy(10_000L)
        runCurrent()

        assertFalse(h.controller.isPollingActive())
        assertTrue(h.emittedSnapshots.isEmpty())

        // Post-reload traffic while screen remains off
        h.nowElapsedMs += 20_000L
        h.pendingDeltas.add(OutboundTrafficStat(AppConfig.TAG_PROXY, "uplink", 1_000L))
        h.pendingDeltas.add(OutboundTrafficStat(AppConfig.TAG_PROXY, "downlink", 2_000L))

        // Screen turns ON -> must resume and reflect pre-reload + post-reload bytes
        h.controller.onScreenOn("Canada")
        runCurrent()

        assertTrue(h.controller.isPollingActive())
        val snapshot = h.emittedSnapshots.single()
        assertEquals(4_000L, snapshot.sessionUplinkBytes)
        assertEquals(9_000L, snapshot.sessionDownlinkBytes)
        assertEquals(0L, snapshot.uplinkBps)
        assertEquals(0L, snapshot.downlinkBps)
    }

    @Test
    fun `disconnecting while suspended cleans up session and prevents screen on resurrection`() = runTest(StandardTestDispatcher()) {
        val h = TestHarness(this)
        h.controller.onSessionStart(h.sessionStartWallMs, isInteractive = true)
        h.controller.startSpeedNotification("Netherlands")
        h.nowElapsedMs += 1_000L
        h.pendingDeltas.add(OutboundTrafficStat(AppConfig.TAG_PROXY, "downlink", 5_000L))
        runCurrent()
        assertEquals(1, h.notificationUpdates.size)

        // Suspend on screen off
        h.controller.onScreenOff()
        assertFalse(h.controller.isPollingActive())

        // VPN disconnects while screen is off
        h.serviceRunning = false
        h.controller.stopSessionStats()

        assertFalse(h.controller.isSessionActiveState())
        assertFalse(h.controller.isPollingActive())
        // Stale notification content cleared cleanly once
        assertNull(h.notificationUpdates.last().first)
        assertEquals(0L, h.tracker.currentSnapshot().sessionTotalBytes)

        // Subsequent late SCREEN_ON after disconnect must NOT restart polling
        val updatesBefore = h.notificationUpdates.size
        val broadcastsBefore = h.emittedSnapshots.size
        h.controller.onScreenOn("Netherlands")
        advanceTimeBy(5_000L)
        runCurrent()

        assertFalse(h.controller.isPollingActive())
        assertEquals(updatesBefore, h.notificationUpdates.size)
        assertEquals(broadcastsBefore, h.emittedSnapshots.size)
    }

    @Test
    fun `existing notification formatting deduplication pref toggle and stopSpeedNotification remain intact`() = runTest(StandardTestDispatcher()) {
        val h = TestHarness(this)
        h.controller.onSessionStart(h.sessionStartWallMs, isInteractive = true)
        h.controller.startSpeedNotification("United Kingdom")

        // First sample with proxy + direct traffic
        h.nowElapsedMs += 1_000L
        h.pendingDeltas.addAll(
            listOf(
                OutboundTrafficStat(AppConfig.TAG_PROXY, "uplink", 1_024L),
                OutboundTrafficStat(AppConfig.TAG_PROXY, "downlink", 2_048L),
                OutboundTrafficStat(AppConfig.TAG_DIRECT, "uplink", 512L),
                OutboundTrafficStat(AppConfig.TAG_DIRECT, "downlink", 256L),
            )
        )
        runCurrent()

        assertEquals(1, h.notificationUpdates.size)
        val firstUpdate = h.notificationUpdates.single()
        val text = firstUpdate.first!!
        assertTrue(text.contains("• United Kingdom ↑"))
        assertTrue(text.contains("• ${AppConfig.TAG_DIRECT} ↑"))
        assertTrue(text.contains("↑ 1.5 KB  ↓ 2.3 KB"))
        assertEquals(1_024L, firstUpdate.second)
        assertEquals(2_048L, firstUpdate.third)

        // Idle ticks with identical 0 B/s + unchanged totals skip duplicate notify() calls
        h.nowElapsedMs += 1_000L
        advanceTimeBy(1_000L)
        runCurrent()
        val countAfterFirstZeroTick = h.notificationUpdates.size

        h.nowElapsedMs += 1_000L
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(countAfterFirstZeroTick, h.notificationUpdates.size)

        // Disabling PREF_SPEED_ENABLED clears notification text but keeps emitting MSG_TRAFFIC_STATS while screen ON
        h.speedNotificationEnabled = false
        val broadcastsBeforePrefOff = h.emittedSnapshots.size
        h.nowElapsedMs += 1_000L
        h.pendingDeltas.add(OutboundTrafficStat(AppConfig.TAG_PROXY, "downlink", 4_096L))
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(broadcastsBeforePrefOff + 1, h.emittedSnapshots.size)
        assertNull(h.notificationUpdates.last().first)

        // Re-enable pref, get notification back, then test stopSpeedNotification clears text
        h.speedNotificationEnabled = true
        h.nowElapsedMs += 1_000L
        h.pendingDeltas.add(OutboundTrafficStat(AppConfig.TAG_PROXY, "downlink", 1_024L))
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(h.notificationUpdates.last().first != null)

        h.controller.stopSpeedNotification()
        assertFalse(h.controller.isPollingActive())
        assertNull(h.notificationUpdates.last().first)
    }
}
