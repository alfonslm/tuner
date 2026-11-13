package io.github.alfonslm.tuner.app

import io.github.alfonslm.tuner.app.ui.headTallness
import io.github.alfonslm.tuner.app.ui.needleOpacity
import io.github.alfonslm.tuner.app.ui.ScaleWindow
import io.github.alfonslm.tuner.app.ui.scaleWindow
import io.github.alfonslm.tuner.app.ui.unclarity
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
    fun `a reading is clear when the estimates agree and the note is alone`() {
        assertEquals(0f, unclarity(0.3f, 0.99f))
        assertEquals(0f, unclarity(1f, 0.93f))
        // Scatter makes it unclear, step by step, up to a limit.
        val scattered = listOf(1f, 2f, 3f, 4f, 5f, 6f).map { unclarity(it, 1f) }
        assertTrue(scattered.zipWithNext().all { (a, b) -> b > a }, "$scattered")
        assertEquals(1f, unclarity(6f, 1f))
        assertEquals(1f, unclarity(30f, 1f))
        // So does company: the less of the sound is this note, the less clear.
        val mixed = listOf(0.92f, 0.85f, 0.75f, 0.65f, 0.55f).map { unclarity(0.3f, it) }
        assertTrue(mixed.zipWithNext().all { (a, b) -> b > a }, "$mixed")
        assertEquals(1f, unclarity(0.3f, 0.4f))
        // The worse of the two counts.
        assertEquals(unclarity(4f, 1f), unclarity(4f, 0.9f))
        assertEquals(unclarity(0.3f, 0.6f), unclarity(2f, 0.6f))
        // Before the scatter is known a reading is neither clear nor broken up.
        assertTrue(unclarity(null, 1f) in 0.2f..0.6f)
        assertEquals(unclarity(null, 1f), unclarity(null, null))
    }

    @Test
    fun `the head is a tall crisp needle for a clear note and a short faint one for a broken-up signal`() {
        assertEquals(1f, needleOpacity(0f, held = false))
        assertEquals(1f, headTallness(0f))
        val steps = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        assertTrue(steps.map { needleOpacity(it, held = false) }.zipWithNext().all { (a, b) -> b < a })
        assertTrue(steps.map(::headTallness).zipWithNext().all { (a, b) -> b < a })
        // Broken up: the line is all but gone, the peak still stands out of the glow.
        assertTrue(needleOpacity(1f, held = false) < 0.1f)
        assertTrue(headTallness(1f) > 0.6f)
        // A reading that is only held is fainter than the same reading live.
        assertTrue(needleOpacity(0f, held = true) < needleOpacity(0f, held = false))
    }
}
