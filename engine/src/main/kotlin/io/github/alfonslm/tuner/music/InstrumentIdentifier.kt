package io.github.alfonslm.tuner.music

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Works out which instrument is being tuned from the strings it hears.
 *
 * One string says little: G3 is on a guitar, a violin and a viola. Two
 * different strings almost always settle it, because instruments differ both
 * in register and in the interval between neighbouring strings (fourths on a
 * guitar or bass, fifths in the violin family). So the identifier looks at
 * the two most recent distinct pitches and asks, for every instrument,
 * "could these be two of your strings, each reasonably near its pitch?".
 *
 * It only answers when exactly one instrument fits clearly better than the
 * rest. Pairs that genuinely fit two instruments (a violin and a viola share
 * G3, D4 and A4) stay undecided until a string that only one of them has is
 * played. Looking at just the last two pitches also means one misheard note
 * is forgotten as soon as two good ones follow.
 *
 * @param toleranceCents how far out of tune a string may be and still count as that string
 * @param marginCents how much closer (RMS cents) the best instrument must fit than the runner-up
 */
class InstrumentIdentifier(
    private val candidates: List<InstrumentPreset> = INSTRUMENTS.filter { it.strings != null },
    private val toleranceCents: Double = 150.0,
    private val marginCents: Double = 40.0,
) {
    private var previous: Double? = null
    private var latest: Double? = null

    fun reset() {
        previous = null
        latest = null
    }

    /**
     * Reports a steadily sounding pitch. Returns the instrument as soon as the
     * last two distinct pitches identify one, otherwise `null`.
     */
    fun observe(frequency: Double, a4: Double = DEFAULT_A4): InstrumentPreset? {
        val last = latest
        // The same string again (perhaps while it is being tuned) replaces the earlier observation.
        if (last != null && abs(centsBetween(frequency, last)) > toleranceCents) previous = last
        latest = frequency
        val other = previous ?: return null

        var best: InstrumentPreset? = null
        var bestFit = Double.POSITIVE_INFINITY
        var runnerUpFit = Double.POSITIVE_INFINITY
        for (candidate in candidates) {
            val fit = fit(candidate, other, frequency, a4) ?: continue
            if (fit < bestFit) {
                runnerUpFit = bestFit
                bestFit = fit
                best = candidate
            } else if (fit < runnerUpFit) {
                runnerUpFit = fit
            }
        }
        return best?.takeIf { runnerUpFit - bestFit >= marginCents }
    }

    /**
     * RMS distance in cents of the two pitches from two different strings of
     * [instrument], or `null` if they do not fit. RMS rather than the mean, so
     * that "both a little off" beats "one spot on, one far off".
     */
    private fun fit(instrument: InstrumentPreset, first: Double, second: Double, a4: Double): Double? {
        val strings = instrument.strings?.map { midiToFrequency(parseNote(it).toDouble(), a4) } ?: return null
        val a = strings.indices.minBy { abs(centsBetween(first, strings[it])) }
        val b = strings.indices.minBy { abs(centsBetween(second, strings[it])) }
        if (a == b) return null
        val distanceA = abs(centsBetween(first, strings[a]))
        val distanceB = abs(centsBetween(second, strings[b]))
        if (distanceA > toleranceCents || distanceB > toleranceCents) return null
        return sqrt((distanceA * distanceA + distanceB * distanceB) / 2)
    }
}
