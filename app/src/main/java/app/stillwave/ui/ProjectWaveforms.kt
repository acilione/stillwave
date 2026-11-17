package app.stillwave.ui

import androidx.compose.runtime.*
import app.stillwave.StillwaveApp
import app.stillwave.audio.AudioTrace
import app.stillwave.audio.EditorAudioSource
import app.stillwave.data.MultitrackProject
import app.stillwave.data.Recording
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class ClipWaveform(val trace: AudioTrace? = null, val loading: Boolean = true, val error: String? = null)
private data class TraceRequest(val clipId: String, val sourceId: String, val fileName: String?, val from: Long, val to: Long)

/** A small in-memory cache survives project navigation; neither moving nor changing gain decodes audio again. */
private object ProjectTraceCache {
    private val cache = LinkedHashMap<String, AudioTrace>(32, 0.75f, true)
    private val loading = Semaphore(2)
    suspend fun read(app: StillwaveApp, id: String, from: Long, to: Long): AudioTrace = loading.withPermit {
        withContext(Dispatchers.IO) {
            app.repository.withRecording(id) { source ->
                val file = app.repository.file(source)
                val key = "$id:${file.length()}:${file.lastModified()}:$from:$to"
                synchronized(cache) { cache[key] } ?: EditorAudioSource.withWav(app, source) { wav ->
                    val context = currentCoroutineContext()
                    AudioTrace.read(wav, from, to, checkCancelled = { context.ensureActive() }).also { trace ->
                        synchronized(cache) {
                            cache[key] = trace
                            while (cache.size > 32) cache.remove(cache.keys.first())
                        }
                    }
                }
            }
        }
    }
}

@Composable fun rememberProjectWaveforms(app: StillwaveApp, project: MultitrackProject, recordings: List<Recording>): State<Map<String, ClipWaveform>> {
    val sources = recordings.associateBy { it.id }
    val requests = project.tracks.map { TraceRequest(it.id, it.recordingId, sources[it.recordingId]?.fileName, it.sourceStartMs, it.sourceEndMs) }
    return produceState<Map<String, ClipWaveform>>(emptyMap(), project.id, requests) {
        value = requests.associate { it.clipId to ClipWaveform() }
        coroutineScope {
            requests.forEach { request -> launch {
                val loaded = try {
                    check(request.fileName != null) { "Source recording missing" }
                    ClipWaveform(ProjectTraceCache.read(app, request.sourceId, request.from, request.to), loading = false)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { ClipWaveform(loading = false, error = "Waveform unavailable") }
                value = value + (request.clipId to loaded)
            } }
        }
    }
}
