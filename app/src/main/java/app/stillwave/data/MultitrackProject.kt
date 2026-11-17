package app.stillwave.data

import java.util.UUID

/** A clip referencing original audio. laneId groups independently editable clips on one track. */
data class MultitrackTrack(
    val id: String = UUID.randomUUID().toString(),
    val recordingId: String,
    val title: String,
    val sourceStartMs: Long = 0,
    val sourceEndMs: Long,
    val offsetMs: Long = 0,
    val gain: Double = 1.0,
    val muted: Boolean = false,
    val solo: Boolean = false,
    val loop: Boolean = false,
    val laneId: String = id
) {
    val sourceDurationMs get() = sourceEndMs - sourceStartMs
    fun error(): String? = when {
        id.isBlank() || recordingId.isBlank() || laneId.isBlank() -> "Invalid track identity"
        title.length > 160 -> "Track names can contain up to 160 characters"
        sourceStartMs < 0 || sourceEndMs <= sourceStartMs || sourceEndMs > MAX_MULTITRACK_DURATION_MS -> "Check the track's From and To times"
        offsetMs !in 0..MAX_MULTITRACK_DURATION_MS -> "Check the track's timeline position"
        !gain.isFinite() || gain !in 0.0..2.0 -> "Track volume must be between 0% and 200%"
        else -> null
    }
}

const val MAX_MULTITRACK_TRACKS = 16
const val MAX_MULTITRACK_CLIPS = 128
// Stereo PCM16 at 48 kHz stays below RIFF's 4 GiB data limit.
const val MAX_MULTITRACK_DURATION_MS = 6L * 60 * 60 * 1000

data class MultitrackProject(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val durationMs: Long = 10_000,
    val loopPlayback: Boolean = false,
    val tracks: List<MultitrackTrack> = emptyList(),
    val laneOrder: List<String> = tracks.map { it.laneId }.distinct()
) {
    val lanes get(): Map<String, List<MultitrackTrack>> {
        val groups = tracks.groupBy { it.laneId }
        return (laneOrder + groups.keys).distinct().mapNotNull { id -> groups[id]?.let { id to it } }.toMap()
    }
    val canAddTrack get() = lanes.size < MAX_MULTITRACK_TRACKS && tracks.size < MAX_MULTITRACK_CLIPS
    fun error(): String? = when {
        !isProjectId(id) -> "Invalid project identity"
        title.isBlank() || title.length > 160 -> "Enter a project name of 1 to 160 characters"
        durationMs !in 1..MAX_MULTITRACK_DURATION_MS -> "Project duration must be between 1 ms and 6 hours"
        lanes.size > MAX_MULTITRACK_TRACKS -> "A project supports up to $MAX_MULTITRACK_TRACKS tracks"
        tracks.size > MAX_MULTITRACK_CLIPS -> "A project supports up to $MAX_MULTITRACK_CLIPS clips"
        tracks.map { it.id }.distinct().size != tracks.size -> "Duplicate track identity"
        tracks.any { it.error() != null } -> tracks.firstNotNullOf { it.error() }
        else -> null
    }
}

internal fun isProjectId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

/** One definition of PCM identity for both cached mixes and resuming an already loaded preview. */
internal fun MultitrackProject.renderContent(): MultitrackProject = copy(
    title = "_", createdAt = 0, updatedAt = 0, loopPlayback = false,
    tracks = tracks.map { it.copy(title = "", laneId = it.id) }, laneOrder = tracks.map { it.id }
)

/** Muted tracks never become audible through Solo. */
fun audibleTracks(tracks: List<MultitrackTrack>): List<MultitrackTrack> {
    val soloed = tracks.any { it.solo && !it.muted }
    return tracks.filter { !it.muted && it.gain > 0 && (!soloed || it.solo) }
}
