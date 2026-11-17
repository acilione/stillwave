package app.stillwave

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.stillwave.audio.CaptureState
import app.stillwave.data.Quality
import app.stillwave.data.RecordControlStyle
import app.stillwave.data.Settings
import app.stillwave.ui.CapturePanel
import app.stillwave.ui.StillwaveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Simulates delayed preference acknowledgements without opening a microphone or changing settings. */
class CaptureSelectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rapidSelectionKeepsLastTappedSlotDuringDelayedPreferenceEmissions() {
        var persistedSlot by mutableIntStateOf(0)
        val writes = mutableListOf<Int>()
        val recordings = mutableListOf<Quality>()
        compose.setContent {
            StillwaveTheme {
                CapturePanel(CaptureState(), persistedSlot, RecordControlStyle.SINGLE_BUTTON,
                    selectSlot = { writes += it }, record = { recordings += it }, command = {})
            }
        }

        // A -> B -> A while the first preference write is still pending.
        compose.onNodeWithTag("quality-selector-2").performClick()
        compose.onNodeWithTag("quality-selector-1").performClick()
        compose.runOnIdle {
            assertEquals(listOf(1, 0), writes)
            persistedSlot = 1 // Late acknowledgement of B must not replace the user's latest A.
        }
        compose.onNodeWithTag("quality-selector-1").assertIsSelected()
        compose.onNodeWithTag("quality-selector-2").assertIsNotSelected()
        compose.onNodeWithText("Record", substring = false).performClick()
        compose.runOnIdle {
            assertEquals(listOf(Quality.VOICE), recordings)
            persistedSlot = 0
        }

        // The same ordering guarantee applies to the separate automatic option.
        compose.onNodeWithText("Best available · Auto").performClick()
        compose.onNodeWithTag("quality-selector-3").performClick()
        compose.onNodeWithText("Best available · Auto").performClick()
        compose.runOnIdle { persistedSlot = 2 }
        compose.onNodeWithText("Best available · Auto").assertIsSelected()
        compose.onNodeWithTag("quality-selector-3").assertIsNotSelected()
        compose.onNodeWithText("Record", substring = false).performClick()
        compose.runOnIdle {
            assertEquals(listOf(Quality.VOICE, Quality.BEST), recordings)
            assertEquals(listOf(1, 0, Settings.AUTO_RECORDING_SLOT, 2, Settings.AUTO_RECORDING_SLOT), writes)
            persistedSlot = Settings.AUTO_RECORDING_SLOT
        }
        compose.onNodeWithText("Best available · Auto").assertIsSelected()
    }
}
