package app.stillwave.ui

import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.stillwave.AppViewModel
import app.stillwave.BuildConfig
import app.stillwave.audio.PcmEncoding
import app.stillwave.data.*

@Composable fun SettingsScreen(settings: AppSettings, vm: AppViewModel, back: () -> Unit) {
    val microphones by vm.app.microphoneProfiles.flow.collectAsStateWithLifecycle(initialValue = emptyList())
    var qualityPicker by rememberSaveable { mutableStateOf<Int?>(null) }
    var showQualityGuide by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to library", back)
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Appearance", color = Lime, style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(settings.themeMode == mode, { vm.setting(Settings.THEME_MODE, mode.name) },
                        SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size), Modifier.testTag("theme-${mode.name}"),
                        icon = { SegmentedButtonDefaults.Icon(settings.themeMode == mode) {
                            Icon(when (mode) { ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto; ThemeMode.LIGHT -> Icons.Outlined.LightMode; ThemeMode.DARK -> Icons.Outlined.DarkMode },
                                null, Modifier.size(SegmentedButtonDefaults.IconSize))
                        } }) { Text(mode.label, maxLines = 1, softWrap = false) }
                }
            }
            Text(settings.themeMode.detail, color = Muted, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Recording controls", color = Lime, style = MaterialTheme.typography.titleMedium)
            RecordControlStyle.entries.forEach { style ->
                Row(Modifier.fillMaxWidth().clickable { vm.setting(Settings.RECORD_STYLE, style.name) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(settings.recordStyle == style, { vm.setting(Settings.RECORD_STYLE, style.name) })
                    Column(Modifier.padding(start = 8.dp)) { Text(style.label, style = MaterialTheme.typography.titleMedium); Text(style.detail, color = Muted, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Text("The app and widget also offer Best available, which checks the active microphone before recording.", color = Muted, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            SettingToggle("Playback while recording", "Keep audio playing while the microphone records a separate track. When off, recording pauses playback.", settings.allowPlaybackWhileRecording) {
                vm.setting(Settings.ALLOW_PLAYBACK_WHILE_RECORDING, it)
            }
            MicrophoneSettings(settings, vm)
            HorizontalDivider()
            Text("Recording button qualities", color = Lime, style = MaterialTheme.typography.titleMedium)
            Text("Assign the three buttons from left to right. The app, quality selector, and widget use these assignments.", color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton({ showQualityGuide = true }) {
                Icon(Icons.Outlined.HelpOutline, null, Modifier.size(20.dp))
                Text(" Recording quality guide")
            }
            settings.recordingSlots.forEachIndexed { index, quality ->
                QualitySettingButton("Recording button ${index + 1}", quality) { qualityPicker = index }
            }
            Text("Choose lossless WAV or compressed AAC. WAV bitrate follows Hz, bit depth, and channel count; AAC lets you choose Hz and target kbps separately. Available formats depend on your microphone and Android version.", color = Muted, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Remembered microphones", color = Lime, style = MaterialTheme.typography.titleMedium)
            Text("Best available remembers each identifiable input’s validated format and checks it again before use. Forget an input to run a fresh quality check.", color = Muted)
            if (microphones.isEmpty()) Text("No remembered microphones", color = Muted, style = MaterialTheme.typography.bodySmall)
            microphones.forEach { microphone ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(microphone.inputName)
                        Text(microphone.format.label, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton({ vm.forgetMicrophone(microphone.key) }) { Text("Forget") }
                }
            }
            HorizontalDivider()
            SettingToggle("Confirm before discarding", "Ask before discarding an unsaved recording.", settings.confirmDiscard) { vm.setting(Settings.CONFIRM, it) }
            HorizontalDivider()
            Text("Long press a recording", color = Lime, style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup()) {
                LongPressAction.entries.forEach { action ->
                    Row(Modifier.fillMaxWidth().testTag("long-press-${action.name}")
                        .selectable(selected = settings.longPressAction == action, role = Role.RadioButton,
                            onClick = { vm.setting(Settings.LONG_PRESS_ACTION, action.name) })
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(settings.longPressAction == action, onClick = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(action.label, style = MaterialTheme.typography.titleMedium)
                            Text(action.detail, color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text("The three-dot button always opens the recording menu.", color = Muted, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Playback", color = Lime, style = MaterialTheme.typography.titleMedium)
            SettingToggle("Show loop button in recording list", "Show a loop button beside Play. Loop playback remains available in the recording menu and editor when hidden.", settings.showLoopButton) {
                vm.setting(Settings.SHOW_LOOP_BUTTON, it)
            }
            SettingToggle("Stop playback when screen locks", "Pause when the screen turns off or locks. Unlocking does not restart playback.", settings.stopPlaybackWhenLocked) {
                vm.setting(Settings.STOP_PLAYBACK_WHEN_LOCKED, it)
            }
            Text("By default, audio keeps playing in the background with playback controls on the lock screen and in notifications.", color = Muted, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Stillwave ${BuildConfig.VERSION_NAME} · Recordings stay on this device until you share them. Creation dates are stored separately from titles. Pinned recordings always appear first.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
    qualityPicker?.takeIf { it in settings.recordingSlots.indices }?.let { slot ->
        QualityPickerDialog("Recording button ${slot + 1}", settings.recordingSlots[slot],
            showGuide = { showQualityGuide = true }, dismiss = { qualityPicker = null }) { quality ->
            vm.setting(Settings.RECORDING_SLOTS[slot], quality.name)
            qualityPicker = null
        }
    }
    if (showQualityGuide) {
        Dialog(onDismissRequest = { showQualityGuide = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            RecordingQualityGuide(onBack = { showQualityGuide = false })
        }
    }
}

@Composable private fun QualitySettingButton(title: String, quality: Quality, choose: () -> Unit) {
    OutlinedButton(choose, Modifier.fillMaxWidth().testTag(title), contentPadding = PaddingValues(14.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(if (quality == Quality.BEST) quality.label else "${quality.label} · ${quality.sampleRateLabel} · ${quality.bitRateLabel}",
                style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Outlined.ExpandMore, null)
    }
}

@Composable private fun QualityPickerDialog(title: String, selected: Quality,
    showGuide: () -> Unit, dismiss: () -> Unit, choose: (Quality) -> Unit) {
    var aac by rememberSaveable(title) { mutableStateOf(selected.isAac) }
    var rate by rememberSaveable(title) { mutableIntStateOf(if (selected.isAac) selected.sampleRate else 48000) }
    var bitRate by rememberSaveable(title) { mutableIntStateOf(selected.aacBitRate ?: 128000) }
    var channels by rememberSaveable(title) { mutableIntStateOf(if (selected.isAac) selected.channels else 2) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(!aac, { aac = false }, label = { Text("WAV") })
                FilterChip(aac, { aac = true }, label = { Text("AAC") })
                TextButton(showGuide, Modifier.testTag("quality-picker-guide")) { Text("Guide") }
            }
            if (aac) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    QualityChoiceField("Sample rate", "$rate Hz", Quality.aacRates, { "$it Hz" }) { rate = it }
                    QualityChoiceField("Target bitrate", "${bitRate / 1000} kbps", Quality.aacBitRates, { "${it / 1000} kbps" }) { bitRate = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(channels == 1, { channels = 1 }, label = { Text("Mono") })
                        FilterChip(channels == 2, { channels = 2 }, label = { Text("Stereo") })
                    }
                    Text(Quality.aac(rate, bitRate, channels).detail, style = MaterialTheme.typography.bodyMedium)
                    Text("AAC is saved as .m4a. Conversion finishes when you save. Temporary audio uses ${Quality.aac(rate, bitRate, channels).format.storageLabel} while recording.",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(Quality.catalog, key = { it.name }) { quality ->
                        val supported = Build.VERSION.SDK_INT >= 31 || quality.encoding !in listOf(PcmEncoding.PCM24, PcmEncoding.PCM32)
                        Row(Modifier.fillMaxWidth().testTag("quality-choice-${quality.name}").clickable(enabled = supported) { choose(quality) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected == quality, { choose(quality) }, enabled = supported)
                            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                                Text(quality.label, color = if (supported) MaterialTheme.colorScheme.onSurface else Muted,
                                    style = MaterialTheme.typography.titleSmall)
                                if (quality != Quality.BEST) Text("${quality.sampleRateLabel} · ${quality.bitRateLabel}", style = MaterialTheme.typography.bodySmall)
                                Text(if (supported) quality.detail else "${quality.detail} · Requires Android 12", color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = {
        if (aac) TextButton({ choose(Quality.aac(rate, bitRate, channels)) }) { Text("Assign quality") }
        else TextButton(dismiss) { Text("Close") }
    }, dismissButton = { if (aac) TextButton(dismiss) { Text("Cancel") } })
}

@Composable private fun QualityChoiceField(title: String, selected: String, choices: List<Int>, label: (Int) -> String, change: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().testTag("AAC $title"), contentPadding = PaddingValues(12.dp)) {
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.labelMedium); Text(selected) }
            Icon(Icons.Outlined.ExpandMore, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { value ->
                DropdownMenuItem(text = { Text(label(value)) }, onClick = { change(value); expanded = false }, modifier = Modifier.testTag("AAC $title $value"))
            }
        }
    }
}

@Composable private fun SettingToggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        Switch(checked, onChange, Modifier.testTag("setting-$title"))
    }
}
