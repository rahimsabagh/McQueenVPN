package com.v2ray.ang.dto

import java.io.Serializable

data class TrafficStatsSnapshot(
    val uplinkBps: Long = 0L,
    val downlinkBps: Long = 0L,
    val sessionStartMs: Long = 0L,
    val sessionUplinkBytes: Long = 0L,
    val sessionDownlinkBytes: Long = 0L,
) : Serializable {
    val sessionTotalBytes: Long
        get() = sessionUplinkBytes.coerceAtLeast(0L) + sessionDownlinkBytes.coerceAtLeast(0L)
}
