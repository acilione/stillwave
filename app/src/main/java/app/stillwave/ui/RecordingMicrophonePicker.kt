package app.stillwave.ui

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.stillwave.AppViewModel
import app.stillwave.audio.InputOption
import app.stillwave.audio.MicrophoneInputs
import app.stillwave.data.AppSettings

/** Shared by Settings and the in-project selector, including input hot-plug events. */
@Composable internal fun rememberMicrophoneInputs(context: Context): State<List<InputOption>> {
    val application = context.applicationContext
    val inputs = remember(application) { mutableStateOf(MicrophoneInputs.options(application)) }
    DisposableEffect(application) {
        val manager = application.getSystemService(AudioManager::class.java)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { inputs.value = MicrophoneInputs.options(application) }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { inputs.value = MicrophoneInputs.options(application) }
        }
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { manager.unregisterAudioDeviceCallback(callback) }
    }
    return inputs
}

/** Uses the same choice as Settings; an active take keeps its already-open microphone. */
@Composable fun RecordingMicrophonePicker(settings: AppSettings, vm: AppViewModel, enabled: Boolean = true) {
    val inputs by rememberMicrophoneInputs(vm.app)
    var choosing by rememberSaveable { mutableStateOf(false) }
    val label = if (settings.selectedMicrophone.isEmpty()) "Automatic"
        else inputs.firstOrNull { it.key == settings.selectedMicrophone }?.label
            ?: settings.selectedMicrophoneName.ifBlank { "Selected microphone" }
    val unavailable = settings.selectedMicrophone.isNotEmpty() && inputs.none { it.key == settings.selectedMicrophone }
    LaunchedEffect(enabled) { if (!enabled) choosing = false }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { choosing = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("studio-microphone-picker")
                .semantics { contentDescription = "Choose recording microphone" },
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
            Icon(Icons.Outlined.Mic, null, Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp), horizontalAlignment = Alignment.Start) {
                Text("Microphone", style = MaterialTheme.typography.labelSmall)
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Outlined.ArrowDropDown, null)
        }
        if (unavailable) Text("$label is disconnected. Choose another input or Automatic.", color = Coral,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("studio-microphone-unavailable"))
        if (!enabled) Text("Stop the recording to change its microphone.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    if (choosing && enabled) {
        AlertDialog(onDismissRequest = { choosing = false }, title = { Text("Recording microphone") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Used for new recordings in the app, widget, and projects.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).selectableGroup().testTag("studio-microphone-options")) {
                    item {
                        MicrophoneChoice("Automatic", settings.selectedMicrophone.isEmpty(), "studio-microphone-auto") {
                            vm.selectMicrophone("", ""); choosing = false
                        }
                    }
                    items(inputs, key = { it.key }) { input ->
                        MicrophoneChoice(input.label, settings.selectedMicrophone == input.key, "studio-microphone-${input.key}") {
                            vm.selectMicrophone(input.key, input.label); choosing = false
                        }
                    }
                }
                if (unavailable) Text("The saved microphone is not connected.", color = Coral, style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton({ choosing = false }) { Text("Close") } })
    }
}

@Composable private fun MicrophoneChoice(label: String, selected: Boolean, tag: String, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)
        .selectable(selected, role = Role.RadioButton, onClick = choose).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Text(label, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
