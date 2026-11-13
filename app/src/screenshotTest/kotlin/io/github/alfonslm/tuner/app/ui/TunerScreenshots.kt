package io.github.alfonslm.tuner.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.alfonslm.tuner.app.PitchTrace
import io.github.alfonslm.tuner.app.TracePoint
import io.github.alfonslm.tuner.app.TunerError
import io.github.alfonslm.tuner.app.TunerUiState
import io.github.alfonslm.tuner.app.TuningCatalog
import io.github.alfonslm.tuner.app.WaterfallStyle
import io.github.alfonslm.tuner.dsp.SPECTRUM_COLUMNS
import io.github.alfonslm.tuner.engine.LockInfo
import io.github.alfonslm.tuner.engine.PitchSource
import io.github.alfonslm.tuner.engine.SmartFilterMode
import io.github.alfonslm.tuner.engine.SpectrumRow
import io.github.alfonslm.tuner.engine.TunerConfig
import io.github.alfonslm.tuner.engine.TunerEngine
import io.github.alfonslm.tuner.engine.TunerMode
import io.github.alfonslm.tuner.engine.TunerReading
import io.github.alfonslm.tuner.music.Target
import io.github.alfonslm.tuner.music.TargetSetOptions
import io.github.alfonslm.tuner.music.centsToRatio
import io.github.alfonslm.tuner.music.createTargetSet
import io.github.alfonslm.tuner.music.STANDARD_TUNINGS
import io.github.alfonslm.tuner.music.findInstrument

// The screen in each state the tuner can be in, on a phone-sized canvas.
// `./gradlew :app:updateDebugScreenshotTest` regenerates the reference images,
// `./gradlew :app:validateDebugScreenshotTest` fails if the UI no longer matches them.

private val NoActions = TunerActions({}, {}, {}, {}, {}, {})
private val NoSettingsActions = SettingsActions({}, {}, {}, { _, _ -> }, { null }, { _, _ -> }, { _, _, _ -> }, {}, { _, _ -> }, {})

private fun instrumentById(id: String) = checkNotNull(findInstrument(id, STANDARD_TUNINGS))

private val Guitar = TunerConfig(instrumentId = "guitar")

private fun strings(instrumentId: String, a4: Double): List<Target> =
    createTargetSet(instrumentById(instrumentId), a4, TargetSetOptions(300.0, 30.0, 300.0)).list.orEmpty()

private fun reading(
    target: Target,
    cents: Double?,
    source: PitchSource? = PitchSource.FOCUSED,
    locked: Boolean = true,
    pinned: Boolean = false,
    widthCents: Double = 35.0,
    inTune: Boolean = false,
    instrumentId: String = "guitar",
    spreadCents: Double? = 0.3,
) = TunerReading(
    time = 10.0,
    delay = if (cents != null) 0.115 else 0.0,
    instrumentId = instrumentId,
    mode = if (locked) TunerMode.LOCKED else TunerMode.SEARCH,
    levelDb = -30.0,
    source = source.takeIf { cents != null },
    target = target,
    frequency = cents?.let { target.frequency * centsToRatio(it) },
    cents = cents,
    spreadCents = spreadCents.takeIf { cents != null },
    confidence = 0.95,
    inTune = inTune,
    lock = if (locked) LockInfo(target, pinned, widthCents) else null,
)

/**
 * A history ending now (t = 10 s): `cents(age)` gives the offset `age` seconds ago,
 * or null where no note was sounding. The spectrogram is a stripe along the same
 * path, `width(age)` cents wide, fading as each note rings out.
 */
private fun trace(
    inTuneBelow: Float = 3f,
    width: (age: Double) -> Double = { 9.0 },
    cents: (age: Double) -> Double?,
): PitchTrace {
    val points = (0..(PitchTrace.SECONDS * 47).toInt()).mapNotNull { step ->
        val age = PitchTrace.SECONDS - step / 47.0
        cents(age)?.let { TracePoint(10.0 - age, it.toFloat(), kotlin.math.abs(it) <= inTuneBelow) }
    }
    val rowSeconds = 3 * 1024 / 48000.0
    var sounding = 0.0
    val rows = (0..(PitchTrace.SECONDS / rowSeconds).toInt()).map { step ->
        val age = PitchTrace.SECONDS - step * rowSeconds
        val centre = cents(age)
        sounding = if (centre == null) 0.0 else sounding + rowSeconds
        val level = 0.95 * kotlin.math.exp(-sounding / 3.5)
        val sigma = width(age) / 2.355
        SpectrumRow(
            time = 10.0 - age,
            duration = rowSeconds,
            targetId = "preview",
            levels = FloatArray(SPECTRUM_COLUMNS) { column ->
                if (centre == null) 0f else (level * kotlin.math.exp(-0.5 * ((column - (SPECTRUM_COLUMNS - 1) / 2 - centre) / sigma).let { it * it })).toFloat()
            },
        )
    }
    return PitchTrace("preview", points, rows, now = 10.0)
}

@Composable
private fun Screen(
    config: TunerConfig = Guitar,
    running: Boolean = true,
    error: TunerError? = null,
    detected: String? = null,
    trace: PitchTrace = PitchTrace(),
    style: WaterfallStyle = WaterfallStyle.SPECTROGRAM,
    readings: (List<Target>) -> Pair<TunerReading?, TunerReading?> = { null to null },
) {
    val strings = strings(detected ?: config.instrumentId, config.a4)
    val (latest, shown) = readings(strings)
    val state = TunerUiState(
        config = config,
        detectedInstrument = detected?.let(::instrumentById),
        strings = strings,
        running = running,
        error = error,
        latest = latest,
        shown = shown,
        trace = trace,
        waterfallStyle = style,
    )
    TunerTheme {
        TunerScreen(state, NoActions)
    }
}

@Retention(AnnotationRetention.BINARY)
@Preview(widthDp = 390, heightDp = 844, showBackground = true, backgroundColor = 0xFF0F1216)
private annotation class PhonePreview

@PreviewTest
@PhonePreview
@Composable
fun Idle() = Screen(config = TunerConfig(), running = false)

@PreviewTest
@PhonePreview
@Composable
fun SearchingWideBand() = Screen(trace = trace { age -> (-22.0 + 3 * kotlin.math.sin(age * 9)).takeIf { age < 0.4 } }) {
    val wide = reading(it[1], cents = -22.0, source = PitchSource.WIDE, locked = false, spreadCents = 4.0)
    wide to wide
}

@PreviewTest
@PhonePreview
@Composable
fun LockedSharp() = Screen(
    // Tuned up from flat in two turns of the peg, overshooting a little.
    trace = trace { age ->
        when {
            age > 7.0 -> null
            age > 4.6 -> -38.0 + (7.0 - age) * 1.5
            age > 3.9 -> -34.0 + (4.6 - age) / 0.7 * 26
            age > 1.8 -> -8.0 + (3.9 - age) * 0.8
            age > 1.2 -> -6.0 + (1.8 - age) / 0.6 * 20
            else -> 14.0
        }
    },
) {
    val focused = reading(it[1], cents = 14.0, widthCents = 49.0, spreadCents = 1.8)
    focused to focused
}

@PreviewTest
@PhonePreview
@Composable
fun LockedInTune() = Screen(
    // Two plucks: the first settling in from sharp, the second steady.
    trace = trace { age ->
        when {
            age > 6.5 -> null
            age > 3.6 -> 0.6 + 9.0 * kotlin.math.exp(-(6.5 - age) * 2.2)
            age > 2.9 -> null
            else -> 0.6 + 4.0 * kotlin.math.exp(-(2.9 - age) * 5)
        }
    },
) {
    val focused = reading(it[3], cents = 0.6, inTune = true)
    focused to focused
}

/** A string more than half a semitone flat: the scale has slid towards it, the in-tune zone still in sight. */
@PreviewTest
@PhonePreview
@Composable
fun LockedBeyondTheScale() = Screen(
    trace = trace(width = { 7.0 }) { age -> -57.0 - 9 * kotlin.math.exp(-(PitchTrace.SECONDS - age) / 1.5) },
) {
    val focused = reading(it[3], cents = -57.0, widthCents = 92.0)
    focused to focused
}

/** A string a long way off: the scale shows more cents instead of sliding further. */
@PreviewTest
@PhonePreview
@Composable
fun LockedFarOffScaleWidened() = Screen(
    trace = trace(width = { 7.0 }) { age -> -165.0 + 4 * age },
) {
    val focused = reading(it[3], cents = -165.0, widthCents = 220.0)
    focused to focused
}

@PreviewTest
@PhonePreview
@Composable
fun LockedFarFlatHeldAfterNoteStopped() = Screen(
    trace = trace { age -> (-68.0).takeIf { age in 0.6..3.0 } },
) {
    reading(it[0], cents = null, widthCents = 280.0) to reading(it[0], cents = -68.0)
}

@PreviewTest
@PhonePreview
@Composable
fun PinnedWaitingForString() = Screen(config = Guitar.copy(pinnedTargetId = "s2:D3")) {
    reading(it[2], cents = null, pinned = true, widthCents = 400.0) to null
}

@PreviewTest
@PhonePreview
@Composable
fun ChromaticFilterOff() = Screen(config = TunerConfig(instrumentId = "chromatic", a4 = 442.0, smartFilter = SmartFilterMode.OFF)) {
    val target = Target("m58", "A#3", 58, 234.16)
    val wide = reading(target, cents = 9.0, source = PitchSource.WIDE, locked = false)
    wide to wide
}

@PreviewTest
@PhonePreview
@Composable
fun AutomaticListening() = Screen(config = TunerConfig())

@PreviewTest
@PhonePreview
@Composable
fun AutomaticFirstStringChromatic() = Screen(config = TunerConfig()) {
    val target = Target("m40", "E2", 40, 82.41)
    val focused = reading(target, cents = -15.0, widthCents = 50.0, instrumentId = "auto")
    focused to focused
}

@PreviewTest
@PhonePreview
@Composable
fun AutomaticIdentifiedUkulele() = Screen(
    config = TunerConfig(),
    detected = "ukulele",
    // A new string slowly going flat as it stretches.
    // The stripe widens as the bright overtones die away and leave less to resolve.
    trace = trace(width = { age -> 9.0 + (5.5 - age).coerceAtLeast(0.0) * 2.5 }) { age -> (6.0 + age * 2.4).takeIf { age < 5.5 } },
) {
    val focused = reading(it[2], cents = 6.0, widthCents = 41.0, instrumentId = "ukulele")
    focused to focused
}

/**
 * Not made-up data: ten seconds of a synthesized A string in a little room noise,
 * plucked three times and tuned up between plucks, run through the real engine.
 * What this shows is what the spectrogram of such a sound actually looks like.
 */
@PreviewTest
@PhonePreview
@Composable
fun RealEngineOutput() {
    val sampleRate = 48_000
    val block = 1024
    // (start second, cents at the pluck, cents it settles at after the peg is turned)
    val plucks = listOf(Triple(1.2, -36.0, -36.0), Triple(4.0, -36.0, -9.0), Triple(7.0, -9.0, 4.0))
    val noise = java.util.Random(4)
    val signal = FloatArray(10 * sampleRate)
    for ((start, from, to) in plucks) {
        var phase = 0.0
        for (i in (start * sampleRate).toInt() until signal.size) {
            val t = i.toDouble() / sampleRate - start
            val cents = from + (to - from) * ((t - 0.5) / 0.9).coerceIn(0.0, 1.0)
            phase += 2 * Math.PI * 110.0 * centsToRatio(cents) / sampleRate
            var sample = 0.0
            for (k in 1..8) {
                val stretch = kotlin.math.sqrt(1 + 5e-5 * k * k)
                sample += kotlin.math.sin(k * stretch * phase + k) / k * kotlin.math.exp(-t * (0.5 + 0.25 * k))
            }
            // The string is damped by the next pluck.
            val ringing = plucks.firstOrNull { it.first > start }?.let { ((it.first - start - t) / 0.02).coerceIn(0.0, 1.0) } ?: 1.0
            signal[i] += (0.2 * sample * ringing).toFloat()
        }
    }
    for (i in signal.indices) signal[i] += (0.002 * noise.nextGaussian()).toFloat()

    val engine = TunerEngine(sampleRate, block, Guitar)
    var trace = PitchTrace()
    var latest: TunerReading? = null
    var shown: TunerReading? = null
    for (offset in 0..signal.size - block step block) {
        val reading = engine.process(signal.copyOfRange(offset, offset + block))
        trace = trace.add(reading)
        latest = reading
        if (reading.voiced) shown = reading
    }
    TunerTheme {
        TunerScreen(TunerUiState(Guitar, strings = strings("guitar", 440.0), running = true, latest = latest, shown = shown, trace = trace), NoActions)
    }
}

/** The old look: the same history as [LockedSharp], as a line only. */
@PreviewTest
@PhonePreview
@Composable
fun LineStyle() = Screen(
    style = WaterfallStyle.LINE,
    trace = trace { age ->
        when {
            age > 7.0 -> null
            age > 4.6 -> -38.0 + (7.0 - age) * 1.5
            age > 3.9 -> -34.0 + (4.6 - age) / 0.7 * 26
            age > 1.8 -> -8.0 + (3.9 - age) * 0.8
            age > 1.2 -> -6.0 + (1.8 - age) / 0.6 * 20
            else -> 14.0
        }
    }.copy(rows = emptyList()),
) {
    val focused = reading(it[1], cents = 14.0, widthCents = 49.0)
    focused to focused
}

@PreviewTest
@PhonePreview
@Composable
fun Settings() = TunerTheme {
    SettingsScreen(TunerUiState(Guitar), NoSettingsActions)
}

@PreviewTest
@PhonePreview
@Composable
fun SettingsEditingCustomTuning() {
    val (copied, id) = TuningCatalog.standard.setEnabled("guitar-drop-d", true).duplicate("guitar")
    val catalog = copied.rename(id!!, "Cigar box").removeString(id, 5).removeString(id, 4).removeString(id, 3).shiftString(id, 0, 3).addString(id)
    TunerTheme {
        SettingsScreen(
            TunerUiState(Guitar.copy(tunings = catalog.inUse), tunings = catalog.tunings, waterfallStyle = WaterfallStyle.LINE),
            NoSettingsActions,
            initiallyEditing = id,
        )
    }
}

@PreviewTest
@PhonePreview
@Composable
fun MicrophoneDenied() = Screen(config = TunerConfig(), running = false, error = TunerError.PERMISSION_DENIED)
