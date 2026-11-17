package app.stillwave

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.stillwave.audio.CaptureJournal
import app.stillwave.audio.WavFile
import app.stillwave.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

class FolderTest {
    private fun recording(id: String) = Recording(id = id, title = id, createdAt = 123456, durationMs = 1000,
        fileName = "$id.wav", sampleRate = 16000, channels = 1, pinned = true)

    @Test fun bulkMoveAndFolderDeletionPreserveRecordingsAndEdits() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<StillwaveApp>()
        val db = Room.inMemoryDatabaseBuilder(app, RecordingDatabase::class.java).build()
        try {
            val dao = db.recordings()
            val folder = RecordingFolder(name = "Notes")
            dao.insertFolder(folder)
            val first = recording("first"); val second = recording("second"); val keep = recording("keep")
            listOf(first, second, keep).forEach { dao.insert(it) }
            dao.checkpoint(Checkpoint(recordingId = first.id, positionMs = 250, label = "Point"))
            dao.window(CutWindow(recordingId = first.id, startMs = 0, endMs = 500))
            dao.move(listOf(first.id, second.id), folder.id)
            assertEquals(first.copy(folderId = folder.id), dao.get(first.id))
            assertEquals(second.copy(folderId = folder.id), dao.get(second.id))
            assertEquals(keep, dao.get(keep.id))
            dao.renameFolder(folder.id, "Renamed", "renamed")
            assertEquals("Renamed", dao.folder(folder.id)!!.name)
            assertEquals(first.copy(folderId = folder.id), dao.get(first.id))
            dao.deleteFolder(folder.id)
            assertEquals(first, dao.get(first.id)); assertEquals(second, dao.get(second.id))
            assertEquals(1, dao.checkpoints(first.id).size); assertEquals(1, dao.windows(first.id).size)
        } finally { db.close() }
    }

    @Test fun folderMembershipSurvivesReopeningAndNamesStayUnique() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<StillwaveApp>()
        val name = "folders-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(app, RecordingDatabase::class.java, name).build()
        try {
            val folder = RecordingFolder(name = "Lectures")
            db.recordings().insertFolder(folder)
            db.recordings().insert(recording("lecture").copy(folderId = folder.id))
            db.close()
            db = Room.databaseBuilder(app, RecordingDatabase::class.java, name).build()
            val dao = db.recordings()
            assertEquals(folder, dao.folders().single())
            assertEquals(folder.id, dao.get("lecture")!!.folderId)
            assertTrue(runCatching { dao.insertFolder(RecordingFolder(name = "LECTURES")) }.isFailure)
            assertTrue(runCatching { dao.move(listOf("lecture"), "missing-folder") }.isFailure)
            assertEquals(folder.id, dao.get("lecture")!!.folderId)
            dao.move(listOf("lecture"), null)
            assertNull(dao.get("lecture")!!.folderId)
        } finally { db.close(); app.deleteDatabase(name) }
    }

    @Test fun recoveryRemembersFolderAndFallsBackWhenFolderWasDeleted() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<StillwaveApp>()
        app.ready.await()
        for (removeFolder in listOf(false, true)) {
            val id = UUID.randomUUID().toString()
            val folder = RecordingFolder(name = "Recovery $id")
            val file = File(app.recordingsDir, "$id.wav")
            val journal = File(app.recordingsDir, "$id.pending")
            try {
                app.repository.dao.insertFolder(folder)
                WavFile.header(file, 16000, 1, 0)
                RandomAccessFile(file, "rw").use { it.setLength(32044) }
                CaptureJournal.write(journal, id, 123456, 16000, 1, emptyList(), folderId = folder.id)
                if (removeFolder) app.repository.dao.deleteFolder(folder.id)
                app.repository.recoverInterruptedRecording()
                val saved = app.repository.dao.get(id)!!
                assertEquals(if (removeFolder) null else folder.id, saved.folderId)
                assertEquals(1000L, saved.durationMs)
                assertTrue(file.exists()); assertFalse(journal.exists())
            } finally {
                app.repository.delete(listOf(id)); app.repository.dao.deleteFolder(folder.id)
                CaptureJournal.delete(journal); file.delete()
            }
        }
    }
}
