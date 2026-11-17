package app.stillwave

import app.stillwave.audio.WavFile
import app.stillwave.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.*

class AudioIntegrityTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun audio(rate: Int = 16000, channels: Int = 1): File {
        val file = temporary.newFile()
        WavFile.header(file, rate, channels, rate * channels * 2L * 2)
        RandomAccessFile(file, "rw").use { output ->
            output.seek(44)
            repeat(rate * channels * 2) { i -> output.write(i and 255); output.write((i shr 8) and 255) }
        }
        return file
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    @Test fun overlappingCutsAreIndependentAndLeaveOriginalUntouched() {
        val source = audio(); val before = hash(source)
        val a = File(temporary.root, "a.wav"); val b = File(temporary.root, "b.wav")
        WavFile.cut(source, a, 250, 1250); WavFile.cut(source, b, 750, 1750)
        assertEquals(1000L, WavFile.info(a).durationMs)
        assertEquals(1000L, WavFile.info(b).durationMs)
        assertEquals(before, hash(source))
        assertArrayEquals(source.readBytes().copyOfRange(44 + 8000, 44 + 40000), a.readBytes().drop(44).toByteArray())
        assertArrayEquals(a.readBytes().takeLast(16000).toByteArray(), b.readBytes().drop(44).take(16000).toByteArray())
    }
    @Test fun stereoCutsStayAlignedAndKeepEveryFrameAtEnd() {
        val source = audio(44100, 2); val output = File(temporary.root, "stereo.wav")
        WavFile.cut(source, output, 123, 2000)
        val info = WavFile.info(output)
        assertEquals(2, info.channels); assertEquals(44100, info.rate)
        assertEquals(0L, info.dataBytes % 4)
        assertArrayEquals(source.readBytes().takeLast(1024).toByteArray(), output.readBytes().takeLast(1024).toByteArray())
    }
    @Test fun invalidCutsNeverOverwriteSourceOrExistingFiles() {
        val source = audio(); val before = hash(source)
        assertThrows(IllegalArgumentException::class.java) { WavFile.cut(source, source, 0, 500) }
        assertThrows(IllegalArgumentException::class.java) { WavFile.cut(source, File(temporary.root, "bad.wav"), 600, 500) }
        assertThrows(IllegalArgumentException::class.java) { WavFile.cut(source, File(temporary.root, "bad.wav"), 0, 2001) }
        val existing = temporary.newFile(); existing.writeText("keep me")
        assertThrows(IllegalArgumentException::class.java) { WavFile.cut(source, existing, 0, 500) }
        assertEquals("keep me", existing.readText()); assertEquals(before, hash(source))
    }
    @Test fun interruptedExportRemovesIncompleteOutput() {
        val source = audio(); val output = File(temporary.root, "cancelled.wav")
        assertThrows(IllegalStateException::class.java) { WavFile.cut(source, output, 0, 2000) { error("cancel") } }
        assertFalse(output.exists()); assertEquals(2000L, WavFile.info(source).durationMs)
    }
    @Test fun waveformIsBoundedForLongAudio() {
        val peaks = WavFile.peaks(audio(), 90)
        assertEquals(90, peaks.size); assertTrue(peaks.all { it in 0f..1f })
    }
    @Test fun timestampsAreStrictAndMillisecondAccurate() {
        assertEquals(3723004L, parseTimestamp("01:02:03.004"))
        assertEquals(90500L, parseTimestamp("1:30.5"))
        assertEquals(1234L, parseTimestamp("1.234"))
        listOf("-1", "1:60", "1:60:00", "0:00.0001", "NaN", "1.2:30", "999999999999999999999").forEach { assertNull(it, parseTimestamp(it)) }
        assertEquals("01:02:03.004", timestamp(3723004))
    }
    private fun recording(id: String, created: Long, duration: Long, pinned: Boolean = false) = Recording(id, "Renamed $id", created, duration, "$id.wav", 16000, 1, pinned)
    @Test fun libraryUsesMetadataAfterRenameAndPinsFirst() {
        val old = recording("old", 1000, 9000); val recent = recording("new", 2000, 1000).copy(title = "01/01/00 00:00:00")
        val pinned = recording("pin", 0, 300, true)
        val items = listOf(old, pinned, recent)
        assertEquals(listOf("pin", "new", "old"), organizeRecordings(items, SortOrder.NEWEST).map { it.id })
        assertEquals(listOf("pin", "old", "new"), organizeRecordings(items, SortOrder.LONGEST).map { it.id })
        assertEquals(listOf(recent), organizeRecordings(items, SortOrder.NEWEST, from = 1500, until = 2500))
        assertEquals(listOf(old), organizeRecordings(items, SortOrder.NEWEST, from = 1000, until = 2000))
    }
    @Test fun inclusiveDateFilterHandlesDaylightSaving() {
        val date = LocalDate.of(2026, 10, 25)
        val (start, end) = periodBounds(date, date, ZoneId.of("Europe/Rome"))
        assertEquals(25 * 3_600_000L, end!! - start!!)
        assertEquals("25/10/26 00:00:00", defaultTitle(start, ZoneId.of("Europe/Rome")))
    }
}
