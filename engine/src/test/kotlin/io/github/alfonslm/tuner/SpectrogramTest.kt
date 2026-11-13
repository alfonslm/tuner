package io.github.alfonslm.tuner

import io.github.alfonslm.tuner.dsp.FoldedSpectrum
import io.github.alfonslm.tuner.dsp.FoldedSpectrumOptions
import io.github.alfonslm.tuner.dsp.HarmonicModel
import io.github.alfonslm.tuner.dsp.SPECTRUM_COLUMNS
import io.github.alfonslm.tuner.engine.SpectrumRow
import io.github.alfonslm.tuner.engine.TunerConfig
import io.github.alfonslm.tuner.engine.TunerEngine
import io.github.alfonslm.tuner.engine.TunerReading
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val BLOCK = 1024
private const val A2 = 110.0

/** Cents of the brightest column. */
private fun FloatArray.peakCents(): Int = indices.maxBy { this[it] } - (SPECTRUM_COLUMNS - 1) / 2

/** Width in cents of the part of the row at least half as bright as its peak. */
private fun FloatArray.widthCents(): Int {
    val half = max() / 2
    return indexOfLast { it >= half } - indexOfFirst { it >= half } + 1
}

class FoldedSpectrumTest {
    private val options = FoldedSpectrumOptions(
        periods = 48.0,
        maxFrame = 28800,
        maxPartialHz = 5000.0,
        floorFactor = 3.0,
        minSnrDb = 10.0,
        fadeInDb = 10.0,
        fullScaleDb = 40.0,
    )

    /** The row for the audio ending at sample 40000, with a model that matches the test strings unless given. */
    private fun row(signal: FloatArray, targetHz: Double, model: HarmonicModel = HarmonicModel(8, 0.0)): Pair<Boolean, FloatArray> {
        val analysis = FoldedSpectrum(SAMPLE_RATE, targetHz, model, options)
        val out = FloatArray(SPECTRUM_COLUMNS)
        val found = analysis.compute(signal.copyOfRange(40000 - analysis.frameLength, 40000), out)
        return found to out
    }

    @Test
    fun `a string shows as a narrow bright stripe where it is, on every string`() {
        for ((name, f0) in GUITAR_STRINGS) {
            for (offset in listOf(-37, -4, 0, 12, 30)) {
                val (found, levels) = row(pluckedString(1.2, detune(f0, offset.toDouble())), f0)
                assertTrue(found, "$name $offset")
                assertTrue(abs(levels.peakCents() - offset) <= 1, "$name $offset: peak at ${levels.peakCents()}")
                assertTrue(levels.max() > 0.9, "$name $offset: brightness ${levels.max()}")
                assertTrue(levels.widthCents() <= 11, "$name $offset: ${levels.widthCents()} cents wide")
            }
        }
    }

    @Test
    fun `the noise floor is removed`() {
        // Nothing but noise, or nothing at all: an empty row.
        for (signal in listOf(whiteNoise(1.2, 0.05), silence(1.2))) {
            val (found, levels) = row(signal, A2)
            assertFalse(found)
            assertTrue(levels.all { it == 0f })
        }
        // A string in noise: the stripe, dimmer, and still nothing beside it.
        val (found, levels) = row(mix(pluckedString(1.2, detune(A2, 5.0), amplitude = 0.1), whiteNoise(1.2, 0.05)), A2)
        assertTrue(found)
        assertTrue(abs(levels.peakCents() - 5) <= 1)
        assertTrue(levels.max() in 0.5f..0.9f, "brightness ${levels.max()}")
        assertTrue(levels.filterIndexed { column, _ -> abs(column - 55) > 25 }.all { it < 0.05f })
    }

    @Test
    fun `mains hum next to a low string does not show`() {
        val (_, levels) = row(mix(pluckedString(1.2, A2), hum(1.2, 50.0, 0.02)), A2)
        assertEquals(0, levels.peakCents())
        assertTrue(levels.widthCents() <= 11)
    }

    @Test
    fun `the width is what the spectrum is`() {
        val clean = row(pluckedString(1.2, A2), A2).second

        // Overtones that disagree (a much stiffer string than the model): a broad, dim smear.
        val stiff = row(pluckedString(1.2, A2, inharmonicity = 6e-4), A2).second
        assertTrue(stiff.widthCents() > 2 * clean.widthCents(), "stiff ${stiff.widthCents()} vs clean ${clean.widthCents()}")
        assertTrue(stiff.max() < 0.6 * clean.max())

        // Only the fundamental left: there is nothing sharper in the sound to show.
        val dull = row(pluckedString(1.2, A2, partialAmplitudes = doubleArrayOf(1.0)), A2).second
        assertTrue(dull.widthCents() > 4 * clean.widthCents(), "dull ${dull.widthCents()}")

        // A shorter analysis window resolves less.
        val short = FoldedSpectrum(SAMPLE_RATE, A2, HarmonicModel(8, 0.0), options.copy(periods = 16.0))
        val coarse = FloatArray(SPECTRUM_COLUMNS)
        short.compute(pluckedString(1.2, A2).copyOfRange(40000 - short.frameLength, 40000), coarse)
        assertTrue(coarse.widthCents() > 2 * clean.widthCents())
    }

    @Test
    fun `two strings a few cents apart show as two stripes`() {
        val both = mix(pluckedString(1.2, detune(A2, -14.0)), pluckedString(1.2, detune(A2, 16.0), seed = 3))
        val levels = row(both, A2).second
        val bright = levels.indices.filter { levels[it] > 0.5f * levels.max() }.map { it - 50 }
        assertTrue(bright.any { abs(it + 14) <= 2 } && bright.any { abs(it - 16) <= 2 }, "bright at $bright")
        assertTrue(levels[51] < 0.3f * levels.max(), "the gap between them: ${levels[51]}")
    }
}

class SpectrogramTest {
    private val guitar = TunerConfig(instrumentId = "guitar")

    private fun run(engine: TunerEngine, signal: FloatArray): List<TunerReading> =
        (0..signal.size - BLOCK step BLOCK).map { engine.process(signal.copyOfRange(it, it + BLOCK)) }

    private fun rows(readings: List<TunerReading>): List<SpectrumRow> = readings.mapNotNull { it.spectrum }

    @Test
    fun `rows arrive at a steady rate once a note is on display, centred on its target`() {
        val readings = run(TunerEngine(SAMPLE_RATE, BLOCK, guitar), pluckedString(3.0, detune(A2, 9.0)))
        val rows = rows(readings)
        assertTrue(rows.size > 35, "${rows.size} rows")
        assertTrue(rows.all { it.targetId == "s1:A2" && it.levels.size == SPECTRUM_COLUMNS })
        // One every three blocks, each standing for that much time.
        assertTrue(rows.all { abs(it.duration - 3.0 * BLOCK / SAMPLE_RATE) < 1e-9 })
        assertTrue(rows.zipWithNext().all { (a, b) -> abs(b.time - a.time - a.duration) < 1e-9 })
        // Each row is dated at the middle of the audio it was computed from.
        val last = readings.last { it.spectrum != null }
        assertTrue(last.time - last.spectrum!!.time in 0.2..0.3, "lag ${last.time - last.spectrum!!.time}")

        // Once the window is full of the note, the stripe sits where the reading is.
        for (row in rows.filter { it.time > 1.0 }) {
            assertTrue(abs(row.levels.peakCents() - 9) <= 3, "peak at ${row.levels.peakCents()}")
            assertTrue(row.levels.max() > 0.8)
        }
    }

    @Test
    fun `nothing is drawn before a note has been heard, and silence gives empty rows`() {
        val engine = TunerEngine(SAMPLE_RATE, BLOCK, guitar)
        assertTrue(rows(run(engine, whiteNoise(1.0, 0.01))).isEmpty())

        val readings = run(engine, concat(pluckedString(1.0, A2), silence(2.0)))
        val late = rows(readings).filter { it.time > 2.4 }
        assertTrue(late.isNotEmpty())
        assertTrue(late.all { row -> row.levels.all { it == 0f } })
    }

    @Test
    fun `no rows are computed when the display does not want them`() {
        val config = guitar.copy(spectrogram = guitar.spectrogram.copy(enabled = false))
        assertTrue(rows(run(TunerEngine(SAMPLE_RATE, BLOCK, config), pluckedString(1.5, A2))).isEmpty())
    }

    @Test
    fun `rows follow the tuner to another string`() {
        val readings = run(TunerEngine(SAMPLE_RATE, BLOCK, guitar), concat(pluckedString(1.5, A2), pluckedString(1.5, detune(146.83, -20.0))))
        val rows = rows(readings)
        assertEquals(listOf("s1:A2", "s2:D3"), rows.map { it.targetId }.distinct())
        val settled = rows.last()
        assertTrue(abs(settled.levels.peakCents() + 20) <= 3, "peak at ${settled.levels.peakCents()}")
    }
}
