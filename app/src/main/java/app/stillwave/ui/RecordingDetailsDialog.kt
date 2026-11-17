package app.stillwave.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import app.stillwave.audio.PcmEncoding
import app.stillwave.audio.RecordingFileDetails
import app.stillwave.audio.fileSizeMb
import app.stillwave.audio.pcmBitRateLabel
import app.stillwave.data.Recording
import app.stillwave.data.timestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun RecordingDetailsDialog(recording: Recording, file: File, folderName: String?, dismiss: () -> Unit) {
    val details by produceState<RecordingFileDetails?>(null, recording.id, file.path) {
        value = withContext(Dispatchers.IO) { RecordingFileDetails.read(file) }
    }
    var microphoneExpanded by rememberSaveable(recording.id) { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val numbers = NumberFormat.getIntegerInstance(locale)
    AlertDialog(onDismissRequest = dismiss, title = { Text("Recording details") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(recording.title, style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                val snapshot = details
                if (snapshot == null) Text("Reading file details…", color = Muted)
                else {
                    snapshot.sizeBytes?.let { bytes ->
                        FileDetail("File size", "${fileSizeMb(bytes, locale)} (${numbers.format(bytes)} bytes)")
                    }
                    snapshot.error?.let { Text(it, color = Coral) }
                    snapshot.audio?.let { audio ->
                        FileDetail("Format", "WAV (.wav)")
                        FileDetail("Encoding", if (audio.encoding == PcmEncoding.FLOAT32) "IEEE float PCM" else "Signed integer PCM")
                        FileDetail("Bit depth", audio.encoding.label)
                        FileDetail("Sample rate", "${numbers.format(audio.rate)} Hz")
                        FileDetail("Channels", if (audio.channels == 1) "Mono (1)" else "Stereo (2)")
                        FileDetail("Bitrate", pcmBitRateLabel(audio, locale))
                        FileDetail("Duration", timestamp(audio.durationMs))
                    }
                    snapshot.compressed?.let { audio ->
                        FileDetail("Format", "M4A (.m4a)")
                        FileDetail("Encoding", "AAC-LC")
                        FileDetail("Sample rate", "${numbers.format(audio.rate)} Hz")
                        FileDetail("Channels", if (audio.channels == 1) "Mono (1)" else "Stereo (2)")
                        FileDetail("Bitrate", String.format(locale, "%.1f kbps", audio.bitRate / 1000.0))
                        FileDetail("Duration", timestamp(audio.durationMs))
                    }
                }
                FileDetail("Recorded", DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss z", locale)
                    .format(Instant.ofEpochMilli(recording.createdAt).atZone(ZoneId.systemDefault())))
                FileDetail("Folder", folderName ?: "Unfiled")
                FileDetail("File name", file.name)
                recording.inputName?.takeIf { it.isNotBlank() }?.let { FileDetail("Microphone", it) }
                recording.captureDetails?.takeIf { it.isNotBlank() }?.let { capture ->
                    TextButton({ microphoneExpanded = !microphoneExpanded }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (microphoneExpanded) "Hide microphone details" else "Microphone details")
                    }
                    if (microphoneExpanded) Text(capture, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        },
        confirmButton = { TextButton(dismiss) { Text("Close") } })
}

@Composable private fun FileDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = Muted, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
