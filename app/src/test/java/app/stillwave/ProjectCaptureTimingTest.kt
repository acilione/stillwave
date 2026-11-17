package app.stillwave

import app.stillwave.audio.projectCaptureFrames
import app.stillwave.audio.projectCaptureOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectCaptureTimingTest {
    @Test fun pausedPlayheadKeepsItsChosenPositionWithoutSubtractingMicrophoneStartup() {
        assertEquals(15_000L, projectCaptureOffset(15_000, false, 20, 30_000, false))
        assertEquals(15_000L, projectCaptureOffset(15_000, false, 20, 30_000, true))
        assertEquals(0L, projectCaptureOffset(null, false, 20, 30_000, true))
    }

    @Test fun advancingPlayheadCompensatesFirstBufferAndWrapsOnlyLoopPositions() {
        assertEquals(14_980L, projectCaptureOffset(15_000, true, 20, 30_000, false))
        assertEquals(29_985L, projectCaptureOffset(5, true, 20, 30_000, true))
        assertEquals(0L, projectCaptureOffset(5, true, 20, 30_000, false))
    }

    @Test fun loopFrameCountRetainsMillisecondDurationAtNonIntegralRates() {
        assertEquals(45L, projectCaptureFrames(1, 44100))
        assertEquals(44_233L, projectCaptureFrames(1003, 44100))
        assertEquals(38_448L, projectCaptureFrames(2403, 16000))
        for (duration in listOf(1L, 1003L, 2403L)) {
            val frames = projectCaptureFrames(duration, 44100)
            assertEquals(duration, frames * 1000 / 44100)
        }
    }
}
