package app.stillwave

import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import app.stillwave.audio.*
import app.stillwave.data.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real timeline → controller → disk → renderer, using only a UUID-owned fixture. */
class TrackTimelinePersistenceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as StillwaveApp
    private val prefix = "Timeline test ${UUID.randomUUID()}"
    private var recording: Recording? = null
    private var projectId: String? = null
    private val vm: AppViewModel get() = if (Looper.myLooper() == Looper.getMainLooper()) ViewModelProvider(compose.activity)[AppViewModel::class.java]
        else playbackMain { ViewModelProvider(compose.activity)[AppViewModel::class.java] }

    @After fun cleanup() {
        playbackMain { vm.pausePlayback() }
        runBlocking {
            projectId?.let { app.multitrackStore.delete(it); MultitrackAudio.deleteForProject(app, it) }
            recording?.let { app.repository.delete(listOf(it.id)) }
        }
    }

    @Test fun dragPersistsAfterActivityRecreationAndRenderedSoundStartsAtNewPosition() {
        check(!RecordingService.state.value.active) { "Do not run this test during a recording" }
        runBlocking { app.ready.await() }
        val id = UUID.randomUUID().toString()
        val file = File(app.recordingsDir, "$id.wav")
        val pcm = ByteBuffer.allocate(96000 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(96000) { pcm.putShort(4096.toShort()) }
        WavFile.header(file, 48000, 1, 0); file.appendBytes(pcm.array())
        WavFile.header(file, 48000, 1, pcm.capacity().toLong())
        val source = Recording(id = id, title = prefix, createdAt = System.currentTimeMillis(), durationMs = 2000,
            fileName = file.name, sampleRate = 48000, channels = 1)
        recording = source
        runBlocking { app.repository.dao.insert(source) }
        compose.waitUntil(10_000) { vm.all.value.any { it.id == id } }
        playbackMain { vm.studio.create(prefix, false, listOf(source)) }
        compose.waitUntil(10_000) { vm.studio.project.value?.title == prefix }
        val before = checkNotNull(vm.studio.project.value)
        projectId = before.id
        val track = before.tracks.single()
        compose.onNodeWithTag("timeline-track-${track.id}").performTouchInput {
            swipe(Offset(width * 0.1f, centerY), Offset(width * 0.5f, centerY))
        }
        compose.waitUntil(10_000) { (vm.studio.project.value?.tracks?.single()?.offsetMs ?: 0) > 0 }
        val moved = checkNotNull(vm.studio.project.value)
        assertEquals(2000 + moved.tracks.single().offsetMs, moved.durationMs)
        val restored = runBlocking { MultitrackStore(File(app.filesDir, "projects")).load().single { it.id == before.id } }
        assertEquals(moved, restored)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { vm.studio.project.value == restored }
        compose.onNodeWithTag("timeline-track-${track.id}").assertIsDisplayed()

        val original = file.readBytes()
        val destination = File(app.cacheDir, "timeline-render-$id.wav")
        try {
            MultitrackRenderer.render(listOf(MultitrackRenderer.Input(restored.tracks.single(), file)), destination, restored.durationMs)
            val info = WavFile.info(destination)
            val samples = ByteBuffer.wrap(destination.readBytes(), info.dataOffset.toInt(), info.dataBytes.toInt()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val offsetFrames = restored.tracks.single().offsetMs * 48
            assertEquals(restored.durationMs, info.durationMs)
            assertEquals(0.toShort(), samples.get(0))
            assertEquals(0.toShort(), samples.get(((offsetFrames - 1) * 2).toInt()))
            assertEquals(4096.toShort(), samples.get((offsetFrames * 2).toInt()))
            assertEquals(4096.toShort(), samples.get(samples.limit() - 1))
            assertArrayEquals(original, file.readBytes())
        } finally { destination.delete() }
    }
}
