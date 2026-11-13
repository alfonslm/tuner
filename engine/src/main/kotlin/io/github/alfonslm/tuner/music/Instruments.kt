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

/** The mode that works out which instrument is being tuned; see [InstrumentIdentifier]. */
const val AUTO_INSTRUMENT_ID = "auto"

/** The mode in which every semitone is a target. */
const val CHROMATIC_INSTRUMENT_ID = "chromatic"

const val DEFAULT_INSTRUMENT_ID = AUTO_INSTRUMENT_ID

val CHROMATIC = InstrumentPreset(CHROMATIC_INSTRUMENT_ID, "Chromatic", null, HarmonicModel(8, 0.0))

/**
 * The tunings the tuner knows out of the box. Which of them are in use is the
 * user's choice (a list in the engine's configuration); custom tunings are
 * further presets of the same kind.
 *
 * Inharmonicity (B) values are typical mid-range figures. They only steer where
 * the comb expects the upper partials, so being off by 2x is harmless.
 */
val STANDARD_TUNINGS: List<InstrumentPreset> = listOf(
    InstrumentPreset("guitar", "Guitar", listOf("E2", "A2", "D3", "G3", "B3", "E4"), HarmonicModel(8, 5e-5)),
    InstrumentPreset("guitar-drop-d", "Guitar · Drop D", listOf("D2", "A2", "D3", "G3", "B3", "E4"), HarmonicModel(8, 5e-5)),
    InstrumentPreset("guitar-half-down", "Guitar · Half step down", listOf("D#2", "G#2", "C#3", "F#3", "A#3", "D#4"), HarmonicModel(8, 5e-5)),
    InstrumentPreset("guitar-dadgad", "Guitar · DADGAD", listOf("D2", "A2", "D3", "G3", "A3", "D4"), HarmonicModel(8, 5e-5)),
    InstrumentPreset("guitar-open-g", "Guitar · Open G", listOf("D2", "G2", "D3", "G3", "B3", "D4"), HarmonicModel(8, 5e-5)),
    InstrumentPreset("guitar-7", "Guitar · 7-string", listOf("B1", "E2", "A2", "D3", "G3", "B3", "E4"), HarmonicModel(8, 5e-5)),
    InstrumentPreset("bass", "Bass", listOf("E1", "A1", "D2", "G2"), HarmonicModel(10, 1e-4)),
    InstrumentPreset("bass-5", "Bass · 5-string", listOf("B0", "E1", "A1", "D2", "G2"), HarmonicModel(10, 1e-4)),
    InstrumentPreset("ukulele", "Ukulele", listOf("G4", "C4", "E4", "A4"), HarmonicModel(6, 3e-5)),
    InstrumentPreset("violin", "Violin", listOf("G3", "D4", "A4", "E5"), HarmonicModel(8, 0.0)),
    InstrumentPreset("viola", "Viola", listOf("C3", "G3", "D4", "A4"), HarmonicModel(8, 0.0)),
    InstrumentPreset("cello", "Cello", listOf("C2", "G2", "D3", "A3"), HarmonicModel(10, 0.0)),
    InstrumentPreset("mandolin", "Mandolin", listOf("G3", "D4", "A4", "E5"), HarmonicModel(8, 2e-5)),
    InstrumentPreset("banjo", "Banjo · Open G", listOf("G4", "D3", "G3", "B3", "D4"), HarmonicModel(8, 3e-5)),
)

/**
 * The tunings switched on until the user says otherwise: one per instrument.
 * The alternates overlap heavily with these (Drop D shares five strings with
 * standard), which would make automatic recognition wait for the one string
 * that differs, so they are opt-in.
 */
val DEFAULT_TUNING_IDS: Set<String> = setOf("guitar", "bass", "ukulele", "violin", "viola", "cello")

val DEFAULT_TUNINGS: List<InstrumentPreset> = STANDARD_TUNINGS.filter { it.id in DEFAULT_TUNING_IDS }

/**
 * The preset behind the automatic mode: until the instrument is known it is a
 * chromatic tuner reaching from the lowest to above the highest string of the
 * [tunings] in use.
 */
fun automaticPreset(tunings: List<InstrumentPreset>): InstrumentPreset {
    val strings = tunings.flatMap { it.strings.orEmpty() }.map(::parseNote)
    val range = if (strings.isEmpty()) CHROMATIC.chromaticRange else midiLabel(strings.min()) to midiLabel(strings.max() + 8)
    return InstrumentPreset(AUTO_INSTRUMENT_ID, "Automatic", null, HarmonicModel(8, 0.0), chromaticRange = range)
}

/** Resolves an instrument setting against the tunings in use; `null` if it names none of them. */
fun findInstrument(id: String, tunings: List<InstrumentPreset>): InstrumentPreset? = when (id) {
    AUTO_INSTRUMENT_ID -> automaticPreset(tunings)
    CHROMATIC_INSTRUMENT_ID -> CHROMATIC
    else -> tunings.find { it.id == id }
}
