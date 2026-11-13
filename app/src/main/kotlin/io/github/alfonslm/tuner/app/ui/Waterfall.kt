package io.github.alfonslm.tuner.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import io.github.alfonslm.tuner.app.PitchTrace
import io.github.alfonslm.tuner.app.TracePoint
import io.github.alfonslm.tuner.dsp.SPECTRUM_COLUMNS
import io.github.alfonslm.tuner.dsp.SPECTRUM_RANGE_CENTS
import io.github.alfonslm.tuner.engine.SpectrumRow
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Readings further apart than this (seconds) are separate notes, not one line. */
private const val GAP_SECONDS = 0.15

/** Seconds between the faint horizontal time lines. */
private const val GRID_SECONDS = 2.0

/**
 * A rolling spectrogram of the string, falling away from the meter: the newest
 * moment is at the top, directly under the needle, and older ones move down.
 * It shares the meter's horizontal scale.
 *
 * Each row is the string's spectrum on that scale with the noise floor removed
 * (the engine's `FoldedSpectrum`), so brightness is energy: a clean note is a
 * narrow stripe, a stiff or dying one a wider and fainter one, a second string
 * a second stripe, and silence is dark. A stripe that runs straight down is a
 * steady pitch; a slanted one is a pitch that is changing.
 *
 * The thin line over it is the tuner's reading at each moment, in the colour
 * the needle had.
 */
@Composable
fun Waterfall(trace: PitchTrace, inTuneCents: Float, modifier: Modifier = Modifier) {
    val spectrogram = remember(trace.rows) { spectrogramImage(trace.rows) }

    // The cents readout already speaks the current value; the history is visual only.
    Canvas(modifier.clearAndSetSemantics { }) {
        val inset = METER_INSET.toPx()
        val half = size.width / 2 - inset
        val centreX = size.width / 2
        fun x(cents: Float) = centreX + cents / METER_RANGE_CENTS * half
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

        // The spectrogram. One image row per spectrum row, newest first; the whole image
        // slides down between rows, so the picture scrolls smoothly although rows arrive in steps.
        if (spectrogram != null) {
            val rowHeight = (spectrogram.rowSeconds / PitchTrace.SECONDS).toFloat() * size.height
            val columnWidth = (x(SPECTRUM_RANGE_CENTS.toFloat()) - x(-SPECTRUM_RANGE_CENTS.toFloat())) / (SPECTRUM_COLUMNS - 1)
            clipRect {
                drawImage(
                    image = spectrogram.image,
                    dstOffset = IntOffset(
                        (x(-SPECTRUM_RANGE_CENTS.toFloat()) - columnWidth / 2).roundToInt(),
                        (y(spectrogram.newest) - rowHeight / 2).roundToInt(),
                    ),
                    dstSize = IntSize(
                        (columnWidth * SPECTRUM_COLUMNS).roundToInt(),
                        (rowHeight * spectrogram.image.height).roundToInt(),
                    ),
                    filterQuality = FilterQuality.Low,
                )
            }
        }

        // The reading over it.
        val stroke = 1.5.dp.toPx()
        var previous: TracePoint? = null
        for (point in trace.points) {
            val position = Offset(x(point.cents.coerceIn(-METER_RANGE_CENTS, METER_RANGE_CENTS)), y(point.time))
            val color = traceColor(point).copy(alpha = fade(trace.now - point.time))
            val before = previous
            if (before != null && point.time - before.time <= GAP_SECONDS) {
                val from = Offset(x(before.cents.coerceIn(-METER_RANGE_CENTS, METER_RANGE_CENTS)), y(before.time))
                drawLine(color, from, position, strokeWidth = stroke, cap = StrokeCap.Round)
            } else {
                drawCircle(color, radius = stroke / 2, center = position)
            }
            previous = point
        }
    }
}

private class Spectrogram(val image: ImageBitmap, val newest: Double, val rowSeconds: Double)

/** Paints the rows into a small bitmap (one pixel per cent and per row); scaling it up does the smoothing. */
private fun spectrogramImage(rows: List<SpectrumRow>): Spectrogram? {
    val newest = rows.lastOrNull() ?: return null
    val height = ceil(PitchTrace.SECONDS / newest.duration).toInt() + 1
    val pixels = IntArray(SPECTRUM_COLUMNS * height)
    for (row in rows) {
        // Rows sit on a regular time grid; a pause in the stream leaves grid lines empty.
        val line = ((newest.time - row.time) / newest.duration).roundToInt()
        if (line !in 0 until height) continue
        for (column in 0 until SPECTRUM_COLUMNS) pixels[line * SPECTRUM_COLUMNS + column] = levelColor(row.levels[column])
    }
    val bitmap = createBitmap(SPECTRUM_COLUMNS, height)
    bitmap.setPixels(pixels, 0, SPECTRUM_COLUMNS, 0, 0, SPECTRUM_COLUMNS, height)
    return Spectrogram(bitmap.asImageBitmap(), newest.time, newest.duration)
}

private val Glow = TunerColors.Accent.toArgb() and 0x00FFFFFF

/**
 * Energy as light: nothing is transparent, a little is a faint blue haze, a lot
 * is solid and brightens towards white at the very top.
 */
private fun levelColor(level: Float): Int {
    if (level <= 0.02f) return 0
    val alpha = (level.coerceAtMost(1f) * 255).roundToInt()
    val white = ((level - 0.75f) / 0.25f).coerceIn(0f, 1f) * 0.6f
    val r = lerp(Glow shr 16 and 0xFF, 255, white)
    val g = lerp(Glow shr 8 and 0xFF, 255, white)
    val b = lerp(Glow and 0xFF, 255, white)
    return alpha shl 24 or (r shl 16) or (g shl 8) or b
}

private fun lerp(from: Int, to: Int, fraction: Float): Int = (from + (to - from) * fraction).roundToInt()

private fun traceColor(point: TracePoint): Color = when {
    point.inTune -> TunerColors.Good
    abs(point.cents) > 25 -> TunerColors.Bad
    else -> TunerColors.Warn
}

/** Full strength when new, down to a quarter at the bottom edge. */
private fun fade(ageSeconds: Double): Float = (1 - 0.75 * ageSeconds / PitchTrace.SECONDS).toFloat().coerceIn(0.25f, 1f)
