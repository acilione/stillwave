package app.stillwave.audio

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.ceil

/** Bounded overview data in source coordinates. Stereo uses the louder channel. */
class AudioTrace(val fromMs: Long, val toMs: Long, val peaks: FloatArray) {
    private val maximum = peaks.maxOrNull() ?: 0f
    fun peak(from: Double, to: Double): Float {
        if (peaks.isEmpty() || to <= fromMs || from >= toMs) return 0f
        if (from <= fromMs && to >= toMs) return maximum
        val duration = (toMs - fromMs).coerceAtLeast(1).toDouble()
        val first = ((from - fromMs) / duration * peaks.size).toInt().coerceIn(0, peaks.lastIndex)
        val last = ceil((to - fromMs) / duration * peaks.size).toInt().coerceIn(first + 1, peaks.size)
        var value = 0f
        for (i in first until last) value = maxOf(value, peaks[i])
        return value
    }

    companion object {
        /** Samples at most 1,024 frames per bin; no whole-file allocation for long recordings. */
        fun read(file: File, fromMs: Long, toMs: Long, bins: Int = 2048, checkCancelled: () -> Unit = {}): AudioTrace {
            require(bins in 1..4096)
            val info = WavFile.info(file)
            require(fromMs >= 0 && toMs > fromMs && toMs <= info.durationMs)
            val first = fromMs * info.rate / 1000
            val end = if (toMs == info.durationMs) info.dataBytes / info.frameSize else toMs * info.rate / 1000
            val frames = end - first
            require(frames > 0)
            val count = minOf(bins.toLong(), frames).toInt()
            val values = FloatArray(count)
            RandomAccessFile(file, "r").use { input ->
                val buffer = ByteArray(1024 * info.frameSize)
                repeat(count) { bucket ->
                    checkCancelled()
                    val begin = first + frames * bucket / count
                    val until = first + frames * (bucket + 1) / count
                    val sampled = minOf(1024L, until - begin)
                    input.seek(info.dataOffset + (begin + (until - begin - sampled) / 2) * info.frameSize)
                    val bytes = (sampled * info.frameSize).toInt()
                    input.readFully(buffer, 0, bytes)
                    values[bucket] = pcmPeak(buffer, bytes, info.encoding)
                }
            }
            return AudioTrace(fromMs, toMs, values)
        }
    }
}
