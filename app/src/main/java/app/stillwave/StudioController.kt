package app.stillwave

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import app.stillwave.audio.*
import app.stillwave.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** Project edits are serialized; render jobs use immutable snapshots and can be cancelled. */
class StudioController(private val app: StillwaveApp, private val scope: CoroutineScope,
    private val saved: SavedStateHandle, private val client: PlaybackClient, private val notice: (String) -> Unit) {
    val projects = app.multitrackStore.projects
    val open = saved.getStateFlow("studioOpen", false)
    val projectId = saved.getStateFlow<String?>("studioProject", null)
    val project = combine(projects, projectId) { items, id -> items.firstOrNull { it.id == id } }
        .stateIn(scope, SharingStarted.Eagerly, null)
    val busy = MutableStateFlow(false)
    /** Live (unrendered) preview of the open project. */
    val live = MultitrackLivePreview.state
    val exportId = saved.getStateFlow<String?>("studioExport", null)
    private data class ClipUndo(val projectId: String, val before: MultitrackProject, val after: MultitrackProject)
    private val undo = MutableStateFlow<ClipUndo?>(null)
    val canUndo = combine(project, undo) { current, edit -> current != null && edit != null &&
        current.id == edit.projectId && current.tracks == edit.after.tracks && current.durationMs == edit.after.durationMs }
        .stateIn(scope, SharingStarted.Eagerly, false)
    private val edits = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var renderJob: Job? = null
    private var renderingProject: String? = null
    private var preparedContent: MultitrackProject? = null
    private var preparedIdentity: String? = null
    private var generation = 0L
    init {
        scope.launch { for (edit in edits) {
            try { edit() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice(failure.message ?: "Could not save project") }
        } }
        scope.launch {
            project.filterNotNull().collect { current ->
                // The live mix follows every edit immediately; it only reopens sources when the sound changes.
                if (current.tracks.isEmpty()) { if (MultitrackLivePreview.isActive(current.id)) MultitrackLivePreview.stop() }
                else MultitrackLivePreview.update(current)
                try {
                    val player = client.player()
                    if (current.tracks.isEmpty() && (player.currentMediaItem?.mediaId == current.id || renderingProject == current.id)) {
                        pause(); client.clear(listOf(current.id))
                    } else if ((player.currentMediaItem?.mediaId == current.id && player.isPlaying) || renderingProject == current.id) {
                        render(current, keepPosition = true)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { notice(failure.message ?: "Could not update project playback") }
            }
        }
    }
    fun show(id: String? = null) { saved["studioOpen"] = true; saved["studioProject"] = id }
    fun back() { pause(); MultitrackLivePreview.stop(); if (projectId.value != null) saved["studioProject"] = null else saved["studioOpen"] = false }
    fun create(title: String, loop: Boolean, recordings: List<Recording> = emptyList(), durationMs: Long = 10_000) {
        edits.trySend {
            require(recordings.size <= MAX_MULTITRACK_TRACKS) { "Select up to $MAX_MULTITRACK_TRACKS recordings" }
            val project = MultitrackProject(title = title.trim(), loopPlayback = loop,
                durationMs = recordings.maxOfOrNull { it.durationMs } ?: durationMs,
                tracks = recordings.map { MultitrackTrack(recordingId = it.id, title = it.title.take(160), sourceEndMs = it.durationMs, loop = loop) })
            app.multitrackStore.save(project); show(project.id)
        }
    }
    fun update(transform: (MultitrackProject) -> MultitrackProject) {
        val id = projectId.value ?: return
        edits.trySend {
            app.multitrackStore.update(id, transform)
        }
    }
    fun track(id: String, transform: (MultitrackTrack) -> MultitrackTrack) = update { project ->
        project.copy(tracks = project.tracks.map { if (it.id == id) transform(it) else it })
    }
    private fun editClips(done: (Boolean) -> Unit = {}, transform: (MultitrackProject) -> MultitrackProject) {
        val targetId = projectId.value ?: run { done(false); return }
        val queued = edits.trySend {
            try {
                check(!(RecordingService.state.value.active && RecordingService.state.value.projectId == targetId)) { "Finish recording before moving tracks" }
                var before: MultitrackProject? = null
                val after = app.multitrackStore.update(targetId) { current ->
                    check(!(RecordingService.state.value.active && RecordingService.state.value.projectId == targetId)) { "Finish recording before moving tracks" }
                    before = current
                    transform(current)
                }
                undo.value = ClipUndo(targetId, checkNotNull(before), after)
                project.first { current -> projectId.value != targetId || current == projects.value.firstOrNull { it.id == targetId } }
                done(true)
            } catch (failure: Exception) { done(false); throw failure }
        }
        if (queued.isFailure) done(false)
    }
    fun moveTrack(id: String, offsetMs: Long, done: (Boolean) -> Unit = {}) = editClips(done) { current ->
        check(current.tracks.any { it.id == id }) { "Clip no longer exists" }
        movedTrackProject(current, id, offsetMs)
    }
    fun splitClip(id: String, atMs: Long) = editClips { splitProjectClip(it, id, atMs) }
    fun trimClip(id: String, fromMs: Long, toMs: Long, sourceDurationMs: Long) =
        editClips { trimmedProjectClip(it, id, fromMs, toMs, sourceDurationMs) }
    fun duplicateClip(id: String) = editClips { duplicateProjectClip(it, id) }
    fun moveToLane(id: String, laneId: String) = editClips { moveClipToLane(it, id, laneId) }
    fun editClip(edited: MultitrackTrack) = editClips { project ->
        val current = project.tracks.firstOrNull { it.id == edited.id } ?: error("Clip no longer exists")
        val replacement = current.copy(title = edited.title, sourceStartMs = edited.sourceStartMs,
            sourceEndMs = edited.sourceEndMs, offsetMs = edited.offsetMs)
        project.copy(tracks = project.tracks.map { if (it.id == edited.id) replacement else it },
            durationMs = if (project.loopPlayback || replacement.loop) project.durationMs else
                maxOf(project.durationMs, replacement.offsetMs + replacement.sourceDurationMs))
    }
    fun undoClipEdit() {
        val edit = undo.value ?: return
        if (edit.projectId != projectId.value) return
        editClips(done = { if (it) undo.value = null }) { current ->
            check(current.tracks == edit.after.tracks && current.durationMs == edit.after.durationMs) { "The project has changed since this edit" }
            current.copy(tracks = edit.before.tracks, durationMs = edit.before.durationMs, laneOrder = edit.before.laneOrder)
        }
    }
    fun add(recordings: List<Recording>) = editClips { project ->
        val added = recordings.map { MultitrackTrack(recordingId = it.id, title = it.title.take(160), sourceEndMs = it.durationMs, loop = project.loopPlayback) }
        project.copy(tracks = project.tracks + added, durationMs = if (project.loopPlayback) project.durationMs
            else maxOf(project.durationMs, recordings.maxOfOrNull { it.durationMs } ?: 1))
    }
    fun removeTrack(id: String) = editClips { it.copy(tracks = it.tracks.filterNot { track -> track.id == id }) }
    fun delete(project: MultitrackProject) {
        pause()
        if (MultitrackLivePreview.isActive(project.id)) MultitrackLivePreview.stop()
        edits.trySend {
            client.clear(listOf(project.id))
            app.multitrackStore.delete(project.id)
            withContext(Dispatchers.IO) { MultitrackAudio.deleteForProject(app, project.id) }
            if (projectId.value == project.id) saved["studioProject"] = null
        }
    }
    fun cancelPreparation() { generation++; renderJob?.cancel(); renderJob = null; renderingProject = null; busy.value = false; MultitrackAudio.pending(null) }
    fun pause() { cancelPreparation(); client.pause(); PlaybackService.pauseForRecording() }

    /** Plays the project by mixing its clips in real time: no render wait, and edits are heard while playing. */
    fun play(fromMs: Long? = null) {
        val current = project.value ?: return
        if (MultitrackLivePreview.isActive(current.id) && live.value.playing && fromMs == null) { MultitrackLivePreview.pause(); return }
        if (RecordingService.state.value.active && !app.settings.current.allowPlaybackWhileRecording) {
            notice("Enable Playback while recording in Settings to hear backing tracks during capture"); return
        }
        cancelPreparation()
        client.pause() // A rendered mix or library recording must not play underneath the live preview.
        MultitrackLivePreview.play(app, current, fromMs) { message -> scope.launch { notice(message) } }
    }

    fun pauseLive() = MultitrackLivePreview.pause()

    /** Renders the mix to a cached file and plays it through the background player with lock-screen controls. */
    fun playRendered() {
        val current = project.value ?: return
        if (RecordingService.state.value.active && !app.settings.current.allowPlaybackWhileRecording) {
            notice("Enable Playback while recording in Settings to hear backing tracks during capture"); return
        }
        val livePosition = MultitrackLivePreview.position(current.id)?.positionMs
        MultitrackLivePreview.stop()
        cancelPreparation()
        val request = generation
        scope.launch {
            try {
                val player = client.player()
                if (request != generation || !open.value || projectId.value != current.id) return@launch
                if (player.currentMediaItem?.mediaId == current.id && player.isPlaying) pause() else project.value?.let { render(it, startMs = livePosition) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice(failure.message ?: "Could not play project") }
        }
    }
    private fun render(snapshot: MultitrackProject, keepPosition: Boolean = false, startMs: Long? = null) {
        cancelPreparation()
        val request = generation
        renderingProject = snapshot.id
        renderJob = scope.launch {
            try {
                val player = client.player()
                ensureActive()
                if (generation != request) return@launch
                // A loaded mix is independent of its source files. Do not wait behind waveform
                // decoding/source locks just to resume it, but never reuse it after a sound edit
                // or cache invalidation (e.g. deletion of a source recording).
                val loaded = player.currentMediaItem
                if (preparedContent == snapshot.renderContent() && preparedIdentity != null &&
                    loaded?.mediaId == snapshot.id && loaded.mediaMetadata.title?.toString() == snapshot.title &&
                    loaded.mediaMetadata.extras?.getString(PlaybackService.PROJECT_PREVIEW_ID) == preparedIdentity &&
                    player.playbackState != Player.STATE_IDLE && MultitrackAudio.accepts(app, loaded)) {
                    player.repeatMode = if (snapshot.loopPlayback) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    if (startMs != null) player.seekTo(startMs) else if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    player.play()
                    return@launch
                }
                busy.value = true
                if (keepPosition) delay(200) // Collapse a drag or quick mixer toggles into one render.
                val preview = MultitrackAudio.prepare(app, snapshot)
                ensureActive()
                if (generation != request) return@launch
                preparedContent = snapshot.renderContent()
                preparedIdentity = preview.identity
                player.repeatMode = if (snapshot.loopPlayback) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                val existing = player.currentMediaItem
                if (existing?.mediaId == snapshot.id && existing.mediaMetadata.extras?.getString(PlaybackService.PROJECT_PREVIEW_ID) == preview.identity &&
                    existing.mediaMetadata.title?.toString() == snapshot.title && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE) {
                    startMs?.let(player::seekTo); player.play(); return@launch
                }
                val position = startMs ?: if (player.currentMediaItem?.mediaId == snapshot.id && player.playbackState != Player.STATE_ENDED) player.currentPosition else 0L
                MultitrackAudio.pending(preview.identity)
                EditPreviewCache.pending(null)
                val extras = Bundle().apply { putString(PlaybackService.PROJECT_PREVIEW_ID, preview.identity) }
                player.setMediaItem(MediaItem.Builder().setMediaId(snapshot.id).setUri(preview.file.toURI().toString())
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(snapshot.title).setArtist("Stillwave").setExtras(extras).build()).build())
                player.prepare()
                player.seekTo(if (snapshot.loopPlayback) position % snapshot.durationMs else position.coerceIn(0, snapshot.durationMs - 1))
                player.play()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice(failure.message ?: "Could not prepare project playback") }
            finally { if (generation == request) { busy.value = false; renderingProject = null } }
        }
    }
    fun seek(positionMs: Long) {
        val id = projectId.value ?: return
        if (MultitrackLivePreview.isActive(id)) { MultitrackLivePreview.seek(id, positionMs); return }
        val request = generation
        scope.launch {
            try {
                val player = client.player()
                if (request == generation && player.currentMediaItem?.mediaId == id) player.seekTo(positionMs.coerceAtLeast(0))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice(failure.message ?: "Could not seek project") }
        }
    }
    fun export(title: String) {
        if (exportId.value != null) return
        val id = projectId.value ?: return
        edits.trySend {
            if (exportId.value != null) return@trySend
            val current = checkNotNull(projects.value.firstOrNull { it.id == id }) { "Project no longer exists" }
            val job = withContext(Dispatchers.IO) { MultitrackExportWorker.enqueue(app, current, title) }
            saved["studioExport"] = job.toString()
        }
    }
    fun clearExport() { saved["studioExport"] = null }
}
