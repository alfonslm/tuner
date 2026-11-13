/**
 * What a plucked or bowed string looks like in the spectrum: partials at
 * (nearly) integer multiples of the fundamental. Stiffness stretches them
 * slightly upwards: f_k = k · f0 · sqrt(1 + B · k²).
 */
export interface HarmonicModel {
  /** How many partials the comb uses. */
  partials: number;
  /** Inharmonicity coefficient B (0 for an ideal string). */
  inharmonicity: number;
}

export function partialFrequency(f0: number, k: number, inharmonicity: number): number {
  return k * f0 * Math.sqrt(1 + inharmonicity * k * k);
}

/** Number of model partials that fit below `maxHz`. */
export function partialCount(f0: number, model: HarmonicModel, maxHz: number): number {
  let count = 0;
  while (count < model.partials && partialFrequency(f0, count + 1, model.inharmonicity) <= maxHz) count++;
  return Math.max(1, count);
}

export interface CombGeometry {
  binHz: number;
  /** Half-width of the analysis window's main lobe in Hz; pass bands are padded by it. */
  lobeHz: number;
  maxHz: number;
}

/**
 * Fills `out` with a harmonic comb: one pass band per partial of a string
 * tuned to `f0`, everything else zero.
 *
 * Each band is flat over ±`halfWidthCents` (so a string that is that far off
 * passes undistorted) and then rolls off with a raised cosine. Partial k is
 * weighted 1/k²: in an autocorrelation a partial pulls on the peak position
 * with strength ∝ power · k², so this gives every partial the same say per unit
 * of power and keeps stretched upper partials from dragging the reading sharp.
 */
export function buildComb(
  out: Float64Array,
  f0: number,
  halfWidthCents: number,
  model: HarmonicModel,
  geometry: CombGeometry,
): void {
  out.fill(0);
  const { binHz, lobeHz, maxHz } = geometry;
  const relativeWidth = Math.pow(2, halfWidthCents / 1200) - 1;
  const padding = 0.75 * lobeHz;
  const count = partialCount(f0, model, maxHz);
  for (let k = 1; k <= count; k++) {
    const centre = partialFrequency(f0, k, model.inharmonicity);
    const flat = centre * relativeWidth + padding;
    const edge = flat + padding;
    const gain = 1 / (k * k);
    const first = Math.max(1, Math.ceil((centre - edge) / binHz));
    const last = Math.min(out.length - 1, Math.floor((centre + edge) / binHz));
    for (let b = first; b <= last; b++) {
      const distance = Math.abs(b * binHz - centre);
      const taper = distance <= flat ? 1 : 0.5 + 0.5 * Math.cos((Math.PI * (distance - flat)) / padding);
      const value = gain * taper;
      if (value > out[b]) out[b] = value;
    }
  }
}

export interface HarmonicProfile {
  /**
   * 0..1: share of the energy in the string's band that sits on the partials of
   * `f0`, corrected for what broadband noise would put there by chance. Low in
   * noise and when nothing string-like is sounding.
   */
  salience: number;
  /**
   * 0..1: share of the *tonal* energy (everything standing out of the noise
   * floor) that the partials of `f0` account for. Stays near 1 for the right
   * string even in heavy noise, and drops when another note is sounding whose
   * partials only partly coincide with the comb (a fourth, a fifth, an octave
   * or a twelfth below).
   */
  explained: number;
  /**
   * Smallest share of harmonic energy found on partials that are NOT a multiple
   * of m (m = 2, 3, 5, 7). Near zero means the sound is really a note m times
   * higher and `f0` is only its sub-harmonic.
   */
  fundamentalSupport: number;
}

const PRIMES = [2, 3, 5, 7];
const SUBHARMONIC_FRACTIONS = [1 / 3, 1 / 2, 2 / 3];
const NO_MATCH: HarmonicProfile = { salience: 0, explained: 0, fundamentalSupport: 0 };
let scratch = new Float64Array(1024);

/** Measures how well the (unfiltered) spectrum matches a string sounding at `f0`. */
export function harmonicProfile(
  power: Float64Array,
  f0: number,
  model: HarmonicModel,
  geometry: CombGeometry,
): HarmonicProfile {
  const { binHz, lobeHz, maxHz } = geometry;
  const count = partialCount(f0, model, maxHz);
  const halfBins = Math.max(2, Math.round(lobeHz / binHz));
  const lastBin = power.length - 1;

  const energies: number[] = [];
  const widths: number[] = [];
  let harmonicEnergy = 0;
  let harmonicBins = 0;
  let firstCentre = 0;
  let lastCentre = 0;
  for (let k = 1; k <= count; k++) {
    const centre = Math.round(partialFrequency(f0, k, model.inharmonicity) / binHz);
    if (k === 1) firstCentre = centre;
    lastCentre = centre;
    let sum = 0;
    const from = Math.max(1, centre - halfBins);
    const to = Math.min(lastBin, centre + halfBins);
    for (let b = from; b <= to; b++) sum += power[b];
    energies.push(sum);
    widths.push(to - from + 1);
    harmonicEnergy += sum;
    harmonicBins += to - from + 1;
  }

  const spacing = f0 / binHz;
  const bandFrom = Math.max(1, Math.min(firstCentre - halfBins, Math.round(firstCentre - 0.3 * spacing)));
  const bandTo = Math.min(lastBin, Math.max(lastCentre + halfBins, Math.round(lastCentre + 0.5 * spacing)));
  const bandBins = bandTo - bandFrom + 1;
  let bandEnergy = 0;
  for (let b = bandFrom; b <= bandTo; b++) bandEnergy += power[b];
  if (!(bandEnergy > 0) || !(harmonicEnergy > 0)) return NO_MATCH;

  const chance = Math.min(0.95, harmonicBins / bandBins);
  const salience = Math.max(0, (harmonicEnergy / bandEnergy - chance) / (1 - chance));

  // Noise floor: the median bin is noise as long as tones occupy under half the band.
  // Noise power per bin is exponentially distributed, so mean = median / ln 2.
  if (scratch.length < bandBins) scratch = new Float64Array(bandBins * 2);
  const sorted = scratch.subarray(0, bandBins);
  sorted.set(power.subarray(bandFrom, bandTo + 1));
  sorted.sort();
  const noisePerBin = sorted[bandBins >> 1] / Math.LN2;

  let tonalHarmonic = 0;
  for (let k = 0; k < count; k++) {
    energies[k] = Math.max(0, energies[k] - widths[k] * noisePerBin);
    tonalHarmonic += energies[k];
  }
  const tonalBand = bandEnergy - bandBins * noisePerBin;
  if (!(tonalHarmonic > 0) || !(tonalBand > 0)) return { salience, explained: 0, fundamentalSupport: 0 };

  // `f0` may itself be the 2nd or 3rd partial of a lower note (typical for the
  // tail of a low string, when little else is left). That note's own lowest
  // partials sit below the band examined so far: count them as unexplained too.
  let below = 0;
  for (const fraction of SUBHARMONIC_FRACTIONS) {
    const centre = Math.round((f0 * fraction) / binHz);
    const from = Math.max(1, centre - halfBins);
    const to = Math.min(bandFrom - 1, centre + halfBins);
    let sum = 0;
    for (let b = from; b <= to; b++) sum += power[b];
    if (to >= from) below += Math.max(0, sum - (to - from + 1) * noisePerBin);
  }
  const explained = Math.min(1, tonalHarmonic / (tonalBand + below));

  let fundamentalSupport = 1;
  for (const m of PRIMES) {
    if (m > count) break;
    let offMultiples = 0;
    for (let k = 1; k <= count; k++) if (k % m !== 0) offMultiples += energies[k - 1];
    fundamentalSupport = Math.min(fundamentalSupport, offMultiples / tonalHarmonic);
  }
  return { salience, explained, fundamentalSupport };
}
