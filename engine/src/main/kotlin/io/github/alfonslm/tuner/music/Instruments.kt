package io.github.alfonslm.tuner.music

import io.github.alfonslm.tuner.dsp.HarmonicModel

/**
 * What we know up front about an instrument: which notes its strings are tuned
 * to, and what the spectrum of one of its strings looks like.
 */
data class InstrumentPreset(
    val id: String,
    val name: String,
    /** Open strings, low to high. `null` means chromatic: every semitone is a target. */
    val strings: List<String>?,
    val harmonics: HarmonicModel,
    /** Lowest / highest note the chromatic mode listens for. */
    val chromaticRange: Pair<String, String> = "C2" to "C7",
)

/** The preset that works out which instrument is being tuned; see [InstrumentIdentifier]. */
const val AUTO_INSTRUMENT_ID = "auto"

const val DEFAULT_INSTRUMENT_ID = AUTO_INSTRUMENT_ID

// Inharmonicity (B) values are typical mid-range figures. They only steer where
// the comb expects the upper partials, so being off by 2x is harmless.
val INSTRUMENTS: List<InstrumentPreset> = listOf(
    // Until the instrument is known, Automatic behaves like a chromatic tuner
    // that reaches from the lowest bass string to above the highest violin string.
    InstrumentPreset(AUTO_INSTRUMENT_ID, "Automatic", null, HarmonicModel(8, 0.0), chromaticRange = "E1" to "C6"),
    InstrumentPreset("guitar", "Guitar", listOf("E2", "A2", "D3", "G3", "B3", "E4"), HarmonicModel(8, 5e-5)),
    InstrumentPreset("bass", "Bass", listOf("E1", "A1", "D2", "G2"), HarmonicModel(10, 1e-4)),
    InstrumentPreset("ukulele", "Ukulele", listOf("G4", "C4", "E4", "A4"), HarmonicModel(6, 3e-5)),
    InstrumentPreset("violin", "Violin", listOf("G3", "D4", "A4", "E5"), HarmonicModel(8, 0.0)),
    InstrumentPreset("viola", "Viola", listOf("C3", "G3", "D4", "A4"), HarmonicModel(8, 0.0)),
    InstrumentPreset("cello", "Cello", listOf("C2", "G2", "D3", "A3"), HarmonicModel(10, 0.0)),
    InstrumentPreset("chromatic", "Chromatic", null, HarmonicModel(8, 0.0)),
)

fun instrumentById(id: String): InstrumentPreset =
    requireNotNull(INSTRUMENTS.find { it.id == id }) { "Unknown instrument: $id" }
