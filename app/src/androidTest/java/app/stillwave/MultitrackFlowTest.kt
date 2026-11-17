package app.stillwave

import android.content.ComponentName
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.stillwave.audio.*
import app.stillwave.data.*
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** UUID fixtures only. Covers project controls, durable exports, and the real MediaSession. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MultitrackFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as StillwaveApp
    private val prefix = "Multitrack test ${UUID.randomUUID()}"
    private lateinit var first: Recording
    private lateinit var second: Recording
    private lateinit var previousSettings: AppSettings
    private var pending: ListenableFuture<MediaController>? = null
    private val vm: AppViewModel get() = if (Looper.myLooper() == Looper.getMainLooper()) ViewModelProvider(compose.activity)[AppViewModel::class.java]
        else playbackMain { ViewModelProvider(compose.activity)[AppViewModel::class.java] }

    @Before fun prepare() {
        check(!RecordingService.state.value.active) { "Do not run these tests during a recording" }
        previousSettings = runBlocking { app.ready.await(); app.settings.flow.first() }
        runBlocking { app.settings.set(Settings.STOP_PLAYBACK_WHEN_LOCKED, false) }
        first = fixture("mono", 1)
        second = fixture("stereo", 2)
        compose.waitUntil(10_000) { vm.all.value.any { it.id == first.id } && vm.all.value.any { it.id == second.id } }
    }

    @After fun cleanup() {
        val projects = app.multitrackStore.projects.value.filter { it.title.startsWith(prefix) }
        playbackMain {
            vm.pausePlayback()
            PlaybackService.clearRecordings(projects.map { it.id } + listOfNotNull(if (::first.isInitialized) first.id else null, if (::second.isInitialized) second.id else null))
            pending?.let(MediaController::releaseFuture)
        }
        runBlocking {
            projects.forEach { app.multitrackStore.delete(it.id); MultitrackAudio.deleteForProject(app, it.id) }
            val recordings = app.repository.dao.observeAll().first().filter { it.title.startsWith(prefix) }
            app.repository.delete(recordings.map { it.id })
            if (::previousSettings.isInitialized) app.settings.set(Settings.STOP_PLAYBACK_WHEN_LOCKED, previousSettings.stopPlaybackWhenLocked)
        }
    }

    private fun fixture(suffix: String, channels: Int): Recording {
        val id = UUID.randomUUID().toString()
        val file = File(app.recordingsDir, "$id.wav")
        val bytes = ByteBuffer.allocate(96000 * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(96000) { repeat(channels) { channel ->
            bytes.putShort((if (channels == 1) 4096 else if (channel == 0) 2048 else -2048).toShort())
        } }
        WavFile.header(file, 48000, channels, 0); file.appendBytes(bytes.array())
        WavFile.header(file, 48000, channels, bytes.capacity().toLong())
        return Recording(id = id, title = "$prefix $suffix", createdAt = System.currentTimeMillis(), durationMs = 2000,
            fileName = file.name, sampleRate = 48000, channels = channels).also { runBlocking { app.repository.dao.insert(it) } }
    }

    private fun controller(): MediaController {
        val future = playbackMain { MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
        pending = future
        return future.get(15, TimeUnit.SECONDS)
    }

    private fun create(loop: Boolean = false): MultitrackProject {
        playbackMain { vm.studio.create("$prefix project", loop, listOf(first, second)) }
        compose.waitUntil(10_000) { vm.studio.project.value?.title == "$prefix project" }
        return checkNotNull(vm.studio.project.value)
    }

    private fun hash(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    private fun samples(file: File): ShortArray {
        val info = WavFile.info(file)
        val buffer = ByteBuffer.wrap(file.readBytes(), info.dataOffset.toInt(), info.dataBytes.toInt()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(buffer.remaining()).also { buffer.get(it) }
    }

    @Test fun pickerAddsTwoLibraryRecordingsAndTrackEditsSurviveActivityAndStoreRecreation() {
        compose.onNodeWithContentDescription("Multitrack and loop station").performClick()
        compose.onNodeWithText(" New project").performClick()
        compose.onNodeWithText("Project name").performTextReplacement("$prefix project")
        compose.onNodeWithText("Project duration").performTextReplacement("00:00:02.000")
        compose.onNodeWithText("Create", substring = false).performClick()
        compose.waitUntil(10_000) { vm.studio.project.value != null }
        compose.onNodeWithText("Add recordings", substring = false).performClick()
        compose.onNodeWithText("Search recordings", substring = false).performTextReplacement(prefix)
        compose.onNodeWithText(first.title, substring = false).performClick()
        compose.onNodeWithText(second.title, substring = false).performClick()
        compose.onNodeWithText("Add tracks", substring = false).performClick()
        compose.waitUntil(10_000) { vm.studio.project.value?.tracks?.size == 2 }
        val project = checkNotNull(vm.studio.project.value)
        val track = project.tracks.single { it.recordingId == first.id }
        compose.onNodeWithTag("studio-tracks").performScrollToNode(hasTestTag("project-choose-clip"))
        compose.onNodeWithTag("project-choose-clip").performClick()
        compose.onNodeWithTag("project-clip-options").performScrollToNode(hasTestTag("choose-clip-${track.id}"))
        compose.onNodeWithTag("choose-clip-${track.id}").performClick()
        compose.onNodeWithTag("studio-tracks").performScrollToNode(hasTestTag("track-${track.id}"))
        compose.onNode(hasText("Mute", substring = false) and hasAnyAncestor(hasTestTag("track-${track.id}"))).performClick()
        compose.waitUntil(10_000) { vm.studio.project.value?.tracks?.firstOrNull { it.id == track.id }?.muted == true }
        compose.onNodeWithContentDescription("Edit clip ${track.title}").performScrollTo().performClick()
        compose.onNodeWithText("Clip name").performTextReplacement("Renamed guitar")
        compose.onNodeWithText("From", substring = false).performTextReplacement("00:00:00.250")
        compose.onNodeWithText("To", substring = false).performTextReplacement("00:00:01.750")
        compose.onNodeWithText("Position in project", substring = false).performTextReplacement("00:00:00.500")
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.waitUntil(10_000) { vm.studio.project.value?.tracks?.any { it.title == "Renamed guitar" } == true }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { vm.studio.project.value?.id == project.id }
        compose.onNodeWithTag("studio-tracks").performScrollToNode(hasText("Renamed guitar"))
        compose.onNodeWithText("Renamed guitar").assertIsDisplayed()
        val restored = runBlocking { MultitrackStore(File(app.filesDir, "projects")).load().single { it.id == project.id } }
        val changed = restored.tracks.single { it.id == track.id }
        assertEquals(2, restored.tracks.size)
        assertTrue(changed.muted)
        assertEquals("Renamed guitar", changed.title)
        assertEquals(250L, changed.sourceStartMs)
        assertEquals(1750L, changed.sourceEndMs)
        assertEquals(500L, changed.offsetMs)
        assertTrue(app.repository.file(first).exists()); assertTrue(app.repository.file(second).exists())
    }

    @Test fun twoTrackExportUsesImmutableSnapshotAndPreservesOriginalAudio() {
        val base = create()
        // Reusing a source must not reacquire the same non-reentrant repository mutex.
        val project = runBlocking { app.multitrackStore.update(base.id) {
            it.copy(tracks = it.tracks + MultitrackTrack(recordingId = first.id, title = "Duplicate layer", sourceEndMs = 2000, gain = 0.5))
        } }
        val firstHash = hash(app.repository.file(first)); val secondHash = hash(app.repository.file(second))
        val title = "$prefix exported mix"
        val job = MultitrackExportWorker.enqueue(app, project, title)
        runBlocking { app.multitrackStore.update(project.id) { it.copy(tracks = it.tracks.map { track -> track.copy(muted = true) }) } }
        val manager = WorkManager.getInstance(app)
        playbackAwait(30_000) { manager.getWorkInfoById(job).get()?.state?.isFinished == true }
        val work = checkNotNull(manager.getWorkInfoById(job).get())
        assertEquals(work.outputData.getString("error"), WorkInfo.State.SUCCEEDED, work.state)
        val output = runBlocking { app.repository.dao.observeAll().first().single { it.title == title } }
        assertEquals(2000L, output.durationMs)
        assertEquals(48000, output.sampleRate); assertEquals(2, output.channels)
        assertEquals(PcmEncoding.PCM16.name, output.pcmEncoding)
        val pcm = samples(app.repository.file(output))
        assertEquals(96000 * 2, pcm.size)
        for (frame in 0 until 96000) {
            assertEquals(8192.toShort(), pcm[frame * 2])
            assertEquals(4096.toShort(), pcm[frame * 2 + 1])
        }
        assertArrayEquals(firstHash, hash(app.repository.file(first)))
        assertArrayEquals(secondHash, hash(app.repository.file(second)))
        assertTrue(app.multitrackStore.projects.value.single { it.id == project.id }.tracks.all { it.muted })
    }

    @Test fun loopKeepsPlayingBeyondItsDurationAndRegularPlaybackDoesNotInheritRepeat() {
        val controller = controller()
        val project = create(loop = true)
        playbackMain { vm.studio.update { it.copy(durationMs = 500, tracks = it.tracks.map { track -> track.copy(sourceEndMs = 500) }) } }
        compose.waitUntil(10_000) { vm.studio.project.value?.durationMs == 500L }
        playbackMain { vm.studio.playRendered() }
        playbackAwait(30_000) { playbackMain { controller.currentMediaItem?.mediaId == project.id && controller.isPlaying } }
        assertEquals(Player.REPEAT_MODE_ONE, playbackMain { controller.repeatMode })
        // The remote controller need not deliver a same-item transition callback on each repeat.
        // Observe playback itself: its playhead must wrap twice and keep advancing beyond two cycles.
        val began = SystemClock.elapsedRealtime()
        var previousPosition = playbackMain { controller.currentPosition }
        var wraps = 0
        playbackAwait(15_000) {
            val (playing, position) = playbackMain { controller.isPlaying to controller.currentPosition }
            if (playing && previousPosition - position > 200) wraps++
            previousPosition = position
            wraps >= 2 && playing && SystemClock.elapsedRealtime() - began >= 1000
        }
        playbackMain { vm.play(first) }
        playbackAwait { playbackMain { controller.currentMediaItem?.mediaId == first.id && controller.isPlaying } }
        assertEquals(Player.REPEAT_MODE_OFF, playbackMain { controller.repeatMode })
        playbackAwait(10_000) { playbackMain { controller.playbackState == Player.STATE_ENDED } }
        assertFalse(playbackMain { controller.isPlaying })
    }

    @Test fun previewRejectsTamperedIdentityOrPathAndDeletingSourceStopsPlaybackAndRemovesCache() {
        val controller = controller()
        val project = create(loop = true)
        val original = runBlocking { MultitrackAudio.prepare(app, project) }
        val cosmetic = runBlocking { MultitrackAudio.prepare(app, project.copy(title = "Cosmetic rename",
            updatedAt = project.updatedAt + 1, loopPlayback = false, tracks = project.tracks.map { it.copy(title = "Track rename") })) }
        assertEquals(original.identity, cosmetic.identity)
        assertEquals(original.file, cosmetic.file)
        val quieter = runBlocking { MultitrackAudio.prepare(app, project.copy(tracks = project.tracks.map { it.copy(gain = 0.5) })) }
        assertNotEquals(original.identity, quieter.identity)
        playbackMain { vm.studio.playRendered() }
        playbackAwait(30_000) { playbackMain { controller.isPlaying } && !vm.studio.busy.value }
        val item = playbackMain { checkNotNull(controller.currentMediaItem) }
        assertTrue(MultitrackAudio.accepts(app, item))
        val rendered = File(checkNotNull(item.localConfiguration?.uri?.path))
        assertTrue(rendered.exists())
        assertFalse(MultitrackAudio.accepts(app, item.buildUpon().setMediaId("unregistered-project").build()))
        assertFalse(MultitrackAudio.accepts(app, item.buildUpon().setUri(app.repository.file(first).toURI().toString()).build()))
        assertFalse(MultitrackAudio.accepts(app, item.buildUpon().setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionMs(10).setEndPositionMs(100).build()).build()))
        val extras = Bundle(item.mediaMetadata.extras).apply { putString(PlaybackService.PROJECT_PREVIEW_ID, "unregistered-preview") }
        assertFalse(MultitrackAudio.accepts(app, item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()))
        // Exercise the repository observer too: cleanup/recovery does not use the UI's eager pause.
        runBlocking { assertEquals(1, app.repository.delete(listOf(first.id)).deleted) }
        playbackAwait(15_000) { playbackMain { !controller.isPlaying && controller.currentMediaItem == null } }
        assertFalse(rendered.exists())
        assertFalse(MultitrackAudio.accepts(app, item))
        assertTrue(app.repository.file(second).exists())
    }

    @Test fun livePreviewPlaysWithoutRenderingAndFollowsEdits() {
        val controller = controller()
        val project = create(loop = true)
        playbackMain { vm.studio.play() }
        playbackAwait(15_000) { vm.studio.live.value.let { it.projectId == project.id && it.playing && !it.loading } }
        // Live playback never prepares a file or loads the background player.
        assertFalse(vm.studio.busy.value)
        assertNotEquals(project.id, playbackMain { controller.currentMediaItem?.mediaId })
        val started = vm.studio.live.value.positionMs
        playbackAwait(5_000) { vm.studio.live.value.positionMs != started }
        // A mixer edit is applied in place: playback continues without preparation.
        playbackMain { vm.studio.track(project.tracks.first().id) { it.copy(gain = 0.5) } }
        playbackAwait { vm.studio.project.value?.tracks?.first()?.gain == 0.5 }
        assertTrue(vm.studio.live.value.playing)
        assertFalse(vm.studio.busy.value)
        playbackMain { vm.studio.seek(1500) }
        playbackAwait { vm.studio.live.value.positionMs in 1400..2000 }
        // New takes align against the live playhead.
        assertEquals(true, playbackMain { PlaybackService.projectPosition(project.id)?.playing })
        playbackMain { vm.studio.play() }
        playbackAwait { !vm.studio.live.value.playing }
        playbackMain { vm.studio.back() }
        playbackAwait { vm.studio.live.value.projectId == null }
    }

    @Test fun removingLastTrackStopsThePreviouslyRenderedMix() {
        val controller = controller()
        val project = create(loop = true)
        playbackMain { vm.studio.playRendered() }
        playbackAwait(30_000) { playbackMain { controller.isPlaying } && !vm.studio.busy.value }
        playbackMain { project.tracks.forEach { vm.studio.removeTrack(it.id) } }
        playbackAwait { vm.studio.project.value?.tracks?.isEmpty() == true && playbackMain { !controller.isPlaying && controller.currentMediaItem == null } }
        assertFalse(vm.studio.busy.value)
        assertTrue(app.repository.file(first).exists()); assertTrue(app.repository.file(second).exists())
    }
}
