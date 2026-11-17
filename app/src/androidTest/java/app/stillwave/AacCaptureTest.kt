package app.stillwave

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.stillwave.audio.AacFile
import app.stillwave.audio.RecordingService
import app.stillwave.audio.WavFile
import app.stillwave.data.Quality
import app.stillwave.data.Settings
import app.stillwave.data.isAac
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Isolated microphone fixture. Hardware opt-in requires permission already granted; preserves user data. */
class AacCaptureTest {
    private fun emulatorOnly() = assumeTrue("Run capture fixtures on an emulator", Build.HARDWARE in listOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic"))
    private fun awaitCapture(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "AAC capture timed out: ${RecordingService.state.value}" }
            Thread.sleep(25)
        }
    }

    @Test fun aacServicePausesResumesKeepsCheckpointsAndSavesM4a() {
        val hardwareOptIn = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("allowHardwareAac") == "true"
        if (!hardwareOptIn) emulatorOnly()
        val app = ApplicationProvider.getApplicationContext<StillwaveApp>()
        check(!RecordingService.state.value.active) { "Do not interrupt an existing capture" }
        val previous = runBlocking { app.ready.await(); app.settings.flow.first() }
        val existing = runBlocking { app.repository.dao.observeAll().first().associate { it.id to it.fileName } }
        var fixtureId: String? = null
        try {
            if (!hardwareOptIn) {
                playbackShell("pm grant app.stillwave android.permission.RECORD_AUDIO")
                if (Build.VERSION.SDK_INT >= 33) playbackShell("pm grant app.stillwave android.permission.POST_NOTIFICATIONS")
            }
            assertEquals(PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val quality = Quality.aac(48000, 128000, 1)
                val startedAt = android.os.SystemClock.elapsedRealtime()
                scenario.onActivity {
                    it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    RecordingService.command(it, RecordingService.START, quality)
                }
                awaitCapture { RecordingService.state.value.active && RecordingService.state.value.sessionId.isNotBlank() }
                fixtureId = RecordingService.state.value.sessionId
                awaitCapture { RecordingService.state.value.elapsedMs > 0 }
                val startupMs = android.os.SystemClock.elapsedRealtime() - startedAt
                awaitCapture { RecordingService.state.value.elapsedMs >= 700 }
                assertEquals(quality, RecordingService.state.value.quality)
                assertEquals(48000, RecordingService.state.value.format!!.rate)
                assertEquals(1, RecordingService.state.value.format!!.channels)
                RecordingService.command(app, RecordingService.PAUSE)
                awaitCapture { RecordingService.state.value.paused }
                val pausedAt = RecordingService.state.value.elapsedMs
                RecordingService.command(app, RecordingService.CHECKPOINT)
                awaitCapture { RecordingService.state.value.checkpointCount == 1 }
                assertEquals(pausedAt, RecordingService.state.value.lastCheckpointMs)
                Thread.sleep(350)
                assertEquals(pausedAt, RecordingService.state.value.elapsedMs)
                RecordingService.command(app, RecordingService.RESUME)
                awaitCapture { RecordingService.state.value.elapsedMs >= pausedAt + 500 }
                RecordingService.command(app, RecordingService.PAUSE)
                awaitCapture { RecordingService.state.value.paused }
                val expectedDuration = RecordingService.state.value.elapsedMs
                RecordingService.command(app, RecordingService.SAVE)
                awaitCapture { !RecordingService.state.value.active }
                assertNull(RecordingService.state.value.error)
                val saved = runBlocking { checkNotNull(app.repository.dao.get(checkNotNull(fixtureId))) }
                assertTrue("AAC must not silently fall back to WAV in this fixture", saved.isAac)
                val info = AacFile.info(app.repository.file(saved))
                assertEquals(48000, info.rate)
                assertEquals(1, info.channels)
                assertEquals(info.durationMs, saved.durationMs)
                assertTrue("AAC duration drift: ${info.durationMs} versus $expectedDuration", kotlin.math.abs(info.durationMs - expectedDuration) < 100)
                val checkpoint = runBlocking { app.repository.dao.checkpoints(saved.id).single() }
                assertEquals(pausedAt, checkpoint.positionMs)
                assertFalse(File(app.recordingsDir, "${saved.id}.wav").exists())
                assertFalse(File(app.recordingsDir, "${saved.id}.pending").exists())
                assertFalse(File(app.recordingsDir, "${saved.id}.encoding").exists())
                assertEquals(saved.id, RecordingService.lastSavedRecordingId.value)
                File(app.cacheDir, "aac-recording-validation.txt").writeText("AAC ${info.rate} Hz / ${info.channels} channels\nStartup: $startupMs ms\nDuration: ${info.durationMs} ms\nBitrate: ${info.bitRate} bps\nBytes: ${app.repository.file(saved).length()}\nPause, checkpoint, resume, save: passed\n")
            }
        } finally {
            if (RecordingService.state.value.active && (fixtureId == null || fixtureId == RecordingService.state.value.sessionId)) {
                fixtureId = RecordingService.state.value.sessionId
                RecordingService.command(app, RecordingService.DISCARD)
                awaitCapture { !RecordingService.state.value.active }
            }
            fixtureId?.let { assertEquals(0, runBlocking { app.repository.delete(listOf(it)).failed }) }
            existing.forEach { (id, fileName) ->
                assertEquals(fileName, runBlocking { app.repository.dao.get(id)?.fileName })
                assertTrue(File(app.recordingsDir, fileName).isFile)
            }
        }
    }

    @Test fun encodeAndDecodeCancellationRemovePartialOutputsAndKeepSources() {
        emulatorOnly()
        val app = ApplicationProvider.getApplicationContext<StillwaveApp>()
        val directory = File(app.cacheDir, "aac-cancellation-${UUID.randomUUID()}").apply { check(mkdir()) }
        val source = File(directory, "source.wav")
        val encoded = File(directory, "source.m4a")
        val partialEncode = File(directory, "cancelled.m4a")
        val partialDecode = File(directory, "cancelled.wav")
        val decoded = File(directory, "decoded.wav")
        fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
        try {
            WavFile.header(source, 48000, 2, 48000L * 4 * 3)
            val sourceHash = hash(source)
            var encodeChecks = 0
            assertThrows(CancellationException::class.java) {
                AacFile.encode(source, partialEncode, 128000) { if (++encodeChecks >= 3) throw CancellationException("Cancel encode fixture") }
            }
            assertFalse(partialEncode.exists())
            assertEquals(sourceHash, hash(source))
            AacFile.encode(source, encoded, 128000)
            val encodedHash = hash(encoded)
            var decodeChecks = 0
            assertThrows(CancellationException::class.java) {
                AacFile.decode(encoded, partialDecode) { if (++decodeChecks >= 3) throw CancellationException("Cancel decode fixture") }
            }
            assertFalse(partialDecode.exists())
            assertEquals(encodedHash, hash(encoded))
            AacFile.decode(encoded, decoded)
            assertEquals(AacFile.info(encoded).durationMs, WavFile.info(decoded).durationMs)
            assertEquals(sourceHash, hash(source))
            assertEquals(encodedHash, hash(encoded))
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }
}
