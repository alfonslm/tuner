import type { Target } from '../music/targets';

interface Observation {
  target: Target;
  cents: number;
}

/**
 * Answers "has the same note been sounding steadily?" over the last N frames
 * of the wide detector. This is the trigger that engages the smart filter and
 * the evidence needed to move it to another string.
 */
export class StabilityWindow {
  private readonly slots: (Observation | null)[];
  private next = 0;

  constructor(
    frames: number,
    private readonly maxSpreadCents: number,
  ) {
    this.slots = new Array(Math.max(2, frames)).fill(null);
  }

  /** Records one frame; `null` for frames without a clear pitch. */
  push(observation: Observation | null): void {
    this.slots[this.next] = observation;
    this.next = (this.next + 1) % this.slots.length;
  }

  clear(): void {
    this.slots.fill(null);
  }

  /** The target every recent frame agreed on, with the mean offset from it, or null. */
  consensus(): { target: Target; cents: number } | null {
    const first = this.slots[0];
    if (!first) return null;
    let low = Infinity;
    let high = -Infinity;
    let sum = 0;
    for (const slot of this.slots) {
      if (!slot || slot.target.id !== first.target.id) return null;
      low = Math.min(low, slot.cents);
      high = Math.max(high, slot.cents);
      sum += slot.cents;
    }
    if (high - low > this.maxSpreadCents) return null;
    return { target: first.target, cents: sum / this.slots.length };
  }
}
