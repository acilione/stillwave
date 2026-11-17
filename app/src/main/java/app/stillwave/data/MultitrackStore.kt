package app.stillwave.data

import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One atomic JSON file per project; a corrupt project cannot hide or overwrite its neighbours. */
class MultitrackStore(private val directory: File) {
    private val mutex = Mutex()
    private val mutableProjects = MutableStateFlow<List<MultitrackProject>>(emptyList())
    val projects: StateFlow<List<MultitrackProject>> = mutableProjects.asStateFlow()
    private val mutableUnreadable = MutableStateFlow(0)
    val unreadableProjects: StateFlow<Int> = mutableUnreadable.asStateFlow()

    suspend fun load(): List<MultitrackProject> = withContext(Dispatchers.IO) { mutex.withLock { readAll() } }
    suspend fun refresh() { load() }

    suspend fun save(project: MultitrackProject): MultitrackProject = withContext(Dispatchers.IO) { mutex.withLock {
        write(project)
    } }

    /** Reads the latest durable revision inside the same lock used by recording-service additions. */
    suspend fun update(id: String, transform: (MultitrackProject) -> MultitrackProject): MultitrackProject = withContext(Dispatchers.IO) { mutex.withLock {
        val current = readAll().firstOrNull { it.id == id } ?: error("Project no longer exists")
        val updated = transform(current)
        require(updated.id == id && updated.createdAt == current.createdAt) { "Project identity cannot change" }
        write(updated)
    } }

    /** Safe to retry after recording recovery; deleting a project never causes it to be recreated. */
    suspend fun addRecordedTake(projectId: String, recording: Recording, offsetMs: Long, loop: Boolean): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        val target = file(projectId)
        val current = readAll().firstOrNull { it.id == projectId } ?: run {
            check(!target.exists() && !File(target.path + ".bak").exists()) { "Could not read the project; the saved take can be attached after recovery" }
            return@withLock false
        }
        if (current.tracks.any { it.recordingId == recording.id }) return@withLock true
        require(current.canAddTrack) { "The recording is saved, but this project has no free track slots" }
        val track = MultitrackTrack(id = recording.id, recordingId = recording.id, title = recording.title,
            sourceEndMs = if (loop) minOf(recording.durationMs, current.durationMs) else recording.durationMs,
            offsetMs = offsetMs, loop = loop)
        val duration = if (loop) current.durationMs else maxOf(current.durationMs,
            (offsetMs + recording.durationMs).coerceAtMost(MAX_MULTITRACK_DURATION_MS))
        write(current.copy(tracks = current.tracks + track, durationMs = duration))
        true
    } }

    private fun write(project: MultitrackProject): MultitrackProject {
        require(project.error() == null) { project.error().orEmpty() }
        check(directory.isDirectory || directory.mkdirs()) { "Could not open project storage" }
        val saved = project.copy(title = project.title.trim(), updatedAt = System.currentTimeMillis(), laneOrder = project.lanes.keys.toList())
        val atomic = AtomicFile(file(saved.id))
        val stream = atomic.startWrite()
        try { stream.write(MultitrackJson.encode(saved).toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
        readAll()
        return saved
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val target = file(id)
        AtomicFile(target).delete()
        check(!target.exists() && !File(target.path + ".bak").exists()) { "Could not delete project" }
        readAll()
        Unit
    } }

    private fun file(id: String): File {
        require(isProjectId(id)) { "Invalid project identity" }
        return File(directory, "$id.json")
    }

    private fun readAll(): List<MultitrackProject> {
        var failures = 0
        val files = directory.listFiles().orEmpty().filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
            .map { File(directory, it.name.removeSuffix(".bak")) }.distinctBy { it.name }
        val result = files.mapNotNull { candidate ->
            try {
                require(isProjectId(candidate.nameWithoutExtension))
                val json = AtomicFile(candidate).openRead().use { stream ->
                    // A bounded document prevents accidental huge files exhausting app memory.
                    val bytes = stream.readBytesLimited(256 * 1024)
                    JSONObject(bytes.toString(Charsets.UTF_8))
                }
                MultitrackJson.decode(json).also {
                    require(it.id == candidate.nameWithoutExtension && it.error() == null)
                }
            } catch (_: Exception) { failures++; null }
        }.sortedWith(compareByDescending<MultitrackProject> { it.updatedAt }.thenBy { it.id })
        mutableUnreadable.value = failures
        mutableProjects.value = result
        return result
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = read(buffer)
            if (read < 0) return output.toByteArray()
            require(output.size() + read <= limit) { "Project file is too large" }
            output.write(buffer, 0, read)
        }
    }

}

/** Shared with durable background-export snapshots. */
object MultitrackJson {
    fun encode(project: MultitrackProject): JSONObject = JSONObject()
        .put("version", 2).put("id", project.id).put("title", project.title)
        .put("createdAt", project.createdAt).put("updatedAt", project.updatedAt)
        .put("durationMs", project.durationMs).put("loopPlayback", project.loopPlayback)
        .put("laneOrder", JSONArray(project.lanes.keys.toList()))
        .put("tracks", JSONArray().apply { project.tracks.forEach { track ->
            put(JSONObject().put("id", track.id).put("recordingId", track.recordingId).put("title", track.title)
                .put("sourceStartMs", track.sourceStartMs).put("sourceEndMs", track.sourceEndMs)
                .put("offsetMs", track.offsetMs).put("gain", track.gain).put("muted", track.muted)
                .put("solo", track.solo).put("loop", track.loop).put("laneId", track.laneId))
        } })

    fun decode(json: JSONObject): MultitrackProject {
        require(json.getInt("version") in 1..2) { "Unsupported project version" }
        val values = json.getJSONArray("tracks")
        require(values.length() <= MAX_MULTITRACK_CLIPS)
        val laneOrder = json.optJSONArray("laneOrder")
        require(laneOrder == null || laneOrder.length() <= MAX_MULTITRACK_TRACKS)
        return MultitrackProject(id = json.getString("id"), title = json.getString("title"),
            createdAt = json.getLong("createdAt"), updatedAt = json.getLong("updatedAt"),
            durationMs = json.getLong("durationMs"), loopPlayback = json.getBoolean("loopPlayback"),
            tracks = (0 until values.length()).map { index ->
                val value = values.getJSONObject(index)
                MultitrackTrack(id = value.getString("id"), recordingId = value.getString("recordingId"),
                    title = value.getString("title"), sourceStartMs = value.getLong("sourceStartMs"),
                    sourceEndMs = value.getLong("sourceEndMs"), offsetMs = value.getLong("offsetMs"),
                    gain = value.getDouble("gain"), muted = value.getBoolean("muted"),
                    solo = value.getBoolean("solo"), loop = value.getBoolean("loop"), laneId = value.optString("laneId", value.getString("id")))
            }, laneOrder = if (laneOrder == null) (0 until values.length()).map { values.getJSONObject(it).getString("id") }
                else (0 until laneOrder.length()).map { laneOrder.getString(it) }).also { require(it.error() == null) { it.error().orEmpty() } }
    }
}
