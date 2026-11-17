package app.stillwave

import app.stillwave.audio.*
import app.stillwave.data.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectClipEditsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val clip = MultitrackTrack(recordingId = "guitar", title = "Guitar", sourceStartMs = 100, sourceEndMs = 900, offsetMs = 200, gain = 0.5)
    private val project = MultitrackProject(title = "Session", durationMs = 1000, tracks = listOf(clip))

    @Test fun splitKeepsSourceCoordinatesAndMixerStateThenMovesEachPieceIndependently() {
        val split = splitProjectClip(project, clip.id, 500)
        val (left, right) = split.tracks
        assertEquals(clip.copy(sourceEndMs = 400), left)
        assertEquals(400L, right.sourceStartMs); assertEquals(900L, right.sourceEndMs)
        assertEquals(500L, right.offsetMs); assertEquals(clip.laneId, right.laneId)
        assertEquals(clip.gain, right.gain, 0.0); assertNotEquals(left.id, right.id)
        assertEquals(1, split.lanes.size)
        val moved = movedTrackProject(split, right.id, 1000)
        assertEquals(left, moved.tracks.first()); assertEquals(1500L, moved.durationMs)
        assertEquals(2, moveClipToLane(moved, right.id, "voice").lanes.size)
    }

    @Test fun trimRetainsAbsoluteTimingAndDuplicationAppendsWithoutOverwriting() {
        val trimmed = trimmedProjectClip(project, clip.id, 200, 700, 1000)
        assertEquals(300L, trimmed.tracks.single().offsetMs)
        val duplicated = duplicateProjectClip(trimmed, clip.id)
        assertEquals(800L, duplicated.tracks.last().offsetMs)
        assertEquals(1300L, duplicated.durationMs)
        assertEquals(1, duplicated.lanes.size)
        assertEquals(clip, project.tracks.single())
        assertThrows(IllegalArgumentException::class.java) { trimmedProjectClip(project, clip.id, 500, 400, 1000) }
        assertThrows(IllegalArgumentException::class.java) { splitProjectClip(project, clip.id, 200) }
        assertThrows(IllegalArgumentException::class.java) { splitProjectClip(project, clip.id, 1000) }
    }

    @Test fun loopSplitUnfoldsRepetitionsIncludingPhaseAndPartialEndsWithoutChangingSound() {
        val source = temporary.newFile("signal.wav")
        val bytes = ByteBuffer.allocate(48000 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(48000) { bytes.putShort(((it / 48 % 100) * 100).toShort()) }
        WavFile.header(source, 48000, 1, 0); source.appendBytes(bytes.array()); WavFile.header(source, 48000, 1, bytes.capacity().toLong())
        val looping = project.copy(durationMs = 1500, loopPlayback = true, tracks = listOf(clip.copy(sourceStartMs = 100, sourceEndMs = 500, offsetMs = 150, loop = true)))
        val cut = splitProjectClip(looping, clip.id, 275)
        assertTrue(cut.tracks.none { it.loop }); assertEquals(1, cut.lanes.size)
        assertEquals(350L, cut.tracks.first().sourceStartMs)
        assertEquals(0L, cut.tracks.first().offsetMs)
        assertEquals(1500L, cut.tracks.last().offsetMs + cut.tracks.last().sourceDurationMs)
        val before = temporary.root.resolve("before.wav"); val after = temporary.root.resolve("after.wav")
        MultitrackRenderer.render(looping.tracks.map { MultitrackRenderer.Input(it, source) }, before, looping.durationMs)
        MultitrackRenderer.render(cut.tracks.map { MultitrackRenderer.Input(it, source) }, after, cut.durationMs)
        assertArrayEquals(before.readBytes(), after.readBytes())
        assertArrayEquals(bytes.array(), source.readBytes().drop(WavFile.HEADER_SIZE.toInt()).toByteArray())
    }

    @Test fun splitCapacityCountsClipsSeparatelyFromTracksAndRejectsHugeUnfolds() {
        val full = project.copy(tracks = List(MAX_MULTITRACK_CLIPS) { clip.copy(id = "clip-$it") })
        assertNull(full.error()); assertEquals(1, full.lanes.size)
        assertThrows(IllegalArgumentException::class.java) { splitProjectClip(full, "clip-0", 500) }
        val fast = clip.copy(sourceStartMs = 0, sourceEndMs = 1, loop = true)
        assertThrows(IllegalArgumentException::class.java) { unfoldProjectClip(project.copy(tracks = listOf(fast)), fast) }
        val tracks = project.copy(tracks = List(16) { clip.copy(id = "clip-$it", laneId = "lane-$it") } + clip.copy(id = "extra", laneId = "lane-0"))
        assertThrows(IllegalArgumentException::class.java) { moveClipToLane(tracks, "extra", "lane-16") }
    }

    @Test fun traceUsesTheTrimmedSourceAndBothChannelsWithBoundedStorageAndCancellation() {
        val source = temporary.newFile("trace.wav")
        val bytes = ByteBuffer.allocate(16000 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(16000) { frame -> bytes.putShort(0); bytes.putShort(if (frame < 8000) 0 else 16384) }
        WavFile.header(source, 16000, 2, 0); source.appendBytes(bytes.array()); WavFile.header(source, 16000, 2, bytes.capacity().toLong())
        val trace = AudioTrace.read(source, 0, 1000, 100)
        assertEquals(100, trace.peaks.size)
        assertEquals(0f, trace.peak(0.0, 490.0), 0f); assertEquals(0.5f, trace.peak(500.0, 1000.0), 0f)
        val trimmed = AudioTrace.read(source, 600, 900)
        assertTrue(trimmed.peaks.all { it == 0.5f }); assertTrue(trimmed.peaks.size <= 2048)
        var checks = 0
        assertThrows(CancellationException::class.java) { AudioTrace.read(source, 0, 1000) { if (++checks == 3) throw CancellationException() } }
        assertEquals(3, checks)
    }
}
