package app.stillwave

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.stillwave.audio.RecordingService
import app.stillwave.audio.WavFile
import app.stillwave.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.text.NumberFormat

/** Emulator-only UI fixtures: clears the test library. Never run on a user's phone. */
class LibraryFeaturesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as StillwaveApp
    @Before fun setup() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("pm grant app.stillwave android.permission.RECORD_AUDIO").close()
        automation.executeShellCommand("pm grant app.stillwave android.permission.POST_NOTIFICATIONS").close()
        runBlocking {
            app.ready.await(); app.database.clearAllTables()
            app.settings.set(Settings.RECORD_STYLE, RecordControlStyle.THREE_BUTTONS.name)
            app.settings.set(Settings.LAYOUT, LibraryLayout.LIST.name)
            app.settings.set(Settings.SORT, SortOrder.NEWEST.name)
            app.settings.set(Settings.CONFIRM, false)
        }
    }
    @After fun cleanup() {
        if (RecordingService.state.value.active) {
            RecordingService.command(app, RecordingService.DISCARD)
            compose.waitUntil(5000) { !RecordingService.state.value.active }
        }
    }
    private fun fixtures(count: Int): List<Recording> {
        val items = (0 until count).map { index ->
            val file = File(app.recordingsDir, "library-feature-$index.wav")
            WavFile.header(file, 16000, 1, 64000)
            Recording(id = "library-feature-$index", title = "Fixture $index", createdAt = 100000L - index * 1000,
                durationMs = 2000, fileName = file.name, sampleRate = 16000, channels = 1)
        }
        runBlocking { items.forEach { app.repository.dao.insert(it) } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("recordingLibrary").fetchSemanticsNodes().isNotEmpty() }
        return items
    }
    @Test fun fileDetailsAvailableFromMenuAndSingleSelection() {
        val item = fixtures(1).single()
        compose.onNodeWithText(item.title).performTouchInput { longClick() }
        compose.onNodeWithText("File details").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("File size").fetchSemanticsNodes().isNotEmpty() }
        val size = app.repository.file(item).length()
        compose.onNodeWithText("${NumberFormat.getIntegerInstance().format(size)} bytes", substring = true).assertIsDisplayed()
        compose.onNodeWithText("WAV (.wav)").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText(item.title).performTouchInput { longClick() }
        compose.onNodeWithText("Select multiple").performClick()
        compose.onNodeWithContentDescription("Selected recording details").performClick()
        compose.onNodeWithText("Recording details").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
    }
    @Test fun fullScreenLibraryExpandsAndRetainsScrollAcrossRecreation() {
        fixtures(40)
        val normalHeight = compose.onNodeWithTag("recordingLibrary").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("recordingLibrary").performScrollToIndex(20)
        compose.onNodeWithContentDescription("Expand library").performClick()
        compose.onNodeWithContentDescription("Record Voice").assertDoesNotExist()
        val fullHeight = compose.onNodeWithTag("recordingLibrary").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Expanded library should reclaim the recorder area", fullHeight > normalHeight + 200)
        compose.onNodeWithText("Fixture 20").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("Exit full-screen library").assertIsDisplayed()
        compose.onNodeWithText("Fixture 20").assertIsDisplayed()
        compose.onNodeWithContentDescription("Exit full-screen library").performClick()
        compose.onNodeWithContentDescription("Record Voice").assertIsDisplayed()
        compose.onNodeWithText("Fixture 20").assertIsDisplayed()
    }
    @Test fun searchIsRevealedOnDemandAndClosingItRestoresTheLibrary() {
        fixtures(3)
        lateinit var vm: AppViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        compose.onNodeWithTag("recordingSearch").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search recordings").performClick()
        compose.onNodeWithTag("recordingSearch").assertIsFocused().performTextInput("Fixture 2")
        compose.waitUntil(5000) {
            vm.recordings.value.map { it.title } == listOf("Fixture 2")
        }
        compose.onNode(hasText("Fixture 2") and !hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText("Fixture 0").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("recordingSearch").assertTextContains("Fixture 2")
        compose.onNode(hasText("Fixture 2") and !hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithContentDescription("Close search").performClick()
        compose.waitUntil(5000) { vm.recordings.value.size == 3 }
        compose.onNodeWithTag("recordingSearch").assertDoesNotExist()
        compose.onNodeWithText("Fixture 0").assertIsDisplayed()

        // Back dismisses search before leaving the expanded library, and never leaves a hidden query.
        compose.onNodeWithContentDescription("Expand library").performClick()
        compose.onNodeWithContentDescription("Search recordings").performClick()
        compose.onNodeWithTag("recordingSearch").performTextInput("Fixture 1")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("recordingSearch").assertDoesNotExist()
        compose.onNodeWithContentDescription("Exit full-screen library").assertIsDisplayed()
        compose.waitUntil(5000) { vm.recordings.value.size == 3 }
        compose.onNodeWithText("Fixture 0").assertIsDisplayed()
    }
    @Test fun saveRevealsNewRecordingAndDiscardKeepsScrollPosition() {
        fixtures(40)
        compose.onNodeWithTag("recordingLibrary").performScrollToIndex(25)
        val idleTop = compose.onNodeWithTag("recordingLibrary").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithContentDescription("Record Voice").performClick()
        compose.waitUntil(10000) { RecordingService.state.value.elapsedMs > 250 }
        assertEquals("Starting capture must not push the library down", idleTop,
            compose.onNodeWithTag("recordingLibrary").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithContentDescription("Pause recording").performClick()
        compose.waitUntil(5000) { RecordingService.state.value.paused }
        assertEquals("Pausing capture must keep the library in place", idleTop,
            compose.onNodeWithTag("recordingLibrary").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithContentDescription("Stop and discard").performClick()
        compose.waitUntil(5000) { !RecordingService.state.value.active }
        assertNull(RecordingService.lastSavedRecordingId.value)
        assertEquals("Discarding capture must keep the library in place", idleTop,
            compose.onNodeWithTag("recordingLibrary").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithText("Fixture 25").assertIsDisplayed()
        compose.onNodeWithContentDescription("Record Voice").performClick()
        compose.waitUntil(10000) { RecordingService.state.value.elapsedMs > 250 }
        val id = RecordingService.state.value.sessionId
        compose.onNodeWithText(" Save").performClick()
        compose.waitUntil(10000) { RecordingService.lastSavedRecordingId.value == id }
        val title = runBlocking { app.repository.dao.get(id)!!.title }
        compose.waitUntil(5000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(title).assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("recordingLibrary").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() < 1f)
    }
    @Test fun savedAudioIsRevealedEvenWhenFolderAndSearchWouldHideIt() {
        fixtures(4)
        val folder = RecordingFolder(name = "Other folder")
        runBlocking { app.repository.dao.insertFolder(folder) }
        compose.onNodeWithContentDescription("Record Voice").performClick()
        compose.waitUntil(10000) { RecordingService.state.value.elapsedMs > 250 }
        val id = RecordingService.state.value.sessionId
        compose.runOnIdle {
            val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            vm.selectFolder(folder.id); vm.filter.value = LibraryFilter(search = "does not match")
        }
        compose.onNodeWithText(" Save").performClick()
        compose.waitUntil(10000) { RecordingService.lastSavedRecordingId.value == id }
        val title = runBlocking { app.repository.dao.get(id)!!.title }
        compose.waitUntil(5000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText("Unfiled").assertIsDisplayed()
        compose.runOnIdle {
            val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            assertEquals("", vm.filter.value.search)
        }
    }
}
