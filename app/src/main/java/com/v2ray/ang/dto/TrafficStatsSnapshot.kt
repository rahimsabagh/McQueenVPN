package com.v2ray.ang.dto

import java.io.Serializable

data class TrafficStatsSnapshot(
    val uplinkBps: Long = 0L,
    val downlinkBps: Long = 0L,
    val sessionStartMs: Long = 0L,
) : Serializable
