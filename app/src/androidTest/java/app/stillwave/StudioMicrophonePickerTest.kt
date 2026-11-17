package app.stillwave

import android.os.Build
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import app.stillwave.audio.MicrophoneInputs
import app.stillwave.audio.RecordingService
import app.stillwave.data.AppSettings
import app.stillwave.data.Quality
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** In-project input selection shares the Settings preference; all fixtures are private to each test. */
class StudioMicrophonePickerTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as StillwaveApp
    private val vm: AppViewModel get() = if (Looper.myLooper() == Looper.getMainLooper()) ViewModelProvider(compose.activity)[AppViewModel::class.java]
        else playbackMain { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
    private lateinit var before: AppSettings
    private var projectId: String? = null

    @Before fun prepare() {
        check(!RecordingService.state.value.active) { "Do not interrupt an existing recording" }
        before = runBlocking { app.ready.await(); app.settings.flow.first() }
        runBlocking { app.settings.selectMicrophone("", "") }
        val title = "Input picker test ${UUID.randomUUID()}"
        playbackMain { vm.studio.create(title, loop = false) }
        compose.waitUntil(10_000) { vm.studio.project.value?.title == title }
        projectId = checkNotNull(vm.studio.project.value).id
        revealPicker()
    }

    @After fun restore() {
        if (!::before.isInitialized) return
        if (projectId != null && RecordingService.state.value.active && RecordingService.state.value.projectId == projectId) {
            RecordingService.command(app, RecordingService.DISCARD)
            playbackAwait { !RecordingService.state.value.active }
        }
        playbackMain { vm.pausePlayback() }
        runBlocking {
            projectId?.let { app.multitrackStore.delete(it) }
            app.settings.selectMicrophone(before.selectedMicrophone, before.selectedMicrophoneName)
        }
    }

    private fun revealPicker() {
        compose.onNodeWithTag("studio-tracks").performScrollToNode(hasTestTag("studio-microphone-picker"))
        compose.onNodeWithTag("studio-microphone-picker").assertIsDisplayed()
    }

    private fun openPicker() {
        revealPicker()
        compose.onNodeWithTag("studio-microphone-picker").performClick()
        compose.onNodeWithTag("studio-microphone-options").assertIsDisplayed()
    }

    @Test fun selectedInputPersistsAcrossProjectRecreationAndMatchesSettings() {
        val input = MicrophoneInputs.options(app).firstOrNull()
        assumeTrue("Requires a capture input", input != null)
        val selected = checkNotNull(input)
        openPicker()
        compose.onNodeWithTag("studio-microphone-auto").assertIsSelected()
        compose.onNodeWithTag("studio-microphone-options").performScrollToNode(hasTestTag("studio-microphone-${selected.key}"))
        compose.onNodeWithTag("studio-microphone-${selected.key}").performClick()
        compose.waitUntil(5000) { vm.settings.value.selectedMicrophone == selected.key }
        assertEquals(selected.label, runBlocking { app.settings.flow.first().selectedMicrophoneName })
        compose.onNodeWithTag("studio-microphone-options").assertDoesNotExist()
        compose.onNodeWithTag("studio-microphone-picker").assertTextContains(selected.label)

        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { vm.studio.project.value?.id == projectId }
        openPicker()
        compose.onNodeWithTag("studio-microphone-options").performScrollToNode(hasTestTag("studio-microphone-${selected.key}"))
        compose.onNodeWithTag("studio-microphone-${selected.key}").assertIsSelected()
        compose.onNodeWithText("Close", substring = false).performClick()
        playbackMain { vm.studio.back(); vm.studio.back() }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("microphone-${selected.key}").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("Back to library").performClick()

        playbackMain { vm.studio.show(projectId) }
        compose.waitUntil(10_000) { vm.studio.project.value?.id == projectId }
        openPicker()
        compose.onNodeWithTag("studio-microphone-auto").performClick()
        compose.waitUntil(5000) { vm.settings.value.selectedMicrophone.isEmpty() }
        assertEquals("", runBlocking { app.settings.flow.first().selectedMicrophoneName })
        compose.onNodeWithTag("studio-microphone-picker").assertTextContains("Automatic")
        assertEquals(projectId, vm.studio.project.value?.id)
    }

    @Test fun disconnectedInputCanBeReplacedWithoutLeavingTheProject() {
        runBlocking { app.settings.selectMicrophone("missing-input-picker-test", "Disconnected USB input") }
        compose.waitUntil(5000) { vm.settings.value.selectedMicrophone == "missing-input-picker-test" }
        revealPicker()
        compose.onNodeWithTag("studio-tracks").performScrollToNode(hasTestTag("studio-microphone-unavailable"))
        compose.onNodeWithTag("studio-microphone-unavailable").assertIsDisplayed()
        compose.onNodeWithTag("studio-microphone-picker").assertTextContains("Disconnected USB input")
        openPicker()
        compose.onNodeWithTag("studio-microphone-auto").assertIsNotSelected().performClick()
        compose.waitUntil(5000) { vm.settings.value.selectedMicrophone.isEmpty() }
        compose.onNodeWithTag("studio-microphone-unavailable").assertDoesNotExist()
        assertEquals(projectId, vm.studio.project.value?.id)
    }

    @Test fun anActiveTakeDismissesThePickerAndKeepsItsMicrophoneFixed() {
        assumeTrue("Capture fixture runs only on an emulator", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk", true) ||
            Build.HARDWARE in setOf("ranchu", "goldfish", "cutf_cvm"))
        playbackShell("pm grant app.stillwave android.permission.RECORD_AUDIO")
        openPicker()
        playbackMain { RecordingService.command(compose.activity, RecordingService.START, Quality.VOICE, projectId = projectId) }
        playbackAwait { RecordingService.state.value.active && RecordingService.state.value.elapsedMs >= 150 }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("studio-microphone-options").fetchSemanticsNodes().isEmpty() }
        revealPicker()
        compose.onNodeWithTag("studio-microphone-picker").assertIsNotEnabled()
        assertEquals("", runBlocking { app.settings.flow.first().selectedMicrophone })
        assertEquals(projectId, RecordingService.state.value.projectId)
        RecordingService.command(app, RecordingService.DISCARD)
        playbackAwait { !RecordingService.state.value.active }
        revealPicker()
        compose.onNodeWithTag("studio-microphone-picker").assertIsEnabled()
        openPicker()
        compose.onNodeWithTag("studio-microphone-auto").assertIsSelected()
    }
}
