package app.stillwave

import android.widget.FrameLayout
import android.widget.TextView
import android.view.View
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.stillwave.audio.RecordingService
import app.stillwave.audio.WavFile
import app.stillwave.data.*
import app.stillwave.widget.RecorderWidget
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

/** Emulator UI/capture checks; changes only its own settings and recording fixtures. */
class QualitySlotsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as StillwaveApp
    private lateinit var before: AppSettings
    private val recordings = mutableListOf<String>()

    @Before fun setup() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("pm grant app.stillwave android.permission.RECORD_AUDIO").close()
        automation.executeShellCommand("pm grant app.stillwave android.permission.POST_NOTIFICATIONS").close()
        runBlocking {
            app.ready.await()
            before = app.settings.flow.first()
            Settings.RECORDING_SLOTS.forEachIndexed { i, key -> app.settings.set(key, Quality.fixedPresets[i].name) }
            app.settings.set(Settings.RECORD_STYLE, RecordControlStyle.THREE_BUTTONS.name)
            app.settings.set(Settings.SELECTED_RECORDING_SLOT, 0)
        }
    }

    @After fun cleanup() {
        if (RecordingService.state.value.active) {
            RecordingService.command(app, RecordingService.DISCARD)
            compose.waitUntil(10000) { !RecordingService.state.value.active }
        }
        runBlocking {
            if (recordings.isNotEmpty()) app.repository.delete(recordings)
            Settings.RECORDING_SLOTS.forEachIndexed { i, key -> app.settings.set(key, before.recordingSlots[i].name) }
            app.settings.set(Settings.RECORD_STYLE, before.recordStyle.name)
            app.settings.set(Settings.SELECTED_RECORDING_SLOT, before.selectedRecordingSlot)
        }
    }

    @Test fun assignedButtonPersistsAndRecordsItsDisplayedFormat() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("Recording button 1").performScrollTo().performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("quality-choice-MONO_48"))
        compose.onNodeWithTag("quality-choice-MONO_48").performClick()
        compose.waitUntil(5000) { runBlocking { app.settings.flow.first().recordingSlots[0] == Quality.MONO_48 } }
        assertEquals(0, runBlocking { app.settings.flow.first().selectedRecordingSlot })
        assertEquals(Quality.MONO_48, runBlocking { app.settings.flow.first().selectedRecordingQuality })
        compose.onNodeWithContentDescription("Back to library").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("Record Mono 48").assertIsDisplayed()
        compose.onAllNodesWithText("48000 Hz")[0].assertIsDisplayed()
        compose.onNodeWithText("768 kbps").assertIsDisplayed()
        compose.onNodeWithContentDescription("Record Mono 48").performClick()
        compose.waitUntil(10000) { RecordingService.state.value.elapsedMs >= 300 }
        val state = RecordingService.state.value
        recordings += state.sessionId
        assertEquals(Quality.MONO_48, state.quality)
        compose.onNodeWithText(" Save").performClick()
        compose.waitUntil(10000) { !RecordingService.state.value.active }
        val recording = runBlocking { app.repository.dao.get(state.sessionId)!! }
        assertEquals(Quality.MONO_48.format, WavFile.info(app.repository.file(recording)).format)
        assertEquals(listOf(Quality.MONO_48, Quality.BALANCED, Quality.STUDIO), runBlocking { Settings(app).flow.first().recordingSlots })
    }

    @Test fun widgetAndSingleSelectorUseTheSameSlotAssignments() {
        runBlocking {
            app.settings.set(Settings.RECORDING_SLOTS[0], Quality.MONO_48.name)
            app.settings.set(Settings.RECORDING_SLOTS[1], Quality.MONO_48.name)
            app.settings.set(Settings.RECORD_STYLE, RecordControlStyle.SINGLE_BUTTON.name)
            app.settings.flow.first { it.recordingSlots[1] == Quality.MONO_48 }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Mono 48").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("quality-selector-2").performClick()
        compose.onNodeWithTag("quality-selector-2").assertIsSelected()
        compose.onNodeWithTag("quality-selector-1").assertIsNotSelected()
        compose.onNodeWithText("48000 Hz · 768 kbps").assertIsDisplayed()
        compose.onNode(hasText("48000 Hz") and hasAnyAncestor(hasTestTag("quality-selector-2")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText("768 kbps") and hasAnyAncestor(hasTestTag("quality-selector-2")), useUnmergedTree = true).assertIsDisplayed()
        val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.waitUntil(5000) { vm.settings.value.selectedRecordingQuality == Quality.MONO_48 }
        val idleLibraryTop = compose.onNodeWithContentDescription("Choose folder").fetchSemanticsNode().boundsInRoot.top
        compose.runOnUiThread {
            val views = RecorderWidget.views(app, RecordingService.state.value).apply(compose.activity, FrameLayout(compose.activity))
            val button = views.findViewById<TextView>(R.id.widget_balanced)
            assertTrue(button.text.contains("Mono 48"))
            assertTrue(button.text.contains("48000 Hz"))
            assertTrue(button.text.contains("768 kbps"))
            assertTrue(views.findViewById<View>(R.id.widget_balanced).performClick())
        }
        compose.waitUntil(10000) { RecordingService.state.value.elapsedMs >= 300 }
        assertEquals(Quality.MONO_48, RecordingService.state.value.quality)
        assertEquals(Quality.MONO_48.format, RecordingService.state.value.format)
        compose.waitForIdle()
        assertEquals("Single-button library moved when capture started", idleLibraryTop,
            compose.onNodeWithContentDescription("Choose folder").fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun aacMenuAssignsSampleRateAndBitrateIndependently() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("Recording button 3").performScrollTo().performClick()
        compose.onNodeWithText("AAC", substring = false).performClick()
        compose.onNodeWithTag("AAC Sample rate").performClick()
        compose.onNodeWithTag("AAC Sample rate 24000").performClick()
        compose.onNodeWithTag("AAC Target bitrate").performClick()
        compose.onNodeWithTag("AAC Target bitrate 64000").performClick()
        compose.onNodeWithText("Mono", substring = false).performClick()
        compose.onNodeWithText("Assign quality").performClick()
        val expected = Quality.aac(24000, 64000, 1)
        compose.waitUntil(5000) { runBlocking { app.settings.flow.first().recordingSlots[2] == expected } }
        compose.onNodeWithContentDescription("Back to library").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("Record AAC 64").assertIsDisplayed()
        compose.onNodeWithText("24000 Hz").assertIsDisplayed()
        compose.onNodeWithText("64 kbps").assertIsDisplayed()
        assertEquals(listOf(Quality.VOICE, Quality.BALANCED, expected), runBlocking { app.settings.flow.first().recordingSlots })
        compose.runOnUiThread {
            val views = RecorderWidget.views(app, RecordingService.state.value).apply(compose.activity, FrameLayout(compose.activity))
            val button = views.findViewById<TextView>(R.id.widget_studio)
            assertTrue(button.text.contains("AAC 64"))
            assertTrue(button.text.contains("24000 Hz"))
            assertTrue(button.text.contains("64 kbps"))
        }
    }

    @Test fun reassigningSelectedSlotUpdatesSingleButtonAndSurvivesRecreation() {
        runBlocking { app.settings.set(Settings.RECORD_STYLE, RecordControlStyle.SINGLE_BUTTON.name) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("quality-selector-2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("quality-selector-2").performClick()
        compose.waitUntil(5000) { runBlocking { app.settings.flow.first().selectedRecordingSlot == 1 } }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("Recording button 2").performScrollTo().performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("quality-choice-MONO_48"))
        compose.onNodeWithTag("quality-choice-MONO_48").performClick()
        compose.waitUntil(5000) { runBlocking { app.settings.flow.first().recordingSlots[1] == Quality.MONO_48 } }
        compose.onNodeWithContentDescription("Back to library").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("quality-selector-2").assertIsSelected()
        compose.onNodeWithText("48000 Hz · 768 kbps").assertIsDisplayed()
        compose.onNodeWithText("Record", substring = false).performClick()
        compose.waitUntil(10000) { RecordingService.state.value.elapsedMs >= 300 }
        assertEquals(Quality.MONO_48, RecordingService.state.value.quality)
        assertEquals(Quality.MONO_48.format, RecordingService.state.value.format)
        assertEquals(1, runBlocking { Settings(app).flow.first().selectedRecordingSlot })
    }
}
