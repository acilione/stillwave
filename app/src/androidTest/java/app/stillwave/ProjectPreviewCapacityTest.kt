package app.stillwave

import androidx.test.core.app.ApplicationProvider
import app.stillwave.audio.MultitrackAudio
import app.stillwave.audio.WavFile
import app.stillwave.data.MultitrackProject
import app.stillwave.data.MultitrackTrack
import app.stillwave.data.Recording
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectPreviewCapacityTest {
    @Test fun moreThanSixteenSourcesOnOneTrackRenderAndPersistedPreviewInvalidationFindsEverySource() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<StillwaveApp>()
        app.ready.await()
        val sources = List(17) {
            val id = UUID.randomUUID().toString()
            Recording(id = id, title = "Clip source", createdAt = System.currentTimeMillis(), durationMs = 100,
                fileName = "$id.wav", sampleRate = 16000, channels = 1)
        }
        val project = MultitrackProject(title = "Many clip sources", durationMs = 1700,
            tracks = sources.mapIndexed { i, source -> MultitrackTrack(recordingId = source.id, title = source.title,
                sourceEndMs = 100, offsetMs = i * 100L, laneId = "shared-track") })
        val directory = File(app.cacheDir, "multitrack-previews")
        val persistedIdentity = UUID.randomUUID().toString().replace("-", "").repeat(2)
        val persistedAudio = File(directory, "$persistedIdentity.wav")
        val persistedMetadata = File(directory, "$persistedIdentity.sources")
        try {
            sources.forEach { WavFile.header(app.repository.file(it), 16000, 1, 3200); app.repository.dao.insert(it) }
            val preview = MultitrackAudio.prepare(app, project)
            assertEquals(1700L, WavFile.info(preview.file).durationMs)
            assertEquals(17, preview.sourceIds.size)
            // This cache entry has no in-memory registration, as after a process restart.
            preview.file.copyTo(persistedAudio)
            File(directory, "${preview.identity}.sources").copyTo(persistedMetadata)
            app.repository.delete(listOf(sources.last().id))
            assertFalse(preview.file.exists())
            assertFalse(persistedAudio.exists())
            assertFalse(persistedMetadata.exists())
        } finally {
            MultitrackAudio.deleteForProject(app, project.id)
            app.repository.delete(sources.map { it.id })
            persistedAudio.delete(); persistedMetadata.delete()
        }
    }
}
