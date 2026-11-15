package com.v2ray.ang.handler

import android.os.SystemClock
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.TrafficStatsSnapshot

fun interface TrafficCounterSource {
    fun queryOutboundDeltas(): List<OutboundTrafficStat>
}

data class TrafficSampleResult(
    val snapshot: TrafficStatsSnapshot,
    val proxyUplinkBps: Long,
    val proxyDownlinkBps: Long,
    val directUplinkBps: Long,
    val directDownlinkBps: Long,
)

/**
 * Thread-safe VPN traffic tracker using a monotonic clock ([SystemClock.elapsedRealtime])
 * to compute accurate upload/download rates and cumulative session byte totals from
 * Xray-core's reset-on-read outbound traffic counters.
 */
class VpnTrafficTracker(
    private val source: TrafficCounterSource,
    private val monotonicClockMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private val lock = Any()

    private var sessionStartWallMs: Long = 0L
    private var lastSampleElapsedMs: Long = 0L

    private var sessionUplinkBytes: Long = 0L
    private var sessionDownlinkBytes: Long = 0L

    private var pendingProxyUplinkBytes: Long = 0L
    private var pendingProxyDownlinkBytes: Long = 0L
    private var pendingDirectUplinkBytes: Long = 0L
    private var pendingDirectDownlinkBytes: Long = 0L

    private var lastProxyUplinkBps: Long = 0L
    private var lastProxyDownlinkBps: Long = 0L
    private var lastDirectUplinkBps: Long = 0L
    private var lastDirectDownlinkBps: Long = 0L

    fun onSessionStart(sessionStartMs: Long) {
        synchronized(lock) {
            runCatching { source.queryOutboundDeltas() }
            sessionStartWallMs = sessionStartMs.coerceAtLeast(0L)
            lastSampleElapsedMs = monotonicClockMs()
            sessionUplinkBytes = 0L
            sessionDownlinkBytes = 0L
            clearPendingAndRatesLocked()
        }
    }

    fun onCoreReloaded() {
        synchronized(lock) {
            val preReloadStats = runCatching { source.queryOutboundDeltas() }.getOrDefault(emptyList())
            accumulateStatsLocked(preReloadStats, includeInPendingWindow = false)
            lastSampleElapsedMs = monotonicClockMs()
            clearPendingAndRatesLocked()
        }
    }

    fun onSessionStop() {
        synchronized(lock) {
            sessionStartWallMs = 0L
            lastSampleElapsedMs = 0L
            sessionUplinkBytes = 0L
            sessionDownlinkBytes = 0L
            clearPendingAndRatesLocked()
        }
    }

    fun sample(activeSessionStartWallMs: Long = sessionStartWallMs): TrafficSampleResult {
        synchronized(lock) {
            if (activeSessionStartWallMs > 0L) {
                sessionStartWallMs = activeSessionStartWallMs
            }

            val rawStats = runCatching { source.queryOutboundDeltas() }.getOrDefault(emptyList())
            accumulateStatsLocked(rawStats, includeInPendingWindow = true)

            val nowElapsedMs = monotonicClockMs()
            if (lastSampleElapsedMs <= 0L) {
                lastSampleElapsedMs = nowElapsedMs
                clearPendingAndRatesLocked()
                return buildResultLocked()
            }

            val elapsedMs = nowElapsedMs - lastSampleElapsedMs
            when {
                elapsedMs < MIN_SAMPLE_INTERVAL_MS -> {
                    // Keep pending bytes accumulated for the next valid interval without advancing baseline.
                    return buildResultLocked()
                }
                elapsedMs > MAX_RATE_WINDOW_MS -> {
                    // Device woke from deep doze or long pause: keep session totals, reset rate window cleanly.
                    lastSampleElapsedMs = nowElapsedMs
                    clearPendingAndRatesLocked()
                    return buildResultLocked()
                }
                else -> {
                    lastProxyUplinkBps = computeRateBps(pendingProxyUplinkBytes, elapsedMs)
                    lastProxyDownlinkBps = computeRateBps(pendingProxyDownlinkBytes, elapsedMs)
                    lastDirectUplinkBps = computeRateBps(pendingDirectUplinkBytes, elapsedMs)
                    lastDirectDownlinkBps = computeRateBps(pendingDirectDownlinkBytes, elapsedMs)

                    pendingProxyUplinkBytes = 0L
                    pendingProxyDownlinkBytes = 0L
                    pendingDirectUplinkBytes = 0L
                    pendingDirectDownlinkBytes = 0L
                    lastSampleElapsedMs = nowElapsedMs
                    return buildResultLocked()
                }
            }
        }
    }

    fun currentSnapshot(activeSessionStartWallMs: Long = sessionStartWallMs): TrafficStatsSnapshot {
        synchronized(lock) {
            val effectiveStartMs = if (activeSessionStartWallMs > 0L) activeSessionStartWallMs else sessionStartWallMs
            val totalUplinkBps = (lastProxyUplinkBps + lastDirectUplinkBps).coerceAtLeast(0L)
            val totalDownlinkBps = (lastProxyDownlinkBps + lastDirectDownlinkBps).coerceAtLeast(0L)
            return TrafficStatsSnapshot(
                uplinkBps = totalUplinkBps,
                downlinkBps = totalDownlinkBps,
                sessionStartMs = effectiveStartMs,
                sessionUplinkBytes = sessionUplinkBytes.coerceAtLeast(0L),
                sessionDownlinkBytes = sessionDownlinkBytes.coerceAtLeast(0L)
            )
        }
    }

    private fun accumulateStatsLocked(stats: List<OutboundTrafficStat>, includeInPendingWindow: Boolean) {
        for (stat in stats) {
            if (stat.tag == AppConfig.TAG_BLOCKED || stat.tag == AppConfig.TAG_EXIT_NODE) {
                continue
            }
            val safeDelta = stat.value.coerceAtLeast(0L)
            if (safeDelta == 0L) continue

            val isDirect = stat.tag == AppConfig.TAG_DIRECT
            when (stat.direction) {
                DIRECTION_UPLINK -> {
                    sessionUplinkBytes = (sessionUplinkBytes + safeDelta).coerceAtLeast(0L)
                    if (includeInPendingWindow) {
                        if (isDirect) {
                            pendingDirectUplinkBytes += safeDelta
                        } else {
                            pendingProxyUplinkBytes += safeDelta
                        }
                    }
                }
                DIRECTION_DOWNLINK -> {
                    sessionDownlinkBytes = (sessionDownlinkBytes + safeDelta).coerceAtLeast(0L)
                    if (includeInPendingWindow) {
                        if (isDirect) {
                            pendingDirectDownlinkBytes += safeDelta
                        } else {
                            pendingProxyDownlinkBytes += safeDelta
                        }
                    }
                }
            }
        }
    }

    private fun clearPendingAndRatesLocked() {
        pendingProxyUplinkBytes = 0L
        pendingProxyDownlinkBytes = 0L
        pendingDirectUplinkBytes = 0L
        pendingDirectDownlinkBytes = 0L
        lastProxyUplinkBps = 0L
        lastProxyDownlinkBps = 0L
        lastDirectUplinkBps = 0L
        lastDirectDownlinkBps = 0L
    }

    private fun buildResultLocked(): TrafficSampleResult {
        val totalUplinkBps = (lastProxyUplinkBps + lastDirectUplinkBps).coerceAtLeast(0L)
        val totalDownlinkBps = (lastProxyDownlinkBps + lastDirectDownlinkBps).coerceAtLeast(0L)
        return TrafficSampleResult(
            snapshot = TrafficStatsSnapshot(
                uplinkBps = totalUplinkBps,
                downlinkBps = totalDownlinkBps,
                sessionStartMs = sessionStartWallMs,
                sessionUplinkBytes = sessionUplinkBytes.coerceAtLeast(0L),
                sessionDownlinkBytes = sessionDownlinkBytes.coerceAtLeast(0L)
            ),
            proxyUplinkBps = lastProxyUplinkBps,
            proxyDownlinkBps = lastProxyDownlinkBps,
            directUplinkBps = lastDirectUplinkBps,
            directDownlinkBps = lastDirectDownlinkBps
        )
    }

    private fun computeRateBps(bytes: Long, elapsedMs: Long): Long {
        if (bytes <= 0L || elapsedMs <= 0L) return 0L
        return ((bytes * 1000.0) / elapsedMs).toLong().coerceAtLeast(0L)
    }

    companion object {
        private const val DIRECTION_UPLINK = "uplink"
        private const val DIRECTION_DOWNLINK = "downlink"
        const val MIN_SAMPLE_INTERVAL_MS = 250L
        const val MAX_RATE_WINDOW_MS = 15_000L
    }
}
