package io.github.alfonslm.tuner.engine

import io.github.alfonslm.tuner.music.Target

enum class TunerMode {
    /** Wide-band, looking for a note. */
    SEARCH,

    /** Target-aware harmonic filter engaged. */
    LOCKED,
}

enum class PitchSource { WIDE, FOCUSED }

/** State of the smart filter. */
data class LockInfo(
    val target: Target,
    val pinned: Boolean,
    /** Current half-width of the comb's pass bands in cents. */
    val widthCents: Double,
)

/** One analysis result. The engine produces exactly one per audio block. */
data class TunerReading(
    /** Stream time in seconds. */
    val time: Double,
    /**
     * The instrument whose strings are the current targets. Equal to the
     * configured one, except in automatic mode: there it is the automatic preset
     * until the instrument has been identified, and the identified one after.
     */
    val instrumentId: String,
    val mode: TunerMode,
    val levelDb: Double,
    /** Which detector the pitch came from; `null` when this frame produced no pitch. */
    val source: PitchSource?,
    /** The note being tuned to: the lock target, or the nearest target while searching. */
    val target: Target?,
    /** Smoothed pitch and its offset from [target]; `null` when not [voiced]. */
    val frequency: Double?,
    val cents: Double?,
    /**
     * How far the individual overtones disagree about the pitch, in cents
     * (power-weighted RMS, smoothed like [cents]). Near zero for a clean string;
     * wider for a stiff or noisy one. `null` when not [voiced] or not measurable.
     */
    val spreadCents: Double?,
    /** 0..1 */
    val confidence: Double,
    /** Within tolerance, and has been for a moment. */
    val inTune: Boolean,
    val lock: LockInfo?,
) {
    /** True when this frame produced a pitch. */
    val voiced: Boolean get() = source != null
}
