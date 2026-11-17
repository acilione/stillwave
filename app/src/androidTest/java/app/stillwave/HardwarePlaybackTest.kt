package app.stillwave

import android.os.Bundle
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import app.stillwave.data.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Safe on the user's phone: no shell input/grants, only one self-owned audio fixture. */
class HardwarePlaybackTest {
    @Test fun backgroundMediaControlsAndOptionalManualLock() {
        withPlaybackFixture(false) { app, activity, vm, recording, controller ->
            playbackMain { vm.play(recording) }
            playbackAwait { playbackMain { controller.isPlaying } }
            val native = nativePlaybackController(app)
            val before = playbackMain { controller.currentPosition }
            activity.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(600)
            assertTrue(playbackMain { controller.isPlaying })
            assertTrue(playbackMain { controller.currentPosition } > before + 300)
            native.transportControls.pause()
            playbackAwait { !playbackMain { controller.isPlaying } }
            native.transportControls.seekTo(5000)
            playbackAwait { playbackMain { controller.currentPosition >= 5000 } }
            native.transportControls.play()
            playbackAwait { playbackMain { controller.isPlaying } }
            var report = "Background playback and native media pause/resume/seek passed.\n"
            if (InstrumentationRegistry.getArguments().getString("manualLock") == "true") {
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\nLOCK_PHONE_NOW: press the phone's power button.\n") })
                playbackAwait(120_000) { !app.getSystemService(PowerManager::class.java).isInteractive }
                val lockedAt = playbackMain { controller.currentPosition }
                Thread.sleep(750)
                assertTrue(playbackMain { controller.isPlaying })
                assertTrue(playbackMain { controller.currentPosition } > lockedAt + 400)
                runBlocking { app.settings.set(Settings.STOP_PLAYBACK_WHEN_LOCKED, true) }
                playbackAwait { !playbackMain { controller.playWhenReady } }
                native.transportControls.play()
                Thread.sleep(300)
                assertFalse(playbackMain { controller.playWhenReady })
                report += "Physical screen lock kept playback running by default; opt-in stopped it and blocked lock-screen resume.\n"
            }
            File(app.cacheDir, "playback-validation.txt").writeText(report)
        }
    }
}
