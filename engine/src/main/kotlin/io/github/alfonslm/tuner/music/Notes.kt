package io.github.alfonslm.tuner.music

import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow

/** Note / frequency helpers (12-tone equal temperament). */

const val DEFAULT_A4 = 440.0

private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val NATURALS = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)
private val NOTE_PATTERN = Regex("""^([A-Ga-g])([#♯b♭]?)(-?\d+)$""")

fun midiToFrequency(midi: Double, a4: Double = DEFAULT_A4): Double = a4 * 2.0.pow((midi - 69) / 12)

fun frequencyToMidi(frequency: Double, a4: Double = DEFAULT_A4): Double = 69 + 12 * log2(frequency / a4)

/** Signed distance from [reference] to [frequency] in cents. */
fun centsBetween(frequency: Double, reference: Double): Double = 1200 * log2(frequency / reference)

fun centsToRatio(cents: Double): Double = 2.0.pow(cents / 1200)

fun midiLabel(midi: Int): String = "${NAMES[Math.floorMod(midi, 12)]}${floor(midi / 12.0).toInt() - 1}"

/** Parses scientific pitch notation such as "E2", "F#3" or "Bb1". */
fun parseNote(note: String): Int {
    val match = requireNotNull(NOTE_PATTERN.matchEntire(note.trim())) { "Invalid note name: $note" }
    val (letter, accidental, octave) = match.destructured
    val shift = when (accidental) {
        "#", "♯" -> 1
        "" -> 0
        else -> -1
    }
    return NATURALS.getValue(letter[0].uppercaseChar()) + shift + (octave.toInt() + 1) * 12
}
