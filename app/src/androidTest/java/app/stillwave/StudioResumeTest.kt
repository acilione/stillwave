package app.stillwave

import android.content.ComponentName
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.stillwave.audio.MultitrackAudio
import app.stillwave.audio.PlaybackService
import app.stillwave.audio.RecordingService
import app.stillwave.audio.WavFile
import app.stillwave.data.MultitrackProject
import app.stillwave.data.MultitrackTrack
import app.stillwave.data.Recording
import app.stillwave.data.Settings
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A held source lock models waveform/decode work without relying on a device speed threshold. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StudioResumeTest {
    private data class Fixture(val app: StillwaveApp, val vm: AppViewModel, val source: Recording,
        val project: MultitrackProject, val player: MediaController)

    private fun fixture(block: (Fixture) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<StillwaveApp>()
        check(!RecordingService.state.value.active) { "Do not run playback tests during a recording" }
        val previous = runBlocking { app.ready.await(); app.settings.flow.first().stopPlaybackWhenLocked }
        val id = UUID.randomUUID().toString()
        val file = File(app.recordingsDir, "$id.wav")
        val source = Recording(id = id, title = "Resume fixture $id", createdAt = System.currentTimeMillis(),
            durationMs = 4000, fileName = file.name, sampleRate = 48000, channels = 1)
        val project = MultitrackProject(title = "Resume project $id", durationMs = 4000, loopPlayback = true,
            tracks = listOf(MultitrackTrack(recordingId = id, title = source.title, sourceEndMs = 4000)))
        var activity: ActivityScenario<MainActivity>? = null
        var connection: ListenableFuture<MediaController>? = null
        var vm: AppViewModel? = null
        try {
            WavFile.header(file, 48000, 1, 384_000)
            RandomAccessFile(file, "rw").use { audio ->
                audio.seek(WavFile.HEADER_SIZE)
                val samples = ByteArray(48_000 * 2) { if (it % 2 == 0) 0 else 16 } // PCM16 = 4096.
                repeat(4) { audio.write(samples) }
            }
            runBlocking {
                app.settings.set(Settings.STOP_PLAYBACK_WHEN_LOCKED, false)
                app.repository.dao.insert(source) // Insert the fixture directly, bypassing the normal save path.
                app.multitrackStore.save(project)
            }
            activity = ActivityScenario.launch(MainActivity::class.java)
            lateinit var model: AppViewModel
            activity.onActivity { model = ViewModelProvider(it)[AppViewModel::class.java]; model.studio.show(project.id) }
            vm = model
            playbackAwait { model.studio.project.value?.id == project.id }
            val future = playbackMain {
                MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
            }
            connection = future
            val player = future.get(15, TimeUnit.SECONDS)
            playbackMain { model.studio.playRendered() }
            playbackAwait(30_000) { playbackMain { player.currentMediaItem?.mediaId == project.id && player.isPlaying } && !model.studio.busy.value }
            block(Fixture(app, model, source, project, player))
        } finally {
            playbackMain {
                vm?.studio?.pause()
                PlaybackService.clearRecordings(listOf(project.id, source.id))
                connection?.let(MediaController::releaseFuture)
            }
            activity?.close()
            runBlocking {
                app.multitrackStore.delete(project.id)
                MultitrackAudio.deleteForProject(app, project.id)
                app.repository.delete(listOf(source.id))
                app.settings.set(Settings.STOP_PLAYBACK_WHEN_LOCKED, previous)
            }
            file.delete()
        }
    }

    private fun Fixture.pause() {
        playbackMain { vm.studio.pause() }
        playbackAwait { playbackMain { !player.isPlaying } && !vm.studio.busy.value }
    }

    private fun Fixture.item(): MediaItem = playbackMain { checkNotNull(player.currentMediaItem) }
    private fun MediaItem.previewId() = checkNotNull(mediaMetadata.extras?.getString(PlaybackService.PROJECT_PREVIEW_ID))

    private fun withSourceLocked(fixture: Fixture, action: () -> Unit) = runBlocking {
        val acquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            fixture.app.repository.withRecording(fixture.source.id) { acquired.complete(Unit); release.await() }
        }
        try {
            withTimeout(15_000) { acquired.await() }
            action()
        } finally { release.complete(Unit); holder.join() }
    }

    @Test fun unchangedAndCosmeticResumeDoNotWaitForWaveformSourceLockOrShowPreparation() = fixture { f ->
        val original = f.item().previewId()
        f.pause()
        // A clip name does not change the already rendered sound.
        playbackMain { f.vm.studio.update { project -> project.copy(tracks = project.tracks.map { it.copy(title = "Renamed clip") }) } }
        playbackAwait { f.vm.studio.project.value?.tracks?.single()?.title == "Renamed clip" }
        val sawPreparation = AtomicBoolean(false)
        val observer = CoroutineScope(Dispatchers.Main.immediate).launch {
            f.vm.studio.busy.collect { if (it) sawPreparation.set(true) }
        }
        try {
            withSourceLocked(f) {
                playbackMain { f.vm.studio.playRendered() }
                playbackAwait { playbackMain { f.player.isPlaying } }
                assertEquals(original, f.item().previewId())
                assertFalse("Resuming a ready mix must not enter preparation", sawPreparation.get())
                f.pause()
                playbackMain { f.vm.studio.playRendered() }
                playbackAwait { playbackMain { f.player.isPlaying } }
                assertEquals(original, f.item().previewId())
                assertFalse(sawPreparation.get())
            }
        } finally { observer.cancel() }
    }

    @Test fun changedGainRequiresNewPreparationAndCancellingDoesNotResumeStaleMix() = fixture { f ->
        val original = f.item().previewId()
        f.pause()
        playbackMain { f.vm.studio.track(f.project.tracks.single().id) { it.copy(gain = 0.5) } }
        playbackAwait { f.vm.studio.project.value?.tracks?.single()?.gain == 0.5 }
        withSourceLocked(f) {
            playbackMain { f.vm.studio.playRendered() }
            playbackAwait { f.vm.studio.busy.value }
            assertFalse(playbackMain { f.player.isPlaying })
            assertEquals(original, f.item().previewId())
            playbackMain { f.vm.studio.cancelPreparation() }
            assertFalse(f.vm.studio.busy.value)
            assertFalse(playbackMain { f.player.isPlaying })
        }
        playbackMain { f.vm.studio.playRendered() }
        playbackAwait(30_000) { playbackMain { f.player.isPlaying } && !f.vm.studio.busy.value }
        val quieter = f.item()
        assertNotEquals(original, quieter.previewId())
        val rendered = File(checkNotNull(quieter.localConfiguration?.uri?.path))
        val info = WavFile.info(rendered)
        RandomAccessFile(rendered, "r").use { pcm ->
            pcm.seek(info.dataOffset)
            assertEquals(2048, java.lang.Short.reverseBytes(pcm.readShort()).toInt())
        }
    }

    @Test fun deletingSourceInvalidatesPausedPreviewAndCannotResumeIt() = fixture { f ->
        val original = f.item()
        val rendered = File(checkNotNull(original.localConfiguration?.uri?.path))
        f.pause()
        runBlocking { assertEquals(1, f.app.repository.delete(listOf(f.source.id)).deleted) }
        playbackAwait { playbackMain { f.player.currentMediaItem == null } }
        assertFalse(rendered.exists())
        assertFalse(MultitrackAudio.accepts(f.app, original))
        runBlocking {
            val failure = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(15_000) { f.vm.notices.first { it.contains("no longer exists") || it.contains("missing") } }
            }
            playbackMain { f.vm.studio.playRendered() }
            failure.await()
        }
        playbackAwait { !f.vm.studio.busy.value }
        assertFalse(f.vm.studio.busy.value)
        assertFalse(playbackMain { f.player.isPlaying })
        assertNull(playbackMain { f.player.currentMediaItem })
    }
}
