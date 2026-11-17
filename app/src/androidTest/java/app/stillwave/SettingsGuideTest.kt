package app.stillwave

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.stillwave.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals

/** Guide navigation must not discard an uncommitted quality-picker draft. */
class SettingsGuideTest {
    @get:org.junit.Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as StillwaveApp
    private lateinit var before: AppSettings

    @Before fun prepare() = runBlocking {
        app.ready.await()
        before = app.settings.flow.first()
        Settings.RECORDING_SLOTS.forEachIndexed { i, key -> app.settings.set(key, Quality.fixedPresets[i].name) }
        app.settings.set(Settings.RECORD_STYLE, RecordControlStyle.THREE_BUTTONS.name)
    }

    @After fun restore() = runBlocking {
        Settings.RECORDING_SLOTS.forEachIndexed { i, key -> app.settings.set(key, before.recordingSlots[i].name) }
        app.settings.set(Settings.RECORD_STYLE, before.recordStyle.name)
    }

    @Test fun settingsGuideSurvivesRecreationAndReturnsToSettingsWithoutDefaultQuality() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Default recording quality").assertDoesNotExist()
        compose.onNodeWithTag("Default quality").assertDoesNotExist()
        compose.onNodeWithText(" Recording quality guide").performScrollTo().performClick()
        compose.onNodeWithTag("quality-guide").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("quality-guide").assertIsDisplayed()
        compose.onNodeWithTag("quality-guide").performScrollToNode(hasTestTag("quality-guide-together"))
        compose.onNodeWithTag("quality-guide-together").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back from quality guide").performClick()
        compose.onNodeWithContentDescription("Back to library").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Record Best available").assertIsDisplayed()
    }

    @Test fun guideFromPickerPreservesUnsavedAacChoicesAndSystemBackReturnsToPicker() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("Recording button 1").performScrollTo().performClick()
        compose.onNodeWithText("AAC", substring = false).performClick()
        compose.onNodeWithTag("AAC Sample rate").performClick()
        compose.onNodeWithTag("AAC Sample rate 24000").performClick()
        compose.onNodeWithTag("AAC Target bitrate").performClick()
        compose.onNodeWithTag("AAC Target bitrate 64000").performClick()
        compose.onNodeWithText("Mono", substring = false).performClick()
        compose.onNodeWithTag("quality-picker-guide").performClick()
        compose.onNodeWithTag("quality-guide").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("quality-guide").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("quality-guide").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("AAC Sample rate").assertTextContains("24000 Hz")
        compose.onNodeWithTag("AAC Target bitrate").assertTextContains("64 kbps")
        assertEquals(Quality.VOICE, runBlocking { app.settings.flow.first().recordingSlots[0] })
        compose.onNodeWithText("Assign quality").performClick()
        val expected = Quality.aac(24000, 64000, 1)
        compose.waitUntil(5000) { runBlocking { app.settings.flow.first().recordingSlots[0] == expected } }
        compose.onNodeWithContentDescription("Back to library").performClick()
        compose.onNodeWithContentDescription("Record AAC 64").assertIsDisplayed()
    }
}
