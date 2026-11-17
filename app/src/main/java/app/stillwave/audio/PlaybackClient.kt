package app.stillwave.audio

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.stillwave.Playback
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** An activity/view-model connection, never the owner of the audio player. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackClient(private val context: Context, private val scope: CoroutineScope,
    private val changed: (Playback) -> Unit, private val error: (String) -> Unit) {
    private var future: ListenableFuture<MediaController>? = null
    private var connected: MediaController? = null
    private var closed = false
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
        override fun onPlayerError(failure: PlaybackException) = error("Could not play audio: ${failure.message}")
    }

    init {
        scope.launch { runCatching { player() }.onFailure { if (it !is CancellationException) error("Could not connect playback: ${it.message}") } }
        scope.launch {
            while (isActive) {
                connected?.let { if (it.playbackState != Player.STATE_IDLE) publish(it) }
                delay(100)
            }
        }
    }

    suspend fun player(): MediaController {
        check(!closed) { "Playback connection is closed" }
        connected?.takeIf { it.isConnected }?.let { return it }
        val pending = future ?: MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if (connected === controller) {
                        connected = null; future = null
                        changed(Playback())
                    }
                }
            }).buildAsync().also { future = it }
        val controller = try {
            suspendCancellableCoroutine<MediaController> { continuation ->
                pending.addListener({
                    if (continuation.isActive) runCatching { pending.get() }.fold(continuation::resume, continuation::resumeWithException)
                }, ContextCompat.getMainExecutor(context))
            }.also { check(it.isConnected) { "Playback disconnected while connecting" } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // A transient binding failure must not poison every subsequent Play request.
            if (future === pending) { future = null; MediaController.releaseFuture(pending) }
            throw failure
        }
        check(!closed) { "Playback connection is closed" }
        if (connected !== controller) {
            connected = controller; controller.addListener(listener); publish(controller)
        }
        return controller
    }

    private fun publish(player: Player) {
        val item = player.currentMediaItem
        val extras = item?.mediaMetadata?.extras
        val start = extras?.getLong(PlaybackService.CLIP_START_MS) ?: 0
        val elapsed = player.currentPosition.coerceAtLeast(0)
        val position = if (extras?.getString(PlaybackService.PREVIEW_ID) != null) {
            val end = extras.getLong(PlaybackService.CLIP_END_MS)
            val distance = (elapsed * extras.getDouble(PlaybackService.PREVIEW_SPEED, 1.0)).toLong()
            (if (extras.getBoolean(PlaybackService.PREVIEW_REVERSE)) end - distance else start + distance).coerceIn(start, end)
        } else start + elapsed
        changed(Playback(item?.mediaId, player.isPlaying,
            position,
            extras?.getString(PlaybackService.WINDOW_ID),
            item != null && player.repeatMode == Player.REPEAT_MODE_ONE))
    }

    fun pause() { connected?.pause() }
    fun clear(ids: Collection<String>) {
        connected?.let { if (it.currentMediaItem?.mediaId in ids) { it.stop(); it.clearMediaItems(); publish(it) } }
    }
    fun close() {
        closed = true
        connected?.removeListener(listener)
        future?.let(MediaController::releaseFuture)
        connected = null; future = null
    }
}
