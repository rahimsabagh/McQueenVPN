package com.v2ray.ang.core

/**
 * Pure decision policy for auxiliary remote IP lookups after core delay measurement.
 *
 * Kept separate from [CoreServiceManager] so it can be unit-tested on the JVM without
 * initializing native core libraries.
 */
internal object TunnelProbePolicy {
    private const val VERIFY_REQUEST_PREFIX = "verify_"
    private val LATENCY_REGEX = Regex("""\b\d+\s*ms\b""", RegexOption.IGNORE_CASE)

    fun shouldFetchRemoteIpInfo(
        requestId: String,
        coreRunning: Boolean,
        localProxyKept: Boolean,
        httpPort: Int,
    ): Boolean {
        if (requestId.startsWith(VERIFY_REQUEST_PREFIX)) return false
        if (!coreRunning) return false
        if (!localProxyKept) return false
        return httpPort in 1..65535
    }

    fun isVerificationSuccess(delayMs: Long?, messageContent: String): Boolean {
        if (delayMs != null) {
            return delayMs >= 0L
        }
        val firstLine = messageContent.lineSequence().firstOrNull().orEmpty()
        return LATENCY_REGEX.containsMatchIn(firstLine) &&
            !firstLine.contains("fail", ignoreCase = true) &&
            !firstLine.contains("error", ignoreCase = true)
    }
}
