package app.stillwave

import androidx.test.core.app.ApplicationProvider
import app.stillwave.audio.*
import app.stillwave.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class DeletionTest {
    private val app = ApplicationProvider.getApplicationContext<StillwaveApp>()
    private suspend fun recording(source: Recording? = null): Recording {
        app.ready.await()
        val id = UUID.randomUUID().toString()
        val file = File(app.recordingsDir, "$id.wav")
        WavFile.header(file, 16000, 1, 32000)
        return Recording(id = id, title = "Deletion fixture", createdAt = 123456, durationMs = 1000,
            fileName = file.name, sampleRate = 16000, channels = 1, sourceId = source?.id).also { app.repository.dao.insert(it) }
    }
    @Test fun deleteRemovesOnlySelectedAudioAndEditsKeepingSavedCuts() = runBlocking {
        val original = recording(); val cut = recording(original); val unrelated = recording()
        app.repository.dao.checkpoint(Checkpoint(recordingId = original.id, positionMs = 100, label = "Point"))
        app.repository.dao.window(CutWindow(recordingId = original.id, startMs = 0, endMs = 500))
        val result = app.repository.delete(listOf(original.id, original.id))
        assertEquals(1, result.deleted); assertEquals(0, result.failed)
        assertNull(app.repository.dao.get(original.id)); assertFalse(app.repository.file(original).exists())
        assertTrue(app.repository.dao.checkpoints(original.id).isEmpty()); assertTrue(app.repository.dao.windows(original.id).isEmpty())
        assertNotNull(app.repository.dao.get(cut.id)); assertTrue(app.repository.file(cut).exists())
        assertNotNull(app.repository.dao.get(unrelated.id)); assertTrue(app.repository.file(unrelated).exists())
        val batch = app.repository.delete(listOf(cut.id, unrelated.id))
        assertEquals(2, batch.deleted); assertEquals(0, batch.failed)
    }
    @Test fun interruptedDeletionCompletesBeforeCaptureRecoveryAndCannotResurrectAudio() = runBlocking {
        for (rowAlreadyRemoved in listOf(false, true)) {
            val source = recording()
            val pending = File(app.recordingsDir, "${source.id}.pending")
            CaptureJournal.write(pending, source.id, source.createdAt, 16000, 1, emptyList())
            val journal = DeletionJournal.write(app.recordingsDir, source)
            if (rowAlreadyRemoved) app.repository.dao.delete(source.id)
            app.repository.recoverPendingDeletions()
            app.repository.recoverInterruptedRecording()
            assertNull(app.repository.dao.get(source.id)); assertFalse(app.repository.file(source).exists())
            assertFalse(journal.exists()); assertFalse(pending.exists())
        }
    }
    @Test fun deletionWaitsForAnActiveFileReaderBeforeRemovingAudio() = runBlocking {
        val source = recording()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val reader = launch(Dispatchers.IO) { app.repository.withRecording(source.id) { entered.complete(Unit); release.await(); assertTrue(app.repository.file(it).exists()) } }
        entered.await()
        val deletion = async(Dispatchers.IO) { app.repository.delete(listOf(source.id)) }
        delay(200)
        assertFalse(deletion.isCompleted); assertTrue(app.repository.file(source).exists())
        release.complete(Unit); reader.join()
        assertEquals(1, deletion.await().deleted); assertFalse(app.repository.file(source).exists())
    }
    @Test fun deletionRejectsPathsOutsideTheRecordingDirectory() {
        assertThrows(IllegalArgumentException::class.java) { DeletionJournal.audioFile(app.recordingsDir, "../outside.wav") }
        assertThrows(IllegalArgumentException::class.java) { DeletionJournal.file(app.recordingsDir, "../other") }
    }
}
