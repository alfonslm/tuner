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

/**
 * How a spectrum compares with a string sounding at some pitch. Each number
 * answers one question, and none of them minds other strings ringing along:
 * when tuning, the strings plucked a moment ago are still sounding, so "is
 * this the only thing I hear?" is the wrong question to ask.
 */
class HarmonicProfile(
    /**
     * Is there a string at all? Energy on the partials, in multiples of what the
     * noise floor alone would put there. Around zero in noise, tens to thousands
     * for a sounding string.
     */
    val prominence: Double,
    /**
     * Is it the main thing in its band? 0..1: share of the tonal energy
     * (everything standing out of the noise floor) that sits on the partials.
     */
    val explained: Double,
    /**
     * Is it really this low? For m = 2, 3, 5, 7: the share of harmonic energy on
     * partials that are NOT a multiple of m. Near zero means the sound is a note
     * m times higher, and the pitch only its sub-harmonic.
     */
    private val support: DoubleArray,
    /**
     * Is it really this high? Energy found where the partials of a note 2, 3 or
     * 4 times lower would lie (between those of the pitch), relative to the
     * energy on the partials themselves; the largest of the three. Above about
     * one half, the pitch is more likely an overtone of that lower note.
     */
    val subharmonic: Double,
    /**
     * 0..1: share of all energy in the band that sits on the partials, corrected
     * for chance. A plain "how clean is it" figure, used as the reading's confidence.
     */
    val salience: Double,
) {
    /** The smallest [supportFor] of any m. */
    val fundamentalSupport: Double get() = support.min()

    /** Share of harmonic energy that speaks against the sound being a note [multiple] times higher. */
    fun supportFor(multiple: Int): Double {
        var least = 1.0
        for (i in PRIMES.indices) if (multiple % PRIMES[i] == 0) least = min(least, support[i])
        return least
    }
}

private val PRIMES = intArrayOf(2, 3, 5, 7)
private val FULL_SUPPORT = DoubleArray(PRIMES.size) { 1.0 }
private val NO_SUPPORT = DoubleArray(PRIMES.size)
private val NO_MATCH = HarmonicProfile(0.0, 0.0, NO_SUPPORT, 0.0, 0.0)
private val LN2 = ln(2.0)

/** Sounds below this are not held against a note: rumble, handling noise, mains hum. */
private const val SHARE_FROM_HZ = 60.0

/**
 * How much of everything that is sounding is a string at [f0], 0..1: the
 * energy on its partials as a share of all the energy from [SHARE_FROM_HZ] (or
 * from below the string, if that is lower) up to its last partial, less what
 * would lie on the partials by chance. One string alone is close to 1. A
 * second note as loud as the first brings both to about one half, whether it
 * is higher or lower, a ringing string or another instrument.
 *
 * Unlike [HarmonicProfile.salience] this also counts what sounds below the
 * string, where a lower note has most of its energy.
 */
fun stringShare(power: DoubleArray, f0: Double, model: HarmonicModel, geometry: CombGeometry): Double {
    val (binHz, lobeHz, maxHz) = geometry
    val count = partialCount(f0, model, maxHz)
    val halfBins = max(2, (lobeHz / binHz).roundToInt())
    val lastBin = power.size - 1
    var onPartials = 0.0
    var partialBins = 0
    var top = 0
    for (k in 1..count) {
        val centre = (partialFrequency(f0, k, model.inharmonicity) / binHz).roundToInt()
        val from = max(1, centre - halfBins)
        val to = min(lastBin, centre + halfBins)
        for (b in from..to) onPartials += power[b]
        partialBins += max(0, to - from + 1)
        top = to
    }
    val bottom = max(1, min((min(SHARE_FROM_HZ, 0.7 * f0) / binHz).roundToInt(), top))
    var all = 0.0
    for (b in bottom..top) all += power[b]
    if (!(all > 0) || top <= bottom) return 0.0
    val chance = min(0.95, partialBins.toDouble() / (top - bottom + 1))
    return ((onPartials / all - chance) / (1 - chance)).coerceIn(0.0, 1.0)
}

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
    if (!(tonalHarmonic > 0) || !(tonalBand > 0)) return HarmonicProfile(0.0, 0.0, NO_SUPPORT, 0.0, salience)

    val prominence = if (noisePerBin > 0) tonalHarmonic / (harmonicBins * noisePerBin) else Double.MAX_VALUE
    val explained = min(1.0, tonalHarmonic / tonalBand)

    val support = DoubleArray(PRIMES.size) { 1.0 }
    for (i in PRIMES.indices) {
        val m = PRIMES[i]
        if (m > count) break
        var offMultiples = 0.0
        for (k in 1..count) if (k % m != 0) offMultiples += energies[k - 1]
        support[i] = offMultiples / tonalHarmonic
    }

    // Where would the partials of a note d times lower be? On ours, and in between. Energy
    // in between is that note's and nobody else's.
    var subharmonic = 0.0
    for (d in 2..4) {
        // Stay clear of our own partials' lobes, which are `spacing / d` bins away.
        val reach = min(halfBins, floor(spacing / d - halfBins - 1).toInt())
        if (reach < 1) continue
        var between = 0.0
        for (j in 1 until count * d) {
            if (j % d == 0) continue
            val centre = (partialFrequency(f0 / d, j, model.inharmonicity) / binHz).roundToInt()
            val from = max(1, centre - reach)
            val to = min(lastBin, centre + reach)
            if (to < from) continue
            var sum = 0.0
            for (b in from..to) sum += power[b]
            between += max(0.0, sum - (to - from + 1) * noisePerBin)
        }
        subharmonic = max(subharmonic, between / tonalHarmonic)
    }
    return HarmonicProfile(prominence, explained, support, subharmonic, salience)
}
