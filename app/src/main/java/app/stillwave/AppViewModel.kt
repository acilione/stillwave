package app.stillwave

import android.app.Application
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.media3.common.*
import app.stillwave.audio.*
import app.stillwave.data.*
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel

data class LibraryFilter(val search: String = "", val from: LocalDate? = null, val through: LocalDate? = null)
data class Playback(val recordingId: String? = null, val playing: Boolean = false, val positionMs: Long = 0, val windowId: String? = null,
    val looping: Boolean = false)

class AppViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    val app = application as StillwaveApp
    val dao = app.database.recordings()
    val settings = app.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val all = dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val folders = dao.observeFolders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val selectedFolder = saved.getStateFlow("folder", ALL_FOLDERS)
    val recordingFolderId get() = selectedFolder.value.takeUnless { it == ALL_FOLDERS || it == UNFILED }
    val filter = MutableStateFlow(LibraryFilter())
    val recordings = combine(all, settings, filter, selectedFolder) { recordings, settings, filter, folder ->
        val (from, until) = periodBounds(filter.from, filter.through)
        val inFolder = when (folder) { ALL_FOLDERS -> recordings; UNFILED -> recordings.filter { it.folderId == null }; else -> recordings.filter { it.folderId == folder } }
        organizeRecordings(inFolder, settings.sort, from, until, filter.search)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val editorId = saved.getStateFlow<String?>("editor", null)
    private val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices = messages.asSharedFlow()
    val exportJob = saved.getStateFlow<String?>("export", null)
    val exportStarting = MutableStateFlow(false)
    val deleting = MutableStateFlow(false)
    private val edits = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    var pendingRecordingQuality: Quality?
        get() = saved.get<String>("pendingRecordingQuality")?.let(Quality::valueOf)
        set(value) { saved["pendingRecordingQuality"] = value?.name }
    var pendingRecordingFolder: String?
        get() = saved["pendingRecordingFolder"]
        set(value) { saved["pendingRecordingFolder"] = value }
    var pendingRecordingProject: String?
        get() = saved["pendingRecordingProject"]
        set(value) { saved["pendingRecordingProject"] = value }
    private val _playback = MutableStateFlow(Playback())
    val playback = _playback.asStateFlow()
    private var playbackCommand = 0L
    private var requestedPlaybackId: String? = null
    private var playbackTask: Job? = null
    private data class LoopIntent(val recordingId: String, val windowId: String?, val enabled: Boolean)
    private var pendingLoop: LoopIntent? = null
    private val _previewPreparing = MutableStateFlow(false)
    val previewPreparing = _previewPreparing.asStateFlow()
    private val playbackClient = PlaybackClient(app, viewModelScope, { _playback.value = it }, ::notify)
    val studio = StudioController(app, viewModelScope, saved, playbackClient, ::notify)
    init {
        viewModelScope.launch { for (edit in edits) { try { edit() } catch (e: Exception) { notify(e.message ?: "Could not save edit") } } }
    }
    fun notify(message: String) { messages.tryEmit(message) }
    fun runAction(block: suspend () -> Unit) { viewModelScope.launch { try { block() } catch (c: CancellationException) { throw c } catch (e: Exception) { notify(e.message ?: "Operation failed") } } }
    fun <T> setting(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T) = runAction { app.settings.set(key, value) }
    fun openEditor(id: String?) { pausePlayback(); saved["editor"] = id }
    fun rename(recording: Recording, title: String) = runAction { require(title.isNotBlank()); dao.rename(recording.id, title.trim()) }
    fun forgetMicrophone(key: String) = runAction { app.microphoneProfiles.forget(key) }
    fun selectMicrophone(key: String, name: String) = runAction { app.settings.selectMicrophone(key, name) }
    fun selectFolder(id: String) { saved["folder"] = id }
    fun createFolder(name: String, moveIds: List<String> = emptyList(), done: (RecordingFolder) -> Unit) = runAction {
        val folder = RecordingFolder(name = name.trim())
        app.database.withTransaction {
            val error = folderNameError(name, dao.folders())
            require(error == null) { error.orEmpty() }
            dao.insertFolder(folder)
            if (moveIds.isNotEmpty()) dao.move(moveIds.distinct(), folder.id)
        }
        done(folder)
    }
    fun renameFolder(folder: RecordingFolder, name: String, done: () -> Unit) = runAction {
        app.database.withTransaction {
            check(dao.folder(folder.id) != null) { "Folder no longer exists" }
            val error = folderNameError(name, dao.folders(), folder.id)
            require(error == null) { error.orEmpty() }
            dao.renameFolder(folder.id, name.trim(), name.trim().lowercase(java.util.Locale.ROOT))
        }
        done()
    }
    fun deleteFolder(folder: RecordingFolder) = runAction {
        dao.deleteFolder(folder.id) // SET NULL preserves every recording and its edits.
        if (selectedFolder.value == folder.id) selectFolder(UNFILED)
        notify("Folder removed. Its recordings are in Unfiled.")
    }
    fun moveRecordings(ids: List<String>, folderId: String?, done: () -> Unit) = runAction {
        app.database.withTransaction {
            check(folderId == null || dao.folder(folderId) != null) { "Folder no longer exists" }
            dao.move(ids.distinct(), folderId)
        }
        done()
        notify("Recordings moved")
    }
    fun deleteRecordings(ids: List<String>) {
        if (deleting.value || ids.isEmpty()) return
        deleting.value = true
        studio.cancelPreparation()
        MultitrackLivePreview.stop() // Releases open source files before the repository removes them.
        if (_playback.value.recordingId in ids || requestedPlaybackId in ids) {
            nextPlaybackRequest(null)
            _playback.value = Playback()
        }
        PlaybackService.clearRecordings(ids); playbackClient.clear(ids)
        if (editorId.value in ids) saved["editor"] = null
        runAction {
            try {
                val result = app.repository.delete(ids)
                notify(if (result.failed > 0) "Some audio could not be fully removed. Cleanup will retry on the next launch."
                    else if (result.deleted == 1) "Recording deleted" else "${result.deleted} recordings deleted")
            } finally { deleting.value = false }
        }
    }
    fun pin(ids: List<String>, value: Boolean) = runAction { dao.pin(ids, value) }
    fun window(window: CutWindow) { edits.trySend { dao.window(window) } }
    fun deleteWindow(window: CutWindow) { edits.trySend { dao.deleteWindow(window) } }
    fun checkpoint(checkpoint: Checkpoint) = runAction { dao.checkpoint(checkpoint) }
    fun deleteCheckpoint(checkpoint: Checkpoint) = runAction { dao.deleteCheckpoint(checkpoint) }
    fun export(recording: Recording, windows: List<CutWindow>, fullAudio: Boolean = false, title: String = "", removeSelected: Boolean = false) {
        if (exportStarting.value || exportJob.value != null) return
        exportStarting.value = true
        runAction {
            try {
                require(windows.isNotEmpty() && windows.all { it.validFor(recording.durationMs) }) { "Check the selected window timestamps and speed" }
                if (fullAudio || removeSelected) {
                    val error = if (removeSelected) WindowRenderer.removalExportError(windows, recording.durationMs)
                        else WindowRenderer.fullExportError(windows, recording.durationMs)
                    require(error == null) { error.orEmpty() }
                }
                val job = withContext(Dispatchers.IO) { ExportWorker.enqueue(app, recording.id, windows, fullAudio, title, removeSelected) }
                saved["export"] = job.toString()
            } finally { exportStarting.value = false }
        }
    }
    fun clearExport() { saved["export"] = null }
    private fun nextPlaybackRequest(recordingId: String?): Long {
        studio.cancelPreparation()
        MultitrackLivePreview.stop()
        playbackTask?.cancel(); playbackTask = null
        pendingLoop = null
        _previewPreparing.value = false
        requestedPlaybackId = recordingId
        return ++playbackCommand
    }
    /** Unspecified repeat preserves this target's loop across pause/resume and changed window effects. */
    fun play(recording: Recording, window: CutWindow? = null, start: Long? = null, loop: Boolean? = null) =
        requestPlayback(recording, window, start, loop, changeLoopOnly = false)

    fun toggleLoop(recording: Recording, window: CutWindow? = null) {
        val intent = pendingLoop?.takeIf { it.recordingId == recording.id && it.windowId == window?.id }
        val current = playback.value
        val enabled = intent?.enabled ?: (current.recordingId == recording.id && current.windowId == window?.id && current.looping)
        requestPlayback(recording, window, null, !enabled, changeLoopOnly = true)
    }

    private fun requestPlayback(recording: Recording, window: CutWindow?, start: Long?, loop: Boolean?, changeLoopOnly: Boolean) {
        val disabling = changeLoopOnly && loop == false
        if (!disabling && RecordingService.state.value.active && !settings.value.allowPlaybackWhileRecording) {
            notify("Enable Playback while recording in Settings to play audio during capture"); return
        }
        val inherited = pendingLoop?.takeIf { it.recordingId == recording.id && it.windowId == window?.id }?.enabled
            ?: playback.value.let { it.recordingId == recording.id && it.windowId == window?.id && it.looping }
        val repeat = loop ?: inherited
        val request = nextPlaybackRequest(recording.id)
        pendingLoop = LoopIntent(recording.id, window?.id, repeat)
        playbackTask = viewModelScope.launch {
            try {
                val player = playbackClient.player()
                if (request != playbackCommand) return@launch
                check(dao.get(recording.id) != null) { "Recording no longer exists" }
                if (request != playbackCommand) return@launch
                if (window != null) require(window.recordingId == recording.id && window.validFor(recording.durationMs)) { "Check the window timestamps and speed" }
                val item = player.currentMediaItem
                val sameTarget = item?.mediaId == recording.id && item.mediaMetadata.extras?.getString(PlaybackService.WINDOW_ID) == window?.id
                val same = sameTarget && item.mediaMetadata.extras?.getString(PlaybackService.WINDOW_VARIANT) == EditPreviewCache.variant(window)
                if (sameTarget && disabling) {
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    return@launch // Turning off repeat does not stop or restart the current pass.
                }
                if (same && start == null) {
                    player.repeatMode = if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    if (!changeLoopOnly && loop == null && player.isPlaying) { player.pause(); return@launch }
                    if (player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE) { player.play(); return@launch }
                }
                if (RecordingService.state.value.active && !settings.value.allowPlaybackWhileRecording) return@launch
                val preview = if (window != null && (window.reverse || window.speed != 1.0)) {
                    player.pause()
                    _previewPreparing.value = true
                    EditPreviewCache.prepare(app, recording, window)
                } else null
                ensureActive()
                if (request != playbackCommand) return@launch
                EditPreviewCache.pending(preview?.identity)
                player.repeatMode = if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                player.setMediaItem(playbackItem(recording, window, preview)); player.prepare()
                if (start != null) {
                    val relative = if (preview != null && window != null) {
                        ((if (window.reverse) window.endMs - start else start - window.startMs) / window.speed).toLong()
                    } else start - (window?.startMs ?: 0)
                    val duration = window?.let { ((it.endMs - it.startMs) / it.speed).toLong() } ?: recording.durationMs
                    player.seekTo(relative.coerceIn(0, duration))
                }
                player.play()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notify(error.message ?: "Could not prepare audio preview") }
            finally { if (request == playbackCommand) { _previewPreparing.value = false; pendingLoop = null } }
        }
    }
    fun seek(recording: Recording, position: Long) {
        val request = nextPlaybackRequest(recording.id)
        runAction {
            val player = playbackClient.player()
            if (request != playbackCommand) return@runAction
            check(dao.get(recording.id) != null) { "Recording no longer exists" }
            if (request != playbackCommand) return@runAction
            val item = player.currentMediaItem
            if (item?.mediaId != recording.id || item.mediaMetadata.extras?.getString(PlaybackService.WINDOW_ID) != null) {
                EditPreviewCache.pending(null)
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.setMediaItem(playbackItem(recording)); player.prepare()
            }
            player.seekTo(position.coerceIn(0, recording.durationMs))
        }
    }
    private fun playbackItem(recording: Recording, window: CutWindow? = null, preview: EditPreviewCache.Preview? = null): MediaItem {
        val extras = android.os.Bundle().apply {
            putLong(PlaybackService.CLIP_START_MS, window?.startMs ?: 0)
            window?.let {
                putString(PlaybackService.WINDOW_ID, it.id)
                putString(PlaybackService.WINDOW_VARIANT, EditPreviewCache.variant(it))
            }
            preview?.let {
                putString(PlaybackService.PREVIEW_ID, it.identity)
                putLong(PlaybackService.CLIP_END_MS, it.window.endMs)
                putDouble(PlaybackService.PREVIEW_SPEED, it.window.speed)
                putBoolean(PlaybackService.PREVIEW_REVERSE, it.window.reverse)
            }
        }
        val media = MediaItem.Builder().setMediaId(recording.id).setUri((preview?.file ?: app.repository.file(recording)).toURI().toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(window?.exportTitle(recording.title) ?: recording.title)
                .setArtist("Stillwave").setExtras(extras).build())
        if (preview == null) window?.let { media.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(it.startMs).setEndPositionMs(it.endMs).build()) }
        return media.build()
    }
    fun pausePlayback() { nextPlaybackRequest(null); PlaybackService.pauseForRecording(); playbackClient.pause() }
    fun shareIntent(recordings: List<Recording>): Intent {
        val uris = ArrayList(recordings.map { FileProvider.getUriForFile(app, "${app.packageName}.files", app.repository.file(it), "${it.title.replace('/', '-')}.${it.fileExtension}") })
        require(uris.isNotEmpty())
        return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = recordings.map { it.mimeType }.distinct().singleOrNull() ?: "audio/*"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri("Recordings", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    override fun onCleared() { playbackTask?.cancel(); MultitrackLivePreview.stop(); playbackClient.close() }
}
