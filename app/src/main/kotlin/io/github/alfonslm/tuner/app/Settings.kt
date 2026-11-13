package io.github.alfonslm.tuner.app

import android.content.Context
import androidx.core.content.edit
import io.github.alfonslm.tuner.engine.SmartFilterMode
import io.github.alfonslm.tuner.music.DEFAULT_A4
import io.github.alfonslm.tuner.music.DEFAULT_INSTRUMENT_ID
import io.github.alfonslm.tuner.music.INSTRUMENTS

/** The choices that survive closing the app. A pinned string deliberately does not. */
data class Settings(
    val instrumentId: String = DEFAULT_INSTRUMENT_ID,
    val a4: Double = DEFAULT_A4,
    val smartFilter: SmartFilterMode = SmartFilterMode.AUTO,
)

interface SettingsStore {
    fun load(): Settings

    fun save(settings: Settings)
}

class PreferencesSettingsStore(context: Context) : SettingsStore {
    private val preferences = context.getSharedPreferences("tuner", Context.MODE_PRIVATE)

    override fun load(): Settings {
        val defaults = Settings()
        val instrumentId = preferences.getString(KEY_INSTRUMENT, null)
        return Settings(
            // A preset saved by an older version may no longer exist.
            instrumentId = instrumentId?.takeIf { id -> INSTRUMENTS.any { it.id == id } } ?: defaults.instrumentId,
            a4 = preferences.getFloat(KEY_A4, defaults.a4.toFloat()).toDouble().coerceIn(A4_RANGE),
            smartFilter = if (preferences.getBoolean(KEY_FILTER, true)) SmartFilterMode.AUTO else SmartFilterMode.OFF,
        )
    }

    override fun save(settings: Settings) {
        preferences.edit {
            putString(KEY_INSTRUMENT, settings.instrumentId)
            putFloat(KEY_A4, settings.a4.toFloat())
            putBoolean(KEY_FILTER, settings.smartFilter == SmartFilterMode.AUTO)
        }
    }

    private companion object {
        const val KEY_INSTRUMENT = "instrument"
        const val KEY_A4 = "a4"
        const val KEY_FILTER = "smartFilter"
    }
}

/** Reference pitches the UI offers (Baroque 415 up to 466). */
val A4_RANGE = 415.0..466.0
