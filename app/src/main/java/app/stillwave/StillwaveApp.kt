package app.stillwave

import android.app.Application
import androidx.room.Room
import app.stillwave.data.*
import app.stillwave.audio.RecordingService
import app.stillwave.widget.RecorderWidget
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class StillwaveApp : Application() {
    val ready = CompletableDeferred<Unit>()
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy { Room.databaseBuilder(this, RecordingDatabase::class.java, "stillwave.db").addMigrations(RecordingDatabase.MIGRATION_1_2, RecordingDatabase.MIGRATION_2_3, RecordingDatabase.MIGRATION_3_4, RecordingDatabase.MIGRATION_4_5).build() }
    val settings by lazy { Settings(this) }
    val microphoneProfiles by lazy { app.stillwave.audio.MicrophoneProfiles(this) }
    val recordingsDir by lazy { File(filesDir, "recordings").apply { mkdirs() } }
    val repository by lazy { RecordingRepository(this) }
    val multitrackStore by lazy { MultitrackStore(File(filesDir, "projects")) }
    override fun onCreate() {
        super.onCreate()
        appScope.launch { multitrackStore.load() }
        appScope.launch { try { repository.recoverPendingDeletions(); repository.recoverInterruptedRecording() } finally { ready.complete(Unit) } }
        appScope.launch {
            // The widget's native Chronometer ticks in the launcher; no polling or waveform updates.
            combine(RecordingService.state.map { it.copy(elapsedMs = 0, amplitude = 0f) }.distinctUntilChanged(),
                settings.flow.map { it.recordingSlots }.distinctUntilChanged()) { _, slots -> slots }.collect { slots ->
                RecorderWidget.updateAll(this@StillwaveApp, RecordingService.state.value, slots)
            }
        }
    }
}
