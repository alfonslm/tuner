package io.github.alfonslm.tuner.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Shared front end of both detectors.
 *
 * One Hann-windowed FFT gives the power spectrum P(f). Every pitch estimate is
 * then taken from an autocorrelation computed as IFFT(P · W), where W is a
 * spectral weighting: a plain band-pass for the wide detector, a harmonic comb
 * for the focused one. Filtering therefore costs one multiply per bin.
 *
 * The frame is shorter than the FFT (`frameLength + maxLag <= fftSize`) so the
 * autocorrelation never wraps around, and it is divided by the window's own
 * autocorrelation so the taper does not pull peaks towards shorter lags.
 */
class SpectralAnalyzer(val sampleRate: Int, val fftSize: Int, val frameLength: Int) {
    val bins: Int = fftSize / 2 + 1
    val binHz: Double = sampleRate.toDouble() / fftSize

    /** Beyond half the frame the window overlap is too small to normalise reliably. */
    val maxLag: Int = min(fftSize - frameLength, frameLength shr 1)

    /** Half-width of the window's spectral main lobe, in Hz. */
    val lobeHz: Double = 2.0 * sampleRate / frameLength

    private val fft = Fft(fftSize)
    private val window = DoubleArray(frameLength) { 0.5 - 0.5 * cos(2 * PI * it / frameLength) }
    private val windowAcf = DoubleArray(maxLag + 1)
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)

    init {
        require(frameLength < fftSize) { "frameLength must be smaller than fftSize" }
        // Window autocorrelation via the same FFT (no wrap-around for lags <= fftSize - frameLength).
        window.copyInto(re)
        fft.forward(re, im)
        for (b in 0 until fftSize) {
            re[b] = re[b] * re[b] + im[b] * im[b]
            im[b] = 0.0
        }
        fft.forward(re, im)
        for (lag in 0..maxLag) windowAcf[lag] = re[lag] / fftSize
    }

    /**
     * Windowed power spectrum of the first [frameLength] samples of [frame],
     * written to [out] ([bins] values). Returns the RMS of the frame.
     */
    fun powerSpectrum(frame: FloatArray, out: DoubleArray): Double {
        var mean = 0.0
        for (i in 0 until frameLength) mean += frame[i]
        mean /= frameLength
        var energy = 0.0
        for (i in 0 until frameLength) {
            val sample = frame[i] - mean
            energy += sample * sample
            re[i] = sample * window[i]
        }
        re.fill(0.0, frameLength, fftSize)
        im.fill(0.0)
        fft.forward(re, im)
        for (b in 0 until bins) out[b] = re[b] * re[b] + im[b] * im[b]
        return sqrt(energy / frameLength)
    }

    /**
     * Normalised autocorrelation of the signal after weighting its power spectrum
     * by [weights]. `out[lag]` is ~1 at lags where the filtered signal repeats.
     * Returns false if the weighted spectrum is empty.
     */
    fun normalizedAcf(power: DoubleArray, weights: DoubleArray, maxLag: Int, out: DoubleArray): Boolean {
        val half = fftSize shr 1
        re[0] = 0.0 // DC never carries pitch.
        for (b in 1..half) {
            val value = power[b] * weights[b]
            re[b] = value
            re[fftSize - b] = value
        }
        im.fill(0.0)
        // The spectrum is real and even, so the forward transform equals the inverse.
        fft.forward(re, im)
        val zeroLag = re[0]
        if (!(zeroLag > 0)) return false
        val limit = min(maxLag, this.maxLag)
        val scale = windowAcf[0] / zeroLag
        for (lag in 0..limit) out[lag] = re[lag] * scale / windowAcf[lag]
        return true
    }
}

/**
 * Analyzers hold FFT tables and scratch buffers, so they are shared per geometry.
 * A pool belongs to one engine and must only be used from that engine's thread.
 */
class AnalyzerPool {
    private val analyzers = HashMap<Long, SpectralAnalyzer>()

    fun get(sampleRate: Int, fftSize: Int, frameLength: Int): SpectralAnalyzer =
        analyzers.getOrPut(fftSize.toLong() shl 32 or frameLength.toLong()) {
            SpectralAnalyzer(sampleRate, fftSize, frameLength)
        }
}

class Peak(val position: Double, val value: Double)

/** Refines the position of a sampled peak at integer index [i] with a parabola through its neighbours. */
fun parabolicPeak(values: DoubleArray, i: Int): Peak {
    val a = values[i - 1]
    val b = values[i]
    val c = values[i + 1]
    val denominator = a - 2 * b + c
    if (denominator >= 0) return Peak(i.toDouble(), b)
    val offset = 0.5 * (a - c) / denominator
    return Peak(i + offset, b - 0.25 * (a - c) * offset)
}
