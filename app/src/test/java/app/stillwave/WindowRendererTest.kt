package app.stillwave

import app.stillwave.audio.*
import app.stillwave.data.CutWindow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class WindowRendererTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun wav(bytes: ByteArray, rate: Int = 8000, channels: Int = 2, encoding: PcmEncoding = PcmEncoding.PCM16): File {
        val file = temporary.newFile()
        WavFile.header(file, rate, channels, 0, encoding)
        file.appendBytes(bytes)
        WavFile.header(file, rate, channels, bytes.size.toLong(), encoding)
        return file
    }
    private fun data(file: File): ByteArray {
        val info = WavFile.info(file)
        return file.readBytes().copyOfRange(info.dataOffset.toInt(), (info.dataOffset + info.dataBytes).toInt())
    }
    private fun output(name: String) = File(temporary.root, name)
    private fun window(start: Long = 0, end: Long, reverse: Boolean = false, speed: Double = 1.0) =
        CutWindow(recordingId = "source", startMs = start, endMs = end, reverse = reverse, speed = speed)

    @Test fun reversalPreservesExactFramesAndChannelsForEveryEncodingIncludingFractionalEof() {
        for (encoding in PcmEncoding.entries) {
            val frameSize = encoding.bytes * 2
            // Cross multiple I/O chunks and retain three frames beyond the displayed duration.
            val bytes = ByteArray(24003 * frameSize) { (it * 37 + it / 13).toByte() }
            val source = wav(bytes, encoding = encoding)
            val before = source.readBytes()
            val reversed = output("${encoding.name}-reversed.wav")
            WindowRenderer.renderWindow(source, reversed, window(123, 3000, reverse = true))
            val first = 123 * 8
            val expected = ByteArray((24003 - first) * frameSize)
            for (index in first until 24003) bytes.copyInto(expected, (24002 - index) * frameSize, index * frameSize, (index + 1) * frameSize)
            assertArrayEquals(expected, data(reversed))
            assertEquals(WavFile.info(source).format, WavFile.info(reversed).format)
            assertArrayEquals(before, source.readBytes())
        }
    }

    @Test fun identityWindowAndFullRenderKeepEverySourceByteIncludingFloatPayloads() {
        for (encoding in PcmEncoding.entries) {
            val source = wav(ByteArray(16001 * encoding.bytes) { (it * 53).toByte() }, channels = 1, encoding = encoding)
            val separate = output("${encoding.name}-identity.wav")
            val integrated = output("${encoding.name}-full.wav")
            WindowRenderer.renderWindow(source, separate, window(end = 2000))
            WindowRenderer.renderFull(source, integrated, listOf(window(100, 1800)))
            assertArrayEquals(source.readBytes(), separate.readBytes())
            assertArrayEquals(source.readBytes(), integrated.readBytes())
        }
    }

    @Test fun fullRenderUsesOriginalCoordinatesAfterEarlierSpeedChangeAndKeepsUntouchedSamplesExact() {
        val frameSize = 4
        val bytes = ByteArray(24000 * frameSize) { (it * 17 + it / 7).toByte() }
        val source = wav(bytes)
        val before = source.readBytes()
        val edited = output("integrated.wav")
        val slower = window(500, 1000, speed = 0.5)
        val reversed = window(1500, 2000, reverse = true)
        WindowRenderer.renderFull(source, edited, listOf(reversed, slower)) // UI order is independent of timeline order.
        val result = data(edited)
        assertEquals(3500L, WavFile.info(edited).durationMs)
        assertArrayEquals(bytes.copyOfRange(0, 4000 * frameSize), result.copyOfRange(0, 4000 * frameSize))
        assertArrayEquals(bytes.copyOfRange(8000 * frameSize, 12000 * frameSize), result.copyOfRange(12000 * frameSize, 16000 * frameSize))
        val expectedReversed = ByteArray(4000 * frameSize)
        for (index in 12000 until 16000) bytes.copyInto(expectedReversed, (15999 - index) * frameSize, index * frameSize, (index + 1) * frameSize)
        assertArrayEquals(expectedReversed, result.copyOfRange(16000 * frameSize, 20000 * frameSize))
        assertArrayEquals(bytes.copyOfRange(16000 * frameSize, bytes.size), result.copyOfRange(20000 * frameSize, result.size))
        assertArrayEquals(before, source.readBytes())
    }

    @Test fun speedHasExpectedFrameCountAndMaintainsDcLevelAndFormat() {
        for (encoding in PcmEncoding.entries) {
            val sample = when (encoding) {
                PcmEncoding.PCM16 -> byteArrayOf(0, 16)
                PcmEncoding.PCM24 -> byteArrayOf(0, 0, 16)
                PcmEncoding.PCM32 -> byteArrayOf(0, 0, 0, 16)
                PcmEncoding.FLOAT32 -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.125f).array()
            }
            val bytes = ByteArray(16000 * 2 * encoding.bytes) { sample[it % sample.size] }
            val source = wav(bytes, encoding = encoding)
            for (speed in listOf(0.25, 0.75, 2.0, 4.0)) {
                val result = output("${encoding.name}-$speed.wav")
                WindowRenderer.renderWindow(source, result, window(end = 2000, reverse = true, speed = speed))
                assertEquals((16000 / speed).toLong() * 2 * encoding.bytes, WavFile.info(result).dataBytes)
                assertEquals(WavFile.info(source).format, WavFile.info(result).format)
                val expected = ByteArray(WavFile.info(result).dataBytes.toInt()) { sample[it % sample.size] }
                assertArrayEquals(expected, data(result))
            }
        }
    }

    @Test fun speedingUpFiltersFrequenciesThatWouldAlias() {
        fun tone(frequency: Int): File {
            val values = ByteBuffer.allocate(8000 * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(8000) { values.putFloat((0.5 * sin(2 * PI * frequency * it / 8000)).toFloat()) }
            return wav(values.array(), channels = 1, encoding = PcmEncoding.FLOAT32)
        }
        fun level(file: File): Double {
            val samples = ByteBuffer.wrap(data(file)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            var squared = 0.0
            // Ignore filter startup/end boundaries when measuring a steady tone.
            for (i in 128 until samples.limit() - 128) squared += samples[i] * samples[i]
            return sqrt(squared / (samples.limit() - 256))
        }
        val pass = output("pass.wav"); val stop = output("stop.wav")
        WindowRenderer.renderWindow(tone(500), pass, window(end = 1000, speed = 2.0))
        WindowRenderer.renderWindow(tone(3000), stop, window(end = 1000, speed = 2.0))
        assertTrue("Pass-band signal should remain audible", level(pass) > 0.3)
        assertTrue("Out-of-band signal should be attenuated before decimation", level(stop) < 0.003)
    }

    @Test fun overlappingWindowsExportIndependentlyButCannotAmbiguouslyReplaceTheFullTimeline() {
        val source = wav(ByteArray(16000 * 4))
        val a = window(100, 900, reverse = true)
        val b = window(500, 1200, speed = 2.0)
        WindowRenderer.renderWindow(source, output("a.wav"), a)
        WindowRenderer.renderWindow(source, output("b.wav"), b)
        assertNotNull(WindowRenderer.fullExportError(listOf(a, b), 2000))
        assertNull(WindowRenderer.fullExportError(listOf(a, b.copy(startMs = 900)), 2000))
        assertThrows(IllegalArgumentException::class.java) { WindowRenderer.renderFull(source, output("overlap.wav"), listOf(a, b)) }
        assertFalse(output("overlap.wav").exists())
    }

    @Test fun invalidOperationsAndCancellationCannotOverwriteSourceOrLeavePartialFiles() {
        val source = wav(ByteArray(16000 * 4) { it.toByte() })
        val original = source.readBytes()
        val unchanged = output("existing.wav").apply { writeText("keep") }
        assertThrows(IllegalArgumentException::class.java) { WindowRenderer.renderWindow(source, source, window(end = 1000, reverse = true)) }
        assertThrows(IllegalArgumentException::class.java) { WindowRenderer.renderWindow(source, unchanged, window(end = 1000)) }
        for (speed in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 4.1)) {
            assertThrows(IllegalArgumentException::class.java) { WindowRenderer.renderWindow(source, output("invalid.wav"), window(end = 1000, speed = speed)) }
        }
        var callbacks = 0
        assertThrows(IllegalStateException::class.java) {
            WindowRenderer.renderWindow(source, output("cancelled.wav"), window(end = 2000, reverse = true)) {
                if (++callbacks >= 3) error("Cancelled")
            }
        }
        assertFalse(output("cancelled.wav").exists())
        assertFalse(output("invalid.wav").exists())
        assertEquals("keep", unchanged.readText())
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun oversizedSlowdownIsRejectedBeforeCreatingAnOutput() {
        val source = temporary.newFile()
        // A sparse file exercises long arithmetic without allocating its audio payload in memory.
        val bytes = ((WavFile.MAX_DATA_BYTES / 4 / 4) + 4) * 4
        WavFile.header(source, 8000, 2, bytes)
        val destination = output("too-large.wav")
        assertThrows(IllegalArgumentException::class.java) {
            WindowRenderer.renderWindow(source, destination, window(end = WavFile.info(source).durationMs, speed = 0.25))
        }
        assertFalse(destination.exists())
        assertEquals(bytes, WavFile.info(source).dataBytes)
    }

    @Test fun removingOverlappingNestedAndAdjacentWindowsJoinsExactFramesForEveryEncoding() {
        for (encoding in PcmEncoding.entries) {
            val frameSize = encoding.bytes * 2
            val bytes = ByteArray(24003 * frameSize) { (it * 31 + it / 9).toByte() }
            val source = wav(bytes, encoding = encoding)
            val original = source.readBytes()
            // The union is [500, 1500); effects and order do not change a removal.
            val windows = listOf(window(1000, 1500, reverse = true, speed = 0.5),
                window(500, 900), window(600, 700), window(800, 1000))
            val result = output("${encoding.name}-removed.wav")
            assertNull(WindowRenderer.removalExportError(windows, 3000))
            WindowRenderer.renderRemaining(source, result, windows)
            val expected = bytes.copyOfRange(0, 4000 * frameSize) + bytes.copyOfRange(12000 * frameSize, bytes.size)
            assertArrayEquals(expected, data(result))
            assertEquals(2000L, WavFile.info(result).durationMs)
            assertEquals(WavFile.info(source).format, WavFile.info(result).format)
            assertArrayEquals(original, source.readBytes())
        }
    }

    @Test fun removingHeadAndTailRetainsOnlyTheMiddleAndTreatsDisplayedEndAsExactEof() {
        val bytes = ByteArray(24003 * 4) { (it * 17).toByte() }
        val source = wav(bytes)
        val result = output("middle.wav")
        WindowRenderer.renderRemaining(source, result, listOf(window(2000, 3000), window(0, 500)))
        assertArrayEquals(bytes.copyOfRange(4000 * 4, 16000 * 4), data(result))
        assertEquals(1500L, WavFile.info(result).durationMs)
    }

    @Test fun removalRejectsEmptyOrInvalidSelectionAndTheUnionOfTheWholeAudio() {
        val source = wav(ByteArray(24003 * 4))
        val selections = listOf(emptyList(), listOf(window(0, 3000)),
            listOf(window(0, 1200), window(1000, 2000), window(2000, 3000)),
            listOf(window(-1, 300)), listOf(window(0, 3001)), listOf(window(100, 100)))
        for ((index, windows) in selections.withIndex()) {
            val result = output("invalid-removal-$index.wav")
            assertNotNull(WindowRenderer.removalExportError(windows, 3000))
            assertThrows(IllegalArgumentException::class.java) { WindowRenderer.renderRemaining(source, result, windows) }
            assertFalse(result.exists())
        }
    }

    @Test fun removalNeverOverwritesSourceAndCleansUpCancelledOutput() {
        val source = wav(ByteArray(24003 * 4) { it.toByte() })
        val original = source.readBytes()
        val windows = listOf(window(500, 1500))
        assertThrows(IllegalArgumentException::class.java) { WindowRenderer.renderRemaining(source, source, windows) }
        var callbacks = 0
        val result = output("cancelled-removal.wav")
        assertThrows(IllegalStateException::class.java) {
            WindowRenderer.renderRemaining(source, result, windows) { if (++callbacks == 4) error("Cancelled") }
        }
        assertFalse(result.exists())
        assertArrayEquals(original, source.readBytes())
    }
}
