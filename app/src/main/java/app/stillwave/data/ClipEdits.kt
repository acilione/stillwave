package app.stillwave.data

import java.util.UUID

/** All clip edits change references into the original audio, never the source file. */
fun splitProjectClip(project: MultitrackProject, id: String, atMs: Long): MultitrackProject {
    val clip = project.tracks.firstOrNull { it.id == id } ?: error("Clip no longer exists")
    require(atMs in 1 until project.durationMs) { "Place the cut inside the project" }
    val pieces = if (clip.loop) unfoldProjectClip(project, clip) else listOf(clip)
    val target = requireNotNull(pieces.firstOrNull { atMs > it.offsetMs && atMs < it.offsetMs + it.sourceDurationMs }) {
        "Place the cut inside the selected clip"
    }
    val sourceCut = target.sourceStartMs + atMs - target.offsetMs
    val result = pieces.flatMap { part ->
        if (part.id != target.id) listOf(part) else listOf(
            part.copy(sourceEndMs = sourceCut),
            part.copy(id = UUID.randomUUID().toString(), sourceStartMs = sourceCut, offsetMs = atMs))
    }
    val updated = project.copy(tracks = project.tracks.flatMap { if (it.id == id) result else listOf(it) })
    require(updated.error() == null) { updated.error().orEmpty() }
    return updated
}

/** Freeze the audible repetitions into finite clips so splitting never changes their repeat period. */
fun unfoldProjectClip(project: MultitrackProject, clip: MultitrackTrack): List<MultitrackTrack> {
    if (!clip.loop) return listOf(clip)
    val length = clip.sourceDurationMs
    val phase = Math.floorMod(clip.offsetMs, length)
    var begin = if (phase == 0L) 0L else phase - length
    val count = (project.durationMs - begin + length - 1) / length
    require(count <= MAX_MULTITRACK_CLIPS - project.tracks.size + 1) {
        "Too many repetitions to cut. Shorten the project or use a longer repeating section (up to $MAX_MULTITRACK_CLIPS clips)."
    }
    return buildList {
        while (begin < project.durationMs) {
            val from = maxOf(0, begin)
            val until = minOf(project.durationMs, begin + length)
            add(clip.copy(id = if (isEmpty()) clip.id else UUID.randomUUID().toString(),
                sourceStartMs = clip.sourceStartMs + from - begin,
                sourceEndMs = clip.sourceStartMs + until - begin, offsetMs = from, loop = false))
            begin += length
        }
    }
}

fun trimmedProjectClip(project: MultitrackProject, id: String, fromMs: Long, toMs: Long, sourceDurationMs: Long): MultitrackProject {
    val clip = project.tracks.firstOrNull { it.id == id } ?: error("Clip no longer exists")
    require(fromMs >= 0 && toMs > fromMs && toMs <= sourceDurationMs) { "Check the clip's source range" }
    // Moving the left edge keeps the retained sound at the same absolute time.
    val offset = if (clip.loop) clip.offsetMs else clip.offsetMs + fromMs - clip.sourceStartMs
    require(offset >= 0 && (clip.loop || offset + toMs - fromMs <= MAX_MULTITRACK_DURATION_MS)) { "Trim extends outside the timeline" }
    val edited = clip.copy(sourceStartMs = fromMs, sourceEndMs = toMs,
        offsetMs = if (clip.loop) Math.floorMod(offset, toMs - fromMs) else offset)
    return project.copy(tracks = project.tracks.map { if (it.id == id) edited else it },
        durationMs = if (project.loopPlayback || clip.loop) project.durationMs else maxOf(project.durationMs, edited.offsetMs + edited.sourceDurationMs))
}

fun duplicateProjectClip(project: MultitrackProject, id: String): MultitrackProject {
    val clip = project.tracks.firstOrNull { it.id == id } ?: error("Clip no longer exists")
    require(project.tracks.size < MAX_MULTITRACK_CLIPS) { "A project supports up to $MAX_MULTITRACK_CLIPS clips" }
    val position = if (clip.loop) clip.offsetMs else clip.offsetMs + clip.sourceDurationMs
    require(position < (if (project.loopPlayback) project.durationMs else MAX_MULTITRACK_DURATION_MS) &&
        (clip.loop || project.loopPlayback || position + clip.sourceDurationMs <= MAX_MULTITRACK_DURATION_MS)) {
        "There is no room after this clip. Move it earlier or increase the project duration."
    }
    val copy = clip.copy(id = UUID.randomUUID().toString(), offsetMs = position)
    return project.copy(tracks = project.tracks + copy,
        durationMs = if (project.loopPlayback || clip.loop) project.durationMs else maxOf(project.durationMs, position + clip.sourceDurationMs))
}

fun moveClipToLane(project: MultitrackProject, id: String, laneId: String): MultitrackProject {
    require(project.tracks.any { it.id == id }) { "Clip no longer exists" }
    val updated = project.copy(tracks = project.tracks.map { if (it.id == id) it.copy(laneId = laneId) else it })
    require(updated.error() == null) { updated.error().orEmpty() }
    return updated
}
