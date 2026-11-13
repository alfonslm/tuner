/**
 * Microphone → AudioWorklet. Owns the AudioContext and the media stream and
 * exposes the captured audio as a MessagePort delivering fixed-size blocks.
 */
export class MicCapture {
  private context: AudioContext | null = null;
  private stream: MediaStream | null = null;
  private node: AudioWorkletNode | null = null;

  constructor(private readonly blockSize: number) {}

  get sampleRate(): number {
    return this.context?.sampleRate ?? 0;
  }

  /** Must be called from a user gesture (browsers only start audio on one). */
  async start(): Promise<MessagePort> {
    // Create the context before the first await so it is tied to the gesture (iOS Safari).
    const context = new AudioContext({ latencyHint: 'interactive' });
    this.context = context;
    try {
      // The voice-call processing phones apply by default is poison for a tuner:
      // noise suppression treats a sustained string as noise and removes it,
      // AGC pumps the level, echo cancellation smears the phase.
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false, channelCount: 1 },
      });
      await context.audioWorklet.addModule(`${import.meta.env.BASE_URL}capture-worklet.js`);
      await context.resume();

      const source = context.createMediaStreamSource(this.stream);
      this.node = new AudioWorkletNode(context, 'capture', {
        numberOfInputs: 1,
        numberOfOutputs: 1,
        channelCount: 1,
        channelCountMode: 'explicit',
        processorOptions: { blockSize: this.blockSize },
      });
      source.connect(this.node);
      // The worklet writes no output; connecting it just keeps the graph pulling.
      this.node.connect(context.destination);

      const channel = new MessageChannel();
      this.node.port.postMessage({ type: 'connect', port: channel.port1 }, [channel.port1]);
      return channel.port2;
    } catch (error) {
      await this.stop();
      throw error;
    }
  }

  async stop(): Promise<void> {
    this.node?.port.postMessage({ type: 'disconnect' });
    this.node?.disconnect();
    this.node = null;
    this.stream?.getTracks().forEach((track) => track.stop());
    this.stream = null;
    const context = this.context;
    this.context = null;
    if (context && context.state !== 'closed') await context.close();
  }
}
