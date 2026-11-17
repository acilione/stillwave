package app.stillwave.audio

import android.content.Context
import android.os.Build
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import app.stillwave.R
import androidx.work.*
import app.stillwave.StillwaveApp
import app.stillwave.data.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class ExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        fun enqueue(app: StillwaveApp, recordingId: String, windows: List<CutWindow>, fullAudio: Boolean = false, title: String = "", removeSelected: Boolean = false): UUID {
            require(windows.isNotEmpty()) { "Select at least one window" }
            require(!fullAudio || !removeSelected) { "Choose either effects or section removal" }
            // Snapshot edits on disk: WorkManager Data is limited to 10 KB.
            val jobId = UUID.randomUUID()
            val jobs = File(app.filesDir, "exports").apply { mkdirs() }
            val entries = JSONArray()
            windows.forEach { w -> entries.put(JSONObject().put("outputId", UUID.randomUUID().toString()).put("start", w.startMs).put("end", w.endMs)
                .put("ordinal", w.ordinal).put("title", w.title.trim()).put("reverse", w.reverse).put("speed", w.speed).put("preservePitch", w.preservePitch)) }
            File(jobs, "$jobId.json").writeText(JSONObject().put("recordingId", recordingId).put("windows", entries)
                .put("fullAudio", fullAudio).put("removeSelected", removeSelected).put("outputId", UUID.randomUUID().toString()).put("title", title.trim().take(160)).toString())
            val request = OneTimeWorkRequestBuilder<ExportWorker>().setId(jobId).setInputData(workDataOf("job" to jobId.toString())).addTag("exports").addTag("export-source-$recordingId").build()
            WorkManager.getInstance(app).enqueue(request)
            return jobId
        }
    }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as StillwaveApp
        val jobFile = File(app.filesDir, "exports/${inputData.getString("job")}.json")
        try {
            setForeground(getForegroundInfo())
            val job = JSONObject(jobFile.readText())
            app.ready.await()
            app.repository.withRecording(job.getString("recordingId")) { original ->
                val entries = job.getJSONArray("windows")
                val windows = List(entries.length()) { index ->
                    val entry = entries.getJSONObject(index)
                    CutWindow(recordingId = original.id, startMs = entry.getLong("start"), endMs = entry.getLong("end"),
                        ordinal = entry.getInt("ordinal"), title = entry.optString("title"),
                        reverse = entry.optBoolean("reverse", false), speed = entry.optDouble("speed", 1.0), preservePitch = entry.optBoolean("preservePitch", false))
                }
                require(windows.isNotEmpty() && windows.all { it.validFor(original.durationMs) }) { "Check the selected window timestamps and speed" }
                val fullAudio = job.optBoolean("fullAudio", false)
                val removeSelected = job.optBoolean("removeSelected", false)
                require(!fullAudio || !removeSelected) { "Choose either effects or section removal" }
                val joinedOutput = fullAudio || removeSelected
                if (joinedOutput) {
                    val error = if (removeSelected) WindowRenderer.removalExportError(windows, original.durationMs)
                        else WindowRenderer.fullExportError(windows, original.durationMs)
                    require(error == null) { error.orEmpty() }
                }
                val count = if (joinedOutput) 1 else windows.size
                val context = currentCoroutineContext()
                EditorAudioSource.withWav(app, original) { source ->
                for (i in 0 until count) {
                    ensureActive()
                    val window = windows[i]
                    val id = if (joinedOutput) job.getString("outputId") else entries.getJSONObject(i).getString("outputId")
                    UUID.fromString(id) // Output paths only use the job's generated UUIDs.
                    if (app.repository.dao.get(id) != null) continue // Safe to resume after process death.
                    setProgress(workDataOf("done" to i, "total" to count))
                    val output = File(app.recordingsDir, "$id.wav")
                    val temp = File(app.recordingsDir, "$id.exporting")
                    temp.delete()
                    try {
                        if (removeSelected) WindowRenderer.renderRemaining(source, temp, windows) { context.ensureActive() }
                        else if (fullAudio) WindowRenderer.renderFull(source, temp, windows) { context.ensureActive() }
                        else WindowRenderer.renderWindow(source, temp, window) { context.ensureActive() }
                        context.ensureActive()
                        withContext(NonCancellable) {
                            if (output.exists()) output.delete() // Only this job's unpublished UUID output.
                            check(temp.renameTo(output)) { "Could not publish exported audio" }
                            val title = if (joinedOutput) job.optString("title").ifBlank {
                                "${original.title} · ${if (removeSelected) "Sections removed" else "Edited"}"
                            } else window.exportTitle(original.title)
                            val info = WavFile.info(output)
                            app.repository.add(Recording(id = id, title = title, createdAt = System.currentTimeMillis(),
                                durationMs = info.durationMs, fileName = output.name, sampleRate = info.rate,
                                channels = info.channels, pcmEncoding = info.encoding.name, inputName = original.inputName, captureDetails = original.captureDetails,
                                sourceId = original.id, sourceCreatedAt = original.createdAt, folderId = original.folderId))
                        }
                    } finally { temp.delete() }
                }
                }
                jobFile.delete()
                Result.success(workDataOf("count" to count))
            }
        } catch (cancelled: CancellationException) { throw cancelled } // WorkManager can restart interrupted work.
        catch (error: Exception) { jobFile.delete(); Result.failure(workDataOf("error" to (error.message ?: "Export failed"))) }
    }
    override suspend fun getForegroundInfo(): ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("exports", "Audio exports", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "exports").setSmallIcon(R.drawable.ic_wave)
            .setContentTitle("Saving edited audio").setContentText("Your original recording is kept intact").setOngoing(true)
            .addAction(0, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return ForegroundInfo(3, notification, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }
}
