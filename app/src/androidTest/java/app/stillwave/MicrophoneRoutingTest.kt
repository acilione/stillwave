package app.stillwave

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.stillwave.audio.CaptureNegotiator
import app.stillwave.audio.MicrophoneInputs
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

/** Phone-safe with -e allowPhysical true: no shell commands on phones, no clearing existing data. */
class MicrophoneRoutingTest {
    private val app get() = ApplicationProvider.getApplicationContext<StillwaveApp>()

    @Before fun prepare() {
        val emulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk", ignoreCase = true) ||
            Build.HARDWARE in setOf("ranchu", "goldfish", "cutf_cvm")
        assumeTrue("Physical microphone tests require explicit allowPhysical=true", emulator ||
            InstrumentationRegistry.getArguments().getString("allowPhysical") == "true")
        check(!RecordingService.state.value.active) { "Do not interrupt an existing recording" }
        if (emulator) {
            playbackShell("input keyevent KEYCODE_WAKEUP")
            playbackShell("wm dismiss-keyguard")
            playbackShell("pm grant app.stillwave android.permission.RECORD_AUDIO")
        }
        check(ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Grant microphone access in the app before running the physical test"
        }
        runBlocking { app.ready.await() }
    }

    private fun builtIn(): AudioDeviceInfo = checkNotNull(app.getSystemService(AudioManager::class.java)
        .getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }) {
        "No built-in microphone is available"
    }

    @Test fun fixedAndBestFormatsUseTheExplicitBuiltInRoute() {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            activity.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val input = builtIn()
            val option = MicrophoneInputs.options(app).single { it.deviceId == input.id }
            assertEquals(input.id, MicrophoneInputs.resolve(app, option.key)?.id)
            assertNull(MicrophoneInputs.resolve(app, ""))
            assertThrows(IllegalStateException::class.java) { MicrophoneInputs.resolve(app, "missing-test-input") }
            val mode = app.getSystemService(AudioManager::class.java).mode
            for (quality in listOf(Quality.VOICE, Quality.BEST)) {
                val capture = runBlocking { CaptureNegotiator(app).open(quality, input) }
                try {
                    assertEquals(input.id, capture.device?.id)
                    assertEquals(input.id, capture.record.routedDevice?.id)
                    assertEquals(input.id, capture.record.preferredDevice?.id)
                    assertTrue(capture.firstBytes.isNotEmpty())
                    assertTrue(capture.details.contains("Selected microphone route verified"))
                    assertEquals(mode, app.getSystemService(AudioManager::class.java).mode)
                } finally { runCatching { capture.record.stop() }; capture.record.release() }
            }
        }
    }

    @Test fun serviceUsesTheChosenMicrophoneAndKeepsCaptureMetadata() {
        val previous = runBlocking { app.settings.flow.first() }
        var fixtureId: String? = null
        try {
            val input = builtIn()
            val option = MicrophoneInputs.options(app).single { it.deviceId == input.id }
            runBlocking {
                app.settings.selectMicrophone(option.key, option.label)
                app.settings.set(Settings.ALLOW_PLAYBACK_WHILE_RECORDING, true)
            }
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                activity.onActivity { RecordingService.command(it, RecordingService.START, Quality.VOICE) }
                playbackAwait { RecordingService.state.value.active && RecordingService.state.value.sessionId.isNotBlank() }
                fixtureId = RecordingService.state.value.sessionId
                playbackAwait { RecordingService.state.value.elapsedMs >= 400 }
                assertTrue(RecordingService.state.value.captureDetails.orEmpty().contains("Selected microphone route verified"))
                val configurations = app.getSystemService(AudioManager::class.java).activeRecordingConfigurations
                assertTrue("The active capture must use the selected built-in microphone", configurations.any { it.audioDevice?.id == input.id })
                RecordingService.command(app, RecordingService.SAVE)
                playbackAwait { !RecordingService.state.value.active }
                val saved = runBlocking { checkNotNull(app.repository.dao.get(checkNotNull(fixtureId))) }
                assertTrue(saved.captureDetails.orEmpty().contains("Selected microphone route verified"))
                assertTrue(saved.inputName.orEmpty().startsWith("Built-in microphone"))
                assertEquals(Quality.VOICE.format, WavFile.info(app.repository.file(saved)).format)
            }
        } finally {
            if (RecordingService.state.value.active && (fixtureId == null || fixtureId == RecordingService.state.value.sessionId)) {
                fixtureId = RecordingService.state.value.sessionId
                RecordingService.command(app, RecordingService.DISCARD)
                playbackAwait { !RecordingService.state.value.active }
            }
            runBlocking {
                fixtureId?.let { app.repository.delete(listOf(it)) }
                app.settings.selectMicrophone(previous.selectedMicrophone, previous.selectedMicrophoneName)
                app.settings.set(Settings.ALLOW_PLAYBACK_WHILE_RECORDING, previous.allowPlaybackWhileRecording)
            }
        }
    }
}
