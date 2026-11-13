import { nextPowerOfTwo } from '../dsp/fft';
import { FocusedDetector, type FocusedEstimate } from '../dsp/focusedDetector';
import { getAnalyzer, type SpectralAnalyzer } from '../dsp/spectral';
import { WideDetector, type PitchEstimate } from '../dsp/wideDetector';
import { getInstrument, type InstrumentPreset } from '../music/instruments';
import { centsBetween, centsToRatio } from '../music/notes';
import { createTargetSet, type Target, type TargetSet } from '../music/targets';
import { DEFAULT_CONFIG, mergeConfig, type TunerConfig, type TunerConfigPatch } from './config';
import { ReadingSmoother } from './ReadingSmoother';
import { RingBuffer } from './RingBuffer';
import { StabilityWindow } from './StabilityWindow';
import type { TunerReading } from './types';

interface Lock {
  target: Target;
  pinned: boolean;
  detector: FocusedDetector;
  frame: Float32Array;
  /** Widest the comb may open: how far the string can be from the target and still be ours. */
  captureCents: number;
  /** Current comb half-width; shrinks as the string approaches the target. */
  widthCents: number;
  lastHeard: number;
  misses: number;
  handoffFrames: number;
}

interface Pitch {
  frequency: number;
  target: Target;
  source: 'wide' | 'focused';
  confidence: number;
}

/**
 * The whole tuner minus the microphone and the screen: feed it audio blocks,
 * get one {@link TunerReading} back per block. It has no platform
 * dependencies, so it runs unchanged in a worker, an audio worklet, Node
 * (tests) or a native shell.
 *
 * Per block:
 *   1. the wide detector looks for any pitched note in the instrument's range;
 *   2. a stability window decides whether that note has settled on one target;
 *   3. in `search` mode a settled note engages the lock; in `locked` mode the
 *      focused detector (harmonic comb around the target) produces the reading
 *      and the wide detector only acts as a watchdog for a change of string;
 *   4. the reading is smoothed for display.
 */
export class TunerEngine {
  private config: TunerConfig;
  private preset!: InstrumentPreset;
  private targets!: TargetSet;
  private wideAnalyzer!: SpectralAnalyzer;
  private wideDetector!: WideDetector;
  private widePower!: Float64Array;
  private wideFrame!: Float32Array;
  private ring!: RingBuffer;
  private stability!: StabilityWindow;
  private smoother!: ReadingSmoother;
  private readonly detectors = new Map<string, FocusedDetector>();
  private lock: Lock | null = null;
  private samples = 0;

  constructor(
    readonly sampleRate: number,
    /** Samples per `process` call; only used to convert time constants into frames. */
    readonly blockSize: number,
    config: TunerConfigPatch = {},
  ) {
    this.config = mergeConfig(DEFAULT_CONFIG, config);
    this.rebuild();
  }

  getConfig(): TunerConfig {
    return this.config;
  }

  getTargets(): TargetSet {
    return this.targets;
  }

  configure(patch: TunerConfigPatch): void {
    const previous = this.config;
    this.config = mergeConfig(previous, patch);
    if (this.config.instrumentId !== previous.instrumentId) this.config.pinnedTargetId = patch.pinnedTargetId ?? null;
    this.rebuild();
  }

  /** Forgets all audio and state, e.g. when the microphone is restarted. */
  reset(): void {
    this.samples = 0;
    this.rebuild();
  }

  process(block: Float32Array): TunerReading {
    const { config, targets } = this;
    this.ring.push(block);
    this.samples += block.length;
    const time = this.samples / this.sampleRate;

    if (!this.ring.copyLatest(this.wideFrame)) return this.reading(time, -120, null, null, false);

    // 1. Wide-band analysis: acquisition while searching, watchdog while locked.
    const rms = this.wideAnalyzer.powerSpectrum(this.wideFrame, this.widePower);
    const levelDb = 20 * Math.log10(rms + 1e-9);
    const audible = levelDb > config.levelGateDb;
    const estimate = audible ? this.wideDetector.detect(this.widePower) : null;
    const wide = estimate && estimate.clarity >= config.wide.minClarity ? estimate : null;
    const wideTarget = wide ? targets.nearest(wide.frequency) : null;

    // 2. Has one note been sounding steadily?
    this.stability.push(
      wide && wideTarget ? { target: wideTarget, cents: centsBetween(wide.frequency, wideTarget.frequency) } : null,
    );
    const settled = this.stability.consensus();

    // 3. Mode logic.
    let pitch: Pitch | null = null;
    if (this.lock) pitch = this.trackLocked(time, audible, wide, settled);
    if (!this.lock) {
      if (wide && wideTarget) {
        pitch = { frequency: wide.frequency, target: wideTarget, source: 'wide', confidence: wide.clarity };
      }
      if (config.smartFilter === 'auto' && settled) this.engage(settled.target, settled.cents, false, time);
    }
    this.adaptComb(pitch);

    // 4. Display smoothing.
    if (!pitch) return this.reading(time, levelDb, null, null, false);
    const smoothed = this.smoother.push(pitch.target.id, centsBetween(pitch.frequency, pitch.target.frequency), time);
    return this.reading(time, levelDb, pitch, smoothed.cents, smoothed.inTune);
  }

  /** Locked mode: trust the focused detector, use the wide one to notice that the player moved on. */
  private trackLocked(
    time: number,
    audible: boolean,
    wide: PitchEstimate | null,
    settled: { target: Target; cents: number } | null,
  ): Pitch | null {
    const lock = this.lock!;
    let focus: FocusedEstimate | null = null;
    if (audible && this.ring.copyLatest(lock.frame)) focus = lock.detector.detect(lock.frame, lock.widthCents);

    const elsewhere = !lock.pinned && settled !== null && settled.target.id !== lock.target.id;

    if (focus?.valid) {
      // The wide detector landing on an overtone of what the comb tracks is its own
      // octave error – exactly the mistake the lock exists to prevent – not a new note.
      const wideAgrees = wide !== null && isOvertone(wide.frequency, focus.frequency);
      if (!elsewhere || wideAgrees) {
        lock.lastHeard = time;
        const target = this.followTarget(focus.frequency, time);
        return { frequency: focus.frequency, target, source: 'focused', confidence: focus.salience };
      }
    }

    if (elsewhere && settled && wide) {
      // A different note is clearly and steadily sounding: move the filter to it.
      this.engage(settled.target, settled.cents, false, time);
      return { frequency: wide.frequency, target: settled.target, source: 'wide', confidence: wide.clarity };
    }

    if (wide && Math.abs(centsBetween(wide.frequency, lock.target.frequency)) <= lock.captureCents) {
      // The comb lost the string (e.g. it was re-tuned in a jump) but it is still in range.
      lock.lastHeard = time;
      return { frequency: wide.frequency, target: lock.target, source: 'wide', confidence: wide.clarity };
    }

    if (!lock.pinned && (time - lock.lastHeard) * 1000 > this.config.lock.holdMs) this.lock = null;
    return null;
  }

  /**
   * Hand the lock over to a neighbouring target when the string has been tuned
   * past the midpoint (chromatic mode: into the next semitone). Returns the
   * target the current frame should be reported against.
   */
  private followTarget(frequency: number, time: number): Target {
    const lock = this.lock!;
    if (lock.pinned) return lock.target;
    const nearest = this.targets.nearest(frequency);
    const toLocked = Math.abs(centsBetween(frequency, lock.target.frequency));
    const toNearest = Math.abs(centsBetween(frequency, nearest.frequency));
    if (nearest.id === lock.target.id || toLocked - toNearest < this.config.lock.handoffHysteresisCents) {
      lock.handoffFrames = 0;
      return lock.target;
    }
    if (++lock.handoffFrames < this.config.lock.handoffFrames) return lock.target;
    this.engage(nearest, centsBetween(frequency, nearest.frequency), false, time);
    return nearest;
  }

  private engage(target: Target, offsetCents: number, pinned: boolean, time: number): void {
    const { focus } = this.config;
    const captureCents = pinned ? focus.pinnedCaptureCents : this.targets.captureCents(target);
    const key = `${target.id}:${captureCents}`;
    let detector = this.detectors.get(key);
    if (!detector) {
      detector = new FocusedDetector(this.sampleRate, target.frequency, this.preset.harmonics, {
        periods: focus.periods,
        maxFrame: Math.round((focus.maxFrameMs * this.sampleRate) / 1000),
        minPeriods: focus.minPeriods,
        minFrame: Math.round((focus.minFrameMs * this.sampleRate) / 1000),
        maxWidthCents: captureCents,
        maxPartialHz: this.config.maxPartialHz,
        minPeak: focus.minPeak,
        minSalience: focus.minSalience,
        minExplained: focus.minExplained,
        minFundamentalSupport: focus.minFundamentalSupport,
      });
      this.detectors.set(key, detector);
    }
    this.lock = {
      target,
      pinned,
      detector,
      frame: new Float32Array(detector.frameLength),
      captureCents,
      widthCents: clamp(Math.abs(offsetCents) + focus.marginCents, focus.marginCents, captureCents),
      lastHeard: time,
      misses: 0,
      handoffFrames: 0,
    };
  }

  /**
   * Keeps the comb just wide enough for where the string currently is: it opens
   * at once when the pitch moves away and closes gradually as the string nears
   * the target, so the filter is at its most selective when precision matters.
   */
  private adaptComb(pitch: Pitch | null): void {
    const lock = this.lock;
    if (!lock) return;
    const { marginCents, narrowingRate } = this.config.focus;
    if (pitch && pitch.target.id === lock.target.id) {
      const offset = Math.abs(centsBetween(pitch.frequency, lock.target.frequency));
      const wanted = clamp(offset + marginCents, marginCents, lock.captureCents);
      lock.widthCents = wanted >= lock.widthCents ? wanted : lock.widthCents + narrowingRate * (wanted - lock.widthCents);
      lock.misses = 0;
    } else if (++lock.misses >= this.config.lock.reopenAfterFrames) {
      lock.widthCents = lock.captureCents;
    }
  }

  private rebuild(): void {
    const { config, sampleRate } = this;
    this.preset = getInstrument(config.instrumentId);
    this.targets = createTargetSet(this.preset, config.a4, {
      rangeMarginCents: config.wide.rangeMarginCents,
      captureOverlapCents: config.focus.captureOverlapCents,
      maxCaptureCents: config.focus.maxCaptureCents,
    });
    const { minHz, maxHz } = this.targets;

    const longestLag = Math.ceil(sampleRate / minHz) + 4;
    const wideFrame = Math.round(
      Math.max((config.wide.minFrameMs * sampleRate) / 1000, (config.wide.periods * sampleRate) / minHz),
    );
    this.wideAnalyzer = getAnalyzer(sampleRate, nextPowerOfTwo(wideFrame + longestLag), wideFrame);
    this.wideDetector = new WideDetector(this.wideAnalyzer, minHz, maxHz, {
      peakPickRatio: config.wide.peakPickRatio,
      maxPartialHz: config.maxPartialHz,
    });
    this.widePower = new Float64Array(this.wideAnalyzer.bins);
    this.wideFrame = new Float32Array(wideFrame);

    const capacity = Math.ceil(
      Math.max(
        wideFrame,
        (config.focus.maxFrameMs * sampleRate) / 1000,
        (config.focus.minFrameMs * sampleRate) / 1000,
        (config.focus.minPeriods * sampleRate) / minHz,
      ),
    );
    if (!this.ring || this.ring.capacity !== capacity) this.ring = new RingBuffer(capacity);
    if (this.samples === 0) this.ring.clear();

    const blockMs = (this.blockSize / sampleRate) * 1000;
    this.stability = new StabilityWindow(Math.ceil(config.lock.stableMs / blockMs), config.lock.maxSpreadCents);
    this.smoother = new ReadingSmoother(config.display);
    this.detectors.clear();
    this.lock = null;

    const pinned = config.pinnedTargetId ? this.targets.byId(config.pinnedTargetId) : null;
    if (pinned) this.engage(pinned, config.focus.pinnedCaptureCents, true, this.samples / sampleRate);
    else this.config.pinnedTargetId = null;
  }

  private reading(
    time: number,
    levelDb: number,
    pitch: Pitch | null,
    cents: number | null,
    inTune: boolean,
  ): TunerReading {
    const lock = this.lock;
    return {
      time,
      mode: lock ? 'locked' : 'search',
      levelDb,
      voiced: pitch !== null,
      source: pitch?.source ?? null,
      target: pitch?.target ?? lock?.target ?? null,
      frequency: pitch && cents !== null ? pitch.target.frequency * centsToRatio(cents) : null,
      cents,
      confidence: pitch?.confidence ?? 0,
      inTune,
      lock: lock ? { target: lock.target, pinned: lock.pinned, widthCents: lock.widthCents } : null,
    };
  }
}

/** True if `frequency` is (close to) `fundamental` or one of its integer multiples. */
function isOvertone(frequency: number, fundamental: number): boolean {
  const ratio = frequency / fundamental;
  const multiple = Math.round(ratio);
  return multiple >= 1 && Math.abs(1200 * Math.log2(ratio / multiple)) <= 40;
}

function clamp(value: number, low: number, high: number): number {
  return Math.min(high, Math.max(low, value));
}
