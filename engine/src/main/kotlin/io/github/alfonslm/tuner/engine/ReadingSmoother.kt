package io.github.alfonslm.tuner.engine

import kotlin.math.abs

class SmoothedReading(val cents: Double, val inTune: Boolean)

/**
 * Turns per-frame cent offsets into a needle that holds still on a ringing
 * note and still follows the peg.
 *
 * Those pull in opposite directions. A real string does not give a steady
 * estimate: another string's overtone sounding a few cents away makes it beat,
 * two or three times a second, by several cents either way. Averaging that out
 * takes a quarter of a second, and a needle that slow is tiresome to tune by.
 *
 * So the averaging adapts. A median of three first drops a single stray frame.
 * Then, while the estimates stay within a few cents of where the needle is,
 * each one moves it only a little; the further they are from it, the more they
 * are believed, up to moving it most of the way at once. Wobble around a
 * steady pitch is smoothed heavily, and a pitch that has really moved is
 * followed within a few frames, never trailing by more than the width of that
 * steady zone or so.
 *
 * Right after a pluck there is nothing yet to hold steady: for the first few
 * frames every estimate is believed, so the needle goes straight to the new
 * note instead of creeping there from wherever the attack threw it.
 * Smoothing restarts when the target changes or after a pause.
 */
class ReadingSmoother(private val options: DisplayConfig) {
    private val recent = ArrayDeque<Double>()
    private var value: Double? = null
    private var targetId: String? = null
    private var lastTime = Double.NEGATIVE_INFINITY
    private var inTuneSince: Double? = null
    private var settling = 0

    /**
     * How far the needle trails a pitch that is on the move, in frames. (At rest
     * it averages over much longer, but at rest there is nothing to trail.)
     */
    val delayFrames: Double = (options.medianFrames - 1) / 2.0 + (1 - options.movingSmoothing) / options.movingSmoothing

    fun reset() {
        targetId = null
        settle()
    }

    /**
     * A note has just started. What the needle showed before belongs to the
     * note before it: start from this one's first reading, and follow closely
     * for the first few frames.
     */
    fun settle() {
        recent.clear()
        value = null
        inTuneSince = null
        settling = options.settleFrames
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

        val weight = if (settling > 0) options.movingSmoothing else weight(abs(median - (value ?: median)))
        if (settling > 0) settling--
        val smoothed = value?.let { it + weight * (median - it) } ?: median
        value = smoothed

        if (abs(smoothed) <= options.inTuneCents) {
            if (inTuneSince == null) inTuneSince = time
        } else {
            inTuneSince = null
        }
        val since = inTuneSince
        return SmoothedReading(smoothed, since != null && (time - since) * 1000 >= options.inTuneHoldMs)
    }

    /** How much of the way towards a new estimate the needle moves, given how far off that estimate is. */
    private fun weight(distance: Double): Double {
        val x = ((distance - options.steadyCents) / (options.movingCents - options.steadyCents)).coerceIn(0.0, 1.0)
        return options.steadySmoothing + (options.movingSmoothing - options.steadySmoothing) * x * x * (3 - 2 * x)
    }
}
