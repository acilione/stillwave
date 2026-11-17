package app.stillwave

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import app.stillwave.audio.*
import app.stillwave.data.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** UUID-owned fixtures only; render checks verify source references rather than just UI state. */
class ProjectClipFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as StillwaveApp
    private val vm: AppViewModel get() = if (Looper.myLooper() == Looper.getMainLooper()) ViewModelProvider(compose.activity)[AppViewModel::class.java]
        else playbackMain { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
    private var source: Recording? = null
    private var projectId: String? = null
    private lateinit var original: ByteArray
    private val project get() = checkNotNull(vm.studio.project.value)

    @Before fun prepare() {
        check(!RecordingService.state.value.active)
        runBlocking { app.ready.await() }
        val id = UUID.randomUUID().toString()
        val file = File(app.recordingsDir, "$id.wav")
        val pcm = ByteBuffer.allocate(48000 * 4 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(48000 * 4) { pcm.putShort(if (it < 96000) 4096 else 8192) }
        WavFile.header(file, 48000, 1, 0); file.appendBytes(pcm.array()); WavFile.header(file, 48000, 1, pcm.capacity().toLong())
        original = file.readBytes()
        val recording = Recording(id = id, title = "Clip editing $id", createdAt = System.currentTimeMillis(), durationMs = 4000,
            fileName = file.name, sampleRate = 48000, channels = 1)
        source = recording
        runBlocking { app.repository.dao.insert(recording) }
        playbackMain { vm.studio.create("Clip editing", false, listOf(recording)) }
        compose.waitUntil(10_000) { vm.studio.project.value?.tracks?.firstOrNull()?.recordingId == id }
        projectId = project.id
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Loading waveform…").fetchSemanticsNodes().isEmpty() }
    }

    @After fun cleanup() {
        if (projectId == null) return
        playbackMain { vm.pausePlayback() }
        runBlocking {
            projectId?.let { app.multitrackStore.delete(it); MultitrackAudio.deleteForProject(app, it) }
            source?.let { app.repository.delete(listOf(it.id)) }
        }
    }
    private fun click(tag: String) {
        compose.onNodeWithTag("studio-tracks").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun choose(id: String) {
        click("project-choose-clip")
        compose.onNodeWithTag("project-clip-options").performScrollToNode(hasTestTag("choose-clip-$id"))
        compose.onNodeWithTag("choose-clip-$id").performClick()
    }
    private fun splitAt(time: String) {
        click("project-cut-position")
        compose.onNodeWithText("Cut time").performTextReplacement(time)
        compose.onNodeWithText("Set position").performClick()
        click("project-split")
        compose.waitUntil(10_000) { project.tracks.size == 2 }
    }
    private fun position(id: String, time: String) {
        choose(id)
        compose.onNodeWithText("Position / name").performScrollTo().performClick()
        compose.onNodeWithText("Position in project").performTextReplacement(time)
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.waitUntil(10_000) { project.tracks.first { it.id == id }.offsetMs == parseTimestamp(time) }
    }

    @Test fun splitRearrangeAndReloadPreserveOriginalAndRenderTheNewSequence() {
        val left = project.tracks.single().id
        val canvas = compose.onNodeWithTag("timeline-track-$left")
        canvas.assertIsDisplayed()
        File(app.cacheDir, "waveform-190.png").outputStream().use {
            canvas.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        splitAt("1")
        val right = project.tracks.last().id
        assertEquals(1, project.lanes.size)
        compose.onNodeWithTag("studio-tracks").performScrollToIndex(0)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Loading waveform…").fetchSemanticsNodes().isEmpty() }
        File(app.cacheDir, "project-clips-190.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        position(right, "0")
        position(left, "3")
        choose(right)
        val arranged = project
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { vm.studio.project.value == arranged }
        compose.onNodeWithTag("studio-tracks").performScrollToNode(hasTestTag("track-$right"))
        compose.onNodeWithTag("track-$right").assertIsDisplayed()
        assertEquals(arranged, runBlocking { MultitrackStore(File(app.filesDir, "projects")).load().single { it.id == projectId } })
        val file = app.repository.file(checkNotNull(source))
        val output = File(app.cacheDir, "clip-sequence-${source!!.id}.wav")
        try {
            MultitrackRenderer.render(arranged.tracks.map { MultitrackRenderer.Input(it, file) }, output, arranged.durationMs)
            val info = WavFile.info(output)
            val samples = ByteBuffer.wrap(output.readBytes(), info.dataOffset.toInt(), info.dataBytes.toInt()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            assertEquals(4000L, info.durationMs)
            for (frame in listOf(0, 47999, 48000, 143999, 144000, 191999)) {
                assertEquals(if (frame in 48000..143999) 8192.toShort() else 4096.toShort(), samples.get(frame * 2))
            }
            assertArrayEquals(original, file.readBytes())
        } finally { output.delete() }
    }

    @Test fun trimDuplicateMoveBetweenTracksAndUndoPersistWithoutChangingSource() {
        splitAt("1")
        val right = project.tracks.last().id
        choose(right); click("project-trim")
        compose.onNodeWithText("Source from").performTextReplacement("1.5")
        compose.onNodeWithText("Source to").performTextReplacement("3.5")
        compose.onNodeWithText("Apply trim").performClick()
        compose.waitUntil(10_000) { project.tracks.last().sourceStartMs == 1500L }
        assertEquals(1500L, project.tracks.last().offsetMs)
        click("project-duplicate")
        compose.waitUntil(10_000) { project.tracks.size == 3 }
        val copy = project.tracks.last()
        assertEquals(3500L, copy.offsetMs)
        assertEquals(5500L, project.durationMs)
        choose(copy.id); click("project-move-lane")
        compose.onNodeWithTag("move-new-lane").performClick()
        compose.waitUntil(10_000) { project.lanes.size == 2 && vm.studio.canUndo.value }
        click("project-undo")
        compose.waitUntil(10_000) { project.lanes.size == 1 && !vm.studio.canUndo.value }
        click("project-remove-clip")
        compose.waitUntil(10_000) { project.tracks.size == 2 && vm.studio.canUndo.value }
        click("project-undo")
        compose.waitUntil(10_000) { project.tracks.size == 3 }
        assertArrayEquals(original, app.repository.file(checkNotNull(source)).readBytes())
    }

    @Test fun cutSliderWorksBeforeMixPreparationRestoresAndSplitsAtItsPosition() {
        val untouched = project
        val slider = compose.onNodeWithTag("project-cut-cursor").performScrollTo().assertIsEnabled()
        slider.performTouchInput { swipe(Offset(width * 0.1f, centerY), Offset(width * 0.7f, centerY)) }
        val dragged = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        assertTrue(dragged in 2000f..3500f)
        compose.onNodeWithTag("project-cut-position").assertTextContains(timestamp(dragged.toLong()), substring = true)
        assertEquals(untouched, project)
        assertFalse(vm.studio.busy.value)
        assertNotEquals(project.id, vm.playback.value.recordingId)

        // TalkBack adjustment and the exact-time field use the same cursor as touch dragging.
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(1500f) }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { vm.studio.project.value?.id == projectId }
        compose.onNodeWithTag("project-cut-cursor").performScrollTo().assertIsEnabled()
        assertEquals(1500f, compose.onNodeWithTag("project-cut-cursor").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
        click("project-cut-position")
        compose.onNodeWithText("Cut time").assertTextContains(timestamp(1500))
        compose.onNodeWithText("Cancel", substring = false).performClick()
        click("project-split")
        compose.waitUntil(10_000) { project.tracks.size == 2 }
        assertEquals(1500L, project.tracks.first().sourceEndMs)
        assertEquals(1500L, project.tracks.last().sourceStartMs)
        assertEquals(1500L, project.tracks.last().offsetMs)
        assertArrayEquals(original, app.repository.file(checkNotNull(source)).readBytes())
    }

    @Test fun waveformAndExactTimeUpdateCutSliderAndShorteningProjectClampsIt() {
        compose.onNodeWithTag("timeline-track-${project.tracks.single().id}").performTouchInput {
            click(Offset(width * 0.25f, centerY))
        }
        val slider = compose.onNodeWithTag("project-cut-cursor").performScrollTo()
        val tapped = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        assertTrue(tapped in 1000f..1500f)
        click("project-cut-position")
        compose.onNodeWithText("Cut time").performTextReplacement("3.5")
        compose.onNodeWithText("Set position").performClick()
        assertEquals(3500f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)

        runBlocking { app.multitrackStore.update(project.id) { it.copy(durationMs = 1000) } }
        compose.waitUntil(10_000) { project.durationMs == 1000L }
        val clamped = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(1000f, clamped.current, 0f)
        assertEquals(0f..1000f, clamped.range)
        compose.onNodeWithTag("project-cut-position").assertTextContains(timestamp(1000), substring = true)
        compose.onNodeWithTag("project-split").assertIsNotEnabled()
        assertFalse(vm.studio.busy.value)
    }

    @Test fun compressedAudioWaveformAndSplitUseTheDecodedSourceWithoutRewritingAac() {
        val wav = checkNotNull(source)
        val id = UUID.randomUUID().toString()
        val encoded = File(app.recordingsDir, "$id.m4a")
        AacFile.encode(app.repository.file(wav), encoded, 128000)
        val info = AacFile.info(encoded)
        val aac = wav.copy(id = id, fileName = encoded.name, title = "AAC clip", durationMs = info.durationMs)
        val bytes = encoded.readBytes()
        try {
            runBlocking { app.repository.dao.insert(aac); app.multitrackStore.update(project.id) {
                it.copy(tracks = listOf(MultitrackTrack(recordingId = id, title = aac.title, sourceEndMs = info.durationMs)), durationMs = info.durationMs)
            } }
            compose.waitUntil(10_000) { project.tracks.singleOrNull()?.recordingId == id }
            splitAt("1")
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Loading waveform…").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Waveform unavailable for a source clip").assertDoesNotExist()
            assertArrayEquals(bytes, encoded.readBytes())
        } finally { runBlocking { app.repository.delete(listOf(id)) } }
    }
}
