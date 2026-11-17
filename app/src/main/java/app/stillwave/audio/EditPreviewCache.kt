package app.stillwave.audio

import androidx.media3.common.MediaItem
import app.stillwave.StillwaveApp
import app.stillwave.data.CutWindow
import app.stillwave.data.Recording
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Private, disposable renders. The source recording is always opened read-only. */
object EditPreviewCache {
    data class Preview(val file: File, val identity: String, val sourceId: String, val window: CutWindow)

    private val preparation = Mutex()
    private val ready = ConcurrentHashMap<String, Preview>()
    @Volatile private var activeIdentity: String? = null
    @Volatile private var pendingIdentity: String? = null
    private const val INACTIVE_BYTES = 64L * 1024 * 1024
    private const val INACTIVE_FILES = 4
    private const val SPACE_RESERVE = 10L * 1024 * 1024

    fun variant(window: CutWindow?) = window?.let { "${it.id}:${it.startMs}:${it.endMs}:${it.reverse}:${it.speed}:${it.preservePitch}" }

    suspend fun prepare(app: StillwaveApp, recording: Recording, window: CutWindow): Preview = withContext(Dispatchers.IO) {
        preparation.withLock {
            app.repository.withRecording(recording.id) { source ->
                require(window.recordingId == source.id && window.validFor(source.durationMs)) { "Check the window timestamps and speed" }
                val directory = File(app.cacheDir, "edit-previews").apply { check(isDirectory || mkdirs()) { "Could not create preview storage" } }
                val audio = app.repository.file(source)
                val fingerprint = "${source.id}:${audio.length()}:${audio.lastModified()}:${variant(window)}"
                val identity = EditorAudioSource.sourcePrefix(source.id) + MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                val file = File(directory, "$identity.wav")
                val preview = Preview(file, identity, source.id, window)
                prune(directory, identity)
                if (!file.isFile) {
                    EditorAudioSource.withWav(app, source) { pcm ->
                        val info = WavFile.info(pcm)
                        val expected = ((window.endMs - window.startMs) / 1000.0 * info.rate / window.speed * info.frameSize).toLong() + 4096
                        require(expected <= WavFile.MAX_DATA_BYTES) { "This preview exceeds the WAV size limit" }
                        check(directory.usableSpace >= expected + SPACE_RESERVE) { "Not enough free space to prepare this preview" }
                        val coroutine = currentCoroutineContext()
                        val temporary = File(directory, "$identity.rendering")
                        temporary.delete()
                        try {
                            WindowRenderer.renderWindow(pcm, temporary, window) { coroutine.ensureActive() }
                            coroutine.ensureActive()
                            check(temporary.renameTo(file)) { "Could not publish edit preview" }
                        } finally { temporary.delete() }
                    }
                }
                currentCoroutineContext().ensureActive()
                file.setLastModified(System.currentTimeMillis())
                ready[identity] = preview
                preview
            }
        }
    }

    /** The service accepts only exact renders produced by this process, never caller-supplied paths. */
    fun accepts(app: StillwaveApp, item: MediaItem): Boolean = runCatching {
        val extras = item.mediaMetadata.extras ?: return false
        val identity = extras.getString(PlaybackService.PREVIEW_ID) ?: return false
        val preview = ready[identity] ?: return false
        val uri = item.localConfiguration?.uri ?: return false
        val file = File(checkNotNull(uri.path)).canonicalFile
        val window = preview.window
        uri.scheme == "file" && file == preview.file.canonicalFile && file.isFile &&
            file.parentFile == File(app.cacheDir, "edit-previews").canonicalFile && file.name == "$identity.wav" &&
            item.mediaId == preview.sourceId && extras.getString(PlaybackService.WINDOW_ID) == window.id &&
            extras.getString(PlaybackService.WINDOW_VARIANT) == variant(window) &&
            extras.getLong(PlaybackService.CLIP_START_MS) == window.startMs &&
            extras.getLong(PlaybackService.CLIP_END_MS) == window.endMs &&
            extras.getDouble(PlaybackService.PREVIEW_SPEED) == window.speed &&
            extras.getBoolean(PlaybackService.PREVIEW_REVERSE) == window.reverse &&
            item.clippingConfiguration.startPositionMs == 0L && item.clippingConfiguration.endPositionMs == androidx.media3.common.C.TIME_END_OF_SOURCE
    }.getOrDefault(false)

    fun pending(identity: String?) { pendingIdentity = identity }
    fun playing(identity: String?) {
        activeIdentity = identity
        if (pendingIdentity == identity) pendingIdentity = null
    }

    /** Called under the source repository lock, after its playback/preparation is stopped. */
    fun deleteForRecording(app: StillwaveApp, id: String) {
        val prefix = EditorAudioSource.sourcePrefix(id)
        ready.entries.filter { it.value.sourceId == id }.forEach { ready.remove(it.key) }
        File(app.cacheDir, "edit-previews").listFiles { file -> file.name.startsWith(prefix) }?.forEach {
            check(!it.exists() || it.delete()) { "Could not remove edit preview cache" }
        }
        if (activeIdentity?.startsWith(prefix) == true) activeIdentity = null
        if (pendingIdentity?.startsWith(prefix) == true) pendingIdentity = null
    }

    /** Keep active/candidate renders even when large; bound all other disposable files. */
    private fun prune(directory: File, candidate: String) {
        directory.listFiles { file -> file.extension == "rendering" }?.forEach { it.delete() }
        val protected = setOfNotNull(activeIdentity, pendingIdentity, candidate)
        val files = directory.listFiles { f -> f.extension == "wav" && f.nameWithoutExtension !in protected }
            .orEmpty().sortedByDescending(File::lastModified)
        var bytes = 0L
        files.forEachIndexed { index, file ->
            bytes += file.length()
            if (index >= INACTIVE_FILES || bytes > INACTIVE_BYTES) {
                if (file.delete()) ready.remove(file.nameWithoutExtension)
            }
        }
    }
}
