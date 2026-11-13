package io.github.alfonslm.tuner.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What a plucked or bowed string looks like in the spectrum: partials at
 * (nearly) integer multiples of the fundamental. Stiffness stretches them
 * slightly upwards: f_k = k · f0 · sqrt(1 + B · k²).
 */
data class HarmonicModel(
    /** How many partials the comb uses. */
    val partials: Int,
    /** Inharmonicity coefficient B (0 for an ideal string). */
    val inharmonicity: Double,
)

fun partialFrequency(f0: Double, k: Int, inharmonicity: Double): Double =
    k * f0 * sqrt(1 + inharmonicity * k * k)

/** Number of model partials that fit below [maxHz]. */
fun partialCount(f0: Double, model: HarmonicModel, maxHz: Double): Int {
    var count = 0
    while (count < model.partials && partialFrequency(f0, count + 1, model.inharmonicity) <= maxHz) count++
    return max(1, count)
}

data class CombGeometry(
    val binHz: Double,
    /** Half-width of the analysis window's main lobe in Hz; pass bands are padded by it. */
    val lobeHz: Double,
    val maxHz: Double,
)

/**
 * Fills [out] with a harmonic comb: one pass band per partial of a string
 * tuned to [f0], everything else zero.
 *
 * Each band is flat over ±[halfWidthCents] (so a string that is that far off
 * passes undistorted) and then rolls off with a raised cosine. Partial k is
 * weighted 1/k²: in an autocorrelation a partial pulls on the peak position
 * with strength ∝ power · k², so this gives every partial the same say per unit
 * of power and keeps stretched upper partials from dragging the reading sharp.
 */
fun buildComb(out: DoubleArray, f0: Double, halfWidthCents: Double, model: HarmonicModel, geometry: CombGeometry) {
    out.fill(0.0)
    val (binHz, lobeHz, maxHz) = geometry
    val relativeWidth = 2.0.pow(halfWidthCents / 1200) - 1
    val padding = 0.75 * lobeHz
    val count = partialCount(f0, model, maxHz)
    for (k in 1..count) {
        val centre = partialFrequency(f0, k, model.inharmonicity)
        val flat = centre * relativeWidth + padding
        val edge = flat + padding
        val gain = 1.0 / (k * k)
        val first = max(1, ceil((centre - edge) / binHz).toInt())
        val last = min(out.size - 1, floor((centre + edge) / binHz).toInt())
        for (b in first..last) {
            val distance = abs(b * binHz - centre)
            val taper = if (distance <= flat) 1.0 else 0.5 + 0.5 * cos(PI * (distance - flat) / padding)
            val value = gain * taper
            if (value > out[b]) out[b] = value
        }
    }
}

data class HarmonicProfile(
    /**
     * 0..1: share of the energy in the string's band that sits on the partials of
     * the pitch, corrected for what broadband noise would put there by chance.
     * Low in noise and when nothing string-like is sounding.
     */
    val salience: Double,
    /**
     * 0..1: share of the *tonal* energy (everything standing out of the noise
     * floor) that the partials account for. Stays near 1 for the right string
     * even in heavy noise, and drops when another note is sounding whose partials
     * only partly coincide with the comb (a fourth, a fifth, an octave or a
     * twelfth below).
     */
    val explained: Double,
    /**
     * Smallest share of harmonic energy found on partials that are NOT a multiple
     * of m (m = 2, 3, 5, 7). Near zero means the sound is really a note m times
     * higher and the pitch is only its sub-harmonic.
     */
    val fundamentalSupport: Double,
)

private val PRIMES = intArrayOf(2, 3, 5, 7)
private val SUBHARMONIC_FRACTIONS = doubleArrayOf(1.0 / 3, 1.0 / 2, 2.0 / 3)
private val NO_MATCH = HarmonicProfile(0.0, 0.0, 0.0)
private val LN2 = ln(2.0)

/** Measures how well the (unfiltered) spectrum matches a string sounding at [f0]. */
fun harmonicProfile(power: DoubleArray, f0: Double, model: HarmonicModel, geometry: CombGeometry): HarmonicProfile {
    val (binHz, lobeHz, maxHz) = geometry
    val count = partialCount(f0, model, maxHz)
    val halfBins = max(2, (lobeHz / binHz).roundToInt())
    val lastBin = power.size - 1

    val energies = DoubleArray(count)
    val widths = IntArray(count)
    var harmonicEnergy = 0.0
    var harmonicBins = 0
    var firstCentre = 0
    var lastCentre = 0
    for (k in 1..count) {
        val centre = (partialFrequency(f0, k, model.inharmonicity) / binHz).roundToInt()
        if (k == 1) firstCentre = centre
        lastCentre = centre
        val from = max(1, centre - halfBins)
        val to = min(lastBin, centre + halfBins)
        var sum = 0.0
        for (b in from..to) sum += power[b]
        energies[k - 1] = sum
        widths[k - 1] = max(0, to - from + 1)
        harmonicEnergy += sum
        harmonicBins += widths[k - 1]
    }

    val spacing = f0 / binHz
    val bandFrom = max(1, min(firstCentre - halfBins, (firstCentre - 0.3 * spacing).roundToInt()))
    val bandTo = min(lastBin, max(lastCentre + halfBins, (lastCentre + 0.5 * spacing).roundToInt()))
    val bandBins = bandTo - bandFrom + 1
    if (bandBins <= 0) return NO_MATCH
    var bandEnergy = 0.0
    for (b in bandFrom..bandTo) bandEnergy += power[b]
    if (!(bandEnergy > 0) || !(harmonicEnergy > 0)) return NO_MATCH

    val chance = min(0.95, harmonicBins.toDouble() / bandBins)
    val salience = max(0.0, (harmonicEnergy / bandEnergy - chance) / (1 - chance))

    // Noise floor: the median bin is noise as long as tones occupy under half the band.
    // Noise power per bin is exponentially distributed, so mean = median / ln 2.
    val sorted = power.copyOfRange(bandFrom, bandTo + 1)
    sorted.sort()
    val noisePerBin = sorted[bandBins shr 1] / LN2

    var tonalHarmonic = 0.0
    for (k in 0 until count) {
        energies[k] = max(0.0, energies[k] - widths[k] * noisePerBin)
        tonalHarmonic += energies[k]
    }
    val tonalBand = bandEnergy - bandBins * noisePerBin
    if (!(tonalHarmonic > 0) || !(tonalBand > 0)) return HarmonicProfile(salience, 0.0, 0.0)

    // The pitch may itself be the 2nd or 3rd partial of a lower note (typical for
    // the tail of a low string, when little else is left). That note's own lowest
    // partials sit below the band examined so far: count them as unexplained too.
    var below = 0.0
    for (fraction in SUBHARMONIC_FRACTIONS) {
        val centre = (f0 * fraction / binHz).roundToInt()
        val from = max(1, centre - halfBins)
        val to = min(bandFrom - 1, centre + halfBins)
        if (to < from) continue
        var sum = 0.0
        for (b in from..to) sum += power[b]
        below += max(0.0, sum - (to - from + 1) * noisePerBin)
    }
    val explained = min(1.0, tonalHarmonic / (tonalBand + below))

    var fundamentalSupport = 1.0
    for (m in PRIMES) {
        if (m > count) break
        var offMultiples = 0.0
        for (k in 1..count) if (k % m != 0) offMultiples += energies[k - 1]
        fundamentalSupport = min(fundamentalSupport, offMultiples / tonalHarmonic)
    }
    return HarmonicProfile(salience, explained, fundamentalSupport)
}

/** A partial weaker than this fraction of the strongest one says too little about the pitch to count. */
private const val SPREAD_MIN_RELATIVE_POWER = 0.01

// Quadratic interpolation of a Hann-windowed spectral peak reads slightly towards the
// bin centre; this cubic correction (Abe & Smith) removes almost all of that bias.
private const val HANN_BIAS_C0 = 0.247560
private const val HANN_BIAS_C1 = 0.084372

/**
 * How far the individual partials disagree about the pitch, in cents.
 *
 * A pitch estimate is one number for the whole sound. The spectrum knows more:
 * every partial is its own measurement of the fundamental (its frequency
 * divided by its number), and on a real string they never agree exactly —
 * stiffness spreads them, noise and other ringing strings pull single partials
 * aside, a dying note leaves only ragged remains. This returns the
 * power-weighted RMS distance of those per-partial readings from [f0]: near
 * zero for a clean string, growing as the sound becomes less of one note.
 *
 * @param zeroPadding FFT size divided by frame length of the analysis that produced [power]
 * @return the spread in cents, or `null` if no partial stands out clearly enough to measure
 */
fun partialSpread(
    power: DoubleArray,
    f0: Double,
    model: HarmonicModel,
    geometry: CombGeometry,
    zeroPadding: Double,
): Double? {
    val (binHz, lobeHz, maxHz) = geometry
    val count = partialCount(f0, model, maxHz)
    val halfBins = max(2, (lobeHz / binHz).roundToInt())
    val lastBin = power.size - 2
    val biasCorrection = HANN_BIAS_C0 / (zeroPadding * zeroPadding) + HANN_BIAS_C1 / zeroPadding.pow(4)

    val deviations = DoubleArray(count)
    val weights = DoubleArray(count)
    var strongest = 0.0
    for (k in 1..count) {
        val expected = partialFrequency(f0, k, model.inharmonicity)
        val centre = (expected / binHz).roundToInt()
        var peak = -1
        for (b in max(2, centre - halfBins)..min(lastBin, centre + halfBins)) {
            if (power[b] > power[b - 1] && power[b] >= power[b + 1] && (peak < 0 || power[b] > power[peak])) peak = b
        }
        if (peak < 0 || power[peak - 1] <= 0 || power[peak + 1] <= 0) continue
        val a = ln(power[peak - 1])
        val b = ln(power[peak])
        val c = ln(power[peak + 1])
        var offset = 0.5 * (a - c) / (a - 2 * b + c)
        offset += biasCorrection * (offset - 0.5) * (offset + 0.5) * offset
        deviations[k - 1] = 1200 * ln((peak + offset) * binHz / expected) / LN2
        weights[k - 1] = power[peak]
        strongest = max(strongest, power[peak])
    }
    if (!(strongest > 0)) return null

    var sum = 0.0
    var total = 0.0
    for (k in 0 until count) {
        if (weights[k] < SPREAD_MIN_RELATIVE_POWER * strongest) continue
        sum += weights[k] * deviations[k] * deviations[k]
        total += weights[k]
    }
    return sqrt(sum / total)
}
