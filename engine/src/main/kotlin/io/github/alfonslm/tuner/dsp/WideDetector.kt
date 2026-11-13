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
) {
    private val minLag = max(2, floor(analyzer.sampleRate / maxHz).toInt())
    private val maxLag = min(analyzer.maxLag - 2, ceil(analyzer.sampleRate / minHz).toInt())
    private val acf = DoubleArray(maxLag + 3)
    private val peaks = IntArray(maxLag + 3)
    private val weights = DoubleArray(analyzer.bins)

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

    fun detect(power: DoubleArray): PitchEstimate? {
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
                return PitchEstimate(analyzer.sampleRate / peak.position, min(1.0, peak.value))
            }
        }
        return null
    }
}
