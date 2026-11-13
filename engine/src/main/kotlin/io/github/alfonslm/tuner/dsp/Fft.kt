package io.github.alfonslm.tuner.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place radix-2 complex FFT with precomputed tables. */
class Fft(val size: Int) {
    private val cosTable: DoubleArray
    private val sinTable: DoubleArray
    private val reverse: IntArray

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two" }
        val half = size shr 1
        cosTable = DoubleArray(half) { cos(2 * PI * it / size) }
        sinTable = DoubleArray(half) { sin(2 * PI * it / size) }
        val bits = Integer.numberOfTrailingZeros(size)
        reverse = IntArray(size) { Integer.reverse(it) ushr (32 - bits) }
    }

    fun forward(re: DoubleArray, im: DoubleArray) {
        val n = size
        for (i in 0 until n) {
            val j = reverse[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len
            var start = 0
            while (start < n) {
                var k = 0
                for (j in 0 until half) {
                    val wr = cosTable[k]
                    val wi = -sinTable[k]
                    val a = start + j
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    k += step
                }
                start += len
            }
            len = len shl 1
        }
    }
}

fun nextPowerOfTwo(n: Int): Int {
    var p = 1
    while (p < n) p = p shl 1
    return p
}
