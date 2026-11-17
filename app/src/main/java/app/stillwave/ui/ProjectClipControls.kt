@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.stillwave.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.stillwave.StudioController
import app.stillwave.data.*
import java.util.UUID

@Composable fun ProjectClipControls(project: MultitrackProject, selected: MultitrackTrack?, cursor: Long,
    enabled: Boolean, canUndo: Boolean, sourceDuration: Long?, studio: StudioController,
    select: (String, Long) -> Unit, setCursor: (Long) -> Unit, edit: () -> Unit) {
    var picker by rememberSaveable(project.id) { mutableStateOf(false) }
    var cutTime by rememberSaveable(project.id) { mutableStateOf(false) }
    var trim by rememberSaveable(project.id) { mutableStateOf(false) }
    var lanePicker by rememberSaveable(project.id) { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) { cutTime = false; trim = false; lanePicker = false } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ picker = true }, Modifier.weight(1f).testTag("project-choose-clip"), enabled = project.tracks.isNotEmpty()) {
                Icon(Icons.Outlined.ArrowDropDown, null)
                Text(selected?.let { "Clip ${project.tracks.indexOf(it) + 1} · ${it.title}" } ?: "Select a clip", maxLines = 2)
            }
            IconButton(studio::undoClipEdit, enabled = enabled && canUndo, modifier = Modifier.testTag("project-undo")) {
                Icon(Icons.Outlined.Undo, "Undo last clip edit")
            }
        }
        if (selected != null) {
            TextButton({ cutTime = true }, enabled = enabled, modifier = Modifier.testTag("project-cut-position")) {
                Text("Cut position · ${timestamp(cursor)}")
            }
            Slider(cursor.coerceIn(0, project.durationMs).toFloat(), { setCursor(it.toLong()) },
                valueRange = 0f..project.durationMs.toFloat(), enabled = enabled,
                colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxWidth().testTag("project-cut-cursor").semantics {
                    contentDescription = "Cut cursor"
                    stateDescription = timestamp(cursor)
                })
            val interior = cursor in 1 until project.durationMs && if (selected.loop)
                Math.floorMod(cursor - selected.offsetMs, selected.sourceDurationMs) != 0L
                else cursor > selected.offsetMs && cursor < selected.offsetMs + selected.sourceDurationMs
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FilledTonalButton({ studio.splitClip(selected.id, cursor) }, enabled = enabled && interior && project.tracks.size < MAX_MULTITRACK_CLIPS,
                    modifier = Modifier.testTag("project-split")) { Icon(Icons.Outlined.ContentCut, null, Modifier.size(18.dp)); Text(" Split") }
                OutlinedButton({ trim = true }, enabled = enabled && sourceDuration != null, modifier = Modifier.testTag("project-trim")) { Text("Trim") }
                OutlinedButton(edit, enabled = enabled) { Text("Position / name") }
                OutlinedButton({ studio.duplicateClip(selected.id) }, enabled = enabled && project.tracks.size < MAX_MULTITRACK_CLIPS,
                    modifier = Modifier.testTag("project-duplicate")) { Text("Duplicate") }
                OutlinedButton({ lanePicker = true }, enabled = enabled, modifier = Modifier.testTag("project-move-lane")) { Text("Move to track") }
                TextButton({ studio.removeTrack(selected.id) }, enabled = enabled, modifier = Modifier.testTag("project-remove-clip")) { Text("Remove clip", color = Coral) }
            }
            Text("White line: cut cursor · Red line: playback. Original audio stays intact.", style = MaterialTheme.typography.labelSmall, color = Muted)
        }
    }
    if (picker) AlertDialog(onDismissRequest = { picker = false }, title = { Text("Select clip") }, text = {
        LazyColumn(Modifier.heightIn(max = 350.dp).testTag("project-clip-options")) {
            itemsIndexed(project.tracks, key = { _, clip -> clip.id }) { index, clip ->
                Column(Modifier.fillMaxWidth().testTag("choose-clip-${clip.id}").clickable {
                    select(clip.id, (if (clip.loop) project.durationMs / 2 else clip.offsetMs + clip.sourceDurationMs / 2).coerceIn(0, project.durationMs))
                    picker = false
                }.padding(vertical = 12.dp)) {
                    Text("${index + 1}. ${clip.title}")
                    Text("Track ${project.lanes.keys.indexOf(clip.laneId) + 1} · ${timestamp(clip.offsetMs)} · ${durationLabel(clip.sourceDurationMs)}", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }, confirmButton = { TextButton({ picker = false }) { Text("Close") } })
    if (cutTime && enabled) ProjectCutPositionDialog(cursor, project.durationMs, { cutTime = false }) { setCursor(it); cutTime = false }
    if (trim && selected != null && sourceDuration != null && enabled) ClipTrimDialog(selected, sourceDuration, { trim = false }) { from, to ->
        studio.trimClip(selected.id, from, to, sourceDuration); trim = false
    }
    if (lanePicker && selected != null && enabled) AlertDialog(onDismissRequest = { lanePicker = false }, title = { Text("Move clip to track") }, text = {
        LazyColumn(Modifier.heightIn(max = 350.dp)) {
            itemsIndexed(project.lanes.entries.toList(), key = { _, lane -> lane.key }) { index, lane ->
                TextButton({ studio.moveToLane(selected.id, lane.key); lanePicker = false }, Modifier.fillMaxWidth().testTag("move-lane-${lane.key}"), enabled = lane.key != selected.laneId) {
                    Text("Track ${index + 1} · ${lane.value.first().title}")
                }
            }
            item { TextButton({ studio.moveToLane(selected.id, UUID.randomUUID().toString()); lanePicker = false },
                Modifier.fillMaxWidth().testTag("move-new-lane"), enabled = project.lanes.size < MAX_MULTITRACK_TRACKS || project.lanes[selected.laneId]?.size == 1) { Text("New track") } }
        }
    }, confirmButton = { TextButton({ lanePicker = false }) { Text("Cancel") } })
}

@Composable private fun ProjectCutPositionDialog(initial: Long, duration: Long, dismiss: () -> Unit, save: (Long) -> Unit) {
    var time by rememberSaveable { mutableStateOf(timestamp(initial)) }
    val value = parseTimestamp(time)
    AlertDialog(onDismissRequest = dismiss, title = { Text("Cut position in project") }, text = {
        OutlinedTextField(time, { time = it }, label = { Text("Cut time") }, supportingText = { Text("hh:mm:ss.SSS") }, singleLine = true, isError = value == null || value !in 0..duration)
    }, confirmButton = { TextButton({ save(value!!) }, enabled = value != null && value in 0..duration) { Text("Set position") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

@Composable private fun ClipTrimDialog(clip: MultitrackTrack, duration: Long, dismiss: () -> Unit, save: (Long, Long) -> Unit) {
    var from by rememberSaveable(clip.id) { mutableStateOf(timestamp(clip.sourceStartMs)) }
    var to by rememberSaveable(clip.id) { mutableStateOf(timestamp(clip.sourceEndMs)) }
    val a = parseTimestamp(from); val b = parseTimestamp(to)
    val valid = a != null && b != null && a >= 0 && b > a && b <= duration && (clip.loop || clip.offsetMs + a - clip.sourceStartMs >= 0)
    AlertDialog(onDismissRequest = dismiss, title = { Text("Trim clip") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(from, { from = it }, label = { Text("Source from") }, singleLine = true, isError = !valid)
            OutlinedTextField(to, { to = it }, label = { Text("Source to") }, singleLine = true, isError = !valid)
            if (a != null && b != null && a < b && b <= duration) RangeSlider(a.toFloat()..b.toFloat(), {
                from = timestamp(it.start.toLong()); to = timestamp(it.endInclusive.toLong())
            }, valueRange = 0f..duration.toFloat(), modifier = Modifier.testTag("project-trim-range"))
            Text(if (clip.loop) "Changes the repeated section. Split first to edit individual repetitions." else
                "Source times use hh:mm:ss.SSS. Trimming the beginning keeps the remaining audio at its current timeline position.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton({ save(a!!, b!!) }, enabled = valid) { Text("Apply trim") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}
