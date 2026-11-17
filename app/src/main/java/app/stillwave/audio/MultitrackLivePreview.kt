package app.stillwave.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.stillwave.StillwaveApp
import app.stillwave.data.MultitrackProject
import app.stillwave.data.audibleTracks
import app.stillwave.data.renderContent
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Real-time project preview: clips are mixed straight from their sources into an AudioTrack,
 * so nothing is rendered to disk and mixer/clip edits are heard within about 100 ms.
 *
 * Sources are opened under the repository lock and decoding lease, then the lock is released;
 * the open descriptors stay readable even if a cache file is pruned. Deleting a recording stops
 * the preview first. Unlike the saved mix, peaks are hard-limited rather than normalized.
 */
object MultitrackLivePreview {
    data class State(val projectId: String? = null, val playing: Boolean = false, val positionMs: Long = 0, val loading: Boolean = false)
    data class Position(val positionMs: Long, val playing: Boolean)

    private const val RATE = MultitrackRenderer.SAMPLE_RATE
    private const val BLOCK = 1024
    private const val PUBLISH_MS = 50L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()
    @Volatile private var session: Session? = null

    fun isActive(projectId: String) = session?.projectId == projectId

    /** Starts (or resumes) the project. [fromMs] seeks first; null continues from the current position. */
    fun play(app: StillwaveApp, project: MultitrackProject, fromMs: Long?, onError: (String) -> Unit) {
        val current = session?.takeIf { it.projectId == project.id } ?: run {
            stop()
            Session(app, project, onError).also { session = it }
        }
        current.update(project)
        fromMs?.let(current::seek)
        current.resume()
    }

    fun update(project: MultitrackProject) { session?.takeIf { it.projectId == project.id }?.update(project) }
    fun pause() { session?.pause() }
    fun seek(projectId: String, positionMs: Long) { session?.takeIf { it.projectId == projectId }?.seek(positionMs) }
    fun stop() {
        val ending = session ?: return
        session = null
        ending.close()
        mutableState.value = State()
    }
    fun position(projectId: String): Position? = session?.takeIf { it.projectId == projectId }?.position()

    private class Mix(val content: MultitrackProject, val readers: List<MultitrackRenderer.Reader>) : Closeable {
        override fun close() = readers.forEach { runCatching { it.close() } }
    }

    private class Session(private val app: StillwaveApp, initial: MultitrackProject, private val onError: (String) -> Unit) {
        val projectId = initial.id
        private val projects = MutableStateFlow(initial)
        @Volatile private var project = initial // Latest snapshot: loop mode and duration apply without reopening sources.
        private val ready = AtomicReference<Mix?>(null) // Newest opened mix, handed over to the audio thread.
        @Volatile private var hasMix = false
        @Volatile private var playing = false
        @Volatile private var ended = false
        @Volatile private var closed = false
        private val seekRequest = AtomicLong(-1)
        @Volatile private var heardFrame = 0L
        @Volatile private var heardAt = 0L
        private val wake = Object()
        private val audio = app.getSystemService(AudioManager::class.java)
        private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change -> if (change != AudioManager.AUDIOFOCUS_GAIN) pause() }.build()
        private val noisy = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) = pause() }

        private val loader = scope.launch {
            projects.distinctUntilChangedBy { it.renderContent() }.collectLatest { snapshot ->
                try {
                    val mix = open(snapshot)
                    if (closed) { mix.close(); return@collectLatest }
                    ready.getAndSet(mix)?.close()
                    hasMix = true
                    signal()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    pause()
                    onError(failure.message ?: "Could not open project audio")
                }
            }
        }
        private val thread = Thread(::run, "Stillwave live mix").apply { start() }

        init { ContextCompat.registerReceiver(app, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED) }

        fun update(next: MultitrackProject) { project = next; projects.value = next; signal() }
        fun seek(positionMs: Long) {
            val frames = project.durationMs * RATE / 1000
            seekRequest.set((positionMs.coerceAtLeast(0) * RATE / 1000).coerceIn(0, maxOf(0, frames - 1)))
            ended = false
            signal()
        }
        fun resume() {
            if (closed) return
            if (ended) { ended = false; seekRequest.compareAndSet(-1, 0) }
            audio.requestAudioFocus(focus)
            playing = true
            publish()
            signal()
        }
        fun pause() {
            if (!playing) return
            playing = false
            audio.abandonAudioFocusRequest(focus)
            signal()
        }
        fun position(): Position {
            val elapsed = if (playing && hasMix) SystemClock.elapsedRealtime() - heardAt else 0
            val ms = heardFrame * 1000 / RATE + elapsed
            val duration = project.durationMs
            return Position(if (project.loopPlayback) Math.floorMod(ms, duration) else ms.coerceAtMost(duration), playing)
        }
        fun close() {
            closed = true; playing = false
            loader.cancel()
            audio.abandonAudioFocusRequest(focus)
            runCatching { app.unregisterReceiver(noisy) }
            signal()
        }

        private suspend fun open(snapshot: MultitrackProject): Mix {
            val audible = audibleTracks(snapshot.tracks).mapTo(HashSet()) { it.id }
            if (audible.isEmpty()) return Mix(snapshot.renderContent(), emptyList())
            return MultitrackAudio.withSources(app, snapshot.copy(tracks = snapshot.tracks.filter { it.id in audible })) { inputs ->
                val readers = mutableListOf<MultitrackRenderer.Reader>()
                try {
                    inputs.forEach { currentCoroutineContext().ensureActive(); readers += MultitrackRenderer.reader(it) }
                    Mix(snapshot.renderContent(), readers)
                } catch (failure: Throwable) { readers.forEach { runCatching { it.close() } }; throw failure }
            }
        }

        private fun signal() = synchronized(wake) { wake.notifyAll() }
        private fun idle(ms: Long) = synchronized(wake) { if (!closed) wake.wait(ms) }

        private fun publish() {
            if (session !== this || closed) return
            mutableState.value = State(projectId, playing, position().positionMs, loading = playing && !hasMix)
        }

        private fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                .setBufferSizeInBytes(maxOf(minimum, BLOCK * 2 * 4 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            var mix: Mix? = null
            val samples = DoubleArray(BLOCK * 2)
            val output = FloatArray(BLOCK * 2)
            var cursor = 0L // Next timeline frame to write.
            var baseFrame = 0L // Timeline frame at baseHead.
            var baseHead = 0L
            var running = false
            var lastPublish = 0L
            fun head() = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
            fun heard(): Long {
                val frame = baseFrame + if (running) head() - baseHead else 0
                val total = project.durationMs * RATE / 1000
                return if (project.loopPlayback) Math.floorMod(frame, total) else frame.coerceAtMost(total)
            }
            /** Discards queued audio so a resume or seek continues from [frame], not from the buffered tail. */
            fun restartAt(frame: Long) {
                if (running) { track.pause(); running = false }
                track.flush()
                cursor = frame; baseFrame = frame; baseHead = head()
                heardFrame = frame; heardAt = SystemClock.elapsedRealtime()
            }
            try {
                while (!closed) {
                    ready.getAndSet(null)?.let { next -> mix?.close(); mix = next }
                    val seek = seekRequest.getAndSet(-1)
                    if (seek >= 0) restartAt(seek)
                    val current = mix
                    if (!playing || current == null) {
                        if (running) restartAt(heard())
                        publish()
                        idle(if (playing) 20 else 250)
                        continue
                    }
                    val total = project.durationMs * RATE / 1000
                    if (project.loopPlayback) cursor = Math.floorMod(cursor, total)
                    if (!project.loopPlayback && cursor >= total) {
                        // Let the buffered tail play out, then stop at the end like the rendered player.
                        if (heard() >= total || !running) { restartAt(total); playing = false; ended = true; audio.abandonAudioFocusRequest(focus); publish() }
                        else { heardFrame = heard(); heardAt = SystemClock.elapsedRealtime(); publish(); idle(10) }
                        continue
                    }
                    val count = minOf(BLOCK.toLong(), total - cursor).toInt()
                    samples.fill(0.0, 0, count * 2)
                    for (reader in current.readers) reader.mix(cursor, count, samples)
                    for (index in 0 until count * 2) output[index] = samples[index].coerceIn(-1.0, 1.0).toFloat()
                    if (!running) { track.play(); running = true }
                    track.write(output, 0, count * 2, AudioTrack.WRITE_BLOCKING)
                    cursor += count
                    // Loop playback wraps after the cycle's last frame.
                    if (project.loopPlayback && cursor >= total) cursor = 0
                    // A position from the hardware head; the UI extrapolates between updates.
                    heardFrame = baseFrame + head() - baseHead
                    heardAt = SystemClock.elapsedRealtime()
                    if (heardAt - lastPublish >= PUBLISH_MS) { publish(); lastPublish = heardAt }
                }
            } catch (failure: Exception) {
                if (!closed) { playing = false; onError(failure.message ?: "Live preview stopped") }
            } finally {
                runCatching { track.pause(); track.flush() }
                track.release()
                mix?.close()
                ready.getAndSet(null)?.close()
            }
        }
    }
}
