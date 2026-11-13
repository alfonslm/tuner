package io.github.alfonslm.tuner.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

class SmoothedReading(val cents: Double, val spreadCents: Double?, val inTune: Boolean)

/**
 * Turns per-frame cent offsets into a needle that holds still on a ringing
 * note.
 *
 * A real string does not give a steady estimate: another string's overtone
 * sounding a few cents away makes it beat, two or three times a second, by
 * several cents either way. A median of three first drops a single stray
 * frame; an exponential moving average with a time constant of
 * [DisplayConfig.smoothingMs] then averages the wobble out. The needle trails
 * a pitch that is on the move by that same time.
 *
 * A new note starts the average over, so the needle goes to it at once instead
 * of sliding there from the old value: its first readings are averaged with
 * equal weight until there are enough of them for the moving average to take
 * over. The same happens when the target changes or after a pause.
 *
 * What the average hides is reported next to it: the scatter of the estimates,
 * as their standard deviation over the last [DisplayConfig.spreadMs]. It is
 * measured on how far each estimate lands from the needle, less the recent
 * mean of that, so a pitch that is on the move (and the needle behind it)
 * counts for little: wobble and noise are scatter, a turn of the peg is not.
 */
class ReadingSmoother(private val options: DisplayConfig, frameSeconds: Double) {
    private val recent = ArrayDeque<Double>()
    private var value: Double? = null
    private var count = 0
    private var targetId: String? = null
    private var lastTime = Double.NEGATIVE_INFINITY
    private var inTuneSince: Double? = null
    private var offMean = 0.0
    private var offSquare = 0.0

    /** How much of the way towards a new estimate the needle moves each frame. */
    private val weight = if (options.smoothingMs > 0) 1 - exp(-frameSeconds * 1000 / options.smoothingMs) else 1.0
    private val trendWeight = 1 - exp(-frameSeconds * 1000 / TREND_MS)
    private val spreadWeight = if (options.spreadMs > 0) 1 - exp(-frameSeconds * 1000 / options.spreadMs) else 1.0

    /** How far the needle trails a pitch that is on the move, in seconds. */
    val delay: Double = (options.medianFrames - 1) / 2.0 * frameSeconds + options.smoothingMs / 1000

    fun reset() {
        targetId = null
        settle()
    }

    /** A note has just started. What the needle showed before belongs to the note before it. */
    fun settle() {
        recent.clear()
        value = null
        count = 0
        inTuneSince = null
        offMean = 0.0
        offSquare = 0.0
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

        count++
        // Scatter: of the estimate itself, not of the median, which exists to hide strays.
        val spread = value?.let { needle ->
            val off = cents - needle
            val stray = off - offMean
            offMean += max(trendWeight, 1.0 / (count - 1)) * stray
            offSquare += max(spreadWeight, 1.0 / (count - 1)) * (stray * stray - offSquare)
            if (count > MIN_SPREAD_READINGS) sqrt(offSquare) else null
        }
        val smoothed = value?.let { it + max(weight, 1.0 / count) * (median - it) } ?: median
        value = smoothed

        if (abs(smoothed) <= options.inTuneCents) {
            if (inTuneSince == null) inTuneSince = time
        } else {
            inTuneSince = null
        }
        val since = inTuneSince
        return SmoothedReading(smoothed, spread, since != null && (time - since) * 1000 >= options.inTuneHoldMs)
    }

    private companion object {
        /** A standard deviation of fewer readings than this says nothing. */
        const val MIN_SPREAD_READINGS = 4

        /** How quickly a steady distance between the estimates and the needle stops counting as scatter. */
        const val TREND_MS = 150.0
    }
}
