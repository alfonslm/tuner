import { FFT } from './fft';

/**
 * Shared front end of both detectors.
 *
 * One Hann-windowed FFT gives the power spectrum P(f). Every pitch estimate is
 * then taken from an autocorrelation computed as IFFT(P · W), where W is a
 * spectral weighting: a plain band-pass for the wide detector, a harmonic comb
 * for the focused one. Filtering therefore costs one multiply per bin.
 *
 * The frame is shorter than the FFT (`frameLength + maxLag <= fftSize`) so the
 * autocorrelation never wraps around, and it is divided by the window's own
 * autocorrelation so the taper does not pull peaks towards shorter lags.
 */
export class SpectralAnalyzer {
  readonly bins: number;
  readonly binHz: number;
  readonly maxLag: number;
  /** Half-width of the window's spectral main lobe, in Hz. */
  readonly lobeHz: number;

  private readonly fft: FFT;
  private readonly window: Float64Array;
  private readonly windowAcf: Float64Array;
  private readonly re: Float64Array;
  private readonly im: Float64Array;

  constructor(
    readonly sampleRate: number,
    readonly fftSize: number,
    readonly frameLength: number,
  ) {
    if (frameLength >= fftSize) throw new Error('frameLength must be smaller than fftSize');
    this.fft = new FFT(fftSize);
    this.bins = fftSize / 2 + 1;
    this.binHz = sampleRate / fftSize;
    // Beyond half the frame the window overlap is too small to normalise reliably.
    this.maxLag = Math.min(fftSize - frameLength, frameLength >> 1);
    this.lobeHz = (2 * sampleRate) / frameLength;
    this.re = new Float64Array(fftSize);
    this.im = new Float64Array(fftSize);

    this.window = new Float64Array(frameLength);
    for (let i = 0; i < frameLength; i++) {
      this.window[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / frameLength);
    }
    // Window autocorrelation via the same FFT (no wrap-around for lags <= fftSize - frameLength).
    this.windowAcf = new Float64Array(this.maxLag + 1);
    this.re.set(this.window);
    this.fft.forward(this.re, this.im);
    for (let b = 0; b < fftSize; b++) {
      this.re[b] = this.re[b] * this.re[b] + this.im[b] * this.im[b];
      this.im[b] = 0;
    }
    this.fft.forward(this.re, this.im);
    for (let lag = 0; lag <= this.maxLag; lag++) this.windowAcf[lag] = this.re[lag] / fftSize;
  }

  /**
   * Windowed power spectrum of `frame` (exactly `frameLength` samples) written to
   * `out` (`bins` values). Returns the RMS of the frame.
   */
  powerSpectrum(frame: ArrayLike<number>, out: Float64Array): number {
    const { re, im, window, frameLength } = this;
    let mean = 0;
    for (let i = 0; i < frameLength; i++) mean += frame[i];
    mean /= frameLength;
    let energy = 0;
    for (let i = 0; i < frameLength; i++) {
      const sample = frame[i] - mean;
      energy += sample * sample;
      re[i] = sample * window[i];
    }
    re.fill(0, frameLength);
    im.fill(0);
    this.fft.forward(re, im);
    for (let b = 0; b < this.bins; b++) out[b] = re[b] * re[b] + im[b] * im[b];
    return Math.sqrt(energy / frameLength);
  }

  /**
   * Normalised autocorrelation of the signal after weighting its power spectrum
   * by `weights`. `out[lag]` is ~1 at lags where the filtered signal repeats.
   * Returns false if the weighted spectrum is empty.
   */
  normalizedAcf(power: Float64Array, weights: Float64Array, maxLag: number, out: Float64Array): boolean {
    const { re, im, fftSize, windowAcf } = this;
    const half = fftSize >> 1;
    re[0] = 0; // DC never carries pitch.
    for (let b = 1; b <= half; b++) {
      const value = power[b] * weights[b];
      re[b] = value;
      re[fftSize - b] = value;
    }
    im.fill(0);
    // The spectrum is real and even, so the forward transform equals the inverse.
    this.fft.forward(re, im);
    const zeroLag = re[0];
    if (!(zeroLag > 0)) return false;
    const limit = Math.min(maxLag, this.maxLag);
    const scale = windowAcf[0] / zeroLag;
    for (let lag = 0; lag <= limit; lag++) out[lag] = (re[lag] * scale) / windowAcf[lag];
    return true;
  }
}

const analyzers = new Map<string, SpectralAnalyzer>();

/** Analyzers hold FFT tables and scratch buffers, so they are shared per geometry. */
export function getAnalyzer(sampleRate: number, fftSize: number, frameLength: number): SpectralAnalyzer {
  const key = `${sampleRate}:${fftSize}:${frameLength}`;
  let analyzer = analyzers.get(key);
  if (!analyzer) {
    analyzer = new SpectralAnalyzer(sampleRate, fftSize, frameLength);
    analyzers.set(key, analyzer);
  }
  return analyzer;
}

/** Refines the position of a sampled peak at integer index `i` with a parabola through its neighbours. */
export function parabolicPeak(values: ArrayLike<number>, i: number): { position: number; value: number } {
  const a = values[i - 1];
  const b = values[i];
  const c = values[i + 1];
  const denominator = a - 2 * b + c;
  if (denominator >= 0) return { position: i, value: b };
  const offset = (0.5 * (a - c)) / denominator;
  return { position: i + offset, value: b - 0.25 * (a - c) * offset };
}
