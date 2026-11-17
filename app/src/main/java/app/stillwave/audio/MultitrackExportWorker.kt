package app.stillwave.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.stillwave.R
import app.stillwave.StillwaveApp
import app.stillwave.data.MultitrackJson
import app.stillwave.data.MultitrackProject
import app.stillwave.data.Recording
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject

/** Durable project snapshot; retries always publish the same output UUID. */
class MultitrackExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        /** Call off the main thread, as for window exports. */
        fun enqueue(app: StillwaveApp, project: MultitrackProject, title: String = project.title): UUID {
            MultitrackAudio.validate(project)
            val name = title.trim()
            require(name.isNotEmpty() && name.length <= 160) { "Enter a recording name of 1 to 160 characters" }
            val jobId = UUID.randomUUID()
            val outputId = UUID.randomUUID().toString()
            val directory = File(app.filesDir, "exports").apply { check(isDirectory || mkdirs()) { "Could not create export storage" } }
            val snapshot = AtomicFile(File(directory, "$jobId.json"))
            val stream = snapshot.startWrite()
            try {
                stream.write(JSONObject().put("project", MultitrackJson.encode(project)).put("title", name)
                    .put("outputId", outputId).put("createdAt", System.currentTimeMillis()).toString().toByteArray(Charsets.UTF_8))
                snapshot.finishWrite(stream)
            } catch (error: Throwable) { snapshot.failWrite(stream); throw error }
            try {
                val request = OneTimeWorkRequestBuilder<MultitrackExportWorker>().setId(jobId)
                    .setInputData(workDataOf("job" to jobId.toString(), "outputId" to outputId)).addTag("exports").addTag("multitrack-exports")
                    .addTag("export-project-${project.id}")
                project.tracks.map { it.recordingId }.distinct().forEach { request.addTag("export-source-$it") }
                WorkManager.getInstance(app).enqueue(request.build())
            } catch (error: Throwable) { snapshot.delete(); throw error }
            return jobId
        }
    }

    // A distinct notification prevents concurrent jobs from replacing each other's cancellation button.
    private val notificationId = 10_000 + (id.hashCode() and 0x0fff_ffff)
    private var progress = 0

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as StillwaveApp
        var snapshot: AtomicFile? = null
        var outputId: String? = null
        var output: File? = null
        var temporary: File? = null
        try {
            val job = UUID.fromString(inputData.getString("job"))
            require(job == id) { "Invalid export job" }
            snapshot = AtomicFile(File(app.filesDir, "exports/$job.json"))
            setForeground(getForegroundInfo())
            val recordingId = UUID.fromString(inputData.getString("outputId")).toString()
            outputId = recordingId
            app.ready.await()
            // This also covers death after deleting the completed snapshot but before WorkManager's acknowledgement.
            if (app.repository.dao.get(recordingId) != null) {
                snapshot.delete()
                return@withContext Result.success(workDataOf("count" to 1, "recordingId" to recordingId))
            }
            val document = snapshot.openRead().use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(bytes.size() + count <= 256 * 1024) { "Project export snapshot is too large" }
                    bytes.write(buffer, 0, count)
                }
                JSONObject(bytes.toByteArray().toString(Charsets.UTF_8))
            }
            val project = MultitrackJson.decode(document.getJSONObject("project"))
            MultitrackAudio.validate(project)
            val title = document.getString("title").trim()
            require(title.isNotEmpty() && title.length <= 160) { "Invalid recording name" }
            require(document.getString("outputId") == recordingId) { "Export snapshot does not match its destination" }
            val destination = File(app.recordingsDir, "$recordingId.wav")
            val pending = File(app.recordingsDir, "$recordingId.exporting")
            output = destination
            temporary = pending
            pending.delete()
            MultitrackAudio.checkSpace(app.recordingsDir, project)
            val coroutine = currentCoroutineContext()
            var lastProgress = 0L
            val rendered = MultitrackAudio.withSources(app, project) { inputs ->
                val result = MultitrackRenderer.render(inputs, pending, project.durationMs,
                    checkCancelled = { coroutine.ensureActive() }, onProgress = { fraction ->
                        val percent = (fraction * 100).toInt().coerceIn(0, 100)
                        val now = SystemClock.elapsedRealtime()
                        if (percent != progress && (now - lastProgress >= 750 || percent == 100)) {
                            progress = percent
                            lastProgress = now
                            setProgressAsync(workDataOf("progress" to percent, "done" to 0, "total" to 1))
                            applicationContext.getSystemService(NotificationManager::class.java).notify(notificationId, notification())
                        }
                    })
                coroutine.ensureActive()
                withContext(NonCancellable) {
                    // Only this job can own this unpublished UUID, including after an interrupted prior run.
                    if (destination.exists()) check(destination.delete()) { "Could not replace interrupted mix output" }
                    check(pending.renameTo(destination)) { "Could not publish mixed audio" }
                    val info = WavFile.info(destination)
                    app.repository.add(Recording(id = recordingId, title = title, createdAt = document.getLong("createdAt"),
                        durationMs = info.durationMs, fileName = destination.name, sampleRate = info.rate, channels = info.channels,
                        pcmEncoding = info.encoding.name, captureDetails = "Multitrack mix: ${project.lanes.size} tracks, ${project.tracks.size} clips. Mix gain: ${result.appliedGain}."))
                }
                result
            }
            snapshot.delete()
            Result.success(workDataOf("count" to 1, "recordingId" to recordingId, "peak" to rendered.peak, "appliedGain" to rendered.appliedGain))
        } catch (cancelled: CancellationException) {
            // Keep the snapshot for a WorkManager restart; only a complete published library file survives.
            if (Build.VERSION.SDK_INT >= 31 && stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) snapshot?.delete()
            throw cancelled
        } catch (error: Exception) {
            val saved = outputId?.let { app.repository.dao.get(it) }
            snapshot?.delete()
            if (saved != null) Result.success(workDataOf("count" to 1, "recordingId" to saved.id))
            else {
                output?.delete()
                Result.failure(workDataOf("error" to (error.message ?: "Could not save the project mix")))
            }
        } finally { temporary?.delete() }
    }

    private fun notification(): Notification = NotificationCompat.Builder(applicationContext, "exports")
        .setSmallIcon(R.drawable.ic_wave).setContentTitle("Saving project mix")
        .setContentText("$progress%").setProgress(100, progress, false).setOnlyAlertOnce(true).setOngoing(true)
        .addAction(0, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("exports", "Audio exports", NotificationManager.IMPORTANCE_LOW))
        return ForegroundInfo(notificationId, notification(), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }
}
