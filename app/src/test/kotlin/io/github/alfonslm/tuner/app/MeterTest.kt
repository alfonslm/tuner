package io.github.alfonslm.tuner.app

import io.github.alfonslm.tuner.app.ui.headTallness
import io.github.alfonslm.tuner.app.ui.needleOpacity
import io.github.alfonslm.tuner.app.ui.ScaleWindow
import io.github.alfonslm.tuner.app.ui.scaleWindow
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeterTest {
    @Test
    fun `the scale stands still near the target, follows further out and widens for the extremes`() {
        // Near the target nothing moves.
        for (cents in listOf(0f, 3f, -12f, 25f, -25f)) assertEquals(ScaleWindow(0f, 50f), scaleWindow(cents))
        assertEquals(ScaleWindow(0f, 50f), scaleWindow(null))

        // Further out it follows, smoothly: no jump anywhere, and the needle keeps moving outwards.
        val readings = (0..4000).map { it / 10f }
        val windows = readings.map(::scaleWindow)
        for (i in 1 until readings.size) {
            assertTrue(abs(windows[i].centre - windows[i - 1].centre) <= 0.11f, "centre jumps at ${readings[i]}")
            assertTrue(abs(windows[i].range - windows[i - 1].range) <= 0.06f, "range jumps at ${readings[i]}")
            val before = (readings[i - 1] - windows[i - 1].centre) / windows[i - 1].range
            val now = (readings[i] - windows[i].centre) / windows[i].range
            assertTrue(now >= before - 1e-4f, "needle moves back at ${readings[i]}")
        }
        // It starts gently: five cents past the steady part the scale has moved by less than one.
        assertTrue(scaleWindow(30f).centre in 0.01f..1f, "${scaleWindow(30f)}")

        // Whatever the reading, the needle and the target are both on the scale, away from its ends.
        for ((cents, window) in readings.zip(windows)) {
            val needle = (cents - window.centre) / window.range
            val target = (0f - window.centre) / window.range
            assertTrue(needle <= 0.89f, "needle at $needle for $cents")
            assertTrue(target >= -0.9001f, "target at $target for $cents")
        }

        // Up to nearly a semitone the scale only slides; beyond, it shows more.
        assertEquals(50f, scaleWindow(-57f).range)
        assertEquals(50f, scaleWindow(85f).range)
        assertTrue(scaleWindow(150f).range > 80f)
        // The flat side mirrors the sharp side.
        assertEquals(-scaleWindow(120f).centre, scaleWindow(-120f).centre)
        assertEquals(scaleWindow(120f).range, scaleWindow(-120f).range)
    }

    @Test
    fun `the line in the needle is crisp on a clear note and fades as the signal breaks up`() {
        assertEquals(1f, needleOpacity(0.2f, held = false))
        assertEquals(1f, needleOpacity(1f, held = false))
        val steps = listOf(1f, 2f, 3f, 4f, 5f, 6f).map { needleOpacity(it, held = false) }
        assertTrue(steps.zipWithNext().all { (a, b) -> b < a }, "$steps")
        // In a broken-up signal it is all but gone, and stays that way however bad it gets.
        assertTrue(needleOpacity(6f, held = false) < 0.1f)
        assertEquals(needleOpacity(6f, held = false), needleOpacity(30f, held = false))
        // Before the scatter is known it is in between.
        assertTrue(needleOpacity(null, held = false) in 0.3f..0.8f)
        // A reading that is only held is fainter than the same reading live.
        assertTrue(needleOpacity(0.2f, held = true) < needleOpacity(0.2f, held = false))
        // The peak is also shorter the less clear the note is, but always stands out of the glow.
        assertEquals(1f, headTallness(0.3f))
        assertTrue(headTallness(3f) < headTallness(1f) && headTallness(6f) < headTallness(3f))
        assertEquals(headTallness(6f), headTallness(30f))
        assertTrue(headTallness(30f) > 0.6f)
    }
}
