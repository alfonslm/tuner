package io.github.alfonslm.tuner.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

class SmoothedReading(val cents: Double, val inTune: Boolean)

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
 */
class ReadingSmoother(private val options: DisplayConfig, frameSeconds: Double) {
    private val recent = ArrayDeque<Double>()
    private var value: Double? = null
    private var count = 0
    private var targetId: String? = null
    private var lastTime = Double.NEGATIVE_INFINITY
    private var inTuneSince: Double? = null

    /** How much of the way towards a new estimate the needle moves each frame. */
    private val weight = if (options.smoothingMs > 0) 1 - exp(-frameSeconds * 1000 / options.smoothingMs) else 1.0

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
        val smoothed = value?.let { it + max(weight, 1.0 / count) * (median - it) } ?: median
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
