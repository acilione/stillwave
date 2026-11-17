package app.stillwave.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stillwave.audio.AudioTrace
import app.stillwave.data.*
import kotlin.math.floor
import kotlin.math.roundToLong

/** Source waveforms stay cached while clip positions and the playback cursor change. */
@Composable fun TrackTimeline(project: MultitrackProject, positionMs: Long, enabled: Boolean,
    waveforms: Map<String, ClipWaveform> = emptyMap(), selectedId: String? = null, cursorMs: Long? = null,
    select: (String, Long) -> Unit = { _, _ -> }, move: (String, Long, (Boolean) -> Unit) -> Unit) {
    var zoom by rememberSaveable(project.id) { mutableIntStateOf(1) }
    var pan by rememberSaveable(project.id) { mutableFloatStateOf(0f) }
    var dragging by remember(project.id) { mutableStateOf(false) }
    var extent by remember(project.id) { mutableLongStateOf(timelineExtentMs(project)) }
    LaunchedEffect(project, dragging) { if (!dragging) extent = timelineExtentMs(project) }
    val span = (extent / zoom).coerceAtLeast(1)
    val start = (pan * (extent - span)).toLong().coerceIn(0, extent - span)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Arrange tracks", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton({ zoom = 1; pan = 0f }, enabled = zoom != 1 && !dragging) { Text("Fit") }
                IconButton({ zoom = (zoom / 2).coerceAtLeast(1) }, enabled = zoom > 1 && !dragging) { Icon(Icons.Outlined.ZoomOut, "Zoom out timeline") }
                IconButton({ zoom = (zoom * 2).coerceAtMost(64) }, enabled = zoom < 64 && span > 1 && !dragging) { Icon(Icons.Outlined.ZoomIn, "Zoom in timeline") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(timestamp(start), color = Muted, style = MaterialTheme.typography.labelSmall)
                Text(timestamp(start + span), color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            if (project.tracks.isEmpty()) Text("Add a recording or record a track to arrange it here.", color = Muted, style = MaterialTheme.typography.bodySmall)
            project.lanes.entries.forEachIndexed { index, (laneId, clips) -> key(laneId) {
                TimelineLane(project, clips, index, start, span, positionMs, enabled, waveforms, selectedId, cursorMs,
                    select, dragActive = { dragging = it }, move)
            } }
            if (zoom > 1) Slider(pan, { pan = it }, enabled = !dragging,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Scroll timeline" })
            Text(if (enabled) "Tap a waveform to select a clip and place the cut cursor. Drag clips left or right." else "Clip editing is disabled while recording.",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            if (project.loopPlayback) Text("The cycle length stays fixed. Repeating clips shift their loop phase.", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable private fun TimelineLane(project: MultitrackProject, clips: List<MultitrackTrack>, index: Int,
    start: Long, span: Long, position: Long, enabled: Boolean, waveforms: Map<String, ClipWaveform>, selectedId: String?, cursor: Long?,
    select: (String, Long) -> Unit, dragActive: (Boolean) -> Unit, move: (String, Long, (Boolean) -> Unit) -> Unit) {
    var preview by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var pressedId by remember { mutableStateOf<String?>(null) }
    val pending = remember { mutableStateMapOf<String, Long>() }
    val requests = remember { mutableMapOf<String, Long>() }
    val latestClips by rememberUpdatedState(clips)
    val latestProject by rememberUpdatedState(project)
    val latestMove by rememberUpdatedState(move)
    val latestSelect by rememberUpdatedState(select)
    val latestDragActive by rememberUpdatedState(dragActive)
    val latestSelected by rememberUpdatedState(selectedId)
    fun offset(clip: MultitrackTrack) = preview?.takeIf { it.first == clip.id }?.second ?: pending[clip.id] ?: clip.offsetMs
    fun target(time: Long): MultitrackTrack? {
        val hits = latestClips.filter { it.loop || time >= offset(it) && time < offset(it) + it.sourceDurationMs }
        return hits.firstOrNull { it.id == latestSelected } ?: hits.lastOrNull() ?: latestClips.singleOrNull()
    }
    fun commit(clip: MultitrackTrack, next: Long) {
        val token = (requests[clip.id] ?: 0) + 1
        requests[clip.id] = token; pending[clip.id] = next
        latestMove(clip.id, next) { if (requests[clip.id] == token) pending.remove(clip.id) }
    }
    LaunchedEffect(clips.map { it.id to it.offsetMs }) {
        pending.keys.toList().forEach { id -> if (clips.none { it.id == id } || clips.any { it.id == id && it.offsetMs == pending[id] }) pending.remove(id) }
    }
    LaunchedEffect(enabled) { if (!enabled) { preview = null; latestDragActive(false) } }
    DisposableEffect(Unit) { onDispose { latestDragActive(false) } }
    val focused = clips.firstOrNull { it.id == selectedId } ?: clips.first()
    val color = when (index % 3) { 0 -> Lime; 1 -> Coral; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    val cursorColor = MaterialTheme.colorScheme.onSurface
    val muted = Muted
    val playhead = Coral
    val step = maxOf(1, span / 100)
    fun nudge(delta: Long): Boolean {
        if (!enabled) return false
        val next = boundedTrackOffset(project, focused, offset(focused) + delta)
        if (next != offset(focused)) commit(focused, next)
        return true
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${index + 1}. ${focused.title}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (clips.size == 1) "${if (focused.loop) "Phase" else "Start"} ${timestamp(offset(focused))}" else "${clips.size} clips", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        Canvas(Modifier.fillMaxWidth().height(64.dp).background(Ink, RoundedCornerShape(8.dp))
            .testTag("timeline-track-${clips.first().id}")
            .semantics {
                contentDescription = "Move track ${focused.title}"
                stateDescription = "${if (focused.loop) "Loop phase" else "Start"} ${timestamp(offset(focused))}"
                if (!enabled) disabled()
                customActions = listOf(CustomAccessibilityAction("Move earlier") { nudge(-step) }, CustomAccessibilityAction("Move later") { nudge(step) })
            }
            .pointerInput(enabled, start, span) {
                if (enabled) detectTapGestures(onPress = { point ->
                    pressedId = target((start + point.x.toDouble() / size.width * span).toLong())?.id
                    try { tryAwaitRelease() } finally { pressedId = null }
                }) { point ->
                    val time = (start + point.x.toDouble() / size.width * span).toLong().coerceIn(0, latestProject.durationMs)
                    (target(time) ?: latestClips.firstOrNull { it.id == latestSelected } ?: latestClips.firstOrNull())?.let { latestSelect(it.id, time) }
                }
            }
            .pointerInput(clips.map { listOf(it.id, it.sourceStartMs, it.sourceEndMs, it.loop) }, enabled, start, span) {
                if (!enabled) return@pointerInput
                var clip: MultitrackTrack? = null
                var origin = 0L
                var delta = 0.0
                try { detectHorizontalDragGestures(
                    onDragStart = { point ->
                        val time = (start + point.x.toDouble() / size.width * span).toLong()
                        clip = latestClips.firstOrNull { it.id == pressedId } ?: target(time)
                        clip?.let {
                            origin = offset(it); delta = 0.0
                            preview = it.id to origin; latestDragActive(true)
                            latestSelect(it.id, time.coerceIn(0, latestProject.durationMs))
                        }
                    },
                    onHorizontalDrag = { change, amount -> clip?.let { moving ->
                        change.consume()
                        if (size.width > 0) { delta += amount.toDouble() / size.width * span
                            preview = moving.id to boundedTrackOffset(latestProject, moving, (origin + delta).roundToLong()) }
                    } },
                    onDragEnd = {
                        clip?.let { moving -> preview?.second?.let { next -> if (next != (pending[moving.id] ?: moving.offsetMs)) commit(moving, next) } }
                        preview = null; latestDragActive(false)
                    },
                    onDragCancel = { preview = null; latestDragActive(false) }
                ) } finally { preview = null; latestDragActive(false) }
            }) {
            fun x(time: Double) = ((time - start) / span * size.width).toFloat()
            val finish = start + span
            repeat(5) { tick -> val at = size.width * tick / 4
                drawLine(muted.copy(alpha = 0.15f), Offset(at, 0f), Offset(at, size.height), 1.dp.toPx()) }
            clips.sortedBy { it.id == selectedId }.forEach { clip ->
                val at = offset(clip)
                val chosen = clip.id == selectedId
                val alpha = if (clip.muted) 0.12f else if (chosen) 0.3f else 0.18f
                fun block(from: Double, to: Double) {
                    val left = x(from).coerceIn(0f, size.width)
                    val right = x(to).coerceIn(0f, size.width)
                    if (right > left) {
                        val top = 5.dp.toPx(); val height = size.height - top * 2
                        drawRoundRect(color.copy(alpha = alpha), Offset(left, top), Size(right - left, height), CornerRadius(4.dp.toPx()))
                        drawRoundRect(color.copy(alpha = if (chosen) 1f else 0.4f), Offset(left, top), Size(right - left, height), CornerRadius(4.dp.toPx()), style = Stroke(if (chosen) 2.dp.toPx() else 1.dp.toPx()))
                    }
                }
                if (clip.loop) {
                    val length = clip.sourceDurationMs.toDouble()
                    val first = at + floor((start - at) / length) * length
                    val cycles = (span / length).toLong() + 2
                    if (cycles <= 128) repeat(cycles.toInt()) { block(first + it * length, first + (it + 1) * length) }
                    else block(start.toDouble(), finish.toDouble())
                } else block(at.toDouble(), (at + clip.sourceDurationMs).toDouble())
                val left = if (clip.loop) 0f else x(at.toDouble()).coerceIn(0f, size.width)
                val right = if (clip.loop) size.width else x((at + clip.sourceDurationMs).toDouble()).coerceIn(0f, size.width)
                val trace = waveforms[clip.id]?.trace
                if (trace != null && right > left) {
                    val spacing = 2.dp.toPx()
                    var px = left
                    while (px < right) {
                        val from = start + px.toDouble() / size.width * span
                        val to = start + minOf(px + spacing, right).toDouble() / size.width * span
                        val peak = clipPeak(trace, clip, at, from, to) * clip.gain.toFloat()
                        val height = (peak.coerceIn(0f, 1f) * (size.height - 18.dp.toPx()) / 2).coerceAtLeast(0.5.dp.toPx())
                        drawLine(color.copy(alpha = if (clip.muted) 0.3f else 0.95f), Offset(px, size.height / 2 - height), Offset(px, size.height / 2 + height), 1.dp.toPx())
                        px += spacing
                    }
                }
            }
            if (project.durationMs in start..finish) drawLine(muted, Offset(x(project.durationMs.toDouble()), 0f), Offset(x(project.durationMs.toDouble()), size.height), 1.dp.toPx())
            if (position in start..finish) drawLine(playhead, Offset(x(position.toDouble()), 0f), Offset(x(position.toDouble()), size.height), 2.dp.toPx())
            if (cursor != null && cursor in start..finish) drawLine(cursorColor, Offset(x(cursor.toDouble()), 0f), Offset(x(cursor.toDouble()), size.height), 1.dp.toPx())
        }
        if (clips.any { waveforms[it.id]?.loading == true }) Text("Loading waveform…", color = Muted, style = MaterialTheme.typography.labelSmall)
        else if (clips.any { waveforms[it.id]?.error != null }) Text("Waveform unavailable for a source clip", color = Coral, style = MaterialTheme.typography.labelSmall)
    }
}

private fun clipPeak(trace: AudioTrace, clip: MultitrackTrack, offset: Long, from: Double, to: Double): Float {
    if (!clip.loop) return trace.peak(clip.sourceStartMs + from - offset, clip.sourceStartMs + to - offset)
    val length = clip.sourceDurationMs.toDouble()
    if (to - from >= length) return trace.peak(clip.sourceStartMs.toDouble(), clip.sourceEndMs.toDouble())
    val relative = from - offset - floor((from - offset) / length) * length
    val until = relative + to - from
    val peak = trace.peak(clip.sourceStartMs + relative, clip.sourceStartMs + minOf(length, until))
    return if (until <= length) peak else maxOf(peak, trace.peak(clip.sourceStartMs.toDouble(), clip.sourceStartMs + until - length))
}
