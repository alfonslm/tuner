/** Deterministic test signals: a plucked-string model plus the things a phone mic adds. */

export function rng(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export interface StringOptions {
  sampleRate: number;
  seconds: number;
  frequency: number;
  amplitude?: number;
  partials?: number;
  inharmonicity?: number;
  /** Relative pluck position along the string (0.5 kills even partials). */
  pluckPosition?: number;
  /** Amplitude decay time of the fundamental in seconds; upper partials die faster. */
  decay?: number;
  /** Gain applied to the fundamental only – small phone mics barely pick up low notes. */
  fundamentalGain?: number;
  /** Explicit per-partial amplitudes; overrides the pluck model. */
  partialAmplitudes?: number[];
  seed?: number;
}

export function pluckedString(options: StringOptions): Float32Array {
  const {
    sampleRate,
    seconds,
    frequency,
    amplitude = 0.2,
    partials = 12,
    inharmonicity = 0,
    pluckPosition = 0.18,
    decay = 2.5,
    fundamentalGain = 1,
    partialAmplitudes,
    seed = 1,
  } = options;
  const random = rng(seed);
  const length = Math.round(seconds * sampleRate);
  const out = new Float32Array(length);
  const count = partialAmplitudes?.length ?? partials;
  for (let k = 1; k <= count; k++) {
    const f = k * frequency * Math.sqrt(1 + inharmonicity * k * k);
    if (f > 0.45 * sampleRate) break;
    let a = partialAmplitudes ? partialAmplitudes[k - 1] : Math.abs(Math.sin(k * Math.PI * pluckPosition)) / k;
    if (k === 1) a *= fundamentalGain;
    if (a === 0) continue;
    const phase = 2 * Math.PI * random();
    const omega = (2 * Math.PI * f) / sampleRate;
    const tau = (decay / (1 + 0.25 * (k - 1))) * sampleRate;
    for (let i = 0; i < length; i++) out[i] += a * Math.exp(-i / tau) * Math.sin(omega * i + phase);
  }
  let peak = 0;
  for (let i = 0; i < length; i++) peak = Math.max(peak, Math.abs(out[i]));
  if (peak > 0) for (let i = 0; i < length; i++) out[i] *= amplitude / peak;
  return out;
}

export function whiteNoise(sampleRate: number, seconds: number, rms: number, seed = 7): Float32Array {
  const random = rng(seed);
  const out = new Float32Array(Math.round(seconds * sampleRate));
  for (let i = 0; i < out.length; i++) {
    // Sum of uniforms ≈ Gaussian.
    out[i] = rms * (random() + random() + random() + random() - 2) * Math.sqrt(3);
  }
  return out;
}

export function hum(sampleRate: number, seconds: number, frequency: number, amplitude: number): Float32Array {
  const out = new Float32Array(Math.round(seconds * sampleRate));
  for (let i = 0; i < out.length; i++) {
    const t = i / sampleRate;
    out[i] =
      amplitude *
      (Math.sin(2 * Math.PI * frequency * t) +
        0.5 * Math.sin(2 * Math.PI * 2 * frequency * t + 1) +
        0.3 * Math.sin(2 * Math.PI * 3 * frequency * t + 2));
  }
  return out;
}

export function silence(sampleRate: number, seconds: number): Float32Array {
  return new Float32Array(Math.round(seconds * sampleRate));
}

/** Sample-wise sum; the result is as long as the longest input. */
export function mix(...signals: Float32Array[]): Float32Array {
  const out = new Float32Array(Math.max(...signals.map((s) => s.length)));
  for (const signal of signals) for (let i = 0; i < signal.length; i++) out[i] += signal[i];
  return out;
}

export function concat(...signals: Float32Array[]): Float32Array {
  const out = new Float32Array(signals.reduce((n, s) => n + s.length, 0));
  let offset = 0;
  for (const signal of signals) {
    out.set(signal, offset);
    offset += signal.length;
  }
  return out;
}

export function cents(frequency: number, reference: number): number {
  return 1200 * Math.log2(frequency / reference);
}

export function detune(frequency: number, offsetCents: number): number {
  return frequency * Math.pow(2, offsetCents / 1200);
}
