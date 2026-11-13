package io.github.alfonslm.tuner.app

import io.github.alfonslm.tuner.app.ui.headTallness
import io.github.alfonslm.tuner.app.ui.needleOpacity
import io.github.alfonslm.tuner.app.ui.JerkLimited
import io.github.alfonslm.tuner.app.ui.SCALE_JERK
import io.github.alfonslm.tuner.app.ui.ScaleFollower
import io.github.alfonslm.tuner.app.ui.ScaleWindow
import io.github.alfonslm.tuner.app.ui.scaleWindow
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FRAME = 1024.0 / 48000

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
    fun `the scale's movement is limited in jerk, not in time`() {
        val frame = 1f / 60
        /** Seconds until the value has arrived at [goal] from 0, with the furthest it went beyond and the largest jerk. */
        fun travel(goal: Float): Triple<Float, Float, Float> {
            val motion = JerkLimited(0f)
            var time = 0f
            var beyond = 0f
            var jerk = 0f
            var speedBefore = 0f
            var accelerationBefore = 0f
            var positionBefore = 0f
            var arrived = Float.NaN
            while (time < 6f) {
                val position = motion.step(goal, frame)
                time += frame
                val speed = (position - positionBefore) / frame
                val acceleration = (speed - speedBefore) / frame
                // Coming to rest on the goal is a last nudge of under a hundredth of a cent, not a jerk.
                if (!motion.atRestOn(goal)) jerk = maxOf(jerk, abs(acceleration - accelerationBefore) / frame)
                beyond = maxOf(beyond, (position - goal) * if (goal > 0) 1 else -1)
                if (arrived.isNaN() && abs(position - goal) <= 0.02f * abs(goal)) arrived = time
                positionBefore = position
                speedBefore = speed
                accelerationBefore = acceleration
            }
            assertTrue(motion.atRestOn(goal), "not at rest on $goal after 6 s")
            return Triple(arrived, beyond, jerk)
        }
        val distances = listOf(1f, 6f, 23f, 73f, 160f)
        val journeys = distances.map(::travel)
        // No fixed time: a short way is quick and a long way takes longer...
        val times = journeys.map { it.first }
        assertTrue(times.zipWithNext().all { (a, b) -> b > a }, "times $times")
        assertTrue(times.first() < 0.35f && times.last() in 0.8f..1.6f, "times $times")
        // ...none of it beyond the goal...
        assertTrue(journeys.all { it.second <= 0.05f }, "beyond ${journeys.map { it.second }}")
        // ...and none of it jerked. (Seen at frame rate the steps add up to slightly over the limit.)
        assertTrue(journeys.all { it.third <= 1.3f * SCALE_JERK }, "jerk ${journeys.map { it.third }}")
        assertEquals(travel(-73f).first, travel(73f).first)

        // A change of mind on the way: off towards +80, then told -40 after a quarter of a second.
        val motion = JerkLimited(0f)
        repeat(15) { motion.step(80f, frame) }
        var furthest = motion.position
        repeat(240) { furthest = maxOf(furthest, motion.step(-40f, frame)) }
        assertTrue(motion.atRestOn(-40f))
        assertTrue(furthest < 40f, "went on to $furthest before turning")
    }

    @Test
    fun `the scale does not move for noise`() {
        // A room with something else going on: bursts of a few stray readings anywhere within
        // three semitones, some of them looking perfectly clear, with pauses in between.
        val random = java.util.Random(11)
        val follower = ScaleFollower()
        var time = 0.0
        repeat(400) {
            val cents = -300f + 600f * random.nextFloat()
            val unclear = if (random.nextInt(3) == 0) 0.1f else 0.9f
            repeat(2 + random.nextInt(9)) {
                time += FRAME
                assertEquals(ScaleWindow(), follower.follow(cents + random.nextFloat(), unclear, time), "moved at $time for $cents")
            }
            repeat(random.nextInt(12)) {
                time += FRAME
                assertEquals(ScaleWindow(), follower.follow(null, 0f, time))
            }
        }
    }

    @Test
    fun `the scale follows a string that is steadily far out, and one being tuned`() {
        val follower = ScaleFollower()
        var time = 0.0
        fun hold(cents: Float, seconds: Double, unclear: Float = 0.1f): ScaleWindow {
            var window = follower.window
            repeat((seconds / FRAME).toInt()) {
                time += FRAME
                window = follower.follow(cents, unclear, time)
            }
            return window
        }
        // A string 57 cents flat: not at once...
        assertEquals(ScaleWindow(), hold(-57f, 0.3))
        // ...but soon.
        assertEquals(scaleWindow(-57f), hold(-57f, 0.2))
        // A stray reading or two elsewhere do not unsettle it.
        assertEquals(scaleWindow(-57f), hold(140f, 0.1))
        assertEquals(scaleWindow(-57f), hold(-57f, 0.1))
        hold(-57f, 0.5)
        // However long an unclear reading says otherwise, the scale stays.
        assertEquals(scaleWindow(-57f), hold(120f, 2.0, unclear = 0.9f))
        // The string again, and tuned up a little at a time: the scale goes along without waiting.
        hold(-57f, 0.5)
        var cents = -57f
        while (cents < -20f) {
            cents += 0.5f
            time += FRAME
            assertEquals(scaleWindow(cents), follower.follow(cents, 0.1f, time), "at $cents")
        }
        // Another string, in tune: back to the middle, after the same short wait.
        assertEquals(scaleWindow(-20f), hold(1f, 0.3))
        assertEquals(ScaleWindow(), hold(1f, 0.2))
        // A pause in the readings starts the wait over.
        hold(90f, 0.3)
        time += 0.5
        assertEquals(ScaleWindow(), hold(90f, 0.3))
        assertEquals(scaleWindow(90f), hold(90f, 0.2))
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
