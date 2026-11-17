package app.stillwave

import android.media.session.PlaybackState
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import app.stillwave.data.CutWindow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Emulator-only tests: sleep/wake commands are intentionally excluded from the physical-phone test. */
class PlaybackServiceTest {
    private fun wake() { playbackShell("input keyevent KEYCODE_WAKEUP"); playbackShell("wm dismiss-keyguard") }

    @Test fun backgroundAndLockScreenPlaybackKeepRunningWithNativeControls() {
        wake()
        try { withPlaybackFixture(false) { app, activity, vm, recording, controller ->
            playbackMain { vm.play(recording) }
            playbackAwait { playbackMain { controller.isPlaying } }
            val native = nativePlaybackController(app)
            assertEquals(recording.title, checkNotNull(native.metadata).getString(android.media.MediaMetadata.METADATA_KEY_TITLE))
            val before = playbackMain { controller.currentPosition }
            playbackMain { controller.release() }
            activity.close() // Remove every app Media3 binding; only the native system control remains.
            playbackShell("input keyevent KEYCODE_SLEEP")
            playbackAwait { !app.getSystemService(PowerManager::class.java).isInteractive }
            Thread.sleep(650)
            assertEquals(PlaybackState.STATE_PLAYING, native.playbackState?.state)
            native.transportControls.pause()
            playbackAwait { native.playbackState?.state == PlaybackState.STATE_PAUSED }
            assertTrue(checkNotNull(native.playbackState).position > before + 300)
            native.transportControls.seekTo(5000)
            playbackAwait { (native.playbackState?.position ?: 0) >= 5000 }
            native.transportControls.play()
            playbackAwait { native.playbackState?.state == PlaybackState.STATE_PLAYING }
        } } finally { wake() }
    }

    @Test fun optInPausesWhenScreenTurnsOffAndRequiresExplicitResume() {
        wake()
        try { withPlaybackFixture(true) { app, activity, vm, recording, controller ->
            playbackMain { vm.play(recording) }
            playbackAwait { playbackMain { controller.isPlaying } }
            val native = nativePlaybackController(app)
            activity.moveToState(Lifecycle.State.CREATED)
            // Merely leaving the app must not be mistaken for locking the screen.
            assertTrue(playbackMain { controller.isPlaying })
            playbackShell("input keyevent KEYCODE_SLEEP")
            playbackAwait { !app.getSystemService(PowerManager::class.java).isInteractive && !playbackMain { controller.playWhenReady } }
            val pausedAt = playbackMain { controller.currentPosition }
            native.transportControls.play()
            Thread.sleep(300)
            assertFalse(playbackMain { controller.playWhenReady })
            assertTrue(kotlin.math.abs(playbackMain { controller.currentPosition } - pausedAt) < 100)
            wake()
            Thread.sleep(200)
            assertFalse(playbackMain { controller.playWhenReady })
            activity.moveToState(Lifecycle.State.RESUMED)
            playbackMain { vm.play(recording) }
            playbackAwait { playbackMain { controller.isPlaying } }
        } } finally { wake() }
    }

    @Test fun clippedPreviewReconnectsWithOriginalCoordinatesAndDeletionStopsPlayback() {
        wake()
        withPlaybackFixture(false) { app, activity, vm, recording, controller ->
            val window = CutWindow(recordingId = recording.id, startMs = 6000, endMs = 30_000, title = "Preview segment")
            playbackMain { vm.play(recording, window) }
            playbackAwait { vm.playback.value.playing && vm.playback.value.positionMs >= 6000 }
            assertEquals(window.id, vm.playback.value.windowId)
            assertEquals("Preview segment", playbackMain { controller.mediaMetadata.title.toString() })
            activity.close()
            ActivityScenario.launch(MainActivity::class.java).use { reopened ->
                lateinit var replacement: AppViewModel
                reopened.onActivity { replacement = ViewModelProvider(it)[AppViewModel::class.java] }
                playbackAwait { replacement.playback.value.playing && replacement.playback.value.windowId == window.id }
                assertTrue(replacement.playback.value.positionMs >= 6000)
                playbackMain { replacement.seek(recording, 2000) }
                playbackAwait { replacement.playback.value.windowId == null && replacement.playback.value.positionMs >= 2000 }
                playbackMain { replacement.deleteRecordings(listOf(recording.id)) }
                playbackAwait { runBlocking { app.repository.dao.get(recording.id) == null } && playbackMain { controller.currentMediaItem == null } }
                assertFalse(playbackMain { controller.isPlaying })
            }
        }
    }
}
