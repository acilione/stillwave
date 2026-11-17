package app.stillwave.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A read-only guide: opening it never changes the recording format or a picker draft. */
@Composable internal fun RecordingQualityGuide(onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back from quality guide", onBack)
                Text("Recording quality guide", Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("quality-guide"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item("start") {
                    GuideSection("start", "Easy starting points") {
                        Text("Assign formats to your three recording buttons. Try a short recording before a long session.")
                        GuidePoint("Voice notes", "AAC · 16,000 Hz · 64 kbps · mono. Small files for speech.")
                        GuidePoint("Guitar or guitar + voice", "WAV · 48,000 Hz · 16-bit · mono. Keeps an uncompressed recording for editing.")
                        GuidePoint("Jam session", "WAV · 48,000 Hz · 16-bit · stereo if your input provides real stereo. For smaller files, try AAC at 48,000 Hz and 256 kbps.")
                    }
                }
                item("best") {
                    GuideSection("best", "Best available · Auto") {
                        Text("Checks the microphone in use, chooses a supported WAV format, and remembers it when possible. It stays separate from your three assigned qualities.")
                        Text("It can use more space and take longer to start. It checks supported formats, not how good the microphone sounds; larger numbers cannot remove noise or improve the microphone itself.", color = Muted)
                    }
                }
                item("format") {
                    GuideSection("format", "WAV or AAC?") {
                        GuidePoint("WAV", "Keeps the captured sound without lossy compression. Useful for editing or keeping an original; files are larger.")
                        GuidePoint("AAC", "Makes smaller .m4a files by discarding some sound detail. Useful for sharing and long recordings. More kbps can reduce compression artifacts.")
                        Text("AAC needs temporary WAV space while recording and conversion time when you save. Edited copies are saved as WAV; converting AAC to WAV does not recover lost detail.", color = Muted)
                    }
                }
                item("rate") {
                    GuideSection("rate", "Hz: how often sound is sampled") {
                        Text("48,000 Hz means 48,000 sound measurements per second. 48 kHz is another way to write the same value.")
                        Text("16,000 Hz is a useful starting point for speech. 44,100 or 48,000 Hz suits music. Higher rates can capture higher frequencies, but 96,000 Hz does not automatically make a phone recording clearer.")
                    }
                }
                item("bitrate") {
                    GuideSection("bitrate", "kbps: data used each second") {
                        Text("kbps means kilobits per second. For AAC, you choose a target: higher values usually keep more detail and make larger files. It is not a volume control.")
                        Text("For WAV, kbps is calculated from Hz, channels, and bit depth. You do not set it independently. WAV and AAC kbps cannot be compared as a sound-quality score.")
                    }
                }
                item("channels") {
                    GuideSection("channels", "Mono or stereo?") {
                        Text("Mono stores one channel and plays through both sides of normal headphones. It is often enough for a voice or one instrument.")
                        Text("Stereo stores left and right channels. It can capture the spread of a band or room when the microphone input provides distinct left/right sound. Selecting stereo cannot create that separation from a mono microphone.")
                    }
                }
                item("depth") {
                    GuideSection("depth", "Bit depth: detail in WAV samples") {
                        Text("16-bit and 24-bit describe how precisely each sound measurement is stored. 16-bit is a practical starting point; 24-bit can be useful for later level changes when the input provides that precision.")
                        Text("More bits make larger WAV files. They do not fix an overloaded microphone. The 32-bit WAV presets use integer samples, not a guarantee of clip-proof recording.", color = Muted)
                    }
                }
                item("together") {
                    GuideSection("together", "How settings work together") {
                        GuidePoint("WAV: settings multiply", "At 48,000 Hz and 16-bit, mono uses about 5.76 MB/min; stereo uses 11.52 MB/min. Keeping stereo and switching to 24-bit uses 17.28 MB/min. Doubling Hz also doubles WAV size.")
                        GuidePoint("AAC: kbps sets the size target", "128 kbps uses about 0.96 MB/min; 256 kbps uses 1.92 MB/min. At the same kbps, switching mono to stereo or increasing Hz keeps roughly the same final size: the encoder must fit more information into that budget.")
                        Text("AAC kbps covers the whole recording, including both stereo channels. Actual AAC size varies. MB here means 1,000,000 bytes; these estimates exclude small file headers.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                item("placement") {
                    GuideSection("placement", "Before raising the numbers") {
                        Text("Make a short test and listen. Keep fingers away from the microphones, reduce nearby noise, and move the phone to balance voice and instruments.")
                        Text("If loud parts crackle or distort, move farther from the loud source or reduce input gain if your microphone offers it. Raising Hz or kbps cannot repair sound already clipped at the microphone.")
                        Text("Available formats depend on the phone and connected input. If a format is unavailable, choose another assigned quality or Best available.", color = Muted)
                    }
                }
            }
        }
    }
}

@Composable private fun GuideSection(id: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("quality-guide-$id"),
        colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.semantics { heading() }, color = Lime, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable private fun GuidePoint(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(description, style = MaterialTheme.typography.bodyMedium)
    }
}
