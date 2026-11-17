package app.stillwave

import android.app.NotificationManager
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.stillwave.audio.CaptureJournal
import app.stillwave.audio.RecordingService
import app.stillwave.audio.WavFile
import app.stillwave.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** Emulator-only: exercises the actual microphone service and durable project-capture journals. */
class ProjectCaptureTest {
    private val app get() = ApplicationProvider.getApplicationContext<StillwaveApp>()

    @Before fun prepare() {
        assumeTrue("Project capture tests are emulator-only", Build.FINGERPRINT.contains("generic") ||
            Build.MODEL.contains("sdk", ignoreCase = true) || Build.HARDWARE in setOf("ranchu", "goldfish", "cutf_cvm"))
        check(!RecordingService.state.value.active)
        playbackShell("input keyevent KEYCODE_WAKEUP")
        playbackShell("wm dismiss-keyguard")
        playbackShell("pm grant app.stillwave android.permission.RECORD_AUDIO")
        if (Build.VERSION.SDK_INT >= 33) playbackShell("pm grant app.stillwave android.permission.POST_NOTIFICATIONS")
        runBlocking { app.ready.await() }
    }

    @Test fun loopCaptureTruncatesFinalBufferAndAutoSavesExactlyOneCycleWithoutPause() {
        val project = MultitrackProject(title = "Exact loop capture", durationMs = 2403, loopPlayback = true)
        val simultaneous = runBlocking { app.settings.flow.first().allowPlaybackWhileRecording }
        var capturedId: String? = null
        runBlocking {
            app.settings.set(Settings.ALLOW_PLAYBACK_WHILE_RECORDING, true)
            app.multitrackStore.save(project)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                activity.onActivity { RecordingService.command(it, RecordingService.START, Quality.VOICE, projectId = project.id) }
                playbackAwait { RecordingService.state.value.elapsedMs >= 200 }
                capturedId = RecordingService.state.value.sessionId
                assertEquals(project.id, RecordingService.state.value.projectId)
                val actions = app.getSystemService(NotificationManager::class.java).activeNotifications
                    .first { it.id == 1 }.notification.actions.orEmpty().map { it.title.toString() }
                assertFalse(actions.contains("Pause"))
                RecordingService.command(app, RecordingService.PAUSE)
                playbackAwait { RecordingService.state.value.elapsedMs >= 600 }
                assertFalse(RecordingService.state.value.paused)
                playbackAwait { !RecordingService.state.value.active && RecordingService.lastSavedRecordingId.value == capturedId }
                val recording = runBlocking { checkNotNull(app.repository.dao.get(checkNotNull(capturedId))) }
                val info = WavFile.info(app.repository.file(recording))
                assertEquals(2403L, info.durationMs)
                assertEquals(2403L * 16 * 2, info.dataBytes)
                assertEquals(recording.durationMs, info.durationMs)
                val savedProject = runBlocking { app.multitrackStore.load().single { it.id == project.id } }
                assertEquals(2403L, savedProject.durationMs)
                assertEquals(recording.id, savedProject.tracks.single().recordingId)
                assertEquals(0L, savedProject.tracks.single().offsetMs)
                assertTrue(savedProject.tracks.single().loop)
                assertFalse(File(app.recordingsDir, "${recording.id}.pending").exists())
            }
        } finally {
            if (RecordingService.state.value.active && RecordingService.state.value.projectId == project.id) {
                capturedId = RecordingService.state.value.sessionId
                RecordingService.command(app, RecordingService.DISCARD)
                playbackAwait { !RecordingService.state.value.active }
            }
            runBlocking {
                capturedId?.let { app.repository.delete(listOf(it)) }
                app.multitrackStore.delete(project.id)
                app.settings.set(Settings.ALLOW_PLAYBACK_WHILE_RECORDING, simultaneous)
            }
        }
    }

    @Test fun interruptedAndAlreadyPublishedTakesAttachIdempotentlyWithTheirSavedOffset() = runBlocking {
        val project = MultitrackProject(title = "Recovered loop", durationMs = 2500, loopPlayback = true)
        val id = UUID.randomUUID().toString()
        val source = File(app.recordingsDir, "$id.wav")
        val journal = File(app.recordingsDir, "$id.pending")
        app.multitrackStore.save(project)
        try {
            WavFile.header(source, 16000, 1, 0)
            source.appendBytes(ByteArray(32000) { (it * 17).toByte() })
            val point = Checkpoint(recordingId = id, positionMs = 500, label = "Recovered point")
            fun writeJournal() = CaptureJournal.write(journal, id, 123456, 16000, 1, listOf(point),
                projectId = project.id, projectOffsetMs = 333, projectLoop = true)
            writeJournal()
            app.repository.recoverInterruptedRecording()
            val recording = checkNotNull(app.repository.dao.get(id))
            assertEquals(1000L, recording.durationMs)
            assertEquals(point, app.repository.dao.checkpoints(id).single())
            val original = source.readBytes()
            val first = app.multitrackStore.load().single { it.id == project.id }.tracks.single()
            assertEquals(id, first.recordingId)
            assertEquals(333L, first.offsetMs)
            assertTrue(first.loop)
            assertFalse(journal.exists())
            // Simulate interruption after the Room row and project track were published.
            writeJournal()
            app.repository.recoverInterruptedRecording()
            assertEquals(listOf(first), app.multitrackStore.load().single { it.id == project.id }.tracks)
            assertArrayEquals(original, source.readBytes())
            assertFalse(journal.exists())
        } finally {
            app.repository.delete(listOf(id))
            CaptureJournal.delete(journal)
            source.delete()
            app.multitrackStore.delete(project.id)
        }
    }

    @Test fun attachmentFailureKeepsPublishedTakeAndJournalUntilProjectCanBeRead() = runBlocking {
        val project = MultitrackProject(title = "Retry attachment", durationMs = 2500)
        val id = UUID.randomUUID().toString()
        val source = File(app.recordingsDir, "$id.wav")
        val journal = File(app.recordingsDir, "$id.pending")
        app.multitrackStore.save(project)
        try {
            WavFile.header(source, 16000, 1, 32000)
            val recording = Recording(id = id, title = "Published take", createdAt = 123456, durationMs = 1000,
                fileName = source.name, sampleRate = 16000, channels = 1)
            app.repository.dao.insert(recording)
            val original = source.readBytes()
            CaptureJournal.write(journal, id, recording.createdAt, 16000, 1, emptyList(), projectId = project.id, projectOffsetMs = 2000)
            File(app.filesDir, "projects/${project.id}.json").writeText("unreadable test fixture")
            app.repository.recoverInterruptedRecording()
            assertTrue(journal.exists())
            assertEquals(recording, app.repository.dao.get(id))
            assertArrayEquals(original, source.readBytes())
            app.multitrackStore.save(project)
            app.repository.recoverInterruptedRecording()
            val restored = app.multitrackStore.load().single { it.id == project.id }
            assertEquals(id, restored.tracks.single().recordingId)
            assertEquals(2000L, restored.tracks.single().offsetMs)
            assertEquals(3000L, restored.durationMs)
            assertFalse(journal.exists())
            assertArrayEquals(original, source.readBytes())
        } finally {
            app.repository.delete(listOf(id))
            CaptureJournal.delete(journal)
            source.delete()
            app.multitrackStore.delete(project.id)
        }
    }
}
