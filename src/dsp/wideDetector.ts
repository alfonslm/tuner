import { parabolicPeak, type SpectralAnalyzer } from './spectral';

export interface PitchEstimate {
  frequency: number;
  /** 0..1 periodicity of the signal at that pitch. */
  clarity: number;
}

export interface WideDetectorOptions {
  /** A peak this close to the best one wins if it is at a shorter lag (McLeod pitch method). */
  peakPickRatio: number;
  /** Partials above this are ignored. */
  maxPartialHz: number;
}

/**
 * Acquisition detector: knows nothing about the note being played and searches
 * the instrument's whole range. Used to find out which string is sounding, and
 * afterwards as a watchdog that notices when the player moves to another one.
 */
export class WideDetector {
  private readonly weights: Float64Array;
  private readonly acf: Float64Array;
  private readonly minLag: number;
  private readonly maxLag: number;

  constructor(
    private readonly analyzer: SpectralAnalyzer,
    minHz: number,
    maxHz: number,
    private readonly options: WideDetectorOptions,
  ) {
    const { sampleRate, binHz, bins } = analyzer;
    this.minLag = Math.max(2, Math.floor(sampleRate / maxHz));
    this.maxLag = Math.min(analyzer.maxLag - 2, Math.ceil(sampleRate / minHz));
    this.acf = new Float64Array(this.maxLag + 3);

    // Band-pass: drop rumble / hum below the lowest string and hiss above the useful partials.
    const lowStop = 0.5 * minHz;
    const lowPass = 0.8 * minHz;
    const highPass = Math.min(options.maxPartialHz, 0.4 * sampleRate);
    const highStop = highPass * 1.15;
    this.weights = new Float64Array(bins);
    for (let b = 1; b < bins; b++) {
      const f = b * binHz;
      let w = 1;
      if (f < lowPass) w = f <= lowStop ? 0 : 0.5 - 0.5 * Math.cos((Math.PI * (f - lowStop)) / (lowPass - lowStop));
      else if (f > highPass) w = f >= highStop ? 0 : 0.5 + 0.5 * Math.cos((Math.PI * (f - highPass)) / (highStop - highPass));
      this.weights[b] = w;
    }
  }

  detect(power: Float64Array): PitchEstimate | null {
    const { acf, minLag, maxLag } = this;
    const end = maxLag + 1;
    if (!this.analyzer.normalizedAcf(power, this.weights, end + 1, acf)) return null;

    // Skip the lobe around lag zero, then take the highest point of every positive lobe.
    let lag = 1;
    while (lag <= end && acf[lag] > 0) lag++;
    const peaks: number[] = [];
    let highest = 0;
    while (lag <= end) {
      while (lag <= end && acf[lag] <= 0) lag++;
      let best = -1;
      while (lag <= end && acf[lag] > 0) {
        if (acf[lag] >= acf[lag - 1] && acf[lag] > acf[lag + 1] && (best < 0 || acf[lag] > acf[best])) best = lag;
        lag++;
      }
      if (best >= minLag && best <= maxLag) {
        peaks.push(best);
        if (acf[best] > highest) highest = acf[best];
      }
    }
    if (peaks.length === 0) return null;

    const threshold = this.options.peakPickRatio * highest;
    for (const candidate of peaks) {
      if (acf[candidate] >= threshold) {
        const peak = parabolicPeak(acf, candidate);
        return {
          frequency: this.analyzer.sampleRate / peak.position,
          clarity: Math.min(1, peak.value),
        };
      }
    }
    return null;
  }
}
