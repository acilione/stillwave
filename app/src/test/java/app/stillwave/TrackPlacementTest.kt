package app.stillwave

import app.stillwave.audio.MultitrackRenderer
import app.stillwave.audio.WavFile
import app.stillwave.data.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrackPlacementTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun track(loop: Boolean = false) = MultitrackTrack(recordingId = "source", title = "Guitar",
        sourceStartMs = 20, sourceEndMs = 100, gain = 0.5, loop = loop)

    @Test fun movingBothWaysKeepsOtherEditsAndExtendsOnlyAsNeeded() {
        val guitar = track()
        val other = track().copy(title = "Voice", muted = true)
        val project = MultitrackProject(title = "Jam", durationMs = 100, tracks = listOf(guitar, other))
        val later = movedTrackProject(project, guitar.id, 75)
        assertEquals(155L, later.durationMs)
        assertEquals(guitar.copy(offsetMs = 75), later.tracks[0])
        assertEquals(other, later.tracks[1])
        val earlier = movedTrackProject(later, guitar.id, -100)
        assertEquals(0L, earlier.tracks[0].offsetMs)
        assertEquals(155L, earlier.durationMs)
        assertEquals(project, movedTrackProject(project, "removed track", 50))
        assertTrue("A full-length clip has space to move later", timelineExtentMs(project) > project.durationMs)
    }

    @Test fun fixedCycleAllowsLaterStartsAndNormalProjectsRespectTheSixHourLimit() {
        val guitar = track()
        val cycle = MultitrackProject(title = "Loop", durationMs = 100, loopPlayback = true, tracks = listOf(guitar))
        val moved = movedTrackProject(cycle, guitar.id, 500)
        assertEquals(99L, moved.tracks.single().offsetMs)
        assertEquals(100L, moved.durationMs)
        assertEquals(100L, timelineExtentMs(moved))
        val fullCycleTrack = guitar.copy(sourceStartMs = 0)
        val shifted = movedTrackProject(cycle.copy(tracks = listOf(fullCycleTrack)), guitar.id, 50)
        assertEquals(50L, shifted.tracks.single().offsetMs)
        assertEquals(100L, shifted.durationMs)
        assertEquals(fullCycleTrack.copy(offsetMs = 50), shifted.tracks.single())
        val maximum = movedTrackProject(cycle.copy(loopPlayback = false), guitar.id, Long.MAX_VALUE)
        assertEquals(MAX_MULTITRACK_DURATION_MS - 80, maximum.tracks.single().offsetMs)
        assertEquals(MAX_MULTITRACK_DURATION_MS, maximum.durationMs)
        assertEquals(MAX_MULTITRACK_DURATION_MS, timelineExtentMs(maximum))
        assertNull(maximum.error())
    }

    @Test fun repeatingTrackMovesItsPhaseInEitherDirectionWithoutChangingCycleLength() {
        val guitar = track(loop = true)
        val project = MultitrackProject(title = "Loop", durationMs = 100, loopPlayback = true, tracks = listOf(guitar))
        val left = movedTrackProject(project, guitar.id, -15)
        assertEquals(65L, left.tracks.single().offsetMs)
        assertEquals(100L, left.durationMs)
        val right = movedTrackProject(left, guitar.id, 95)
        assertEquals(15L, right.tracks.single().offsetMs)
        assertEquals(guitar.copy(offsetMs = 15), right.tracks.single())
    }

    @Test fun movedTrackRenderBeginsAtSavedOffsetAndRetainsItsEntireTrimmedSource() {
        val source = temporary.newFile("source.wav")
        val pcm = ByteBuffer.allocate(4800 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(4800) { pcm.putShort(8192.toShort()) }
        WavFile.header(source, 48000, 1, 0)
        source.appendBytes(pcm.array())
        WavFile.header(source, 48000, 1, pcm.capacity().toLong())
        val original = source.readBytes()
        val guitar = track()
        val project = movedTrackProject(MultitrackProject(title = "Jam", durationMs = 100, tracks = listOf(guitar)), guitar.id, 75)
        val output = File(temporary.root, "moved.wav")
        MultitrackRenderer.render(listOf(MultitrackRenderer.Input(project.tracks.single(), source)), output, project.durationMs)
        val info = WavFile.info(output)
        val samples = ByteBuffer.wrap(output.readBytes(), info.dataOffset.toInt(), info.dataBytes.toInt()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertEquals(155L, info.durationMs)
        for (frame in 0 until 155 * 48) {
            val expected = if (frame < 75 * 48) 0.toShort() else 4096.toShort()
            assertEquals("left frame $frame", expected, samples.get())
            assertEquals("right frame $frame", expected, samples.get())
        }
        assertArrayEquals(original, source.readBytes())
    }
}
