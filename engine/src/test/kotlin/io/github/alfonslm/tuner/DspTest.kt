package io.github.alfonslm.tuner

import io.github.alfonslm.tuner.dsp.AnalyzerPool
import io.github.alfonslm.tuner.dsp.CombGeometry
import io.github.alfonslm.tuner.dsp.Fft
import io.github.alfonslm.tuner.dsp.FocusedDetector
import io.github.alfonslm.tuner.dsp.FocusedDetectorOptions
import io.github.alfonslm.tuner.dsp.FocusedEstimate
import io.github.alfonslm.tuner.dsp.HarmonicModel
import io.github.alfonslm.tuner.dsp.PitchEstimate
import io.github.alfonslm.tuner.dsp.WideDetector
import io.github.alfonslm.tuner.dsp.buildComb
import io.github.alfonslm.tuner.dsp.harmonicProfile
import io.github.alfonslm.tuner.dsp.nextPowerOfTwo
import io.github.alfonslm.tuner.dsp.partialFrequency
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DspTest {
    private val fs = SAMPLE_RATE
    private val guitar = HarmonicModel(partials = 8, inharmonicity = 5e-5)
    private val pool = AnalyzerPool()
    private val focusOptions = FocusedDetectorOptions(
        periods = 16.0,
        maxFrame = 7200,
        minPeriods = 10.0,
        minFrame = 1920,
        maxWidthCents = 280.0,
        maxPartialHz = 5000.0,
        minPeak = 0.5,
        minSalience = 0.5,
        minExplained = 0.8,
        minFundamentalSupport = 0.06,
    )

    /** Wide detector covering the guitar's range, applied to the frame ending at sample `at`. */
    private val wide: (FloatArray, Int) -> PitchEstimate? = run {
        val minHz = 82.41 / 1.19
        val maxHz = 329.63 * 1.19
        val frame = (8 * fs / minHz).roundToInt()
        val analyzer = pool.get(fs, nextPowerOfTwo(frame + ceil(fs / minHz).toInt() + 4), frame)
        val detector = WideDetector(analyzer, minHz, maxHz, peakPickRatio = 0.9, maxPartialHz = 5000.0)
        val power = DoubleArray(analyzer.bins)
        ({ signal, at ->
            analyzer.powerSpectrum(signal.copyOfRange(at - frame, at), power)
            detector.detect(power)
        })
    }

    private fun focused(
        signal: FloatArray,
        at: Int,
        targetHz: Double,
        widthCents: Double,
        options: FocusedDetectorOptions = focusOptions,
    ): FocusedEstimate? {
        val detector = FocusedDetector(pool, fs, targetHz, guitar, options)
        return detector.detect(signal.copyOfRange(at - detector.frameLength, at), widthCents)
    }

    @Test
    fun `fft puts a sinusoid in the right bin`() {
        val n = 64
        val re = DoubleArray(n) { cos(2 * PI * 5 * it / n) }
        val im = DoubleArray(n)
        Fft(n).forward(re, im)
        for (b in 0 until n) {
            assertEquals(if (b == 5 || b == n - 5) n / 2.0 else 0.0, hypot(re[b], im[b]), 1e-8)
        }
    }

    @Test
    fun `comb passes the partials of the target and blocks what lies between them`() {
        val geometry = CombGeometry(binHz = fs / 8192.0, lobeHz = 2.0 * fs / 7200, maxHz = 5000.0)
        val comb = DoubleArray(4097)
        buildComb(comb, 110.0, 35.0, guitar, geometry)
        fun at(hz: Double) = comb[(hz / geometry.binHz).roundToInt()]
        for (k in 1..8) {
            assertEquals(1.0 / (k * k), at(partialFrequency(110.0, k, guitar.inharmonicity)), 1e-6)
            assertEquals(0.0, at(110 * (k + 0.5)))
        }
        assertEquals(0.0, at(50.0)) // mains hum
        assertEquals(0.0, at(4000.0)) // above the modelled partials
    }

    @Test
    fun `comb widens with the allowed detuning`() {
        val geometry = CombGeometry(binHz = fs / 8192.0, lobeHz = 2.0 * fs / 7200, maxHz = 5000.0)
        val narrow = DoubleArray(4097)
        val wideOpen = DoubleArray(4097)
        buildComb(narrow, 110.0, 35.0, guitar, geometry)
        buildComb(wideOpen, 110.0, 200.0, guitar, geometry)
        assertTrue(wideOpen.count { it > 0 } > 1.5 * narrow.count { it > 0 })
    }

    @Test
    fun `white noise is recognised as not string-like`() {
        val analyzer = pool.get(fs, 8192, 7200)
        val power = DoubleArray(analyzer.bins)
        val geometry = CombGeometry(analyzer.binHz, analyzer.lobeHz, 5000.0)
        for (seed in 1..10) {
            analyzer.powerSpectrum(whiteNoise(0.2, 0.05, seed), power)
            assertTrue(harmonicProfile(power, 110.0, guitar, geometry).salience < 0.25)
        }
    }

    @Test
    fun `wide detector finds every string anywhere within a semitone`() {
        for ((name, f0) in GUITAR_STRINGS) {
            for (offset in listOf(-45.0, -12.0, 0.0, 7.0, 38.0)) {
                val f = detune(f0, offset)
                val estimate = assertNotNull(wide(pluckedString(0.5, f), 20000), "$name $offset")
                assertTrue(abs(cents(estimate.frequency, f)) < 0.2, "$name $offset: ${cents(estimate.frequency, f)}")
                assertTrue(estimate.clarity > 0.95)
            }
        }
    }

    @Test
    fun `wide detector is not fooled by a missing fundamental`() {
        val estimate = assertNotNull(wide(pluckedString(0.5, 82.41, fundamentalGain = 0.0), 20000))
        assertTrue(abs(cents(estimate.frequency, 82.41)) < 0.5)
    }

    @Test
    fun `wide detector reports low clarity for noise`() {
        val estimate = wide(whiteNoise(0.5, 0.1), 20000)
        assertTrue(estimate == null || estimate.clarity < 0.6)
    }

    @Test
    fun `focused detector measures every string to a small fraction of a cent`() {
        for ((name, f0) in GUITAR_STRINGS) {
            for (offset in listOf(-30.0, -4.2, 0.0, 1.5, 30.0)) {
                val f = detune(f0, offset)
                val estimate = assertNotNull(focused(pluckedString(0.5, f), 20000, f0, abs(offset) + 35))
                assertTrue(estimate.valid, "$name $offset")
                assertTrue(abs(cents(estimate.frequency, f)) < 0.1, "$name $offset: ${cents(estimate.frequency, f)}")
            }
        }
    }

    @Test
    fun `focused detector is pulled less by stiff-string inharmonicity than the wide detector`() {
        val signal = pluckedString(0.5, 110.0, inharmonicity = 1e-4)
        val wideError = abs(cents(wide(signal, 20000)!!.frequency, 110.0))
        val focusError = abs(cents(focused(signal, 20000, 110.0, 35.0)!!.frequency, 110.0))
        assertTrue(focusError < 1, "focused error $focusError")
        assertTrue(focusError < wideError / 3, "focused $focusError vs wide $wideError")
    }

    @Test
    fun `focused detector keeps working in noise that defeats the wide detector`() {
        var wideHits = 0
        var focusHits = 0
        for (seed in 1..20) {
            val f = detune(196.0, 6.0)
            val signal = mix(
                pluckedString(0.5, f, amplitude = 0.1, fundamentalGain = 0.3, seed = seed),
                whiteNoise(0.5, 0.04, seed * 13),
                hum(0.5, 50.0, 0.005),
            )
            val wideEstimate = wide(signal, 20000)
            if (wideEstimate != null && wideEstimate.clarity >= 0.85 && abs(cents(wideEstimate.frequency, f)) < 5) wideHits++
            val focus = focused(signal, 20000, 196.0, 45.0)
            if (focus != null && focus.valid && abs(cents(focus.frequency, f)) < 5) focusHits++
        }
        assertTrue(wideHits < 5, "wide hits $wideHits")
        assertTrue(focusHits >= 19, "focused hits $focusHits")
    }

    @Test
    fun `focused detector never validates pure noise`() {
        for (seed in 1..30) {
            val noise = whiteNoise(0.5, 0.05, seed)
            for ((name, f0) in GUITAR_STRINGS) {
                for (width in listOf(35.0, 280.0)) {
                    assertTrue(focused(noise, 20000, f0, width)?.valid != true, "$name seed $seed width $width")
                }
            }
        }
    }

    @Test
    fun `focused detector does not take the fading second partial of a lower string for the target`() {
        // Late in the decay of a low E, on a mic that barely hears 82 Hz, the sound is
        // mostly its 165 Hz partial – which is within reach of a comb opened wide around D3.
        val tail = pluckedString(0.5, 82.41, partialAmplitudes = doubleArrayOf(0.5, 1.0, 0.45, 0.2, 0.04), decay = 30.0)
        val estimate = focused(tail, 20000, 146.83, 400.0, focusOptions.copy(maxWidthCents = 400.0))
        assertTrue(estimate?.valid != true)
    }

    @Test
    fun `focused detector rejects every other string of the instrument, even with the comb wide open`() {
        for ((lockedName, lockedHz) in GUITAR_STRINGS) {
            for ((playedName, playedHz) in GUITAR_STRINGS) {
                val estimate = focused(pluckedString(0.5, playedHz, inharmonicity = 5e-5), 20000, lockedHz, 280.0)
                assertEquals(lockedName == playedName, estimate?.valid == true, "locked $lockedName, played $playedName")
            }
        }
    }
}
