package com.v2ray.ang.mcqueen

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

data class SelectableCandidate(
    val guid: String,
    val countryCode: String,
    val pingHost: String?,
    val pingPort: Int?,
)

object CountryServerSelector {

    /**
     * Measures reachability for [candidates] with bounded parallelism and orders them by lowest latency.
     * Responsive nodes (`latencyMs > 0`) are ranked ahead of timed-out/unresponsive ones (`latencyMs <= 0`),
     * while preserving unreachable candidates at the tail so VPN failover can still attempt them if
     * probing was blocked independently of transport traffic.
     */
    suspend fun rankCandidates(
        candidates: List<SelectableCandidate>,
        timeoutMs: Long = McQueenServerConfig.PROBE_TIMEOUT_MS.toLong(),
        maxConcurrency: Int = McQueenServerConfig.MAX_CONCURRENT_PROBES,
        probe: suspend (SelectableCandidate) -> Long,
    ): List<CandidateLatency> {
        if (candidates.isEmpty()) return emptyList()

        val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
        val measured = coroutineScope {
            candidates.mapIndexed { index, candidate ->
                async {
                    semaphore.withPermit {
                        val rawLatency = withTimeoutOrNull(timeoutMs) {
                            runCatching { probe(candidate) }.getOrDefault(-1L)
                        } ?: -1L
                        index to CandidateLatency(guid = candidate.guid, latencyMs = rawLatency)
                    }
                }
            }.awaitAll()
        }

        return measured
            .sortedWith(
                compareBy<Pair<Int, CandidateLatency>>(
                    { if (it.second.latencyMs > 0L) 0 else 1 },
                    { if (it.second.latencyMs > 0L) it.second.latencyMs else Long.MAX_VALUE },
                    { it.first },
                )
            )
            .map { it.second }
    }
}
