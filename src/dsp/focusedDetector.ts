import { nextPowerOfTwo } from './fft';
import { buildComb, harmonicProfile, type CombGeometry, type HarmonicModel } from './harmonicComb';
import { getAnalyzer, parabolicPeak, type SpectralAnalyzer } from './spectral';

export interface FocusedDetectorOptions {
  /** Preferred analysis window length, in periods of the target note... */
  periods: number;
  /** ...capped to this many samples (keeps low notes responsive)... */
  maxFrame: number;
  /** ...but never shorter than this many periods or samples. */
  minPeriods: number;
  minFrame: number;
  /** Widest half-width (cents) the comb will ever be asked for. */
  maxWidthCents: number;
  maxPartialHz: number;
  minPeak: number;
  minSalience: number;
  minExplained: number;
  minFundamentalSupport: number;
}

export interface FocusedEstimate {
  frequency: number;
  /** 0..1 – how string-like and on-target the sound is. */
  salience: number;
  explained: number;
  /** False when a pitch was found but the sound does not look like the target string. */
  valid: boolean;
}

/**
 * Tracking detector: assumes the string being tuned is close to `targetHz`.
 *
 * That assumption buys three things:
 *  - a harmonic comb that only lets through the partials such a string can
 *    have (noise, hum, speech and other strings are removed before estimation),
 *  - a pitch search restricted to the neighbourhood of the target, which makes
 *    octave errors impossible,
 *  - an analysis window sized for this one note (long for low strings, short
 *    for high ones) instead of a compromise for the whole instrument.
 *
 * The price is that it will "find" something near the target in almost any
 * input, so every estimate is checked against the unfiltered spectrum.
 */
export class FocusedDetector {
  readonly analyzer: SpectralAnalyzer;
  private readonly geometry: CombGeometry;
  private readonly power: Float64Array;
  private readonly comb: Float64Array;
  private readonly acf: Float64Array;

  constructor(
    sampleRate: number,
    readonly targetHz: number,
    private readonly model: HarmonicModel,
    private readonly options: FocusedDetectorOptions,
  ) {
    const longestLag = Math.ceil((sampleRate / targetHz) * Math.pow(2, options.maxWidthCents / 1200)) + 4;
    const period = sampleRate / targetHz;
    const frame = Math.round(
      Math.max(options.minFrame, options.minPeriods * period, Math.min(options.maxFrame, options.periods * period)),
    );
    this.analyzer = getAnalyzer(sampleRate, nextPowerOfTwo(frame + longestLag), frame);
    this.geometry = {
      binHz: this.analyzer.binHz,
      lobeHz: this.analyzer.lobeHz,
      maxHz: Math.min(options.maxPartialHz, 0.4 * sampleRate),
    };
    this.power = new Float64Array(this.analyzer.bins);
    this.comb = new Float64Array(this.analyzer.bins);
    this.acf = new Float64Array(this.analyzer.maxLag + 1);
  }

  get frameLength(): number {
    return this.analyzer.frameLength;
  }

  /**
   * @param frame the most recent `frameLength` samples
   * @param halfWidthCents how far from the target the string may currently be
   */
  detect(frame: ArrayLike<number>, halfWidthCents: number): FocusedEstimate | null {
    const { analyzer, power, comb, acf, options } = this;
    const width = Math.min(halfWidthCents, options.maxWidthCents);
    analyzer.powerSpectrum(frame, power);
    buildComb(comb, this.targetHz, width, this.model, this.geometry);

    const ratio = Math.pow(2, width / 1200);
    const period = analyzer.sampleRate / this.targetHz;
    const from = Math.max(2, Math.floor(period / ratio));
    const to = Math.min(analyzer.maxLag - 1, Math.ceil(period * ratio));
    if (!analyzer.normalizedAcf(power, comb, to + 1, acf)) return null;

    let best = -1;
    for (let lag = from; lag <= to; lag++) {
      if (acf[lag] >= acf[lag - 1] && acf[lag] > acf[lag + 1] && (best < 0 || acf[lag] > acf[best])) best = lag;
    }
    if (best < 0) return null;

    const peak = parabolicPeak(acf, best);
    const frequency = analyzer.sampleRate / peak.position;
    const profile = harmonicProfile(power, frequency, this.model, this.geometry);
    return {
      frequency,
      salience: profile.salience,
      explained: profile.explained,
      valid:
        peak.value >= options.minPeak &&
        profile.salience >= options.minSalience &&
        profile.explained >= options.minExplained &&
        profile.fundamentalSupport >= options.minFundamentalSupport,
    };
  }
}
