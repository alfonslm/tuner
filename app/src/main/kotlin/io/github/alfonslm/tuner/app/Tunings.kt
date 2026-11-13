package io.github.alfonslm.tuner.app

import io.github.alfonslm.tuner.dsp.HarmonicModel
import io.github.alfonslm.tuner.music.DEFAULT_TUNING_IDS
import io.github.alfonslm.tuner.music.InstrumentPreset
import io.github.alfonslm.tuner.music.STANDARD_TUNINGS
import io.github.alfonslm.tuner.music.midiLabel
import io.github.alfonslm.tuner.music.parseNote

/** A tuning as the settings list shows it. */
data class Tuning(
    val preset: InstrumentPreset,
    /** In use: selectable on the main screen and a candidate for automatic recognition. */
    val enabled: Boolean,
    /** Made by the user (by duplicating another), and therefore editable and deletable. */
    val custom: Boolean,
) {
    val id: String get() = preset.id
    val strings: List<String> get() = preset.strings.orEmpty()
}

/**
 * The user's list of tunings: the standard ones, each of which can be switched
 * off, and their own, made by duplicating any tuning and then changing its
 * name, its notes and its number of strings. Immutable; every change returns a
 * new catalog.
 */
data class TuningCatalog(val tunings: List<Tuning>) {

    /** What the engine works with: the tunings that are switched on. */
    val inUse: List<InstrumentPreset> get() = tunings.filter { it.enabled }.map { it.preset }

    operator fun get(id: String): Tuning? = tunings.find { it.id == id }

    fun setEnabled(id: String, enabled: Boolean): TuningCatalog = change(id, customOnly = false) { it.copy(enabled = enabled) }

    /**
     * Adds an editable copy of a tuning right below it.
     *
     * @return the new catalog and the id of the copy, or this catalog and `null` if there is no such tuning
     */
    fun duplicate(id: String): Pair<TuningCatalog, String?> {
        val index = tunings.indexOfFirst { it.id == id }
        if (index < 0) return this to null
        val source = tunings[index]
        val number = (tunings.mapNotNull { it.id.removePrefix(CUSTOM_PREFIX).toIntOrNull().takeIf { _ -> it.custom } }.maxOrNull() ?: 0) + 1
        val copy = Tuning(
            source.preset.copy(id = "$CUSTOM_PREFIX$number", name = freeName("${source.preset.name} copy")),
            enabled = true,
            custom = true,
        )
        return TuningCatalog(tunings.subList(0, index + 1) + copy + tunings.subList(index + 1, tunings.size)) to copy.id
    }

    fun rename(id: String, name: String): TuningCatalog =
        change(id) { it.copy(preset = it.preset.copy(name = name.replace(Regex("[\\t\\n\\r]"), " ").take(MAX_NAME_LENGTH))) }

    /** Retunes one string of a custom tuning by [semitones]. */
    fun shiftString(id: String, index: Int, semitones: Int): TuningCatalog = changeStrings(id) { strings ->
        if (index !in strings.indices) return@changeStrings strings
        val midi = (parseNote(strings[index]) + semitones).coerceIn(LOWEST_MIDI, HIGHEST_MIDI)
        strings.toMutableList().also { it[index] = midiLabel(midi) }
    }

    /** Adds a string to a custom tuning, a fourth above its last one. */
    fun addString(id: String): TuningCatalog = changeStrings(id) { strings ->
        if (strings.size >= MAX_STRINGS) return@changeStrings strings
        val last = strings.lastOrNull()?.let(::parseNote) ?: (DEFAULT_FIRST_STRING_MIDI - 5)
        strings + midiLabel((last + 5).coerceIn(LOWEST_MIDI, HIGHEST_MIDI))
    }

    /** Removes a string from a custom tuning; the last one stays. */
    fun removeString(id: String, index: Int): TuningCatalog = changeStrings(id) { strings ->
        if (strings.size <= 1 || index !in strings.indices) strings else strings.filterIndexed { i, _ -> i != index }
    }

    /** Deletes a custom tuning. Standard ones can only be switched off. */
    fun delete(id: String): TuningCatalog = TuningCatalog(tunings.filterNot { it.id == id && it.custom })

    private fun changeStrings(id: String, change: (List<String>) -> List<String>): TuningCatalog =
        change(id) { it.copy(preset = it.preset.copy(strings = change(it.strings))) }

    private fun change(id: String, customOnly: Boolean = true, change: (Tuning) -> Tuning): TuningCatalog =
        TuningCatalog(tunings.map { if (it.id == id && (it.custom || !customOnly)) change(it) else it })

    private fun freeName(wanted: String): String {
        val taken = tunings.map { it.preset.name }.toSet()
        if (wanted !in taken) return wanted
        return generateSequence(2) { it + 1 }.map { "$wanted $it" }.first { it !in taken }
    }

    // --- persistence ---

    /** Standard tunings whose switch is not in its default position. */
    val switches: Map<String, Boolean>
        get() = tunings.filter { !it.custom && it.enabled != (it.id in DEFAULT_TUNING_IDS) }.associate { it.id to it.enabled }

    /**
     * The custom tunings as text: one per line, with the id of the tuning each
     * one sits below, so the list comes back in the order the user left it.
     */
    fun encodeCustom(): String = tunings.withIndex().filter { it.value.custom }.joinToString("\n") { (index, tuning) ->
        val preset = tuning.preset
        listOf(
            preset.id,
            tunings.getOrNull(index - 1)?.id.orEmpty(),
            if (tuning.enabled) "1" else "0",
            preset.harmonics.partials,
            preset.harmonics.inharmonicity,
            tuning.strings.joinToString(","),
            preset.name,
        ).joinToString("\t")
    }

    companion object {
        const val MAX_STRINGS = 12
        const val MAX_NAME_LENGTH = 40

        /** B0 to C7: what the detectors are built to follow. */
        const val LOWEST_MIDI = 23
        const val HIGHEST_MIDI = 96
        private const val DEFAULT_FIRST_STRING_MIDI = 40
        private const val CUSTOM_PREFIX = "custom-"

        /** The catalog a new user starts with. */
        val standard: TuningCatalog = TuningCatalog(STANDARD_TUNINGS.map { Tuning(it, it.id in DEFAULT_TUNING_IDS, custom = false) })

        /** Rebuilds the catalog from what [switches] and [encodeCustom] saved. Anything unreadable is skipped. */
        fun restore(switches: Map<String, Boolean>, custom: String): TuningCatalog {
            val tunings = standard.tunings.map { it.copy(enabled = switches[it.id] ?: it.enabled) }.toMutableList()
            for (line in custom.lines()) {
                val fields = line.split("\t", limit = 7)
                if (fields.size != 7) continue
                val strings = fields[5].split(",").filter { it.isNotBlank() }
                val valid = strings.isNotEmpty() && strings.size <= MAX_STRINGS && strings.all { runCatching { parseNote(it) }.isSuccess }
                val partials = fields[3].toIntOrNull()
                val inharmonicity = fields[4].toDoubleOrNull()
                if (!valid || partials == null || inharmonicity == null || tunings.any { it.id == fields[0] }) continue
                val tuning = Tuning(
                    InstrumentPreset(fields[0], fields[6], strings, HarmonicModel(partials.coerceIn(1, 16), inharmonicity.coerceIn(0.0, 0.01))),
                    enabled = fields[2] == "1",
                    custom = true,
                )
                val after = tunings.indexOfFirst { it.id == fields[1] }
                if (after >= 0) tunings.add(after + 1, tuning) else tunings.add(tuning)
            }
            return TuningCatalog(tunings)
        }
    }
}
