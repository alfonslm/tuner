package io.github.alfonslm.tuner

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Deterministic test signals: a plucked-string model plus the things a phone mic adds. */

const val SAMPLE_RATE = 48000

/** Small deterministic PRNG (mulberry32) so every run sees the same noise. */
class Rng(seed: Int) {
    private var state = seed

    fun next(): Double {
        state += 0x6d2b79f5
        var t = state
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return (t xor (t ushr 14)).toUInt().toDouble() / 4294967296.0
    }
}

/**
 * @param pluckPosition relative position along the string (0.5 kills even partials)
 * @param decay amplitude decay time of the fundamental in seconds; upper partials die faster
 * @param fundamentalGain gain applied to the fundamental only – small phone mics barely pick up low notes
 * @param partialAmplitudes explicit per-partial amplitudes; overrides the pluck model
 */
fun pluckedString(
    seconds: Double,
    frequency: Double,
    amplitude: Double = 0.2,
    partials: Int = 12,
    inharmonicity: Double = 0.0,
    pluckPosition: Double = 0.18,
    decay: Double = 2.5,
    fundamentalGain: Double = 1.0,
    partialAmplitudes: DoubleArray? = null,
    seed: Int = 1,
): FloatArray {
    val random = Rng(seed)
    val length = (seconds * SAMPLE_RATE).roundToInt()
    val out = DoubleArray(length)
    val count = partialAmplitudes?.size ?: partials
    for (k in 1..count) {
        val f = k * frequency * sqrt(1 + inharmonicity * k * k)
        if (f > 0.45 * SAMPLE_RATE) break
        var a = partialAmplitudes?.get(k - 1) ?: (abs(sin(k * PI * pluckPosition)) / k)
        if (k == 1) a *= fundamentalGain
        if (a == 0.0) continue
        val phase = 2 * PI * random.next()
        val omega = 2 * PI * f / SAMPLE_RATE
        val tau = decay / (1 + 0.25 * (k - 1)) * SAMPLE_RATE
        for (i in 0 until length) out[i] += a * exp(-i / tau) * sin(omega * i + phase)
    }
    val peak = out.maxOfOrNull { abs(it) } ?: 0.0
    return FloatArray(length) { if (peak > 0) (out[it] * amplitude / peak).toFloat() else 0f }
}

fun whiteNoise(seconds: Double, rms: Double, seed: Int = 7): FloatArray {
    val random = Rng(seed)
    // Sum of uniforms ≈ Gaussian.
    return FloatArray((seconds * SAMPLE_RATE).roundToInt()) {
        (rms * (random.next() + random.next() + random.next() + random.next() - 2) * sqrt(3.0)).toFloat()
    }
}

fun hum(seconds: Double, frequency: Double, amplitude: Double): FloatArray =
    FloatArray((seconds * SAMPLE_RATE).roundToInt()) {
        val t = it.toDouble() / SAMPLE_RATE
        (amplitude * (sin(2 * PI * frequency * t) + 0.5 * sin(2 * PI * 2 * frequency * t + 1) + 0.3 * sin(2 * PI * 3 * frequency * t + 2))).toFloat()
    }

fun silence(seconds: Double) = FloatArray((seconds * SAMPLE_RATE).roundToInt())

/** Sample-wise sum; the result is as long as the longest input. */
fun mix(vararg signals: FloatArray): FloatArray {
    val out = FloatArray(signals.maxOf { it.size })
    for (signal in signals) for (i in signal.indices) out[i] += signal[i]
    return out
}

fun concat(vararg signals: FloatArray): FloatArray {
    val out = FloatArray(signals.sumOf { it.size })
    var offset = 0
    for (signal in signals) {
        signal.copyInto(out, offset)
        offset += signal.size
    }
    return out
}

fun cents(frequency: Double, reference: Double): Double = 1200 * log2(frequency / reference)

fun detune(frequency: Double, offsetCents: Double): Double = frequency * 2.0.pow(offsetCents / 1200)

val GUITAR_STRINGS = listOf("E2" to 82.41, "A2" to 110.0, "D3" to 146.83, "G3" to 196.0, "B3" to 246.94, "E4" to 329.63)

@Suppress("unused")
fun peak(signal: FloatArray): Float = signal.fold(0f) { acc, v -> max(acc, abs(v)) }
