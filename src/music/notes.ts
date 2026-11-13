/** Note / frequency helpers (12-tone equal temperament). */

export const DEFAULT_A4 = 440;

const NAMES = ['C', 'C♯', 'D', 'D♯', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'A♯', 'B'];
const PARSE: Record<string, number> = { C: 0, D: 2, E: 4, F: 5, G: 7, A: 9, B: 11 };

export function midiToFrequency(midi: number, a4 = DEFAULT_A4): number {
  return a4 * Math.pow(2, (midi - 69) / 12);
}

export function frequencyToMidi(frequency: number, a4 = DEFAULT_A4): number {
  return 69 + 12 * Math.log2(frequency / a4);
}

/** Signed distance from `reference` to `frequency` in cents. */
export function centsBetween(frequency: number, reference: number): number {
  return 1200 * Math.log2(frequency / reference);
}

export function centsToRatio(cents: number): number {
  return Math.pow(2, cents / 1200);
}

export function midiLabel(midi: number): string {
  const pitchClass = ((midi % 12) + 12) % 12;
  return `${NAMES[pitchClass]}${Math.floor(midi / 12) - 1}`;
}

/** Parses scientific pitch notation such as "E2", "F#3" or "Bb1". */
export function parseNote(note: string): number {
  const match = /^([A-Ga-g])([#♯b♭]?)(-?\d+)$/.exec(note.trim());
  if (!match) throw new Error(`Invalid note name: ${note}`);
  const accidental = match[2] === '#' || match[2] === '♯' ? 1 : match[2] ? -1 : 0;
  return PARSE[match[1].toUpperCase()] + accidental + (Number(match[3]) + 1) * 12;
}
