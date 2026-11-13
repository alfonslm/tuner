package io.github.alfonslm.tuner.app

import io.github.alfonslm.tuner.app.ui.ScaleFollower
import io.github.alfonslm.tuner.app.ui.ScaleWindow
import io.github.alfonslm.tuner.app.ui.unclarity
import io.github.alfonslm.tuner.engine.TunerConfig
import io.github.alfonslm.tuner.engine.TunerEngine
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scale's window, decided from what the real engine makes of sound: it
 * must stand still when there is only noise, and still go to a string that is
 * far out.
 */
class ScaleFollowingTest {
    private val rate = 48_000
    private val block = 1024

    /** Every window the screen would have shown, one per block, with the number of readings that came out. */
    private fun windows(signal: FloatArray, instrument: String): Pair<List<ScaleWindow>, Int> {
        val engine = TunerEngine(rate, block, TunerConfig(instrumentId = instrument))
        val follower = ScaleFollower()
        var readings = 0
        val windows = (0..signal.size - block step block).map { start ->
            val reading = engine.process(signal.copyOfRange(start, start + block))
            if (reading.voiced) readings++
            follower.follow(
                cents = reading.cents?.toFloat(),
                unclear = unclarity(reading.spreadCents?.toFloat(), reading.confidence.toFloat()),
                time = reading.time,
            )
        }
        return windows to readings
    }

    /**
     * Something like a voice from a television: syllables of 80 to 300 ms with a
     * gliding pitch between 90 and 260 Hz and a few strong overtones, pauses,
     * hiss throughout and a click now and then.
     */
    private fun chatter(seconds: Double, seed: Long): FloatArray {
        val random = java.util.Random(seed)
        val out = FloatArray((seconds * rate).toInt()) { (0.004 * random.nextGaussian()).toFloat() }
        var at = 0
        while (at < out.size) {
            val length = ((0.08 + 0.22 * random.nextDouble()) * rate).toInt()
            val from = 90 + 170 * random.nextDouble()
            val to = from * (0.8 + 0.4 * random.nextDouble())
            val weights = DoubleArray(10) { 1.0 / (it + 1) * (0.3 + random.nextDouble()) }
            var phase = 0.0
            for (i in 0 until length) {
                if (at + i >= out.size) break
                val place = i.toDouble() / length
                phase += 2 * PI * (from + (to - from) * place) / rate
                var sample = 0.0
                for (k in 1..10) sample += weights[k - 1] * sin(k * phase)
                val envelope = sin(PI * place)
                out[at + i] += (0.08 * envelope * sample).toFloat()
            }
            at += length + (random.nextDouble() * 0.25 * rate).toInt()
            if (random.nextInt(4) == 0 && at < out.size - 400) {
                for (i in 0 until 400) out[at + i] += (0.3 * exp(-i / 60.0) * random.nextGaussian()).toFloat()
            }
        }
        return out
    }

    @Test
    fun `chatter in the room does not move the scale`() {
        for (instrument in listOf("guitar", "auto", "chromatic")) {
            for (seed in 1L..4L) {
                val (windows, readings) = windows(chatter(20.0, seed), instrument)
                // (By less than a cent does not count: nobody sees that.)
                val moved = windows.count { abs(it.centre) > 1f || it.range != ScaleWindow().range }
                println("SCALE chatter $instrument seed $seed: $readings readings in ${windows.size} blocks, scale away from its place in $moved")
                assertTrue(readings > 30, "$instrument, seed $seed: only $readings readings, not much of a test")
                assertEquals(0, moved, "$instrument, seed $seed: the scale moved")
            }
        }
    }

    private fun recording(name: String): FloatArray {
        val directory = System.getenv("TUNER_RECORDING_SET")
        assumeTrue("recordings not decoded: run ./gradlew :engine:recordingsTest first", directory != null && File(directory, "$name.wav").exists())
        val bytes = File(directory, "$name.wav").readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var position = 12
        while (position + 8 <= bytes.size) {
            val size = buffer.getInt(position + 4)
            if (String(bytes, position, 4) == "data") {
                val start = position + 8
                return FloatArray(minOf(size, bytes.size - start) / 4) { buffer.getFloat(start + 4 * it) }
            }
            position += 8 + size + (size and 1)
        }
        error("no data chunk in $name")
    }

    @Test
    fun `on the recorded guitar the scale stays for strings near their note and goes to the one that is far out`() {
        for (string in listOf("E4", "B3", "D3", "A2", "E2")) {
            val (windows, _) = windows(recording("open-$string"), "guitar")
            assertEquals(0, windows.count { it != ScaleWindow() }, "open $string: the scale moved")
        }
        // The G string was 56 cents flat: the scale goes there after the first pluck and stays,
        // through four plucks and the hand stopping the string in between.
        val (windows, _) = windows(recording("open-G3"), "guitar")
        val settled = windows.drop((1.5 * rate / block).toInt())
        val moves = windows.zipWithNext().count { (a, b) -> abs(a.centre - b.centre) > 8 || abs(a.range - b.range) > 8 }
        println("SCALE open G3: centre ${settled.minOf { it.centre }}..${settled.maxOf { it.centre }}, range ${settled.minOf { it.range }}..${settled.maxOf { it.range }}, $moves moves")
        assertTrue(settled.all { it.centre in -25f..-10f && it.range == 50f }, "centres ${settled.map { it.centre }.distinct().take(12)}")
        assertTrue(moves <= 1, "$moves moves")
    }
}
