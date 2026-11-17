package app.stillwave

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import app.stillwave.audio.WavFile
import app.stillwave.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Uses only its own fixtures; verifies menu access and selection in every library layout. */
class LongPressSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as StillwaveApp
    private val vm get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]
    private lateinit var before: AppSettings
    private val fixtures = mutableListOf<Recording>()

    @Before fun prepare() {
        runBlocking {
            app.ready.await()
            before = app.settings.flow.first()
            app.settings.set(Settings.LONG_PRESS_ACTION, LongPressAction.MENU.name)
            app.settings.set(Settings.LAYOUT, LibraryLayout.LIST.name)
            repeat(2) { index ->
                val id = UUID.randomUUID().toString()
                val recording = Recording(id = id, title = "Long press fixture $index", createdAt = System.currentTimeMillis() + index,
                    durationMs = 1000, fileName = "$id.wav", sampleRate = 16000, channels = 1)
                WavFile.header(File(app.recordingsDir, recording.fileName), 16000, 1, 32000)
                app.repository.dao.insert(recording)
                fixtures += recording
            }
        }
        compose.runOnUiThread { vm.filter.value = LibraryFilter(search = "Long press fixture"); vm.selectFolder(ALL_FOLDERS) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("recording-${fixtures[0].id}").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun restore() {
        runBlocking {
            app.repository.delete(fixtures.map { it.id })
            app.settings.set(Settings.LONG_PRESS_ACTION, before.longPressAction.name)
            app.settings.set(Settings.LAYOUT, before.layout.name)
        }
    }

    @Test fun menuModeKeepsMenuAndExistingSelectionTogglesOnLongPress() {
        assertEquals(LongPressAction.MENU, AppSettings().longPressAction)
        val first = "recording-${fixtures[0].id}"
        val second = "recording-${fixtures[1].id}"
        compose.onNodeWithTag(first).performTouchInput { longClick() }
        compose.onNodeWithText("File details").assertIsDisplayed()
        compose.onNodeWithText("Select multiple").performClick()
        compose.onNodeWithTag(first).assertIsSelected()
        compose.onNodeWithTag(second).performTouchInput { longClick() }
        compose.onNodeWithTag(second).assertIsSelected()
        compose.onNodeWithText("File details").assertDoesNotExist()
        compose.onNodeWithTag(first).performTouchInput { longClick() }
        compose.onNodeWithTag(first).assertIsNotSelected()
        compose.onNodeWithTag(second).assertIsSelected()
    }

    @Test fun settingPersistsAndAllLayoutsSelectImmediatelyWhileOverflowKeepsMenu() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("long-press-MULTI_SELECT").performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { app.settings.flow.first().longPressAction == LongPressAction.MULTI_SELECT } }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("long-press-MULTI_SELECT").performScrollTo().assertIsSelected()
        assertEquals(LongPressAction.MULTI_SELECT, runBlocking { Settings(app).flow.first().longPressAction })
        compose.onNodeWithContentDescription("Back to library").performClick()
        val first = "recording-${fixtures[0].id}"
        val second = "recording-${fixtures[1].id}"
        LibraryLayout.entries.forEach { layout ->
            runBlocking { app.settings.set(Settings.LAYOUT, layout.name) }
            compose.waitUntil(5000) { vm.settings.value.layout == layout }
            compose.waitForIdle()
            compose.onNodeWithTag(first).performTouchInput { longClick() }
            compose.onNodeWithTag(first).assertIsSelected()
            compose.onNodeWithContentDescription("Clear selection").assertIsDisplayed()
            compose.onNodeWithText("File details").assertDoesNotExist()
            compose.onNodeWithTag(second).performClick().assertIsSelected()
            compose.onNodeWithTag(first).performTouchInput { longClick() }
            compose.onNodeWithTag(first).assertIsNotSelected()
            compose.onNodeWithTag(second).assertIsSelected()
            compose.onNodeWithContentDescription("Clear selection").performClick()
            compose.onNode(hasContentDescription("Recording actions") and hasAnyAncestor(hasTestTag(first))).performClick()
            compose.onNodeWithText("File details").assertIsDisplayed()
            compose.onNodeWithText("Select multiple").performClick()
            compose.onNodeWithTag(first).assertIsSelected()
            compose.onNodeWithContentDescription("Clear selection").performClick()
        }
    }
}
