package app.stillwave

import androidx.test.core.app.ApplicationProvider
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.stillwave.audio.*
import app.stillwave.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin

class EditedExportTest {
    private val app get() = ApplicationProvider.getApplicationContext<StillwaveApp>()
    private fun awaitJob(id: UUID) {
        val manager = WorkManager.getInstance(app)
        val deadline = System.nanoTime() + 30_000_000_000
        while (manager.getWorkInfoById(id).get()?.state?.isFinished != true) {
            check(System.nanoTime() < deadline) { "Edited export timed out" }
            Thread.sleep(25)
        }
        val result = manager.getWorkInfoById(id).get()!!
        assertEquals(result.outputData.getString("error"), WorkInfo.State.SUCCEEDED, result.state)
    }
    private fun payload(file: File): ByteArray {
        val info = WavFile.info(file)
        return file.readBytes().copyOfRange(info.dataOffset.toInt(), (info.dataOffset + info.dataBytes).toInt())
    }
    private fun source(encoding: PcmEncoding = PcmEncoding.PCM24, folder: String? = null): Recording {
        val id = UUID.randomUUID().toString()
        val file = File(app.recordingsDir, "$id.wav")
        val bytes = ByteArray(16000 * 2 * encoding.bytes) { (it * 31 + it / 7).toByte() }
        WavFile.header(file, 8000, 2, 0, encoding)
        file.appendBytes(bytes)
        WavFile.header(file, 8000, 2, bytes.size.toLong(), encoding)
        return Recording(id = id, title = "Editor export source", createdAt = 123456L, durationMs = 2000,
            fileName = file.name, sampleRate = 8000, channels = 2, pcmEncoding = encoding.name, folderId = folder)
            .also { runBlocking { app.ready.await(); app.repository.add(it) } }
    }
    private fun cleanup(source: Recording) = runBlocking {
        val outputs = app.repository.dao.observeAll().first().filter { it.sourceId == source.id }
        app.repository.delete(outputs.map { it.id } + source.id)
    }

    @Test fun selectedWindowsAndFullTimelineExportSnapshotsWithNamesMetadataAndOriginalIntact() {
        val folder = RecordingFolder(name = "Effects ${UUID.randomUUID()}")
        runBlocking { app.ready.await(); app.repository.dao.insertFolder(folder) }
        val source = source(folder = folder.id)
        val original = app.repository.file(source).readBytes()
        try {
            val reverse = CutWindow(recordingId = source.id, startMs = 100, endMs = 600, title = "Reversed intro", reverse = true)
            val faster = CutWindow(recordingId = source.id, startMs = 900, endMs = 1400, ordinal = 1, title = "Fast fragment", speed = 2.0)
            runBlocking { app.repository.dao.window(reverse); app.repository.dao.window(faster) }
            val separate = ExportWorker.enqueue(app, source.id, listOf(reverse, faster))
            runBlocking { app.repository.dao.window(reverse.copy(title = "Changed after export", reverse = false)) }
            awaitJob(separate)
            awaitJob(ExportWorker.enqueue(app, source.id, listOf(faster, reverse), fullAudio = true, title = "Integrated session"))
            val exports = runBlocking { app.repository.dao.observeAll().first().filter { it.sourceId == source.id } }
            assertEquals(setOf("Reversed intro", "Fast fragment", "Integrated session"), exports.map { it.title }.toSet())
            assertEquals(500L, exports.single { it.title == "Reversed intro" }.durationMs)
            assertEquals(250L, exports.single { it.title == "Fast fragment" }.durationMs)
            assertEquals(1750L, exports.single { it.title == "Integrated session" }.durationMs)
            for (export in exports) {
                assertEquals(source.format(), export.format())
                assertEquals(source.createdAt, export.sourceCreatedAt)
                assertEquals(folder.id, export.folderId)
                assertEquals(export.durationMs, WavFile.info(app.repository.file(export)).durationMs)
            }
            val first = payload(app.repository.file(exports.single { it.title == "Reversed intro" }))
            val input = payload(app.repository.file(source))
            assertArrayEquals(input.copyOfRange(4799 * 6, 4800 * 6), first.copyOfRange(0, 6))
            val integrated = payload(app.repository.file(exports.single { it.title == "Integrated session" }))
            assertArrayEquals(input.copyOfRange(0, 800 * 6), integrated.copyOfRange(0, 800 * 6))
            assertArrayEquals(input.takeLast(1000).toByteArray(), integrated.takeLast(1000).toByteArray())
            assertArrayEquals(original, app.repository.file(source).readBytes())
        } finally { cleanup(source); runBlocking { app.repository.dao.deleteFolder(folder.id) } }
    }

    @Test fun queuedLegacyCutJobsRemainCompatibleAfterUpgrade() {
        val source = source()
        try {
            val job = UUID.randomUUID()
            val output = UUID.randomUUID().toString()
            val entries = JSONArray().put(JSONObject().put("outputId", output).put("start", 200L).put("end", 1400L).put("ordinal", 0).put("title", "Legacy cut"))
            File(app.filesDir, "exports").mkdirs()
            File(app.filesDir, "exports/$job.json").writeText(JSONObject().put("recordingId", source.id).put("windows", entries).toString())
            val request = OneTimeWorkRequestBuilder<ExportWorker>().setId(job).setInputData(workDataOf("job" to job.toString())).build()
            WorkManager.getInstance(app).enqueue(request)
            awaitJob(job)
            val exported = runBlocking { app.repository.dao.get(output)!! }
            assertEquals("Legacy cut", exported.title)
            assertEquals(1200L, exported.durationMs)
            val frame = source.format().frameSize
            assertArrayEquals(payload(app.repository.file(source)).copyOfRange(1600 * frame, 11200 * frame), payload(app.repository.file(exported)))
        } finally { cleanup(source) }
    }

    @Test fun internalRemovalJoinsCheckedIntervalsAndItsOutputCanBeEditedAgain() {
        val folder = RecordingFolder(name = "Removal ${UUID.randomUUID()}")
        runBlocking { app.ready.await(); app.repository.dao.insertFolder(folder) }
        val source = source(folder = folder.id)
        val original = app.repository.file(source).readBytes()
        var remaining: Recording? = null
        try {
            val a = CutWindow(recordingId = source.id, startMs = 300, endMs = 900, reverse = true)
            val b = CutWindow(recordingId = source.id, startMs = 700, endMs = 1200, speed = 0.5)
            awaitJob(ExportWorker.enqueue(app, source.id, listOf(b, a), title = "Joined take", removeSelected = true))
            val joined = runBlocking { app.repository.dao.observeAll().first().single { it.sourceId == source.id } }
            remaining = joined
            assertEquals("Joined take", joined.title)
            assertEquals(1100L, joined.durationMs)
            assertEquals(source.format(), joined.format())
            assertEquals(source.createdAt, joined.sourceCreatedAt)
            assertEquals(folder.id, joined.folderId)
            val bytes = payload(app.repository.file(source))
            val frame = source.format().frameSize
            val expected = bytes.copyOfRange(0, 2400 * frame) + bytes.copyOfRange(9600 * frame, bytes.size)
            assertArrayEquals(expected, payload(app.repository.file(joined)))
            val secondEdit = CutWindow(recordingId = joined.id, startMs = 200, endMs = 500, title = "Edited joined take")
            runBlocking { app.repository.dao.window(secondEdit) }
            awaitJob(ExportWorker.enqueue(app, joined.id, listOf(secondEdit)))
            val recut = runBlocking { app.repository.dao.observeAll().first().single { it.sourceId == joined.id } }
            assertEquals(300L, recut.durationMs)
            assertArrayEquals(expected.copyOfRange(1600 * frame, 4000 * frame), payload(app.repository.file(recut)))
            assertArrayEquals(original, app.repository.file(source).readBytes())
        } finally {
            remaining?.let { cleanup(it) }
            cleanup(source)
            runBlocking { app.repository.dao.deleteFolder(folder.id) }
        }
    }

    @Test fun pitchPreservingSpeedKeepsBothStereoFrequenciesForEveryPcmEncoding() {
        val root = File(app.cacheDir, "pitch-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            for (encoding in PcmEncoding.entries) {
                val source = File(root, "${encoding.name}-source.wav")
                val values = ByteArray(32000 * 2 * encoding.bytes)
                repeat(32000) { frame -> repeat(2) { channel ->
                    val value = 0.25 * sin(2 * PI * (if (channel == 0) 400 else 800) * frame / 16000)
                    val bits = when (encoding) {
                        PcmEncoding.FLOAT32 -> value.toFloat().toRawBits().toLong()
                        PcmEncoding.PCM16 -> (value * 32768).roundToLong()
                        PcmEncoding.PCM24 -> (value * 8388608).roundToLong()
                        PcmEncoding.PCM32 -> (value * 2147483648.0).roundToLong()
                    }
                    repeat(encoding.bytes) { byte -> values[(frame * 2 + channel) * encoding.bytes + byte] = (bits shr (8 * byte)).toByte() }
                } }
                WavFile.header(source, 16000, 2, 0, encoding); source.appendBytes(values)
                WavFile.header(source, 16000, 2, values.size.toLong(), encoding)
                for (speed in listOf(0.5, 2.0)) {
                    val result = File(root, "${encoding.name}-$speed.wav")
                    WindowRenderer.renderWindow(source, result, CutWindow(recordingId = "test", startMs = 0, endMs = 2000,
                        reverse = true, speed = speed, preservePitch = true))
                    val info = WavFile.info(result)
                    assertEquals(encoding, info.encoding); assertEquals(2, info.channels)
                    assertEquals(2000.0 / speed, info.durationMs.toDouble(), 30.0)
                    for (channel in 0..1) assertEquals(if (channel == 0) 400.0 else 800.0, frequency(result, channel), 5.0)
                }
                val varispeed = File(root, "${encoding.name}-varispeed.wav")
                WindowRenderer.renderWindow(source, varispeed, CutWindow(recordingId = "test", startMs = 0, endMs = 2000, speed = 2.0))
                assertEquals(800.0, frequency(varispeed, 0), 5.0)
                assertEquals(1600.0, frequency(varispeed, 1), 5.0)
            }
        } finally { root.listFiles()?.forEach { it.delete() }; root.delete() }
    }

    private fun frequency(file: File, channel: Int): Double {
        val info = WavFile.info(file)
        val bytes = payload(file)
        fun negative(frame: Int): Boolean {
            val offset = frame * info.frameSize + channel * info.encoding.bytes
            if (info.encoding == PcmEncoding.FLOAT32) return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float < 0f
            return bytes[offset + info.encoding.bytes - 1] < 0
        }
        val first = info.rate / 10
        val last = bytes.size / info.frameSize - first
        var crossings = 0
        for (frame in first + 1 until last) if (negative(frame - 1) && !negative(frame)) crossings++
        return crossings.toDouble() * info.rate / (last - first)
    }
}
