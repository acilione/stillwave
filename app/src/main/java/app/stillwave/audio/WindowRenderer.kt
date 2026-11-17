package app.stillwave.audio

import app.stillwave.data.CutWindow
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin

/** Renders immutable source-coordinate edits with bounded buffers and optional pitch-preserving speed. */
@androidx.annotation.OptIn(UnstableApi::class)
object WindowRenderer {
    private const val BUFFER_BYTES = 64 * 1024
    private const val READ_FRAMES = 4096
    private const val PHASES = 1024
    private const val RADIUS = 32
    private const val TAPS = RADIUS * 2

    /** Callers pass the selected windows. Overlaps remain valid for separate window exports. */
    fun fullExportError(windows: List<CutWindow>, durationMs: Long): String? {
        if (windows.isEmpty()) return "Select at least one window"
        if (windows.any { !it.validFor(durationMs) }) return "Check the selected window timestamps and speed"
        val sorted = windows.sortedWith(compareBy<CutWindow> { it.startMs }.thenBy { it.endMs })
        if (sorted.zipWithNext().any { (left, right) -> left.endMs > right.startMs })
            return "Selected windows overlap. Uncheck or adjust them to save the full audio; separate windows can overlap."
        return null
    }

    fun renderWindow(source: File, destination: File, window: CutWindow, checkCancelled: () -> Unit = {}) {
        val info = WavFile.info(source)
        require(window.validFor(info.durationMs)) { "Check the window timestamps and speed" }
        val segment = segment(info, window)
        render(source, destination, info, listOf(segment), checkCancelled)
    }

    fun renderFull(source: File, destination: File, windows: List<CutWindow>, checkCancelled: () -> Unit = {}) {
        val info = WavFile.info(source)
        val error = fullExportError(windows, info.durationMs)
        require(error == null) { error.orEmpty() }
        val segments = mutableListOf<Segment>()
        var cursor = 0L
        for (window in windows.sortedBy { it.startMs }) {
            val edit = segment(info, window)
            if (cursor < edit.first) segments += Segment(cursor, edit.first)
            segments += edit
            cursor = edit.last
        }
        val end = info.dataBytes / info.frameSize
        if (cursor < end) segments += Segment(cursor, end)
        render(source, destination, info, segments, checkCancelled)
    }

    /** Removal uses the union of source intervals; effects on the removed audio are irrelevant. */
    fun removalExportError(windows: List<CutWindow>, durationMs: Long): String? {
        if (windows.isEmpty()) return "Select at least one window to remove"
        if (windows.any { it.startMs < 0 || it.endMs <= it.startMs || it.endMs > durationMs })
            return "Check the selected window timestamps"
        var cursor = 0L
        var remaining = 0L
        for (window in windows.sortedBy { it.startMs }) {
            if (window.startMs > cursor) remaining += window.startMs - cursor
            cursor = maxOf(cursor, window.endMs)
        }
        remaining += durationMs - cursor
        return if (remaining <= 0) "The selected windows remove all audio. Keep at least one section." else null
    }

    /** Joins untouched sections with exact PCM frames, including the fractional millisecond at EOF. */
    fun renderRemaining(source: File, destination: File, windows: List<CutWindow>, checkCancelled: () -> Unit = {}) {
        val info = WavFile.info(source)
        val error = removalExportError(windows, info.durationMs)
        require(error == null) { error.orEmpty() }
        val segments = mutableListOf<Segment>()
        var cursor = 0L
        val end = info.dataBytes / info.frameSize
        for (window in windows.sortedBy { it.startMs }) {
            val first = window.startMs * info.rate / 1000
            val last = if (window.endMs == info.durationMs) end else window.endMs * info.rate / 1000
            if (first > cursor) segments += Segment(cursor, first)
            cursor = maxOf(cursor, last)
        }
        if (cursor < end) segments += Segment(cursor, end)
        require(segments.isNotEmpty()) { "The selected windows remove all audio" }
        render(source, destination, info, segments, checkCancelled)
    }

    private data class Segment(val first: Long, val last: Long, val reverse: Boolean = false, val speed: Double = 1.0, val preservePitch: Boolean = false) {
        val frames get() = last - first
        val outputFrames get() = (frames / speed).roundToLong().coerceAtLeast(1L)
    }

    private fun segment(info: WavFile.Info, window: CutWindow): Segment {
        val first = window.startMs * info.rate / 1000
        // Preserve the fractional millisecond at EOF, as with the existing cut exporter.
        val last = if (window.endMs == info.durationMs) info.dataBytes / info.frameSize else window.endMs * info.rate / 1000
        require(last > first) { "The window contains no audio frames" }
        return Segment(first, last, window.reverse, window.speed, window.preservePitch)
    }

    private fun render(source: File, destination: File, info: WavFile.Info, segments: List<Segment>, checkCancelled: () -> Unit) {
        require(source.canonicalFile != destination.canonicalFile) { "The original cannot be overwritten" }
        val frames = segments.sumOf { it.outputFrames }
        val bytes = Math.multiplyExact(frames, info.frameSize.toLong())
        require(bytes in 1..WavFile.MAX_DATA_BYTES) { "The edited audio exceeds the WAV size limit" }
        val available = destination.absoluteFile.parentFile.usableSpace
        require(available >= bytes + 8L * 1024 * 1024) { "Not enough free space to save the edited audio" }
        checkCancelled()
        require(destination.createNewFile()) { "Output already exists" }
        try {
            WavFile.header(destination, info.rate, info.channels, 0, info.encoding)
            var written = 0L
            RandomAccessFile(source, "r").use { input ->
                RandomAccessFile(destination, "rw").use { output ->
                    output.seek(WavFile.headerSize(info.encoding))
                    var lastSpeed: Double? = null
                    var coefficients: DoubleArray? = null
                    for (segment in segments) {
                        checkCancelled()
                        if (segment.speed == 1.0) copyFrames(input, output, info, segment, checkCancelled)
                        else if (segment.preservePitch) stretch(input, output, info, segment, checkCancelled)
                        else {
                            if (lastSpeed != segment.speed) { coefficients = kernel(segment.speed); lastSpeed = segment.speed }
                            resample(input, output, info, segment, checkNotNull(coefficients), checkCancelled)
                        }
                    }
                    written = output.filePointer - WavFile.headerSize(info.encoding)
                    require(written in 1..WavFile.MAX_DATA_BYTES && written % info.frameSize == 0L) { "The edited audio exceeds the WAV size limit" }
                    if (segments.none { it.preservePitch && it.speed != 1.0 }) check(written == bytes) { "Incomplete edited audio" }
                    output.fd.sync()
                }
            }
            checkCancelled()
            WavFile.header(destination, info.rate, info.channels, written, info.encoding)
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    /** Sonic sees bounded float blocks; reversing changes frame order before the tempo operation. */
    private fun stretch(input: RandomAccessFile, output: RandomAccessFile, info: WavFile.Info, segment: Segment, checkCancelled: () -> Unit) {
        val processor = SonicAudioProcessor()
        try {
            processor.setSpeed(segment.speed.toFloat())
            processor.setPitch(1f)
            processor.configure(AudioProcessor.AudioFormat(info.rate, info.channels, C.ENCODING_PCM_FLOAT))
            if (!processor.isActive) { copyFrames(input, output, info, segment, checkCancelled); return }
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            val reader = FrameReader(input, info)
            val queued = ByteBuffer.allocateDirect(READ_FRAMES * info.channels * 4).order(ByteOrder.nativeOrder())
            val buffer = ByteArray(BUFFER_BYTES / info.frameSize * info.frameSize)
            fun drain(): Boolean {
                val audio = processor.output.order(ByteOrder.nativeOrder())
                val produced = audio.hasRemaining()
                while (audio.hasRemaining()) {
                    checkCancelled()
                    var used = 0
                    while (audio.remaining() >= 4 && used + info.encoding.bytes <= buffer.size) {
                        encode(audio.float.toDouble(), buffer, used, info.encoding)
                        used += info.encoding.bytes
                    }
                    require(used > 0 && used % info.frameSize == 0) { "Invalid stretched audio frames" }
                    write(output, info, buffer, used)
                }
                return produced
            }
            var consumed = 0L
            while (consumed < segment.frames) {
                checkCancelled()
                queued.clear()
                val count = minOf(READ_FRAMES.toLong(), segment.frames - consumed).toInt()
                repeat(count) { index ->
                    val frame = if (segment.reverse) segment.last - 1 - consumed - index else segment.first + consumed + index
                    repeat(info.channels) { channel -> queued.putFloat(reader.sample(frame, channel).toFloat()) }
                }
                queued.flip()
                processor.queueInput(queued)
                check(!queued.hasRemaining()) { "Audio processor did not consume the input" }
                drain()
                consumed += count
            }
            processor.queueEndOfStream()
            while (!processor.isEnded) {
                checkCancelled()
                check(drain() || processor.isEnded) { "Audio processor did not finish" }
            }
        } finally { processor.reset() }
    }

    /** Copy raw frame bytes, preserving float payloads, precision and stereo channel order at 1x. */
    private fun copyFrames(input: RandomAccessFile, output: RandomAccessFile, info: WavFile.Info, segment: Segment, checkCancelled: () -> Unit) {
        val frameSize = info.frameSize
        val buffer = ByteArray(BUFFER_BYTES / frameSize * frameSize)
        var remaining = segment.frames
        var cursor = if (segment.reverse) segment.last else segment.first
        while (remaining > 0) {
            checkCancelled()
            val count = minOf(remaining, (buffer.size / frameSize).toLong()).toInt()
            if (segment.reverse) cursor -= count
            input.seek(info.dataOffset + cursor * frameSize)
            input.readFully(buffer, 0, count * frameSize)
            if (segment.reverse) {
                for (a in 0 until count / 2) {
                    val b = count - a - 1
                    for (byte in 0 until frameSize) {
                        val held = buffer[a * frameSize + byte]
                        buffer[a * frameSize + byte] = buffer[b * frameSize + byte]
                        buffer[b * frameSize + byte] = held
                    }
                }
            } else cursor += count
            write(output, info, buffer, count * frameSize)
            remaining -= count
        }
    }

    /** Hann-windowed sinc with a lower Nyquist cutoff when speeding up, avoiding decimation aliasing. */
    private fun kernel(speed: Double): DoubleArray {
        val cutoff = 1.0 / maxOf(1.0, speed)
        return DoubleArray(PHASES * TAPS).also { table ->
            repeat(PHASES) { phase ->
                val fraction = phase.toDouble() / PHASES
                var sum = 0.0
                repeat(TAPS) { tap ->
                    val distance = tap - RADIUS + 1 - fraction
                    val x = distance * cutoff
                    val sinc = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)
                    val window = if (abs(distance) >= RADIUS) 0.0 else 0.5 + 0.5 * cos(PI * distance / RADIUS)
                    val coefficient = cutoff * sinc * window
                    table[phase * TAPS + tap] = coefficient
                    sum += coefficient
                }
                repeat(TAPS) { table[phase * TAPS + it] /= sum }
            }
        }
    }

    private fun resample(input: RandomAccessFile, output: RandomAccessFile, info: WavFile.Info, segment: Segment,
        coefficients: DoubleArray, checkCancelled: () -> Unit) {
        val reader = FrameReader(input, info)
        val buffer = ByteArray(BUFFER_BYTES / info.frameSize * info.frameSize)
        var used = 0
        var frame = 0L
        while (frame < segment.outputFrames) {
            if (used == 0) checkCancelled()
            val offset = frame * segment.speed
            val position = if (segment.reverse) segment.last - 1 - offset else segment.first + offset
            val center = floor(position).toLong()
            val phase = ((position - center) * PHASES).toInt().coerceIn(0, PHASES - 1)
            for (channel in 0 until info.channels) {
                var sample = 0.0
                for (tap in 0 until TAPS) {
                    val sourceFrame = (center + tap - RADIUS + 1).coerceIn(segment.first, segment.last - 1)
                    sample += reader.sample(sourceFrame, channel) * coefficients[phase * TAPS + tap]
                }
                encode(sample, buffer, used, info.encoding)
                used += info.encoding.bytes
            }
            if (used == buffer.size) { write(output, info, buffer, used); used = 0 }
            frame++
        }
        if (used > 0) write(output, info, buffer, used)
    }

    private fun write(output: RandomAccessFile, info: WavFile.Info, buffer: ByteArray, count: Int) {
        require(output.filePointer - WavFile.headerSize(info.encoding) + count <= WavFile.MAX_DATA_BYTES) { "The edited audio exceeds the WAV size limit" }
        output.write(buffer, 0, count)
    }

    /** Two adjacent cached blocks cover the FIR neighborhood in either traversal direction. */
    private class FrameReader(private val file: RandomAccessFile, private val info: WavFile.Info) {
        private val blocks = longArrayOf(-1, -1)
        private val samples = Array(2) { DoubleArray(READ_FRAMES * info.channels) }
        private val bytes = ByteArray(READ_FRAMES * info.frameSize)
        fun sample(frame: Long, channel: Int): Double {
            val block = frame / READ_FRAMES
            val slot = (block % 2).toInt()
            if (blocks[slot] != block) {
                val start = block * READ_FRAMES
                val count = minOf(READ_FRAMES.toLong(), info.dataBytes / info.frameSize - start).toInt()
                file.seek(info.dataOffset + start * info.frameSize)
                file.readFully(bytes, 0, count * info.frameSize)
                repeat(count * info.channels) { samples[slot][it] = decode(bytes, it * info.encoding.bytes, info.encoding) }
                blocks[slot] = block
            }
            return samples[slot][((frame % READ_FRAMES) * info.channels + channel).toInt()]
        }
    }

    private fun decode(bytes: ByteArray, offset: Int, encoding: PcmEncoding): Double {
        val low = bytes[offset].toInt() and 255
        return when (encoding) {
            PcmEncoding.PCM16 -> (low or (bytes[offset + 1].toInt() shl 8)).toShort() / 32768.0
            PcmEncoding.PCM24 -> (low or ((bytes[offset + 1].toInt() and 255) shl 8) or (bytes[offset + 2].toInt() shl 16)) / 8388608.0
            PcmEncoding.PCM32, PcmEncoding.FLOAT32 -> {
                val bits = low or ((bytes[offset + 1].toInt() and 255) shl 8) or ((bytes[offset + 2].toInt() and 255) shl 16) or (bytes[offset + 3].toInt() shl 24)
                if (encoding == PcmEncoding.FLOAT32) Float.fromBits(bits).takeIf { it.isFinite() }?.toDouble() ?: 0.0 else bits / 2147483648.0
            }
        }
    }

    private fun encode(value: Double, bytes: ByteArray, offset: Int, encoding: PcmEncoding) {
        val bits = if (encoding == PcmEncoding.FLOAT32) value.toFloat().toRawBits().toLong() else {
            val scale = when (encoding) { PcmEncoding.PCM16 -> 32768L; PcmEncoding.PCM24 -> 8388608L; else -> 2147483648L }
            (value * scale).roundToLong().coerceIn(-scale, scale - 1)
        }
        repeat(encoding.bytes) { bytes[offset + it] = (bits shr (it * 8)).toByte() }
    }
}
