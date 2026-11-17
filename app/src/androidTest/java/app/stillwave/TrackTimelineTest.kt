package app.stillwave

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.stillwave.data.*
import app.stillwave.ui.StillwaveTheme
import app.stillwave.ui.TrackTimeline
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Verifies actual touch gestures, delayed save acknowledgements, and capture gating without opening a mic. */
class TrackTimelineTest {
    @get:Rule val compose = createComposeRule()
    private val track = MultitrackTrack(recordingId = "guitar", title = "Guitar", sourceEndMs = 1000)
    private val initial = MultitrackProject(title = "Session", durationMs = 1000, tracks = listOf(track))
    private fun lane() = compose.onNodeWithTag("timeline-track-${track.id}")
    private fun at(offset: Long, loop: Boolean = false) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
        "${if (loop) "Loop phase" else "Start"} ${timestamp(offset)}")

    @Test fun dragPreviewsWithoutSavingThenCommitsOnceAndMovesBackToZero() {
        var project by mutableStateOf(initial)
        val writes = mutableListOf<Long>()
        compose.setContent { StillwaveTheme {
            TrackTimeline(project, 0, true) { id, offset, done ->
                writes += offset; project = movedTrackProject(project, id, offset); done(true)
            }
        } }
        lane().performTouchInput { down(Offset(width * 0.1f, centerY)); moveBy(Offset(width * 0.4f, 0f)) }
        compose.runOnIdle { assertTrue(writes.isEmpty()) }
        lane().assert(!at(0))
        lane().performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, writes.size)
            assertTrue(writes.single() > 0)
            assertEquals(1000 + writes.single(), project.durationMs)
        }
        lane().performTouchInput { swipe(Offset(width * 0.9f, centerY), Offset(width * 0.05f, centerY)) }
        compose.runOnIdle { assertEquals(2, writes.size); assertEquals(0L, project.tracks.single().offsetMs) }
        lane().assert(at(0))
    }

    @Test fun cancellationAndSaveFailureRestoreSavedPositionWithoutInventingSuccess() {
        val writes = mutableListOf<Long>()
        var completion: ((Boolean) -> Unit)? = null
        compose.setContent { StillwaveTheme {
            TrackTimeline(initial, 0, true) { _, offset, done -> writes += offset; completion = done }
        } }
        lane().performTouchInput { down(Offset(width * 0.1f, centerY)); moveBy(Offset(width * 0.4f, 0f)) }
        lane().assert(!at(0))
        lane().performTouchInput { cancel() }
        lane().assert(at(0))
        compose.runOnIdle { assertTrue(writes.isEmpty()) }
        lane().performTouchInput { swipe(Offset(width * 0.1f, centerY), Offset(width * 0.5f, centerY)) }
        compose.runOnIdle { assertEquals(1, writes.size) }
        lane().assert(at(writes.single()))
        compose.runOnIdle { checkNotNull(completion)(false) }
        lane().assert(at(0))
    }

    @Test fun repeatPhaseWrapsWhenDraggedLeftAndCaptureDisablesDragging() {
        var project by mutableStateOf(initial.copy(loopPlayback = true, tracks = listOf(track.copy(loop = true))))
        var enabled by mutableStateOf(true)
        val writes = mutableListOf<Long>()
        compose.setContent { StillwaveTheme {
            TrackTimeline(project, 0, enabled) { id, offset, done ->
                writes += offset; project = movedTrackProject(project, id, offset); done(true)
            }
        } }
        lane().performTouchInput { swipe(Offset(width * 0.8f, centerY), Offset(width * 0.4f, centerY)) }
        compose.runOnIdle {
            assertEquals(1, writes.size)
            assertTrue(writes.single() in 1..999)
            assertEquals(1000L, project.durationMs)
            enabled = false
        }
        lane().assertIsNotEnabled()
        lane().performTouchInput { swipe(Offset(width * 0.2f, centerY), Offset(width * 0.7f, centerY)) }
        compose.runOnIdle { assertEquals(1, writes.size) }
        lane().assert(at(writes.single(), loop = true))
    }

    @Test fun sourceChangeDuringHeldDragClearsPreviewAndUnlocksTheViewportWithoutSaving() {
        var project by mutableStateOf(initial)
        val writes = mutableListOf<Long>()
        compose.setContent { StillwaveTheme {
            TrackTimeline(project, 0, true) { id, offset, done ->
                writes += offset; project = movedTrackProject(project, id, offset); done(true)
            }
        } }
        compose.onNodeWithContentDescription("Zoom in timeline").performClick()
        lane().performTouchInput { down(Offset(width * 0.1f, centerY)); moveBy(Offset(width * 0.4f, 0f)) }
        lane().assert(!at(0))
        compose.runOnIdle { project = project.copy(tracks = listOf(track.copy(sourceEndMs = 800))) }
        lane().assert(at(0))
        compose.onNodeWithContentDescription("Zoom in timeline").assertIsEnabled()
        compose.onNodeWithText("Fit").assertIsEnabled()
        lane().performTouchInput { up() }
        compose.runOnIdle { assertTrue(writes.isEmpty()) }
        lane().performTouchInput { swipe(Offset(width * 0.1f, centerY), Offset(width * 0.5f, centerY)) }
        compose.runOnIdle { assertEquals(1, writes.size); assertTrue(writes.single() > 0) }
    }

    @Test fun draggingOneOfTwoClipsInALaneLeavesTheOtherPieceInPlace() {
        var project by mutableStateOf(splitProjectClip(initial, track.id, 500))
        val second = project.tracks.last().id
        var selected: String? = null
        compose.setContent { StillwaveTheme {
            TrackTimeline(project, 0, true, select = { id, _ -> selected = id }) { id, offset, done ->
                project = movedTrackProject(project, id, offset); done(true)
            }
        } }
        lane().performTouchInput { swipe(Offset(width * 0.35f, centerY), Offset(width * 0.65f, centerY)) }
        compose.runOnIdle {
            assertEquals(second, selected)
            assertEquals(0L, project.tracks.first().offsetMs)
            assertTrue(project.tracks.last().offsetMs > 500)
            assertEquals(1, project.lanes.size)
        }
    }

    @Test fun verticalGestureOnLaneStillScrollsItsParent() {
        lateinit var scroll: ScrollState
        val project = initial.copy(tracks = List(16) { track.copy(id = "track-$it", laneId = "lane-$it") })
        val writes = mutableListOf<Long>()
        compose.setContent { StillwaveTheme {
            scroll = rememberScrollState()
            Column(Modifier.verticalScroll(scroll)) {
                TrackTimeline(project, 0, true) { _, offset, done -> writes += offset; done(true) }
            }
        } }
        compose.onNodeWithTag("timeline-track-track-0").performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue(scroll.value > 0); assertTrue(writes.isEmpty()) }
    }
}
