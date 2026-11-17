package app.stillwave

import androidx.media3.common.MediaItem
import androidx.room.withTransaction
import app.stillwave.audio.AacFile
import app.stillwave.audio.EditPreviewCache
import app.stillwave.audio.PlaybackService
import app.stillwave.audio.WavFile
import app.stillwave.data.CutWindow
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** UUID-only fixtures; verifies rendered preview playback through the real MediaSession. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EditedPreviewTest {
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                hash.update(bytes, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Test fun reversedSpeedPreviewUsesOriginalCoordinatesAndChangedWindowReloads() {
        withPlaybackFixture(false) { app, _, vm, recording, controller ->
            val original = app.repository.file(recording)
            val before = digest(original)
            val window = CutWindow(recordingId = recording.id, startMs = 6000, endMs = 18_000,
                title = "Reversed passage", reverse = true, speed = 2.0)
            playbackMain { vm.play(recording, window) }
            playbackAwait(30_000) { vm.playback.value.playing && !vm.previewPreparing.value && playbackMain { controller.duration > 0 } }
            val item = playbackMain { checkNotNull(controller.currentMediaItem) }
            assertEquals(recording.id, item.mediaId)
            assertEquals(window.id, vm.playback.value.windowId)
            assertEquals(window.title, item.mediaMetadata.title.toString())
            assertTrue(EditPreviewCache.accepts(app, item))
            val rendered = File(checkNotNull(item.localConfiguration?.uri?.path))
            assertEquals(6000L, WavFile.info(rendered).durationMs)
            assertTrue(vm.playback.value.positionMs in 6000..18_000)
            val firstPosition = vm.playback.value.positionMs
            playbackAwait { vm.playback.value.positionMs < firstPosition - 300 }
            playbackMain { vm.play(recording, window) }
            playbackAwait { !vm.playback.value.playing }

            val pitchPreserved = window.copy(preservePitch = true)
            playbackMain { vm.play(recording, pitchPreserved) }
            playbackAwait(30_000) {
                vm.playback.value.playing && !vm.previewPreparing.value && playbackMain {
                    controller.currentMediaItem?.mediaMetadata?.extras?.getString(PlaybackService.WINDOW_VARIANT) == EditPreviewCache.variant(pitchPreserved)
                }
            }
            assertNotEquals(item.localConfiguration?.uri, playbackMain { controller.currentMediaItem?.localConfiguration?.uri })

            // Same window id with new timestamps/effects must not resume its old cached render.
            val changed = window.copy(startMs = 2000, endMs = 7000, speed = 1.0, reverse = false)
            playbackMain { vm.play(recording, changed) }
            playbackAwait {
                vm.playback.value.playing && playbackMain {
                    controller.currentMediaItem?.mediaMetadata?.extras?.getString(PlaybackService.WINDOW_VARIANT) == EditPreviewCache.variant(changed)
                }
            }
            val updated = playbackMain { checkNotNull(controller.currentMediaItem) }
            assertNull(updated.mediaMetadata.extras?.getString(PlaybackService.PREVIEW_ID))
            assertEquals(original.canonicalFile, File(checkNotNull(updated.localConfiguration?.uri?.path)).canonicalFile)
            assertTrue(vm.playback.value.positionMs in 2000..7000)
            assertEquals(before, digest(original))
        }
    }

    @Test fun previewRegistrationRejectsTamperingAndPauseCancelsPreparation() {
        withPlaybackFixture(false) { app, _, vm, recording, controller ->
            val window = CutWindow(recordingId = recording.id, startMs = 1000, endMs = 5000, reverse = true)
            playbackMain { vm.play(recording, window) }
            playbackAwait { vm.playback.value.playing && !vm.previewPreparing.value }
            val item = playbackMain { checkNotNull(controller.currentMediaItem) }
            assertTrue(EditPreviewCache.accepts(app, item))
            val extras = android.os.Bundle(item.mediaMetadata.extras).apply { putDouble(PlaybackService.PREVIEW_SPEED, 4.0) }
            val tampered = item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
            assertFalse(EditPreviewCache.accepts(app, tampered))
            assertFalse(EditPreviewCache.accepts(app, item.buildUpon().setMediaId("another-recording").build()))
            val arbitrary = MediaItem.Builder().setMediaId(recording.id).setUri(app.repository.file(recording).toURI().toString())
                .setMediaMetadata(item.mediaMetadata).build()
            assertFalse(EditPreviewCache.accepts(app, arbitrary))
            playbackMain {
                vm.play(recording, window.copy(endMs = recording.durationMs, speed = 0.25))
                vm.pausePlayback()
            }
            playbackAwait { !vm.previewPreparing.value && !playbackMain { controller.playWhenReady } }
            Thread.sleep(300)
            assertFalse(playbackMain { controller.playWhenReady })
            assertFalse(vm.previewPreparing.value)
        }
    }

    @Test fun aacOriginalAndWholeReversedPreviewPlayAndDeletionRemovesBothCaches() {
        withPlaybackFixture(false) { app, _, vm, recording, controller ->
            val pcm = File(app.cacheDir, "preview-fixture-${recording.id}.wav")
            val aac = File(app.recordingsDir, "${recording.id}.m4a")
            try {
                WavFile.header(pcm, 48000, 1, 48000L * 2 * 4)
                AacFile.encode(pcm, aac, 128000)
                val info = AacFile.info(aac)
                val compressed = recording.copy(fileName = aac.name, durationMs = info.durationMs, sampleRate = info.rate, channels = info.channels)
                runBlocking {
                    app.database.withTransaction {
                        // The shared fixture starts as WAV; insert uses IGNORE for an existing id.
                        app.repository.dao.delete(recording.id)
                        app.repository.dao.insert(compressed)
                    }
                    assertEquals(aac.name, app.repository.dao.get(recording.id)?.fileName)
                }
                val before = digest(aac)
                playbackMain { vm.play(compressed) }
                playbackAwait { vm.playback.value.playing }
                assertEquals(aac.canonicalFile, playbackMain { File(checkNotNull(controller.currentMediaItem?.localConfiguration?.uri?.path)).canonicalFile })
                playbackMain { vm.pausePlayback() }
                val window = CutWindow(recordingId = recording.id, startMs = 0, endMs = info.durationMs, reverse = true)
                playbackMain { vm.play(compressed, window) }
                playbackAwait(30_000) { vm.playback.value.playing && vm.playback.value.windowId == window.id && !vm.previewPreparing.value }
                val item = playbackMain { checkNotNull(controller.currentMediaItem) }
                assertTrue(EditPreviewCache.accepts(app, item))
                val preview = File(checkNotNull(item.localConfiguration?.uri?.path))
                val prefix = preview.name.substring(0, 65)
                assertTrue(File(app.cacheDir, "decoded-audio").listFiles().orEmpty().any { it.name.startsWith(prefix) })
                assertEquals(before, digest(aac))
                playbackMain { vm.deleteRecordings(listOf(recording.id)) }
                playbackAwait {
                    runBlocking { app.repository.dao.get(recording.id) == null } && !vm.deleting.value &&
                        playbackMain { controller.currentMediaItem == null }
                }
                assertFalse(aac.exists())
                assertFalse(preview.exists())
                assertTrue(File(app.cacheDir, "decoded-audio").listFiles().orEmpty().none { it.name.startsWith(prefix) })
                assertFalse(EditPreviewCache.accepts(app, item))
            } finally { pcm.delete(); aac.delete() }
        }
    }
}
