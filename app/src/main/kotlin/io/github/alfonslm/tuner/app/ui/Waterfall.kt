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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import io.github.alfonslm.tuner.app.PitchTrace
import io.github.alfonslm.tuner.app.TracePoint
import io.github.alfonslm.tuner.app.WaterfallStyle
import io.github.alfonslm.tuner.dsp.SPECTRUM_COLUMNS
import io.github.alfonslm.tuner.dsp.SPECTRUM_RANGE_CENTS
import io.github.alfonslm.tuner.engine.SpectrumRow
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.expm1
import kotlin.math.pow
import kotlin.math.roundToInt

/** Seconds between the faint horizontal time lines. */
private const val GRID_SECONDS = 2.0

/**
 * The recent history of the string, falling away from the meter: the newest
 * moment is at the top, directly under the needle, and older ones move down.
 * It shares the meter's horizontal scale, so something that runs straight down
 * is a steady pitch and something slanted is a pitch that is changing.
 *
 * As a [WaterfallStyle.SPECTROGRAM] each row is the string's spectrum with the
 * noise floor removed (the engine's `FoldedSpectrum`). Colour says where on
 * the scale it is - green in tune, through yellow, to red far off - and
 * brightness says how much energy is there, on black: a clean note lights a
 * narrow stripe, a stiff or dying one a wider and fainter one, a second string
 * a second stripe, and silence is dark. The tuner's reading runs through it as
 * a thin white line, where the tuner knew where the note was: a few readings
 * in passing leave none ([PitchTrace.runs]), and within a stretch it fades
 * where the reading was unclear ([TracePoint.sure]).
 *
 * As a [WaterfallStyle.LINE] it shows only the reading, in the colour the
 * needle had at each moment.
 *
 * @param window what the scale shows, as for the meter (see [scaleWindow])
 */
@Composable
fun Waterfall(trace: PitchTrace, style: WaterfallStyle, inTuneCents: Float, modifier: Modifier = Modifier, window: ScaleWindow = ScaleWindow()) {
    val spectral = style == WaterfallStyle.SPECTROGRAM
    val spectrogram = remember(trace.rows, spectral, inTuneCents) {
        if (spectral) spectrogramImage(trace.rows, inTuneCents) else null
    }

    // The cents readout already speaks the current value; the history is visual only.
    Canvas(modifier.clearAndSetSemantics { }) {
        val inset = METER_INSET.toPx()
        val half = size.width / 2 - inset
        val centreX = size.width / 2
        val left = centreX - half
        val right = centreX + half
        fun x(cents: Float) = centreX + (cents - window.centre) / window.range * half
        fun onScale(cents: Float) = x(cents).coerceIn(left, right)
        fun y(time: Double) = ((trace.now - time) / PitchTrace.SECONDS).toFloat() * size.height

        if (spectral) {
            // Black wherever nothing is sounding, so that anything lit stands out.
            drawRect(color = Color.Black, topLeft = Offset(left, 0f), size = Size(right - left, size.height))
        } else {
            // Where "in tune" is, all the way down.
            val zoneLeft = onScale(-inTuneCents)
            val zoneRight = onScale(inTuneCents)
            drawRect(TunerColors.Good.copy(alpha = 0.08f), Offset(zoneLeft, 0f), Size(zoneRight - zoneLeft, size.height))
        }
        val guide = if (spectral) Color.White.copy(alpha = 0.10f) else TunerColors.Line
        if (x(0f) in left..right) drawLine(guide, Offset(x(0f), 0f), Offset(x(0f), size.height), strokeWidth = 1.dp.toPx())

        // Time lines every two seconds.
        var age = GRID_SECONDS
        while (age < PitchTrace.SECONDS) {
            val lineY = (age / PitchTrace.SECONDS).toFloat() * size.height
            drawLine(guide.copy(alpha = guide.alpha * 0.5f), Offset(left, lineY), Offset(right, lineY), strokeWidth = 1.dp.toPx())
            age += GRID_SECONDS
        }

        // The spectrogram. One image row per spectrum row, newest first; the whole image
        // slides down between rows, so the picture scrolls smoothly although rows arrive in steps.
        if (spectrogram != null) {
            val rowHeight = (spectrogram.rowSeconds / PitchTrace.SECONDS).toFloat() * size.height
            val columnWidth = (x(SPECTRUM_RANGE_CENTS.toFloat()) - x(-SPECTRUM_RANGE_CENTS.toFloat())) / (SPECTRUM_COLUMNS - 1)
            // The rows reach further to either side than the scale shows at once.
            clipRect(left = left, right = right) {
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

        // The reading: a fine white thread through the spectrogram, or the whole picture on its own.
        val stroke = (if (spectral) 1.5.dp else 3.dp).toPx()
        for (run in trace.runs()) {
            var before: TracePoint? = null
            for (point in run) {
                val position = Offset(onScale(point.cents), y(point.time))
                val from = before
                before = point
                // The line is only drawn where the tuner knew where the note was: a stretch between
                // two readings is as firm as the less sure of them, and where neither was sure there is none.
                val sure = if (from != null) min(point.sure, from.sure) else point.sure
                if (sure <= 0.02f) continue
                val color = (if (spectral) Color.White else traceColor(point)).copy(alpha = fade(trace.now - point.time) * sure)
                if (from != null) {
                    drawLine(color, Offset(onScale(from.cents), y(from.time)), position, strokeWidth = stroke, cap = StrokeCap.Round)
                } else {
                    drawCircle(color, radius = stroke / 2, center = position)
                }
            }
        }
    }
}

/**
 * How the scale is coloured by distance from the target (cents → colour): green
 * while in tune, yellow from there to where the needle turns red, red beyond,
 * blending between them.
 */
private val ZONE_STOPS = listOf(
    0f to TunerColors.Good,
    10f to TunerColors.Warn,
    20f to TunerColors.Warn,
    30f to TunerColors.Bad,
    METER_RANGE_CENTS to TunerColors.Bad,
)

private fun zoneColor(cents: Float, inTuneCents: Float): Color {
    val distance = abs(cents)
    if (distance <= inTuneCents) return TunerColors.Good
    val upper = ZONE_STOPS.indexOfFirst { it.first >= distance }.takeIf { it > 0 } ?: return ZONE_STOPS.last().second
    val (from, fromColor) = ZONE_STOPS[upper - 1]
    val (to, toColor) = ZONE_STOPS[upper]
    val start = if (upper == 1) inTuneCents else from
    return lerp(fromColor, toColor, ((distance - start) / (to - start)).coerceIn(0f, 1f))
}

private class Spectrogram(val image: ImageBitmap, val newest: Double, val rowSeconds: Double)

/** Paints the rows into a small bitmap (one pixel per cent and per row); scaling it up does the smoothing. */
private fun spectrogramImage(rows: List<SpectrumRow>, inTuneCents: Float): Spectrogram? {
    val newest = rows.lastOrNull() ?: return null
    val height = ceil(PitchTrace.SECONDS / newest.duration).toInt() + 1
    val centre = (SPECTRUM_COLUMNS - 1) / 2
    val columnCents = 2 * SPECTRUM_RANGE_CENTS.toFloat() / (SPECTRUM_COLUMNS - 1)
    val hues = IntArray(SPECTRUM_COLUMNS) { zoneColor((it - centre) * columnCents, inTuneCents).toArgb() and 0x00FFFFFF }
    val pixels = IntArray(SPECTRUM_COLUMNS * height)
    for (row in rows) {
        // Rows sit on a regular time grid; a pause in the stream leaves grid lines empty.
        val line = ((newest.time - row.time) / newest.duration).roundToInt()
        if (line !in 0 until height) continue
        val peak = row.levels.max()
        if (peak <= LEVEL_FLOOR) continue
        // How loud the row is decides how bright its stripe is; how the energy is spread
        // within the row decides where the stripe is. The two are shaped separately, so a
        // quiet note still gives a clear stripe and a loud one does not flood the scale.
        // What the tuner could not read is not the string: it stays in the picture, but in the background.
        val loudness = peak.coerceAtMost(1f).pow(LOUDNESS_GAMMA) * (if (row.read) 1f else UNREAD_BRIGHTNESS)
        for (column in 0 until SPECTRUM_COLUMNS) {
            pixels[line * SPECTRUM_COLUMNS + column] = stripeColor(row.levels[column] / peak, loudness, hues[column])
        }
    }
    val bitmap = createBitmap(SPECTRUM_COLUMNS, height)
    bitmap.setPixels(pixels, 0, SPECTRUM_COLUMNS, 0, 0, SPECTRUM_COLUMNS, height)
    return Spectrogram(bitmap.asImageBitmap(), newest.time, newest.duration)
}

/**
 * How bright rows are drawn for which the tuner had no reading (a pluck's
 * attack, the string being stopped, other strings ringing on), relative to
 * rows it could read.
 */
private const val UNREAD_BRIGHTNESS = 0.3f

/** Rows whose strongest level is below this are left black. */
private const val LEVEL_FLOOR = 0.03f

/** Below 1: lifts quiet rows, so a note that has faded to a tenth of full level is still drawn at about 40 %. */
private const val LOUDNESS_GAMMA = 0.4f

/**
 * How steeply brightness rises towards a row's peak. Within a row the curve is
 * exponential, `(e^(k·x) - 1) / (e^k - 1)` for a level x relative to the peak,
 * so the part near the peak is spread out and everything below it is pushed
 * down: at k = 5, half the peak's level is drawn at 8 %, 80 % of it at 36 %.
 * The skirts of a spectral peak are wide and faint; this is what keeps them
 * from hazing over the scale and makes a clear peak a clear line.
 */
private const val PEAK_STEEPNESS = 5f

/**
 * Energy as light on black, in the colour of that part of the scale.
 *
 * @param shape this column's level relative to the strongest in its row, 0..1
 * @param loudness how bright the row as a whole is, 0..1
 */
private fun stripeColor(shape: Float, loudness: Float, hue: Int): Int {
    val glow = expm1(PEAK_STEEPNESS * shape.coerceIn(0f, 1f)) / expm1(PEAK_STEEPNESS)
    val alpha = (glow * loudness * 255).roundToInt()
    if (alpha < 4) return 0
    // The very centre of the stripe burns through to white.
    val white = ((glow - 0.7f) / 0.3f).coerceIn(0f, 1f) * 0.55f * loudness
    val r = mix(hue shr 16 and 0xFF, white)
    val g = mix(hue shr 8 and 0xFF, white)
    val b = mix(hue and 0xFF, white)
    return alpha shl 24 or (r shl 16) or (g shl 8) or b
}

private fun mix(channel: Int, white: Float): Int = (channel + (255 - channel) * white).roundToInt()

private fun traceColor(point: TracePoint): Color = when {
    point.inTune -> TunerColors.Good
    abs(point.cents) > 25 -> TunerColors.Bad
    else -> TunerColors.Warn
}

/** Full strength when new, down to a quarter at the bottom edge. */
private fun fade(ageSeconds: Double): Float = (1 - 0.75 * ageSeconds / PitchTrace.SECONDS).toFloat().coerceIn(0.25f, 1f)
