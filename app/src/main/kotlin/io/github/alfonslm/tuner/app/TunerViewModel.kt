package io.github.alfonslm.tuner.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.alfonslm.tuner.app.audio.AudioSource
import io.github.alfonslm.tuner.engine.SmartFilterMode
import io.github.alfonslm.tuner.engine.TunerConfig
import io.github.alfonslm.tuner.engine.TunerReading
import io.github.alfonslm.tuner.music.AUTO_INSTRUMENT_ID
import io.github.alfonslm.tuner.music.CHROMATIC
import io.github.alfonslm.tuner.music.InstrumentPreset
import io.github.alfonslm.tuner.music.Target
import io.github.alfonslm.tuner.music.TargetSetOptions
import io.github.alfonslm.tuner.music.automaticPreset
import io.github.alfonslm.tuner.music.createTargetSet
import io.github.alfonslm.tuner.music.findInstrument
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

/** Everything the screens show. */
data class TunerUiState(
    val config: TunerConfig,
    /** What the instrument picker offers: Automatic, the tunings in use, Chromatic. */
    val instruments: List<InstrumentPreset> = instrumentsOf(config),
    /** Every tuning, in use or not, for the settings list. */
    val tunings: List<Tuning> = TuningCatalog.standard.tunings,
    val waterfallStyle: WaterfallStyle = WaterfallStyle.SPECTROGRAM,
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
    /** The last few seconds of readings, for the waterfall. */
    val trace: PitchTrace = PitchTrace(),
) {
    /** True while a pitch is being measured right now (as opposed to a held reading). */
    val live: Boolean get() = latest?.voiced == true
}

private fun instrumentsOf(config: TunerConfig): List<InstrumentPreset> =
    listOf(automaticPreset(config.tunings)) + config.tunings + CHROMATIC

/**
 * Owns the tuner's state: settings, the list of tunings, whether it is running,
 * and the reading on screen. Starting collects [tunerReadings]; stopping
 * cancels that collection, which releases the microphone.
 */
class TunerViewModel(
    private val source: AudioSource,
    private val settings: SettingsStore,
    private val audioDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "tuner-audio") }.asCoroutineDispatcher(),
) : ViewModel() {

    private var catalog: TuningCatalog
    private var waterfallStyle: WaterfallStyle
    private val config: MutableStateFlow<TunerConfig>
    private val _state: MutableStateFlow<TunerUiState>
    val state: StateFlow<TunerUiState>

    private var session: Job? = null

    init {
        val saved = settings.load()
        catalog = saved.tunings
        waterfallStyle = saved.waterfallStyle
        config = MutableStateFlow(
            TunerConfig(a4 = saved.a4, smartFilter = saved.smartFilter).withTunings(saved.instrumentId).withWaterfall(),
        )
        _state = MutableStateFlow(
            TunerUiState(
                config = config.value,
                tunings = catalog.tunings,
                waterfallStyle = waterfallStyle,
                strings = stringsOf(config.value, null),
            ),
        )
        state = _state.asStateFlow()
    }

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

    fun setWaterfallStyle(style: WaterfallStyle) {
        waterfallStyle = style
        configure { it.withWaterfall() }
    }

    // --- tunings ---

    fun setTuningEnabled(id: String, enabled: Boolean) = changeTunings { it.setEnabled(id, enabled) }

    /** Makes an editable copy of a tuning and returns its id. */
    fun duplicateTuning(id: String): String? {
        val (changed, copy) = catalog.duplicate(id)
        changeTunings { changed }
        return copy
    }

    fun renameTuning(id: String, name: String) = changeTunings { it.rename(id, name) }

    fun shiftTuningString(id: String, index: Int, semitones: Int) = changeTunings { it.shiftString(id, index, semitones) }

    fun addTuningString(id: String) = changeTunings { it.addString(id) }

    fun removeTuningString(id: String, index: Int) = changeTunings { it.removeString(id, index) }

    fun deleteTuning(id: String) = changeTunings { it.delete(id) }

    private fun changeTunings(change: (TuningCatalog) -> TuningCatalog) {
        val changed = change(catalog)
        if (changed == catalog) return
        catalog = changed
        // Strings may have been renumbered or retuned, so a pin cannot be carried over.
        configure { it.copy(pinnedTargetId = null).withTunings(it.instrumentId) }
    }

    /** The tunings in use, and an instrument setting that is one of them (else Automatic). */
    private fun TunerConfig.withTunings(instrumentId: String): TunerConfig {
        val inUse = catalog.inUse
        return copy(tunings = inUse, instrumentId = instrumentId.takeIf { findInstrument(it, inUse) != null } ?: AUTO_INSTRUMENT_ID)
    }

    /** The spectrogram is only computed while it is on show. */
    private fun TunerConfig.withWaterfall(): TunerConfig =
        copy(spectrogram = spectrogram.copy(enabled = waterfallStyle == WaterfallStyle.SPECTROGRAM))

    /** Applies a settings change: to the engine (through [config]), to disk, and to the screen. */
    private fun configure(change: (TunerConfig) -> TunerConfig) {
        val previous = config.value
        val updated = change(previous)
        config.value = updated
        settings.save(Settings(updated.instrumentId, updated.a4, updated.smartFilter, waterfallStyle, catalog))
        _state.update { state ->
            // The recognised instrument may just have been edited, switched off or deleted.
            val detected = state.detectedInstrument?.let { known -> updated.tunings.find { it == known } }
            val base = state.copy(
                config = updated,
                instruments = instrumentsOf(updated),
                tunings = catalog.tunings,
                waterfallStyle = waterfallStyle,
                detectedInstrument = detected,
                strings = stringsOf(updated, detected),
            )
            // Readings taken under other targets (other instrument, tuning or reference pitch) no longer apply.
            val sameTargets = updated.instrumentId == previous.instrumentId && updated.a4 == previous.a4 &&
                updated.tunings == previous.tunings
            if (sameTargets) base else base.copy(latest = null, shown = null, trace = PitchTrace())
        }
    }

    /**
     * Every session identifies the instrument afresh: a new engine knows nothing
     * about the last one, and a stale guess is worse than asking for two strings again.
     */
    private fun forgetDetection() {
        // A pinned string belonged to the instrument that had been recognised.
        if (config.value.instrumentId == AUTO_INSTRUMENT_ID) config.update { it.copy(pinnedTargetId = null) }
        _state.update {
            it.copy(
                config = config.value,
                detectedInstrument = null,
                strings = stringsOf(config.value, null),
                latest = null,
                shown = null,
                trace = PitchTrace(),
            )
        }
    }

    private fun show(reading: TunerReading) {
        _state.update { state ->
            val detected = state.config.tunings
                .takeIf { state.config.instrumentId == AUTO_INSTRUMENT_ID && reading.instrumentId != AUTO_INSTRUMENT_ID }
                ?.find { it.id == reading.instrumentId }
            val held = state.shown?.takeIf { reading.time - it.time < HOLD_SECONDS }
            state.copy(
                detectedInstrument = detected,
                strings = if (detected != state.detectedInstrument) stringsOf(state.config, detected) else state.strings,
                latest = reading,
                shown = if (reading.voiced) reading else held,
                trace = state.trace.add(reading),
            )
        }
    }

    override fun onCleared() {
        audioDispatcher.close()
    }

    private companion object {
        /** How long the last reading stays on screen after the note stops. */
        const val HOLD_SECONDS = 1.5

        fun stringsOf(config: TunerConfig, detected: InstrumentPreset?): List<Target> = createTargetSet(
            detected ?: findInstrument(config.instrumentId, config.tunings) ?: automaticPreset(config.tunings),
            config.a4,
            TargetSetOptions(config.wide.rangeMarginCents, config.focus.captureOverlapCents, config.focus.maxCaptureCents),
        ).list.orEmpty()
    }
}
