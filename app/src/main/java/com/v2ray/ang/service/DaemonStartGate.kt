package com.v2ray.ang.service

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates foreground service start/stop generations across main-thread lifecycle callbacks
 * (`onStartCommand`, `stopService`, `onDestroy`) and background service worker execution.
 *
 * Prevents stale stop or failure cleanups from tearing down or calling `stopSelfResult` on a newer
 * `startForegroundService()` command that has already incremented [operationGeneration].
 */
internal class DaemonStartGate {
    private val operationGeneration = AtomicLong(0L)
    private val lastStartId = AtomicInteger(0)
    private val destroyed = AtomicBoolean(false)

    fun resetOnCreate() {
        destroyed.set(false)
    }

    /**
     * Registers a new start request for [startId] and returns its generation token,
     * or `-1L` if the service has already been destroyed.
     */
    fun beginStart(startId: Int): Long {
        if (destroyed.get()) {
            return -1L
        }
        if (startId > 0) {
            lastStartId.set(startId)
        }
        val gen = operationGeneration.incrementAndGet()
        return if (destroyed.get()) -1L else gen
    }

    /**
     * Invalidates any pending starts and returns the generation token associated with this stop.
     */
    fun beginStop(): Long = operationGeneration.incrementAndGet()

    /**
     * Marks the service as destroyed and invalidates all pending start/stop operations.
     */
    fun markDestroyed() {
        destroyed.set(true)
        operationGeneration.incrementAndGet()
    }

    fun isDestroyed(): Boolean = destroyed.get()

    fun currentStartId(): Int = lastStartId.get()

    fun currentGeneration(): Long = operationGeneration.get()

    fun shouldContinueStart(generation: Long): Boolean =
        generation > 0L && !destroyed.get() && operationGeneration.get() == generation

    fun shouldStopSelf(expectedGeneration: Long): Boolean =
        !destroyed.get() && operationGeneration.get() == expectedGeneration
}
