package com.v2ray.ang.mcqueen

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class CountryServerSelectorTest {

    @Test
    fun `ranks responsive candidates by lowest latency and places failures at tail`() = runTest {
        val candidates = listOf(
            SelectableCandidate("guid-slow", "DE", "198.51.100.1", 443),
            SelectableCandidate("guid-dead", "DE", "198.51.100.2", 443),
            SelectableCandidate("guid-fast", "DE", "198.51.100.3", 443),
            SelectableCandidate("guid-mid", "DE", "198.51.100.4", 443),
        )

        val latencies = mapOf(
            "guid-slow" to 310L,
            "guid-dead" to -1L,
            "guid-fast" to 42L,
            "guid-mid" to 125L,
        )

        val ranked = CountryServerSelector.rankCandidates(
            candidates = candidates,
            timeoutMs = 1000L,
        ) { candidate ->
            latencies.getValue(candidate.guid)
        }

        assertEquals(
            listOf("guid-fast", "guid-mid", "guid-slow", "guid-dead"),
            ranked.map { it.guid },
        )
        assertEquals(42L, ranked.first().latencyMs)
        assertEquals(-1L, ranked.last().latencyMs)
    }

    @Test
    fun `treats slow hanging probe exceeding timeout as failed`() = runTest {
        val candidates = listOf(
            SelectableCandidate("guid-timeout", "NL", "203.0.113.10", 443),
            SelectableCandidate("guid-ok", "NL", "203.0.113.11", 443),
        )

        val ranked = CountryServerSelector.rankCandidates(
            candidates = candidates,
            timeoutMs = 150L,
        ) { candidate ->
            if (candidate.guid == "guid-timeout") {
                delay(500L)
                10L
            } else {
                35L
            }
        }

        assertEquals(listOf("guid-ok", "guid-timeout"), ranked.map { it.guid })
        assertEquals(-1L, ranked.last().latencyMs)
    }

    @Test
    fun `enforces maxConcurrency bound across parallel candidate probes`() = runTest {
        val candidates = (1..8).map { i ->
            SelectableCandidate("guid-$i", "DE", "203.0.113.$i", 443)
        }
        val active = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)

        val ranked = CountryServerSelector.rankCandidates(
            candidates = candidates,
            timeoutMs = 1000L,
            maxConcurrency = 2,
        ) { candidate ->
            val nowActive = active.incrementAndGet()
            maxObserved.updateAndGet { prev -> maxOf(prev, nowActive) }
            delay(30L)
            active.decrementAndGet()
            40L + candidate.guid.substringAfter('-').toLong()
        }

        assertEquals(8, ranked.size)
        assertTrue(maxObserved.get() <= 2, "Expected <= 2 concurrent probes, observed ${maxObserved.get()}")
    }
}
