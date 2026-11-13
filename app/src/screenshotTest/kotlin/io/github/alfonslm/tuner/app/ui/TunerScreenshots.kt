package io.github.alfonslm.tuner.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.alfonslm.tuner.app.TunerError
import io.github.alfonslm.tuner.app.TunerUiState
import io.github.alfonslm.tuner.engine.LockInfo
import io.github.alfonslm.tuner.engine.PitchSource
import io.github.alfonslm.tuner.engine.SmartFilterMode
import io.github.alfonslm.tuner.engine.TunerConfig
import io.github.alfonslm.tuner.engine.TunerMode
import io.github.alfonslm.tuner.engine.TunerReading
import io.github.alfonslm.tuner.music.Target
import io.github.alfonslm.tuner.music.TargetSetOptions
import io.github.alfonslm.tuner.music.centsToRatio
import io.github.alfonslm.tuner.music.createTargetSet
import io.github.alfonslm.tuner.music.instrumentById

// The screen in each state the tuner can be in, on a phone-sized canvas.
// `./gradlew :app:updateDebugScreenshotTest` regenerates the reference images,
// `./gradlew :app:validateDebugScreenshotTest` fails if the UI no longer matches them.

private val NoActions = TunerActions({}, {}, {}, {}, {})

private fun strings(config: TunerConfig): List<Target> =
    createTargetSet(instrumentById(config.instrumentId), config.a4, TargetSetOptions(300.0, 30.0, 300.0)).list.orEmpty()

private fun reading(
    target: Target,
    cents: Double?,
    source: PitchSource? = PitchSource.FOCUSED,
    locked: Boolean = true,
    pinned: Boolean = false,
    widthCents: Double = 35.0,
    inTune: Boolean = false,
) = TunerReading(
    time = 10.0,
    mode = if (locked) TunerMode.LOCKED else TunerMode.SEARCH,
    levelDb = -30.0,
    source = source.takeIf { cents != null },
    target = target,
    frequency = cents?.let { target.frequency * centsToRatio(it) },
    cents = cents,
    confidence = 0.95,
    inTune = inTune,
    lock = if (locked) LockInfo(target, pinned, widthCents) else null,
)

@Composable
private fun Screen(
    config: TunerConfig = TunerConfig(),
    running: Boolean = true,
    error: TunerError? = null,
    readings: (List<Target>) -> Pair<TunerReading?, TunerReading?> = { null to null },
) {
    val strings = strings(config)
    val (latest, shown) = readings(strings)
    TunerTheme {
        TunerScreen(TunerUiState(config, strings = strings, running = running, error = error, latest = latest, shown = shown), NoActions)
    }
}

@Retention(AnnotationRetention.BINARY)
@Preview(widthDp = 390, heightDp = 844, showBackground = true, backgroundColor = 0xFF0F1216)
private annotation class PhonePreview

@PreviewTest
@PhonePreview
@Composable
fun Idle() = Screen(running = false)

@PreviewTest
@PhonePreview
@Composable
fun SearchingWideBand() = Screen {
    val wide = reading(it[1], cents = -22.0, source = PitchSource.WIDE, locked = false)
    wide to wide
}

@PreviewTest
@PhonePreview
@Composable
fun LockedSharp() = Screen {
    val focused = reading(it[1], cents = 14.0, widthCents = 49.0)
    focused to focused
}

@PreviewTest
@PhonePreview
@Composable
fun LockedInTune() = Screen {
    val focused = reading(it[3], cents = 0.6, inTune = true)
    focused to focused
}

@PreviewTest
@PhonePreview
@Composable
fun LockedFarFlatHeldAfterNoteStopped() = Screen {
    reading(it[0], cents = null, widthCents = 280.0) to reading(it[0], cents = -68.0)
}

@PreviewTest
@PhonePreview
@Composable
fun PinnedWaitingForString() = Screen(config = TunerConfig(pinnedTargetId = "s2:D3")) {
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
fun MicrophoneDenied() = Screen(running = false, error = TunerError.PERMISSION_DENIED)
