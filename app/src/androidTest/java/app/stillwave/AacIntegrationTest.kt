package app.stillwave

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.stillwave.audio.*
import app.stillwave.data.*
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AacIntegrationTest {
    private val app = ApplicationProvider.getApplicationContext<StillwaveApp>()

    private suspend fun source(): Recording {
        app.ready.await()
        val id = UUID.randomUUID().toString()
        val wav = File(app.cacheDir, "$id-source.wav")
        val encoded = File(app.recordingsDir, "$id.m4a")
        try {
            WavFile.header(wav, 48000, 1, 288000)
            RandomAccessFile(wav, "rw").use { output ->
                output.seek(WavFile.HEADER_SIZE)
                repeat(144000) { frame ->
                    val value = (sin(2 * PI * 440 * frame / 48000) * 12000).toInt().toShort()
                    output.writeShort(java.lang.Short.reverseBytes(value).toInt())
                }
            }
            AacFile.encode(wav, encoded, 128000)
            val info = AacFile.info(encoded)
            return Recording(id = id, title = "AAC integration", createdAt = 123456L, durationMs = info.durationMs,
                fileName = encoded.name, sampleRate = info.rate, channels = info.channels).also { app.repository.add(it) }
        } finally { wav.delete() }
    }

    @Test fun compressedSourceSupportsDetailsWindowAndFullExportWithoutMutation() = runBlocking(Dispatchers.IO) {
        val source = source()
        val original = app.repository.file(source).readBytes()
        val exports = mutableListOf<String>()
        try {
            val details = RecordingFileDetails.read(app.repository.file(source))
            assertNull(details.error)
            assertNull(details.audio)
            assertEquals(48000, details.compressed!!.rate)
            assertEquals(1, details.compressed!!.channels)
            assertTrue(details.compressed!!.bitRate > 0)
            assertEquals(original.size.toLong(), details.sizeBytes)
            val window = CutWindow(recordingId = source.id, startMs = 500, endMs = 1500, reverse = true, speed = 2.0)
            for (full in listOf(false, true)) {
                val job = ExportWorker.enqueue(app, source.id, listOf(window), fullAudio = full, title = "Edited AAC source")
                val work = withTimeout(30000) { WorkManager.getInstance(app).getWorkInfoByIdFlow(job).first { it?.state?.isFinished == true }!! }
                assertEquals(work.outputData.getString("error"), WorkInfo.State.SUCCEEDED, work.state)
                val created = app.repository.dao.observeAll().first().filter { it.sourceId == source.id && it.id !in exports }
                assertEquals(1, created.size)
                val output = created.single()
                exports += output.id
                assertEquals("wav", output.fileExtension)
                if (!full) assertEquals(500L, output.durationMs)
                else assertTrue(kotlin.math.abs(output.durationMs - (source.durationMs - 500)) <= 30)
                assertTrue(WavFile.peaks(app.repository.file(output)).any { it > 0.05f })
            }
            // The final displayed timestamp must also be a valid decoded endpoint.
            app.repository.withRecording(source.id) { current ->
                EditorAudioSource.withWav(app, current) { decoded ->
                    assertEquals(source.durationMs, WavFile.info(decoded).durationMs)
                    val whole = File(app.cacheDir, "${source.id}-whole.wav")
                    try {
                        WindowRenderer.renderWindow(decoded, whole, CutWindow(recordingId = source.id, startMs = 0,
                            endMs = source.durationMs, reverse = true))
                        assertEquals(source.durationMs, WavFile.info(whole).durationMs)
                    } finally { whole.delete() }
                }
            }
            assertArrayEquals(original, app.repository.file(source).readBytes())
        } finally { app.repository.delete(exports + source.id) }
    }

    @Test fun compressedSourceSharesCorrectNameAndDeletesWithItsEdits() = runBlocking(Dispatchers.IO) {
        val source = source()
        try {
            app.repository.dao.window(CutWindow(recordingId = source.id, startMs = 0, endMs = 1000, reverse = true))
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                lateinit var share: Intent
                activity.onActivity { screen -> share = ViewModelProvider(screen)[AppViewModel::class.java].shareIntent(listOf(source)) }
                assertEquals("audio/mp4", share.type)
                @Suppress("DEPRECATION") val uri = share.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
                app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("${source.title}.m4a", cursor.getString(0))
                }
            }
            val result = app.repository.delete(listOf(source.id))
            assertEquals(1, result.deleted)
            assertEquals(0, result.failed)
            assertFalse(app.repository.file(source).exists())
            assertTrue(app.repository.dao.windows(source.id).isEmpty())
        } finally { app.repository.delete(listOf(source.id)) }
    }

    @Test fun recoveryKeepsPublishedAacAndRemovesItsTemporaryPcm() = runBlocking(Dispatchers.IO) {
        val source = source()
        val encoded = app.repository.file(source)
        val original = encoded.readBytes()
        val temporary = File(app.recordingsDir, "${source.id}.wav")
        val journal = File(app.recordingsDir, "${source.id}.pending")
        try {
            AacFile.decode(encoded, temporary)
            CaptureJournal.write(journal, source.id, source.createdAt, source.sampleRate, source.channels, emptyList())
            app.repository.dao.checkpoint(Checkpoint(recordingId = source.id, positionMs = 100, label = "Kept checkpoint"))
            app.repository.recoverInterruptedRecording()
            assertEquals(source.fileName, app.repository.dao.get(source.id)!!.fileName)
            assertArrayEquals(original, encoded.readBytes())
            assertFalse(temporary.exists())
            assertFalse(journal.exists())
            assertEquals("Kept checkpoint", app.repository.dao.checkpoints(source.id).single().label)
            AacFile.decode(encoded, temporary)
            CaptureJournal.write(journal, source.id, source.createdAt, source.sampleRate, source.channels, emptyList())
            val partial = File(app.recordingsDir, "${source.id}.encoding").apply { writeText("partial") }
            assertEquals(1, app.repository.delete(listOf(source.id)).deleted)
            assertFalse(encoded.exists()); assertFalse(temporary.exists()); assertFalse(partial.exists()); assertFalse(journal.exists())
        } finally {
            CaptureJournal.delete(journal)
            temporary.delete()
            app.repository.delete(listOf(source.id))
        }
    }

    @Test fun interruptedAacSaveRecoversPcmAndRemovesUnpublishedConversions() = runBlocking(Dispatchers.IO) {
        app.ready.await()
        val id = UUID.randomUUID().toString()
        val wav = File(app.recordingsDir, "$id.wav")
        val encoded = File(app.recordingsDir, "$id.m4a")
        val partial = File(app.recordingsDir, "$id.encoding")
        val journal = File(app.recordingsDir, "$id.pending")
        try {
            WavFile.header(wav, 16000, 1, 32000)
            val original = wav.readBytes()
            encoded.writeText("unpublished")
            partial.writeText("incomplete")
            val point = Checkpoint(recordingId = id, positionMs = 200, label = "Recovered point")
            CaptureJournal.write(journal, id, 123456, 16000, 1, listOf(point), requestedQuality = Quality.aac(16000, 32000, 1).name)
            app.repository.recoverInterruptedRecording()
            val recovered = app.repository.dao.get(id)!!
            assertFalse(recovered.isAac)
            assertArrayEquals(original, app.repository.file(recovered).readBytes())
            assertEquals(123456L, recovered.createdAt)
            assertEquals("Recovered point", app.repository.dao.checkpoints(id).single().label)
            assertTrue(recovered.captureDetails!!.contains("recovered as lossless WAV"))
            assertFalse(encoded.exists()); assertFalse(partial.exists()); assertFalse(journal.exists())
        } finally {
            CaptureJournal.delete(journal)
            encoded.delete(); partial.delete()
            app.repository.delete(listOf(id))
            wav.delete()
        }
    }
}
