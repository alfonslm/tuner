package io.github.alfonslm.tuner.app

import android.content.Context
import androidx.core.content.edit
import io.github.alfonslm.tuner.engine.SmartFilterMode
import io.github.alfonslm.tuner.music.DEFAULT_A4
import io.github.alfonslm.tuner.music.DEFAULT_INSTRUMENT_ID

/** What the waterfall under the meter shows. */
enum class WaterfallStyle {
    /** The rolling spectrogram, coloured by how far from in tune each part of the scale is. */
    SPECTROGRAM,

    /** Only the reading, as a line. */
    LINE,
}

/**
 * How quickly the needle follows the pitch: the time its moving average
 * spans. Shorter follows a turning peg more closely, longer holds stiller on
 * a note that wavers.
 */
enum class NeedleResponse(val smoothingMs: Double) {
    FAST(100.0),
    NORMAL(200.0),
    STEADY(400.0),
}

/** The choices that survive closing the app. A pinned string deliberately does not. */
data class Settings(
    val instrumentId: String = DEFAULT_INSTRUMENT_ID,
    val a4: Double = DEFAULT_A4,
    val smartFilter: SmartFilterMode = SmartFilterMode.AUTO,
    val waterfallStyle: WaterfallStyle = WaterfallStyle.SPECTROGRAM,
    val needleResponse: NeedleResponse = NeedleResponse.NORMAL,
    val tunings: TuningCatalog = TuningCatalog.standard,
)

interface SettingsStore {
    fun load(): Settings

    fun save(settings: Settings)
}

class PreferencesSettingsStore(context: Context) : SettingsStore {
    private val preferences = context.getSharedPreferences("tuner", Context.MODE_PRIVATE)

    override fun load(): Settings {
        val defaults = Settings()
        val switches = preferences.getStringSet(KEY_TUNING_SWITCHES, emptySet()).orEmpty()
            .mapNotNull { entry -> entry.split("=").takeIf { it.size == 2 }?.let { it[0] to (it[1] == "1") } }
            .toMap()
        return Settings(
            // Whether the instrument is still in use is checked against the tunings by the view model.
            instrumentId = preferences.getString(KEY_INSTRUMENT, null) ?: defaults.instrumentId,
            a4 = preferences.getFloat(KEY_A4, defaults.a4.toFloat()).toDouble().coerceIn(A4_RANGE),
            smartFilter = if (preferences.getBoolean(KEY_FILTER, true)) SmartFilterMode.AUTO else SmartFilterMode.OFF,
            waterfallStyle = WaterfallStyle.entries.find { it.name == preferences.getString(KEY_WATERFALL, null) }
                ?: defaults.waterfallStyle,
            needleResponse = NeedleResponse.entries.find { it.name == preferences.getString(KEY_RESPONSE, null) }
                ?: defaults.needleResponse,
            tunings = TuningCatalog.restore(switches, preferences.getString(KEY_CUSTOM_TUNINGS, null).orEmpty()),
        )
    }

    override fun save(settings: Settings) {
        preferences.edit {
            putString(KEY_INSTRUMENT, settings.instrumentId)
            putFloat(KEY_A4, settings.a4.toFloat())
            putBoolean(KEY_FILTER, settings.smartFilter == SmartFilterMode.AUTO)
            putString(KEY_WATERFALL, settings.waterfallStyle.name)
            putString(KEY_RESPONSE, settings.needleResponse.name)
            putStringSet(KEY_TUNING_SWITCHES, settings.tunings.switches.map { (id, on) -> "$id=${if (on) 1 else 0}" }.toSet())
            putString(KEY_CUSTOM_TUNINGS, settings.tunings.encodeCustom())
        }
    }

    private companion object {
        const val KEY_INSTRUMENT = "instrument"
        const val KEY_A4 = "a4"
        const val KEY_FILTER = "smartFilter"
        const val KEY_WATERFALL = "waterfall"
        const val KEY_RESPONSE = "needleResponse"
        const val KEY_TUNING_SWITCHES = "tuningSwitches"
        const val KEY_CUSTOM_TUNINGS = "customTunings"
    }
}

/** Reference pitches the UI offers (Baroque 415 up to 466). */
val A4_RANGE = 415.0..466.0
