package io.github.alfonslm.tuner.music

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** A note the user may be tuning to. */
data class Target(
    /** Stable identity, e.g. "s0:E2" for a string or "m45" for a chromatic note. */
    val id: String,
    val label: String,
    val midi: Int,
    val frequency: Double,
)

/**
 * The set of notes the tuner snaps to: either the strings of an instrument or
 * every semitone (chromatic). The lock logic only talks to this interface, so
 * alternate tunings or custom temperaments are just another TargetSet.
 */
interface TargetSet {
    /** Explicit targets in display order, or `null` for chromatic. */
    val list: List<Target>?

    /** Frequency range the wide-band detector has to cover. */
    val minHz: Double
    val maxHz: Double

    fun nearest(frequency: Double): Target

    fun byId(id: String): Target?

    /** How far (in cents) a string may be from [target] and still be tracked by the focused filter. */
    fun captureCents(target: Target): Double
}

data class TargetSetOptions(
    /** Extra room beyond the outermost strings that the wide detector still covers. */
    val rangeMarginCents: Double,
    /** Capture reaches this far past the midpoint to the neighbouring target (hand-off hysteresis room). */
    val captureOverlapCents: Double,
    val maxCaptureCents: Double,
)

fun createTargetSet(preset: InstrumentPreset, a4: Double, options: TargetSetOptions): TargetSet =
    if (preset.strings == null) ChromaticTargets(preset, a4, options) else StringTargets(preset.strings, a4, options)

private class ChromaticTargets(preset: InstrumentPreset, private val a4: Double, private val options: TargetSetOptions) :
    TargetSet {
    private val lowMidi = parseNote(preset.chromaticRange.first)
    private val highMidi = parseNote(preset.chromaticRange.second)

    override val list: List<Target>? = null

    // A semitone grid needs no extra margin: half a semitone beyond the end notes is enough.
    override val minHz = midiToFrequency(lowMidi - 0.75, a4)
    override val maxHz = midiToFrequency(highMidi + 0.75, a4)

    private fun make(midi: Int) = Target("m$midi", midiLabel(midi), midi, midiToFrequency(midi.toDouble(), a4))

    override fun nearest(frequency: Double): Target =
        make(frequencyToMidi(frequency, a4).roundToInt().coerceIn(lowMidi, highMidi))

    override fun byId(id: String): Target? =
        id.removePrefix("m").takeIf { id.startsWith("m") }?.toIntOrNull()?.takeIf { it in lowMidi..highMidi }?.let(::make)

    override fun captureCents(target: Target): Double = min(options.maxCaptureCents, 50 + options.captureOverlapCents)
}

private class StringTargets(strings: List<String>, a4: Double, private val options: TargetSetOptions) : TargetSet {
    override val list: List<Target> = strings.mapIndexed { index, note ->
        val midi = parseNote(note)
        Target("s$index:$note", midiLabel(midi), midi, midiToFrequency(midi.toDouble(), a4))
    }

    private val margin = 2.0.pow(options.rangeMarginCents / 1200)
    override val minHz = list.minOf { it.frequency } / margin
    override val maxHz = list.maxOf { it.frequency } * margin

    override fun nearest(frequency: Double): Target = list.minBy { abs(centsBetween(frequency, it.frequency)) }

    override fun byId(id: String): Target? = list.find { it.id == id }

    override fun captureCents(target: Target): Double {
        val nearestNeighbour = list
            .filter { it.id != target.id }
            .map { abs(centsBetween(it.frequency, target.frequency)) }
            // Unison / octave courses (e.g. 12-string) must not collapse the capture range.
            .filter { it > 1 }
            .minOrNull() ?: Double.POSITIVE_INFINITY
        return min(options.maxCaptureCents, nearestNeighbour / 2 + options.captureOverlapCents)
    }
}
