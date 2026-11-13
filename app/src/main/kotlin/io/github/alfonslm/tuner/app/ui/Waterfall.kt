package io.github.alfonslm.tuner.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.alfonslm.tuner.app.PitchTrace
import io.github.alfonslm.tuner.app.TracePoint
import kotlin.math.abs

/** Readings further apart than this (seconds) are separate notes, not one line. */
private const val GAP_SECONDS = 0.15

/** Seconds between the faint horizontal time lines. */
private const val GRID_SECONDS = 2.0

/**
 * The reading's recent history, falling away from the meter: the newest
 * moment is at the top, directly under the needle, and older ones move down.
 * It shares the meter's horizontal scale, so a vertical line means a steady
 * pitch, a slope means it is changing, and the distance to the centre line is
 * how far there is still to go.
 *
 * The trace is coloured like the needle was at that moment and fades with age.
 */
@Composable
fun Waterfall(trace: PitchTrace, inTuneCents: Float, modifier: Modifier = Modifier) {
    // The cents readout already speaks the current value; the history is visual only.
    Canvas(modifier.clearAndSetSemantics { }) {
        val inset = METER_INSET.toPx()
        val half = size.width / 2 - inset
        val centreX = size.width / 2
        fun x(cents: Float) = centreX + (cents / METER_RANGE_CENTS).coerceIn(-1f, 1f) * half
        fun y(time: Double) = ((trace.now - time) / PitchTrace.SECONDS).toFloat() * size.height

        // Where "in tune" is, all the way down.
        val zoneHalf = inTuneCents / METER_RANGE_CENTS * half
        drawRect(TunerColors.Good.copy(alpha = 0.08f), Offset(centreX - zoneHalf, 0f), Size(2 * zoneHalf, size.height))
        drawLine(TunerColors.Line, Offset(centreX, 0f), Offset(centreX, size.height), strokeWidth = 1.dp.toPx())

        // Time lines every two seconds.
        var age = GRID_SECONDS
        while (age < PitchTrace.SECONDS) {
            val lineY = (age / PitchTrace.SECONDS).toFloat() * size.height
            drawLine(TunerColors.Line.copy(alpha = 0.5f), Offset(x(-METER_RANGE_CENTS), lineY), Offset(x(METER_RANGE_CENTS), lineY), strokeWidth = 1.dp.toPx())
            age += GRID_SECONDS
        }

        val stroke = 3.dp.toPx()
        var previous: TracePoint? = null
        for (point in trace.points) {
            val position = Offset(x(point.cents), y(point.time))
            val color = traceColor(point).copy(alpha = fade(trace.now - point.time))
            val before = previous
            if (before != null && point.time - before.time <= GAP_SECONDS) {
                drawLine(color, Offset(x(before.cents), y(before.time)), position, strokeWidth = stroke, cap = StrokeCap.Round)
            } else {
                drawCircle(color, radius = stroke / 2, center = position)
            }
            previous = point
        }
    }
}

private fun traceColor(point: TracePoint): Color = when {
    point.inTune -> TunerColors.Good
    abs(point.cents) > 25 -> TunerColors.Bad
    else -> TunerColors.Warn
}

/** Full strength when new, down to a quarter at the bottom edge. */
private fun fade(ageSeconds: Double): Float = (1 - 0.75 * ageSeconds / PitchTrace.SECONDS).toFloat().coerceIn(0.25f, 1f)
