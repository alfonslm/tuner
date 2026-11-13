package io.github.alfonslm.tuner.app

import io.github.alfonslm.tuner.engine.TunerReading

/** One moment of the waterfall: how far from the target the string was. */
data class TracePoint(val time: Double, val cents: Float, val inTune: Boolean)

/**
 * The recent history of the reading, for the waterfall under the meter: where
 * the pitch has been over the last few seconds, so the player can see how far
 * a turn of the peg moved it and whether the note is drifting.
 *
 * Offsets are only comparable against one target, so the history starts over
 * when the tuner moves to another string. Time keeps running through silence;
 * the gap between two plucks stays visible as a gap.
 */
data class PitchTrace(
    /** The target the points are measured against. */
    val targetId: String? = null,
    /** Oldest first. */
    val points: List<TracePoint> = emptyList(),
    /** Stream time of the latest reading, pitched or not: the top edge of the waterfall. */
    val now: Double = 0.0,
) {
    fun add(reading: TunerReading): PitchTrace {
        val cents = reading.cents
        val target = reading.target?.id
        if (cents == null || target == null) {
            return copy(points = points.recent(reading.time), now = reading.time)
        }
        val kept = if (target == targetId) points.recent(reading.time) else emptyList()
        return PitchTrace(target, kept + TracePoint(reading.time, cents.toFloat(), reading.inTune), reading.time)
    }

    private fun List<TracePoint>.recent(now: Double): List<TracePoint> {
        val first = indexOfFirst { now - it.time <= SECONDS }
        return when (first) {
            0 -> this
            -1 -> emptyList()
            else -> subList(first, size)
        }
    }

    companion object {
        /** How much history the waterfall shows. */
        const val SECONDS = 8.0
    }
}
