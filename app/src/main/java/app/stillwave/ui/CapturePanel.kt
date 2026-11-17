@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.stillwave.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.stillwave.audio.CaptureState
import app.stillwave.audio.RecordingService
import app.stillwave.data.Quality
import app.stillwave.data.RecordControlStyle
import app.stillwave.data.Settings
import app.stillwave.data.durationLabel

@Composable internal fun CapturePanel(state: CaptureState, selectedSlot: Int, style: RecordControlStyle,
    selectSlot: (Int) -> Unit, record: (Quality) -> Unit, command: (String) -> Unit,
    presets: List<Quality> = Quality.fixedPresets) {
    // The most recent tap owns this panel's selection. Preference writes can acknowledge
    // earlier taps later; accepting those emissions could make Record use the wrong slot.
    var localSlot by rememberSaveable { mutableStateOf<Int?>(null) }
    val currentSlot = localSlot ?: selectedSlot
    val selectedQuality = if (currentSlot == Settings.AUTO_RECORDING_SLOT) Quality.BEST else presets.getOrElse(currentSlot) { presets.first() }
    Card(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Panel)) {
        // Each idle style reserves the same space during capture, without a size animation.
        // Keep a minimum, not a fixed height, so larger text can grow the panel as needed.
        Column(Modifier.fillMaxWidth().padding(12.dp).heightIn(min = if (style == RecordControlStyle.SINGLE_BUTTON) 192.dp else 176.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (state.active) { if (state.finishing) "SAVING" else if (state.preparing) "STARTING" else if (state.paused) "PAUSED" else "●  RECORDING" } else "RECORDER",
                    modifier = Modifier.weight(1f), color = if (state.active && !state.paused) Coral else Muted,
                    style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp)
                Text(when {
                    state.active -> "${state.quality.label} · ${state.quality.containerLabel}"
                    style == RecordControlStyle.SINGLE_BUTTON -> "${selectedQuality.label} · ${selectedQuality.containerLabel}"
                    else -> presets.map { it.containerLabel }.distinct().joinToString(" / ")
                }, color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            if (state.active) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.finishing && state.quality.isAac) "Saving AAC…" else if (state.quality.isAac && !state.preparing)
                        "${state.quality.sampleRateLabel} · ${state.quality.bitRateLabel} target · ${if (state.quality.channels == 1) "mono" else "stereo"}"
                        else state.format?.label ?: "Starting recording…", color = Muted,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    CaptureDetails(state.inputName, state.captureDetails, compact = true)
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(durationLabel(state.elapsedMs), fontSize = 24.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f))
                    OutlinedButton({ command(RecordingService.CHECKPOINT) }, Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        enabled = !state.finishing && !state.preparing) {
                        Icon(Icons.Outlined.BookmarkAdd, "Add checkpoint while recording", Modifier.size(20.dp))
                        Column {
                            Text(" Checkpoint (${state.checkpointCount})", style = MaterialTheme.typography.labelMedium)
                            state.lastCheckpointMs?.let { Text(" ${durationLabel(it)}", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({ command(RecordingService.DISCARD) }, Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp), enabled = !state.finishing) {
                        Icon(Icons.Outlined.DeleteOutline, "Stop and discard")
                    }
                    if (state.projectId == null) FilledTonalButton({ command(if (state.paused) RecordingService.RESUME else RecordingService.PAUSE) },
                        Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 16.dp),
                        enabled = !state.finishing && !state.preparing) {
                        Icon(if (state.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                            if (state.paused) "Resume recording" else "Pause recording")
                    }
                    Button({ command(RecordingService.SAVE) }, Modifier.weight(1f).heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp), enabled = !state.finishing && !state.preparing) {
                        Icon(Icons.Outlined.Stop, null); Text(" Save")
                    }
                }
            } else if (style == RecordControlStyle.THREE_BUTTONS) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { preset ->
                        Button({ record(preset) }, Modifier.weight(1f).heightIn(min = 72.dp)
                            .semantics { contentDescription = "Record ${preset.label}" }, shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Icon(Icons.Outlined.Mic, null, Modifier.size(20.dp))
                                Text(preset.label, style = MaterialTheme.typography.labelMedium)
                                Text(preset.sampleRateLabel, style = MaterialTheme.typography.labelSmall)
                                Text(preset.bitRateLabel, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                FilledTonalButton({ record(Quality.BEST) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { contentDescription = "Record Best available" }) {
                    Icon(Icons.Outlined.Mic, null, Modifier.size(20.dp))
                    Text(" Best available · Auto")
                }
            } else {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 48.dp)) {
                    presets.forEachIndexed { index, preset ->
                        SegmentedButton(selected = currentSlot == index, onClick = { localSlot = index; selectSlot(index) },
                            modifier = Modifier.fillMaxHeight().testTag("quality-selector-${index + 1}"),
                            shape = SegmentedButtonDefaults.itemShape(index, presets.size),
                            icon = {}, label = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(preset.label, style = MaterialTheme.typography.labelMedium)
                                    Text(preset.sampleRateLabel, style = MaterialTheme.typography.labelSmall)
                                    Text(preset.bitRateLabel, style = MaterialTheme.typography.labelSmall)
                                }
                            })
                    }
                }
                FilterChip(selected = currentSlot == Settings.AUTO_RECORDING_SLOT,
                    onClick = { localSlot = Settings.AUTO_RECORDING_SLOT; selectSlot(Settings.AUTO_RECORDING_SLOT) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Best available · Auto", style = MaterialTheme.typography.labelMedium) })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("00:00", fontSize = 24.sp, fontFamily = FontFamily.Monospace)
                        Text(if (selectedQuality == Quality.BEST) "Check microphone on start" else "${selectedQuality.sampleRateLabel} · ${selectedQuality.bitRateLabel}",
                            color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                    Button({ record(selectedQuality) }, Modifier.widthIn(min = 72.dp).heightIn(min = 64.dp),
                        shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(12.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Mic, null, Modifier.size(24.dp)); Text("Record", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
