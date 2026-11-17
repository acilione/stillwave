package app.stillwave.audio

import app.stillwave.data.MAX_MULTITRACK_DURATION_MS
import app.stillwave.data.MAX_MULTITRACK_CLIPS
import app.stillwave.data.MAX_MULTITRACK_TRACKS
import app.stillwave.data.MultitrackTrack
import app.stillwave.data.audibleTracks
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * An offline mixer with memory bounded by the track count, not the project duration.
 * Callers hold source repository locks and decoded-audio leases until this returns.
 * A peak-only pass precedes writing: excessive level reduces the entire mix uniformly,
 * preserving relative track volumes without clipping or a compressor's pumping.
 */
object MultitrackRenderer {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    private const val FRAMES_PER_BLOCK = 4096
    private const val RADIUS = 32
    private const val TAPS = RADIUS * 2
    private const val PHASES = 1024

    data class Input(val track: MultitrackTrack, val wavFile: File)
    data class Result(val durationMs: Long, val peak: Double, val appliedGain: Double)

    fun render(inputs: List<Input>, destination: File, durationMs: Long,
        checkCancelled: () -> Unit = {}, onProgress: (Float) -> Unit = {}): Result {
        require(inputs.isNotEmpty()) { "Add at least one track" }
        require(inputs.size <= MAX_MULTITRACK_CLIPS) { "A project supports up to $MAX_MULTITRACK_CLIPS clips" }
        require(inputs.map { it.track.laneId }.distinct().size <= MAX_MULTITRACK_TRACKS) { "A project supports up to $MAX_MULTITRACK_TRACKS tracks" }
        require(inputs.map { it.track.id }.distinct().size == inputs.size) { "Duplicate track identity" }
        require(durationMs in 1..MAX_MULTITRACK_DURATION_MS) { "Project duration must be between 1 ms and 6 hours" }
        inputs.forEach { require(it.track.error() == null) { it.track.error().orEmpty() } }
        require(inputs.none { it.wavFile.canonicalFile == destination.canonicalFile }) { "The original cannot be overwritten" }
        require(!destination.exists()) { "Output already exists" }
        val frames = durationMs * SAMPLE_RATE / 1000
        val bytes = frames * CHANNELS * 2
        require(bytes in 1..WavFile.MAX_DATA_BYTES) { "The mix exceeds the WAV size limit" }
        require(destination.absoluteFile.parentFile.usableSpace >= bytes + 8L * 1024 * 1024) { "Not enough free space to save the mix" }
        checkCancelled()
        val audibleIds = audibleTracks(inputs.map { it.track }).mapTo(HashSet()) { it.id }
        val tracks = mutableListOf<Reader>()
        try {
            for (input in inputs.filter { it.track.id in audibleIds }) {
                checkCancelled()
                tracks += reader(input)
            }
            val samples = DoubleArray(FRAMES_PER_BLOCK * CHANNELS)
            fun mixBlock(firstFrame: Long, count: Int) {
                samples.fill(0.0, 0, count * CHANNELS)
                for (track in tracks) {
                    checkCancelled()
                    track.mix(firstFrame, count, samples)
                }
            }
            var peak = 0.0
            var cursor = 0L
            while (cursor < frames) {
                checkCancelled()
                val count = minOf(FRAMES_PER_BLOCK.toLong(), frames - cursor).toInt()
                mixBlock(cursor, count)
                repeat(count * CHANNELS) { peak = maxOf(peak, abs(samples[it])) }
                cursor += count
                onProgress((cursor.toDouble() / frames * 0.5).toFloat())
            }
            val gain = if (peak > 32767.0 / 32768) (32767.0 / 32768) / peak else 1.0
            checkCancelled()
            require(destination.createNewFile()) { "Output already exists" }
            try {
                WavFile.header(destination, SAMPLE_RATE, CHANNELS, 0)
                RandomAccessFile(destination, "rw").use { output ->
                    output.seek(WavFile.HEADER_SIZE)
                    val buffer = ByteArray(FRAMES_PER_BLOCK * CHANNELS * 2)
                    cursor = 0
                    while (cursor < frames) {
                        checkCancelled()
                        val count = minOf(FRAMES_PER_BLOCK.toLong(), frames - cursor).toInt()
                        mixBlock(cursor, count)
                        repeat(count * CHANNELS) { index ->
                            val value = (samples[index] * gain * 32768).roundToInt().coerceIn(-32768, 32767)
                            buffer[index * 2] = value.toByte()
                            buffer[index * 2 + 1] = (value shr 8).toByte()
                        }
                        output.write(buffer, 0, count * CHANNELS * 2)
                        cursor += count
                        onProgress((0.5 + cursor.toDouble() / frames * 0.5).toFloat())
                    }
                    output.fd.sync()
                }
                checkCancelled()
                WavFile.header(destination, SAMPLE_RATE, CHANNELS, bytes)
            } catch (error: Throwable) { destination.delete(); throw error }
            return Result(durationMs, peak, gain)
        } finally { tracks.forEach { it.close() } }
    }

    private val kernels = ConcurrentHashMap<Int, DoubleArray>()

    /** Opens one clip for random-access mixing at [SAMPLE_RATE]. The caller closes it. */
    internal fun reader(input: Input): Reader {
        val info = WavFile.info(input.wavFile)
        require(input.track.sourceEndMs <= info.durationMs) { "Track '${input.track.title}' ends outside its source recording" }
        val kernel = if (info.rate == SAMPLE_RATE) null else kernels.getOrPut(info.rate) { kernel(info.rate) }
        return Reader(input, info, kernel)
    }

    /** Two cached adjacent blocks retain FIR neighbours across each source block boundary. */
    internal class Reader(input: Input, private val info: WavFile.Info, private val coefficients: DoubleArray?) : Closeable {
        private val track = input.track
        private val first = track.sourceStartMs * info.rate / 1000
        private val last = if (track.sourceEndMs == info.durationMs) info.dataBytes / info.frameSize else track.sourceEndMs * info.rate / 1000
        private val sourceFrames = last - first
        private val timelineOffset = track.offsetMs * SAMPLE_RATE / 1000
        private val ratio = info.rate.toDouble() / SAMPLE_RATE
        private val file = RandomAccessFile(input.wavFile, "r")
        private val blocks = longArrayOf(-1, -1)
        private val samples = Array(2) { DoubleArray(FRAMES_PER_BLOCK * info.channels) }
        private val buffer = ByteArray(FRAMES_PER_BLOCK * info.frameSize)
        // Resolve wrapping, edge extension and disk-cache lookup once per source frame,
        // rather than once per FIR tap and channel. The halo retains the exact old FIR
        // boundary behavior and bounds memory independently of recording duration.
        private val window = if (coefficients == null) null else DoubleArray((FRAMES_PER_BLOCK + TAPS) * info.channels)
        private var windowBlock = -1L

        fun mix(startFrame: Long, count: Int, output: DoubleArray) {
            if (!track.loop && (startFrame + count <= timelineOffset || (startFrame - timelineOffset) * ratio >= sourceFrames)) return
            if (coefficients == null) {
                mixNative(startFrame, count, output)
                return
            }
            val sourceWindow = checkNotNull(window)
            for (index in 0 until count) {
                val timelineFrame = startFrame + index - timelineOffset
                if (!track.loop && timelineFrame < 0) continue
                var position = timelineFrame * ratio
                // Loop offsets are phase shifts within a cycle, including the section before zero.
                if (track.loop) position -= floor(position / sourceFrames) * sourceFrames
                else if (position >= sourceFrames) break
                val center = floor(position).toLong()
                val phase = ((position - center) * PHASES).toInt().coerceIn(0, PHASES - 1)
                val block = center / FRAMES_PER_BLOCK
                if (windowBlock != block) {
                    val from = block * FRAMES_PER_BLOCK - RADIUS + 1
                    repeat(FRAMES_PER_BLOCK + TAPS) { frameIndex ->
                        val candidate = from + frameIndex
                        val frame = first + if (track.loop) Math.floorMod(candidate, sourceFrames) else candidate.coerceIn(0, sourceFrames - 1)
                        val slot = sourceBlock(frame)
                        val offset = ((frame % FRAMES_PER_BLOCK) * info.channels).toInt()
                        sourceWindow[frameIndex * info.channels] = samples[slot][offset]
                        if (info.channels == 2) sourceWindow[frameIndex * 2 + 1] = samples[slot][offset + 1]
                    }
                    windowBlock = block
                }
                val coefficientOffset = phase * TAPS
                var sampleOffset = ((center % FRAMES_PER_BLOCK) * info.channels).toInt()
                var left = 0.0
                var right = 0.0
                if (info.channels == 1) {
                    repeat(TAPS) { tap -> left += sourceWindow[sampleOffset++] * coefficients[coefficientOffset + tap] }
                    right = left
                } else {
                    repeat(TAPS) { tap ->
                        val coefficient = coefficients[coefficientOffset + tap]
                        left += sourceWindow[sampleOffset++] * coefficient
                        right += sourceWindow[sampleOffset++] * coefficient
                    }
                }
                output[index * 2] += left * track.gain
                output[index * 2 + 1] += right * track.gain
            }
        }

        /** At the output rate there is no fractional position or FIR phase to calculate. */
        private fun mixNative(startFrame: Long, count: Int, output: DoubleArray) {
            for (index in 0 until count) {
                var position = startFrame + index - timelineOffset
                if (track.loop) position = Math.floorMod(position, sourceFrames)
                else {
                    if (position < 0) continue
                    if (position >= sourceFrames) break
                }
                val frame = first + position
                val slot = sourceBlock(frame)
                val offset = ((frame % FRAMES_PER_BLOCK) * info.channels).toInt()
                val left = samples[slot][offset] * track.gain
                val right = if (info.channels == 1) left else samples[slot][offset + 1] * track.gain
                output[index * 2] += left
                output[index * 2 + 1] += right
            }
        }

        private fun sourceBlock(frame: Long): Int {
            val block = frame / FRAMES_PER_BLOCK
            val slot = (block % 2).toInt()
            if (blocks[slot] != block) {
                val start = block * FRAMES_PER_BLOCK
                val count = minOf(FRAMES_PER_BLOCK.toLong(), info.dataBytes / info.frameSize - start).toInt()
                file.seek(info.dataOffset + start * info.frameSize)
                file.readFully(buffer, 0, count * info.frameSize)
                repeat(count * info.channels) { samples[slot][it] = decode(buffer, it * info.encoding.bytes, info.encoding) }
                blocks[slot] = block
            }
            return slot
        }

        override fun close() = file.close()
    }

    /** Hann-windowed sinc: reduce the Nyquist cutoff before downsampling high-rate recordings. */
    private fun kernel(rate: Int): DoubleArray {
        val cutoff = minOf(1.0, SAMPLE_RATE.toDouble() / rate)
        return DoubleArray(PHASES * TAPS).also { table ->
            repeat(PHASES) { phase ->
                val fraction = phase.toDouble() / PHASES
                var sum = 0.0
                repeat(TAPS) { tap ->
                    val distance = tap - RADIUS + 1 - fraction
                    val x = distance * cutoff
                    val sinc = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)
                    val window = if (abs(distance) >= RADIUS) 0.0 else 0.5 + 0.5 * cos(PI * distance / RADIUS)
                    val value = cutoff * sinc * window
                    table[phase * TAPS + tap] = value
                    sum += value
                }
                repeat(TAPS) { table[phase * TAPS + it] /= sum }
            }
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
}
