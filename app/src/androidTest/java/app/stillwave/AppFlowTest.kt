package app.stillwave

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stillwave.audio.*
import app.stillwave.data.*
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as StillwaveApp
    @Before fun prepare() {
        runBlocking(Dispatchers.IO) { app.ready.await(); app.database.clearAllTables(); app.settings.set(Settings.RECORD_STYLE, RecordControlStyle.SINGLE_BUTTON.name) }
        runBlocking {
            Settings.RECORDING_SLOTS.forEachIndexed { i, key -> app.settings.set(key, Quality.fixedPresets[i].name) }
            app.settings.set(Settings.SELECTED_RECORDING_SLOT, 0)
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant app.stillwave android.permission.RECORD_AUDIO").close()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant app.stillwave android.permission.POST_NOTIFICATIONS").close()
    }
    @After fun stopTestCapture() {
        if (RecordingService.state.value.active) {
            RecordingService.command(app, RecordingService.DISCARD)
            compose.waitUntil(5000) { !RecordingService.state.value.active }
        }
    }
    private fun chooseFolder(name: String) {
        compose.onNodeWithContentDescription("Choose folder").performClick()
        compose.onNodeWithContentDescription("Folder: $name").performClick()
    }
    @Test fun foldersSupportIndividualAndBulkMovesRenameAndSafeRemoval() {
        val first = deletionFixture("folder-a", "Folder audio A")
        val second = deletionFixture("folder-b", "Folder audio B")
        val keep = deletionFixture("folder-c", "Folder audio C")
        compose.onNodeWithContentDescription("Choose folder").performClick()
        compose.onNodeWithText("New folder", substring = true).performClick()
        compose.onNodeWithText("Folder name").performTextInput("Work")
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(5000) { runBlocking { app.repository.dao.folders().any { it.name == "Work" } } }
        val work = runBlocking { app.repository.dao.folders().single() }
        chooseFolder("All recordings")
        compose.onNodeWithText(first.title).performTouchInput { longClick() }
        compose.onNodeWithText("Move to folder").performClick()
        compose.onNodeWithContentDescription("Folder: Work").performClick()
        compose.waitUntil(5000) { runBlocking { app.repository.dao.get(first.id)?.folderId == work.id } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Recordings moved").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(first.title).performTouchInput { longClick() }
        compose.onNodeWithText("Select multiple").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(second.title))
        compose.onNodeWithText(second.title).assertIsDisplayed()
        compose.onNodeWithText(second.title).performClick()
        compose.onNodeWithText("2", substring = false).assertIsDisplayed()
        compose.onNodeWithContentDescription("Move selected recordings").performClick()
        compose.onNodeWithText("New folder", substring = true).performClick()
        compose.onNodeWithText("Folder name").performTextInput("Trips")
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(5000) { runBlocking { app.repository.dao.folders().size == 2 } }
        val trips = runBlocking { app.repository.dao.folders().first { it.name == "Trips" } }
        assertEquals(trips.id, runBlocking { app.repository.dao.get(first.id)!!.folderId })
        assertEquals(trips.id, runBlocking { app.repository.dao.get(second.id)!!.folderId })
        assertNull(runBlocking { app.repository.dao.get(keep.id)!!.folderId })
        compose.waitUntil(5000) { compose.onAllNodesWithText("Create", substring = false).fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(first.title))
        compose.onNodeWithText(first.title).assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(second.title))
        compose.onNodeWithText(second.title).assertIsDisplayed()
        compose.onNodeWithText(keep.title).assertDoesNotExist()
        compose.onNodeWithContentDescription("Choose folder").performClick()
        compose.onNodeWithContentDescription("Folder actions: Trips").performClick()
        compose.onNodeWithText("Rename folder").performClick()
        compose.onNodeWithText("Folder name").performTextReplacement("Interviews")
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Folder: Interviews").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Folder: Interviews").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Interviews").assertIsDisplayed()
        compose.onNodeWithText(keep.title).assertDoesNotExist()
        compose.onNodeWithContentDescription("Choose folder").performClick()
        compose.onNodeWithContentDescription("Folder actions: Interviews").performClick()
        compose.onNodeWithText("Delete folder").performClick()
        compose.onNodeWithText("Cancel", substring = false).performClick()
        assertNotNull(runBlocking { app.repository.dao.folder(trips.id) })
        compose.onNodeWithContentDescription("Folder actions: Interviews").performClick()
        compose.onNodeWithText("Delete folder").performClick()
        compose.onNodeWithText("Delete folder", substring = false).performClick()
        compose.waitUntil(5000) { runBlocking { app.repository.dao.folder(trips.id) == null } }
        compose.onNodeWithContentDescription("Folder: Unfiled").performClick()
        listOf(first, second, keep).forEach { item ->
            assertEquals(item, runBlocking { app.repository.dao.get(item.id) })
            assertTrue(app.repository.file(item).exists())
        }
    }
    @Test fun recordingUsesFolderSelectedAtStartEvenWhenBrowsingChanges() {
        val folder = RecordingFolder(name = "Sessions")
        runBlocking { app.repository.dao.insertFolder(folder) }
        chooseFolder("Sessions")
        compose.onNodeWithText("Record", substring = false).performClick()
        compose.waitUntil(10000) { RecordingService.state.value.elapsedMs > 250 }
        val id = RecordingService.state.value.sessionId
        chooseFolder("Unfiled")
        compose.onNodeWithText(" Save").performClick()
        compose.waitUntil(10000) { !RecordingService.state.value.active }
        assertEquals(folder.id, runBlocking { app.repository.dao.get(id)!!.folderId })
        chooseFolder("Sessions")
        compose.onNodeWithText(runBlocking { app.repository.dao.get(id)!!.title }).assertIsDisplayed()
    }
    @Test fun bestAvailableButtonAndSingleSelectorRetainAndDisplayActualFormat() {
        runBlocking { app.settings.set(Settings.RECORD_STYLE, RecordControlStyle.THREE_BUTTONS.name) }
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Record Best available").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Record Best available").performClick()
        compose.waitUntil(15000) { RecordingService.state.value.elapsedMs > 300 }
        val capture = RecordingService.state.value
        assert(capture.quality == Quality.BEST)
        compose.onNodeWithText(capture.format!!.label).assertIsDisplayed()
        compose.onNodeWithContentDescription("Recording format details").performClick()
        compose.onNodeWithText(capture.captureDetails!!).assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Save", substring = true).performClick()
        compose.waitUntil(5000) { !RecordingService.state.value.active }
        runBlocking { app.settings.set(Settings.RECORD_STYLE, RecordControlStyle.SINGLE_BUTTON.name); app.settings.set(Settings.SELECTED_RECORDING_SLOT, 1) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Best available · Auto").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Best available · Auto").performClick()
        compose.onNodeWithText("Record", useUnmergedTree = true).performClick()
        compose.waitUntil(15000) { RecordingService.state.value.elapsedMs > 200 }
        assert(RecordingService.state.value.quality == Quality.BEST)
        assert(runBlocking { app.settings.flow.first().selectedRecordingQuality == Quality.BEST })
        assertEquals(Settings.AUTO_RECORDING_SLOT, runBlocking { app.settings.flow.first().selectedRecordingSlot })
        compose.activityRule.scenario.recreate()
        assertEquals(Settings.AUTO_RECORDING_SLOT, runBlocking { app.settings.flow.first().selectedRecordingSlot })
    }
    private fun deletionFixture(id: String, title: String): Recording {
        val file = File(app.recordingsDir, "$id.wav")
        WavFile.header(file, 16000, 1, 320000)
        return Recording(id = id, title = title, createdAt = 123456, durationMs = 10000, fileName = file.name, sampleRate = 16000, channels = 1).also {
            runBlocking { app.repository.dao.insert(it) }
        }
    }
    @Test fun editorDeletionRequiresConfirmationAndStopsPlayback() {
        val item = deletionFixture("delete-editor", "Delete from editor")
        compose.waitUntil(5000) { compose.onAllNodesWithText(item.title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(item.title).performClick()
        compose.onNodeWithText(" Play original").performClick()
        compose.onNodeWithContentDescription("Delete recording").performClick()
        compose.onNodeWithText("Delete recording?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        Assert.assertTrue(app.repository.file(item).exists())
        Assert.assertNotNull(runBlocking { app.repository.dao.get(item.id) })
        compose.onNodeWithContentDescription("Delete recording").performClick()
        compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { runBlocking { app.repository.dao.get(item.id) == null } }
        compose.onNodeWithContentDescription("Choose folder").assertIsDisplayed()
        Assert.assertFalse(app.repository.file(item).exists())
        val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java]
        Assert.assertNull(vm.playback.value.recordingId)
    }
    @Test fun longPressAndBatchDeletionKeepUnselectedRecordings() {
        val first = deletionFixture("delete-a", "Delete A")
        val second = deletionFixture("delete-b", "Delete B")
        val keep = deletionFixture("keep-c", "Keep C")
        compose.waitUntil(5000) { compose.onAllNodesWithText(first.title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(first.title).performTouchInput { longClick() }
        compose.onNodeWithText("Delete recording").assertIsDisplayed().performClick()
        compose.onNodeWithText("Delete recording?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText(first.title).performTouchInput { longClick() }
        compose.onNodeWithText("Select multiple").performClick()
        compose.onNodeWithText(second.title).performClick()
        compose.onNodeWithContentDescription("Delete selected recordings").performClick()
        compose.onNodeWithText("Delete 2 recordings?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        Assert.assertTrue(app.repository.file(first).exists()); Assert.assertTrue(app.repository.file(second).exists())
        compose.onNodeWithContentDescription("Delete selected recordings").performClick()
        compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { runBlocking { app.repository.dao.get(first.id) == null && app.repository.dao.get(second.id) == null } }
        Assert.assertFalse(app.repository.file(first).exists()); Assert.assertFalse(app.repository.file(second).exists())
        Assert.assertTrue(app.repository.file(keep).exists())
        compose.onNodeWithText(keep.title).assertIsDisplayed()
    }
    @Test fun singleSelectionPersistsAndSettingsHaveNoSeparateDefaultQuality() {
        compose.onNodeWithText("Studio", substring = false).performClick()
        compose.waitUntil(5000) { runBlocking { app.settings.flow.first().selectedRecordingSlot == 2 } }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("Default quality").assertDoesNotExist()
        compose.onNodeWithText("Default recording quality").assertDoesNotExist()
        compose.onNodeWithText("Confirm before discarding").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to library").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Studio · WAV").assertIsDisplayed()
        compose.onNodeWithTag("quality-selector-3").assertIsSelected()
    }
    @Test fun threeDirectButtonsRecordTheirOwnQualityWithoutChangingSingleSelection() {
        runBlocking { app.settings.set(Settings.SELECTED_RECORDING_SLOT, 2); app.settings.set(Settings.RECORD_STYLE, RecordControlStyle.THREE_BUTTONS.name) }
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Record Voice").fetchSemanticsNodes().isNotEmpty() }
        Quality.fixedPresets.forEach { quality ->
            compose.onNodeWithContentDescription("Record ${quality.label}").performClick()
            compose.waitUntil(10000) { RecordingService.state.value.elapsedMs > 250 }
            val capture = RecordingService.state.value
            Assert.assertEquals(quality, capture.quality)
            compose.onNodeWithText(" Save").performClick()
            compose.waitUntil(10000) { !RecordingService.state.value.active }
            val rec = runBlocking { app.repository.dao.get(capture.sessionId)!! }
            val wav = WavFile.info(app.repository.file(rec))
            Assert.assertEquals(quality.sampleRate, wav.rate)
            Assert.assertEquals(quality.channels, wav.channels)
        }
        Assert.assertEquals(Quality.STUDIO, runBlocking { app.settings.flow.first().selectedRecordingQuality })
    }
    @Test fun controlStylePersistsAndSingleButtonUsesImmediateSelection() {
        runBlocking { app.settings.set(Settings.SELECTED_RECORDING_SLOT, 0) }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Three record buttons").performClick()
        compose.onNodeWithContentDescription("Back to library").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Record Studio").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("Record Studio").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("One button with quality selector").performClick()
        compose.onNodeWithContentDescription("Back to library").performClick()
        compose.onNodeWithText("Balanced", substring = false).performClick()
        compose.onNodeWithText("Record", substring = false).performClick()
        compose.waitUntil(10000) { RecordingService.state.value.elapsedMs > 250 }
        Assert.assertEquals(Quality.BALANCED, RecordingService.state.value.quality)
        val style = runBlocking { app.settings.flow.first().recordStyle }
        Assert.assertEquals(RecordControlStyle.SINGLE_BUTTON, style)
    }
    @Test fun editorExportsOverlappingSelectedWindowsAndPreservesSource() {
        val source = File(app.recordingsDir, "test-source.wav")
        WavFile.header(source, 16000, 1, 64000)
        RandomAccessFile(source, "rw").use { it.setLength(64044) }
        val originalBytes = source.readBytes()
        runBlocking(Dispatchers.IO) {
            app.repository.dao.insert(Recording(id = "ui-source", title = "Test recording", createdAt = 1792837800000, durationMs = 2000, fileName = source.name, sampleRate = 16000, channels = 1))
            app.repository.dao.window(CutWindow(id = "window-a", recordingId = "ui-source", startMs = 0, endMs = 1500, ordinal = 0))
            app.repository.dao.window(CutWindow(id = "window-b", recordingId = "ui-source", startMs = 500, endMs = 2000, ordinal = 1, title = "Second excerpt"))
            app.repository.dao.window(CutWindow(id = "window-c", recordingId = "ui-source", startMs = 0, endMs = 500, selected = false, ordinal = 2))
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Test recording").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Test recording").performClick()
        compose.onNodeWithText("Edit recording").assertIsDisplayed()
        compose.onNodeWithText("Window 1").performScrollTo()
        compose.onAllNodesWithText("Cut name")[0].performTextInput("Opening excerpt")
        compose.waitUntil(5000) { runBlocking { app.repository.dao.windows("ui-source").first().title == "Opening excerpt" } }
        compose.onNodeWithText(" Save all (2)").performClick()
        compose.waitUntil(20000) { runBlocking { app.repository.dao.observeAll().first().size == 3 } }
        Assert.assertArrayEquals(originalBytes, source.readBytes())
        val cuts = runBlocking { app.repository.dao.observeAll().first().filter { it.sourceId == "ui-source" } }
        Assert.assertEquals(2, cuts.size)
        Assert.assertTrue(cuts.all { it.durationMs == 1500L })
        Assert.assertEquals(setOf("Opening excerpt", "Second excerpt"), cuts.map { it.title }.toSet())
    }
    @Test fun recordingPausesResumesAndSaves() {
        runBlocking { app.settings.set(Settings.SELECTED_RECORDING_SLOT, 0) }
        compose.onNodeWithText("Record").performClick()
        compose.waitUntil(10000) { RecordingService.state.value.elapsedMs > 400 }
        compose.onNodeWithContentDescription("Add checkpoint while recording").performClick()
        compose.waitUntil(5000) { RecordingService.state.value.checkpointCount == 1 }
        compose.onNodeWithContentDescription("Pause recording").performClick()
        compose.waitUntil(5000) { RecordingService.state.value.paused }
        val elapsed = RecordingService.state.value.elapsedMs
        Thread.sleep(350)
        Assert.assertEquals(elapsed, RecordingService.state.value.elapsedMs)
        compose.onNodeWithContentDescription("Add checkpoint while recording").performClick()
        compose.waitUntil(5000) { RecordingService.state.value.checkpointCount == 2 }
        compose.onNodeWithContentDescription("Resume recording").performClick()
        compose.waitUntil(5000) { RecordingService.state.value.elapsedMs > elapsed + 300 }
        compose.onNodeWithText(" Save").performClick()
        compose.waitUntil(10000) { !RecordingService.state.value.active && runBlocking { app.repository.dao.observeAll().first().isNotEmpty() } }
        val recording = runBlocking { app.repository.dao.observeAll().first().single() }
        Assert.assertTrue(recording.durationMs > 600)
        Assert.assertEquals(16000, recording.sampleRate)
        Assert.assertTrue(app.repository.file(recording).exists())
        val points = runBlocking { app.repository.dao.checkpoints(recording.id) }
        Assert.assertEquals(2, points.size)
        Assert.assertTrue(points.first().positionMs in 1..elapsed)
        Assert.assertEquals(elapsed, points.last().positionMs)
    }
    @Test fun discardConfirmationCanBeDisabled() {
        runBlocking { app.settings.set(Settings.CONFIRM, true); app.settings.set(Settings.SELECTED_RECORDING_SLOT, 0) }
        compose.onNodeWithText("Record").performClick()
        compose.waitUntil(10000) { RecordingService.state.value.elapsedMs > 200 }
        compose.onNodeWithContentDescription("Stop and discard").performClick()
        compose.onNodeWithText("Discard this recording?").assertIsDisplayed()
        compose.onNodeWithText("Keep recording").performClick()
        Assert.assertTrue(RecordingService.state.value.active)
        compose.onNodeWithContentDescription("Stop and discard").performClick()
        compose.onNodeWithText("Discard", substring = false).performClick()
        compose.waitUntil(5000) { !RecordingService.state.value.active }
        Assert.assertTrue(runBlocking { app.repository.dao.observeAll().first().isEmpty() })
        runBlocking { app.settings.set(Settings.CONFIRM, false) }
        compose.onNodeWithText("Record").performClick()
        compose.waitUntil(10000) { RecordingService.state.value.elapsedMs > 200 }
        compose.onNodeWithContentDescription("Stop and discard").performClick()
        compose.waitUntil(5000) { !RecordingService.state.value.active }
        compose.onNodeWithText("Discard this recording?").assertDoesNotExist()
        Assert.assertTrue(runBlocking { app.repository.dao.observeAll().first().isEmpty() })
    }
}
