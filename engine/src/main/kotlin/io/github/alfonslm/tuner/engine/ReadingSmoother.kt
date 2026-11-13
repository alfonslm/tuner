package io.github.alfonslm.tuner.engine

import kotlin.math.abs

class SmoothedReading(val cents: Double, val inTune: Boolean)

/**
 * Turns per-frame cent offsets into a steady needle: a median of three removes
 * a single stray frame, an exponential average removes jitter. Both are kept
 * short so the needle follows the peg closely. Smoothing restarts when the
 * target changes or after a pause.
 */
class ReadingSmoother(private val options: DisplayConfig) {
    private val recent = ArrayDeque<Double>()
    private var value: Double? = null
    private var targetId: String? = null
    private var lastTime = Double.NEGATIVE_INFINITY
    private var inTuneSince: Double? = null

    /** How far the smoothed value trails its input while that is changing steadily, in frames. */
    val delayFrames: Double = (options.medianFrames - 1) / 2.0 + (1 - options.smoothing) / options.smoothing

    fun reset() {
        recent.clear()
        value = null
        targetId = null
        inTuneSince = null
    }

    fun push(targetId: String, cents: Double, time: Double): SmoothedReading {
        if (targetId != this.targetId || (time - lastTime) * 1000 > options.resetGapMs) {
            reset()
            this.targetId = targetId
        }
        lastTime = time

        recent.addLast(cents)
        if (recent.size > options.medianFrames) recent.removeFirst()
        val sorted = recent.sorted()
        val middle = sorted.size shr 1
        val median = if (sorted.size % 2 == 1) sorted[middle] else 0.5 * (sorted[middle - 1] + sorted[middle])

        val smoothed = value?.let { it + options.smoothing * (median - it) } ?: median
        value = smoothed

        if (abs(smoothed) <= options.inTuneCents) {
            if (inTuneSince == null) inTuneSince = time
        } else {
            inTuneSince = null
        }
        val since = inTuneSince
        return SmoothedReading(smoothed, since != null && (time - since) * 1000 >= options.inTuneHoldMs)
    }
}
