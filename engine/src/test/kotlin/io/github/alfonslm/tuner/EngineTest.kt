package io.github.alfonslm.tuner

import io.github.alfonslm.tuner.engine.PitchSource
import io.github.alfonslm.tuner.engine.SmartFilterMode
import io.github.alfonslm.tuner.engine.TunerConfig
import io.github.alfonslm.tuner.engine.TunerEngine
import io.github.alfonslm.tuner.engine.TunerMode
import io.github.alfonslm.tuner.engine.TunerReading
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val BLOCK = 1024
private const val E2 = 82.41
private const val A2 = 110.0
private const val D3 = 146.83
private const val G3 = 196.0
private const val E4 = 329.63

class EngineTest {
    private val guitar = TunerConfig(instrumentId = "guitar")

    private fun engine(config: TunerConfig = guitar) = TunerEngine(SAMPLE_RATE, BLOCK, config)

    /** Feeds a signal through the engine block by block, as the microphone thread would. */
    private fun run(engine: TunerEngine, signal: FloatArray): List<TunerReading> =
        (0..signal.size - BLOCK step BLOCK).map { engine.process(signal.copyOfRange(it, it + BLOCK)) }

    private fun List<TunerReading>.between(from: Double, to: Double) = filter { it.time >= from && it.time < to }

    private fun List<TunerReading>.firstLock(label: String? = null) =
        firstOrNull { it.lock != null && (label == null || it.lock!!.target.label == label) }?.time

    private fun pinned(label: String): TunerEngine {
        val engine = engine()
        val target = engine.targets.list!!.first { it.label == label }
        engine.configure(engine.config.copy(pinnedTargetId = target.id))
        return engine
    }

    // --- acquisition and lock ---

    @Test
    fun `identifies each string, locks onto it and reads its offset`() {
        val offset = -12.5
        for ((label, f0) in GUITAR_STRINGS) {
            val readings = run(engine(), pluckedString(1.5, detune(f0, offset)))
            val lockedAt = assertNotNull(readings.firstLock(label), label)
            assertTrue(lockedAt < 0.5, "$label locked at $lockedAt")
            for (reading in readings.between(1.0, 1.5)) {
                assertEquals(TunerMode.LOCKED, reading.mode)
                assertEquals(PitchSource.FOCUSED, reading.source)
                assertEquals(label, reading.target?.label)
                assertTrue(abs(reading.cents!! - offset) < 0.3, "$label: ${reading.cents}")
            }
        }
    }

    @Test
    fun `shows the wide-band reading until the note has been stable`() {
        val readings = run(engine(), pluckedString(1.0, A2))
        val firstVoiced = readings.first { it.voiced }
        assertEquals(TunerMode.SEARCH, firstVoiced.mode)
        assertEquals(PitchSource.WIDE, firstVoiced.source)
        // stableMs (180) of agreement must come first.
        assertTrue(readings.firstLock()!! - firstVoiced.time >= 0.12)
    }

    @Test
    fun `stays wide-band when the smart filter is switched off`() {
        val readings = run(engine(guitar.copy(smartFilter = SmartFilterMode.OFF)), pluckedString(1.0, A2))
        assertTrue(readings.all { it.mode == TunerMode.SEARCH && it.lock == null })
        assertTrue(readings.between(0.5, 1.0).all { it.source == PitchSource.WIDE && it.target?.label == "A2" })
    }

    @Test
    fun `narrows the comb as the string comes into tune`() {
        val near = run(engine(), pluckedString(1.5, detune(A2, 1.0))).last()
        assertTrue(near.lock!!.widthCents < 40)
        assertTrue(near.inTune)

        val far = run(engine(), pluckedString(1.5, detune(A2, -120.0))).last()
        assertTrue(far.lock!!.widthCents > 150)
        assertTrue(abs(far.cents!! + 120) < 0.5)
        assertTrue(!far.inTune)
    }

    @Test
    fun `a reading says how old its audio is, and dated that way it follows a moving pitch`() {
        // An A string tuned up from -30 to 0 cents between t = 1 s and t = 2 s, steady before and after.
        fun truth(t: Double) = -30 + 30 * (t - 1).coerceIn(0.0, 1.0)
        val signal = FloatArray(3 * SAMPLE_RATE)
        var phase = 0.0
        for (i in signal.indices) {
            phase += 2 * PI * detune(A2, truth(i.toDouble() / SAMPLE_RATE)) / SAMPLE_RATE
            signal[i] = (0.2 * (sin(phase) + 0.5 * sin(2 * phase) + 0.3 * sin(3 * phase) + 0.2 * sin(5 * phase))).toFloat()
        }
        val readings = run(engine(), signal)
        val moving = readings.between(1.3, 1.9)
        assertTrue(moving.all { it.source == PitchSource.FOCUSED })

        // The needle trails a steadily moving pitch, by a bounded amount: a few cents, however long it moves.
        assertTrue(moving.all { it.delay in 0.09..0.13 }, "delays ${moving.map { it.delay }.distinct()}")
        assertTrue(moving.all { truth(it.time) - it.cents!! in 3.0..7.5 }, "lag ${moving.map { truth(it.time) - it.cents!! }}")
        // Dated by its delay, the reading is within half a stripe's width of the pitch at that moment.
        assertTrue(moving.all { abs(truth(it.time - it.delay) - it.cents!!) < 4 }, "${moving.map { truth(it.time - it.delay) - it.cents!! }}")

        // Once the pitch comes to rest the needle closes the last few cents gently: within half a second.
        assertTrue(readings.between(2.5, 3.0).all { abs(it.cents!!) < 0.5 }, "${readings.between(2.5, 3.0).maxOf { abs(it.cents!!) }}")
        assertTrue(readings.filter { !it.voiced }.all { it.delay == 0.0 })
    }

    @Test
    fun `holds the needle still when another string's overtone beats against the note`() {
        // A lower string that is 20 cents out, set ringing by the pluck: its overtone sounds next to
        // the high E and the two beat about three times a second. The estimate swings with the beat.
        val seconds = 3.0
        val beating = FloatArray((seconds * SAMPLE_RATE).toInt()) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            (0.05 * (1 - kotlin.math.exp(-t / 0.15)) * kotlin.math.exp(-t / 3.0) * sin(2 * PI * detune(E4, 20.0) * t)).toFloat()
        }
        val signal = mix(pluckedString(seconds, E4, inharmonicity = 2e-5, decay = 2.0), beating)
        fun swing(config: TunerConfig): Double {
            val cents = run(engine(config), signal).between(0.8, 2.8).map { it.cents!! }
            return cents.max() - cents.min()
        }
        // What the estimate does, shown by a needle that believes every reading...
        val raw = swing(guitar.copy(display = guitar.display.copy(steadySmoothing = 0.6)))
        // ...and what the needle does.
        val steadied = swing(guitar)
        assertTrue(raw > 7, "estimate swings by $raw")
        assertTrue(steadied < 0.6 * raw, "needle swings by $steadied, estimate by $raw")
    }

    @Test
    fun `goes straight to a newly plucked note and shows nothing of its attack`() {
        val signal = mix(
            whiteNoise(4.0, 0.003),
            concat(pluckedString(2.0, detune(A2, -14.0)), pluckedString(2.0, detune(A2, 9.0), seed = 2)),
        )
        val readings = run(engine(), signal)
        // Nothing for the first moment after the second pluck, during which the first reading
        // stays on screen; then the new value at once, not a slow slide from the old one.
        assertTrue(readings.between(2.07, 2.2).none { it.voiced })
        val after = readings.between(2.07, 2.5).filter { it.voiced }
        assertTrue(after.first().time < 2.3, "first reading at ${after.first().time}")
        assertTrue(after.all { abs(it.cents!! - 9) < 0.5 }, "readings after the pluck: ${after.map { it.cents }}")
        // The frame or two before the pluck is noticed may show a trace of it, no more.
        assertTrue(readings.between(2.0, 2.07).all { !it.voiced || abs(it.cents!! + 14) < 3 })
        assertTrue(readings.between(2.4, 4.0).all { abs(it.cents!! - 9) < 0.5 })
    }

    // --- switching strings ---

    @Test
    fun `moves the lock when another string is plucked`() {
        val readings = run(engine(), concat(pluckedString(1.2, E2), pluckedString(1.2, detune(A2, 8.0))))
        assertTrue(readings.between(0.8, 1.2).all { it.lock?.target?.label == "E2" })
        val relocked = assertNotNull(readings.firstOrNull { it.time > 1.2 && it.lock?.target?.label == "A2" })
        assertTrue(relocked.time - 1.2 < 0.5)
        // No frame may ever report the new note against the old target.
        for (r in readings.between(1.2, 2.4)) {
            if (r.voiced) assertEquals(if (r.time < relocked.time) "E2" else "A2", r.target!!.label)
        }
        assertTrue(readings.between(2.0, 2.4).all { it.source == PitchSource.FOCUSED && abs(it.cents!! - 8) < 0.5 })
    }

    @Test
    fun `tells E2 from E4 although every partial of E4 fits the E2 comb`() {
        val up = run(engine(), concat(pluckedString(1.0, E2), pluckedString(1.0, E4)))
        assertTrue(up.between(1.6, 2.0).all { it.lock?.target?.label == "E4" && it.source == PitchSource.FOCUSED })

        val down = run(engine(), concat(pluckedString(1.0, E4), pluckedString(1.0, E2)))
        assertTrue(down.between(1.6, 2.0).all { it.lock?.target?.label == "E2" && it.source == PitchSource.FOCUSED })
    }

    @Test
    fun `follows a string tuned through a semitone in chromatic mode`() {
        // Glide from A2 -20c to A2 +90c (= A#2 -10c) over three seconds.
        val signal = FloatArray(3 * SAMPLE_RATE)
        var phase = 0.0
        for (i in signal.indices) {
            phase += 2 * PI * detune(A2, -20 + 110.0 * i / signal.size) / SAMPLE_RATE
            signal[i] = (0.2 * (sin(phase) + 0.5 * sin(2 * phase) + 0.3 * sin(3 * phase))).toFloat()
        }
        val readings = run(engine(TunerConfig(instrumentId = "chromatic")), signal)
        assertTrue(readings.between(0.5, 1.0).all { it.lock?.target?.label == "A2" })
        assertTrue(readings.between(2.9, 3.0).all { it.lock?.target?.label == "A#2" && it.source == PitchSource.FOCUSED })
        // The target may change once, not flap back and forth around the midpoint.
        val labels = readings.mapNotNull { it.lock?.target?.label }
        assertEquals(1, labels.zipWithNext().count { (a, b) -> a != b })
    }

    // --- robustness ---

    @Test
    fun `keeps reading in noise where wide-band detection gives up, and shows nothing rather than something wrong`() {
        val signal = mix(pluckedString(4.0, detune(G3, -6.0), amplitude = 0.25, decay = 1.2), whiteNoise(4.0, 0.02))
        fun readings(mode: SmartFilterMode) = run(engine(guitar.copy(smartFilter = mode)), signal).between(1.5, 4.0).filter { it.voiced }
        val withFilter = readings(SmartFilterMode.AUTO)
        val without = readings(SmartFilterMode.OFF)

        // With the filter: a second and more of readings as the note sinks into the noise, every one of them right.
        assertTrue(withFilter.size > 40, "with filter: ${withFilter.size}")
        assertTrue(withFilter.all { abs(it.cents!! + 6) < 3 }, "worst ${withFilter.maxOf { abs(it.cents!! + 6) }}")
        // Without it: far fewer that are right.
        assertTrue(withFilter.size > 2 * without.count { abs(it.cents!! + 6) < 3 })
    }

    @Test
    fun `tracks a low string whose fundamental the microphone does not pick up`() {
        val readings = run(engine(), pluckedString(1.5, detune(E2, 4.0), fundamentalGain = 0.02))
        assertTrue(readings.between(1.0, 1.5).all { it.lock?.target?.label == "E2" && abs(it.cents!! - 4) < 0.5 })
    }

    @Test
    fun `reports nothing for silence or noise`() {
        for (signal in listOf(silence(2.0), whiteNoise(2.0, 0.05))) {
            assertTrue(run(engine(), signal).none { it.voiced || it.lock != null })
        }
    }

    @Test
    fun `holds the lock through a pause and releases it after the hold time`() {
        val signal = mix(
            whiteNoise(6.0, 0.003),
            concat(pluckedString(1.0, A2), silence(1.0), pluckedString(1.0, detune(A2, 5.0))),
        )
        val readings = run(engine(), signal)
        // During the pause: still locked, but no needle driven by room noise.
        assertTrue(readings.between(1.3, 2.0).all { it.lock?.target?.label == "A2" && !it.voiced })
        // Re-pluck: focused readings without going back through search.
        assertTrue(
            readings.between(2.3, 3.0).all {
                it.mode == TunerMode.LOCKED && it.source == PitchSource.FOCUSED && abs(it.cents!! - 5) < 0.5
            },
        )
        // 2.5 s after the note is gone the tuner is searching again.
        assertTrue(readings.between(3.2, 5.0).any { it.mode == TunerMode.LOCKED })
        assertEquals(TunerMode.SEARCH, readings.last().mode)
    }

    // --- other strings still ringing ---

    /** `signal` placed `seconds` into a buffer of `total` seconds. */
    private fun at(total: Double, seconds: Double, signal: FloatArray): FloatArray {
        val out = FloatArray((total * SAMPLE_RATE).toInt())
        signal.copyInto(out, (seconds * SAMPLE_RATE).toInt(), 0, minOf(signal.size, out.size - (seconds * SAMPLE_RATE).toInt()))
        return out
    }

    @Test
    fun `follows the strings plucked one after another while the earlier ones ring on`() {
        // Nobody damps the strings while tuning. Each is plucked while all before it are still
        // sounding; by the last, the new string is about half of what the microphone hears.
        for (config in listOf(guitar, TunerConfig())) {
            for (gap in listOf(2.0, 1.2)) {
                val total = 0.5 + 6 * gap
                val signal = mix(
                    *GUITAR_STRINGS.mapIndexed { i, (_, f) ->
                        at(total, 0.5 + gap * i, pluckedString(total - 0.5 - gap * i, detune(f, 4.0), decay = 3.5, seed = i + 1))
                    }.toTypedArray(),
                    whiteNoise(total, 0.001),
                )
                val readings = run(engine(config), signal)
                GUITAR_STRINGS.forEachIndexed { i, (label, _) ->
                    val plucked = 0.5 + gap * i
                    val heard = readings.between(plucked + 0.45, plucked + gap)
                    val case = "${config.instrumentId}, ${gap}s apart, $label"
                    assertTrue(heard.all { it.voiced && it.target!!.label == label }, "$case: ${heard.map { it.target?.label }.distinct()}")
                    assertTrue(heard.all { abs(it.cents!! - 4) < 3.5 }, "$case: off by ${heard.maxOf { abs(it.cents!! - 4) }}")
                }
            }
        }
    }

    @Test
    fun `reads the high E over a ringing low E, A or B, which share its overtones`() {
        // E4 is the fourth partial of E2 and the third of A2: together with either, the sound
        // repeats at the lower string's period, and in one frame cannot be told from that string alone.
        for (config in listOf(guitar, TunerConfig())) {
            for ((name, other) in listOf("E2" to E2, "A2" to A2, "B3" to 246.94)) {
                for (db in listOf(-12.0, -6.0, -3.0)) {
                    val signal = mix(
                        pluckedString(2.2, other, amplitude = 0.2 * 10.0.pow(db / 20), decay = 6.0, seed = 9),
                        at(2.2, 0.8, pluckedString(1.4, detune(E4, 5.0), decay = 3.0)),
                        whiteNoise(2.2, 0.001),
                    )
                    val heard = run(engine(config), signal).between(1.3, 2.2)
                    val case = "${config.instrumentId}: E4 over $name at $db dB"
                    assertTrue(heard.all { it.voiced && it.target!!.label == "E4" }, "$case: ${heard.map { it.target?.label }.distinct()}")
                    assertTrue(heard.all { abs(it.cents!! - 5) < 1.5 }, "$case: off by ${heard.maxOf { abs(it.cents!! - 5) }}")
                }
            }
        }
    }

    @Test
    fun `goes to the lower string when that one is plucked over a ringing high E`() {
        val signal = mix(pluckedString(2.5, E4, decay = 4.0), at(2.5, 1.0, pluckedString(1.5, detune(E2, -7.0), seed = 4)))
        val readings = run(engine(), signal)
        assertTrue(readings.between(0.6, 1.0).all { it.target?.label == "E4" })
        assertTrue(readings.between(1.5, 2.5).all { it.voiced && it.target!!.label == "E2" && abs(it.cents!! + 7) < 2 })
    }

    // --- pinned target ---

    @Test
    fun `pinned - stays on the chosen string and measures far-off pitches against it`() {
        // A slack new string, 3.2 semitones flat: nearer to A2 than to D3.
        val readings = run(pinned("D3"), pluckedString(1.5, detune(D3, -320.0)))
        assertTrue(readings.all { it.lock?.target?.label == "D3" && it.lock!!.pinned })
        assertTrue(readings.between(1.0, 1.5).all { it.voiced && it.target!!.label == "D3" && abs(it.cents!! + 320) < 1 })
    }

    @Test
    fun `pinned - ignores other strings`() {
        val readings = run(pinned("E2"), pluckedString(1.5, G3))
        assertTrue(readings.all { it.lock?.target?.label == "E2" && !it.voiced })
    }

    @Test
    fun `pinned - ignores a lower string for its whole decay, including the tail where one partial is left`() {
        val signal = mix(
            pluckedString(5.0, detune(E2, -8.0), inharmonicity = 5e-5, fundamentalGain = 0.3),
            whiteNoise(5.0, 0.002),
        )
        assertTrue(run(pinned("D3"), signal).none { it.voiced })
    }

    @Test
    fun `a pin does not survive a change of instrument`() {
        val engine = pinned("D3")
        engine.configure(engine.config.copy(instrumentId = "violin"))
        assertEquals(null, engine.config.pinnedTargetId)
        assertTrue(run(engine, silence(0.2)).all { it.lock == null })
    }
}
