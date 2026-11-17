package app.stillwave

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.stillwave.audio.*
import app.stillwave.data.Quality
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

/** Uses only emulator fixtures; does not run as part of the physical-phone hardware test. */
class MicrophoneProfileTest {
    private val app = ApplicationProvider.getApplicationContext<StillwaveApp>()
    private lateinit var activity: ActivityScenario<MainActivity>
    @Before fun prepare() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant app.stillwave android.permission.RECORD_AUDIO").close()
        activity = ActivityScenario.launch(MainActivity::class.java)
        runBlocking { app.ready.await(); app.microphoneProfiles.flow.first().forEach { app.microphoneProfiles.forget(it.key) } }
    }
    @After fun close() { activity.close() }
    private suspend fun capture(): Pair<PcmFormat, String> {
        val capture = CaptureNegotiator(app).open(Quality.BEST)
        return try { capture.format to capture.details }
        finally { capture.record.stop(); capture.record.release() }
    }
    @Test fun remembersValidatedFormatAndCanForgetIt() = runBlocking(Dispatchers.IO) {
        val first = capture()
        val stored = MicrophoneProfiles(app).flow.first().single()
        assertEquals(first.first, stored.format)
        assertTrue(java.io.File(app.filesDir, "datastore/microphone_profiles.preferences_pb").length() > 0)
        val second = capture()
        assertEquals(first.first, second.first)
        assertTrue(second.second.contains("Remembered microphone format validated"))
        app.microphoneProfiles.forget(stored.key)
        assertTrue(app.microphoneProfiles.flow.first().isEmpty())
        assertFalse(capture().second.contains("Remembered microphone format validated"))
    }
    @Test fun changedConfigurationInvalidatesTheSavedChoice() = runBlocking(Dispatchers.IO) {
        capture()
        val stored = app.microphoneProfiles.flow.first().single()
        app.microphoneProfiles.remember(stored.copy(signature = "previous-firmware"))
        assertTrue(capture().second.contains("configuration changed"))
        assertNotEquals("previous-firmware", app.microphoneProfiles.find(stored.key)!!.signature)
    }
    @Test fun UnadvertisedSavedFormatIsReplacedInsteadOfSilentlyUpsampling() = runBlocking(Dispatchers.IO) {
        val first = capture()
        val stored = app.microphoneProfiles.flow.first().single()
        app.microphoneProfiles.remember(stored.copy(format = PcmFormat(192000, 2, PcmEncoding.PCM32)))
        val second = capture()
        assertEquals(first.first, second.first)
        assertFalse(second.second.contains("Remembered microphone format validated"))
        assertEquals(first.first, app.microphoneProfiles.find(stored.key)!!.format)
    }
}
