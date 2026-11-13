import type { TunerConfig, TunerConfigPatch } from '../engine/config';
import type { TunerReading } from '../engine/types';

/** Main thread → engine worker. */
export type EngineRequest =
  | {
      type: 'start';
      sampleRate: number;
      blockSize: number;
      config: TunerConfig;
      /** Audio blocks (Float32Array of `blockSize` samples) arrive here straight from the worklet. */
      audio: MessagePort;
    }
  | { type: 'configure'; patch: TunerConfigPatch }
  | { type: 'stop' };

/** Engine worker → main thread. */
export type EngineEvent = { type: 'reading'; reading: TunerReading };
