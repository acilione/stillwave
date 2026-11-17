@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.stillwave.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.stillwave.AppViewModel
import app.stillwave.audio.WavFile
import app.stillwave.audio.WindowRenderer
import app.stillwave.audio.EditorAudioSource
import app.stillwave.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

@Composable fun EditorScreen(vm: AppViewModel, id: String, exporting: Boolean, back: () -> Unit, delete: (Recording) -> Unit) {
    val recording by remember(id) { vm.dao.observe(id) }.collectAsStateWithLifecycle(initialValue = null)
    val windows by remember(id) { vm.dao.observeWindows(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val checkpoints by remember(id) { vm.dao.observeCheckpoints(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val playback by vm.playback.collectAsStateWithLifecycle()
    val previewPreparing by vm.previewPreparing.collectAsStateWithLifecycle()
    val invalid = remember(id) { mutableStateMapOf<String, Boolean>() }
    val drafts = remember(id) { mutableStateMapOf<String, CutWindow>() }
    val liveWindows = windows.map { drafts[it.id] ?: it }
    fun updateWindow(window: CutWindow) {
        val previous = liveWindows.firstOrNull { it.id == window.id }
        if (playback.windowId == window.id && previous != null &&
            (previous.startMs != window.startMs || previous.endMs != window.endMs || previous.reverse != window.reverse || previous.speed != window.speed || previous.preservePitch != window.preservePitch)) vm.pausePlayback()
        drafts[window.id] = window; vm.window(window)
    }
    var rename by remember { mutableStateOf(false) }
    var fullSaveWindows by remember(id) { mutableStateOf<List<CutWindow>?>(null) }
    var removalWindows by remember(id) { mutableStateOf<List<CutWindow>?>(null) }
    var checkpointToEdit by remember { mutableStateOf<Checkpoint?>(null) }
    val source = recording
    if (source == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    val position = if (playback.recordingId == id) playback.positionMs.coerceIn(0, source.durationMs) else 0L
    val peaks by produceState(emptyList<Float>(), id) {
        value = withContext(Dispatchers.IO) {
            try { vm.app.repository.withRecording(source.id) { current -> EditorAudioSource.withWav(vm.app, current) { WavFile.peaks(it) } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
        }
    }
    val chosen = liveWindows.filter { it.selected }
    val chosenValid = chosen.isNotEmpty() && chosen.none { invalid[it.id] == true } && chosen.all { it.validFor(source.durationMs) }
    val fullError = WindowRenderer.fullExportError(chosen, source.durationMs)
    val removalError = WindowRenderer.removalExportError(chosen, source.durationMs)
    fun addWindow(wholeAudio: Boolean = false) {
        val start = if (wholeAudio || position >= source.durationMs) 0 else position
        vm.window(CutWindow(recordingId = id, startMs = start, endMs = if (wholeAudio) source.durationMs else minOf(start + 30_000, source.durationMs), ordinal = (windows.maxOfOrNull { it.ordinal } ?: -1) + 1))
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to recordings", back)
            Text("Edit recording", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            ActionIcon(Icons.Outlined.Edit, "Rename recording", { rename = true })
            ActionIcon(Icons.Outlined.DeleteOutline, "Delete recording", { delete(source) }, Coral)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(source.title, style = MaterialTheme.typography.headlineSmall)
                Text("Recorded ${defaultTitle(source.createdAt)}", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                Text(source.formatLabel, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                CaptureDetails(source.inputName, source.captureDetails)
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Waveform(peaks, Modifier.fillMaxWidth().height(88.dp), if (source.durationMs > 0) position.toFloat() / source.durationMs else 0f)
                        Slider(value = position.toFloat(), onValueChange = { vm.seek(source, it.toLong()) }, valueRange = 0f..source.durationMs.coerceAtLeast(1).toFloat())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(timestamp(position), color = Lime, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
                            Text(timestamp(source.durationMs), color = Muted, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button({ vm.play(source) }, Modifier.weight(1f), enabled = !previewPreparing) {
                                Icon(if (playback.recordingId == id && playback.playing && playback.windowId == null) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null)
                                Text(if (playback.recordingId == id && playback.playing && playback.windowId == null) " Pause" else " Play original")
                            }
                            OutlinedIconButton({ checkpointToEdit = Checkpoint(recordingId = id, positionMs = position, label = "Checkpoint ${checkpoints.size + 1}") }) { Icon(Icons.Outlined.BookmarkAdd, "Add checkpoint at playhead") }
                        }
                        TextButton({ addWindow(wholeAudio = true) }, enabled = source.durationMs > 0 && !exporting && !previewPreparing) {
                            Text("Edit whole audio")
                        }
                    }
                }
            }
            if (checkpoints.isNotEmpty()) item {
                Text("Checkpoints", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    checkpoints.forEach { point ->
                        InputChip(selected = false, onClick = { vm.seek(source, point.positionMs) }, label = { Text("${point.label} · ${durationLabel(point.positionMs)}") },
                            trailingIcon = { Icon(Icons.Outlined.Edit, "Edit checkpoint", Modifier.size(18.dp).clickable { checkpointToEdit = point }) })
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Audio windows", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    FilledTonalIconButton({ addWindow() }, enabled = source.durationMs > 0 && !exporting && !previewPreparing) { Icon(Icons.Outlined.Add, "Add cut window") }
                }
            }
            if (windows.isEmpty()) item {
                OutlinedCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.ContentCut, null, tint = Lime)
                        Text("No audio windows", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                        Text("Choose a section or the whole audio, then set reverse and speed before previewing or saving.", color = Muted, modifier = Modifier.padding(top = 6.dp))
                        TextButton({ addWindow() }, enabled = source.durationMs > 0 && !exporting && !previewPreparing) { Text("+ Add window") }
                    }
                }
            }
            items(liveWindows, key = { it.id }) { window ->
                CutWindowCard(window, source.title, source.durationMs, exporting || previewPreparing,
                    playback.recordingId == id && playback.windowId == window.id && playback.playing,
                    playback.recordingId == id && playback.windowId == window.id && playback.looping,
                    onUpdate = ::updateWindow, onValidity = { invalid[window.id] = !it }, onPlay = { vm.play(source, it) },
                    onLoop = { vm.toggleLoop(source, it) },
                    onSave = { vm.export(source, listOf(it)) }, onDelete = {
                        if (playback.windowId == window.id) vm.pausePlayback()
                        vm.deleteWindow(window); invalid.remove(window.id); drafts.remove(window.id)
                    })
            }
            item { Text("Times refer to the original audio. Save selected windows separately, apply their effects to a full copy, or remove them and join the remaining audio. Each option creates a new editable WAV; the original stays intact.", color = Muted, style = MaterialTheme.typography.bodySmall) }
        }
        Surface(color = Panel, shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                if (exporting) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Saving audio…", color = Muted, modifier = Modifier.padding(vertical = 8.dp)) }
                if (previewPreparing) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Preparing preview…", Modifier.weight(1f), color = Muted)
                        TextButton(vm::pausePlayback) { Text("Cancel preview") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ liveWindows.forEach { updateWindow(it.copy(selected = chosen.size != windows.size)) } }, enabled = windows.isNotEmpty() && !exporting && !previewPreparing) { Text(if (chosen.size == windows.size && windows.isNotEmpty()) "Uncheck all" else "Check all") }
                    Button({ vm.export(source, chosen) }, Modifier.weight(1f), enabled = chosenValid && !exporting && !previewPreparing) {
                        Icon(Icons.Outlined.SaveAlt, null, Modifier.size(18.dp)); Text(" Save all (${chosen.size})")
                    }
                }
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton({ fullSaveWindows = chosen.toList() }, Modifier.weight(1f).fillMaxHeight(), enabled = chosenValid && fullError == null && !exporting && !previewPreparing) {
                        Text("Save full audio")
                    }
                    OutlinedButton({ removalWindows = chosen.toList() }, Modifier.weight(1f).fillMaxHeight(), enabled = chosenValid && removalError == null && !exporting && !previewPreparing) {
                        Text("Remove selected sections")
                    }
                }
                if (chosenValid && fullError != null) Text(fullError, color = Coral, style = MaterialTheme.typography.labelSmall)
                if (chosenValid && removalError != null) Text(removalError, color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (rename) RenameDialog(source.title, { rename = false }) { vm.rename(source, it); rename = false }
    fullSaveWindows?.let { snapshot ->
        var title by remember(id) { mutableStateOf("${source.title} · Edited") }
        AlertDialog(onDismissRequest = { fullSaveWindows = null }, title = { Text("Save full audio") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Apply ${snapshot.size} selected ${if (snapshot.size == 1) "window" else "windows"} and keep the rest of the recording.")
                OutlinedTextField(title, { title = it.take(160) }, label = { Text("Recording name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }, confirmButton = { TextButton({ vm.export(source, snapshot, fullAudio = true, title = title.trim()); fullSaveWindows = null }, enabled = title.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton({ fullSaveWindows = null }) { Text("Cancel") } })
    }
    removalWindows?.let { snapshot ->
        var title by remember(id) { mutableStateOf("${source.title} · Sections removed") }
        AlertDialog(onDismissRequest = { removalWindows = null }, title = { Text("Save remaining audio") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Remove the ${snapshot.size} selected ${if (snapshot.size == 1) "window" else "windows"} and join the remaining audio into a new file. Overlaps are removed once. Reverse and speed settings are ignored. The original stays intact.")
                OutlinedTextField(title, { title = it.take(160) }, label = { Text("Recording name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }, confirmButton = { TextButton({ vm.export(source, snapshot, title = title.trim(), removeSelected = true); removalWindows = null }, enabled = title.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton({ removalWindows = null }) { Text("Cancel") } })
    }
    checkpointToEdit?.let { point ->
        var label by remember(point.id) { mutableStateOf(point.label) }
        var time by remember(point.id) { mutableStateOf(timestamp(point.positionMs)) }
        val parsed = parseTimestamp(time)
        val valid = parsed != null && parsed in 0..source.durationMs && label.isNotBlank()
        AlertDialog(onDismissRequest = { checkpointToEdit = null }, title = { Text("Checkpoint") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(label, { label = it.take(80) }, label = { Text("Label") }, singleLine = true)
                OutlinedTextField(time, { time = it }, label = { Text("Position · hh:mm:ss.SSS") }, singleLine = true, isError = !valid)
                if (checkpoints.any { it.id == point.id }) TextButton({ vm.deleteCheckpoint(point); checkpointToEdit = null }) { Text("Delete checkpoint", color = Coral) }
            }
        }, confirmButton = { TextButton({ vm.checkpoint(point.copy(label = label.trim(), positionMs = parsed!!)); checkpointToEdit = null }, enabled = valid) { Text("Save") } }, dismissButton = { TextButton({ checkpointToEdit = null }) { Text("Cancel") } })
    }
}

@Composable private fun CutWindowCard(window: CutWindow, originalTitle: String, duration: Long, busy: Boolean, playing: Boolean, looping: Boolean, onUpdate: (CutWindow) -> Unit,
    onValidity: (Boolean) -> Unit, onPlay: (CutWindow) -> Unit, onLoop: (CutWindow) -> Unit, onSave: (CutWindow) -> Unit, onDelete: () -> Unit) {
    var from by rememberSaveable(window.id) { mutableStateOf(timestamp(window.startMs)) }
    var to by rememberSaveable(window.id) { mutableStateOf(timestamp(window.endMs)) }
    val start = parseTimestamp(from); val end = parseTimestamp(to)
    val valid = start != null && end != null && start >= 0 && end > start && end <= duration
    LaunchedEffect(valid) { onValidity(valid) }
    fun commit(newFrom: String, newTo: String) {
        from = newFrom; to = newTo
        val a = parseTimestamp(newFrom); val b = parseTimestamp(newTo)
        if (a != null && b != null && a >= 0 && b > a && b <= duration) onUpdate(window.copy(startMs = a, endMs = b))
    }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(window.selected, { onUpdate(window.copy(selected = it)) }, enabled = !busy)
                Text("Window ${window.ordinal + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onDelete, enabled = !busy) { Icon(Icons.Outlined.Close, "Remove cut window", tint = Muted) }
            }
            OutlinedTextField(window.title, { onUpdate(window.copy(title = it.take(160))) },
                label = { Text("Cut name") }, placeholder = { Text(window.copy(title = "").exportTitle(originalTitle)) },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(from, { commit(it, to) }, label = { Text("From") }, singleLine = true, modifier = Modifier.weight(1f), isError = !valid, enabled = !busy, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
                OutlinedTextField(to, { commit(from, it) }, label = { Text("To") }, singleLine = true, modifier = Modifier.weight(1f), isError = !valid, enabled = !busy, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
            }
            if (!valid) Text("Use hh:mm:ss.SSS. End must follow start and stay within the recording.", color = Coral, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
            else {
                RangeSlider(value = start!!.toFloat()..end!!.toFloat(), onValueChange = { range -> commit(timestamp(range.start.toLong()), timestamp(range.endInclusive.toLong())) }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = !busy)
            }
            WindowEffects(window, busy, onUpdate)
            Text(if (valid) durationLabel(((end!! - start!!) / window.speed).toLong()) else "Invalid window", color = Muted, style = MaterialTheme.typography.labelMedium)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton({ onPlay(window.copy(startMs = start!!, endMs = end!!)) }, enabled = valid && !busy) {
                    Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)); Text(if (playing) " Pause" else " Preview")
                }
                FilterChip(looping, { onLoop(window.copy(startMs = start!!, endMs = end!!)) }, enabled = valid && !busy,
                    modifier = Modifier.testTag("loop-window-${window.id}").semantics { contentDescription = "Loop window ${window.ordinal + 1}" },
                    leadingIcon = { Icon(Icons.Outlined.Repeat, null, Modifier.size(18.dp)) }, label = { Text("Loop") })
                TextButton({ onSave(window.copy(startMs = start!!, endMs = end!!)) }, enabled = valid && !busy) { Text("Save cut") }
            }
        }
    }
}

@Composable private fun WindowEffects(window: CutWindow, busy: Boolean, onUpdate: (CutWindow) -> Unit) {
    var speedMenu by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(selected = window.reverse, onClick = { onUpdate(window.copy(reverse = !window.reverse)) }, enabled = !busy,
            modifier = Modifier.semantics { contentDescription = "Reverse window ${window.ordinal + 1}" },
            label = { Text("Reverse") })
        Box {
            OutlinedButton({ speedMenu = true }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Speed for window ${window.ordinal + 1}" }) {
                Text("Speed ${speedLabel(window.speed)}")
                Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp))
            }
            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0).forEach { speed ->
                    DropdownMenuItem(text = { Text(speedLabel(speed)) }, onClick = { onUpdate(window.copy(speed = speed)); speedMenu = false },
                        trailingIcon = { if (window.speed == speed) Icon(Icons.Outlined.Check, null) })
                }
            }
        }
        if (window.reverse || window.speed != 1.0 || window.preservePitch) TextButton({ onUpdate(window.copy(reverse = false, speed = 1.0, preservePitch = false)) }, enabled = !busy) { Text("Reset effects") }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Keep original pitch", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(window.preservePitch, { onUpdate(window.copy(preservePitch = it)) }, enabled = !busy,
            modifier = Modifier.semantics { contentDescription = "Keep pitch for window ${window.ordinal + 1}" })
    }
    if (window.speed != 1.0 && !window.preservePitch) Text("Speed changes pitch too.", color = Muted, style = MaterialTheme.typography.labelSmall)
}

private fun speedLabel(speed: Double) = "${speed.toString().removeSuffix(".0")}×"
