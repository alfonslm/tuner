package io.github.alfonslm.tuner.app

import io.github.alfonslm.tuner.engine.PitchSource
import io.github.alfonslm.tuner.engine.SpectrumRow
import io.github.alfonslm.tuner.engine.TunerMode
import io.github.alfonslm.tuner.engine.TunerReading
import io.github.alfonslm.tuner.music.Target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PitchTraceTest {
    private val a2 = Target("s1:A2", "A2", 45, 110.0)
    private val d3 = Target("s2:D3", "D3", 50, 146.83)

    private fun row(time: Double, target: Target = a2) = SpectrumRow(time, 0.064, target.id, FloatArray(101))

    private fun reading(
        time: Double,
        target: Target? = a2,
        cents: Double? = 0.0,
        inTune: Boolean = false,
        spectrum: SpectrumRow? = null,
        delay: Double = 0.0,
    ) = TunerReading(
        time = time,
        delay = if (cents != null) delay else 0.0,
        instrumentId = "guitar",
        mode = TunerMode.LOCKED,
        levelDb = -30.0,
        source = PitchSource.FOCUSED.takeIf { cents != null },
        target = target,
        frequency = cents?.let { 110.0 },
        cents = cents,
        confidence = 1.0,
        inTune = inTune,
        lock = null,
        spectrum = spectrum,
    )

    @Test
    fun `records where the pitch has been, oldest first`() {
        val trace = PitchTrace()
            .add(reading(1.00, cents = -20.0))
            .add(reading(1.02, cents = -12.0))
            .add(reading(1.04, cents = 1.5, inTune = true))
        assertEquals(listOf(-20f, -12f, 1.5f), trace.points.map { it.cents })
        assertEquals(listOf(false, false, true), trace.points.map { it.inTune })
        assertEquals(1.04, trace.now)
    }

    @Test
    fun `readings are placed at the moment they describe, which is also the top edge`() {
        val trace = PitchTrace().add(reading(1.00, cents = -20.0, delay = 0.115)).add(reading(1.02, cents = -19.0, delay = 0.115))
        assertEquals(listOf(0.885, 0.905), trace.points.map { it.time }.map { Math.round(it * 1000) / 1000.0 })
        assertEquals(0.905, trace.now, 1e-9)

        // When the note stops, the edge keeps moving at the same distance behind the clock instead of jumping.
        val silent = trace.add(reading(1.04, cents = null))
        assertEquals(0.925, silent.now, 1e-9)
        assertEquals(2, silent.points.size)

        // A row computed from a longer window lies further back, below the newest reading.
        val row = SpectrumRow(0.70, 0.064, a2.id, FloatArray(101))
        assertTrue(silent.add(reading(1.06, cents = -18.0, delay = 0.115, spectrum = row)).let { it.rows.single().time < it.points.last().time })
    }

    @Test
    fun `time runs on through silence without adding points`() {
        val trace = PitchTrace().add(reading(1.0)).add(reading(1.5, cents = null)).add(reading(2.0, target = null, cents = null))
        assertEquals(1, trace.points.size)
        assertEquals(2.0, trace.now)
        // A second pluck of the same string continues the same history.
        assertEquals(listOf(1.0, 2.5), trace.add(reading(2.5)).points.map { it.time })
    }

    @Test
    fun `forgets what is older than the window`() {
        var trace = PitchTrace()
        for (step in 0..600) trace = trace.add(reading(step * 0.02))
        assertTrue(trace.points.first().time >= 12.0 - PitchTrace.SECONDS - 1e-9)
        assertEquals(12.0, trace.points.last().time, 1e-9)
        // Everything scrolls away once the note has been gone for the whole window.
        assertTrue(trace.add(reading(12.0 + PitchTrace.SECONDS + 0.1, cents = null)).points.isEmpty())
    }

    @Test
    fun `collects the spectrogram rows that come with some readings, also through silence`() {
        val first = row(0.8)
        val second = row(0.864)
        val trace = PitchTrace()
            .add(reading(1.00, spectrum = first))
            .add(reading(1.02))
            .add(reading(1.06, target = null, cents = null, spectrum = second))
        assertEquals(listOf(first, second), trace.rows)
        assertEquals(2, trace.points.size)
        assertEquals("s1:A2", trace.targetId)

        // Rows scroll out of the window like the reading does.
        assertTrue(trace.add(reading(0.864 + PitchTrace.SECONDS + 0.01, cents = null)).rows.isEmpty())
    }

    @Test
    fun `rows for another string start the picture over`() {
        val trace = PitchTrace().add(reading(1.0, spectrum = row(0.8))).add(reading(1.1, target = null, cents = null, spectrum = row(0.9, d3)))
        assertEquals("s2:D3", trace.targetId)
        assertEquals(listOf("s2:D3"), trace.rows.map { it.targetId })
        assertTrue(trace.points.isEmpty())
    }

    @Test
    fun `starts over when the tuner moves to another string`() {
        val trace = PitchTrace().add(reading(1.0, a2, -5.0)).add(reading(1.1, a2, -4.0)).add(reading(1.2, d3, 30.0))
        assertEquals("s2:D3", trace.targetId)
        assertEquals(listOf(30f), trace.points.map { it.cents })
    }
}
