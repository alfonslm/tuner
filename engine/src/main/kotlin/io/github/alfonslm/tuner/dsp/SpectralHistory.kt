package io.github.alfonslm.tuner.dsp

import kotlin.math.exp
import kotlin.math.max

/**
 * Remembers what was sounding before the latest pluck, so that a detector can
 * look at the newly plucked string on its own.
 *
 * Why that matters: while tuning, the strings plucked a few seconds ago are
 * still ringing, and the one plucked now may be only half of what the
 * microphone hears. In a single spectrum that is ambiguous. A high E over a
 * ringing low E looks just like a low E with weak overtones. Over time it is
 * not: a ringing string only fades, the plucked one appears.
 *
 * So the history keeps a short run of recent spectra. [novelty] says how much
 * of the current spectrum stands well above the average of that run; a jump
 * in it is a pluck. At a pluck the owner calls [anchor], which takes the least
 * each bin has held over the run as "the background". From then on [fresh]
 * returns the spectrum with the background taken away: what was already
 * sounding is gone, because it has only faded since, and the new string is
 * left nearly intact.
 *
 * The background is itself faded out over about a second. The strings it
 * stands for are dying away too, and taking away more than is really still
 * there would eat into the new string's own partials where the two lie close.
 * By the time it has gone, the new string has long been found; and a
 * background anchored at a wrong moment cannot do lasting harm.
 *
 * @param entries how many spectra the short run holds
 * @param stride one of every this many frames is kept
 * @param guard only spectra at least this many frames old are compared against,
 *   so that the comparison is with audio that lies entirely before the current frame
 * @param fadeFrames frames over which the background falls to 1/e
 */
class SpectralHistory(
    private val bins: Int,
    private val entries: Int,
    private val stride: Int,
    private val guard: Int,
    private val fadeFrames: Double,
) {
    private val stored = Array(entries) { DoubleArray(bins) }
    private val storedAt = LongArray(entries) { Long.MIN_VALUE }
    private val recent = DoubleArray(bins)
    private val background = DoubleArray(bins)
    private var frame = 0L
    private var next = 0
    private var anchoredAt = NEVER

    /** How much of the background is still being taken away, 1 at a pluck falling towards 0. */
    val weight: Double get() = if (anchoredAt == NEVER) 0.0 else exp(-(frame - anchoredAt) / fadeFrames)

    /** True while a background from a recent pluck is in force. */
    val anchored: Boolean get() = weight >= MIN_WEIGHT

    fun clear() {
        storedAt.fill(Long.MIN_VALUE)
        frame = 0
        next = 0
        anchoredAt = NEVER
    }

    /** Files away the spectrum of a frame that ended [framesAgo] frames before the next one to be analysed. */
    fun prime(power: DoubleArray, framesAgo: Int) = store(power, frame - framesAgo)

    /** Notes that a frame has been analysed, and keeps its spectrum if it is this one's turn. */
    fun remember(power: DoubleArray) {
        if (frame % stride == 0L) store(power, frame)
        frame++
    }

    private fun store(power: DoubleArray, at: Long) {
        power.copyInto(stored[next], 0, 0, bins)
        storedAt[next] = at
        next = (next + 1) % entries
    }

    /**
     * The share of the energy in [power] that stands well above what the last
     * few frames held on average, 0..1; or -1 if there is nothing old enough to
     * compare with. Close to zero for anything steady or fading, noise
     * included, and large when a note starts.
     */
    fun novelty(power: DoubleArray): Double {
        var count = 0
        recent.fill(0.0)
        for (entry in 0 until entries) {
            if (storedAt[entry] == Long.MIN_VALUE || frame - storedAt[entry] < guard) continue
            count++
            val past = stored[entry]
            for (b in 0 until bins) recent[b] += past[b]
        }
        if (count == 0) return -1.0
        var total = 0.0
        var new = 0.0
        val threshold = NOVELTY_FACTOR / count
        for (b in 0 until bins) {
            new += max(0.0, power[b] - threshold * recent[b])
            total += power[b]
        }
        return if (total > 0) new / total else 0.0
    }

    /**
     * Declares what has been sounding over the last few frames to be the
     * background from now on: the least each bin has held. Returns false,
     * changing nothing, if there is no frame old enough to take it from.
     */
    fun anchor(): Boolean {
        var found = false
        recent.fill(Double.MAX_VALUE)
        for (entry in 0 until entries) {
            if (storedAt[entry] == Long.MIN_VALUE || frame - storedAt[entry] < guard) continue
            found = true
            val past = stored[entry]
            for (b in 0 until bins) if (past[b] < recent[b]) recent[b] = past[b]
        }
        if (!found) return false
        recent.copyInto(background)
        anchoredAt = frame
        return true
    }

    /**
     * Writes to [out] the part of [power] that stands above the (fading)
     * background, and returns its share of the total energy; or -1 (leaving
     * [out] alone) if no background is in force.
     */
    fun fresh(power: DoubleArray, out: DoubleArray): Double {
        val weight = weight
        if (weight < MIN_WEIGHT) return -1.0
        var total = 0.0
        var new = 0.0
        for (b in 0 until bins) {
            val value = max(0.0, power[b] - weight * background[b])
            out[b] = value
            new += value
            total += power[b]
        }
        return if (total > 0) new / total else 0.0
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE / 2

        /** Below this the background no longer matters and the whole spectrum is used again. */
        const val MIN_WEIGHT = 0.1

        /**
         * "Well above average" for [novelty]: the power of steady noise jumps about
         * from frame to frame and exceeds four times its average in under 2 % of bins.
         */
        const val NOVELTY_FACTOR = 4.0
    }
}
