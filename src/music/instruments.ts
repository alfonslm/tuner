import type { HarmonicModel } from '../dsp/harmonicComb';

/**
 * What we know up front about an instrument: which notes its strings are tuned
 * to, and what the spectrum of one of its strings looks like.
 */
export interface InstrumentPreset {
  id: string;
  name: string;
  /** Open strings, low to high. `null` means chromatic: every semitone is a target. */
  strings: string[] | null;
  /** Lowest / highest note the chromatic mode listens for. */
  chromaticRange?: [low: string, high: string];
  harmonics: HarmonicModel;
}

// Inharmonicity (B) values are typical mid-range figures. They only steer where
// the comb expects the upper partials, so being off by 2x is harmless.
export const INSTRUMENTS: InstrumentPreset[] = [
  {
    id: 'chromatic',
    name: 'Chromatic',
    strings: null,
    chromaticRange: ['C2', 'C7'],
    harmonics: { partials: 8, inharmonicity: 0 },
  },
  {
    id: 'guitar',
    name: 'Guitar',
    strings: ['E2', 'A2', 'D3', 'G3', 'B3', 'E4'],
    harmonics: { partials: 8, inharmonicity: 5e-5 },
  },
  {
    id: 'bass',
    name: 'Bass',
    strings: ['E1', 'A1', 'D2', 'G2'],
    harmonics: { partials: 10, inharmonicity: 1e-4 },
  },
  {
    id: 'ukulele',
    name: 'Ukulele',
    strings: ['G4', 'C4', 'E4', 'A4'],
    harmonics: { partials: 6, inharmonicity: 3e-5 },
  },
  {
    id: 'violin',
    name: 'Violin',
    strings: ['G3', 'D4', 'A4', 'E5'],
    harmonics: { partials: 8, inharmonicity: 0 },
  },
  {
    id: 'viola',
    name: 'Viola',
    strings: ['C3', 'G3', 'D4', 'A4'],
    harmonics: { partials: 8, inharmonicity: 0 },
  },
  {
    id: 'cello',
    name: 'Cello',
    strings: ['C2', 'G2', 'D3', 'A3'],
    harmonics: { partials: 10, inharmonicity: 0 },
  },
];

export function getInstrument(id: string): InstrumentPreset {
  const preset = INSTRUMENTS.find((p) => p.id === id);
  if (!preset) throw new Error(`Unknown instrument: ${id}`);
  return preset;
}
