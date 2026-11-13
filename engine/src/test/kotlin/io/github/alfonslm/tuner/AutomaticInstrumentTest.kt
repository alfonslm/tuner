package io.github.alfonslm.tuner

import io.github.alfonslm.tuner.engine.PitchSource
import io.github.alfonslm.tuner.engine.SmartFilterMode
import io.github.alfonslm.tuner.engine.TunerConfig
import io.github.alfonslm.tuner.engine.TunerEngine
import io.github.alfonslm.tuner.engine.TunerReading
import io.github.alfonslm.tuner.music.AUTO_INSTRUMENT_ID
import io.github.alfonslm.tuner.music.DEFAULT_TUNINGS
import io.github.alfonslm.tuner.music.InstrumentPreset
import io.github.alfonslm.tuner.music.STANDARD_TUNINGS
import io.github.alfonslm.tuner.dsp.HarmonicModel
import io.github.alfonslm.tuner.music.InstrumentIdentifier
import io.github.alfonslm.tuner.music.midiToFrequency
import io.github.alfonslm.tuner.music.parseNote
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BLOCK = 1024

private fun hz(note: String) = midiToFrequency(parseNote(note).toDouble())

class InstrumentIdentifierTest {
    private val stringed = DEFAULT_TUNINGS

    private fun identify(vararg pitches: Double): String? {
        val identifier = InstrumentIdentifier()
        return pitches.map { identifier.observe(it)?.id }.last()
    }

    @Test
    fun `any two strings of an instrument identify it, unless another instrument has the same two`() {
        for (instrument in stringed) {
            val strings = instrument.strings!!
            for (first in strings) for (second in strings) {
                if (first == second) continue
                val shared = stringed.any { it != instrument && first in it.strings!! && second in it.strings!! }
                val expected = if (shared) null else instrument.id
                assertEquals(expected, identify(hz(first), hz(second)), "${instrument.name}: $first then $second")
            }
        }
    }

    @Test
    fun `a single string is never enough`() {
        for (instrument in stringed) for (string in instrument.strings!!) {
            assertNull(identify(hz(string), detune(hz(string), 20.0), detune(hz(string), -35.0)), "${instrument.name} $string")
        }
    }

    @Test
    fun `strings well out of tune still identify the instrument`() {
        assertEquals("guitar", identify(detune(hz("E2"), -70.0), detune(hz("A2"), 45.0)))
        assertEquals("bass", identify(detune(hz("A1"), 60.0), detune(hz("D2"), -50.0)))
        assertEquals("cello", identify(detune(hz("G2"), -40.0), detune(hz("D3"), -80.0)))
        // The whole instrument a third of a semitone flat.
        assertEquals("guitar", identify(detune(hz("E2"), -30.0), detune(hz("A2"), -30.0)))
    }

    @Test
    fun `an instrument tuned a full semitone away from standard is not guessed at`() {
        // E♭2 and A♭2 are as close to a bass's D2 and G2 as to a guitar's E2 and A2;
        // C#3 and F#3 as close to a viola's C3 and G3 as to a guitar's D3 and G3.
        assertNull(identify(detune(hz("E2"), -100.0), detune(hz("A2"), -100.0)))
        assertNull(identify(detune(hz("D3"), -100.0), detune(hz("G3"), -100.0)))
    }

    @Test
    fun `violin and viola are told apart by the string only one of them has`() {
        assertNull(identify(hz("G3"), hz("D4"), hz("A4")))
        assertEquals("violin", identify(hz("G3"), hz("D4"), hz("A4"), hz("E5")))
        assertEquals("viola", identify(hz("A4"), hz("D4"), hz("G3"), hz("C3")))
    }

    @Test
    fun `pairs that fit two instruments about equally well stay undecided`() {
        // Halfway between a guitar's B3 and a ukulele's C4, followed by the E4 both have.
        assertNull(identify(detune(hz("B3"), 50.0), hz("E4")))
        // Notes too far from any instrument's strings.
        assertNull(identify(hz("F#2"), hz("B2")))
    }

    @Test
    fun `a misheard note is forgotten once two good ones follow`() {
        assertEquals("guitar", identify(hz("F#5"), hz("E2"), hz("A2")))
    }

    @Test
    fun `chooses only among the tunings it is given`() {
        fun tuning(id: String) = STANDARD_TUNINGS.first { it.id == id }
        fun identify(tunings: List<InstrumentPreset>, vararg notes: String): String? {
            val identifier = InstrumentIdentifier(tunings)
            return notes.map { identifier.observe(hz(it))?.id }.last()
        }

        // With the guitar switched off, its strings are nobody's.
        assertNull(identify(listOf(tuning("bass"), tuning("ukulele")), "E2", "A2"))

        // Standard and Drop D differ in one string, so only a pair including it tells them apart.
        val guitars = listOf(tuning("guitar"), tuning("guitar-drop-d"))
        assertNull(identify(guitars, "A2", "D3"))
        assertEquals("guitar", identify(guitars, "E2", "A2"))
        assertEquals("guitar-drop-d", identify(guitars, "D2", "A2"))
        // With Drop D alone in use, any pair will do.
        assertEquals("guitar-drop-d", identify(listOf(tuning("guitar-drop-d")), "A2", "D3"))

        // A custom tuning is a candidate like any other.
        val custom = InstrumentPreset("custom-1", "Cigar box", listOf("G2", "D3", "G3"), HarmonicModel(8, 5e-5))
        assertEquals("custom-1", identify(listOf(tuning("bass"), custom), "G2", "D3"))
        assertNull(identify(emptyList(), "E2", "A2"))
    }

    @Test
    fun `follows the reference pitch`() {
        val identifier = InstrumentIdentifier()
        assertNull(identifier.observe(midiToFrequency(40.0, 415.0), a4 = 415.0))
        assertEquals("guitar", identifier.observe(midiToFrequency(45.0, 415.0), a4 = 415.0)?.id)
    }
}

class AutomaticInstrumentTest {
    private fun engine(config: TunerConfig = TunerConfig()) = TunerEngine(SAMPLE_RATE, BLOCK, config)

    private fun run(engine: TunerEngine, signal: FloatArray): List<TunerReading> =
        (0..signal.size - BLOCK step BLOCK).map { engine.process(signal.copyOfRange(it, it + BLOCK)) }

    private fun List<TunerReading>.between(from: Double, to: Double) = filter { it.time >= from && it.time < to }

    private fun strings(vararg notes: Pair<String, Double>, seconds: Double = 1.5) =
        concat(*notes.map { (note, offset) -> pluckedString(seconds, detune(hz(note), offset)) }.toTypedArray())

    @Test
    fun `automatic is the default instrument`() {
        assertEquals(AUTO_INSTRUMENT_ID, TunerConfig().instrumentId)
        assertNull(engine().targets.list)
    }

    @Test
    fun `tunes chromatically at first, then recognises a guitar from its second string`() {
        val readings = run(engine(), strings("E2" to -15.0, "A2" to 10.0, "D3" to -6.0))

        // First string: not enough to go on, but the tuner is already useful.
        val first = readings.between(1.0, 1.5)
        assertTrue(first.all { it.instrumentId == AUTO_INSTRUMENT_ID })
        assertTrue(first.all { it.target?.id == "m40" && it.source == PitchSource.FOCUSED && abs(it.cents!! + 15) < 0.5 })

        // Second string: guitar, within half a second of the pluck, and the lock carries straight over.
        val identifiedAt = assertNotNull(readings.firstOrNull { it.instrumentId == "guitar" }).time
        assertTrue(identifiedAt in 1.5..2.0, "identified at $identifiedAt")
        assertTrue(readings.between(identifiedAt + 0.1, 3.0).all { it.lock?.target?.id == "s1:A2" })
        assertTrue(readings.between(2.5, 3.0).all { it.source == PitchSource.FOCUSED && abs(it.cents!! - 10) < 0.5 })

        // From here on the targets are the guitar's strings.
        assertTrue(readings.between(4.0, 4.5).all { it.instrumentId == "guitar" && it.target?.id == "s2:D3" && abs(it.cents!! + 6) < 0.5 })
    }

    @Test
    fun `recognises the other instruments, including a bass`() {
        val cases = mapOf(
            "bass" to strings("E1" to 5.0, "A1" to -8.0, seconds = 2.0),
            "ukulele" to strings("C4" to -12.0, "E4" to 6.0),
            "violin" to strings("A4" to 4.0, "E5" to -9.0),
            "viola" to strings("C3" to 7.0, "G3" to -5.0),
            "cello" to strings("C2" to -6.0, "G2" to 9.0, seconds = 2.0),
        )
        for ((instrument, signal) in cases) {
            assertEquals(instrument, run(engine(), signal).last().instrumentId, instrument)
        }
    }

    @Test
    fun `once known, a string far out of tune is read against the right string`() {
        // D3 a whole tone flat is the note C3; a chromatic tuner would call it in tune.
        val readings = run(engine(), strings("E2" to 0.0, "A2" to 0.0, "D3" to -200.0))
        assertTrue(readings.between(4.0, 4.5).all { it.target?.label == "D3" && abs(it.cents!! + 200) < 1.5 })
    }

    @Test
    fun `works with the smart filter off`() {
        val readings = run(engine(TunerConfig(smartFilter = SmartFilterMode.OFF)), strings("G3" to 0.0, "B3" to 0.0))
        assertEquals("guitar", readings.last().instrumentId)
        assertTrue(readings.all { it.lock == null })
    }

    @Test
    fun `keeps the instrument through settings changes, forgets it when the instrument setting changes`() {
        val engine = engine()
        run(engine, strings("E2" to 0.0, "A2" to 0.0))

        // Pinning one of the strings that have just appeared is a settings change like any other.
        engine.configure(engine.config.copy(pinnedTargetId = "s3:G3"))
        val pinned = run(engine, silence(0.2)).last()
        assertEquals("guitar", pinned.instrumentId)
        assertEquals("G3", pinned.lock?.target?.label)

        engine.configure(engine.config.copy(instrumentId = "chromatic", pinnedTargetId = null))
        engine.configure(engine.config.copy(instrumentId = AUTO_INSTRUMENT_ID))
        assertEquals(AUTO_INSTRUMENT_ID, run(engine, silence(0.2)).last().instrumentId)
    }

    @Test
    fun `automatic mode reaches as low as the tunings in use, and recognises a custom one`() {
        val fiveString = STANDARD_TUNINGS.first { it.id == "bass-5" }
        val low = engine(TunerConfig(tunings = listOf(fiveString)))
        val readings = run(low, strings("B0" to 6.0, "E1" to -4.0, seconds = 2.5))
        assertEquals("bass-5", readings.last().instrumentId)
        assertTrue(readings.between(4.5, 5.0).all { it.target?.id == "s1:E1" && abs(it.cents!! + 4) < 1 })

        val cigarBox = InstrumentPreset("custom-1", "Cigar box", listOf("G2", "D3", "G3"), HarmonicModel(8, 5e-5))
        val custom = run(engine(TunerConfig(tunings = DEFAULT_TUNINGS + cigarBox)), strings("D3" to 3.0, "G3" to -7.0))
        // D3 and G3 are on a guitar too: undecided, and still tuned chromatically.
        assertEquals(AUTO_INSTRUMENT_ID, custom.last().instrumentId)
        val alone = run(engine(TunerConfig(tunings = listOf(cigarBox))), strings("D3" to 3.0, "G3" to -7.0))
        assertEquals("custom-1", alone.last().instrumentId)
        assertEquals("s2:G3", alone.last().target?.id)
    }

    @Test
    fun `changing the tunings in use starts the identification over, and an instrument no longer in use means automatic`() {
        val engine = engine()
        assertEquals("guitar", run(engine, strings("E2" to 0.0, "A2" to 0.0)).last().instrumentId)
        engine.configure(engine.config.copy(tunings = DEFAULT_TUNINGS.filter { it.id != "guitar" }))
        assertEquals(AUTO_INSTRUMENT_ID, run(engine, silence(0.2)).last().instrumentId)

        val fixed = engine(TunerConfig(instrumentId = "guitar"))
        fixed.configure(fixed.config.copy(tunings = DEFAULT_TUNINGS.filter { it.id != "guitar" }))
        assertEquals(AUTO_INSTRUMENT_ID, fixed.config.instrumentId)
    }

    @Test
    fun `reset starts the identification over`() {
        val engine = engine()
        assertEquals("guitar", run(engine, strings("E2" to 0.0, "A2" to 0.0)).last().instrumentId)
        engine.reset()
        assertEquals(AUTO_INSTRUMENT_ID, run(engine, pluckedString(1.0, hz("A2"))).last().instrumentId)
    }
}
