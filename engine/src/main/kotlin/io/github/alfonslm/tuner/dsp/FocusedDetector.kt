package io.github.alfonslm.tuner.dsp

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class FocusedDetectorOptions(
    /** Preferred analysis window length, in periods of the target note... */
    val periods: Double,
    /** ...capped to this many samples (keeps low notes responsive)... */
    val maxFrame: Int,
    /** ...but never shorter than this many periods or samples. */
    val minPeriods: Double,
    val minFrame: Int,
    /** Widest half-width (cents) the comb will ever be asked for. */
    val maxWidthCents: Double,
    val maxPartialHz: Double,
    val minPeak: Double,
    val minSalience: Double,
    val minExplained: Double,
    val minFundamentalSupport: Double,
)

class FocusedEstimate(
    val frequency: Double,
    /** 0..1 – how string-like and on-target the sound is. */
    val salience: Double,
    val explained: Double,
    /** False when a pitch was found but the sound does not look like the target string. */
    val valid: Boolean,
)

/**
 * Tracking detector: assumes the string being tuned is close to [targetHz].
 *
 * That assumption buys three things:
 *  - a harmonic comb that only lets through the partials such a string can
 *    have (noise, hum, speech and other strings are removed before estimation),
 *  - a pitch search restricted to the neighbourhood of the target, which makes
 *    octave errors impossible,
 *  - an analysis window sized for this one note (long for low strings, short
 *    for high ones) instead of a compromise for the whole instrument.
 *
 * The price is that it will "find" something near the target in almost any
 * input, so every estimate is checked against the unfiltered spectrum.
 */
class FocusedDetector(
    pool: AnalyzerPool,
    sampleRate: Int,
    val targetHz: Double,
    private val model: HarmonicModel,
    private val options: FocusedDetectorOptions,
) {
    val analyzer: SpectralAnalyzer
    private val geometry: CombGeometry
    private val power: DoubleArray
    private val comb: DoubleArray
    private val acf: DoubleArray

    init {
        val period = sampleRate / targetHz
        val longestLag = ceil(period * 2.0.pow(options.maxWidthCents / 1200)).toInt() + 4
        val frame = max(
            max(options.minFrame.toDouble(), options.minPeriods * period),
            min(options.maxFrame.toDouble(), options.periods * period),
        ).roundToInt()
        analyzer = pool.get(sampleRate, nextPowerOfTwo(frame + longestLag), frame)
        geometry = CombGeometry(analyzer.binHz, analyzer.lobeHz, min(options.maxPartialHz, 0.4 * sampleRate))
        power = DoubleArray(analyzer.bins)
        comb = DoubleArray(analyzer.bins)
        acf = DoubleArray(analyzer.maxLag + 1)
    }

    val frameLength: Int get() = analyzer.frameLength

    /**
     * @param frame the most recent [frameLength] samples
     * @param halfWidthCents how far from the target the string may currently be
     */
    fun detect(frame: FloatArray, halfWidthCents: Double): FocusedEstimate? {
        val width = min(halfWidthCents, options.maxWidthCents)
        analyzer.powerSpectrum(frame, power)
        buildComb(comb, targetHz, width, model, geometry)

        val ratio = 2.0.pow(width / 1200)
        val period = analyzer.sampleRate / targetHz
        val from = max(2, floor(period / ratio).toInt())
        val to = min(analyzer.maxLag - 1, ceil(period * ratio).toInt())
        if (!analyzer.normalizedAcf(power, comb, to + 1, acf)) return null

        var best = -1
        for (lag in from..to) {
            if (acf[lag] >= acf[lag - 1] && acf[lag] > acf[lag + 1] && (best < 0 || acf[lag] > acf[best])) best = lag
        }
        if (best < 0) return null

        val peak = parabolicPeak(acf, best)
        val frequency = analyzer.sampleRate / peak.position
        val profile = harmonicProfile(power, frequency, model, geometry)
        return FocusedEstimate(
            frequency = frequency,
            salience = profile.salience,
            explained = profile.explained,
            valid = peak.value >= options.minPeak &&
                profile.salience >= options.minSalience &&
                profile.explained >= options.minExplained &&
                profile.fundamentalSupport >= options.minFundamentalSupport,
        )
    }
}
