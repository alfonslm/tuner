package io.github.alfonslm.tuner.engine

import io.github.alfonslm.tuner.music.Target
import kotlin.math.max
import kotlin.math.min

/** A target every recent frame agreed on, with the mean offset from it. */
class Consensus(val target: Target, val cents: Double)

/**
 * Answers "has the same note been sounding steadily?" over the last N frames
 * of the wide detector. This is the trigger that engages the smart filter and
 * the evidence needed to move it to another string.
 */
class StabilityWindow(frames: Int, private val maxSpreadCents: Double) {
    private val size = max(2, frames)
    private val targets = arrayOfNulls<Target>(size)
    private val cents = DoubleArray(size)
    private var next = 0

    /** Records one frame; a `null` target for frames without a clear pitch. */
    fun push(target: Target?, offsetCents: Double = 0.0) {
        targets[next] = target
        cents[next] = offsetCents
        next = (next + 1) % size
    }

    fun clear() {
        targets.fill(null)
    }

    fun consensus(): Consensus? {
        val first = targets[0] ?: return null
        var low = Double.POSITIVE_INFINITY
        var high = Double.NEGATIVE_INFINITY
        var sum = 0.0
        for (i in 0 until size) {
            if (targets[i]?.id != first.id) return null
            low = min(low, cents[i])
            high = max(high, cents[i])
            sum += cents[i]
        }
        if (high - low > maxSpreadCents) return null
        return Consensus(first, sum / size)
    }
}
