/// <reference lib="webworker" />
import { TunerEngine } from '../engine/TunerEngine';
import type { EngineEvent, EngineRequest } from './protocol';

// Hosts the engine off the main thread. Audio comes in on a port owned by the
// capture worklet; readings go out to the UI.
let engine: TunerEngine | null = null;
let audio: MessagePort | null = null;

self.onmessage = (event: MessageEvent<EngineRequest>) => {
  const message = event.data;
  switch (message.type) {
    case 'start': {
      audio?.close();
      const current = new TunerEngine(message.sampleRate, message.blockSize, message.config);
      engine = current;
      audio = message.audio;
      audio.onmessage = (block: MessageEvent<Float32Array>) => {
        const reply: EngineEvent = { type: 'reading', reading: current.process(block.data) };
        self.postMessage(reply);
      };
      break;
    }
    case 'configure':
      engine?.configure(message.patch);
      break;
    case 'stop':
      audio?.close();
      audio = null;
      engine = null;
      break;
  }
};
