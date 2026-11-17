package app.stillwave

import app.stillwave.audio.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmFormatTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun everyEncodingCutsExactFramesWithoutChangingTheOriginal() {
        PcmEncoding.entries.forEach { encoding ->
            val source = temporary.newFile()
            val pcm = PcmFormat(44100, 2, encoding)
            val bytes = ByteArray(44101 * pcm.frameSize) { (it * 31).toByte() }
            WavFile.header(source, pcm.rate, pcm.channels, 0, encoding)
            source.appendBytes(bytes)
            WavFile.header(source, pcm.rate, pcm.channels, bytes.size.toLong(), encoding)
            val original = source.readBytes()
            val output = File(temporary.root, "${encoding.name}.wav")
            WavFile.cut(source, output, 123, 1000)
            val info = WavFile.info(output)
            assertEquals(pcm, info.format)
            val first = 123 * 44100 / 1000 * pcm.frameSize
            assertArrayEquals(bytes.copyOfRange(first, bytes.size), output.readBytes().copyOfRange(info.dataOffset.toInt(), (info.dataOffset + info.dataBytes).toInt()))
            assertArrayEquals(original, source.readBytes())
            assertTrue(WavFile.peaks(output).all { it.isFinite() && it in 0f..1f })
        }
    }
    @Test fun oddPacked24BitDataHasRiffPaddingOutsideTheAudio() {
        val source = temporary.newFile()
        WavFile.header(source, 8000, 1, 0, PcmEncoding.PCM24)
        source.appendBytes(ByteArray(409 * 3) { it.toByte() })
        WavFile.header(source, 8000, 1, 409 * 3L, PcmEncoding.PCM24)
        val info = WavFile.info(source)
        assertEquals(409 * 3L, info.dataBytes)
        assertEquals(44 + info.dataBytes + 1, source.length())
        val output = File(temporary.root, "odd.wav")
        WavFile.cut(source, output, 0, info.durationMs)
        assertArrayEquals(source.readBytes(), output.readBytes())
    }
    @Test fun floatingWavHasFactChunkAndFloatingSamplesAreNotQuantized() {
        val source = temporary.newFile()
        val data = ByteBuffer.allocate(8000 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(8000) { data.putFloat(0.12345679f) }
        WavFile.header(source, 8000, 1, 0, PcmEncoding.FLOAT32)
        source.appendBytes(data.array())
        WavFile.header(source, 8000, 1, data.capacity().toLong(), PcmEncoding.FLOAT32)
        val bytes = source.readBytes()
        assertEquals("fact", String(bytes, 38, 4))
        assertEquals(8000, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(46))
        assertEquals(58L, WavFile.info(source).dataOffset)
        assertEquals(0.12345679f, WavFile.peaks(source).first(), 0f)
    }
    @Test fun meteringHandlesMinimumIntegersAndNonFiniteFloatSamples() {
        assertEquals(1f, pcmPeak(byteArrayOf(0, -128), 2, PcmEncoding.PCM16), 0f)
        assertEquals(1f, pcmPeak(byteArrayOf(0, 0, -128), 3, PcmEncoding.PCM24), 0f)
        assertEquals(1f, pcmPeak(byteArrayOf(0, 0, 0, -128), 4, PcmEncoding.PCM32), 0f)
        val floats = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).putFloat(Float.POSITIVE_INFINITY).putFloat(-0.5f).putFloat(0.25f).array()
        assertEquals(0.5f, pcmPeak(floats, floats.size, PcmEncoding.FLOAT32), 0f)
    }
    @Test fun incompleteWavDataIsRejected() {
        val file = temporary.newFile()
        WavFile.header(file, 48000, 2, 48L, PcmEncoding.PCM24)
        RandomAccessFile(file, "rw").use { it.setLength(50) }
        assertThrows(IllegalArgumentException::class.java) { WavFile.info(file) }
    }
    @Test fun unknownCapabilitiesDoNotInventHighSampleRatesOrBitDepths() {
        val baseline = PcmFormat(48000, 1)
        assertEquals(listOf(baseline), CaptureFormats.ranked(emptyList(), null, baseline))
        val observed = PcmFormat(48000, 2)
        assertEquals(listOf(observed, baseline), CaptureFormats.ranked(emptyList(), observed, baseline))
    }
    @Test fun selectionPreservesAdvertisedCombinationsAndPrioritizesPrecision() {
        val baseline = PcmFormat(48000, 1)
        val precision = PcmFormat(96000, 2, PcmEncoding.PCM24)
        val rate = PcmFormat(192000, 2)
        assertEquals(listOf(precision, rate, baseline), CaptureFormats.ranked(listOf(rate, precision, rate), baseline, baseline))
        assertThrows(IllegalArgumentException::class.java) { PcmFormat(384000, 2) }
    }
}
