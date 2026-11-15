package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DaemonStartGateTest {

    @Test
    fun `newer start supersedes earlier start and blocks stale stopSelf`() {
        val gate = DaemonStartGate()
        gate.resetOnCreate()

        val gen1 = gate.beginStart(startId = 1)
        assertTrue(gen1 > 0L)
        assertTrue(gate.shouldContinueStart(gen1))
        assertEquals(1, gate.currentStartId())

        // Rapid second startForegroundService() arrives before gen1 finished
        val gen2 = gate.beginStart(startId = 2)
        assertTrue(gen2 > gen1)
        assertFalse(gate.shouldContinueStart(gen1))
        assertTrue(gate.shouldContinueStart(gen2))
        assertEquals(2, gate.currentStartId())

        // Stale stop/failure from gen1 must NOT remove foreground or stopSelf
        assertFalse(gate.shouldStopSelf(gen1))
        assertTrue(gate.shouldStopSelf(gen2))
    }

    @Test
    fun `stop supersedes active start and permits stopSelf only if no newer start arrives`() {
        val gate = DaemonStartGate()
        gate.resetOnCreate()

        val startGen = gate.beginStart(startId = 5)
        val stopGen = gate.beginStop()

        assertFalse(gate.shouldContinueStart(startGen))
        assertTrue(gate.shouldStopSelf(stopGen))

        // If a restart happens right before scheduled stopSelf runs on mainHandler,
        // the pending stopSelf from stopGen must be suppressed.
        val restartGen = gate.beginStart(startId = 6)
        assertFalse(gate.shouldStopSelf(stopGen))
        assertTrue(gate.shouldContinueStart(restartGen))
    }

    @Test
    fun `markDestroyed rejects subsequent starts and suppresses deferred stopSelf`() {
        val gate = DaemonStartGate()
        gate.resetOnCreate()

        val startGen = gate.beginStart(startId = 10)
        assertTrue(gate.shouldContinueStart(startGen))

        gate.markDestroyed()
        assertTrue(gate.isDestroyed())
        assertFalse(gate.shouldContinueStart(startGen))
        assertFalse(gate.shouldStopSelf(startGen))
        assertEquals(-1L, gate.beginStart(startId = 11))
    }
}
