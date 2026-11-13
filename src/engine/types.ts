import type { Target } from '../music/targets';

/** `search`: wide-band, looking for a note. `locked`: target-aware harmonic filter engaged. */
export type TunerMode = 'search' | 'locked';

/** One analysis result. The engine produces exactly one per audio block. */
export interface TunerReading {
  /** Stream time in seconds. */
  time: number;
  mode: TunerMode;
  levelDb: number;
  /** True when this frame produced a pitch; the fields below then describe it. */
  voiced: boolean;
  /** Which detector the pitch came from. */
  source: 'wide' | 'focused' | null;
  /** The note being tuned to: the lock target, or the nearest target while searching. */
  target: Target | null;
  /** Smoothed pitch and its offset from `target`. */
  frequency: number | null;
  cents: number | null;
  /** 0..1 */
  confidence: number;
  /** Within tolerance, and has been for a moment. */
  inTune: boolean;
  /** State of the smart filter. */
  lock: {
    target: Target;
    pinned: boolean;
    /** Current half-width of the comb's pass bands in cents. */
    widthCents: number;
  } | null;
}
