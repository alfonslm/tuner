package io.github.alfonslm.tuner.dsp

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Columns of a spectrogram row: one per cent from -[SPECTRUM_RANGE_CENTS] to +[SPECTRUM_RANGE_CENTS]. */
const val SPECTRUM_COLUMNS = 201

/**
 * Cents covered either side of the target: a semitone, twice what the meter
 * shows at once, so that the scale can slide towards a string that is far out.
 */
const val SPECTRUM_RANGE_CENTS = 100.0

data class FoldedSpectrumOptions(
    /** Analysis window length in periods of the target note. Sets the resolution: about 400 / (periods · partial number) cents. */
    val periods: Double,
    /** ...capped to this many samples, so low notes do not need seconds of audio. */
    val maxFrame: Int,
    val maxPartialHz: Double,
    /** Power is counted from this many times the mean noise power upwards. */
    val floorFactor: Double,
    /** A partial is shown once its peak is this far above the noise (dB)... */
    val minSnrDb: Double,
    /** ...and at full weight this much further up. */
    val fadeInDb: Double,
    /** A partial this far above the noise (dB) is drawn at full brightness. */
    val fullScaleDb: Double,
)

/**
 * One row of the tuner's spectrogram: the spectrum around a string's partials,
 * laid over each other on a cents scale centred on the target note, with the
 * noise floor removed.
 *
 * Why not simply the spectrum around the fundamental? The view is only ±50
 * cents wide. At 82 Hz that is ±2.4 Hz, well under what a window of practical
 * length can resolve, so the row would be one featureless smear. Two things fix
 * that, both plain spectrum analysis:
 *
 *  - Every partial carries the same pitch information, and partial k is k times
 *    sharper on a cents scale (the window's resolution is fixed in Hz, a cent
 *    at partial k is k times as many Hz). So the neighbourhoods of all partials
 *    are mapped onto one cents axis and added, a partial counting for more
 *    the sharper it is (k²) and the stronger it is (its amplitude).
 *  - The row is computed from a much longer window than the detectors use
 *    ([FoldedSpectrumOptions.periods]). A history display can afford that: a
 *    quarter of a second of delay is a few pixels of the waterfall.
 *
 * Noise removal is per partial: the floor is the median power in that
 * partial's own neighbourhood, so hum and rumble do not raise it elsewhere.
 * Each partial's slice is scaled to its own peak before adding (the picture is
 * about where the energy is, not which partial is loudest) and the row as a
 * whole is scaled by how far the strongest partial stands above the noise, so
 * a note fading away fades from the display.
 *
 * What the row shows as a result: a stripe where the string's energy is, as
 * narrow as the sound allows. Partials that disagree (a stiff string), a second
 * string, or a pitch that moved within the window all show as a wider or
 * doubled stripe, because that is what the spectrum looks like.
 */
class FoldedSpectrum(
    sampleRate: Int,
    val targetHz: Double,
    private val model: HarmonicModel,
    private val options: FoldedSpectrumOptions,
) {
    private val analyzer: SpectralAnalyzer
    private val power: DoubleArray
    private val slices = Array(model.partials) { DoubleArray(SPECTRUM_COLUMNS) }
    private val peaks = DoubleArray(model.partials)
    private val snrDb = DoubleArray(model.partials)
    private val row = DoubleArray(SPECTRUM_COLUMNS)
    private val ratios = DoubleArray(SPECTRUM_COLUMNS) {
        2.0.pow((it - (SPECTRUM_COLUMNS - 1) / 2) * (2 * SPECTRUM_RANGE_CENTS / (SPECTRUM_COLUMNS - 1)) / 1200)
    }
    private var neighbourhood = DoubleArray(256)
    private val maxHz = min(options.maxPartialHz, 0.4 * sampleRate)

    init {
        val frame = min(options.maxFrame.toDouble(), options.periods * sampleRate / targetHz).roundToInt()
        analyzer = SpectralAnalyzer(sampleRate, nextPowerOfTwo(frame), frame)
        power = DoubleArray(analyzer.bins)
    }

    val frameLength: Int get() = analyzer.frameLength

    /**
     * Computes the row for the most recent [frameLength] samples.
     *
     * @param out [SPECTRUM_COLUMNS] values in 0..1
     * @return false (and an all-zero [out]) when nothing stands out of the noise
     */
    fun compute(frame: FloatArray, out: FloatArray, inharmonicity: Double = model.inharmonicity): Boolean {
        analyzer.powerSpectrum(frame, power)
        val binHz = analyzer.binHz
        val count = partialCount(targetHz, model, maxHz)

        // Each partial's neighbourhood on the cents axis, above its own noise floor.
        var strongest = 0.0
        for (k in 1..count) {
            val centre = partialFrequency(targetHz, k, inharmonicity)
            val noise = noiseFloor(centre / binHz, 0.5 * targetHz / binHz)
            val slice = slices[k - 1]
            var peak = 0.0
            if (noise > 0) {
                for (column in 0 until SPECTRUM_COLUMNS) {
                    val value = max(0.0, powerAt(centre * ratios[column] / binHz) - options.floorFactor * noise)
                    slice[column] = value
                    if (value > peak) peak = value
                }
            }
            peaks[k - 1] = peak
            snrDb[k - 1] = if (noise > 0 && peak > 0) 10 * log10(peak / noise) else 0.0
            if (snrDb[k - 1] >= options.minSnrDb && peak > strongest) strongest = peak
        }

        // Laid over each other. A partial counts for more the sharper it is (k²) and the
        // stronger it is (its amplitude relative to the strongest), so the picture follows
        // the frequencies that carry the sound and a faint overtone cannot pull it aside.
        row.fill(0.0)
        var totalWeight = 0.0
        var strongestDb = 0.0
        for (k in 1..count) {
            if (snrDb[k - 1] < options.minSnrDb) continue
            val peak = peaks[k - 1]
            val weight = k * k * sqrt(peak / strongest) * min(1.0, (snrDb[k - 1] - options.minSnrDb) / options.fadeInDb)
            val slice = slices[k - 1]
            for (column in 0 until SPECTRUM_COLUMNS) row[column] += weight * slice[column] / peak
            totalWeight += weight
            strongestDb = max(strongestDb, snrDb[k - 1])
        }

        if (!(totalWeight > 0)) {
            out.fill(0f)
            return false
        }
        val level = min(1.0, strongestDb / options.fullScaleDb) / totalWeight
        for (column in 0 until SPECTRUM_COLUMNS) out[column] = (row[column] * level).toFloat()
        return true
    }

    /** Mean noise power per bin around a partial: the median of its neighbourhood (exponentially distributed, so mean = median / ln 2). */
    private fun noiseFloor(centreBin: Double, halfWidthBins: Double): Double {
        val from = max(1, (centreBin - halfWidthBins).roundToInt())
        val to = min(power.size - 1, (centreBin + halfWidthBins).roundToInt())
        val count = to - from + 1
        if (count < 8) return 0.0
        if (neighbourhood.size < count) neighbourhood = DoubleArray(count * 2)
        power.copyInto(neighbourhood, 0, from, to + 1)
        neighbourhood.sort(0, count)
        return neighbourhood[count shr 1] / LN2
    }

    /** Power at a fractional bin: a parabola through the log power of the three nearest bins (a window's main lobe is close to one). */
    private fun powerAt(bin: Double): Double {
        val nearest = floor(bin + 0.5).toInt()
        if (nearest < 1 || nearest >= power.size - 1) return 0.0
        val a = power[nearest - 1]
        val b = power[nearest]
        val c = power[nearest + 1]
        if (!(a > 0 && b > 0 && c > 0)) return b
        val la = ln(a)
        val lb = ln(b)
        val lc = ln(c)
        val d = bin - nearest
        return exp(lb + 0.5 * (lc - la) * d + 0.5 * (la - 2 * lb + lc) * d * d)
    }

    private companion object {
        val LN2 = ln(2.0)
    }
}
