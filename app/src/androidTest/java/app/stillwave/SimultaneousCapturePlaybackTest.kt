package app.stillwave

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import app.stillwave.audio.RecordingService
import app.stillwave.audio.WavFile
import app.stillwave.data.Quality
import app.stillwave.data.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Emulator-only: grants microphone permission and creates/removes only its own capture fixtures. */
class SimultaneousCapturePlaybackTest {
    @Before fun permission() {
        assumeTrue("This test grants permissions only on an emulator",
            Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk", ignoreCase = true) ||
                Build.HARDWARE in setOf("ranchu", "goldfish", "cutf_cvm"))
        val app = ApplicationProvider.getApplicationContext<StillwaveApp>()
        check(!RecordingService.state.value.active) { "Do not interrupt an existing capture" }
        playbackShell("input keyevent KEYCODE_WAKEUP")
        playbackShell("wm dismiss-keyguard")
        playbackShell("pm grant app.stillwave android.permission.RECORD_AUDIO")
        check(ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }

    private fun hash(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun captureFixture(app: StillwaveApp, block: () -> Unit) {
        val previousPlayback = runBlocking { app.settings.flow.first().allowPlaybackWhileRecording }
        val beforeIds = runBlocking { app.repository.dao.observeAll().first().map { it.id }.toSet() }
        var session: String? = null
        try {
            runBlocking { app.settings.set(Settings.ALLOW_PLAYBACK_WHILE_RECORDING, true) }
            block()
        } finally {
            // This helper is entered only while idle and never touches another library item.
            if (RecordingService.state.value.active) {
                session = RecordingService.state.value.sessionId
                RecordingService.command(app, RecordingService.DISCARD)
                playbackAwait { !RecordingService.state.value.active }
            }
            val saved = RecordingService.lastSavedRecordingId.value
            val cleanup = listOfNotNull(session, saved).filter { it !in beforeIds }
            if (cleanup.isNotEmpty()) runBlocking { app.repository.delete(cleanup) }
            runBlocking { app.settings.set(Settings.ALLOW_PLAYBACK_WHILE_RECORDING, previousPlayback) }
        }
    }

    @Test fun playbackContinuesThroughMicrophoneStartPauseResumeAndSave() {
        withPlaybackFixture(false) { app, activity, vm, source, controller ->
            val original = hash(app.repository.file(source))
            captureFixture(app) {
                playbackMain { vm.play(source) }
                playbackAwait { playbackMain { controller.isPlaying } }
                val beforeCapture = playbackMain { controller.currentPosition }
                activity.onActivity { RecordingService.command(it, RecordingService.START, Quality.VOICE) }
                playbackAwait { RecordingService.state.value.elapsedMs >= 400 }
                val capture = RecordingService.state.value.sessionId
                assertTrue(playbackMain { controller.isPlaying })
                assertEquals(source.id, playbackMain { controller.currentMediaItem?.mediaId })
                playbackAwait { playbackMain { controller.currentPosition } >= beforeCapture + 300 }

                RecordingService.command(app, RecordingService.PAUSE)
                playbackAwait { RecordingService.state.value.paused }
                val pausedAt = RecordingService.state.value.elapsedMs
                val playbackAtPause = playbackMain { controller.currentPosition }
                playbackAwait { playbackMain { controller.currentPosition } >= playbackAtPause + 350 }
                assertEquals(pausedAt, RecordingService.state.value.elapsedMs)
                assertTrue(playbackMain { controller.isPlaying })

                RecordingService.command(app, RecordingService.RESUME)
                playbackAwait { RecordingService.state.value.elapsedMs >= pausedAt + 400 }
                assertTrue(playbackMain { controller.isPlaying })
                RecordingService.command(app, RecordingService.SAVE)
                playbackAwait { !RecordingService.state.value.active }
                val saved = runBlocking { checkNotNull(app.repository.dao.get(capture)) }
                assertNotEquals(source.id, saved.id)
                assertTrue(saved.durationMs >= 800)
                assertEquals(saved.durationMs, WavFile.info(app.repository.file(saved)).durationMs)
                assertTrue(playbackMain { controller.isPlaying })
                assertEquals(source.id, playbackMain { controller.currentMediaItem?.mediaId })
                assertArrayEquals(original, hash(app.repository.file(source)))
            }
        }
    }

    @Test fun playbackCanStartAndSeekDuringCaptureAndDiscardLeavesSourceUntouched() {
        withPlaybackFixture(false) { app, activity, vm, source, controller ->
            val original = hash(app.repository.file(source))
            captureFixture(app) {
                activity.onActivity { RecordingService.command(it, RecordingService.START, Quality.VOICE) }
                playbackAwait { RecordingService.state.value.elapsedMs >= 400 }
                val capture = RecordingService.state.value.sessionId
                val capturedBeforePlayback = RecordingService.state.value.elapsedMs
                playbackMain { vm.play(source) }
                playbackAwait { playbackMain { controller.isPlaying } }
                playbackMain { vm.seek(source, 5000) }
                playbackAwait { playbackMain { controller.currentPosition } >= 5000 }
                playbackAwait { RecordingService.state.value.elapsedMs >= capturedBeforePlayback + 400 }
                assertTrue(RecordingService.state.value.active)
                assertFalse(RecordingService.state.value.paused)
                assertEquals(capture, RecordingService.state.value.sessionId)

                RecordingService.command(app, RecordingService.DISCARD)
                playbackAwait { !RecordingService.state.value.active }
                assertNull(runBlocking { app.repository.dao.get(capture) })
                assertFalse(File(app.recordingsDir, "$capture.wav").exists())
                assertFalse(File(app.recordingsDir, "$capture.pending").exists())
                assertTrue(playbackMain { controller.isPlaying })
                val afterDiscard = playbackMain { controller.currentPosition }
                playbackAwait { playbackMain { controller.currentPosition } >= afterDiscard + 300 }
                assertArrayEquals(original, hash(app.repository.file(source)))
            }
        }
    }
}
