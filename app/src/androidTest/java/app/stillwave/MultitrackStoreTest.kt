package app.stillwave

import androidx.test.core.app.ApplicationProvider
import app.stillwave.data.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class MultitrackStoreTest {
    private val app = ApplicationProvider.getApplicationContext<StillwaveApp>()
    private val directory = File(app.cacheDir, "project-test-${UUID.randomUUID()}").apply { mkdirs() }
    @After fun cleanup() { directory.deleteRecursively() }

    private fun recording(duration: Long = 2000) = Recording(title = "Take", createdAt = 1, durationMs = duration,
        fileName = "test.wav", sampleRate = 48000, channels = 2)

    @Test fun versionOneProjectsBecomeIndependentLanesAndSplitClipsPersistInVersionTwo() = runBlocking<Unit> {
        val source = MultitrackTrack(recordingId = "source", title = "Guitar", sourceEndMs = 2000)
        val project = MultitrackProject(title = "Legacy session", durationMs = 2000, tracks = listOf(source))
        val legacy = MultitrackJson.encode(project).put("version", 1)
        legacy.remove("laneOrder")
        legacy.getJSONArray("tracks").getJSONObject(0).remove("laneId")
        File(directory, "${project.id}.json").writeText(legacy.toString())
        val store = MultitrackStore(directory)
        val loaded = store.load().single()
        assertEquals(source.id, loaded.tracks.single().laneId)
        val saved = store.update(loaded.id) { splitProjectClip(it, source.id, 1000) }
        assertEquals(2, saved.tracks.size); assertEquals(1, saved.lanes.size)
        assertEquals(saved, MultitrackStore(directory).load().single())
        assertEquals(2, MultitrackJson.encode(saved).getInt("version"))
    }

    @Test fun everyTrackPropertySurvivesReloadAndInterruptedWriteUsesPriorAtomicRevision() = runBlocking<Unit> {
        val store = MultitrackStore(directory)
        val project = MultitrackProject(title = "Session", durationMs = 8000, loopPlayback = true,
            tracks = listOf(MultitrackTrack(recordingId = "a", title = "Guitar", sourceStartMs = 200, sourceEndMs = 1700,
                offsetMs = 350, gain = 0.65, muted = true, solo = true, loop = true)))
        val saved = store.save(project)
        assertEquals(saved, MultitrackStore(directory).load().single())
        val file = File(directory, "${saved.id}.json")
        file.copyTo(File(file.path + ".bak"))
        file.writeText("interrupted revision")
        assertEquals(saved, MultitrackStore(directory).load().single())
        assertEquals(saved, MultitrackJson.decode(MultitrackJson.encode(saved)))
    }

    @Test fun concurrentTrackAdditionsAndRenamesPreserveBothEditsAndRetryIsIdempotent() = runBlocking<Unit> {
        val store = MultitrackStore(directory)
        val project = store.save(MultitrackProject(title = "Session", durationMs = 1000))
        val take = recording()
        coroutineScope {
            launch(Dispatchers.IO) { store.update(project.id) { it.copy(title = "Renamed") } }
            launch(Dispatchers.IO) { assertTrue(store.addRecordedTake(project.id, take, 500, false)) }
        }
        assertTrue(store.addRecordedTake(project.id, take, 500, false))
        val updated = MultitrackStore(directory).load().single()
        assertEquals("Renamed", updated.title)
        assertEquals(1, updated.tracks.size)
        assertEquals(take.id, updated.tracks.single().id)
        assertEquals(2500L, updated.durationMs)
        assertEquals(500L, updated.tracks.single().offsetMs)
        store.delete(project.id)
        assertFalse(store.addRecordedTake(project.id, take, 0, false))
        assertTrue(store.load().isEmpty())
    }

    @Test fun loopTakePreservesCycleAndCorruptNeighbourRemainsIsolated() = runBlocking<Unit> {
        val store = MultitrackStore(directory)
        val project = store.save(MultitrackProject(title = "Loop", durationMs = 1000, loopPlayback = true))
        store.addRecordedTake(project.id, recording(), 250, true)
        val updated = store.load().single()
        assertEquals(1000L, updated.durationMs)
        assertEquals(1000L, updated.tracks.single().sourceEndMs)
        assertTrue(updated.tracks.single().loop)
        val corrupt = File(directory, "${UUID.randomUUID()}.json").apply { writeText("broken") }
        assertEquals(updated, store.load().single())
        assertEquals(1, store.unreadableProjects.value)
        assertTrue(corrupt.exists())
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.addRecordedTake(corrupt.nameWithoutExtension, recording(), 0, false) }
        }
        assertEquals("broken", corrupt.readText())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.delete("../outside") } }
    }
}
