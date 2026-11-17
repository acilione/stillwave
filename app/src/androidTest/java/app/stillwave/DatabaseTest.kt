package app.stillwave

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.stillwave.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import app.stillwave.audio.WavFile
import app.stillwave.audio.PcmEncoding
import app.stillwave.audio.CaptureJournal
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    @Test fun interruptedCaptureRecoversFromJournal() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<StillwaveApp>()
        app.ready.await()
        val id = UUID.randomUUID().toString()
        val file = File(app.recordingsDir, "$id.wav")
        val journal = File(app.recordingsDir, "$id.pending")
        WavFile.header(file, 16000, 1, 0)
        RandomAccessFile(file, "rw").use { it.setLength(32044) }
        CaptureJournal.write(journal, id, 1792837800000, 16000, 1, listOf(
            Checkpoint(id = "recovered-point-$id", recordingId = id, positionMs = 750, label = "Checkpoint 1"),
            Checkpoint(recordingId = id, positionMs = 1500, label = "Beyond recovered audio")))
        app.repository.recoverInterruptedRecording()
        assertEquals(1000L, WavFile.info(file).durationMs)
        assertEquals(1000L, app.repository.dao.get(id)!!.durationMs)
        assertFalse(journal.exists())
        val points = app.repository.dao.checkpoints(id)
        assertEquals(1, points.size)
        assertEquals(750L, points.single().positionMs)
        assertEquals("Checkpoint 1", points.single().label)
    }
    @Test fun higherPrecisionInterruptedCapturesPreserveFormatAndInputMetadata() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<StillwaveApp>()
        app.ready.await()
        for (encoding in PcmEncoding.entries) {
            val id = UUID.randomUUID().toString()
            val file = File(app.recordingsDir, "$id.wav")
            val journal = File(app.recordingsDir, "$id.pending")
            val frames = 48000
            val frameSize = 2 * encoding.bytes
            WavFile.header(file, 48000, 2, 0, encoding)
            RandomAccessFile(file, "rw").use { it.setLength(WavFile.headerSize(encoding) + frames.toLong() * frameSize + frameSize - 1) }
            CaptureJournal.write(journal, id, 123456L, 48000, 2, emptyList(), encoding, "USB test microphone", "Validated format")
            app.repository.recoverInterruptedRecording()
            val recovered = app.repository.dao.get(id)!!
            assertEquals(encoding.name, recovered.pcmEncoding)
            assertEquals("USB test microphone", recovered.inputName)
            assertEquals("Validated format", recovered.captureDetails)
            assertEquals(1000L, recovered.durationMs)
            assertEquals(encoding, WavFile.info(file).encoding)
            assertFalse(journal.exists())
        }
    }
    @Test fun metadataAndEditsSurviveRename() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RecordingDatabase::class.java).build()
        try {
            val dao = db.recordings()
            val rec = Recording(id = "original", title = "24/10/26 12:30:00", createdAt = 1792837800000, durationMs = 9000, fileName = "original.wav", sampleRate = 16000, channels = 1)
            dao.insert(rec)
            dao.checkpoint(Checkpoint(recordingId = rec.id, positionMs = 1000, label = "Idea"))
            dao.window(CutWindow(recordingId = rec.id, startMs = 0, endMs = 4000, ordinal = 0, title = "Named excerpt"))
            dao.window(CutWindow(recordingId = rec.id, startMs = 2000, endMs = 8000, selected = false, ordinal = 1))
            dao.rename(rec.id, "A different name")
            dao.pin(listOf(rec.id), true)
            val loaded = dao.get(rec.id)!!
            assertEquals(rec.createdAt, loaded.createdAt)
            assertEquals(rec.durationMs, loaded.durationMs)
            assertEquals("A different name", loaded.title)
            assertTrue(loaded.pinned)
            assertEquals(2, dao.observeWindows(rec.id).first().size)
            assertFalse(dao.windows(rec.id)[1].selected)
            assertEquals("Named excerpt", dao.windows(rec.id)[0].title)
            assertEquals(1000L, dao.checkpoints(rec.id).single().positionMs)
        } finally { db.close() }
    }
}
