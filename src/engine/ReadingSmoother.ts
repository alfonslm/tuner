/**
 * Turns per-frame cent offsets into a steady needle: a short median removes
 * single-frame outliers (pluck transients), an exponential average removes
 * jitter. Smoothing restarts when the target changes or after a pause.
 */
export class ReadingSmoother {
  private readonly recent: number[] = [];
  private readonly sorted: number[] = [];
  private value: number | null = null;
  private targetId: string | null = null;
  private lastTime = -Infinity;
  private inTuneSince: number | null = null;

  constructor(
    private readonly options: {
      medianFrames: number;
      smoothing: number;
      resetGapMs: number;
      inTuneCents: number;
      inTuneHoldMs: number;
    },
  ) {}

  reset(): void {
    this.recent.length = 0;
    this.value = null;
    this.targetId = null;
    this.inTuneSince = null;
  }

  push(targetId: string, cents: number, time: number): { cents: number; inTune: boolean } {
    const { options } = this;
    if (targetId !== this.targetId || (time - this.lastTime) * 1000 > options.resetGapMs) {
      this.reset();
      this.targetId = targetId;
    }
    this.lastTime = time;

    this.recent.push(cents);
    if (this.recent.length > options.medianFrames) this.recent.shift();
    this.sorted.length = 0;
    this.sorted.push(...this.recent);
    this.sorted.sort((a, b) => a - b);
    const middle = this.sorted.length >> 1;
    const median = this.sorted.length % 2 ? this.sorted[middle] : 0.5 * (this.sorted[middle - 1] + this.sorted[middle]);

    this.value = this.value === null ? median : this.value + options.smoothing * (median - this.value);

    if (Math.abs(this.value) <= options.inTuneCents) this.inTuneSince ??= time;
    else this.inTuneSince = null;
    const inTune = this.inTuneSince !== null && (time - this.inTuneSince) * 1000 >= options.inTuneHoldMs;
    return { cents: this.value, inTune };
  }
}
