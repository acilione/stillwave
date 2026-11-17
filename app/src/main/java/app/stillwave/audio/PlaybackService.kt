package app.stillwave.audio

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import app.stillwave.MainActivity
import app.stillwave.StillwaveApp
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Owns playback independently of activities, with Android's native media notification/session. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    companion object {
        const val WINDOW_ID = "window_id"
        const val CLIP_START_MS = "clip_start_ms"
        const val CLIP_END_MS = "clip_end_ms"
        const val WINDOW_VARIANT = "window_variant"
        const val PREVIEW_ID = "preview_id"
        const val PREVIEW_SPEED = "preview_speed"
        const val PREVIEW_REVERSE = "preview_reverse"
        const val PROJECT_PREVIEW_ID = "project_preview_id"
        private var active: PlaybackService? = null // Accessed only on the main/application looper.

        fun pauseForRecording() { active?.pause(); MultitrackLivePreview.pause() }
        /** Read only on the main looper, immediately after the microphone opens. */
        data class ProjectPosition(val positionMs: Long, val playing: Boolean)
        fun projectPosition(projectId: String): ProjectPosition? = MultitrackLivePreview.position(projectId)?.let {
            ProjectPosition(it.positionMs, it.playing)
        } ?: active?.player?.let {
            if (it.currentMediaItem?.mediaId == projectId) ProjectPosition(it.currentPosition.coerceAtLeast(0), it.isPlaying) else null
        }
        fun clearRecordings(ids: Collection<String>) {
            active?.let { service ->
                val item = service.player.currentMediaItem
                val projectSources = item?.mediaMetadata?.extras?.getString(PROJECT_PREVIEW_ID)?.let(MultitrackAudio::sourceIds).orEmpty()
                if (item?.mediaId in ids || projectSources.any { it in ids }) {
                    service.pause(); service.player.stop(); service.player.clearMediaItems()
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private var settingsLoaded = false
    private var stopWhenLocked = false
    private var allowPlaybackWhileRecording = false
    private var pendingPlay = false
    private var screenOff = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOff = intent.action == Intent.ACTION_SCREEN_OFF
            if (stopWhenLocked && locked()) pause()
        }
    }
    private fun locked() = screenOff || !getSystemService(PowerManager::class.java).isInteractive ||
        getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun playbackAllowed() = (!RecordingService.state.value.active || allowPlaybackWhileRecording) && (!stopWhenLocked || !locked())
    private fun requestPlay() {
        if (!settingsLoaded) { pendingPlay = true; return }
        pendingPlay = false
        if (playbackAllowed()) player.play()
    }
    private fun pause() { pendingPlay = false; player.pause() }

    override fun onCreate() {
        super.onCreate()
        active = this
        screenOff = !getSystemService(PowerManager::class.java).isInteractive
        player = ExoPlayer.Builder(this).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (playWhenReady && !playbackAllowed()) pause()
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    EditPreviewCache.playing(mediaItem?.mediaMetadata?.extras?.getString(PREVIEW_ID))
                    MultitrackAudio.playing(mediaItem?.mediaMetadata?.extras?.getString(PROJECT_PREVIEW_ID))
                }
            })
        }
        val guarded = object : ForwardingPlayer(player) {
            override fun play() = requestPlay()
            override fun setPlayWhenReady(playWhenReady: Boolean) { if (playWhenReady) requestPlay() else this@PlaybackService.pause() }
            override fun pause() = this@PlaybackService.pause()
            override fun stop() { pendingPlay = false; super.stop() }
            override fun clearMediaItems() { pendingPlay = false; super.clearMediaItems() }
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, guarded).setSessionActivity(open).setCallback(object : MediaSession.Callback {
            override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.ConnectionResult> {
                if (!ownController(controller) && !controller.isTrusted) return Futures.immediateFuture(MediaSession.ConnectionResult.reject())
                val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                if (!ownController(controller)) {
                    commands.remove(Player.COMMAND_SET_MEDIA_ITEM).remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                }
                return Futures.immediateFuture(MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                    .setAvailablePlayerCommands(commands.build()).build())
            }
            override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                if (!ownController(controller)) return Futures.immediateFailedFuture(SecurityException("Only Stillwave can choose audio"))
                val app = application as StillwaveApp
                val root = app.recordingsDir.canonicalFile
                val valid = mediaItems.all { item ->
                    val uri = item.localConfiguration?.uri
                    if (item.mediaMetadata.extras?.getString(PROJECT_PREVIEW_ID) != null) MultitrackAudio.accepts(app, item)
                    else if (item.mediaMetadata.extras?.getString(PREVIEW_ID) != null) EditPreviewCache.accepts(app, item)
                    else uri?.scheme == "file" && runCatching {
                        val file = File(checkNotNull(uri.path)).canonicalFile
                        file.parentFile == root && file.extension in setOf("wav", "m4a") && file.nameWithoutExtension == item.mediaId && file.isFile
                    }.getOrDefault(false)
                }
                return if (valid) Futures.immediateFuture(mediaItems)
                    else Futures.immediateFailedFuture(SecurityException("Audio must belong to the recording library or a verified edit preview"))
            }
        }).setMediaButtonPreferences(listOf(
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10).setDisplayName("Rewind 10 seconds").setPlayerCommand(Player.COMMAND_SEEK_BACK).build(),
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10).setDisplayName("Forward 10 seconds").setPlayerCommand(Player.COMMAND_SEEK_FORWARD).build()
        )).build()
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        val app = application as StillwaveApp
        scope.launch {
            app.settings.flow.map { it.stopPlaybackWhenLocked to it.allowPlaybackWhileRecording }.distinctUntilChanged().collect { (stop, simultaneous) ->
                stopWhenLocked = stop; allowPlaybackWhileRecording = simultaneous; settingsLoaded = true
                if (!playbackAllowed()) pause()
                else if (pendingPlay) requestPlay()
            }
        }
        scope.launch {
            RecordingService.state.map { it.active }.distinctUntilChanged().collect {
                if (!playbackAllowed()) pause()
            }
        }
        scope.launch {
            app.ready.await()
            app.database.recordings().observeAll().collect { recordings ->
                val id = player.currentMediaItem?.mediaId
                val projectPreview = player.currentMediaItem?.mediaMetadata?.extras?.getString(PROJECT_PREVIEW_ID)
                val projectSources = MultitrackAudio.sourceIds(projectPreview)
                val missing = if (projectPreview != null) projectSources.isEmpty() || projectSources.any { sourceId -> recordings.none { it.id == sourceId } }
                    else id != null && recordings.none { it.id == id }
                if (missing) {
                    pause(); player.stop(); player.clearMediaItems()
                }
            }
        }
    }

    private fun ownController(controller: MediaSession.ControllerInfo) =
        controller.uid == applicationInfo.uid && controller.packageName == packageName

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session.takeIf { ownController(controllerInfo) || controllerInfo.isTrusted }

    override fun onDestroy() {
        if (active === this) { active = null; EditPreviewCache.pending(null); EditPreviewCache.playing(null); MultitrackAudio.pending(null); MultitrackAudio.playing(null) }
        unregisterReceiver(screenReceiver)
        scope.cancel()
        session?.release(); session = null
        player.release()
        super.onDestroy()
    }
}
