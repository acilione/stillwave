package app.stillwave.audio

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.stillwave.*
import app.stillwave.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.nio.ByteBuffer

data class CaptureState(val active: Boolean = false, val paused: Boolean = false, val finishing: Boolean = false,
    val elapsedMs: Long = 0, val amplitude: Float = 0f, val quality: Quality = Quality.BALANCED, val error: String? = null,
    val preparing: Boolean = false, val format: PcmFormat? = null, val inputName: String? = null, val captureDetails: String? = null,
    val sessionId: String = "", val checkpointCount: Int = 0, val lastCheckpointMs: Long? = null, val discardConfirmation: Boolean = false,
    val projectId: String? = null)

class RecordingService : Service() {
    companion object {
        const val START = "start"; const val PAUSE = "pause"; const val RESUME = "resume"; const val SAVE = "save"; const val DISCARD = "discard"
        const val CHECKPOINT = "checkpoint"
        const val REQUEST_DISCARD = "request_discard"; const val CANCEL_DISCARD = "cancel_discard"; const val CONFIRM_DISCARD = "confirm_discard"
        const val SESSION = "session"
        const val QUALITY = "quality"
        const val FOLDER = "folder"
        const val PROJECT = "project"
        private val mutable = MutableStateFlow(CaptureState())
        val state = mutable.asStateFlow()
        private val savedRecording = MutableStateFlow<String?>(null)
        val lastSavedRecordingId = savedRecording.asStateFlow()
        fun command(context: Context, action: String, quality: Quality = Quality.BEST, folderId: String? = null, projectId: String? = null) {
            val intent = Intent(context, RecordingService::class.java).setAction(action).putExtra(QUALITY, quality.name).putExtra(FOLDER, folderId).putExtra(PROJECT, projectId)
            if (action == START) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
        }
    }
    private val app get() = application as StillwaveApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Mutex()
    private val captureLock = Any()
    private var audio: AudioRecord? = null
    private var writer: Job? = null
    private var ticker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var file: File? = null
    private var journal: File? = null
    private var id = ""
    private var createdAt = 0L
    private var quality = Quality.BALANCED
    private var folderId: String? = null
    private var projectId: String? = null
    private var projectOffsetMs = 0L
    private var projectLoop = false
    private var projectTargetBytes: Long? = null
    private var format: PcmFormat? = null
    private var inputName: String? = null
    private var captureDetails: String? = null
    @Volatile private var preparing = false
    @Volatile private var checkpoints = emptyList<Checkpoint>()
    @Volatile private var discardConfirmation = false
    @Volatile private var paused = false
    @Volatile private var stopping = false
    @Volatile private var written = 0L
    @Volatile private var peak = 0f

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("capture", "Recording", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        // Promote synchronously to satisfy the foreground service startup deadline.
        if (action == START && !mutable.value.active) {
            // A later discarded capture must not replay a previous save in a newly opened Activity.
            savedRecording.value = null
            try {
                ServiceCompat.startForeground(this, 1, notification(false, allowPause = intent.getStringExtra(PROJECT) == null), if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            } catch (error: Exception) { mutable.value = CaptureState(error = error.message ?: "Microphone unavailable"); stopSelf(); return START_NOT_STICKY }
        }
        scope.launch { commands.withLock {
            try {
                if (action != START && (!mutable.value.active || intent.hasExtra(SESSION) && intent.getStringExtra(SESSION) != id)) {
                    if (!mutable.value.active) stopSelfResult(startId)
                    return@withLock
                }
                when (action) {
                    START -> if (!mutable.value.active) begin(intent.getStringExtra(QUALITY)?.let(Quality::valueOf) ?: Quality.BEST, intent.getStringExtra(FOLDER), intent.getStringExtra(PROJECT))
                    PAUSE -> if (projectId == null && audio != null && !paused && !stopping) {
                        // Wait for any in-flight disk write before publishing the paused duration.
                        synchronized(captureLock) { paused = true }
                        audio?.stop(); wakeLock?.let { if (it.isHeld) it.release() }; publish()
                        getSystemService(NotificationManager::class.java).notify(1, notification(true))
                    }
                    RESUME -> if (projectId == null && audio != null && paused && !stopping) {
                        audio?.startRecording(); paused = false; acquireWakeLock(); publish()
                        getSystemService(NotificationManager::class.java).notify(1, notification(false))
                    }
                    SAVE -> finish(true)
                    DISCARD -> finish(false)
                    CHECKPOINT -> if (!stopping) {
                        val point = Checkpoint(recordingId = id, positionMs = elapsed(), label = "Checkpoint ${checkpoints.size + 1}")
                        val next = checkpoints + point
                        writeJournal(next)
                        checkpoints = next; publish()
                    }
                    REQUEST_DISCARD -> if (!stopping) {
                        if (app.settings.flow.first().confirmDiscard) { discardConfirmation = true; publish() }
                        else finish(false)
                    }
                    CANCEL_DISCARD -> { discardConfirmation = false; publish() }
                    CONFIRM_DISCARD -> if (discardConfirmation) finish(false)
                }
            } catch (error: Exception) {
                if (mutable.value.active) runCatching { finish(audio != null) }
                mutable.value = CaptureState(error = error.message ?: "Recording failed")
                stopSelf()
            }
        } }
        return START_NOT_STICKY
    }
    @Suppress("MissingPermission")
    private suspend fun begin(q: Quality, destinationFolder: String?, destinationProject: String?) {
        val startedAt = SystemClock.elapsedRealtime()
        fun timing(stage: String) {
            if (BuildConfig.DEBUG) android.util.Log.d("CaptureStartup", "${q.name} $stage: ${SystemClock.elapsedRealtime() - startedAt} ms")
        }
        app.ready.await()
        timing("recovery complete")
        check(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone permission is required" }
        val settings = app.settings.flow.first()
        val preferred = MicrophoneInputs.resolve(this, settings.selectedMicrophone)
        if (!settings.allowPlaybackWhileRecording) withContext(Dispatchers.Main) { PlaybackService.pauseForRecording() }
        val project = destinationProject?.let { requested ->
            checkNotNull(app.multitrackStore.load().firstOrNull { it.id == requested }) { "Project no longer exists or could not be read" }
                .also { check(it.canAddTrack) { "This project has no free track slots" } }
        }
        quality = q; id = UUID.randomUUID().toString(); folderId = destinationFolder
        projectId = project?.id; projectLoop = project?.loopPlayback == true; projectOffsetMs = 0; projectTargetBytes = null
        written = 0; paused = false; stopping = false; checkpoints = emptyList(); discardConfirmation = false
        format = null; inputName = null; captureDetails = null; file = null; journal = null; preparing = true
        publish()
        // getAllocatableBytes includes reclaimable cache accounting and can take seconds.
        // Check currently usable space; the capture writer also handles subsequent I/O failures.
        check(app.recordingsDir.usableSpace > 10 * 1024 * 1024) { "Not enough storage to start recording" }
        timing("storage checked")
        acquireWakeLock()
        val capture = CaptureNegotiator(this).open(q, preferred)
        timing("microphone opened")
        val record = capture.record
        audio = record; format = capture.format; inputName = capture.inputName; captureDetails = capture.details
        val pcm = capture.format
        if (project != null) {
            val position = withContext(Dispatchers.Main) { PlaybackService.projectPosition(project.id) }
            val firstBufferMs = capture.firstBytes.size.toLong() / pcm.frameSize * 1000 / pcm.rate
            projectOffsetMs = projectCaptureOffset(position?.positionMs, position?.playing == true, firstBufferMs, project.durationMs, projectLoop)
            if (projectLoop) projectTargetBytes = Math.multiplyExact(projectCaptureFrames(project.durationMs, pcm.rate), pcm.frameSize.toLong())
        }
        createdAt = System.currentTimeMillis()
        val destination = File(app.recordingsDir, "$id.wav"); file = destination
        journal = File(app.recordingsDir, "$id.pending")
        writeJournal(checkpoints)
        WavFile.header(destination, pcm.rate, pcm.channels, 0, pcm.encoding)
        timing("file prepared")
        if ((q == Quality.BEST || preferred != null) && capture.device != null) {
            record.addOnRoutingChangedListener({ routing ->
                val current = routing.routedDevice
                val changed = if (preferred != null) current?.id != preferred.id else current != null && current.id != capture.device.id
                if (changed && !paused && audio === record && !stopping) {
                    saveAutomatically(record, "Microphone changed. Recording saved; start again to check the new input.")
                }
            }, Handler(Looper.getMainLooper()))
        }
        preparing = false
        publish()
        getSystemService(NotificationManager::class.java).notify(1, notification(false))
        writer = scope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            try {
                val byteLimit = projectTargetBytes
                var loopComplete = false
                RandomAccessFile(destination, "rw").use { out ->
                    out.seek(WavFile.headerSize(pcm.encoding))
                    fun write(bytes: ByteArray, count: Int): Boolean = synchronized(captureLock) {
                        if (!paused && !stopping) {
                            val allowed = minOf(count.toLong(), byteLimit?.minus(written) ?: count.toLong()).coerceAtLeast(0).toInt()
                            check(written + allowed <= WavFile.MAX_DATA_BYTES - 4096) { "WAV size limit reached; recording saved" }
                            if (allowed > 0) { out.write(bytes, 0, allowed); written += allowed; peak = pcmPeak(bytes, allowed, pcm.encoding) }
                        }
                        byteLimit != null && written >= byteLimit
                    }
                    loopComplete = write(capture.firstBytes, capture.firstBytes.size)
                    timing("first audio written")
                    val buffer = ByteBuffer.allocateDirect(pcm.rate / 50 * pcm.frameSize)
                    val bytes = ByteArray(buffer.capacity())
                    while (isActive && !stopping && !loopComplete) {
                        if (paused) { delay(30); continue }
                        buffer.clear()
                        val n = record.read(buffer, buffer.capacity(), AudioRecord.READ_BLOCKING)
                        if (paused || stopping) continue
                        check(n >= 0) { "Microphone disconnected (code $n)" }
                        if (n == 0) continue
                        check(n % pcm.frameSize == 0) { "Incomplete microphone sample frame" }
                        buffer.position(0); buffer.get(bytes, 0, n)
                        loopComplete = write(bytes, n)
                    }
                    out.fd.sync()
                }
                if (loopComplete) saveAutomatically(record)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                saveAutomatically(record, error.message)
            } finally { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT) }
        }
        ticker = scope.launch { while (isActive) { publish(); delay(100) } }
    }
    /** A sibling job can join the writer safely; a failed save remains recoverable from its journal. */
    private fun saveAutomatically(record: AudioRecord, notice: String? = null) {
        scope.launch { commands.withLock {
            if (audio !== record || stopping) return@withLock
            try {
                finish(true)
                if (notice != null) mutable.update { it.copy(error = listOfNotNull(it.error, notice).joinToString("\n")) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.value = CaptureState(error = "Recording interrupted. Saved audio will be recovered when the app restarts. ${error.message.orEmpty()}")
                stopSelf()
            }
        } }
    }
    private fun acquireWakeLock() {
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Stillwave:Recording")
        if (wakeLock?.isHeld != true) wakeLock?.acquire(12 * 60 * 60 * 1000L)
    }
    private fun writeJournal(points: List<Checkpoint>) {
        val pcm = checkNotNull(format)
        CaptureJournal.write(checkNotNull(journal), id, createdAt, pcm.rate, pcm.channels, points, pcm.encoding, inputName, captureDetails, folderId, quality.name,
            projectId, projectOffsetMs, projectLoop)
    }
    private fun elapsed() = format?.let { written / it.frameSize * 1000 / it.rate } ?: 0L
    private fun publish() {
        val points = checkpoints
        mutable.value = CaptureState(active = true, paused = paused, finishing = stopping,
            preparing = preparing, format = format, inputName = inputName, captureDetails = captureDetails,
            elapsedMs = elapsed(), amplitude = if (paused) 0f else peak, quality = quality, sessionId = id,
            checkpointCount = points.size, lastCheckpointMs = points.lastOrNull()?.positionMs, discardConfirmation = discardConfirmation, projectId = projectId)
    }
    private suspend fun finish(save: Boolean) {
        if (stopping || !mutable.value.active) return
        stopping = true; publish()
        runCatching { audio?.stop() }
        writer?.cancelAndJoin(); ticker?.cancelAndJoin()
        audio?.release(); audio = null
        val source = file
        var savedId: String? = null
        var saveNotice: String? = null
        var cleanupComplete = true
        if (save && source != null && written > 0) {
            val pcm = checkNotNull(format)
            WavFile.header(source, pcm.rate, pcm.channels, written, pcm.encoding)
            var output = source
            var details = captureDetails
            var duration = WavFile.info(source).durationMs
            if (quality.isAac) {
                acquireWakeLock()
                getSystemService(NotificationManager::class.java).notify(1, NotificationCompat.Builder(this, "capture")
                    .setSmallIcon(R.drawable.ic_wave).setContentTitle("Saving AAC recording").setContentText("Finishing audio conversion")
                    .setOngoing(true).setSilent(true).build())
                val temp = File(app.recordingsDir, "$id.encoding")
                val encoded = File(app.recordingsDir, "$id.m4a")
                try {
                    val estimated = duration * quality.bitRate / 8000 + 1024 * 1024
                    check(app.recordingsDir.usableSpace > estimated) { "Not enough storage for AAC conversion" }
                    val context = currentCoroutineContext()
                    AacFile.encode(source, temp, checkNotNull(quality.aacBitRate)) { context.ensureActive() }
                    val encodedInfo = AacFile.info(temp)
                    check(temp.renameTo(encoded)) { "Could not publish AAC recording" }
                    output = encoded; duration = encodedInfo.durationMs
                    details = "${details.orEmpty()}\nSaved AAC-LC / M4A at ${quality.bitRateLabel} target bitrate."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    saveNotice = "AAC conversion failed; your recording was saved as WAV. ${error.message.orEmpty()}"
                    details = "${details.orEmpty()}\nAAC conversion failed. Saved lossless WAV: ${pcm.label}."
                } finally { temp.delete() }
            }
            val recording = Recording(id = id, title = defaultTitle(createdAt), createdAt = createdAt, durationMs = duration,
                fileName = output.name, sampleRate = pcm.rate, channels = pcm.channels,
                pcmEncoding = pcm.encoding.name, inputName = inputName, captureDetails = details, folderId = folderId)
            app.repository.add(recording, checkpoints.filter { it.positionMs <= duration })
            savedId = id
            projectId?.let { destination ->
                try {
                    if (!app.multitrackStore.addRecordedTake(destination, recording, projectOffsetMs, projectLoop))
                        saveNotice = "Recording saved to the library. Its project was deleted."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    // The library row and journal remain durable; recovery retries only the attachment.
                    cleanupComplete = false
                    saveNotice = "Recording saved to the library. Adding it to the project will be retried when the app restarts. ${error.message.orEmpty()}"
                }
            }
            if (output != source) cleanupComplete = (source.delete() || !source.exists()) && cleanupComplete
        } else source?.delete()
        if (cleanupComplete) journal?.let(CaptureJournal::delete)
        wakeLock?.let { if (it.isHeld) it.release() }
        mutable.value = CaptureState(error = saveNotice)
        savedId?.let { savedRecording.value = it }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun notification(isPaused: Boolean, allowPause: Boolean = projectId == null): Notification {
        fun action(name: String) = PendingIntent.getService(this, name.hashCode(), Intent(this, RecordingService::class.java).setAction(name).putExtra(SESSION, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "capture").setSmallIcon(R.drawable.ic_wave).setContentTitle(if (isPaused) "Recording paused" else "Recording in progress")
            .setContentText("Stillwave · tap to open").setContentIntent(open).setOngoing(true).setSilent(true)
            .apply { if (allowPause) addAction(0, if (isPaused) "Resume" else "Pause", action(if (isPaused) RESUME else PAUSE)) }
            .addAction(0, "Checkpoint", action(CHECKPOINT))
            .addAction(0, "Stop & save", action(SAVE)).build()
    }
    override fun onDestroy() {
        stopping = true
        runCatching { audio?.stop() }
        scope.cancel(); audio?.release(); audio = null
        wakeLock?.let { if (it.isHeld) it.release() }
        if (mutable.value.active) mutable.value = CaptureState(error = "Recording interrupted. Audio will be recovered on next launch.")
        super.onDestroy()
    }
}
