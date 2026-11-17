package app.stillwave.audio

import android.util.AtomicFile
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import app.stillwave.StillwaveApp
import app.stillwave.data.MultitrackJson
import app.stillwave.data.MAX_MULTITRACK_CLIPS
import app.stillwave.data.MultitrackProject
import app.stillwave.data.Recording
import app.stillwave.data.renderContent
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Leased source decoding and private, disposable project previews. */
object MultitrackAudio {
    data class Preview(val file: File, val identity: String, val projectId: String, val sourceIds: Set<String>)

    private val preparation = Mutex()
    private val metadataAccess = Any() // AtomicFile readers must not recover a writer's in-progress transaction.
    private val ready = ConcurrentHashMap<String, Preview>()
    @Volatile private var activeIdentity: String? = null
    @Volatile private var pendingIdentity: String? = null
    private const val INACTIVE_BYTES = 64L * 1024 * 1024
    private const val INACTIVE_FILES = 3
    private const val SPACE_RESERVE = 32L * 1024 * 1024

    suspend fun prepare(app: StillwaveApp, project: MultitrackProject): Preview = withContext(Dispatchers.IO) {
        validate(project)
        app.ready.await()
        preparation.withLock {
            app.repository.withRecordings(project.tracks.map { it.recordingId }) { sources ->
                val directory = File(app.cacheDir, "multitrack-previews").apply {
                    check(isDirectory || mkdirs()) { "Could not create project preview storage" }
                }
                val fingerprint = buildString {
                    // Cosmetic edits and player repeat mode do not change the rendered PCM.
                    append(MultitrackJson.encode(project.renderContent()).toString())
                    sources.sortedBy { it.id }.forEach { source ->
                        val audio = app.repository.file(source)
                        check(audio.isFile) { "A project recording is missing" }
                        append('|').append(source.id).append(':').append(audio.length()).append(':').append(audio.lastModified())
                    }
                }
                val identity = MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                val output = File(directory, "$identity.wav")
                val metadata = File(directory, "$identity.sources")
                val preview = Preview(output, identity, project.id, sources.map { it.id }.toSet())
                prune(directory, identity)
                if (!output.isFile || readMetadata(metadata) != (project.id to preview.sourceIds)) {
                    checkSpace(directory, project)
                    val temporary = File(directory, "$identity.rendering")
                    temporary.delete()
                    try {
                        withDecoded(app, sources) { files ->
                            val coroutine = currentCoroutineContext()
                            MultitrackRenderer.render(project.tracks.map { MultitrackRenderer.Input(it, files.getValue(it.recordingId)) },
                                temporary, project.durationMs, checkCancelled = { coroutine.ensureActive() })
                        }
                        currentCoroutineContext().ensureActive()
                        // Source metadata is published first; an interruption cannot leave an unidentifiable cache file.
                        synchronized(metadataAccess) {
                            val atomic = AtomicFile(metadata)
                            val stream = atomic.startWrite()
                            try {
                                stream.write(JSONObject().put("projectId", project.id).put("sourceIds", JSONArray(preview.sourceIds.toList()))
                                    .toString().toByteArray(Charsets.UTF_8))
                                atomic.finishWrite(stream)
                            } catch (error: Throwable) { atomic.failWrite(stream); throw error }
                        }
                        if (output.exists()) check(output.delete()) { "Could not refresh project preview" }
                        check(temporary.renameTo(output)) { "Could not publish project preview" }
                    } finally {
                        temporary.delete()
                        if (!output.isFile) AtomicFile(metadata).delete()
                    }
                }
                currentCoroutineContext().ensureActive()
                output.setLastModified(System.currentTimeMillis())
                ready[identity] = preview
                preview
            }
        }
    }

    /** Repository locks and decoding leases cover the entire render, including repeated source tracks. */
    internal suspend fun <T> withSources(app: StillwaveApp, project: MultitrackProject,
        action: suspend (List<MultitrackRenderer.Input>) -> T): T = withContext(Dispatchers.IO) {
        validate(project)
        app.ready.await()
        app.repository.withRecordings(project.tracks.map { it.recordingId }) { sources ->
            withDecoded(app, sources) { files ->
                action(project.tracks.map { MultitrackRenderer.Input(it, files.getValue(it.recordingId)) })
            }
        }
    }

    private suspend fun <T> withDecoded(app: StillwaveApp, sources: List<Recording>, action: suspend (Map<String, File>) -> T): T {
        val files = mutableMapOf<String, File>()
        suspend fun lease(index: Int): T {
            if (index == sources.size) return action(files)
            val source = sources[index]
            return EditorAudioSource.withWav(app, source) { wav ->
                files[source.id] = wav
                lease(index + 1)
            }
        }
        return lease(0)
    }

    internal fun validate(project: MultitrackProject) {
        require(project.error() == null) { project.error().orEmpty() }
        require(project.tracks.isNotEmpty()) { "Add at least one recording to the project" }
    }

    internal fun checkSpace(directory: File, project: MultitrackProject) {
        val bytes = project.durationMs * 48_000L / 1000 * 4
        require(bytes <= WavFile.MAX_DATA_BYTES) { "This project exceeds the WAV size limit" }
        check(directory.usableSpace >= bytes + SPACE_RESERVE) { "Not enough free space to render this project" }
    }

    /** No caller-supplied path is trusted: only an exact render registered by this process is playable. */
    fun accepts(app: StillwaveApp, item: MediaItem): Boolean = runCatching {
        val identity = item.mediaMetadata.extras?.getString(PlaybackService.PROJECT_PREVIEW_ID) ?: return false
        val preview = ready[identity] ?: return false
        val uri = item.localConfiguration?.uri ?: return false
        val file = File(checkNotNull(uri.path)).canonicalFile
        uri.scheme == "file" && item.mediaId == preview.projectId && file == preview.file.canonicalFile && file.isFile &&
            file.parentFile == File(app.cacheDir, "multitrack-previews").canonicalFile && file.name == "$identity.wav" &&
            item.clippingConfiguration.startPositionMs == 0L && item.clippingConfiguration.endPositionMs == C.TIME_END_OF_SOURCE
    }.getOrDefault(false)

    fun sourceIds(identity: String?): Set<String> = identity?.let { ready[it]?.sourceIds }.orEmpty()
    fun pending(identity: String?) { pendingIdentity = identity }
    fun playing(identity: String?) {
        activeIdentity = identity
        if (pendingIdentity == identity) pendingIdentity = null
    }

    /** Call after stopping dependent playback/preparation, while holding the source repository lock. */
    fun deleteForRecording(app: StillwaveApp, id: String) = invalidate(app) { _, sources -> id in sources }

    /** Call after stopping the project's playback/preparation. Exported library files remain independent. */
    fun deleteForProject(app: StillwaveApp, id: String) = invalidate(app) { projectId, _ -> projectId == id }

    private fun invalidate(app: StillwaveApp, matches: (String, Set<String>) -> Boolean) {
        val directory = File(app.cacheDir, "multitrack-previews")
        val identities = ready.values.filter { matches(it.projectId, it.sourceIds) }.map { it.identity }.toMutableSet()
        metadataFiles(directory).forEach { file ->
            val metadata = readMetadata(file)
            // An unrelated render may still be publishing. Malformed/orphan cleanup belongs to serialized prune.
            if (metadata != null && matches(metadata.first, metadata.second)) identities += file.nameWithoutExtension
        }
        identities.forEach { identity ->
            ready.remove(identity)
            if (activeIdentity == identity) activeIdentity = null
            if (pendingIdentity == identity) pendingIdentity = null
            AtomicFile(File(directory, "$identity.sources")).delete()
            for (extension in listOf("wav", "rendering")) {
                val file = File(directory, "$identity.$extension")
                check(!file.exists() || file.delete()) { "Could not remove project preview cache" }
            }
        }
    }

    private fun prune(directory: File, candidate: String) {
        directory.listFiles { file -> file.extension == "rendering" }?.forEach { it.delete() }
        val protected = setOfNotNull(activeIdentity, pendingIdentity, candidate)
        metadataFiles(directory).filter { it.nameWithoutExtension !in protected }.forEach { metadata ->
            val file = File(directory, "${metadata.nameWithoutExtension}.wav")
            if (!file.exists() || readMetadata(metadata) == null) {
                if (!file.exists() || file.delete()) {
                    ready.remove(metadata.nameWithoutExtension)
                    AtomicFile(metadata).delete()
                }
            }
        }
        var bytes = 0L
        directory.listFiles { file -> file.extension == "wav" && file.nameWithoutExtension !in protected }
            .orEmpty().sortedByDescending(File::lastModified).forEachIndexed { index, file ->
                bytes += file.length()
                if (index >= INACTIVE_FILES || bytes > INACTIVE_BYTES) {
                    if (file.delete()) {
                        ready.remove(file.nameWithoutExtension)
                        AtomicFile(File(directory, "${file.nameWithoutExtension}.sources")).delete()
                    }
                }
            }
    }

    private fun metadataFiles(directory: File): List<File> = directory.listFiles { file ->
        file.name.endsWith(".sources") || file.name.endsWith(".sources.bak")
    }.orEmpty().map { File(directory, it.name.removeSuffix(".bak")) }.distinct()

    private fun readMetadata(file: File): Pair<String, Set<String>>? = synchronized(metadataAccess) { runCatching {
        val json = AtomicFile(file).openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 16 * 1024)
                output.write(buffer, 0, count)
            }
            JSONObject(output.toByteArray().toString(Charsets.UTF_8))
        }
        val ids = json.getJSONArray("sourceIds")
        require(ids.length() in 1..MAX_MULTITRACK_CLIPS)
        json.getString("projectId") to (0 until ids.length()).map { ids.getString(it) }.toSet()
    }.getOrNull() }
}
