package io.github.alfonslm.tuner.app

import io.github.alfonslm.tuner.engine.PitchSource
import io.github.alfonslm.tuner.engine.TunerMode
import io.github.alfonslm.tuner.engine.TunerReading
import io.github.alfonslm.tuner.music.Target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PitchTraceTest {
    private val a2 = Target("s1:A2", "A2", 45, 110.0)
    private val d3 = Target("s2:D3", "D3", 50, 146.83)

    private fun reading(
        time: Double,
        target: Target? = a2,
        cents: Double? = 0.0,
        inTune: Boolean = false,
        spread: Double? = 1.0,
    ) = TunerReading(
        time = time,
        instrumentId = "guitar",
        mode = TunerMode.LOCKED,
        levelDb = -30.0,
        source = PitchSource.FOCUSED.takeIf { cents != null },
        target = target,
        frequency = cents?.let { 110.0 },
        cents = cents,
        spreadCents = spread.takeIf { cents != null },
        confidence = 1.0,
        inTune = inTune,
        lock = null,
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
    fun `keeps the overtone spread of each moment, as the width of the trace`() {
        val trace = PitchTrace()
            .add(reading(1.00, spread = 6.5))
            .add(reading(1.02, spread = 2.0))
            .add(reading(1.04, spread = null)) // could not be measured: drawn as the bare line
        assertEquals(listOf(6.5f, 2f, 0f), trace.points.map { it.spread })
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
    fun `starts over when the tuner moves to another string`() {
        val trace = PitchTrace().add(reading(1.0, a2, -5.0)).add(reading(1.1, a2, -4.0)).add(reading(1.2, d3, 30.0))
        assertEquals("s2:D3", trace.targetId)
        assertEquals(listOf(30f), trace.points.map { it.cents })
    }
}
