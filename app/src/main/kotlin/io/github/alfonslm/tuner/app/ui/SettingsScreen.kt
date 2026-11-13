package io.github.alfonslm.tuner.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.alfonslm.tuner.app.R
import io.github.alfonslm.tuner.app.Tuning
import io.github.alfonslm.tuner.app.TuningCatalog
import io.github.alfonslm.tuner.app.TunerUiState
import io.github.alfonslm.tuner.app.WaterfallStyle

/** What the user can do on the settings screen. */
class SettingsActions(
    val onBack: () -> Unit,
    val onWaterfallStyle: (WaterfallStyle) -> Unit,
    val onTuningEnabled: (id: String, enabled: Boolean) -> Unit,
    /** Returns the id of the copy, so its editor can be opened straight away. */
    val onDuplicateTuning: (id: String) -> String?,
    val onRenameTuning: (id: String, name: String) -> Unit,
    val onShiftString: (id: String, index: Int, semitones: Int) -> Unit,
    val onAddString: (id: String) -> Unit,
    val onRemoveString: (id: String, index: Int) -> Unit,
    val onDeleteTuning: (id: String) -> Unit,
)

/**
 * Settings: how the waterfall looks, and the list of tunings. Every change
 * applies at once; there is nothing to save.
 *
 * @param initiallyEditing id of a custom tuning whose editor starts open
 */
@Composable
fun SettingsScreen(state: TunerUiState, actions: SettingsActions, modifier: Modifier = Modifier, initiallyEditing: String? = null) {
    var editing by rememberSaveable { mutableStateOf(initiallyEditing) }

    Column(
        modifier
            .fillMaxSize()
            .background(TunerColors.Background)
            .safeDrawingPadding(),
    ) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val back = stringResource(R.string.back)
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(onClickLabel = back, role = Role.Button, onClick = actions.onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = back, tint = TunerColors.Text)
            }
            Text(
                stringResource(R.string.settings),
                modifier = Modifier
                    .padding(start = 4.dp)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = TunerColors.Text,
            )
        }

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_waterfall))
            WaterfallChoice(state.waterfallStyle, actions.onWaterfallStyle)

            Spacer(Modifier.height(8.dp))
            SectionTitle(stringResource(R.string.settings_tunings))
            Text(stringResource(R.string.tunings_hint), style = MaterialTheme.typography.bodySmall, color = TunerColors.Muted)
            for (tuning in state.tunings) {
                TuningCard(
                    tuning = tuning,
                    editing = editing == tuning.id,
                    onEditing = { editing = if (it) tuning.id else null },
                    onDuplicate = { actions.onDuplicateTuning(tuning.id)?.let { copy -> editing = copy } },
                    actions = actions,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = TunerColors.Accent,
    )
}

/** Two-way choice with a line under it saying what the selected style shows. */
@Composable
private fun WaterfallChoice(selected: WaterfallStyle, onSelect: (WaterfallStyle) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(TunerColors.Panel)
            .border(1.dp, TunerColors.Line, shape),
    ) {
        for (style in WaterfallStyle.entries) {
            val chosen = style == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(if (chosen) TunerColors.Accent else TunerColors.Panel)
                    .clickable(role = Role.RadioButton) { onSelect(style) }
                    .semantics { this.selected = chosen },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(if (style == WaterfallStyle.SPECTROGRAM) R.string.waterfall_spectrogram else R.string.waterfall_line),
                    color = if (chosen) TunerColors.OnAccent else TunerColors.Text,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
    Text(
        stringResource(if (selected == WaterfallStyle.SPECTROGRAM) R.string.waterfall_spectrogram_hint else R.string.waterfall_line_hint),
        style = MaterialTheme.typography.bodySmall,
        color = TunerColors.Muted,
    )
}

/** One tuning: its name and notes, the switch that puts it in use, and what can be done with it. */
@Composable
private fun TuningCard(
    tuning: Tuning,
    editing: Boolean,
    onEditing: (Boolean) -> Unit,
    onDuplicate: () -> Unit,
    actions: SettingsActions,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = TunerColors.Panel,
        border = BorderStroke(1.dp, if (editing) TunerColors.Accent else TunerColors.Line),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        tuning.preset.name.ifBlank { stringResource(R.string.tuning_custom) },
                        style = MaterialTheme.typography.titleSmall,
                        color = if (tuning.enabled) TunerColors.Text else TunerColors.Muted,
                    )
                    Text(
                        tuning.strings.joinToString("  "),
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = TunerColors.Muted,
                    )
                    if (tuning.custom) {
                        val count = pluralStringResource(R.plurals.tuning_string_count, tuning.strings.size, tuning.strings.size)
                        Text(
                            "${stringResource(R.string.tuning_custom)} · $count",
                            style = MaterialTheme.typography.labelSmall,
                            color = TunerColors.Accent,
                        )
                    }
                }
                val label = stringResource(R.string.tuning_in_use, tuning.preset.name)
                Switch(
                    checked = tuning.enabled,
                    onCheckedChange = { actions.onTuningEnabled(tuning.id, it) },
                    modifier = Modifier.semantics { contentDescription = label },
                )
            }
            Row {
                TextButton(onClick = onDuplicate) { Text(stringResource(R.string.tuning_duplicate)) }
                if (tuning.custom) {
                    TextButton(onClick = { onEditing(!editing) }) {
                        Text(stringResource(if (editing) R.string.tuning_done else R.string.tuning_edit))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { actions.onDeleteTuning(tuning.id) }) {
                        Text(stringResource(R.string.tuning_delete), color = TunerColors.Bad)
                    }
                }
            }
            if (editing && tuning.custom) TuningEditor(tuning, actions)
        }
    }
}

/** Name, the note of every string, and adding or removing strings. */
@Composable
private fun TuningEditor(tuning: Tuning, actions: SettingsActions) {
    Column(Modifier.padding(end = 4.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = tuning.preset.name,
            onValueChange = { actions.onRenameTuning(tuning.id, it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.tuning_name)) },
            singleLine = true,
        )
        tuning.strings.forEachIndexed { index, note ->
            val number = index + 1
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.tuning_string_number, number),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TunerColors.Muted,
                )
                Row(
                    Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(TunerColors.Background)
                        .border(1.dp, TunerColors.Line, RoundedCornerShape(10.dp)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Step("−", stringResource(R.string.tuning_string_lower, number)) { actions.onShiftString(tuning.id, index, -1) }
                    Text(
                        note,
                        modifier = Modifier.widthIn(min = 44.dp),
                        color = TunerColors.Text,
                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                        textAlign = TextAlign.Center,
                    )
                    Step("+", stringResource(R.string.tuning_string_raise, number)) { actions.onShiftString(tuning.id, index, 1) }
                }
                Spacer(Modifier.width(4.dp))
                Step("✕", stringResource(R.string.tuning_string_remove, number), enabled = tuning.strings.size > 1) {
                    actions.onRemoveString(tuning.id, index)
                }
            }
        }
        if (tuning.strings.size < TuningCatalog.MAX_STRINGS) {
            TextButton(onClick = { actions.onAddString(tuning.id) }) { Text("+ " + stringResource(R.string.tuning_string_add)) }
        }
    }
}

@Composable
private fun Step(symbol: String, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clickable(enabled = enabled, onClickLabel = description, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, style = MaterialTheme.typography.titleMedium, color = TunerColors.Muted.copy(alpha = if (enabled) 1f else 0.3f))
    }
}
