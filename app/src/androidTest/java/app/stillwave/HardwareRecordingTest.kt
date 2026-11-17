package app.stillwave

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.stillwave.audio.RecordingService
import app.stillwave.audio.WavFile
import app.stillwave.data.Quality
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Direct hardware integration test, independent of Compose input injection. */
@RunWith(AndroidJUnit4::class)
class HardwareRecordingTest {
    private fun awaitCondition(timeoutMillis: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Recording condition timed out: ${RecordingService.state.value}" }
            Thread.sleep(25)
        }
    }
    @Test fun allQualityPresetsPauseResumeAndSaveOnMicrophone() {
        val app = ApplicationProvider.getApplicationContext<StillwaveApp>()
        check(!RecordingService.state.value.active) { "A recording is already in progress" }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Some physical devices prohibit ADB permission grants. Use the normal OS consent dialog.
            scenario.onActivity { activity ->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 42)
                }
            }
            awaitCondition(120_000) { ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
            val startupReport = java.io.File(app.cacheDir, "recording-startup-validation.txt")
            startupReport.writeText("Command to first published audio (includes service launch):\n")
            // Extended formats depend on the input; the universal smoke check keeps the core presets.
            val qualities = Quality.fixedPresets + listOf(Quality.BEST, Quality.BEST)
            for ((index, quality) in qualities.withIndex()) {
                var fixtureId: String? = null
                try {
                    val startedAt = android.os.SystemClock.elapsedRealtime()
                    scenario.onActivity { RecordingService.command(it, RecordingService.START, quality) }
                    awaitCondition { RecordingService.state.value.elapsedMs > 0 }
                    val startupMs = android.os.SystemClock.elapsedRealtime() - startedAt
                    startupReport.appendText("${quality.name}: $startupMs ms\n")
                    android.util.Log.i("CaptureStartup", "${quality.name} command to published audio: $startupMs ms")
                    // Opt-in device-specific budget; loaded CI emulators have different timing.
                    val budgetKey = if (quality == Quality.BEST) "maxBestStartupMs" else "maxPresetStartupMs"
                    androidx.test.platform.app.InstrumentationRegistry.getArguments().getString(budgetKey)?.toLong()?.let { budget ->
                        assertTrue("$quality startup took $startupMs ms (budget $budget ms)", startupMs <= budget)
                    }
                    awaitCondition { RecordingService.state.value.elapsedMs >= 400 }
                    fixtureId = RecordingService.state.value.sessionId
                    RecordingService.command(app, RecordingService.PAUSE)
                    awaitCondition { RecordingService.state.value.paused }
                    val actualFormat = RecordingService.state.value.format!!
                    val pausedAt = RecordingService.state.value.elapsedMs
                    Thread.sleep(350)
                    assertEquals("Paused duration drifted for $quality", pausedAt, RecordingService.state.value.elapsedMs)
                    RecordingService.command(app, RecordingService.RESUME)
                    awaitCondition { RecordingService.state.value.elapsedMs >= pausedAt + 400 }
                    RecordingService.command(app, RecordingService.SAVE)
                    awaitCondition { !RecordingService.state.value.active }
                    val saved = runBlocking { app.repository.dao.get(fixtureId!!)!! }
                    val info = WavFile.info(app.repository.file(saved))
                    assertEquals(actualFormat, info.format)
                    assertEquals(actualFormat, saved.format())
                    if (quality != Quality.BEST) {
                        assertEquals(quality.sampleRate, info.rate)
                        assertEquals(quality.channels, info.channels)
                    } else {
                        assertNotNull(saved.inputName)
                        assertNotNull(saved.captureDetails)
                        val reused = index == qualities.lastIndex
                        if (reused) assertTrue("Expected the same microphone's remembered format", saved.captureDetails!!.contains("Remembered microphone format validated"))
                        val signalPeak = WavFile.peaks(app.repository.file(saved)).maxOrNull()
                        java.io.File(app.cacheDir, if (reused) "microphone-cached-validation.txt" else "microphone-validation.txt")
                            .writeText("${saved.inputName}\n${saved.captureDetails}\nMeasured sample peak: $signalPeak")
                    }
                    assertTrue(info.durationMs >= 800)
                    assertEquals(info.durationMs, saved.durationMs)
                    runBlocking { app.repository.dao.rename(saved.id, "Hardware test · ${quality.label}") }
                } finally {
                    if (RecordingService.state.value.active) {
                        RecordingService.command(app, RecordingService.DISCARD)
                        awaitCondition { !RecordingService.state.value.active }
                    }
                    fixtureId?.let { id ->
                        val deleted = runBlocking { app.repository.delete(listOf(id)) }
                        assertEquals("Test recording cleanup failed", 0, deleted.failed)
                        assertNull(runBlocking { app.repository.dao.get(id) })
                    }
                }
            }
        }
    }
}
