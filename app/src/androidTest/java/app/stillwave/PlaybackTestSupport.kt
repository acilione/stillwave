package app.stillwave

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.media.session.MediaSession
import androidx.core.os.BundleCompat
import androidx.lifecycle.ViewModelProvider
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.stillwave.audio.PlaybackService
import app.stillwave.audio.RecordingService
import app.stillwave.audio.WavFile
import app.stillwave.data.Recording
import app.stillwave.data.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.google.common.util.concurrent.ListenableFuture

internal fun playbackAwait(timeoutMs: Long = 15_000, predicate: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (!predicate()) { check(System.nanoTime() < deadline) { "Playback condition timed out" }; Thread.sleep(25) }
}
internal fun <T> playbackMain(block: () -> T): T {
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
    return checkNotNull(result).getOrThrow()
}
internal fun playbackShell(command: String) {
    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
}

/** Every test owns its UUID fixture; never clears an existing library or changes microphone permissions. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun withPlaybackFixture(stopWhenLocked: Boolean, block: (StillwaveApp, ActivityScenario<MainActivity>, AppViewModel, Recording, MediaController) -> Unit) {
    val app = ApplicationProvider.getApplicationContext<StillwaveApp>()
    check(!RecordingService.state.value.active) { "Do not run playback tests during a recording" }
    val previous = runBlocking { app.ready.await(); app.settings.flow.first().stopPlaybackWhenLocked }
    val id = UUID.randomUUID().toString()
    val file = File(app.recordingsDir, "$id.wav")
    val recording = Recording(id = id, title = "Playback test $id", createdAt = System.currentTimeMillis(),
        durationMs = 180_000, fileName = file.name, sampleRate = 16000, channels = 1)
    var activity: ActivityScenario<MainActivity>? = null
    var pending: ListenableFuture<MediaController>? = null
    try {
        runBlocking { app.settings.set(Settings.STOP_PLAYBACK_WHEN_LOCKED, stopWhenLocked) }
        WavFile.header(file, 16000, 1, 5_760_000)
        // Insert test-only fixtures directly, bypassing the normal save path.
        runBlocking { app.repository.dao.insert(recording) }
        activity = ActivityScenario.launch(MainActivity::class.java)
        lateinit var vm: AppViewModel
        activity.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
        val connection = playbackMain { MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
        pending = connection
        val controller = connection.get(15, TimeUnit.SECONDS)
        block(app, activity, vm, recording, controller)
    } finally {
        playbackMain {
            PlaybackService.clearRecordings(listOf(id))
            pending?.let(MediaController::releaseFuture)
        }
        activity?.close()
        runBlocking {
            app.settings.set(Settings.STOP_PLAYBACK_WHEN_LOCKED, previous)
            app.repository.delete(listOf(id))
        }
        file.delete()
    }
}

internal fun nativePlaybackController(app: StillwaveApp): android.media.session.MediaController {
    var token: MediaSession.Token? = null
    playbackAwait {
        token = app.getSystemService(NotificationManager::class.java).activeNotifications.firstNotNullOfOrNull {
            if (it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE == 0) null
            else BundleCompat.getParcelable(it.notification.extras, Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        }
        token != null
    }
    return android.media.session.MediaController(app, checkNotNull(token))
}
