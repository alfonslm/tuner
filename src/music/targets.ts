import type { InstrumentPreset } from './instruments';
import { centsBetween, frequencyToMidi, midiLabel, midiToFrequency, parseNote } from './notes';

/** A note the user may be tuning to. */
export interface Target {
  /** Stable identity, e.g. "s0:E2" for a string or "m45" for a chromatic note. */
  id: string;
  label: string;
  midi: number;
  frequency: number;
}

/**
 * The set of notes the tuner snaps to: either the strings of an instrument or
 * every semitone (chromatic). The lock logic only talks to this interface, so
 * alternate tunings or custom temperaments are just another TargetSet.
 */
export interface TargetSet {
  /** Explicit targets in display order, or `null` for chromatic. */
  readonly list: Target[] | null;
  /** Frequency range the wide-band detector has to cover. */
  readonly minHz: number;
  readonly maxHz: number;
  nearest(frequency: number): Target;
  byId(id: string): Target | null;
  /** How far (in cents) a string may be from `target` and still be tracked by the focused filter. */
  captureCents(target: Target): number;
}

export interface TargetSetOptions {
  /** Extra room beyond the outermost strings that the wide detector still covers. */
  rangeMarginCents: number;
  /** Capture reaches this far past the midpoint to the neighbouring target (hand-off hysteresis room). */
  captureOverlapCents: number;
  maxCaptureCents: number;
}

export function createTargetSet(
  preset: InstrumentPreset,
  a4: number,
  options: TargetSetOptions,
): TargetSet {
  const margin = Math.pow(2, options.rangeMarginCents / 1200);

  if (!preset.strings) {
    const [low, high] = preset.chromaticRange ?? ['C2', 'C7'];
    const lowMidi = parseNote(low);
    const highMidi = parseNote(high);
    const make = (midi: number): Target => ({
      id: `m${midi}`,
      label: midiLabel(midi),
      midi,
      frequency: midiToFrequency(midi, a4),
    });
    return {
      list: null,
      // A semitone grid needs no extra margin: half a semitone beyond the end notes is enough.
      minHz: midiToFrequency(lowMidi - 0.75, a4),
      maxHz: midiToFrequency(highMidi + 0.75, a4),
      nearest: (frequency) =>
        make(Math.min(highMidi, Math.max(lowMidi, Math.round(frequencyToMidi(frequency, a4))))),
      byId: (id) => (/^m\d+$/.test(id) ? make(Number(id.slice(1))) : null),
      captureCents: () => Math.min(options.maxCaptureCents, 50 + options.captureOverlapCents),
    };
  }

  const list: Target[] = preset.strings.map((note, index) => {
    const midi = parseNote(note);
    return { id: `s${index}:${note}`, label: midiLabel(midi), midi, frequency: midiToFrequency(midi, a4) };
  });
  const frequencies = list.map((t) => t.frequency);

  return {
    list,
    minHz: Math.min(...frequencies) / margin,
    maxHz: Math.max(...frequencies) * margin,
    nearest(frequency) {
      let best = list[0];
      let bestDistance = Infinity;
      for (const target of list) {
        const distance = Math.abs(centsBetween(frequency, target.frequency));
        if (distance < bestDistance) {
          best = target;
          bestDistance = distance;
        }
      }
      return best;
    },
    byId: (id) => list.find((t) => t.id === id) ?? null,
    captureCents(target) {
      let nearestNeighbour = Infinity;
      for (const other of list) {
        if (other.id === target.id) continue;
        const distance = Math.abs(centsBetween(other.frequency, target.frequency));
        // Unison / octave courses (e.g. 12-string) must not collapse the capture range.
        if (distance > 1) nearestNeighbour = Math.min(nearestNeighbour, distance);
      }
      return Math.min(options.maxCaptureCents, nearestNeighbour / 2 + options.captureOverlapCents);
    },
  };
}
