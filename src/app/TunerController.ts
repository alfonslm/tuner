import { MicCapture } from '../audio/MicCapture';
import { DEFAULT_CONFIG, mergeConfig, type TunerConfig, type TunerConfigPatch } from '../engine/config';
import type { TunerReading } from '../engine/types';
import type { EngineEvent, EngineRequest } from '../worker/protocol';

export type TunerStatus = 'idle' | 'starting' | 'running' | 'error';

const BLOCK_SIZE = 1024;

/**
 * Application façade used by the UI: wires microphone → worklet → engine
 * worker and exposes start/stop, configuration and a stream of readings.
 */
export class TunerController {
  onReading: (reading: TunerReading) => void = () => {};
  onStatus: (status: TunerStatus, error?: unknown) => void = () => {};

  private config: TunerConfig;
  private readonly capture = new MicCapture(BLOCK_SIZE);
  private worker: Worker | null = null;
  private status: TunerStatus = 'idle';

  constructor(config: TunerConfigPatch = {}) {
    this.config = mergeConfig(DEFAULT_CONFIG, config);
  }

  getConfig(): TunerConfig {
    return this.config;
  }

  getStatus(): TunerStatus {
    return this.status;
  }

  async start(): Promise<void> {
    if (this.status === 'starting' || this.status === 'running') return;
    this.setStatus('starting');
    try {
      const audio = await this.capture.start();
      this.worker = new Worker(new URL('../worker/engine.worker.ts', import.meta.url), { type: 'module' });
      this.worker.onmessage = (event: MessageEvent<EngineEvent>) => {
        if (event.data.type === 'reading') this.onReading(event.data.reading);
      };
      this.send(
        { type: 'start', sampleRate: this.capture.sampleRate, blockSize: BLOCK_SIZE, config: this.config, audio },
        [audio],
      );
      this.setStatus('running');
    } catch (error) {
      await this.teardown();
      this.setStatus('error', error);
    }
  }

  async stop(): Promise<void> {
    await this.teardown();
    this.setStatus('idle');
  }

  configure(patch: TunerConfigPatch): void {
    const instrumentChanged = patch.instrumentId !== undefined && patch.instrumentId !== this.config.instrumentId;
    this.config = mergeConfig(this.config, patch);
    // Target ids belong to an instrument; a pin cannot survive a change of instrument.
    if (instrumentChanged && patch.pinnedTargetId === undefined) this.config.pinnedTargetId = null;
    this.send({ type: 'configure', patch: { ...patch, pinnedTargetId: this.config.pinnedTargetId } });
  }

  private send(message: EngineRequest, transfer: Transferable[] = []): void {
    this.worker?.postMessage(message, transfer);
  }

  private async teardown(): Promise<void> {
    this.send({ type: 'stop' });
    this.worker?.terminate();
    this.worker = null;
    await this.capture.stop();
  }

  private setStatus(status: TunerStatus, error?: unknown): void {
    this.status = status;
    this.onStatus(status, error);
  }
}
