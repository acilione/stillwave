package app.stillwave

import app.stillwave.audio.PcmEncoding
import app.stillwave.audio.PcmFormat
import app.stillwave.data.Quality
import app.stillwave.data.AppSettings
import app.stillwave.data.RecordingSelectionMigration
import app.stillwave.data.Settings
import app.stillwave.data.recordingQualitySlots
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QualitySlotsTest {
    @Test fun upgradesAndInvalidEntriesKeepThreeUsableSlots() {
        assertEquals(listOf(Quality.VOICE, Quality.BALANCED, Quality.STUDIO), recordingQualitySlots(emptyList()))
        assertEquals(listOf(Quality.MONO_48, Quality.BALANCED, Quality.STUDIO),
            recordingQualitySlots(listOf("MONO_48", "removed-preset", "BEST", "STEREO_96")))
        assertEquals(listOf(Quality.STUDIO, Quality.VOICE, Quality.MONO_24BIT),
            recordingQualitySlots(listOf("STUDIO", "VOICE", "MONO_24BIT")))
    }

    @Test fun bitrateUsesActualPcmPrecisionAndChannelCount() {
        assertEquals(705600, Quality.BALANCED.bitRate)
        assertEquals("705.6 kbps", Quality.BALANCED.bitRateLabel)
        assertEquals(2304000, Quality.STEREO_24BIT.bitRate)
        assertEquals("2304 kbps", Quality.STEREO_24BIT.bitRateLabel)
        assertEquals(PcmFormat(48000, 2, PcmEncoding.PCM24), Quality.STEREO_24BIT.format)
        assertEquals("48000 Hz", Quality.STEREO_24BIT.sampleRateLabel)
        assertEquals(3072000, Quality.STEREO_32BIT.bitRate)
        assertEquals(128000, Quality.COMPACT.bitRate)
        assertEquals("Auto", Quality.BEST.bitRateLabel)
    }

    @Test fun catalogIsDistinctAndPreservesExistingQualityFormats() {
        assertEquals(PcmFormat(16000, 1), Quality.valueOf("VOICE").format)
        assertEquals(PcmFormat(44100, 1), Quality.valueOf("BALANCED").format)
        assertEquals(PcmFormat(48000, 2), Quality.valueOf("STUDIO").format)
        assertFalse(Quality.BEST in Quality.catalog)
        assertEquals(Quality.catalog.size, Quality.catalog.map { it.format }.distinct().size)
        assertTrue(Quality.catalog.size > 10)
    }

    @Test fun aacSettingsRoundTripIndependentRateBitrateAndChannels() {
        val quality = Quality.aac(24000, 64000, 1)
        assertEquals(quality, Quality.valueOf(quality.name))
        assertEquals(64000, quality.bitRate)
        assertEquals(PcmFormat(24000, 1), quality.format)
        assertEquals("24000 Hz", quality.sampleRateLabel)
        assertEquals("64 kbps", quality.bitRateLabel)
        assertEquals("AAC", quality.containerLabel)
        assertEquals(quality, recordingQualitySlots(listOf(quality.name))[0])
        assertNotEquals(Quality.aac(48000, 64000, 1), Quality.aac(48000, 128000, 1))
        assertNotEquals(Quality.aac(48000, 128000, 1), Quality.aac(48000, 128000, 2))
        assertTrue(runCatching { Quality.valueOf("AAC_96000_128000_2") }.isFailure)
        assertTrue(runCatching { Quality.valueOf("AAC_48000_9000_2") }.isFailure)
    }

    @Test fun oldQualityMigratesToItsAssignedSlotAndKeepsOtherPreferences() = runBlocking {
        val legacy = stringPreferencesKey("quality")
        val before = mutablePreferencesOf(
            legacy to Quality.MONO_48.name,
            Settings.RECORDING_SLOTS[2] to Quality.MONO_48.name,
            Settings.CONFIRM to false,
            Settings.SORT to "LONGEST"
        )
        assertTrue(RecordingSelectionMigration.shouldMigrate(before))
        val migrated = RecordingSelectionMigration.migrate(before)
        assertEquals(2, migrated[Settings.SELECTED_RECORDING_SLOT])
        assertFalse(migrated.contains(legacy))
        assertEquals(Quality.MONO_48.name, migrated[Settings.RECORDING_SLOTS[2]])
        assertEquals(false, migrated[Settings.CONFIRM])
        assertEquals("LONGEST", migrated[Settings.SORT])
        assertFalse(RecordingSelectionMigration.shouldMigrate(migrated))
    }

    @Test fun oldBestIsRetainedButMissingOrUnassignedQualityUsesFirstSlot() = runBlocking {
        val legacy = stringPreferencesKey("quality")
        val best = RecordingSelectionMigration.migrate(mutablePreferencesOf(legacy to Quality.BEST.name))
        assertEquals(Settings.AUTO_RECORDING_SLOT, best[Settings.SELECTED_RECORDING_SLOT])
        assertEquals(Quality.BEST, AppSettings(selectedRecordingSlot = Settings.AUTO_RECORDING_SLOT).selectedRecordingQuality)
        listOf(Quality.MONO_48.name, "removed-quality", "").forEach { value ->
            assertEquals(0, RecordingSelectionMigration.migrate(mutablePreferencesOf(legacy to value))[Settings.SELECTED_RECORDING_SLOT])
        }
        assertEquals(0, RecordingSelectionMigration.migrate(emptyPreferences())[Settings.SELECTED_RECORDING_SLOT])
    }

    @Test fun migratedSlotFollowsReassignmentEvenWithDuplicateFormats() = runBlocking {
        val legacy = stringPreferencesKey("quality")
        val migrated = RecordingSelectionMigration.migrate(mutablePreferencesOf(legacy to Quality.BALANCED.name))
        val reassigned = migrated.toMutablePreferences().apply {
            this[Settings.RECORDING_SLOTS[0]] = Quality.MONO_48.name
            this[Settings.RECORDING_SLOTS[1]] = Quality.MONO_48.name
        }
        assertFalse(RecordingSelectionMigration.shouldMigrate(reassigned))
        val settings = AppSettings(selectedRecordingSlot = reassigned[Settings.SELECTED_RECORDING_SLOT]!!,
            recordingSlots = recordingQualitySlots(Settings.RECORDING_SLOTS.map { reassigned[it] }))
        assertEquals(1, settings.selectedRecordingSlot)
        assertEquals(Quality.MONO_48, settings.selectedRecordingQuality)
        assertEquals(Quality.STUDIO, settings.copy(recordingSlots = listOf(Quality.MONO_48, Quality.STUDIO, Quality.VOICE)).selectedRecordingQuality)
    }

    @Test fun validNewSelectionWinsAndInvalidIndicesAreRepaired() = runBlocking {
        val legacy = stringPreferencesKey("quality")
        val kept = RecordingSelectionMigration.migrate(mutablePreferencesOf(
            Settings.SELECTED_RECORDING_SLOT to 0, legacy to Quality.BEST.name))
        assertEquals(0, kept[Settings.SELECTED_RECORDING_SLOT])
        assertFalse(kept.contains(legacy))
        listOf(-2, 3, Int.MAX_VALUE).forEach { invalid ->
            val preferences = mutablePreferencesOf(Settings.SELECTED_RECORDING_SLOT to invalid)
            assertTrue(RecordingSelectionMigration.shouldMigrate(preferences))
            assertEquals(0, RecordingSelectionMigration.migrate(preferences)[Settings.SELECTED_RECORDING_SLOT])
        }
    }
}
