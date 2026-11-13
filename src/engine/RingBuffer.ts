/** Fixed-size history of the most recent samples. */
export class RingBuffer {
  private readonly data: Float32Array;
  private writeIndex = 0;
  private filled = 0;

  constructor(readonly capacity: number) {
    this.data = new Float32Array(capacity);
  }

  get length(): number {
    return this.filled;
  }

  push(block: ArrayLike<number>): void {
    const { data, capacity } = this;
    const start = Math.max(0, block.length - capacity);
    for (let i = start; i < block.length; i++) {
      data[this.writeIndex] = block[i];
      this.writeIndex = this.writeIndex + 1 === capacity ? 0 : this.writeIndex + 1;
    }
    this.filled = Math.min(capacity, this.filled + block.length - start);
  }

  /** Copies the newest `out.length` samples (oldest first). Returns false if not enough have arrived yet. */
  copyLatest(out: Float32Array): boolean {
    const count = out.length;
    if (count > this.filled) return false;
    let index = this.writeIndex - count;
    if (index < 0) index += this.capacity;
    const firstPart = Math.min(count, this.capacity - index);
    out.set(this.data.subarray(index, index + firstPart));
    if (firstPart < count) out.set(this.data.subarray(0, count - firstPart), firstPart);
    return true;
  }

  clear(): void {
    this.data.fill(0);
    this.writeIndex = 0;
    this.filled = 0;
  }
}
