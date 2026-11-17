package app.stillwave

import app.stillwave.audio.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

class RecordingFileDetailsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun actualFileSizeIncludesHeadersAndPackedPcmPadding() {
        val file = temporary.newFile("short.wav")
        WavFile.header(file, 48000, 1, 3, PcmEncoding.PCM24)
        val details = RecordingFileDetails.read(file)
        assertEquals(48L, details.sizeBytes)
        assertEquals(3L, details.audio!!.dataBytes)
        assertEquals(PcmEncoding.PCM24, details.audio.encoding)
        assertNull(details.error)
    }

    @Test fun detailsReadFloatEncodingAndDurationFromTheFile() {
        val file = temporary.newFile("float.wav")
        WavFile.header(file, 48000, 2, 48000L * 2 * 4, PcmEncoding.FLOAT32)
        val details = RecordingFileDetails.read(file)
        assertEquals(384058L, details.sizeBytes)
        assertEquals(PcmEncoding.FLOAT32, details.audio!!.encoding)
        assertEquals(1000L, details.audio.durationMs)
        assertEquals("3072 kbps", pcmBitRateLabel(details.audio, Locale.ROOT))
    }

    @Test fun missingAndUnsupportedFilesNeverReportInventedAudioDetails() {
        val missing = RecordingFileDetails.read(File(temporary.root, "missing.wav"))
        assertNull(missing.sizeBytes); assertNull(missing.audio); assertNotNull(missing.error)
        val unsupported = temporary.newFile("invalid.wav").apply { writeText("Not a WAV") }
        val invalid = RecordingFileDetails.read(unsupported)
        assertEquals(unsupported.length(), invalid.sizeBytes)
        assertNull(invalid.audio); assertNotNull(invalid.error)
        assertNull(RecordingFileDetails.read(temporary.root).sizeBytes)
    }

    @Test fun megabytesUseDecimalUnitsAndRespectLocaleWithoutRoundingTinyFilesToZero() {
        assertEquals("1.00 MB", fileSizeMb(1_000_000, Locale.ROOT))
        assertEquals("1,50 MB", fileSizeMb(1_500_000, Locale.ITALY))
        assertEquals("<0.01 MB", fileSizeMb(44, Locale.ROOT))
        assertEquals("0.00 MB", fileSizeMb(0, Locale.ROOT))
        assertEquals("705.6 kbps", pcmBitRateLabel(WavFile.Info(44100, 1, 0), Locale.ROOT))
    }
}
