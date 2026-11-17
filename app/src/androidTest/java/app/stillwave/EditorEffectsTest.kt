package app.stillwave

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.stillwave.audio.WavFile
import app.stillwave.data.*
import java.io.RandomAccessFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Emulator-only UI fixtures. Never run this class on a phone with personal recordings. */
@RunWith(AndroidJUnit4::class)
class EditorEffectsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as StillwaveApp
    private lateinit var source: Recording
    private lateinit var original: ByteArray

    @Before fun prepare() = runBlocking(Dispatchers.IO) {
        app.ready.await()
        app.database.clearAllTables()
        source = Recording(id = "effects-ui-source", title = "Window effects test", createdAt = 123456L,
            durationMs = 4000L, fileName = "effects-ui-source.wav", sampleRate = 16000, channels = 1)
        val file = app.repository.file(source)
        WavFile.header(file, 16000, 1, 128000)
        RandomAccessFile(file, "rw").use { audio ->
            audio.seek(WavFile.HEADER_SIZE)
            repeat(64000) { frame -> audio.writeShort(java.lang.Short.reverseBytes(((frame % 1000) - 500).toShort()).toInt()) }
        }
        original = file.readBytes()
        app.repository.add(source)
    }

    private fun openEditor() {
        compose.waitUntil(5000) { compose.onAllNodesWithText(source.title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(source.title).performClick()
        compose.onNodeWithText("Edit recording").assertIsDisplayed()
    }

    @Test fun windowEffectsPersistAndSaveSeparatelyOrInFullAudio() {
        runBlocking { app.repository.dao.window(CutWindow(id = "effects-window", recordingId = source.id, startMs = 1000, endMs = 2000)) }
        openEditor()
        compose.onNodeWithContentDescription("Reverse window 1").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Speed for window 1").performScrollTo().performClick()
        compose.onNodeWithText("2×", substring = false).performClick()
        compose.onNodeWithContentDescription("Keep pitch for window 1").performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { app.repository.dao.windows(source.id).single().let { it.reverse && it.speed == 2.0 && it.preservePitch } } }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("Reverse window 1").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Speed 2×").assertIsDisplayed()
        compose.onNodeWithContentDescription("Keep pitch for window 1").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithText(" Save all (1)").performClick()
        compose.waitUntil(20000) { runBlocking { app.repository.dao.observeAll().first().size == 2 } }
        val cut = runBlocking { app.repository.dao.observeAll().first().single { it.sourceId == source.id } }
        assertEquals(500L, cut.durationMs)
        compose.waitUntil(10000) { compose.onNodeWithText("Save full audio").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() }
        // Scaffold's transient export notice overlaps the bottom action while it is visible.
        compose.waitUntil(10000) { compose.onAllNodesWithText("recordings saved to your library", substring = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Save full audio").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Recording name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Recording name").performTextReplacement("Complete edited take")
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.waitUntil(20000) { runBlocking { app.repository.dao.observeAll().first().any { it.title == "Complete edited take" } } }
        val full = runBlocking { app.repository.dao.observeAll().first().single { it.title == "Complete edited take" } }
        assertEquals(3500L, full.durationMs)
        assertEquals(source.id, full.sourceId)
        assertArrayEquals(original, app.repository.file(source).readBytes())
        assertTrue(app.repository.file(cut).exists())
        assertTrue(app.repository.file(full).exists())
    }

    @Test fun overlappingWindowsDisableFullExportButAllowSeparateClips() {
        runBlocking {
            app.repository.dao.window(CutWindow(id = "overlap-a", recordingId = source.id, startMs = 0, endMs = 2000, reverse = true))
            app.repository.dao.window(CutWindow(id = "overlap-b", recordingId = source.id, startMs = 1000, endMs = 3000, ordinal = 1, speed = 0.5))
        }
        openEditor()
        compose.onNodeWithText("Save full audio").assertIsNotEnabled()
        compose.onNodeWithText(" Save all (2)").assertIsEnabled().performClick()
        compose.waitUntil(20000) { runBlocking { app.repository.dao.observeAll().first().size == 3 } }
        val clips = runBlocking { app.repository.dao.observeAll().first().filter { it.sourceId == source.id } }
        assertEquals(listOf(2000L, 4000L), clips.map { it.durationMs }.sorted())
        assertArrayEquals(original, app.repository.file(source).readBytes())
    }

    @Test fun wholeAudioShortcutCreatesAnEditableFullRangeWindow() {
        openEditor()
        compose.onNodeWithText("Edit whole audio").performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { app.repository.dao.windows(source.id).size == 1 } }
        val window = runBlocking { app.repository.dao.windows(source.id).single() }
        assertEquals(0L, window.startMs)
        assertEquals(source.durationMs, window.endMs)
        compose.onNodeWithContentDescription("Reverse window 1").performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { app.repository.dao.windows(source.id).single().reverse } }
        compose.onNodeWithText("Reset effects").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5000) { runBlocking { !app.repository.dao.windows(source.id).single().reverse } }
        assertArrayEquals(original, app.repository.file(source).readBytes())
    }

    @Test fun internalRemovalAcceptsOverlapsAndSavesANamedEditableCopy() {
        runBlocking {
            app.repository.dao.window(CutWindow(id = "remove-a", recordingId = source.id, startMs = 500, endMs = 2000, reverse = true))
            app.repository.dao.window(CutWindow(id = "remove-b", recordingId = source.id, startMs = 1000, endMs = 3000, ordinal = 1, speed = 2.0))
        }
        openEditor()
        compose.onNodeWithText("Save full audio").assertIsNotEnabled()
        compose.onNodeWithText("Remove selected sections").assertIsEnabled().performClick()
        compose.onNodeWithText("Save remaining audio").assertIsDisplayed()
        compose.onNodeWithText("Recording name").performTextReplacement("Joined remaining audio")
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.waitUntil(20000) { runBlocking { app.repository.dao.observeAll().first().any { it.title == "Joined remaining audio" } } }
        val joined = runBlocking { app.repository.dao.observeAll().first().single { it.title == "Joined remaining audio" } }
        assertEquals(1500L, joined.durationMs)
        assertArrayEquals(original, app.repository.file(source).readBytes())
        compose.onNodeWithContentDescription("Back to recordings").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(joined.title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(joined.title).performClick()
        compose.onNodeWithText("Edit whole audio").performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { app.repository.dao.windows(joined.id).singleOrNull()?.endMs == joined.durationMs } }
    }

    @Test fun removingEntireAudioIsDisabledWithAnExplanation() {
        runBlocking {
            app.repository.dao.window(CutWindow(id = "all-remove-a", recordingId = source.id, startMs = 0, endMs = 2000))
            app.repository.dao.window(CutWindow(id = "all-remove-b", recordingId = source.id, startMs = 2000, endMs = 4000, ordinal = 1))
        }
        openEditor()
        compose.onNodeWithText("Remove selected sections").assertIsNotEnabled()
        compose.onNodeWithText("The selected windows remove all audio. Keep at least one section.").assertIsDisplayed()
        compose.onNodeWithText("Save full audio").assertIsEnabled()
    }
}
