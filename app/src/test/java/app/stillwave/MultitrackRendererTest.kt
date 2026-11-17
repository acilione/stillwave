package app.stillwave

import app.stillwave.audio.MultitrackRenderer
import app.stillwave.audio.PcmEncoding
import app.stillwave.audio.WavFile
import app.stillwave.data.MAX_MULTITRACK_DURATION_MS
import app.stillwave.data.MultitrackTrack
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class MultitrackRendererTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun wav(rate: Int = 48000, channels: Int = 1, frames: Int = rate / 10,
        encoding: PcmEncoding = PcmEncoding.PCM16, sample: (Int, Int) -> Double): File {
        val file = temporary.newFile()
        val bytes = ByteBuffer.allocate(frames * channels * encoding.bytes).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { frame -> repeat(channels) { channel ->
            val value = sample(frame, channel)
            when (encoding) {
                PcmEncoding.PCM16 -> bytes.putShort((value * 32768).toInt().toShort())
                PcmEncoding.PCM24 -> repeat(3) { bytes.put(((value * 8388608).toInt() shr (it * 8)).toByte()) }
                PcmEncoding.PCM32 -> bytes.putInt((value * 2147483648).toLong().toInt())
                PcmEncoding.FLOAT32 -> bytes.putFloat(value.toFloat())
            }
        } }
        WavFile.header(file, rate, channels, 0, encoding)
        file.appendBytes(bytes.array())
        WavFile.header(file, rate, channels, bytes.capacity().toLong(), encoding)
        return file
    }

    private fun input(file: File, from: Long = 0, to: Long = WavFile.info(file).durationMs,
        offset: Long = 0, gain: Double = 1.0, loop: Boolean = false, muted: Boolean = false, solo: Boolean = false) =
        MultitrackRenderer.Input(MultitrackTrack(recordingId = file.name, title = file.name,
            sourceStartMs = from, sourceEndMs = to, offsetMs = offset, gain = gain, loop = loop, muted = muted, solo = solo), file)

    private fun output(name: String = "mix.wav") = File(temporary.root, name)
    private fun samples(file: File): ShortArray {
        val info = WavFile.info(file)
        val buffer = ByteBuffer.wrap(file.readBytes(), info.dataOffset.toInt(), info.dataBytes.toInt()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(buffer.remaining()).also { buffer.get(it) }
    }

    @Test fun mixesIndependentTrimsOffsetsAndGainsWithoutChangingSources() {
        val mono = wav { frame, _ -> if (frame < 2400) 0.25 else 0.125 }
        val stereo = wav(channels = 2) { _, channel -> if (channel == 0) 0.125 else -0.125 }
        val originalMono = mono.readBytes(); val originalStereo = stereo.readBytes()
        val destination = output()
        val result = MultitrackRenderer.render(listOf(input(mono, from = 50, offset = 25), input(stereo, offset = 50, gain = 0.5)), destination, 200)
        val pcm = samples(destination)
        fun assertFrame(ms: Int, left: Int, right: Int) {
            assertEquals(left.toShort(), pcm[ms * 48 * 2]); assertEquals(right.toShort(), pcm[ms * 48 * 2 + 1])
        }
        assertFrame(0, 0, 0)
        assertFrame(25, 4096, 4096)
        assertFrame(50, 6144, 2048)
        assertFrame(75, 2048, -2048)
        assertFrame(150, 0, 0)
        assertEquals(200L, result.durationMs)
        assertEquals(1.0, result.appliedGain, 0.0)
        assertEquals(48000, WavFile.info(destination).rate)
        assertEquals(2, WavFile.info(destination).channels)
        assertArrayEquals(originalMono, mono.readBytes()); assertArrayEquals(originalStereo, stereo.readBytes())
    }

    @Test fun loopingTrackRepeatsAtItsTrimmedLengthAndPhaseWrapsBeforeOffset() {
        val source = wav { frame, _ -> if (frame < 2400) 0.25 else -0.25 }
        val destination = output()
        MultitrackRenderer.render(listOf(input(source, offset = 25, loop = true)), destination, 300)
        val pcm = samples(destination)
        for (ms in 0 until 300) {
            val sourceMs = Math.floorMod(ms - 25, 100)
            val expected = if (sourceMs < 50) 8192 else -8192
            assertEquals("at $ms ms", expected.toShort(), pcm[ms * 96])
            assertEquals(expected.toShort(), pcm[ms * 96 + 1])
        }
    }

    @Test fun soloMuteAndZeroGainApplyBeforeMixingAndAllMutedIsSilence() {
        val source = wav { _, _ -> 0.25 }
        val tracks = listOf(input(source, gain = 2.0), input(source, gain = 0.5, solo = true), input(source, solo = true, muted = true))
        val mixed = output()
        MultitrackRenderer.render(tracks, mixed, 100)
        assertTrue(samples(mixed).all { it == 4096.toShort() })
        val silent = output("silence.wav")
        MultitrackRenderer.render(tracks.map { it.copy(track = it.track.copy(muted = true)) }, silent, 100)
        assertTrue(samples(silent).all { it == 0.toShort() })
    }

    @Test fun peakProtectionScalesEntireMixUniformlyWithoutFlatteningLoudSections() {
        val source = wav { frame, _ -> if (frame < 2400) 0.75 else 0.25 }
        val destination = output()
        val result = MultitrackRenderer.render(listOf(input(source), input(source)), destination, 100)
        val pcm = samples(destination)
        assertEquals(1.5, result.peak, 0.0)
        assertEquals((32767.0 / 32768) / 1.5, result.appliedGain, 1e-12)
        assertEquals(32767.toShort(), pcm[0])
        assertEquals(10922.toShort(), pcm[4800])
    }

    @Test fun everyPcmEncodingResamplesToStereoAndPreservesDcLevel() {
        for (encoding in PcmEncoding.entries) for (rate in listOf(16000, 44100, 48000, 96000)) {
            val source = wav(rate = rate, encoding = encoding) { _, _ -> 0.125 }
            val destination = output("${encoding.name}-$rate.wav")
            MultitrackRenderer.render(listOf(input(source)), destination, 100)
            assertEquals(4800 * 2, samples(destination).size)
            assertTrue("$encoding $rate Hz", samples(destination).all { it == 4096.toShort() })
        }
    }

    @Test fun downsamplingRejectsFrequenciesAboveOutputNyquist() {
        fun level(frequency: Int): Double {
            val source = wav(rate = 96000, frames = 24000, encoding = PcmEncoding.FLOAT32) { frame, _ -> 0.5 * sin(2 * PI * frequency * frame / 96000) }
            val destination = output("tone-$frequency.wav")
            MultitrackRenderer.render(listOf(input(source)), destination, 250)
            val pcm = samples(destination)
            var squared = 0.0
            for (frame in 128 until 12000 - 128) squared += (pcm[frame * 2] / 32768.0).let { it * it }
            return sqrt(squared / (12000 - 256))
        }
        assertTrue(level(1000) > 0.34)
        assertTrue("A 30 kHz source tone must not alias into 18 kHz", level(30000) < 0.003)
    }

    @Test fun resamplingCachePreservesTrimmedEdgesLoopPhaseAndStereoAcrossBlocks() {
        // Baseline hashes from the original scalar FIR mixer. Each fixture crosses
        // source/cache blocks, a trimmed edge and a loop seam with unequal channels.
        val expected = mapOf(
            44_100 to "bb81f3f00f84dd7236eb66676d53833b4787536b73ca71eac3d6143142567efd",
            48_000 to "ac4f78fac62a37ba5fd1d2bd25cf589e509255e8b15ea152ac61060fbe332997",
            96_000 to "e68094adf73c0b637fd95631a387398d2f04a6bcf36cb87e34890914515fa676",
            192_000 to "ffaf4d61ea57df2f08f6d85a131a988efca4f9a85029f5f8621cc6cef41cfd5d"
        )
        for ((rate, checksum) in expected) {
            val source = wav(rate = rate, channels = 2, frames = rate / 2) { frame, channel ->
                ((frame * (173 + channel * 92) + channel * 719) % 50_000 - 25_000) / 32768.0
            }
            val destination = output("cache-$rate.wav")
            MultitrackRenderer.render(listOf(
                input(source, from = 7, to = 311, offset = 73, gain = 0.7),
                input(source, from = 11, to = 397, offset = 131, gain = 0.6, loop = true)
            ), destination, 750)
            val actual = MessageDigest.getInstance("SHA-256").digest(destination.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals("$rate Hz", checksum, actual)
        }
    }

    @Test fun veryShortResampledLoopsRepeatAcrossPaddedCacheWithoutReadingOutsideTrim() {
        val source = wav(rate = 44_100, channels = 2) { frame, channel ->
            if (frame in 441 until 485) (if (channel == 0) 0.125 else -0.25) else 0.75
        }
        val destination = output()
        MultitrackRenderer.render(listOf(input(source, from = 10, to = 11, offset = 37, loop = true)), destination, 250)
        val pcm = samples(destination)
        for (frame in 0 until pcm.size / 2) {
            assertEquals(4096.toShort(), pcm[frame * 2])
            assertEquals((-8192).toShort(), pcm[frame * 2 + 1])
        }
    }

    @Test fun cancellationDuringWritingDeletesPartialOutputAndKeepsInputs() {
        val source = wav(frames = 24000) { _, _ -> 0.125 }
        val before = source.readBytes()
        val destination = output()
        var progress = 0f
        assertThrows(IllegalStateException::class.java) {
            MultitrackRenderer.render(listOf(input(source)), destination, 500,
                checkCancelled = { if (progress > 0.6f) error("cancel") }, onProgress = { progress = it })
        }
        assertTrue(progress > 0.6f)
        assertFalse(destination.exists())
        assertArrayEquals(before, source.readBytes())
    }

    @Test fun invalidRangesAndExistingOutputsCannotModifyAnyAudio() {
        val source = wav { _, _ -> 0.25 }
        val before = source.readBytes()
        val existing = output().apply { writeText("unchanged") }
        assertThrows(IllegalArgumentException::class.java) { MultitrackRenderer.render(listOf(input(source)), source, 100) }
        assertThrows(IllegalArgumentException::class.java) { MultitrackRenderer.render(listOf(input(source)), existing, 100) }
        assertEquals("unchanged", existing.readText())
        assertThrows(IllegalArgumentException::class.java) { MultitrackRenderer.render(listOf(input(source, to = 101)), output("invalid.wav"), 100) }
        assertThrows(IllegalArgumentException::class.java) { MultitrackRenderer.render(listOf(input(source)), output("huge.wav"), MAX_MULTITRACK_DURATION_MS + 1) }
        assertFalse(output("invalid.wav").exists()); assertFalse(output("huge.wav").exists())
        assertArrayEquals(before, source.readBytes())
    }
}
