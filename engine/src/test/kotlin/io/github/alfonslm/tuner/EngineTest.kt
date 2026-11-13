package io.github.alfonslm.tuner

import io.github.alfonslm.tuner.engine.PitchSource
import io.github.alfonslm.tuner.engine.SmartFilterMode
import io.github.alfonslm.tuner.engine.TunerConfig
import io.github.alfonslm.tuner.engine.TunerEngine
import io.github.alfonslm.tuner.engine.TunerMode
import io.github.alfonslm.tuner.engine.TunerReading
import kotlin.math.PI
import kotlin.math.abs
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
    fun `reports how far the overtones disagree, in both modes`() {
        val clean = run(engine(), pluckedString(1.5, detune(A2, -9.0)))
        val searching = clean.first { it.voiced }
        assertEquals(PitchSource.WIDE, searching.source)
        assertTrue(searching.spreadCents!! < 1, "wide: ${searching.spreadCents}")
        assertTrue(clean.between(1.0, 1.5).all { it.source == PitchSource.FOCUSED && it.spreadCents!! < 0.5 })

        // A much stiffer string than the guitar preset expects: visibly wider, and widest
        // early on, while the upper partials that disagree most are still ringing.
        val stiff = run(engine(), pluckedString(1.5, detune(A2, -9.0), inharmonicity = 6e-4))
        assertTrue(stiff.between(0.5, 1.0).all { it.voiced && it.spreadCents!! > 3 })
        assertTrue(stiff.zip(clean).filter { (a, _) -> a.time >= 0.5 }.all { (a, b) -> a.spreadCents!! > 4 * b.spreadCents!! })
        assertTrue(stiff.last().spreadCents!! < stiff.first { it.time >= 0.5 }.spreadCents!!)

        assertTrue(run(engine(), silence(0.5)).all { it.spreadCents == null })
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
    fun `keeps reading in noise where wide-band detection gives up`() {
        val signal = mix(pluckedString(4.0, detune(G3, -6.0), amplitude = 0.25, decay = 1.2), whiteNoise(4.0, 0.02))
        fun usable(mode: SmartFilterMode) = run(engine(guitar.copy(smartFilter = mode)), signal)
            .between(1.5, 4.0)
            .count { it.voiced && abs(it.cents!! + 6) < 3 }
        val withFilter = usable(SmartFilterMode.AUTO)
        val without = usable(SmartFilterMode.OFF)
        assertTrue(withFilter > 60, "with filter: $withFilter")
        assertTrue(withFilter > 2 * without, "with $withFilter, without $without")
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
