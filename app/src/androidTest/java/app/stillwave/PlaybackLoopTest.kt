package app.stillwave

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.core.app.ActivityScenario
import app.stillwave.audio.PlaybackService
import app.stillwave.audio.WavFile
import app.stillwave.data.CutWindow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import kotlin.math.abs

/** Service-backed tests use only UUID fixtures and observe real playback position wraps. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackLoopTest {
    private data class Clock(val playing: Boolean, val position: Long, val duration: Long, val repeat: Int)

    private fun clock(controller: MediaController): Clock = playbackMain {
        assertNull("Loop playback failed", controller.playerError)
        Clock(controller.isPlaying, controller.currentPosition, controller.duration, controller.repeatMode)
    }

    private fun ready(controller: MediaController, window: CutWindow?, looping: Boolean) {
        playbackAwait(30_000) {
            playbackMain {
                assertNull(controller.playerError)
                controller.isPlaying && controller.duration > 0 &&
                    controller.currentMediaItem?.mediaMetadata?.extras?.getString(PlaybackService.WINDOW_ID) == window?.id &&
                    controller.repeatMode == if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            }
        }
    }

    private fun wraps(controller: MediaController, count: Int = 2) {
        var previous = clock(controller)
        assertTrue(previous.playing)
        assertTrue(previous.duration > 0)
        var wraps = 0
        playbackAwait(previous.duration * (count + 1) + 8000) {
            val current = clock(controller)
            assertEquals(Player.REPEAT_MODE_ONE, current.repeat)
            // No seeks occur in this helper. A high-to-low transition is an actual repeat.
            if (previous.playing && current.playing && previous.position > current.duration * 3 / 5 &&
                current.position < current.duration * 2 / 5) wraps++
            previous = current
            wraps >= count
        }
    }

    @Test fun clippedWindowRepeatsAndPauseResumeThenLoopOffKeepTheCurrentPass() = withPlaybackFixture(false) { _, _, vm, recording, controller ->
        val window = CutWindow(recordingId = recording.id, startMs = 1000, endMs = 2800)
        playbackMain { vm.toggleLoop(recording, window) }
        ready(controller, window, true)
        assertEquals(1800L, clock(controller).duration)
        wraps(controller)
        playbackMain { vm.play(recording, window) }
        playbackAwait { !clock(controller).playing && vm.playback.value.looping }
        val pausedAt = clock(controller).position
        Thread.sleep(200)
        assertTrue(abs(clock(controller).position - pausedAt) < 100)
        playbackMain { vm.play(recording, window) }
        ready(controller, window, true)
        playbackMain { controller.seekTo(400) }
        playbackAwait { clock(controller).position in 400..1000 }
        val before = clock(controller).position
        playbackMain { vm.toggleLoop(recording, window) }
        playbackAwait { clock(controller).let { it.repeat == Player.REPEAT_MODE_OFF && it.playing } }
        assertTrue("Turning off loop must not restart the window", clock(controller).position >= before - 100)
        playbackAwait { playbackMain { controller.playbackState == Player.STATE_ENDED } }
        playbackAwait { !vm.playback.value.looping && !vm.playback.value.playing }
    }

    @Test fun reversedRetimedPreviewActuallyRepeatsAndMapsBackToOriginalCoordinates() = withPlaybackFixture(false) { _, _, vm, recording, controller ->
        val window = CutWindow(recordingId = recording.id, startMs = 1000, endMs = 4600, reverse = true, speed = 2.0, preservePitch = true)
        playbackMain { vm.play(recording, window, loop = true) }
        ready(controller, window, true)
        val extras = playbackMain { controller.currentMediaItem?.mediaMetadata?.extras }
        assertNotNull(extras?.getString(PlaybackService.PREVIEW_ID))
        assertEquals(true, extras?.getBoolean(PlaybackService.PREVIEW_REVERSE))
        assertEquals(2.0, extras?.getDouble(PlaybackService.PREVIEW_SPEED) ?: 0.0, 0.0)
        assertEquals(1800L, clock(controller).duration)
        wraps(controller)
        playbackMain { controller.seekTo(400); controller.pause() }
        playbackAwait { !clock(controller).playing && !vm.playback.value.playing }
        val nativePosition = clock(controller).position
        playbackAwait { abs(vm.playback.value.positionMs - (window.endMs - nativePosition * 2)) < 200 }
        assertTrue(vm.playback.value.looping)
        // Editing the same window keeps repeat enabled while replacing the rendered variant.
        val changed = window.copy(endMs = 3400, reverse = false)
        playbackMain { vm.play(recording, changed) }
        ready(controller, changed, true)
        playbackAwait { clock(controller).duration == 1200L }
        wraps(controller)
    }

    @Test fun repeatResetsAcrossWindowsAndRecordingsButSeekingSameOriginalKeepsIt() = withPlaybackFixture(false) { app, _, vm, recording, controller ->
        val first = CutWindow(recordingId = recording.id, startMs = 1000, endMs = 2800)
        val second = first.copy(id = UUID.randomUUID().toString(), startMs = 3000, endMs = 7000)
        val targetId = UUID.randomUUID().toString()
        val target = recording.copy(id = targetId, title = "Independent loop target", durationMs = 8000, fileName = "$targetId.wav")
        WavFile.header(app.repository.file(target), target.sampleRate, target.channels, 256_000)
        runBlocking { app.repository.dao.insert(target) }
        try {
            playbackMain { vm.play(recording, first, loop = true) }
            ready(controller, first, true)
            playbackMain { vm.play(recording, second) }
            ready(controller, second, false)
            playbackMain { vm.toggleLoop(recording, second) }
            ready(controller, second, true)
            playbackMain { vm.seek(recording, 7000) }
            ready(controller, null, false)
            playbackMain { vm.play(recording, loop = true) }
            ready(controller, null, true)
            playbackMain { vm.seek(recording, 1000) }
            ready(controller, null, true)
            playbackMain { vm.play(target) }
            ready(controller, null, false)
            playbackAwait { playbackMain { controller.currentMediaItem?.mediaId == target.id } && vm.playback.value.let { it.recordingId == target.id && !it.looping } }
            assertFalse(vm.playback.value.looping)
        } finally {
            playbackMain { vm.pausePlayback(); PlaybackService.clearRecordings(listOf(target.id)) }
            runBlocking { app.repository.delete(listOf(target.id)) }
        }
    }

    @Test fun loopSurvivesBackgroundNativeControlsAndFreshActivityConnection() = withPlaybackFixture(false) { app, activity, vm, recording, controller ->
        val window = CutWindow(recordingId = recording.id, startMs = 5000, endMs = 6500, reverse = true)
        playbackMain { vm.toggleLoop(recording, window) }
        ready(controller, window, true)
        activity.moveToState(Lifecycle.State.CREATED)
        wraps(controller, 1)
        val native = nativePlaybackController(app)
        native.transportControls.pause()
        playbackAwait { !clock(controller).playing }
        assertEquals(Player.REPEAT_MODE_ONE, clock(controller).repeat)
        native.transportControls.play()
        ready(controller, window, true)
        activity.close()
        wraps(controller, 1)
        ActivityScenario.launch(MainActivity::class.java).use { reopened ->
            lateinit var replacement: AppViewModel
            reopened.onActivity { replacement = ViewModelProvider(it)[AppViewModel::class.java] }
            playbackAwait { replacement.playback.value.let { it.recordingId == recording.id && it.windowId == window.id && it.playing && it.looping } }
            wraps(controller, 1)
            playbackMain { replacement.toggleLoop(recording, window) }
            ready(controller, window, false)
        }
    }

    @Test fun turningOffLoopDuringPreviewPreparationFinishesOnceWithoutLateRestart() = withPlaybackFixture(false) { app, _, vm, recording, controller ->
        val window = CutWindow(recordingId = recording.id, startMs = 1000, endMs = 3400, reverse = true, speed = 2.0)
        // Hold the source lock to make the pending render deterministic instead of timing a CPU race.
        runBlocking {
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = launch(Dispatchers.IO) { app.repository.withRecording(recording.id) { held.complete(Unit); release.await() } }
            held.await()
            try {
                playbackMain { vm.toggleLoop(recording, window) }
                playbackAwait { vm.previewPreparing.value }
                playbackMain { vm.toggleLoop(recording, window) }
            } finally { release.complete(Unit); holder.join() }
        }
        ready(controller, window, false)
        playbackAwait { !vm.previewPreparing.value && !vm.playback.value.looping }
        playbackAwait { playbackMain { controller.playbackState == Player.STATE_ENDED } }
        Thread.sleep(250)
        assertFalse(clock(controller).playing)
        assertEquals(Player.REPEAT_MODE_OFF, clock(controller).repeat)
    }
}
