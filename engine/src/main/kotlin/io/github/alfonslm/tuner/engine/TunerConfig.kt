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
    /** Minimum periodicity for a frame to count as a pitched note. */
    val minClarity: Double = 0.85,
    val peakPickRatio: Double = 0.9,
    /** How far outside the outermost strings the detector still listens. */
    val rangeMarginCents: Double = 300.0,
)

data class FocusConfig(
    /** Preferred window length in periods of the locked note, capped at [maxFrameMs]... */
    val periods: Double = 16.0,
    val maxFrameMs: Double = 150.0,
    /** ...but never fewer than this many periods (bass strings need the length). */
    val minPeriods: Double = 10.0,
    val minFrameMs: Double = 40.0,
    /** The comb is kept this much wider than the current distance to the target. */
    val marginCents: Double = 35.0,
    /** Fraction of the remaining gap closed per frame when the comb narrows. */
    val narrowingRate: Double = 0.25,
    val minPeak: Double = 0.5,
    val minSalience: Double = 0.5,
    val minExplained: Double = 0.8,
    val minFundamentalSupport: Double = 0.06,
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
    /** Chromatic / neighbouring-string hand-off: the new target must be closer by this much... */
    val handoffHysteresisCents: Double = 10.0,
    /** ...for this many frames in a row. */
    val handoffFrames: Int = 3,
)

data class DisplayConfig(
    /** Frames in the median that drops a single stray reading. */
    val medianFrames: Int = 3,
    /** 0..1 weight of the newest value in the exponential average applied after the median. */
    val smoothing: Double = 0.5,
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
