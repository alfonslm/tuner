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
    /** Least [HarmonicProfile.prominence]: how far the partials must stand out of the noise. */
    val minProminence: Double,
    /** Least [HarmonicProfile.explained]: the string must be the main thing in its band, not the only thing. */
    val minExplained: Double,
    /** Least [HarmonicProfile.fundamentalSupport]. */
    val minFundamentalSupport: Double,
    /** Most [HarmonicProfile.subharmonic]. */
    val maxSubharmonic: Double,
    /** Samples per audio block: the step between frames. */
    val blockSize: Int = 1024,
    /** How the detector sets a newly plucked string apart from ones still ringing; `null` to analyse the whole sound only. */
    val fresh: FreshOptions? = null,
)

class FocusedEstimate(
    val frequency: Double,
    /** How the spectrum compares with the target string sounding at [frequency]. */
    val profile: HarmonicProfile,
    /** False when a pitch was found but the sound does not look like the target string. */
    val valid: Boolean,
    /** True when measured on what is new in the spectrum, with the strings that were already ringing taken away. */
    val fresh: Boolean = false,
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
 * input, so every estimate is checked against the unfiltered spectrum: is
 * there a string here, is it the main one sounding, and is it at this octave?
 * Other strings ringing along do not fail the check; they are the normal case.
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
    private val freshPower: DoubleArray
    private val history: SpectralHistory?
    private var primed = false

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
        freshPower = DoubleArray(analyzer.bins)
        history = options.fresh?.let {
            // Compare only with audio that ended before the current window began.
            val guard = (frame + options.blockSize - 1) / options.blockSize + 1
            SpectralHistory(analyzer.bins, it.entries, it.stride, guard, it.fadeFrames)
        }
    }

    /**
     * Starts over for a new lock. If the string was plucked recently enough, the
     * detector learns from [past] what was sounding before the pluck, so that it
     * can take that away: `past(samplesAgo, frame)` fills `frame` with the audio
     * that ended `samplesAgo` samples ago and returns false if there is not that much.
     *
     * @param onsetSamplesAgo how long ago the last pluck was noticed, or `null` if not known
     */
    fun begin(onsetSamplesAgo: Long?, past: (samplesAgo: Int, frame: FloatArray) -> Boolean) {
        val history = history ?: return
        val stride = options.fresh?.stride ?: return
        history.clear()
        if (onsetSamplesAgo == null) return
        val frame = FloatArray(frameLength)
        // Frames that ended before the pluck, oldest first so that the latest are the ones kept.
        val first = ((onsetSamplesAgo + options.blockSize - 1) / options.blockSize).toInt() + 1
        val steps = generateSequence(first) { it + stride }.takeWhile { past(it * options.blockSize, frame) }.toList()
        for (framesAgo in steps.asReversed()) {
            past(framesAgo * options.blockSize, frame)
            analyzer.powerSpectrum(frame, power)
            history.prime(power, framesAgo)
        }
        primed = steps.isNotEmpty()
    }

    /** A pluck has just been noticed: what has been sounding until now is background from here on. */
    fun pluck() {
        history?.anchor()
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

        // First what is new: the string just plucked, without the ones still ringing.
        val history = history
        val fresh = options.fresh
        if (history != null && fresh != null) {
            if (primed) {
                // The frames learned in begin() all predate the pluck this lock came from.
                primed = false
                history.anchor()
            }
            val share = history.fresh(power, freshPower)
            history.remember(power)
            if (share >= fresh.minShare) estimate(freshPower, width, true)?.takeIf { it.valid }?.let { return it }
        }
        // Otherwise the whole sound.
        return estimate(power, width, false)
    }

    private fun estimate(power: DoubleArray, width: Double, fresh: Boolean): FocusedEstimate? {
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
            profile = profile,
            valid = peak.value >= options.minPeak &&
                profile.prominence >= options.minProminence &&
                profile.explained >= options.minExplained &&
                profile.fundamentalSupport >= options.minFundamentalSupport &&
                profile.subharmonic <= options.maxSubharmonic,
            fresh = fresh,
        )
    }
}
