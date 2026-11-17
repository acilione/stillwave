package app.stillwave

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.stillwave.audio.*
import app.stillwave.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PrecisionPlaybackTest {
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000
        while (!condition()) { check(System.nanoTime() < deadline) { "Playback/export timed out" }; Thread.sleep(25) }
    }
    @Test fun everyEncodingExportsNamedOverlappingCutsAndPlaysInMedia3() {
        val app = ApplicationProvider.getApplicationContext<StillwaveApp>()
        runBlocking { app.ready.await() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = RecordingFolder(name = "Precision ${UUID.randomUUID()}")
        runBlocking { app.repository.dao.insertFolder(folder) }
        for (encoding in PcmEncoding.entries) {
            val id = UUID.randomUUID().toString()
            val file = File(app.recordingsDir, "$id.wav")
            val pcm = PcmFormat(48000, 2, encoding)
            WavFile.header(file, pcm.rate, pcm.channels, 48000L * pcm.frameSize, encoding)
            val originalBytes = file.readBytes()
            val recording = Recording(id = id, title = "Precision playback", createdAt = 123456, durationMs = 1000,
                fileName = file.name, sampleRate = pcm.rate, channels = pcm.channels, pcmEncoding = encoding.name,
                inputName = "Test input", captureDetails = "Test format", folderId = folder.id)
            runBlocking { app.repository.add(recording) }
            val windows = listOf(CutWindow(recordingId = id, startMs = 100, endMs = 800, title = "First excerpt"),
                CutWindow(recordingId = id, startMs = 400, endMs = 1000, title = "Overlapping excerpt", ordinal = 1))
            val job = ExportWorker.enqueue(app, id, windows)
            await { WorkManager.getInstance(app).getWorkInfoById(job).get()?.state?.isFinished == true }
            assertEquals(WorkInfo.State.SUCCEEDED, WorkManager.getInstance(app).getWorkInfoById(job).get()!!.state)
            val cuts = runBlocking { app.repository.dao.observeAll().first().filter { it.sourceId == id } }
            assertEquals(setOf("First excerpt", "Overlapping excerpt"), cuts.map { it.title }.toSet())
            assertArrayEquals(originalBytes, file.readBytes())
            for (cut in cuts) {
                assertEquals(pcm, cut.format())
                assertEquals("Test input", cut.inputName)
                assertEquals("Test format", cut.captureDetails)
                assertEquals(folder.id, cut.folderId)
                assertEquals(encoding, WavFile.info(app.repository.file(cut)).encoding)
            }
            lateinit var player: ExoPlayer
            instrumentation.runOnMainSync { player = ExoPlayer.Builder(app).build() }
            try {
                instrumentation.runOnMainSync {
                    player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(app.repository.file(cuts.first()))))
                    player.prepare(); player.play()
                }
                await {
                    var ended = false
                    instrumentation.runOnMainSync {
                        assertNull("Media3 failed for $encoding", player.playerError)
                        ended = player.playbackState == Player.STATE_ENDED
                    }
                    ended
                }
            } finally { instrumentation.runOnMainSync { player.release() } }
        }
    }
}
