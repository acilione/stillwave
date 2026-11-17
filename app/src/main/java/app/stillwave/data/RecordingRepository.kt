package app.stillwave.data

import app.stillwave.StillwaveApp
import app.stillwave.audio.WavFile
import app.stillwave.audio.PcmEncoding
import app.stillwave.audio.CaptureJournal
import app.stillwave.audio.EditPreviewCache
import app.stillwave.audio.EditorAudioSource
import app.stillwave.audio.MultitrackAudio
import androidx.room.withTransaction
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.work.WorkManager

class RecordingRepository(private val app: StillwaveApp) {
    private val fileLocks = Array(64) { Mutex() }
    private fun fileLock(id: String) = fileLocks[(id.hashCode() and Int.MAX_VALUE) % fileLocks.size]
    val dao get() = app.database.recordings()
    fun file(recording: Recording) = File(app.recordingsDir, recording.fileName)
    suspend fun <T> withRecording(id: String, action: suspend (Recording) -> T): T = fileLock(id).withLock {
        check(!deletionPending(id)) { "Recording is being deleted" }
        action(checkNotNull(dao.get(id)) { "Recording no longer exists" })
    }
    /** Acquire each shared lock once in a stable order, including colliding recording IDs. */
    suspend fun <T> withRecordings(ids: List<String>, action: suspend (List<Recording>) -> T): T {
        val unique = ids.distinct()
        val buckets = unique.map { (it.hashCode() and Int.MAX_VALUE) % fileLocks.size }.distinct().sorted()
        suspend fun acquire(index: Int): T {
            if (index < buckets.size) return fileLocks[buckets[index]].withLock { acquire(index + 1) }
            val recordings = unique.map { id ->
                check(!deletionPending(id)) { "A project recording is being deleted" }
                checkNotNull(dao.get(id)) { "A project recording no longer exists" }
            }
            return action(recordings)
        }
        return acquire(0)
    }
    private fun deletionPending(id: String): Boolean {
        val journal = DeletionJournal.file(app.recordingsDir, id)
        return journal.exists() || File(journal.path + ".bak").exists()
    }
    data class DeleteResult(val deleted: Int, val failed: Int)
    suspend fun delete(ids: List<String>): DeleteResult = withContext(Dispatchers.IO) {
        app.ready.await()
        var deleted = 0
        var failed = 0
        val work = WorkManager.getInstance(app)
        for (id in ids.distinct()) {
            try {
                // Cancel readers before waiting for their file lock; existing completed cuts are independent.
                val exports = work.getWorkInfosByTag("export-source-$id").get()
                work.cancelAllWorkByTag("export-source-$id").result.get()
                fileLock(id).withLock {
                    val recording = dao.get(id)
                    if (recording != null) {
                        val journal = DeletionJournal.write(app.recordingsDir, recording)
                        completeDeletion(journal)
                        deleted++
                    } else if (deletionPending(id)) completeDeletion(DeletionJournal.file(app.recordingsDir, id))
                    exports.forEach { File(app.filesDir, "exports/${it.id}.json").delete() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed++ }
        }
        DeleteResult(deleted, failed)
    }
    private suspend fun completeDeletion(journal: File) {
        val (id, name) = DeletionJournal.read(journal)
        check(journal.canonicalFile == DeletionJournal.file(app.recordingsDir, id).canonicalFile)
        val source = DeletionJournal.audioFile(app.recordingsDir, name)
        val recording = dao.get(id)
        check(recording == null || recording.fileName == name) { "Recording changed while deleting" }
        app.database.withTransaction { dao.delete(id) } // Room cascades checkpoints/windows, not exported recordings.
        EditPreviewCache.deleteForRecording(app, id)
        MultitrackAudio.deleteForRecording(app, id)
        EditorAudioSource.deleteForRecording(app, id)
        val pending = File(app.recordingsDir, "$id.pending")
        if (pending.exists() || File(pending.path + ".bak").exists()) {
            check(CaptureJournal.read(pending).getString("id") == id) { "Capture cleanup does not match recording" }
            // A published AAC file can still have its PCM staging file after interrupted cleanup.
            for (name in listOf("$id.wav", "$id.m4a", "$id.encoding")) {
                val companion = File(app.recordingsDir, name)
                if (companion.canonicalFile != source) check(!companion.exists() || companion.delete()) { "Could not remove temporary capture" }
            }
        }
        CaptureJournal.delete(pending)
        check(!source.exists() || source.delete()) { "Could not remove audio file" }
        DeletionJournal.delete(journal)
    }
    suspend fun recoverPendingDeletions() {
        app.recordingsDir.listFiles { f -> f.name.endsWith(".deleting") || f.name.endsWith(".deleting.bak") }
            ?.map { File(it.parentFile, it.name.removeSuffix(".bak")) }?.distinct()?.forEach { journal ->
                try {
                    val (id, _) = DeletionJournal.read(journal)
                    fileLock(id).withLock { completeDeletion(journal) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep the durable intent and retry on the next launch. */ }
            }
    }
    suspend fun add(recording: Recording, checkpoints: List<Checkpoint> = emptyList()) {
        app.database.withTransaction {
            // A folder can be removed while recording/exporting. Keep the audio in Unfiled.
            val folder = recording.folderId?.takeIf { dao.folder(it) != null }
            dao.insert(recording.copy(folderId = folder))
            checkpoints.forEach { dao.checkpoint(it) }
        }
    }
    suspend fun recoverInterruptedRecording() {
        suspend fun restoreProjectTake(recording: Recording, journal: org.json.JSONObject) {
            val projectId = journal.optString("projectId").takeIf { it.isNotBlank() && !journal.isNull("projectId") } ?: return
            // False means the project was deleted. Errors leave the journal for a later retry.
            app.multitrackStore.addRecordedTake(projectId, recording, journal.optLong("projectOffsetMs", 0), journal.optBoolean("projectLoop", false))
        }
        // A journal is written before sample bytes reach disk, and removed only after save/discard.
        app.recordingsDir.listFiles { f -> f.name.endsWith(".pending") || f.name.endsWith(".pending.bak") }
            ?.map { File(it.parentFile, it.name.removeSuffix(".bak")) }?.distinct()?.forEach { journal ->
            runCatching {
                val j = CaptureJournal.read(journal)
                val id = j.getString("id")
                if (deletionPending(id)) return@runCatching
                val source = File(app.recordingsDir, "$id.wav")
                // Saving AAC can publish its Room row before its temporary WAV/journal are removed.
                // Preserve the published container and finish only that capture's cleanup.
                val published = dao.get(id)
                if (published != null && file(published).isFile) {
                    restoreProjectTake(published, j)
                    if (published.fileName != source.name) check(!source.exists() || source.delete()) { "Could not remove temporary PCM" }
                    val encoded = File(app.recordingsDir, "$id.m4a")
                    if (published.fileName != encoded.name) check(!encoded.exists() || encoded.delete()) { "Could not remove unfinished AAC" }
                    val partial = File(app.recordingsDir, "$id.encoding")
                    check(!partial.exists() || partial.delete()) { "Could not remove unfinished AAC" }
                    CaptureJournal.delete(journal)
                    return@runCatching
                }
                val channels = j.getInt("channels")
                val rate = j.getInt("rate")
                val encoding = PcmEncoding.valueOf(j.optString("encoding", "PCM16"))
                val frameSize = channels * encoding.bytes
                val count = (source.length() - WavFile.headerSize(encoding)).coerceAtLeast(0) / frameSize * frameSize
                if (count > 0) {
                    WavFile.header(source, rate, channels, count, encoding)
                    val created = j.getLong("createdAt")
                    val duration = WavFile.info(source).durationMs
                    val details = j.optString("captureDetails").takeIf { it.isNotBlank() }
                    val recoveredDetails = if (j.optString("requestedQuality").startsWith("AAC_"))
                        listOfNotNull(details, "Interrupted AAC capture recovered as lossless WAV.").joinToString("\n") else details
                    val recording = Recording(id = id, title = defaultTitle(created), createdAt = created, durationMs = duration,
                        fileName = source.name, sampleRate = rate, channels = channels, pcmEncoding = encoding.name,
                        inputName = j.optString("inputName").takeIf { it.isNotBlank() }, captureDetails = recoveredDetails,
                        folderId = j.optString("folderId").takeIf { it.isNotBlank() })
                    add(recording, CaptureJournal.points(j, duration))
                    restoreProjectTake(recording, j)
                } else source.delete()
                // A stopped AAC conversion still has its recoverable PCM. Prefer that complete
                // capture over an unpublished encoded file; the journal is removed only after cleanup.
                for (name in listOf("$id.encoding", "$id.m4a")) {
                    val unfinished = File(app.recordingsDir, name)
                    check(!unfinished.exists() || unfinished.delete()) { "Could not remove unfinished AAC" }
                }
                CaptureJournal.delete(journal)
            }
        }
    }
}
