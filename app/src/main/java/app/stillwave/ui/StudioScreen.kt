@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.stillwave.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.stillwave.AppViewModel
import app.stillwave.audio.RecordingService
import app.stillwave.data.*
import kotlinx.coroutines.flow.*
import java.util.UUID

@Composable fun StudioScreen(vm: AppViewModel, record: (Quality, String) -> Unit) {
    val studio = vm.studio
    val projects by studio.projects.collectAsStateWithLifecycle()
    val project by studio.project.collectAsStateWithLifecycle()
    val projectId by studio.projectId.collectAsStateWithLifecycle()
    val all by vm.all.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val busy by studio.busy.collectAsStateWithLifecycle()
    val live by studio.live.collectAsStateWithLifecycle()
    val canUndo by studio.canUndo.collectAsStateWithLifecycle()
    val capture by RecordingService.state.collectAsStateWithLifecycle()
    val exportId by studio.exportId.collectAsStateWithLifecycle()
    val unreadable by vm.app.multitrackStore.unreadableProjects.collectAsStateWithLifecycle()
    var create by rememberSaveable { mutableStateOf(false) }
    var editProject by rememberSaveable { mutableStateOf(false) }
    var addTracks by rememberSaveable { mutableStateOf(false) }
    var editTrack by remember { mutableStateOf<MultitrackTrack?>(null) }
    var deleteProject by remember { mutableStateOf<MultitrackProject?>(null) }
    var saveMix by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var selectedClip by rememberSaveable(projectId) { mutableStateOf<String?>(null) }
    var cutCursor by rememberSaveable(projectId) { mutableLongStateOf(0) }
    LaunchedEffect(project?.id, project?.durationMs) {
        project?.let { cutCursor = cutCursor.coerceIn(0, it.durationMs) }
    }
    LaunchedEffect(project?.tracks?.map { it.id }) {
        // A recreated controller briefly has no project while the store flow reconnects.
        project?.let { loaded -> if (loaded.tracks.none { it.id == selectedClip }) selectedClip = loaded.tracks.firstOrNull()?.id }
    }
    LaunchedEffect(exportId) {
        exportId?.let { id ->
            val work = WorkManager.getInstance(vm.app).getWorkInfoByIdFlow(UUID.fromString(id)).filterNotNull().first { it.state.isFinished }
            vm.notify(if (work.state == WorkInfo.State.SUCCEEDED) "Mix saved to your recording library" else work.outputData.getString("error") ?: "Mix export cancelled")
            studio.clearExport()
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back from multitrack", studio::back)
            Text(if (projectId == null) "Multitrack & loops" else project?.title ?: "Project", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (project != null && !(capture.active && capture.projectId == projectId)) ActionIcon(Icons.Outlined.Tune, "Project settings", { editProject = true })
        }
        val current = project
        if (projectId == null) {
            Button({ create = true }, Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Icon(Icons.Outlined.Add, null); Text(" New project") }
            LazyColumn(Modifier.weight(1f).testTag("studio-projects"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (unreadable > 0) item { Text("$unreadable projects could not be read. Their files have been kept.", color = Coral) }
                if (projects.isEmpty()) item { Text("Create a project, then add recordings or record new tracks.", color = Muted) }
                items(projects, key = { it.id }) { item ->
                    Card(onClick = { studio.show(item.id) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (item.loopPlayback) Icons.Outlined.Repeat else Icons.Outlined.QueueMusic, null, tint = Lime)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium)
                                Text("${item.lanes.size} tracks · ${item.tracks.size} clips · ${durationLabel(item.durationMs)}${if (item.loopPlayback) " · Loop" else ""}", color = Muted)
                            }
                            ActionIcon(Icons.Outlined.DeleteOutline, "Delete project ${item.title}", { deleteProject = item })
                        }
                    }
                }
            }
        } else if (current == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Project unavailable", color = Muted) }
        } else {
            val liveHere = live.projectId == current.id
            val renderedPlaying = playback.recordingId == current.id && playback.playing
            val playing = liveHere && live.playing
            val position = (if (liveHere) live.positionMs else if (playback.recordingId == current.id) playback.positionMs else 0L).coerceIn(0, current.durationMs)
            val taking = capture.active && capture.projectId == current.id
            // The live mix has no notification controls, so it pauses when the app leaves the screen,
            // except while a take is being recorded over it.
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
                if (!(RecordingService.state.value.active && RecordingService.state.value.projectId == current.id)) studio.pauseLive()
            }
            val waveforms by rememberProjectWaveforms(vm.app, current, all)
            val selected = current.tracks.firstOrNull { it.id == selectedClip }
            LazyColumn(Modifier.weight(1f).testTag("studio-tracks"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    TrackTimeline(current, position, enabled = !taking, waveforms = waveforms, selectedId = selectedClip, cursorMs = cutCursor,
                        select = { id, time -> selectedClip = id; cutCursor = time }, move = studio::moveTrack)
                    ProjectClipControls(current, selected, cutCursor, !taking, canUndo, all.firstOrNull { it.id == selected?.recordingId }?.durationMs, studio,
                        select = { id, time -> selectedClip = id; cutCursor = time }, setCursor = { cutCursor = it }, edit = { editTrack = selected })
                    Slider(position.toFloat(), { studio.seek(it.toLong()) }, valueRange = 0f..current.durationMs.toFloat(),
                        enabled = liveHere || playback.recordingId == current.id, modifier = Modifier.semantics { contentDescription = "Project playhead" })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(timestamp(position), color = Lime)
                        Text(timestamp(current.durationMs), color = Muted)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button({ studio.play() }, Modifier.weight(1f).testTag("project-play"), enabled = current.tracks.isNotEmpty()) {
                            Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null)
                            Text(if (playing) " Pause" else " Play")
                        }
                        FilledTonalIconButton({ studio.play(cutCursor) }, Modifier.testTag("project-play-from-cut"), enabled = current.tracks.isNotEmpty()) {
                            Icon(Icons.Outlined.SkipNext, "Play from cut position")
                        }
                        FilterChip(current.loopPlayback, { studio.update { it.copy(loopPlayback = !it.loopPlayback) } },
                            label = { Text("Loop") }, leadingIcon = { Icon(Icons.Outlined.Repeat, null, Modifier.size(18.dp)) }, enabled = !taking)
                    }
                    if (liveHere && live.loading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Opening tracks…", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Preparing mix for the background player…", Modifier.weight(1f), color = Muted)
                            TextButton(studio::cancelPreparation) { Text("Cancel") }
                        }
                    } else Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Live preview · edits are heard while playing", Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodySmall)
                        TextButton(studio::playRendered, enabled = current.tracks.isNotEmpty()) {
                            Text(if (renderedPlaying) "Pause background" else "Background player")
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${current.lanes.size}/$MAX_MULTITRACK_TRACKS tracks · ${current.tracks.size}/$MAX_MULTITRACK_CLIPS clips", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton({ addTracks = true }, enabled = !taking && current.canAddTrack) { Text("Add recordings") }
                    }
                }
                items(listOfNotNull(selected), key = { it.id }) { track ->
                    val missing = all.none { it.id == track.recordingId }
                    TrackCard(track, missing, taking,
                        update = { transform -> studio.track(track.id, transform) }, edit = { editTrack = track }, remove = { studio.removeTrack(track.id) })
                }
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Record new track", style = MaterialTheme.typography.titleMedium)
                            RecordingMicrophonePicker(settings, vm, enabled = !capture.active)
                            if (taking) {
                                Text(if (capture.preparing) "Starting microphone…" else if (capture.finishing) "Saving track…" else "Recording · ${timestamp(capture.elapsedMs)}", color = Coral)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button({ RecordingService.command(vm.app, RecordingService.SAVE) }, Modifier.weight(1f), enabled = !capture.preparing && !capture.finishing) { Text("Stop & save") }
                                    OutlinedButton({ if (settings.confirmDiscard) discard = true else RecordingService.command(vm.app, RecordingService.DISCARD) }, enabled = !capture.finishing) { Text("Discard") }
                                }
                            } else {
                                Text(if (current.loopPlayback) "Records one ${durationLabel(current.durationMs)} cycle, then saves it as a repeating track." else "Saves a separate track at the current playback position.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                Text(if (settings.allowPlaybackWhileRecording) "Play the project first to hear your backing tracks. Use headphones to keep speaker sound out of the microphone. Adjust Position if the take needs alignment."
                                    else "To hear backing tracks while recording, enable Playback while recording in Settings.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    settings.recordingSlots.forEachIndexed { index, quality ->
                                        FilledTonalButton({ record(quality, current.id) }, Modifier.weight(1f).semantics { contentDescription = "Record track with button ${index + 1}" },
                                            enabled = !capture.active && !busy && current.canAddTrack, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Icon(Icons.Outlined.Mic, null, tint = Coral, modifier = Modifier.size(20.dp))
                                                Text(quality.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                                Text(quality.sampleRateLabel, style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                                TextButton({ record(Quality.BEST, current.id) }, Modifier.fillMaxWidth(), enabled = !capture.active && !busy && current.canAddTrack) { Text("Best available · Auto") }
                            }
                        }
                    }
                }
                item {
                    Text("Project changes are saved automatically. Play mixes the tracks live, so volume, mute, solo, moves and cuts are heard right away; it pauses when you leave the app. Background player renders the mix first for lock-screen controls. Save mix creates a new 48,000 Hz, 16-bit stereo WAV with peak normalization; tracks and original recordings remain editable.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    if (exportId != null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        TextButton({ exportId?.let { WorkManager.getInstance(vm.app).cancelWorkById(UUID.fromString(it)) } }) { Text("Cancel mix export") }
                    }
                    Button({ saveMix = true }, Modifier.fillMaxWidth(), enabled = current.tracks.isNotEmpty() && exportId == null && !taking) { Text("Save mix to library") }
                }
            }
        }
    }
    if (create) ProjectDialog(null, { create = false }) { title, duration, loop -> studio.create(title, loop, durationMs = duration); create = false }
    if (editProject && project != null) ProjectDialog(project, { editProject = false }) { title, duration, loop ->
        studio.update { it.copy(title = title, durationMs = duration, loopPlayback = loop) }; editProject = false
    }
    if (addTracks && project != null) RecordingPicker(all, minOf(MAX_MULTITRACK_TRACKS - project!!.lanes.size, MAX_MULTITRACK_CLIPS - project!!.tracks.size), { addTracks = false }) { studio.add(it); addTracks = false }
    editTrack?.let { track -> TrackDialog(track, all.firstOrNull { it.id == track.recordingId }?.durationMs, { editTrack = null }) { edited ->
        studio.editClip(edited); editTrack = null
    } }
    deleteProject?.let { item -> AlertDialog(onDismissRequest = { deleteProject = null }, title = { Text("Delete project?") },
        text = { Text("Delete “${item.title}”? Library recordings and exported mixes are kept.") },
        confirmButton = { TextButton({ studio.delete(item); deleteProject = null }) { Text("Delete", color = Coral) } },
        dismissButton = { TextButton({ deleteProject = null }) { Text("Cancel") } }) }
    if (saveMix && project != null) RenameDialog("${project!!.title.take(154)} · Mix", { saveMix = false }, "Save mix") { studio.export(it); saveMix = false }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard this track?") }, text = { Text("The current microphone take will be discarded.") },
        confirmButton = { TextButton({ RecordingService.command(vm.app, RecordingService.DISCARD); discard = false }) { Text("Discard", color = Coral) } },
        dismissButton = { TextButton({ discard = false }) { Text("Keep recording") } })
}

@Composable private fun TrackCard(track: MultitrackTrack, missing: Boolean, recording: Boolean,
    update: ((MultitrackTrack) -> MultitrackTrack) -> Unit, edit: () -> Unit, remove: () -> Unit) {
    var volume by remember(track.id, track.gain) { mutableFloatStateOf(track.gain.toFloat()) }
    Card(Modifier.fillMaxWidth().testTag("track-${track.id}"), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium)
                    Text(if (missing) "Source recording missing" else "${durationLabel(track.sourceDurationMs)} · Position ${timestamp(track.offsetMs)}", color = if (missing) Coral else Muted, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(edit, enabled = !recording) { Icon(Icons.Outlined.Edit, "Edit clip ${track.title}") }
                IconButton(remove, enabled = !recording) { Icon(Icons.Outlined.Close, "Remove clip ${track.title}") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(track.muted, { update { it.copy(muted = !it.muted) } }, label = { Text("Mute") })
                FilterChip(track.solo, { update { it.copy(solo = !it.solo) } }, label = { Text("Solo") })
                FilterChip(track.loop, { update { it.copy(loop = !it.loop) } }, label = { Text("Repeat clip") }, enabled = !recording)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${(volume * 100).toInt()}%", Modifier.width(52.dp), style = MaterialTheme.typography.labelMedium)
                Slider(volume, { volume = it }, valueRange = 0f..2f, onValueChangeFinished = { update { it.copy(gain = volume.toDouble()) } },
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Volume for ${track.title}" })
            }
        }
    }
}

@Composable private fun ProjectDialog(project: MultitrackProject?, dismiss: () -> Unit, save: (String, Long, Boolean) -> Unit) {
    var title by rememberSaveable { mutableStateOf(project?.title ?: "Project ${defaultTitle(System.currentTimeMillis())}") }
    var duration by rememberSaveable { mutableStateOf(timestamp(project?.durationMs ?: 10_000)) }
    var loop by rememberSaveable { mutableStateOf(project?.loopPlayback ?: false) }
    val ms = parseTimestamp(duration)
    val valid = title.isNotBlank() && ms != null && ms in 1..MAX_MULTITRACK_DURATION_MS
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (project == null) "New project" else "Project settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it.take(160) }, label = { Text("Project name") }, singleLine = true)
            OutlinedTextField(duration, { duration = it }, label = { Text(if (loop) "Loop duration" else "Project duration") }, supportingText = { Text("hh:mm:ss.SSS, up to 6 hours") }, singleLine = true, isError = ms == null || ms !in 1..MAX_MULTITRACK_DURATION_MS)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(loop, { loop = it }); Text("Loop station") }
            Text("Loop station repeats the project and records one cycle per new take. Track lengths and start positions can be edited individually.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton({ save(title.trim(), ms!!, loop) }, enabled = valid) { Text(if (project == null) "Create" else "Save") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

@Composable private fun TrackDialog(track: MultitrackTrack, sourceDuration: Long?, dismiss: () -> Unit, save: (MultitrackTrack) -> Unit) {
    var title by rememberSaveable(track.id) { mutableStateOf(track.title) }
    var from by rememberSaveable(track.id) { mutableStateOf(timestamp(track.sourceStartMs)) }
    var to by rememberSaveable(track.id) { mutableStateOf(timestamp(track.sourceEndMs)) }
    var offset by rememberSaveable(track.id) { mutableStateOf(timestamp(track.offsetMs)) }
    val a = parseTimestamp(from); val b = parseTimestamp(to); val at = parseTimestamp(offset)
    val valid = title.isNotBlank() && a != null && b != null && at != null && b > a && b <= (sourceDuration ?: MAX_MULTITRACK_DURATION_MS) && at <= MAX_MULTITRACK_DURATION_MS
    AlertDialog(onDismissRequest = dismiss, title = { Text("Clip settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it.take(160) }, label = { Text("Clip name") }, singleLine = true)
            OutlinedTextField(from, { from = it }, label = { Text("From") }, singleLine = true)
            OutlinedTextField(to, { to = it }, label = { Text("To") }, singleLine = true)
            OutlinedTextField(offset, { offset = it }, label = { Text("Position in project") }, supportingText = { Text("hh:mm:ss.SSS. Use this to align recordings.") }, singleLine = true)
            Text("Repeat clip loops the From–To section. Position sets its phase when repeating. Source audio stays intact.", style = MaterialTheme.typography.bodySmall)
            if (!valid) Text("Check the name and timestamps.", color = Coral)
        }
    }, confirmButton = { TextButton({ save(track.copy(title = title.trim(), sourceStartMs = a!!, sourceEndMs = b!!, offsetMs = at!!)) }, enabled = valid) { Text("Save") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

@Composable private fun RecordingPicker(recordings: List<Recording>, capacity: Int, dismiss: () -> Unit, add: (List<Recording>) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Add recordings") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text("Search recordings") }, singleLine = true)
            LazyColumn(Modifier.heightIn(max = 350.dp)) {
                items(recordings.filter { it.title.contains(query, true) }, key = { it.id }) { item ->
                    Row(Modifier.fillMaxWidth().clickable { selected = if (item.id in selected) selected - item.id else if (selected.size < capacity) selected + item.id else selected }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(item.id in selected, null)
                        Column(Modifier.padding(vertical = 10.dp)) { Text(item.title); Text(durationLabel(item.durationMs), color = Muted) }
                    }
                }
            }
            Text("${selected.size} selected · $capacity slots available", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton({ add(recordings.filter { it.id in selected }) }, enabled = selected.isNotEmpty()) { Text("Add tracks") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}
