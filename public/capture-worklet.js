// Runs on the audio thread. It does no analysis: it only groups the 128-sample
// render quanta into fixed-size blocks and hands them to the engine worker
// through a MessagePort, so the main thread is never in the audio path.
//
// Plain JavaScript in /public on purpose: worklet modules are loaded by URL
// and must work without the bundler.
class CaptureProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    this.blockSize = (options.processorOptions && options.processorOptions.blockSize) || 1024;
    this.block = new Float32Array(this.blockSize);
    this.fill = 0;
    this.sink = null;
    this.port.onmessage = (event) => {
      if (event.data.type === 'connect') this.sink = event.data.port;
      if (event.data.type === 'disconnect') this.sink = null;
    };
  }

  process(inputs) {
    const channel = inputs[0] && inputs[0][0];
    if (!channel || !this.sink) return true;
    let read = 0;
    while (read < channel.length) {
      const count = Math.min(channel.length - read, this.blockSize - this.fill);
      this.block.set(channel.subarray(read, read + count), this.fill);
      this.fill += count;
      read += count;
      if (this.fill === this.blockSize) {
        this.sink.postMessage(this.block, [this.block.buffer]);
        this.block = new Float32Array(this.blockSize);
        this.fill = 0;
      }
    }
    return true;
  }
}

registerProcessor('capture', CaptureProcessor);
