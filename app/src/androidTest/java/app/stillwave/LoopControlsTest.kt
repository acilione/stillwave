package app.stillwave

import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import app.stillwave.audio.PlaybackService
import app.stillwave.audio.WavFile
import app.stillwave.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Owns every fixture and restores preferences; safe alongside an existing recording library. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LoopControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as StillwaveApp
    private val vm: AppViewModel get() = if (Looper.myLooper() == Looper.getMainLooper()) ViewModelProvider(compose.activity)[AppViewModel::class.java]
        else playbackMain { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
    private lateinit var before: AppSettings
    private lateinit var source: Recording
    private val cardTag get() = "recording-${source.id}"
    private val loopTag get() = "loop-recording-${source.id}"

    @Before fun prepare() {
        runBlocking {
            app.ready.await()
            before = app.settings.flow.first()
            app.settings.set(Settings.SHOW_LOOP_BUTTON, true)
            app.settings.set(Settings.LAYOUT, LibraryLayout.LIST.name)
            app.settings.set(Settings.STOP_PLAYBACK_WHEN_LOCKED, false)
            val id = UUID.randomUUID().toString()
            source = Recording(id = id, title = "Loop controls $id", createdAt = System.currentTimeMillis(),
                durationMs = 30_000, fileName = "$id.wav", sampleRate = 16000, channels = 1)
            WavFile.header(app.repository.file(source), 16000, 1, 960_000)
            // Insert directly so test audio skips the normal save path.
            app.repository.dao.insert(source)
        }
        compose.runOnUiThread { vm.filter.value = LibraryFilter(search = source.title); vm.selectFolder(ALL_FOLDERS) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag(cardTag).fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun restore() {
        if (::source.isInitialized) playbackMain { vm.pausePlayback(); PlaybackService.clearRecordings(listOf(source.id)) }
        runBlocking {
            if (::source.isInitialized) app.repository.delete(listOf(source.id))
            if (::before.isInitialized) {
                app.settings.set(Settings.SHOW_LOOP_BUTTON, before.showLoopButton)
                app.settings.set(Settings.LAYOUT, before.layout.name)
                app.settings.set(Settings.STOP_PLAYBACK_WHEN_LOCKED, before.stopPlaybackWhenLocked)
            }
        }
    }

    private fun layout(layout: LibraryLayout) {
        runBlocking { app.settings.set(Settings.LAYOUT, layout.name) }
        compose.waitUntil(5000) { vm.settings.value.layout == layout }
        compose.waitForIdle()
    }

    private fun menu() {
        compose.onNode(hasContentDescription("Recording actions") and hasAnyAncestor(hasTestTag(cardTag))).performClick()
    }

    @Test fun visibilitySettingPersistsForEveryLayoutAndMenuCanStillLoop() {
        assertTrue(AppSettings().showLoopButton)
        LibraryLayout.entries.forEach { layout(it); compose.onNodeWithTag(loopTag).assertIsDisplayed().assertIsOff() }
        compose.onNodeWithContentDescription("Settings").performClick()
        val setting = "setting-Show loop button in recording list"
        compose.onNodeWithTag(setting).performScrollTo().assertIsOn().performClick()
        compose.waitUntil(5000) { runBlocking { !app.settings.flow.first().showLoopButton } }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag(setting).performScrollTo().assertIsOff()
        assertEquals(false, runBlocking { Settings(app).flow.first().showLoopButton })
        compose.onNodeWithContentDescription("Back to library").performClick()
        LibraryLayout.entries.forEach { layout(it); compose.onNodeWithTag(loopTag).assertDoesNotExist() }
        menu()
        compose.onNodeWithText("Loop recording").performClick()
        playbackAwait { vm.playback.value.let { it.recordingId == source.id && it.windowId == null && it.looping && it.playing } }
        compose.onNodeWithTag(loopTag).assertDoesNotExist()
        menu()
        compose.onNodeWithText("Turn off loop").performClick()
        playbackAwait { vm.playback.value.let { !it.looping && it.playing } }
    }

    @Test fun adjacentLoopButtonStartsPlaybackAndOrdinaryPlayPauseRetainsLoop() {
        compose.onNodeWithTag(loopTag).performClick()
        playbackAwait { vm.playback.value.let { it.recordingId == source.id && it.looping && it.playing } }
        compose.onNodeWithTag(loopTag).assertIsOn()
        compose.onNode(hasContentDescription("Pause playback") and hasAnyAncestor(hasTestTag(cardTag))).performClick()
        playbackAwait { vm.playback.value.let { it.looping && !it.playing } }
        compose.onNodeWithTag(loopTag).assertIsOn()
        compose.onNode(hasContentDescription("Play recording") and hasAnyAncestor(hasTestTag(cardTag))).performClick()
        playbackAwait { vm.playback.value.let { it.looping && it.playing } }
        compose.onNodeWithTag(loopTag).performClick()
        playbackAwait { vm.playback.value.let { !it.looping && it.playing } }
        compose.onNodeWithTag(loopTag).assertIsOff()
    }

    @Test fun transformedWindowCanLoopWithoutMarkingOriginalAndInvalidWindowsCannotPlay() {
        val window = CutWindow(recordingId = source.id, startMs = 1000, endMs = 5000, reverse = true, speed = 2.0, preservePitch = true)
        runBlocking { app.repository.dao.window(window) }
        compose.onNodeWithTag(cardTag).performClick()
        val windowLoop = "loop-window-${window.id}"
        compose.onNodeWithTag(windowLoop).performScrollTo().assertIsNotSelected().performClick()
        playbackAwait(30_000) { vm.playback.value.let { it.recordingId == source.id && it.windowId == window.id && it.looping && it.playing } && !vm.previewPreparing.value }
        compose.onNodeWithTag(windowLoop).assertIsSelected()
        compose.onNodeWithText(" Pause").performScrollTo().performClick()
        playbackAwait { vm.playback.value.let { it.looping && !it.playing } }
        compose.onNodeWithTag(windowLoop).assertIsSelected()
        compose.onNodeWithText(" Preview").performScrollTo().performClick()
        playbackAwait { vm.playback.value.let { it.looping && it.playing } }
        compose.onNodeWithContentDescription("Back to recordings").performClick()
        compose.onNodeWithTag(loopTag).assertIsOff()
        compose.onNodeWithTag(cardTag).performClick()
        compose.onNodeWithText("From").performScrollTo().performTextReplacement("invalid")
        compose.onNodeWithTag(windowLoop).performScrollTo().assertIsNotEnabled()
    }
}
