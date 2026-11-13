package io.github.alfonslm.tuner.app

import io.github.alfonslm.tuner.engine.SpectrumRow
import io.github.alfonslm.tuner.engine.TunerReading

/** One moment of the reading: how far from the target the string was at [time]. */
data class TracePoint(val time: Double, val cents: Float, val inTune: Boolean)

/**
 * The last few seconds of the tuner's output, for the waterfall under the
 * meter: the rows of the rolling spectrogram, and the reading drawn over them.
 * Together they let the player see how far a turn of the peg moved the string
 * and whether the note is drifting.
 *
 * Everything is placed at the time the sound was made, not the time the
 * result came out: a reading describes audio from about a tenth of a second
 * ago and a spectrogram row from about a quarter, and only on a common time
 * axis does the line run down the middle of the stripe while the pitch moves.
 * The top edge of the picture is therefore the moment the newest reading
 * describes, which is what the needle is showing.
 *
 * Everything in it is measured against one target, so the history starts over
 * when the tuner moves to another string. Time keeps running through silence;
 * the gap between two plucks stays visible as a gap.
 */
data class PitchTrace(
    /** The target the cents scale is centred on. */
    val targetId: String? = null,
    /** The reading, oldest first. */
    val points: List<TracePoint> = emptyList(),
    /** The spectrogram, oldest first. */
    val rows: List<SpectrumRow> = emptyList(),
    /** The top edge of the waterfall: the moment the newest reading describes. */
    val now: Double = 0.0,
    /** How far readings currently trail the audio; kept through silence so the picture does not jump when a note stops. */
    val delay: Double = 0.0,
) {
    fun add(reading: TunerReading): PitchTrace {
        val cents = reading.cents
        val row = reading.spectrum
        val pointTarget = reading.target?.id?.takeIf { cents != null }
        val delay = if (pointTarget != null) reading.delay else delay
        val now = reading.time - delay
        val target = pointTarget ?: row?.targetId ?: targetId
        val same = target == targetId

        var points = if (same) points.since(now) { it.time } else emptyList()
        var rows = if (same) rows.since(now) { it.time } else emptyList()
        if (pointTarget != null && cents != null) points = points + TracePoint(now, cents.toFloat(), reading.inTune)
        if (row != null && row.targetId == target) rows = rows + row
        return PitchTrace(target, points, rows, now, delay)
    }

    private inline fun <T> List<T>.since(now: Double, time: (T) -> Double): List<T> {
        val first = indexOfFirst { now - time(it) <= SECONDS }
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
