export type SmartFilterMode = 'auto' | 'off';

export interface TunerConfig {
  instrumentId: string;
  a4: number;
  /**
   * `auto`: listen wide-band, then switch to the target-aware harmonic filter
   * once a note has been stable. `off`: always wide-band.
   */
  smartFilter: SmartFilterMode;
  /** Target the user fixed by hand; the filter then stays on it regardless of what is heard. */
  pinnedTargetId: string | null;

  /** Frames quieter than this (dBFS) are treated as silence. */
  levelGateDb: number;
  /** Partials above this frequency are ignored by both detectors. */
  maxPartialHz: number;

  wide: {
    /** Window length in periods of the lowest note the instrument can produce. */
    periods: number;
    minFrameMs: number;
    /** Minimum periodicity for a frame to count as a pitched note. */
    minClarity: number;
    peakPickRatio: number;
    /** How far outside the outermost strings the detector still listens. */
    rangeMarginCents: number;
  };

  focus: {
    /** Preferred window length in periods of the locked note, capped at `maxFrameMs`... */
    periods: number;
    maxFrameMs: number;
    /** ...but never fewer than this many periods (bass strings need the length). */
    minPeriods: number;
    minFrameMs: number;
    /** The comb is kept this much wider than the current distance to the target. */
    marginCents: number;
    /** Fraction of the remaining gap closed per frame when the comb narrows. */
    narrowingRate: number;
    minPeak: number;
    minSalience: number;
    minExplained: number;
    minFundamentalSupport: number;
    /** Capture reaches this far past the midpoint to the neighbouring target. */
    captureOverlapCents: number;
    maxCaptureCents: number;
    /** Capture range when the user pinned the target (the string may be far off). */
    pinnedCaptureCents: number;
  };

  lock: {
    /** A note must be heard this long, on the same target, before the filter engages. */
    stableMs: number;
    /** ...and wander no more than this while doing so. */
    maxSpreadCents: number;
    /** The lock survives this much silence, so re-plucking the same string is tracked instantly. */
    holdMs: number;
    /** Frames without a usable estimate before the comb re-opens to the full capture range. */
    reopenAfterFrames: number;
    /** Chromatic / neighbouring-string hand-off: the new target must be closer by this much... */
    handoffHysteresisCents: number;
    /** ...for this many frames in a row. */
    handoffFrames: number;
  };

  display: {
    medianFrames: number;
    /** 0..1 exponential smoothing factor applied after the median. */
    smoothing: number;
    /** A gap longer than this restarts smoothing. */
    resetGapMs: number;
    inTuneCents: number;
    inTuneHoldMs: number;
  };
}

export const DEFAULT_CONFIG: TunerConfig = {
  instrumentId: 'guitar',
  a4: 440,
  smartFilter: 'auto',
  pinnedTargetId: null,
  levelGateDb: -72,
  maxPartialHz: 5000,
  wide: {
    periods: 8,
    minFrameMs: 40,
    minClarity: 0.85,
    peakPickRatio: 0.9,
    rangeMarginCents: 300,
  },
  focus: {
    periods: 16,
    maxFrameMs: 150,
    minPeriods: 10,
    minFrameMs: 40,
    marginCents: 35,
    narrowingRate: 0.25,
    minPeak: 0.5,
    minSalience: 0.5,
    minExplained: 0.8,
    minFundamentalSupport: 0.06,
    captureOverlapCents: 30,
    maxCaptureCents: 300,
    pinnedCaptureCents: 400,
  },
  lock: {
    stableMs: 180,
    maxSpreadCents: 30,
    holdMs: 2500,
    reopenAfterFrames: 4,
    handoffHysteresisCents: 10,
    handoffFrames: 3,
  },
  display: {
    medianFrames: 5,
    smoothing: 0.35,
    resetGapMs: 300,
    inTuneCents: 3,
    inTuneHoldMs: 400,
  },
};

export type TunerConfigPatch = Partial<Omit<TunerConfig, 'wide' | 'focus' | 'lock' | 'display'>> & {
  wide?: Partial<TunerConfig['wide']>;
  focus?: Partial<TunerConfig['focus']>;
  lock?: Partial<TunerConfig['lock']>;
  display?: Partial<TunerConfig['display']>;
};

export function mergeConfig(base: TunerConfig, patch: TunerConfigPatch): TunerConfig {
  return {
    ...base,
    ...patch,
    wide: { ...base.wide, ...patch.wide },
    focus: { ...base.focus, ...patch.focus },
    lock: { ...base.lock, ...patch.lock },
    display: { ...base.display, ...patch.display },
  };
}
