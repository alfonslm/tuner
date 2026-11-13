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

/**
 * One row of the rolling spectrogram: the string's spectrum on a cents scale
 * centred on a target, noise floor removed (see `FoldedSpectrum`).
 */
class SpectrumRow(
    /** Stream time of the middle of the audio the row was computed from. */
    val time: Double,
    /** Seconds until the next row: how much time this one stands for. */
    val duration: Double,
    /** The target the cents scale is centred on. */
    val targetId: String,
    /** `SPECTRUM_COLUMNS` levels in 0..1, from -`SPECTRUM_RANGE_CENTS` to +`SPECTRUM_RANGE_CENTS`. */
    val levels: FloatArray,
    /**
     * Whether the tuner had a reading for the audio this row describes. Rows
     * without one are whatever else was sounding: a pluck's attack, the string
     * being stopped by the hand, other strings ringing on after it has faded.
     */
    val read: Boolean = true,
)

/** One analysis result. The engine produces exactly one per audio block. */
data class TunerReading(
    /** Stream time in seconds at which the reading was produced. */
    val time: Double,
    /**
     * How long ago, in seconds, the audio behind [cents] was sounding: a
     * reading describes the middle of its analysis window, and smoothing adds a
     * little more. Subtract it from [time] to place the reading on the same
     * time axis as the spectrogram rows. Zero when not [voiced].
     */
    val delay: Double,
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
     * How much the estimates behind [cents] scatter, as a standard deviation in
     * cents: what the smoothing is hiding. Small on a clean note; large in
     * noise, on a note that is dying away, or when something else is sounding
     * next to it. `null` until a note has given enough readings to tell.
     */
    val spreadCents: Double? = null,
    /**
     * 0..1: how much of what is sounding is the note being read, in this frame
     * alone. Near 1 for one string on its own; lower at once when something
     * else sounds with it, whether other strings ringing on or another
     * instrument. (Locked: the share of the energy in the string's band that
     * lies on its partials. Searching: how periodic the sound is.)
     */
    val confidence: Double,
    /** Within tolerance, and has been for a moment. */
    val inTune: Boolean,
    val lock: LockInfo?,
    /** A new row of the spectrogram, on the frames that produce one. */
    val spectrum: SpectrumRow? = null,
) {
    /** True when this frame produced a pitch. */
    val voiced: Boolean get() = source != null
}
