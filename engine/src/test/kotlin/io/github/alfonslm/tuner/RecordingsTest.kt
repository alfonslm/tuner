package io.github.alfonslm.tuner

import io.github.alfonslm.tuner.engine.TunerConfig
import io.github.alfonslm.tuner.engine.TunerEngine
import io.github.alfonslm.tuner.engine.TunerReading
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The engine against recordings of a real instrument: the files in
 * `recordings/` at the top of the repository (an acoustic guitar through a
 * laptop microphone; see the README there for what each one is).
 *
 * This is the check to run before a build is handed to anyone:
 *
 *     ./gradlew :engine:recordingsTest
 *
 * which decodes the files with ffmpeg and runs this class. As part of the
 * ordinary `test` task it is skipped, because the JVM cannot decode the files
 * itself and a missing ffmpeg should not break the build.
 *
 * What is asserted is what the engine did with these files when they were
 * last examined by hand, with a margin: which note it hears, where it reads
 * it, how steady the reading is and how sure it says it is. A failure here
 * means a real instrument is now heard differently, which is either the
 * improvement that was intended (then the figures here are updated, with the
 * reason in the commit) or a regression that the synthetic tests missed.
 */
class RecordingsTest {
    private class Heard(readings: List<TunerReading>) {
        val voiced = readings.filter { it.voiced }
        fun shareOn(labels: Set<String>) = voiced.count { it.target!!.label in labels }.toDouble() / voiced.size
        fun on(label: String) = voiced.filter { it.target!!.label == label }
        fun median(label: String) = on(label).map { it.cents!! }.sorted().let { it[it.size / 2] }
        fun shareNear(label: String, cents: Double) = on(label).let { all -> all.count { abs(it.cents!! - median(label)) <= cents }.toDouble() / all.size }
        val confidence = voiced.map { it.confidence }.sorted().let { it[it.size / 2] }
    }

    private fun hear(name: String, instrument: String): Heard {
        val directory = System.getenv("TUNER_RECORDING_SET")
        assumeTrue("recordings not decoded: run ./gradlew :engine:recordingsTest", directory != null)
        val (rate, signal) = readWav(File(directory, "$name.wav"))
        val engine = TunerEngine(rate, 1024, TunerConfig(instrumentId = instrument))
        return Heard((0..signal.size - 1024 step 1024).map { engine.process(signal.copyOfRange(it, it + 1024)) })
    }

    /** An open string, plucked several times: [cents] is where this guitar's string was when it was recorded. */
    private fun openString(label: String, cents: Double, modes: List<String>, near: Double = 0.85) {
        for (mode in modes) {
            val heard = hear("open-$label", mode)
            val case = "open $label, $mode"
            println("RECORDED %-22s readings %3d on the note %.2f median %+.1f near it %.2f confidence %.2f".format(
                case, heard.voiced.size, heard.shareOn(setOf(label)), heard.median(label), heard.shareNear(label, NEAR_CENTS), heard.confidence))
            assertTrue(heard.voiced.size >= MIN_READINGS, "$case: only ${heard.voiced.size} readings")
            // It is this string and no other: no octave, no neighbour.
            assertTrue(heard.shareOn(setOf(label)) >= 0.98, "$case: on the string in ${heard.shareOn(setOf(label))} of the readings")
            // Read where the string was...
            assertTrue(abs(heard.median(label) - cents) <= 1.0, "$case: median ${heard.median(label)}, was $cents")
            // ...steadily: no wandering off towards the strings that ring along, no reading of the attack.
            assertTrue(heard.shareNear(label, NEAR_CENTS) >= near, "$case: ${heard.shareNear(label, NEAR_CENTS)} of the readings within $NEAR_CENTS cents of the median")
            // ...and known to be one clear note.
            assertTrue(heard.confidence >= 0.93, "$case: confidence ${heard.confidence}")
        }
    }

    /** A fretted note or several, read chromatically: the notes are heard at their own octave for [share] of the time. */
    private fun fretted(number: Int, notes: Set<String>, share: Double) {
        val heard = hear("fretted-%02d".format(number), "chromatic")
        val case = "fretted %02d".format(number)
        println("RECORDED %-22s readings %3d on the notes %.2f %s confidence %.2f".format(
            case, heard.voiced.size, heard.shareOn(notes), notes.filter { heard.on(it).isNotEmpty() }.joinToString { "$it %+.1f".format(heard.median(it)) }, heard.confidence))
        assertTrue(heard.voiced.size >= MIN_READINGS, "$case: only ${heard.voiced.size} readings")
        assertTrue(heard.shareOn(notes) >= share, "$case: on $notes in ${heard.shareOn(notes)} of the readings, was at least $share")
    }

    private val everyMode = listOf("guitar", "auto", "chromatic")

    @Test
    fun `open high E`() = openString("E4", -8.6, everyMode)

    @Test
    fun `open B`() = openString("B3", -10.9, everyMode)

    // Half a semitone flat when recorded, so only in guitar mode is it a G; chromatically it sits
    // between two notes. A string that slack starts well sharp of itself when plucked, and that
    // is read too: fewer of the readings are near the median than on the other strings.
    @Test
    fun `open G, half a semitone flat`() = openString("G3", -55.6, listOf("guitar"), near = 0.78)

    @Test
    fun `open D`() = openString("D3", 0.7, everyMode)

    @Test
    fun `open A`() = openString("A2", 1.1, everyMode)

    @Test
    fun `open low E`() = openString("E2", -0.3, everyMode)

    @Test
    fun `fretted notes are heard at their own octave`() {
        // The low F goes to the octave above in the dying tail of the note, a known weakness.
        fretted(1, setOf("F2"), 0.75)
        fretted(2, setOf("F#2"), 0.92)
        fretted(3, setOf("G2"), 0.97)
        fretted(4, setOf("B2", "C3"), 0.97)
        fretted(5, setOf("E3"), 0.87)
        fretted(6, setOf("F3"), 0.97)
        // Played with vibrato across the midpoint between two semitones: either name is right.
        fretted(7, setOf("G#3", "A3"), 0.90)
        fretted(8, setOf("A3", "A#3"), 0.83)
        fretted(9, setOf("B3"), 0.95)
        fretted(10, setOf("F4", "G4", "A4", "B4", "C#5", "E5"), 0.88)
    }

    private companion object {
        const val MIN_READINGS = 150
        const val NEAR_CENTS = 2.5
    }
}
