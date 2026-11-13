package io.github.alfonslm.tuner

import io.github.alfonslm.tuner.dsp.AnalyzerPool
import io.github.alfonslm.tuner.dsp.FreshOptions
import io.github.alfonslm.tuner.dsp.SpectralHistory
import io.github.alfonslm.tuner.dsp.WideDetector
import io.github.alfonslm.tuner.dsp.nextPowerOfTwo
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val BLOCK = 1024
private const val FRAME = 5545

class SpectralHistoryTest {
    private val analyzer = AnalyzerPool().get(SAMPLE_RATE, 8192, FRAME)

    /** Feeds a signal frame by frame; returns what `each` made of every frame, with the frame's end time. */
    private fun <T> frames(signal: FloatArray, each: (power: DoubleArray) -> T): List<Pair<Double, T>> {
        val power = DoubleArray(analyzer.bins)
        return (FRAME..signal.size step BLOCK).map { end ->
            analyzer.powerSpectrum(signal.copyOfRange(end - FRAME, end), power)
            end.toDouble() / SAMPLE_RATE to each(power)
        }
    }

    private fun history() = SpectralHistory(analyzer.bins, entries = 6, stride = 4, guard = 7, fadeFrames = 70.0)

    @Test
    fun `novelty is near zero for anything steady or fading and jumps when a note starts`() {
        val history = history()
        // Noise, then a string plucked at 1 s that rings on, then a second string at 2.5 s.
        val signal = mix(
            whiteNoise(4.0, 0.01),
            concat(silence(1.0), pluckedString(3.0, 110.0, decay = 4.0)),
            concat(silence(2.5), pluckedString(1.5, 196.0)),
        )
        val novelty = frames(signal) { power -> history.novelty(power).also { history.remember(power) } }
        fun during(from: Double, to: Double) = novelty.filter { it.first >= from && it.first < to }.map { it.second }

        assertTrue(during(0.5, 1.0).all { it in 0.0..0.1 }, "noise: ${during(0.5, 1.0).max()}")
        assertTrue(during(1.05, 1.3).max() > 0.8, "first pluck: ${during(1.05, 1.3).max()}")
        assertTrue(during(1.9, 2.5).all { it < 0.1 }, "ringing: ${during(1.9, 2.5).max()}")
        assertTrue(during(2.55, 2.8).max() > 0.4, "second pluck: ${during(2.55, 2.8).max()}")
        assertTrue(during(3.4, 4.0).all { it < 0.1 }, "both ringing: ${during(3.4, 4.0).max()}")
    }

    @Test
    fun `after a pluck, what was sounding before is taken away and the new string is left`() {
        val history = history()
        val signal = mix(pluckedString(3.0, 110.0, decay = 6.0), concat(silence(1.0), pluckedString(2.0, 196.0)))
        val fresh = DoubleArray(analyzer.bins)
        fun energyAt(power: DoubleArray, hz: Double): Double {
            val bin = (hz / analyzer.binHz).roundToInt()
            return (bin - 3..bin + 3).sumOf { power[it] }
        }
        // (time, share of the old string's fundamental left, share of the new string's fundamental kept)
        val left = ArrayList<Triple<Double, Double, Double>>()
        frames(signal) { power ->
            val time = (left.lastOrNull()?.first ?: 0.0)
            history.fresh(power, fresh).takeIf { it >= 0 }?.let {
                left += Triple(time, energyAt(fresh, 110.0) / energyAt(power, 110.0), energyAt(fresh, 196.0) / energyAt(power, 196.0))
            }
            history.remember(power)
        }
        assertTrue(left.isEmpty()) // no pluck noticed, no background, nothing taken away

        // The owner anchors when it notices the pluck; here, 60 ms after it.
        val anchored = history()
        frames(signal) { it.copyOf() }.forEach { (time, power) ->
            if (!anchored.anchored && time >= 1.06) assertTrue(anchored.anchor())
            if (anchored.anchored) {
                anchored.fresh(power, fresh)
                left += Triple(time, energyAt(fresh, 110.0) / energyAt(power, 110.0), energyAt(fresh, 196.0) / energyAt(power, 196.0))
            }
            anchored.remember(power)
        }
        val early = left.filter { it.first in 1.2..1.4 }
        val late = left.filter { it.first in 2.0..2.5 }
        assertTrue(early.all { it.second < 0.05 }, "old string left: ${early.maxOf { it.second }}")
        assertTrue(early.all { it.third > 0.95 }, "new string kept: ${early.minOf { it.third }}")
        // A second later the background has faded faster than this long-ringing string has, and
        // part of it shows again; the new string is untouched throughout.
        assertTrue(late.all { it.second in 0.1..0.5 }, "old string later: ${late.map { it.second }}")
        assertTrue(late.all { it.third > 0.9 })
    }

    @Test
    fun `the background fades, and without a pluck nothing is taken away`() {
        val history = history()
        val out = DoubleArray(analyzer.bins)
        val power = DoubleArray(analyzer.bins) { 1.0 }
        assertEquals(-1.0, history.fresh(power, out))
        assertFalse(history.anchor()) // nothing remembered yet

        repeat(12) { history.remember(power) }
        assertTrue(history.anchor())
        assertEquals(1.0, history.weight, 1e-9)
        assertEquals(0.0, history.fresh(power, out), 1e-9) // nothing new: all of it was there before

        repeat(70) { history.remember(power) }
        assertEquals(1 / Math.E, history.weight, 1e-9)
        assertEquals(1 - 1 / Math.E, history.fresh(power, out), 1e-9)

        repeat(200) { history.remember(power) }
        assertFalse(history.anchored)
        assertEquals(-1.0, history.fresh(power, out))
    }

    @Test
    fun `the wide detector hears a string plucked over a louder one that is still ringing`() {
        val minHz = 82.41 / 1.19
        val wideAnalyzer = AnalyzerPool().get(SAMPLE_RATE, nextPowerOfTwo(FRAME + ceil(SAMPLE_RATE / minHz).toInt() + 4), FRAME)
        fun detector(fresh: Boolean) = WideDetector(
            wideAnalyzer, minHz, 329.63 * 1.19, peakPickRatio = 0.65, maxPartialHz = 5000.0,
            fresh = if (fresh) FreshOptions(entries = 6, stride = 4, guard = 7, fadeFrames = 70.0, onsetRise = 0.25, minShare = 0.1, minClarity = 0.8) else null,
        )
        // A low E ringing loudly, and the high E plucked over it at 1 s, at half its power.
        val e4 = detune(329.63, 5.0)
        val signal = mix(pluckedString(2.5, 82.41, decay = 6.0), concat(silence(1.0), pluckedString(1.5, e4, amplitude = 0.14)))
        val power = DoubleArray(wideAnalyzer.bins)
        fun listen(detector: WideDetector) = (FRAME..signal.size step BLOCK).map { end ->
            wideAnalyzer.powerSpectrum(signal.copyOfRange(end - FRAME, end), power)
            Triple(end.toDouble() / SAMPLE_RATE, detector.detect(power), detector.onset)
        }

        // On the whole sound, the answer is the low E: its period fits both strings.
        val plain = listen(detector(fresh = false)).filter { it.first in 1.3..2.0 }
        assertTrue(plain.all { abs(cents(assertNotNull(it.second).frequency, 82.41)) < 20 })

        // Hearing what is new, it is the high E, and the pluck is noticed once, as it happens.
        val heard = listen(detector(fresh = true))
        val plucks = heard.filter { it.third && it.first > 0.5 }.map { it.first }
        assertTrue(plucks.isNotEmpty() && plucks.all { it in 1.0..1.4 }, "plucks noticed at $plucks")
        // For the half second that matters: long enough for the engine to lock onto the new string.
        for ((_, estimate, _) in heard.filter { it.first in 1.15..1.5 }) {
            assertTrue(assertNotNull(estimate).fresh)
            assertTrue(abs(cents(estimate.frequency, e4)) < 1)
        }
    }
}
