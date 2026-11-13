package io.github.alfonslm.tuner.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alfonslm.tuner.app.R
import io.github.alfonslm.tuner.app.TunerError
import io.github.alfonslm.tuner.app.TunerUiState
import io.github.alfonslm.tuner.engine.PitchSource
import io.github.alfonslm.tuner.engine.SmartFilterMode
import io.github.alfonslm.tuner.engine.TunerReading
import io.github.alfonslm.tuner.music.AUTO_INSTRUMENT_ID
import io.github.alfonslm.tuner.music.InstrumentPreset
import io.github.alfonslm.tuner.music.Target
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the user can do on the screen. */
class TunerActions(
    val onToggleRunning: () -> Unit,
    val onInstrument: (String) -> Unit,
    val onReferencePitch: (Double) -> Unit,
    val onSmartFilter: (Boolean) -> Unit,
    val onTogglePin: (String) -> Unit,
    val onOpenSettings: () -> Unit,
)

@Composable
fun TunerScreen(state: TunerUiState, actions: TunerActions, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .background(TunerColors.Background)
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsBar(state, actions)
        Display(state, Modifier.weight(1f))
        if (state.strings.isNotEmpty()) {
            StringButtons(state.strings, activeId = activeTarget(state)?.id, pinnedId = state.config.pinnedTargetId, actions.onTogglePin)
        }
        Button(
            onClick = actions.onToggleRunning,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp),
            border = if (state.running) BorderStroke(1.dp, TunerColors.Line) else null,
            colors = if (state.running) {
                ButtonDefaults.buttonColors(containerColor = TunerColors.Panel, contentColor = TunerColors.Text)
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            Text(
                stringResource(if (state.running) R.string.stop else R.string.start),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        state.error?.let { error ->
            Text(
                stringResource(
                    when (error) {
                        TunerError.PERMISSION_DENIED -> R.string.error_permission
                        TunerError.MICROPHONE_UNAVAILABLE -> R.string.error_microphone
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = TunerColors.Bad,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The note on display: what is sounding, else what is held, else what the filter is locked on. */
private fun activeTarget(state: TunerUiState): Target? = state.shown?.target ?: state.latest?.lock?.target

@Composable
private fun SettingsBar(state: TunerUiState, actions: TunerActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
        // In automatic mode the picker shows what was recognised, and the label says who chose it.
        val detected = state.detectedInstrument
        Labelled(stringResource(if (detected != null) R.string.instrument_auto else R.string.instrument), Modifier.weight(1f)) {
            InstrumentPicker(
                label = (detected ?: state.instruments.firstOrNull { it.id == state.config.instrumentId })?.name.orEmpty(),
                instruments = state.instruments,
                onSelect = actions.onInstrument,
            )
        }
        Labelled(stringResource(R.string.reference_pitch)) {
            ReferencePitchStepper(state.config.a4, actions.onReferencePitch)
        }
        // One place, two uses. While tuning it is the smart-filter switch, the one thing worth
        // reaching for mid-tune; while stopped it is the way into the settings, which are not.
        if (state.running) {
            Labelled(stringResource(R.string.smart_filter)) {
                val label = stringResource(R.string.smart_filter)
                Box(Modifier.size(width = 64.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                    Switch(
                        checked = state.config.smartFilter == SmartFilterMode.AUTO,
                        onCheckedChange = actions.onSmartFilter,
                        modifier = Modifier.semantics { contentDescription = label },
                    )
                }
            }
        } else {
            val settings = stringResource(R.string.settings)
            Labelled(settings) {
                Box(
                    Modifier
                        .size(width = 64.dp, height = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(TunerColors.Panel)
                        .border(1.dp, TunerColors.Line, RoundedCornerShape(10.dp))
                        .clickable(onClickLabel = settings, role = Role.Button, onClick = actions.onOpenSettings),
                    contentAlignment = Alignment.Center,
                ) {
                    // The label above already names it.
                    Icon(Icons.Default.Settings, contentDescription = null, tint = TunerColors.Text)
                }
            }
        }
    }
}

@Composable
private fun Labelled(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TunerColors.Muted)
        content()
    }
}

@Composable
private fun InstrumentPicker(label: String, instruments: List<InstrumentPreset>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = TunerColors.Panel, contentColor = TunerColors.Text),
        ) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("▾", color = TunerColors.Muted)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (instrument in instruments) {
                DropdownMenuItem(
                    text = { Text(instrument.name) },
                    onClick = {
                        expanded = false
                        onSelect(instrument.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun ReferencePitchStepper(a4: Double, onChange: (Double) -> Unit) {
    Row(
        Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(TunerColors.Panel)
            .border(1.dp, TunerColors.Line, RoundedCornerShape(10.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton("−", stringResource(R.string.reference_lower)) { onChange(a4 - 1) }
        Text(
            a4.roundToInt().toString(),
            modifier = Modifier.widthIn(min = 36.dp),
            color = TunerColors.Text,
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = TABULAR),
            textAlign = TextAlign.Center,
        )
        StepButton("+", stringResource(R.string.reference_raise)) { onChange(a4 + 1) }
    }
}

@Composable
private fun StepButton(symbol: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 40.dp, height = 48.dp)
            .clickable(onClickLabel = description, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, style = MaterialTheme.typography.titleMedium, color = TunerColors.Muted)
    }
}

@Composable
private fun Display(state: TunerUiState, modifier: Modifier = Modifier) {
    val shown = state.shown
    val cents = shown?.cents
    val tone by animateColorAsState(toneOf(shown), label = "tone")
    val readingAlpha by animateFloatAsState(if (shown != null && !state.live) 0.35f else 1f, label = "readingAlpha")

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = TunerColors.Panel,
        border = BorderStroke(1.dp, TunerColors.Line),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Once the instrument is known the string buttons say which string this is,
            // so the name steps back and leaves the room to the meter and its history.
            val noteSize = if (state.strings.isEmpty()) 104.sp else 44.sp
            Text(
                activeTarget(state)?.label ?: stringResource(R.string.no_note),
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(readingAlpha),
                color = tone,
                fontSize = noteSize,
                lineHeight = noteSize,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Column(Modifier.weight(1f)) {
                Meter(
                    cents = cents?.toFloat(),
                    tone = tone,
                    inTuneCents = state.config.display.inTuneCents.toFloat(),
                    dimmed = !state.live,
                )
                Waterfall(
                    trace = state.trace,
                    style = state.waterfallStyle,
                    inTuneCents = state.config.display.inTuneCents.toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 28.dp)
                    .alpha(readingAlpha),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val numbers = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TABULAR)
                if (shown != null && cents != null) {
                    val rounded = cents.roundToInt()
                    Text(pluralStringResource(R.plurals.cents_value, abs(rounded), signed(rounded)), style = numbers)
                    Text(
                        stringResource(R.string.hertz_value, String.format(Locale.US, "%.1f", shown.frequency)),
                        style = numbers,
                        color = TunerColors.Muted,
                    )
                }
            }
            ModeLine(state)
        }
    }
}

private fun toneOf(reading: TunerReading?): Color {
    val cents = reading?.cents ?: return TunerColors.Muted
    return when {
        reading.inTune -> TunerColors.Good
        abs(cents) > 25 -> TunerColors.Bad
        else -> TunerColors.Warn
    }
}

private fun signed(cents: Int): String = when {
    cents > 0 -> "+$cents"
    cents < 0 -> "−${-cents}"
    else -> "0"
}

/** One line that says what the smart filter is doing right now. */
@Composable
private fun ModeLine(state: TunerUiState) {
    val lock = state.latest?.lock
    val (dot, text) = when {
        !state.running -> TunerColors.Line to stringResource(R.string.mode_idle)
        lock != null -> {
            val name = stringResource(if (lock.pinned) R.string.mode_pinned else R.string.mode_locked, lock.target.label)
            val detail = when {
                !state.live -> stringResource(R.string.mode_detail_waiting)
                state.latest.source == PitchSource.FOCUSED -> stringResource(R.string.mode_detail_filter, lock.widthCents.roundToInt())
                else -> stringResource(R.string.mode_detail_wide)
            }
            TunerColors.Accent to "$name · $detail"
        }
        !state.live -> TunerColors.Warn to stringResource(
            if (state.config.instrumentId == AUTO_INSTRUMENT_ID && state.detectedInstrument == null) {
                R.string.mode_listening_auto
            } else {
                R.string.mode_listening
            },
        )
        state.config.smartFilter == SmartFilterMode.AUTO -> TunerColors.Warn to stringResource(R.string.mode_wide_waiting)
        else -> TunerColors.Warn to stringResource(R.string.mode_wide_filter_off)
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(dot, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = TunerColors.Muted, textAlign = TextAlign.Center)
    }
}

/** One button per string: highlights the string being heard, tap to pin the tuner to it. */
@Composable
private fun StringButtons(strings: List<Target>, activeId: String?, pinnedId: String?, onTogglePin: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (string in strings) {
            val pinned = string.id == pinnedId
            val active = string.id == activeId
            val description = stringResource(R.string.pin_string, string.label)
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(shape)
                    .background(if (pinned) TunerColors.Accent else TunerColors.Panel)
                    .border(1.dp, if (pinned || active) TunerColors.Accent else TunerColors.Line, shape)
                    .clickable(role = Role.Button) { onTogglePin(string.id) }
                    .semantics {
                        contentDescription = description
                        selected = pinned
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    string.label,
                    color = when {
                        pinned -> TunerColors.OnAccent
                        active -> TunerColors.Accent
                        else -> TunerColors.Text
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Digits of equal width, so the readouts do not jitter sideways as the numbers change. */
private const val TABULAR = "tnum"
