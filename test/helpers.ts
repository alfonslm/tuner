import type { TunerConfigPatch } from '../src/engine/config';
import { TunerEngine } from '../src/engine/TunerEngine';
import type { TunerReading } from '../src/engine/types';

export const SAMPLE_RATE = 48000;
export const BLOCK = 1024;

export function createEngine(config: TunerConfigPatch = {}): TunerEngine {
  return new TunerEngine(SAMPLE_RATE, BLOCK, config);
}

/** Feeds a signal through the engine block by block, as the audio worklet would. */
export function run(engine: TunerEngine, signal: Float32Array): TunerReading[] {
  const readings: TunerReading[] = [];
  for (let offset = 0; offset + BLOCK <= signal.length; offset += BLOCK) {
    readings.push(engine.process(signal.subarray(offset, offset + BLOCK)));
  }
  return readings;
}

export function between(readings: TunerReading[], from: number, to: number): TunerReading[] {
  return readings.filter((r) => r.time >= from && r.time < to);
}

export function firstLockTime(readings: TunerReading[], label?: string): number | null {
  const hit = readings.find((r) => r.lock && (!label || r.lock.target.label === label));
  return hit ? hit.time : null;
}
