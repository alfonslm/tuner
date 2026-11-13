package io.github.alfonslm.tuner.engine

import io.github.alfonslm.tuner.music.DEFAULT_A4
import io.github.alfonslm.tuner.music.DEFAULT_INSTRUMENT_ID
import io.github.alfonslm.tuner.music.DEFAULT_TUNINGS
import io.github.alfonslm.tuner.music.InstrumentPreset

enum class SmartFilterMode {
    /** Listen wide-band, then switch to the target-aware harmonic filter once a note has been stable. */
    AUTO,

    /** Always wide-band. */
    OFF,
}

data class TunerConfig(
    /** Automatic, chromatic, or the id of one of [tunings]. */
    val instrumentId: String = DEFAULT_INSTRUMENT_ID,
    /**
     * The tunings in use: the ones that can be selected, and the candidates
     * automatic mode chooses among. Standard and custom ones alike.
     */
    val tunings: List<InstrumentPreset> = DEFAULT_TUNINGS,
    val a4: Double = DEFAULT_A4,
    val smartFilter: SmartFilterMode = SmartFilterMode.AUTO,
    /** Target the user fixed by hand; the filter then stays on it regardless of what is heard. */
    val pinnedTargetId: String? = null,
    /** Frames quieter than this (dBFS) are treated as silence. */
    val levelGateDb: Double = -72.0,
    /** Partials above this frequency are ignored by both detectors. */
    val maxPartialHz: Double = 5000.0,
    val fresh: FreshConfig = FreshConfig(),
    val wide: WideConfig = WideConfig(),
    val focus: FocusConfig = FocusConfig(),
    val lock: LockConfig = LockConfig(),
    val display: DisplayConfig = DisplayConfig(),
    val spectrogram: SpectrogramConfig = SpectrogramConfig(),
)

data class WideConfig(
    /** Window length in periods of the lowest note the instrument can produce. */
    val periods: Double = 8.0,
    val minFrameMs: Double = 40.0,
    /**
     * Minimum periodicity for a frame to count as a pitched note. Below this the
     * estimate is too often wrong (in noise by tens of cents) to show. A string
     * plucked over others that are still ringing does not have to reach it on
     * the whole sound: it is heard on its own first, see [FreshConfig].
     */
    val minClarity: Double = 0.75,
    /**
     * Periodicity that is enough to count towards a lock, though not to be shown.
     * A string among several others reaches only this much on the whole sound;
     * once locked, the focused detector judges it by its own partials.
     */
    val minLockClarity: Double = 0.5,
    /**
     * The shortest period that is at least this good, relative to the best one,
     * is taken as the pitch. Low enough that the string just plucked wins over a
     * quieter one ringing an octave or two below it, whose longer period fits
     * both strings and therefore always scores highest.
     */
    val peakPickRatio: Double = 0.65,
    /** How far outside the outermost strings the detector still listens. */
    val rangeMarginCents: Double = 300.0,
)

data class FocusConfig(
    /** Preferred window length in periods of the locked note, capped at [maxFrameMs]... */
    val periods: Double = 16.0,
    val maxFrameMs: Double = 150.0,
    /** ...but never fewer than this many periods (bass strings need the length)... */
    val minPeriods: Double = 10.0,
    /**
     * ...nor shorter than this. A window resolves what lies further apart than
     * about two divided by its length: at 120 ms, 17 Hz. High strings need that
     * as much as low ones, because what crowds them is not their own partials
     * but those of the other strings still ringing: the second partial of a D
     * string lies 36 Hz below a high E, and inside a 50 ms window's reach it
     * pulls the reading flat by several cents.
     */
    val minFrameMs: Double = 120.0,
    /** The comb is kept this much wider than the current distance to the target. */
    val marginCents: Double = 35.0,
    /** Fraction of the remaining gap closed per frame when the comb narrows. */
    val narrowingRate: Double = 0.25,
    val minPeak: Double = 0.5,
    /** The partials must stand this many times above what the noise floor alone would put there. */
    val minProminence: Double = 12.0,
    /** The string must hold this share of the tonal energy in its band: the main thing sounding, not the only one. */
    val minExplained: Double = 0.45,
    /** Least share of energy on partials that are not multiples of 2 (3, 5, 7): else it is a note that much higher. */
    val minFundamentalSupport: Double = 0.06,
    /**
     * Most energy allowed between the partials, relative to that on them. More
     * than this, and what the comb found is taken for an overtone of a lower
     * note. Lenient, because between the partials is also where the partials of
     * other strings fall, and a string plucked a moment ago may well still be
     * ringing nearly as loudly; the wide detector settles such cases over time.
     */
    val maxSubharmonic: Double = 1.0,
    /**
     * The same limit while a string is pinned. Strict, because nothing else then
     * guards against showing the tail of a lower string as the pinned one.
     */
    val pinnedMaxSubharmonic: Double = 0.35,
    /** Capture reaches this far past the midpoint to the neighbouring target. */
    val captureOverlapCents: Double = 30.0,
    val maxCaptureCents: Double = 300.0,
    /** Capture range when the user pinned the target (the string may be far off). */
    val pinnedCaptureCents: Double = 400.0,
)

data class LockConfig(
    /** A note must be heard this long, on the same target, before the filter engages. */
    val stableMs: Double = 180.0,
    /** ...and wander no more than this while doing so. */
    val maxSpreadCents: Double = 30.0,
    /** The lock survives this much silence, so re-plucking the same string is tracked instantly. */
    val holdMs: Double = 2500.0,
    /** Frames without a usable estimate before the comb re-opens to the full capture range. */
    val reopenAfterFrames: Int = 4,
    /**
     * When the wide detector names an overtone of the locked string: below this
     * share of energy on the string's other partials, believe it (the higher
     * string has been plucked while this one rings on); above it, it is the wide
     * detector's octave error.
     */
    val minOvertoneSupport: Double = 0.3,
    /**
     * ...and only to a string that the overtone is within this many cents of.
     * (Strings that are each other's overtones, like a guitar's two E strings,
     * are; the octave of a low string usually is not.)
     */
    val overtoneHandoffCents: Double = 60.0,
    /**
     * When the comb has lost the string and the wide detector names an overtone
     * of it, the lock waits this long for the string to come back before moving.
     */
    val overtoneWaitMs: Double = 400.0,
    /** Chromatic / neighbouring-string hand-off: the new target must be closer by this much... */
    val handoffHysteresisCents: Double = 10.0,
    /** ...for this many frames in a row. */
    val handoffFrames: Int = 3,
)

data class DisplayConfig(
    /** Frames in the median that drops a single stray reading. */
    val medianFrames: Int = 3,
    /**
     * Time constant of the exponential moving average behind the needle. Longer
     * is steadier against a beating string; the needle trails a moving pitch by
     * this long.
     */
    val smoothingMs: Double = 200.0,
    /** Time over which the scatter of the estimates around the needle is measured. */
    val spreadMs: Double = 400.0,
    /**
     * Nothing is shown for this long after a pluck is noticed. The attack is
     * noise and a moment of wildly sharp pitch; the needle is better left where
     * it was than thrown about by it.
     */
    val attackHoldMs: Double = 150.0,
    /** A frame this much louder (dB) than the quietest of the three before it is a pluck arriving, and is not shown. */
    val surgeDb: Double = 10.0,
    /** A gap longer than this restarts smoothing. */
    val resetGapMs: Double = 300.0,
    val inTuneCents: Double = 3.0,
    val inTuneHoldMs: Double = 400.0,
)

/** The rolling spectrogram under the meter; see `FoldedSpectrum`. */
data class SpectrogramConfig(
    /** Off when the display does not show it; saves the extra analysis. */
    val enabled: Boolean = true,
    /** A row is computed every this many audio blocks (3 ≈ 16 rows per second). */
    val everyBlocks: Int = 3,
    /** Analysis window in periods of the target note: the stripe is about 400 / (periods · 8) cents wide at best. */
    val periods: Double = 48.0,
    /** ...capped at this length, which also bounds how much audio history is kept. */
    val maxFrameMs: Double = 600.0,
    /** Power counts from this many times the mean noise power upwards. */
    val floorFactor: Double = 3.0,
    /** A partial appears once its peak is this far above the noise (dB), at full weight [fadeInDb] further up. */
    val minSnrDb: Double = 10.0,
    val fadeInDb: Double = 10.0,
    /** A partial this far above the noise (dB) is drawn at full brightness. */
    val fullScaleDb: Double = 40.0,
)

/**
 * Telling the string just plucked from the ones still ringing: both detectors
 * first examine the spectrum with everything taken away that was already
 * sounding before the latest pluck, and only if nothing clear is found there,
 * the whole sound. See `SpectralHistory`.
 */
data class FreshConfig(
    val enabled: Boolean = true,
    /** The short run of spectra that "a moment ago" is taken from. */
    val memoryMs: Double = 500.0,
    /** Spectra are remembered this far apart. */
    val stepMs: Double = 85.0,
    /** The remembered background is faded out with this time constant; the strings it stands for are dying away too. */
    val fadeMs: Double = 1500.0,
    /** A pluck is a rise by this much in the share of the energy that was not there a moment ago. */
    val onsetRise: Double = 0.25,
    /** At least this share of the energy must stand above the background for that part to be examined on its own. */
    val minShare: Double = 0.1,
    /** Periodicity that part must have for the wide detector to believe its pitch. */
    val minClarity: Double = 0.8,
    /** Audio kept so that a detector starting on a new lock can still learn what was sounding before the pluck. */
    val lookBackMs: Double = 1000.0,
)
