package io.github.alfonslm.tuner.dsp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The pitch of a string, read off its partials one at a time.
 *
 * An autocorrelation peak is one number for the whole sound, and the loudest
 * partials decide it. On a string instrument those are the lowest ones, and
 * they are the least trustworthy: the other open strings ring along at
 * exactly the partials they share with the plucked one (a guitar's low E and
 * A both have a partial on the high E's fundamental), a few cents off and
 * beating against it. The reading is pulled towards them and wobbles.
 *
 * Here every partial is measured on its own: the peak of the spectrum next to
 * where the partial is expected, interpolated to a small fraction of a bin.
 * Each is an estimate of the fundamental once the string's stiffness is taken
 * out (partial k of a stiff string sounds at k·f0·√(1 + B·k²)), and they are
 * combined so that
 *
 *  - a partial counts for more the higher it is (the same error in Hz is k
 *    times fewer cents) and the stronger it is,
 *  - the ones that disagree with the rest are left out: a weighted median
 *    finds what most of the weight agrees on, and only partials close to it
 *    are averaged.
 *
 * The stiffness B is a property of the string, not of the moment, so it is
 * learned as the note sounds: the slope of the partials' offsets against k²,
 * taken robustly (the median of the slopes between pairs of partials) and
 * followed slowly. It starts from the preset's typical value, which the first
 * clean measurement replaces. Frames in which the partials do not line up the
 * way a stiff string's do (a pluck's attack, two notes in one window) teach
 * nothing.
 *
 * A partial that disagrees is also remembered. While most partials agree, one
 * that does not is being pulled by something else, and that something usually
 * outlasts the plucked string: on a recording of a guitar's high E the
 * fundamental sat between the string's own -7 cents and the 0 cents of the low
 * strings ringing along, and a second after the pluck only the 0 was left.
 * Such a partial is distrusted for the rest of the note, also once the others
 * have faded and there is no majority left to outvote it. When only
 * distrusted partials are left, the string can no longer be read ([faded]).
 *
 * @param zeroPadding FFT size divided by frame length of the analysis that produces the spectra
 */
class PartialPitch(private val model: HarmonicModel, private val geometry: CombGeometry, zeroPadding: Double) {
    /** The string's inharmonicity coefficient as measured so far. */
    var inharmonicity: Double = model.inharmonicity
        private set

    // Quadratic interpolation of a Hann-windowed spectral peak reads slightly towards the
    // bin centre; this cubic correction (Abe & Smith) removes almost all of that bias.
    private val biasCorrection = 0.247560 / (zeroPadding * zeroPadding) + 0.084372 / zeroPadding.pow(4)

    /**
     * True when the last [measure] found the string's sound only in partials
     * that have been disagreeing with the rest: what is left is not the string.
     */
    var faded = false
        private set

    private val distrust = DoubleArray(model.partials)
    private var learned = 0
    private var paused = 0
    private val ceiling = max(4 * model.inharmonicity, MIN_CEILING)
    private val number = IntArray(model.partials)
    private val offset = DoubleArray(model.partials)
    private val weight = DoubleArray(model.partials)
    private val amplitude = DoubleArray(model.partials)
    private val order = arrayOfNulls<Int>(model.partials)
    private var scratch = DoubleArray(64)
    private val slopes = DoubleArray(model.partials * (model.partials - 1) / 2)
    private val slopeWeights = DoubleArray(slopes.size)
    private val slopeOrder = arrayOfNulls<Int>(slopes.size)

    /**
     * The string has been plucked: every partial is its own again. Nothing is
     * learned about the stiffness from the next [frames] measurements, which
     * contain the attack.
     */
    fun pluck(frames: Int) {
        paused = frames
        distrust.fill(0.0)
    }

    /**
     * @param power a Hann-windowed power spectrum
     * @param coarseHz the fundamental to within a few cents, which says where to look
     * @return the fundamental, or `null` if fewer than two partials stand clear of the noise
     */
    fun measure(power: DoubleArray, coarseHz: Double): Double? {
        val binHz = geometry.binHz
        val count = partialCount(coarseHz, model, geometry.maxHz)
        val lastBin = power.size - 2
        val reach = 2.0.pow(SEARCH_CENTS / 1200) - 1

        // 1. Each partial's own frequency.
        var found = 0
        var strongest = 0.0
        for (k in 1..count) {
            val expected = partialFrequency(coarseHz, k, inharmonicity)
            val half = max(binHz, expected * reach)
            var peak = -1
            for (b in max(2, ceil((expected - half) / binHz).toInt())..min(lastBin, floor((expected + half) / binHz).toInt())) {
                if (power[b] > power[b - 1] && power[b] >= power[b + 1] && (peak < 0 || power[b] > power[peak])) peak = b
            }
            if (peak < 0 || power[peak - 1] <= 0 || power[peak + 1] <= 0) continue
            if (power[peak] < MIN_SNR * noiseFloor(power, expected / binHz, 0.5 * coarseHz / binHz)) continue

            val a = ln(power[peak - 1])
            val b = ln(power[peak])
            val c = ln(power[peak + 1])
            var shift = 0.5 * (a - c) / (a - 2 * b + c)
            shift += biasCorrection * (shift - 0.5) * (shift + 0.5) * shift
            number[found] = k
            offset[found] = 1200 * log2((peak + shift) * binHz / (k * coarseHz))
            amplitude[found] = sqrt(power[peak])
            strongest = max(strongest, power[peak])
            found++
        }
        // A partial far below the strongest says nothing the others do not say better.
        var kept = 0
        for (i in 0 until found) {
            if (amplitude[i] * amplitude[i] < MIN_RELATIVE_POWER * strongest) continue
            number[kept] = number[i]
            offset[kept] = offset[i]
            amplitude[kept] = amplitude[i]
            kept++
        }
        // Partials that have been disagreeing with the rest stay out, majority or not.
        val measured = kept
        var doubted = 0
        kept = 0
        for (i in 0 until measured) {
            val k = number[i]
            if (distrust[k - 1] >= DISTRUST_FRAMES) {
                // Kept behind the trusted ones, to be judged against them below.
                doubted++
                continue
            }
            swap(i, kept)
            kept++
        }
        faded = kept < 2 && doubted > 0
        if (kept < 2) return null

        // 2. The string's stiffness, from how the offsets grow with k².
        learnStiffness(kept)

        // 3. What the partials say about the fundamental, each with the stiffness taken out.
        for (i in 0 until measured) {
            val k = number[i]
            offset[i] -= 600 * log2(1 + inharmonicity * k * k)
            weight[i] = k * k * amplitude[i]
        }
        val middle = weightedMedian(offset, weight, order, kept)
        for (i in 0 until kept) scratch[i] = abs(offset[i] - middle)
        val tolerance = AGREE_CENTS + 1.5 * weightedMedian(scratch, weight, order, kept)
        var sum = 0.0
        var total = 0.0
        var agreeing = 0
        for (i in 0 until kept) {
            if (abs(offset[i] - middle) > tolerance) continue
            sum += weight[i] * offset[i]
            total += weight[i]
            agreeing++
        }

        // 4. Who disagreed? Only a clear majority can say.
        if (agreeing >= MAJORITY) {
            for (i in 0 until measured) {
                val k = number[i]
                distrust[k - 1] = if (abs(offset[i] - middle) > tolerance) {
                    min(MAX_DISTRUST, distrust[k - 1] + 1)
                } else {
                    max(0.0, distrust[k - 1] - REGAIN)
                }
            }
        }
        return coarseHz * 2.0.pow(sum / total / 1200)
    }

    private fun swap(i: Int, j: Int) {
        if (i == j) return
        val k = number[i]; number[i] = number[j]; number[j] = k
        val o = offset[i]; offset[i] = offset[j]; offset[j] = o
        val a = amplitude[i]; amplitude[i] = amplitude[j]; amplitude[j] = a
    }

    private fun learnStiffness(count: Int) {
        var pairs = 0
        for (i in 0 until count) {
            for (j in i + 1 until count) {
                val span = (number[j] * number[j] - number[i] * number[i]).toDouble()
                slopes[pairs] = (offset[j] - offset[i]) / span
                slopeWeights[pairs] = span * sqrt(amplitude[i] * amplitude[j])
                pairs++
            }
        }
        // Partials 1 and 2 alone are 3 apart in k²: too short a lever to tell stiffness from error.
        if (paused > 0) {
            paused--
            return
        }
        if (count < 3 || number[count - 1] * number[count - 1] - number[0] * number[0] < MIN_SPAN) return
        val centsPerSquare = weightedMedian(slopes, slopeWeights, slopeOrder, pairs)

        // Do the partials lie on that line? If not, this is not one steady string.
        for (i in 0 until count) {
            weight[i] = amplitude[i]
            scratch[i] = offset[i] - centsPerSquare * number[i] * number[i]
        }
        val level = weightedMedian(scratch, weight, order, count)
        for (i in 0 until count) scratch[i] = abs(scratch[i] - level)
        if (weightedMedian(scratch, weight, order, count) > MAX_MISFIT_CENTS) return

        val measured = (2.0.pow(centsPerSquare / 600) - 1).coerceIn(0.0, ceiling)
        // The first measurements count equally, so a preset that is far off is left behind at once.
        learned++
        inharmonicity += max(LEARNING_RATE, 1.0 / learned) * (measured - inharmonicity)
    }

    /** Mean noise power per bin around a partial: the median of its neighbourhood (exponentially distributed, so mean = median / ln 2). */
    private fun noiseFloor(power: DoubleArray, centreBin: Double, halfWidthBins: Double): Double {
        val from = max(1, (centreBin - halfWidthBins).toInt())
        val to = min(power.size - 1, (centreBin + halfWidthBins).toInt())
        val size = to - from + 1
        if (size < 3) return 0.0
        if (scratch.size < size) scratch = DoubleArray(size)
        System.arraycopy(power, from, scratch, 0, size)
        java.util.Arrays.sort(scratch, 0, size)
        return scratch[size / 2] / LN2
    }

    private fun weightedMedian(values: DoubleArray, weights: DoubleArray, order: Array<Int?>, count: Int): Double {
        for (i in 0 until count) order[i] = i
        java.util.Arrays.sort(order, 0, count) { a, b -> values[a!!].compareTo(values[b!!]) }
        var total = 0.0
        for (i in 0 until count) total += weights[i]
        var below = 0.0
        for (i in 0 until count) {
            val index = order[i]!!
            below += weights[index]
            if (below >= 0.5 * total) return values[index]
        }
        return values[order[count - 1]!!]
    }

    private companion object {
        const val LN2 = 0.6931471805599453

        /** How far from where it is expected a partial is looked for (at least one bin). */
        const val SEARCH_CENTS = 35.0

        /** A partial has to stand this far above the noise around it (13 dB) to be measured... */
        const val MIN_SNR = 20.0

        /** ...and within 40 dB of the strongest one. */
        const val MIN_RELATIVE_POWER = 1e-4

        /** Partials within this many cents of what most of the weight says (plus their usual scatter) are averaged. */
        const val AGREE_CENTS = 2.0

        const val MIN_SPAN = 12

        /** Stiffness is looked for up to four times the preset's value, or this if that is more. */
        const val MIN_CEILING = 2e-4

        /** Half the partials (by strength) have to be this close to the stiff-string line for a frame to teach anything. */
        const val MAX_MISFIT_CENTS = 0.7

        /** Per frame: the stiffness estimate follows over roughly thirty frames. */
        const val LEARNING_RATE = 0.03

        /** This many partials agreeing make a majority that can call another one wrong. */
        const val MAJORITY = 3

        /** A partial that has disagreed in this many more frames than it has made up for is left out. */
        const val DISTRUST_FRAMES = 5.0
        const val MAX_DISTRUST = 12.0

        /** Trust comes back at this many frames per frame of agreeing. */
        const val REGAIN = 0.25
    }
}
