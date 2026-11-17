package app.stillwave.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.stillwave.AppViewModel
import app.stillwave.data.AppSettings

@Composable fun MicrophoneSettings(settings: AppSettings, vm: AppViewModel) {
    val inputs by rememberMicrophoneInputs(vm.app)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Recording microphone", color = Lime, style = MaterialTheme.typography.titleMedium)
        Text("Choose the input for new recordings in the app, widget and projects. Playback follows Android's audio output: connect headphones to listen while recording with the phone, headset or another connected microphone.", color = Muted, style = MaterialTheme.typography.bodySmall)
        Column(Modifier.selectableGroup()) {
            MicrophoneRow("Automatic", settings.selectedMicrophone.isEmpty(), "microphone-auto") { vm.selectMicrophone("", "") }
            inputs.forEach { input ->
                MicrophoneRow(input.label, input.key == settings.selectedMicrophone, "microphone-${input.key}") { vm.selectMicrophone(input.key, input.label) }
            }
        }
        if (settings.selectedMicrophone.isNotEmpty() && inputs.none { it.key == settings.selectedMicrophone }) {
            Text("${settings.selectedMicrophoneName.ifBlank { "Selected microphone" }} is disconnected. Reconnect it or select another input before recording.", color = Coral, style = MaterialTheme.typography.bodySmall)
        }
        Text("The selected input is checked when capture starts. If Android cannot use it, recording reports an error. Bluetooth headset microphones can change the output quality or route; available combinations depend on the phone and headset.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun MicrophoneRow(label: String, selected: Boolean, tag: String, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).selectable(selected, role = Role.RadioButton, onClick = choose).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
