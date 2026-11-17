package app.stillwave

import android.content.Intent
import android.widget.FrameLayout
import android.widget.TextView
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.stillwave.audio.CaptureState
import app.stillwave.audio.RecordingService
import app.stillwave.audio.WavFile
import app.stillwave.data.*
import app.stillwave.widget.RecorderWidget
import app.stillwave.widget.WidgetPermissionActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class WidgetTest {
    private val app = ApplicationProvider.getApplicationContext<StillwaveApp>()
    private lateinit var activity: ActivityScenario<MainActivity>
    @Before fun setup() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant app.stillwave android.permission.RECORD_AUDIO").close()
        activity = ActivityScenario.launch(MainActivity::class.java)
        runBlocking { app.ready.await(); app.settings.set(Settings.SELECTED_RECORDING_SLOT, 0); app.settings.set(Settings.CONFIRM, true) }
        runBlocking { Settings.RECORDING_SLOTS.forEachIndexed { i, key -> app.settings.set(key, Quality.fixedPresets[i].name) } }
    }
    @After fun cleanup() {
        if (RecordingService.state.value.active) { RecordingService.command(app, RecordingService.DISCARD); await { !it.active } }
        activity.close()
    }
    private fun await(predicate: (CaptureState) -> Boolean) = runBlocking {
        withTimeout(10_000) { while (!predicate(RecordingService.state.value)) delay(30) }
    }
    private fun click(id: Int) {
        activity.onActivity { screen ->
            val view = RecorderWidget.views(app, RecordingService.state.value).apply(screen, FrameLayout(screen))
            val button = view.findViewById<View>(id)
            assertTrue("Widget button must be enabled", button.isEnabled)
            assertTrue(button.performClick())
        }
    }
    @Test fun remoteViewsRecordPauseCheckpointResumeSaveAndRejectStaleDiscard() {
        click(R.id.widget_voice); await { it.elapsedMs > 250 }
        val session = RecordingService.state.value.sessionId
        click(R.id.widget_record); await { it.paused }
        val elapsed = RecordingService.state.value.elapsedMs
        val pausedViews = RecorderWidget.views(app, RecordingService.state.value)
        Thread.sleep(1100) // A launcher can apply cached RemoteViews long after the pause.
        activity.onActivity { screen ->
            val delayed = pausedViews.apply(screen, FrameLayout(screen))
            assertEquals(durationLabel(elapsed), delayed.findViewById<TextView>(R.id.widget_timer).text.toString())
        }
        click(R.id.widget_checkpoint); await { it.checkpointCount == 1 }
        assertEquals(elapsed, RecordingService.state.value.lastCheckpointMs)
        click(R.id.widget_record); await { !it.paused && it.elapsedMs > elapsed + 250 }
        click(R.id.widget_save); await { !it.active }
        val idleViews = RecorderWidget.views(app, RecordingService.state.value)
        Thread.sleep(1100)
        activity.onActivity { screen ->
            assertEquals("00:00", idleViews.apply(screen, FrameLayout(screen)).findViewById<TextView>(R.id.widget_timer).text.toString())
        }
        runBlocking {
            val saved = app.repository.dao.get(session)!!
            assertEquals(16000, saved.sampleRate)
            assertEquals(elapsed, app.repository.dao.checkpoints(session).single().positionMs)
        }
        click(R.id.widget_voice); await { it.elapsedMs > 200 }
        app.startService(Intent(app, RecordingService::class.java).setAction(RecordingService.DISCARD).putExtra(RecordingService.SESSION, session))
        Thread.sleep(250)
        assertTrue(RecordingService.state.value.active)
        assertNotEquals(session, RecordingService.state.value.sessionId)
    }
    @Test fun remoteViewsDiscardUsesPreferenceAndSupportsCancellation() {
        click(R.id.widget_voice); await { it.elapsedMs > 200 }
        val session = RecordingService.state.value.sessionId
        click(R.id.widget_checkpoint); await { it.checkpointCount == 1 }
        click(R.id.widget_discard); await { it.discardConfirmation }
        click(R.id.widget_cancel); await { !it.discardConfirmation }
        assertTrue(RecordingService.state.value.active)
        click(R.id.widget_discard); await { it.discardConfirmation }
        click(R.id.widget_confirm); await { !it.active }
        assertNull(runBlocking { app.repository.dao.get(session) })
        assertTrue(runBlocking { app.repository.dao.checkpoints(session).isEmpty() })
        runBlocking { app.settings.set(Settings.CONFIRM, false) }
        click(R.id.widget_voice); await { it.elapsedMs > 200 }
        click(R.id.widget_discard); await { !it.active }
        assertFalse(RecordingService.state.value.discardConfirmation)
    }
    @Test fun allThreeWidgetButtonsSaveTheirOwnFormatsRegardlessOfSingleSelection() {
        runBlocking { app.settings.set(Settings.SELECTED_RECORDING_SLOT, 2) }
        val presets = listOf(R.id.widget_voice to Quality.VOICE, R.id.widget_balanced to Quality.BALANCED, R.id.widget_studio to Quality.STUDIO)
        val intents = presets.map { RecorderWidget.action(app, RecordingService.START, "", it.second) }
        assertEquals(3, intents.toSet().size)
        presets.forEach { (button, quality) ->
            click(button); await { it.elapsedMs > 250 }
            val state = RecordingService.state.value
            assertEquals(quality, state.quality)
            click(R.id.widget_save); await { !it.active }
            val rec = runBlocking { app.repository.dao.get(state.sessionId)!! }
            val info = WavFile.info(app.repository.file(rec))
            assertEquals(quality.sampleRate, info.rate)
            assertEquals(quality.channels, info.channels)
        }
        assertEquals(Quality.STUDIO, runBlocking { app.settings.flow.first().selectedRecordingQuality })
    }
    @Test fun bestAvailableWidgetValidatesAndSavesTheActualFormat() {
        click(R.id.widget_best); await { it.elapsedMs > 300 }
        val capture = RecordingService.state.value
        assertEquals(Quality.BEST, capture.quality)
        assertNotNull(capture.format); assertNotNull(capture.inputName); assertNotNull(capture.captureDetails)
        click(R.id.widget_checkpoint); await { it.checkpointCount == 1 }
        click(R.id.widget_save); await { !it.active }
        val saved = runBlocking { app.repository.dao.get(capture.sessionId)!! }
        assertEquals(capture.format, WavFile.info(app.repository.file(saved)).format)
        assertEquals(capture.format, saved.format())
        assertEquals(capture.inputName, saved.inputName)
        assertEquals(capture.captureDetails, saved.captureDetails)
        assertEquals(Quality.VOICE, runBlocking { app.settings.flow.first().selectedRecordingQuality })
    }
    @Test fun permissionActivityRetainsRequestedPresetInsteadOfSingleSelection() {
        runBlocking { app.settings.set(Settings.SELECTED_RECORDING_SLOT, 0) }
        val intent = Intent(app, WidgetPermissionActivity::class.java).putExtra(RecordingService.QUALITY, Quality.STUDIO.name)
        ActivityScenario.launch<WidgetPermissionActivity>(intent).use {
            await { it.elapsedMs > 250 }
            assertEquals(Quality.STUDIO, RecordingService.state.value.quality)
            val session = RecordingService.state.value.sessionId
            click(R.id.widget_save); await { !it.active }
            val rec = runBlocking { app.repository.dao.get(session)!! }
            assertEquals(48000, rec.sampleRate)
            assertEquals(2, rec.channels)
        }
    }

    @Test fun missingWidgetPresetUsesBestAvailableWithoutHiddenQuality() {
        runBlocking { app.settings.set(Settings.SELECTED_RECORDING_SLOT, 2) }
        activity.onActivity { RecorderWidget.action(app, RecordingService.START, "").send() }
        await { it.elapsedMs > 250 }
        assertEquals(Quality.BEST, RecordingService.state.value.quality)
        assertNotNull(RecordingService.state.value.format)
        assertEquals(2, runBlocking { app.settings.flow.first().selectedRecordingSlot })
    }
}
