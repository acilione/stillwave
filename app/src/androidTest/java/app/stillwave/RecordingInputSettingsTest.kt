package app.stillwave

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import app.stillwave.audio.MicrophoneInputs
import app.stillwave.audio.RecordingService
import app.stillwave.data.AppSettings
import app.stillwave.data.Quality
import app.stillwave.data.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue

class RecordingInputSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as StillwaveApp
    private lateinit var before: AppSettings

    @Before fun prepare() = runBlocking {
        app.ready.await()
        before = app.settings.flow.first()
        app.settings.set(Settings.ALLOW_PLAYBACK_WHILE_RECORDING, false)
        app.settings.selectMicrophone("", "")
    }

    @After fun restore() = runBlocking {
        app.settings.set(Settings.ALLOW_PLAYBACK_WHILE_RECORDING, before.allowPlaybackWhileRecording)
        app.settings.selectMicrophone(before.selectedMicrophone, before.selectedMicrophoneName)
    }

    @Test fun simultaneousPlaybackIsOffByDefaultAndTogglePersistsThroughRecreation() {
        assertFalse(AppSettings().allowPlaybackWhileRecording)
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("setting-Playback while recording").performScrollTo().assertIsOff().performClick()
        compose.waitUntil(5000) { runBlocking { app.settings.flow.first().allowPlaybackWhileRecording } }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("setting-Playback while recording").performScrollTo().assertIsOn().performClick()
        compose.waitUntil(5000) { runBlocking { !app.settings.flow.first().allowPlaybackWhileRecording } }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("setting-Playback while recording").performScrollTo().assertIsOff()
    }

    @Test fun microphoneSelectionPersistsAndAutomaticClearsExplicitSelection() {
        val inputs = MicrophoneInputs.options(app)
        assumeTrue("Requires an Android input route", inputs.isNotEmpty())
        val input = inputs.first()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("microphone-auto").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("microphone-${input.key}").performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { app.settings.flow.first().selectedMicrophone == input.key } }
        assertEquals(input.label, runBlocking { app.settings.flow.first().selectedMicrophoneName })
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("microphone-${input.key}").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("microphone-auto").performScrollTo().assertIsNotSelected().performClick()
        compose.waitUntil(5000) { runBlocking { app.settings.flow.first().selectedMicrophone.isEmpty() } }
        assertEquals("", runBlocking { app.settings.flow.first().selectedMicrophoneName })
        assertNull(MicrophoneInputs.resolve(app, ""))
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("microphone-auto").performScrollTo().assertIsSelected()
    }

    @Test fun unavailableSelectedMicrophoneRemainsVisibleUntilUserChoosesAutomatic() {
        runBlocking { app.settings.selectMicrophone("test-disconnected-input", "Test USB microphone") }
        compose.onNodeWithContentDescription("Settings").performClick()
        val warning = "Test USB microphone is disconnected. Reconnect it or select another input before recording."
        compose.onNodeWithText(warning).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("microphone-auto").performScrollTo().assertIsNotSelected()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(warning).performScrollTo().assertIsDisplayed()
        assertTrue(runCatching { MicrophoneInputs.resolve(app, "test-disconnected-input") }.isFailure)
        compose.onNodeWithTag("microphone-auto").performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { app.settings.flow.first().selectedMicrophone.isEmpty() } }
        compose.onNodeWithText(warning).assertDoesNotExist()
    }
}

/** Emulator-only service checks: microphone fixtures are discarded, existing audio is never modified. */
class RecordingPlaybackOptOutTest {
    @Before fun permissions() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk", ignoreCase = true) ||
            Build.HARDWARE in setOf("ranchu", "goldfish", "cutf_cvm"))
        check(!RecordingService.state.value.active) { "Do not interrupt an existing capture" }
        playbackShell("input keyevent KEYCODE_WAKEUP")
        playbackShell("wm dismiss-keyguard")
        playbackShell("pm grant app.stillwave android.permission.RECORD_AUDIO")
    }

    private fun captureSettings(app: StillwaveApp, block: () -> Unit) {
        val before = runBlocking { app.settings.flow.first() }
        try {
            runBlocking {
                app.settings.selectMicrophone("", "")
                app.settings.set(Settings.ALLOW_PLAYBACK_WHILE_RECORDING, false)
            }
            block()
        } finally {
            if (RecordingService.state.value.active) {
                RecordingService.command(app, RecordingService.DISCARD)
                playbackAwait { !RecordingService.state.value.active }
            }
            runBlocking {
                app.settings.selectMicrophone(before.selectedMicrophone, before.selectedMicrophoneName)
                app.settings.set(Settings.ALLOW_PLAYBACK_WHILE_RECORDING, before.allowPlaybackWhileRecording)
            }
        }
    }

    @Test fun disabledOptionStopsExistingPlaybackAndBlocksBothAppAndSessionPlayDuringCapture() {
        withPlaybackFixture(false) { app, activity, vm, source, controller ->
            captureSettings(app) {
                playbackAwait { !vm.settings.value.allowPlaybackWhileRecording }
                playbackMain { vm.play(source) }
                playbackAwait { playbackMain { controller.isPlaying } }
                activity.onActivity { RecordingService.command(it, RecordingService.START, Quality.VOICE) }
                playbackAwait { RecordingService.state.value.elapsedMs >= 400 }
                playbackAwait { playbackMain { !controller.isPlaying } }
                var captureAt = RecordingService.state.value.elapsedMs
                playbackMain { controller.play() }
                playbackAwait { RecordingService.state.value.elapsedMs >= captureAt + 400 }
                assertFalse(playbackMain { controller.isPlaying })
                RecordingService.command(app, RecordingService.DISCARD)
                playbackAwait { !RecordingService.state.value.active }

                // Reverse order: capture starts while playback is already paused.
                activity.onActivity { RecordingService.command(it, RecordingService.START, Quality.VOICE) }
                playbackAwait { RecordingService.state.value.elapsedMs >= 400 }
                captureAt = RecordingService.state.value.elapsedMs
                playbackMain { vm.play(source) }
                playbackAwait { RecordingService.state.value.elapsedMs >= captureAt + 400 }
                assertFalse(playbackMain { controller.isPlaying })
                captureAt = RecordingService.state.value.elapsedMs
                playbackMain { controller.play() }
                playbackAwait { RecordingService.state.value.elapsedMs >= captureAt + 400 }
                assertFalse(playbackMain { controller.isPlaying })
                assertTrue(RecordingService.state.value.active)
            }
        }
    }

    @Test fun disablingSimultaneousPlaybackPausesAudioWithoutInterruptingCapture() {
        withPlaybackFixture(false) { app, activity, vm, source, controller ->
            captureSettings(app) {
                runBlocking { app.settings.set(Settings.ALLOW_PLAYBACK_WHILE_RECORDING, true) }
                playbackAwait { vm.settings.value.allowPlaybackWhileRecording }
                playbackMain { vm.play(source) }
                playbackAwait { playbackMain { controller.isPlaying } }
                activity.onActivity { RecordingService.command(it, RecordingService.START, Quality.VOICE) }
                playbackAwait { RecordingService.state.value.elapsedMs >= 500 }
                assertTrue(playbackMain { controller.isPlaying })
                val session = RecordingService.state.value.sessionId
                val captureAt = RecordingService.state.value.elapsedMs
                runBlocking { app.settings.set(Settings.ALLOW_PLAYBACK_WHILE_RECORDING, false) }
                playbackAwait { playbackMain { !controller.isPlaying } }
                playbackAwait { RecordingService.state.value.elapsedMs >= captureAt + 400 }
                assertEquals(session, RecordingService.state.value.sessionId)
                assertFalse(RecordingService.state.value.paused)
                runBlocking { app.settings.set(Settings.ALLOW_PLAYBACK_WHILE_RECORDING, true) }
                playbackAwait { vm.settings.value.allowPlaybackWhileRecording }
                assertFalse(playbackMain { controller.isPlaying })
                playbackMain { vm.play(source) }
                playbackAwait { playbackMain { controller.isPlaying } }
                assertEquals(session, RecordingService.state.value.sessionId)
            }
        }
    }
}
