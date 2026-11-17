package app.stillwave.data

/** Repeating clips move their phase; ordinary clips move their start on the project timeline. */
fun boundedTrackOffset(project: MultitrackProject, track: MultitrackTrack, requestedOffsetMs: Long): Long {
    if (track.loop) return Math.floorMod(requestedOffsetMs, track.sourceDurationMs)
    val lastStart = if (project.loopPlayback) project.durationMs - 1 else MAX_MULTITRACK_DURATION_MS - track.sourceDurationMs
    return requestedOffsetMs.coerceIn(0, lastStart.coerceAtLeast(0))
}

/** Apply to the latest project revision so a drag cannot overwrite another track's settings. */
fun movedTrackProject(project: MultitrackProject, id: String, requestedOffsetMs: Long): MultitrackProject {
    val track = project.tracks.firstOrNull { it.id == id } ?: return project
    val offset = boundedTrackOffset(project, track, requestedOffsetMs)
    return project.copy(tracks = project.tracks.map { if (it.id == id) it.copy(offsetMs = offset) else it },
        durationMs = if (!project.loopPlayback && !track.loop) maxOf(project.durationMs, offset + track.sourceDurationMs) else project.durationMs)
}

/** Fit includes empty space for moving a full-length track later without first editing project duration. */
fun timelineExtentMs(project: MultitrackProject): Long {
    if (project.loopPlayback) return project.durationMs
    val end = maxOf(project.durationMs, project.tracks.filterNot { it.loop }.maxOfOrNull {
        (it.offsetMs + it.sourceDurationMs).coerceAtMost(MAX_MULTITRACK_DURATION_MS)
    } ?: 1)
    return (end + maxOf(1000, end / 4)).coerceAtMost(MAX_MULTITRACK_DURATION_MS)
}
