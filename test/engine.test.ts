import { describe, expect, it } from 'vitest';
import { between, createEngine, firstLockTime, run, SAMPLE_RATE as fs } from './helpers';
import { concat, detune, mix, pluckedString, silence, whiteNoise } from './synth';

const E2 = 82.41;
const A2 = 110.0;
const G3 = 196.0;
const E4 = 329.63;

describe('acquisition and lock', () => {
  it.each([
    ['E2', 82.41],
    ['A2', 110.0],
    ['D3', 146.83],
    ['G3', 196.0],
    ['B3', 246.94],
    ['E4', 329.63],
  ])('identifies the %s string, locks onto it and reads its offset', (label, f0) => {
    const offset = -12.5;
    const readings = run(createEngine(), pluckedString({ sampleRate: fs, seconds: 1.5, frequency: detune(f0, offset) }));

    const lockedAt = firstLockTime(readings, label);
    expect(lockedAt).not.toBeNull();
    expect(lockedAt!).toBeLessThan(0.5);

    const settled = between(readings, 1.0, 1.5);
    for (const reading of settled) {
      expect(reading.mode).toBe('locked');
      expect(reading.source).toBe('focused');
      expect(reading.target?.label).toBe(label);
      expect(reading.cents!).toBeCloseTo(offset, 0);
      expect(Math.abs(reading.cents! - offset)).toBeLessThan(0.3);
    }
  });

  it('shows the wide-band reading until the note has been stable', () => {
    const readings = run(createEngine(), pluckedString({ sampleRate: fs, seconds: 1, frequency: A2 }));
    const firstVoiced = readings.find((r) => r.voiced)!;
    expect(firstVoiced.mode).toBe('search');
    expect(firstVoiced.source).toBe('wide');
    const lockedAt = firstLockTime(readings)!;
    // stableMs (180) of agreement must come first.
    expect(lockedAt - firstVoiced.time).toBeGreaterThanOrEqual(0.12);
  });

  it('stays wide-band when the smart filter is switched off', () => {
    const readings = run(createEngine({ smartFilter: 'off' }), pluckedString({ sampleRate: fs, seconds: 1, frequency: A2 }));
    expect(readings.every((r) => r.mode === 'search' && r.lock === null)).toBe(true);
    expect(between(readings, 0.5, 1).every((r) => r.source === 'wide' && r.target?.label === 'A2')).toBe(true);
  });

  it('narrows the comb as the string comes into tune', () => {
    const readings = run(createEngine(), pluckedString({ sampleRate: fs, seconds: 1.5, frequency: detune(A2, 1) }));
    const last = readings[readings.length - 1];
    expect(last.lock!.widthCents).toBeLessThan(40);
    expect(last.inTune).toBe(true);

    const far = run(createEngine(), pluckedString({ sampleRate: fs, seconds: 1.5, frequency: detune(A2, -120) }));
    expect(far[far.length - 1].lock!.widthCents).toBeGreaterThan(150);
    expect(far[far.length - 1].cents!).toBeCloseTo(-120, 0);
    expect(far[far.length - 1].inTune).toBe(false);
  });
});

describe('switching strings', () => {
  it('moves the lock when another string is plucked', () => {
    const signal = concat(
      pluckedString({ sampleRate: fs, seconds: 1.2, frequency: E2 }),
      pluckedString({ sampleRate: fs, seconds: 1.2, frequency: detune(A2, 8) }),
    );
    const readings = run(createEngine(), signal);
    expect(between(readings, 0.8, 1.2).every((r) => r.lock?.target.label === 'E2')).toBe(true);
    const relocked = readings.find((r) => r.time > 1.2 && r.lock?.target.label === 'A2');
    expect(relocked).toBeDefined();
    expect(relocked!.time - 1.2).toBeLessThan(0.5);
    // No frame may ever report the new note against the old target.
    for (const r of between(readings, 1.2, 2.4)) if (r.voiced) expect(r.target!.label).toBe(r.time < relocked!.time ? 'E2' : 'A2');
    const end = between(readings, 2.0, 2.4);
    expect(end.every((r) => r.source === 'focused' && Math.abs(r.cents! - 8) < 0.5)).toBe(true);
  });

  it('tells E2 from E4 although every partial of E4 fits the E2 comb', () => {
    const up = run(
      createEngine(),
      concat(pluckedString({ sampleRate: fs, seconds: 1, frequency: E2 }), pluckedString({ sampleRate: fs, seconds: 1, frequency: E4 })),
    );
    expect(between(up, 1.6, 2).every((r) => r.lock?.target.label === 'E4' && r.source === 'focused')).toBe(true);

    const down = run(
      createEngine(),
      concat(pluckedString({ sampleRate: fs, seconds: 1, frequency: E4 }), pluckedString({ sampleRate: fs, seconds: 1, frequency: E2 })),
    );
    expect(between(down, 1.6, 2).every((r) => r.lock?.target.label === 'E2' && r.source === 'focused')).toBe(true);
  });

  it('follows a string tuned through a semitone in chromatic mode', () => {
    // Glide from A2 -20c to A2 +90c (= A#2 -10c) over three seconds.
    const seconds = 3;
    const signal = new Float32Array(seconds * fs);
    let phase = 0;
    for (let i = 0; i < signal.length; i++) {
      const f = detune(A2, -20 + (110 * i) / signal.length);
      phase += (2 * Math.PI * f) / fs;
      signal[i] = 0.2 * (Math.sin(phase) + 0.5 * Math.sin(2 * phase) + 0.3 * Math.sin(3 * phase));
    }
    const readings = run(createEngine({ instrumentId: 'chromatic' }), signal);
    expect(between(readings, 0.5, 1).every((r) => r.lock?.target.label === 'A2')).toBe(true);
    const end = between(readings, 2.9, 3);
    expect(end.every((r) => r.lock?.target.label === 'A♯2' && r.source === 'focused')).toBe(true);
    // The target may change once, not flap back and forth around the midpoint.
    const labels = readings.filter((r) => r.lock).map((r) => r.lock!.target.label);
    expect(labels.filter((label, i) => i > 0 && label !== labels[i - 1]).length).toBe(1);
  });
});

describe('robustness', () => {
  it('keeps reading in noise where wide-band detection gives up', () => {
    const signal = mix(
      pluckedString({ sampleRate: fs, seconds: 4, frequency: detune(G3, -6), amplitude: 0.25, decay: 1.2 }),
      whiteNoise(fs, 4, 0.02),
    );
    const usable = (smartFilter: 'auto' | 'off') =>
      between(run(createEngine({ smartFilter }), signal), 1.5, 4).filter((r) => r.voiced && Math.abs(r.cents! + 6) < 3).length;
    const withFilter = usable('auto');
    const without = usable('off');
    expect(withFilter).toBeGreaterThan(60);
    expect(withFilter).toBeGreaterThan(2 * without);
  });

  it('tracks a low string whose fundamental the microphone does not pick up', () => {
    const signal = pluckedString({ sampleRate: fs, seconds: 1.5, frequency: detune(E2, 4), fundamentalGain: 0.02 });
    const settled = between(run(createEngine(), signal), 1, 1.5);
    expect(settled.every((r) => r.lock?.target.label === 'E2' && Math.abs(r.cents! - 4) < 0.5)).toBe(true);
  });

  it('reports nothing for silence or noise', () => {
    for (const signal of [silence(fs, 2), whiteNoise(fs, 2, 0.05)]) {
      const readings = run(createEngine(), signal);
      expect(readings.some((r) => r.voiced || r.lock)).toBe(false);
    }
  });

  it('holds the lock through a pause and releases it after the hold time', () => {
    const noise = whiteNoise(fs, 6, 0.003);
    const signal = mix(
      noise,
      concat(
        pluckedString({ sampleRate: fs, seconds: 1, frequency: A2 }),
        silence(fs, 1),
        pluckedString({ sampleRate: fs, seconds: 1, frequency: detune(A2, 5) }),
      ),
    );
    const readings = run(createEngine(), signal);
    // During the pause: still locked, but no needle driven by room noise.
    const pause = between(readings, 1.3, 2);
    expect(pause.every((r) => r.lock?.target.label === 'A2' && !r.voiced)).toBe(true);
    // Re-pluck: focused readings without going back through search.
    const again = between(readings, 2.3, 3);
    expect(again.every((r) => r.mode === 'locked' && r.source === 'focused' && Math.abs(r.cents! - 5) < 0.5)).toBe(true);
    // 2.5 s after the note is gone the tuner is searching again.
    expect(between(readings, 3.2, 5).some((r) => r.mode === 'locked')).toBe(true);
    expect(readings[readings.length - 1].mode).toBe('search');
  });
});

describe('pinned target', () => {
  it('stays on the chosen string and measures far-off pitches against it', () => {
    const engine = createEngine();
    const d3 = engine.getTargets().list!.find((t) => t.label === 'D3')!;
    engine.configure({ pinnedTargetId: d3.id });
    // A slack new string, 3.2 semitones flat: nearer to A2 than to D3.
    const readings = run(engine, pluckedString({ sampleRate: fs, seconds: 1.5, frequency: detune(146.83, -320) }));
    expect(readings.every((r) => r.lock?.target.label === 'D3' && r.lock.pinned)).toBe(true);
    const settled = between(readings, 1, 1.5);
    expect(settled.every((r) => r.voiced && r.target!.label === 'D3' && Math.abs(r.cents! + 320) < 1)).toBe(true);
  });

  it('ignores other strings while pinned', () => {
    const engine = createEngine();
    engine.configure({ pinnedTargetId: engine.getTargets().list![0].id });
    const readings = run(engine, pluckedString({ sampleRate: fs, seconds: 1.5, frequency: G3 }));
    expect(readings.every((r) => r.lock?.target.label === 'E2' && !r.voiced)).toBe(true);
  });

  it('ignores a lower string for its whole decay, including the tail where one partial is left', () => {
    const engine = createEngine();
    engine.configure({ pinnedTargetId: engine.getTargets().list!.find((t) => t.label === 'D3')!.id });
    const signal = mix(
      pluckedString({ sampleRate: fs, seconds: 5, frequency: detune(E2, -8), inharmonicity: 5e-5, fundamentalGain: 0.3 }),
      whiteNoise(fs, 5, 0.002),
    );
    expect(run(engine, signal).some((r) => r.voiced)).toBe(false);
  });
});
