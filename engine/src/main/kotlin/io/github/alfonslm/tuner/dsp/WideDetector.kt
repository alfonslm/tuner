package io.github.alfonslm.tuner.dsp

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class PitchEstimate(
    val frequency: Double,
    /** 0..1 periodicity of the signal at that pitch. */
    val clarity: Double,
    /**
     * True when the estimate was made on what is new in the spectrum (see
     * [SpectralHistory]): a note that has just started, heard without the ones
     * that were already ringing.
     */
    val fresh: Boolean = false,
)

/**
 * How a detector sets a newly plucked string apart from ones still ringing; see [SpectralHistory].
 *
 * @param entries spectra kept in the short run, one of every [stride] frames
 * @param guard frames an entry must be old before it is used: at least the length of the analysis window
 * @param fadeFrames frames over which the remembered background falls to 1/e
 * @param onsetRise a rise of the novelty by this much within a few frames is a pluck
 * @param minShare least share of the energy that must stand above the background for that part to be examined on its own
 * @param minClarity least periodicity of that part for the wide detector to believe its pitch
 */
data class FreshOptions(
    val entries: Int,
    val stride: Int,
    val guard: Int,
    val fadeFrames: Double,
    val onsetRise: Double,
    val minShare: Double,
    val minClarity: Double,
)

/**
 * Acquisition detector: knows nothing about the note being played and searches
 * the instrument's whole range. Used to find out which string is sounding, and
 * afterwards as a watchdog that notices when the player moves to another one.
 *
 * @param peakPickRatio a peak this close to the best one wins if it is at a shorter lag (McLeod pitch method)
 * @param maxPartialHz partials above this are ignored
 */
class WideDetector(
    private val analyzer: SpectralAnalyzer,
    minHz: Double,
    maxHz: Double,
    private val peakPickRatio: Double,
    maxPartialHz: Double,
    private val fresh: FreshOptions? = null,
) {
    private val minLag = max(2, floor(analyzer.sampleRate / maxHz).toInt())
    private val maxLag = min(analyzer.maxLag - 2, ceil(analyzer.sampleRate / minHz).toInt())
    private val acf = DoubleArray(maxLag + 3)
    private val peaks = IntArray(maxLag + 3)
    private val weights = DoubleArray(analyzer.bins)
    private val history = fresh?.let { SpectralHistory(analyzer.bins, it.entries, it.stride, it.guard, it.fadeFrames) }
    private val freshPower = DoubleArray(analyzer.bins)
    private val novelties = DoubleArray(NOVELTY_FRAMES) { 1.0 }
    private var frames = 0L
    private var anchoredAt = -1_000_000L

    /** True if the frame just analysed is where a pluck was noticed. */
    var onset = false
        private set

    init {
        // Band-pass: drop rumble / hum below the lowest string and hiss above the useful partials.
        val lowStop = 0.5 * minHz
        val lowPass = 0.8 * minHz
        val highPass = min(maxPartialHz, 0.4 * analyzer.sampleRate)
        val highStop = highPass * 1.15
        for (b in 1 until analyzer.bins) {
            val f = b * analyzer.binHz
            weights[b] = when {
                f <= lowStop || f >= highStop -> 0.0
                f < lowPass -> 0.5 - 0.5 * cos(PI * (f - lowStop) / (lowPass - lowStop))
                f > highPass -> 0.5 + 0.5 * cos(PI * (f - highPass) / (highStop - highPass))
                else -> 1.0
            }
        }
    }

    /**
     * The pitch of the string plucked last, heard without the ones that were
     * already ringing, if that gives a clear note; otherwise of whatever is
     * sounding. Call once per frame: it also watches for plucks.
     */
    fun detect(power: DoubleArray): PitchEstimate? {
        onset = false
        val history = history
        if (history == null || fresh == null) return estimate(power, false)

        // A pluck: suddenly much of the spectrum is something that was not there a moment ago.
        val novelty = history.novelty(power)
        if (novelty >= 0) {
            // One pluck, one onset: while its rise is still within the frames looked back over,
            // the novelty has nothing new to say.
            val settled = frames - anchoredAt >= max(fresh.guard, NOVELTY_FRAMES + 2)
            if (settled && novelty - novelties.min() >= fresh.onsetRise && history.anchor()) {
                anchoredAt = frames
                onset = true
            }
            novelties[(frames % NOVELTY_FRAMES).toInt()] = novelty
        }
        history.remember(power)
        frames++

        if (history.fresh(power, freshPower) >= fresh.minShare) {
            estimate(freshPower, true)?.takeIf { it.clarity >= fresh.minClarity }?.let { return it }
        }
        return estimate(power, false)
    }

    private fun estimate(power: DoubleArray, fresh: Boolean): PitchEstimate? {
        val end = maxLag + 1
        if (!analyzer.normalizedAcf(power, weights, end + 1, acf)) return null

        // Skip the lobe around lag zero, then take the highest point of every positive lobe.
        var lag = 1
        while (lag <= end && acf[lag] > 0) lag++
        var peakCount = 0
        var highest = 0.0
        while (lag <= end) {
            while (lag <= end && acf[lag] <= 0) lag++
            var best = -1
            while (lag <= end && acf[lag] > 0) {
                if (acf[lag] >= acf[lag - 1] && acf[lag] > acf[lag + 1] && (best < 0 || acf[lag] > acf[best])) best = lag
                lag++
            }
            if (best in minLag..maxLag) {
                peaks[peakCount++] = best
                if (acf[best] > highest) highest = acf[best]
            }
        }
        if (peakCount == 0) return null

        val threshold = peakPickRatio * highest
        for (i in 0 until peakCount) {
            val candidate = peaks[i]
            if (acf[candidate] >= threshold) {
                val peak = parabolicPeak(acf, candidate)
                return PitchEstimate(analyzer.sampleRate / peak.position, min(1.0, peak.value), fresh)
            }
        }
        return null
    }

    private companion object {
        /** A pluck is a rise of the novelty within this many frames. */
        const val NOVELTY_FRAMES = 8
    }
}
