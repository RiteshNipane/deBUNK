package com.debunk.core.detect

import kotlin.math.max

/** Enforces "max one scan per [minIntervalMs]". Pure logic; the caller supplies the clock and does the scheduling. */
class ScanThrottle(private val minIntervalMs: Long = 2_000L) {
    private var lastScanAt: Long? = null

    /** Milliseconds to wait before the next scan may run (0 = run now). */
    fun delayUntilNextScan(now: Long): Long {
        val last = lastScanAt ?: return 0L
        return max(0L, last + minIntervalMs - now)
    }

    fun markScanned(now: Long) {
        lastScanAt = now
    }
}
