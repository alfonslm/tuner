import { describe, expect, it } from 'vitest';
import { FFT, nextPowerOfTwo } from '../src/dsp/fft';
import { FocusedDetector, type FocusedDetectorOptions } from '../src/dsp/focusedDetector';
import { buildComb, harmonicProfile, partialFrequency } from '../src/dsp/harmonicComb';
import { getAnalyzer } from '../src/dsp/spectral';
import { WideDetector } from '../src/dsp/wideDetector';
import { cents, detune, hum, mix, pluckedString, whiteNoise } from './synth';

const fs = 48000;
const guitar = { partials: 8, inharmonicity: 5e-5 };
const focusOptions: FocusedDetectorOptions = {
  periods: 16,
  maxFrame: 7200,
  minPeriods: 10,
  minFrame: 1920,
  maxWidthCents: 280,
  maxPartialHz: 5000,
  minPeak: 0.5,
  minSalience: 0.5,
  minExplained: 0.8,
  minFundamentalSupport: 0.06,
};

function wideDetector(minHz: number, maxHz: number) {
  const frame = Math.round((8 * fs) / minHz);
  const analyzer = getAnalyzer(fs, nextPowerOfTwo(frame + Math.ceil(fs / minHz) + 4), frame);
  const detector = new WideDetector(analyzer, minHz, maxHz, { peakPickRatio: 0.9, maxPartialHz: 5000 });
  const power = new Float64Array(analyzer.bins);
  return (signal: Float32Array, at: number) => {
    analyzer.powerSpectrum(signal.subarray(at - frame, at), power);
    return detector.detect(power);
  };
}

function focused(signal: Float32Array, at: number, targetHz: number, widthCents: number) {
  const detector = new FocusedDetector(fs, targetHz, guitar, focusOptions);
  return detector.detect(signal.subarray(at - detector.frameLength, at), widthCents);
}

const STRINGS: [string, number][] = [
  ['E2', 82.41],
  ['A2', 110.0],
  ['D3', 146.83],
  ['G3', 196.0],
  ['B3', 246.94],
  ['E4', 329.63],
];

describe('FFT', () => {
  it('puts a sinusoid in the right bin', () => {
    const n = 64;
    const fft = new FFT(n);
    const re = new Float64Array(n);
    const im = new Float64Array(n);
    for (let i = 0; i < n; i++) re[i] = Math.cos((2 * Math.PI * 5 * i) / n);
    fft.forward(re, im);
    for (let b = 0; b < n; b++) {
      const magnitude = Math.hypot(re[b], im[b]);
      expect(magnitude).toBeCloseTo(b === 5 || b === n - 5 ? n / 2 : 0, 8);
    }
  });
});

describe('harmonic comb', () => {
  const geometry = { binHz: fs / 8192, lobeHz: (2 * fs) / 7200, maxHz: 5000 };

  it('passes the partials of the target and blocks what lies between them', () => {
    const comb = new Float64Array(4097);
    buildComb(comb, 110, 35, guitar, geometry);
    const at = (hz: number) => comb[Math.round(hz / geometry.binHz)];
    for (let k = 1; k <= 8; k++) {
      expect(at(partialFrequency(110, k, guitar.inharmonicity))).toBeCloseTo(1 / (k * k), 6);
      expect(at(110 * (k + 0.5))).toBe(0);
    }
    expect(at(50)).toBe(0); // mains hum
    expect(at(4000)).toBe(0); // above the modelled partials
  });

  it('widens with the allowed detuning', () => {
    const narrow = new Float64Array(4097);
    const wide = new Float64Array(4097);
    buildComb(narrow, 110, 35, guitar, geometry);
    buildComb(wide, 110, 200, guitar, geometry);
    const open = (comb: Float64Array) => comb.reduce((n, v) => n + (v > 0 ? 1 : 0), 0);
    expect(open(wide)).toBeGreaterThan(1.5 * open(narrow));
  });

  it('recognises white noise as not string-like', () => {
    const analyzer = getAnalyzer(fs, 8192, 7200);
    const power = new Float64Array(analyzer.bins);
    for (let seed = 1; seed <= 10; seed++) {
      analyzer.powerSpectrum(whiteNoise(fs, 0.2, 0.05, seed), power);
      const profile = harmonicProfile(power, 110, guitar, { binHz: analyzer.binHz, lobeHz: analyzer.lobeHz, maxHz: 5000 });
      expect(profile.salience).toBeLessThan(0.25);
    }
  });
});

describe('wide detector', () => {
  const detect = wideDetector(82.41 / 1.19, 329.63 * 1.19);

  it.each(STRINGS)('finds %s anywhere within a semitone', (_name, f0) => {
    for (const offset of [-45, -12, 0, 7, 38]) {
      const f = detune(f0, offset);
      const estimate = detect(pluckedString({ sampleRate: fs, seconds: 0.5, frequency: f }), 20000);
      expect(estimate).not.toBeNull();
      expect(Math.abs(cents(estimate!.frequency, f))).toBeLessThan(0.2);
      expect(estimate!.clarity).toBeGreaterThan(0.95);
    }
  });

  it('is not fooled by a missing fundamental', () => {
    const signal = pluckedString({ sampleRate: fs, seconds: 0.5, frequency: 82.41, fundamentalGain: 0 });
    expect(Math.abs(cents(detect(signal, 20000)!.frequency, 82.41))).toBeLessThan(0.5);
  });

  it('reports low clarity for noise', () => {
    const estimate = detect(whiteNoise(fs, 0.5, 0.1), 20000);
    expect(estimate === null || estimate.clarity < 0.6).toBe(true);
  });
});

describe('focused detector', () => {
  it.each(STRINGS)('measures %s to a small fraction of a cent', (_name, f0) => {
    for (const offset of [-30, -4.2, 0, 1.5, 30]) {
      const f = detune(f0, offset);
      const estimate = focused(pluckedString({ sampleRate: fs, seconds: 0.5, frequency: f }), 20000, f0, Math.abs(offset) + 35);
      expect(estimate?.valid).toBe(true);
      expect(Math.abs(cents(estimate!.frequency, f))).toBeLessThan(0.1);
    }
  });

  it('is pulled less by stiff-string inharmonicity than the wide detector', () => {
    const detect = wideDetector(82.41 / 1.19, 329.63 * 1.19);
    const signal = pluckedString({ sampleRate: fs, seconds: 0.5, frequency: 110, inharmonicity: 1e-4 });
    const wideError = Math.abs(cents(detect(signal, 20000)!.frequency, 110));
    const focusError = Math.abs(cents(focused(signal, 20000, 110, 35)!.frequency, 110));
    expect(focusError).toBeLessThan(1);
    expect(focusError).toBeLessThan(wideError / 3);
  });

  it('keeps working in noise that defeats the wide detector', () => {
    const detect = wideDetector(82.41 / 1.19, 329.63 * 1.19);
    let wideHits = 0;
    let focusHits = 0;
    for (let seed = 1; seed <= 20; seed++) {
      const f = detune(196, 6);
      const signal = mix(
        pluckedString({ sampleRate: fs, seconds: 0.5, frequency: f, amplitude: 0.1, fundamentalGain: 0.3, seed }),
        whiteNoise(fs, 0.5, 0.04, seed * 13),
        hum(fs, 0.5, 50, 0.005),
      );
      const wide = detect(signal, 20000);
      if (wide && wide.clarity >= 0.85 && Math.abs(cents(wide.frequency, f)) < 5) wideHits++;
      const focus = focused(signal, 20000, 196, 45);
      if (focus?.valid && Math.abs(cents(focus.frequency, f)) < 5) focusHits++;
    }
    expect(wideHits).toBeLessThan(5);
    expect(focusHits).toBeGreaterThanOrEqual(19);
  });

  it('never validates pure noise', () => {
    for (let seed = 1; seed <= 30; seed++) {
      const noise = whiteNoise(fs, 0.5, 0.05, seed);
      for (const [, f0] of STRINGS) {
        for (const width of [35, 280]) expect(focused(noise, 20000, f0, width)?.valid ?? false).toBe(false);
      }
    }
  });

  it('does not take the fading second partial of a lower string for the target', () => {
    // Late in the decay of a low E, on a mic that barely hears 82 Hz, the sound is
    // mostly its 165 Hz partial – which is within reach of a comb opened wide around D3.
    const tail = pluckedString({
      sampleRate: fs,
      seconds: 0.5,
      frequency: 82.41,
      partialAmplitudes: [0.5, 1, 0.45, 0.2, 0.04],
      decay: 30,
    });
    const detector = new FocusedDetector(fs, 146.83, guitar, { ...focusOptions, maxWidthCents: 400 });
    const estimate = detector.detect(tail.subarray(20000 - detector.frameLength, 20000), 400);
    expect(estimate?.valid ?? false).toBe(false);
  });

  it('rejects every other string of the instrument, even with the comb wide open', () => {
    for (const [lockedName, lockedHz] of STRINGS) {
      for (const [playedName, playedHz] of STRINGS) {
        const signal = pluckedString({ sampleRate: fs, seconds: 0.5, frequency: playedHz, inharmonicity: 5e-5 });
        const estimate = focused(signal, 20000, lockedHz, 280);
        expect(estimate?.valid ?? false, `locked ${lockedName}, played ${playedName}`).toBe(lockedName === playedName);
      }
    }
  });
});
