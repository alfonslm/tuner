package io.github.alfonslm.tuner.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.alfonslm.tuner.app.audio.AudioSource
import io.github.alfonslm.tuner.engine.SmartFilterMode
import io.github.alfonslm.tuner.engine.TunerConfig
import io.github.alfonslm.tuner.engine.TunerReading
import io.github.alfonslm.tuner.music.AUTO_INSTRUMENT_ID
import io.github.alfonslm.tuner.music.INSTRUMENTS
import io.github.alfonslm.tuner.music.InstrumentPreset
import io.github.alfonslm.tuner.music.Target
import io.github.alfonslm.tuner.music.TargetSetOptions
import io.github.alfonslm.tuner.music.createTargetSet
import io.github.alfonslm.tuner.music.instrumentById
import java.util.concurrent.Executors
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TunerError { PERMISSION_DENIED, MICROPHONE_UNAVAILABLE }

/** Everything the screen shows. */
data class TunerUiState(
    val config: TunerConfig,
    val instruments: List<InstrumentPreset> = INSTRUMENTS,
    /** Automatic mode only: the instrument recognised from the strings played so far, once there is one. */
    val detectedInstrument: InstrumentPreset? = null,
    /** The strings of the instrument being tuned; empty while chromatic (or not yet recognised). */
    val strings: List<Target> = emptyList(),
    val running: Boolean = false,
    val error: TunerError? = null,
    /** The most recent analysis frame, pitched or not. */
    val latest: TunerReading? = null,
    /** What the display shows: the latest pitched reading, held briefly after the note stops. */
    val shown: TunerReading? = null,
) {
    /** True while a pitch is being measured right now (as opposed to a held reading). */
    val live: Boolean get() = latest?.voiced == true
}

/**
 * Owns the tuner's state: settings, whether it is running, and the reading on
 * screen. Starting collects [tunerReadings]; stopping cancels that collection,
 * which releases the microphone.
 */
class TunerViewModel(
    private val source: AudioSource,
    private val settings: SettingsStore,
    private val audioDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "tuner-audio") }.asCoroutineDispatcher(),
) : ViewModel() {

    private val config = MutableStateFlow(settings.load().toConfig())
    private val _state = MutableStateFlow(TunerUiState(config.value, strings = stringsOf(config.value, null)))
    val state: StateFlow<TunerUiState> = _state.asStateFlow()

    private var session: Job? = null

    /** Call once the RECORD_AUDIO permission has been granted. */
    fun start() {
        if (session?.isActive == true) return
        forgetDetection()
        _state.update { it.copy(running = true, error = null) }
        session = viewModelScope.launch {
            tunerReadings(source, config, audioDispatcher)
                .catch { _state.update { it.copy(error = TunerError.MICROPHONE_UNAVAILABLE) } }
                .collect(::show)
            // The stream ended by itself (microphone taken away, or it never opened).
            forgetDetection()
            _state.update { it.copy(running = false) }
        }
    }

    fun stop() {
        session?.cancel()
        session = null
        forgetDetection()
        _state.update { it.copy(running = false) }
    }

    /**
     * Every session identifies the instrument afresh: a new engine knows nothing
     * about the last one, and a stale guess is worse than asking for two strings again.
     */
    private fun forgetDetection() {
        // A pinned string belonged to the instrument that had been recognised.
        if (config.value.instrumentId == AUTO_INSTRUMENT_ID) config.update { it.copy(pinnedTargetId = null) }
        _state.update {
            it.copy(config = config.value, detectedInstrument = null, strings = stringsOf(config.value, null), latest = null, shown = null)
        }
    }

    fun permissionDenied() {
        _state.update { it.copy(error = TunerError.PERMISSION_DENIED) }
    }

    /**
     * Changes the instrument. A running session is restarted so that nothing
     * heard under the old instrument carries over; choosing Automatic again is
     * therefore also how to make it identify the instrument anew.
     */
    fun setInstrument(id: String) {
        val wasRunning = session?.isActive == true
        if (wasRunning) stop()
        configure { it.copy(instrumentId = id, pinnedTargetId = null) }
        if (wasRunning) start()
    }

    fun setReferencePitch(a4: Double) = configure { it.copy(a4 = a4.coerceIn(A4_RANGE)) }

    fun setSmartFilter(enabled: Boolean) =
        configure { it.copy(smartFilter = if (enabled) SmartFilterMode.AUTO else SmartFilterMode.OFF) }

    /** Pins the tuner to a string, or releases it if that string is already pinned. */
    fun togglePin(targetId: String) =
        configure { it.copy(pinnedTargetId = if (it.pinnedTargetId == targetId) null else targetId) }

    private fun configure(change: (TunerConfig) -> TunerConfig) {
        val updated = change(config.value)
        if (updated == config.value) return
        config.value = updated
        settings.save(Settings(updated.instrumentId, updated.a4, updated.smartFilter))
        // Readings taken under the old settings (other instrument, other reference) no longer apply.
        _state.update {
            it.copy(config = updated, strings = stringsOf(updated, it.detectedInstrument), latest = null, shown = null)
        }
    }

    private fun show(reading: TunerReading) {
        _state.update { state ->
            val detected = INSTRUMENTS
                .takeIf { state.config.instrumentId == AUTO_INSTRUMENT_ID && reading.instrumentId != AUTO_INSTRUMENT_ID }
                ?.find { it.id == reading.instrumentId }
            val held = state.shown?.takeIf { reading.time - it.time < HOLD_SECONDS }
            state.copy(
                detectedInstrument = detected,
                strings = if (detected != state.detectedInstrument) stringsOf(state.config, detected) else state.strings,
                latest = reading,
                shown = if (reading.voiced) reading else held,
            )
        }
    }

    override fun onCleared() {
        audioDispatcher.close()
    }

    private companion object {
        /** How long the last reading stays on screen after the note stops. */
        const val HOLD_SECONDS = 1.5

        fun Settings.toConfig() = TunerConfig(instrumentId = instrumentId, a4 = a4, smartFilter = smartFilter)

        fun stringsOf(config: TunerConfig, detected: InstrumentPreset?): List<Target> = createTargetSet(
            detected ?: instrumentById(config.instrumentId),
            config.a4,
            TargetSetOptions(config.wide.rangeMarginCents, config.focus.captureOverlapCents, config.focus.maxCaptureCents),
        ).list.orEmpty()
    }
}
