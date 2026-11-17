package app.stillwave.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded-memory WAV I/O. Edits copy exact sample frames, including higher precision PCM. */
object WavFile {
    const val HEADER_SIZE = 44L // Legacy integer PCM files.
    const val MAX_DATA_BYTES = 0xFFFF_FFFFL - 51
    fun headerSize(encoding: PcmEncoding) = if (encoding == PcmEncoding.FLOAT32) 58L else HEADER_SIZE
    data class Info(val rate: Int, val channels: Int, val dataBytes: Long,
        val encoding: PcmEncoding = PcmEncoding.PCM16, val dataOffset: Long = HEADER_SIZE) {
        val frameSize get() = channels * encoding.bytes
        val durationMs get() = dataBytes / frameSize * 1000 / rate
        val format get() = PcmFormat(rate, channels, encoding)
    }
    fun header(file: File, rate: Int, channels: Int, bytes: Long, encoding: PcmEncoding = PcmEncoding.PCM16) {
        val format = PcmFormat(rate, channels, encoding)
        require(bytes in 0..MAX_DATA_BYTES && bytes % format.frameSize == 0L)
        val size = headerSize(encoding).toInt()
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        val floating = encoding == PcmEncoding.FLOAT32
        b.put("RIFF".toByteArray()).putInt((bytes + size - 8 + bytes % 2).toInt()).put("WAVEfmt ".toByteArray()).putInt(if (floating) 18 else 16)
        b.putShort(encoding.wavTag.toShort()).putShort(channels.toShort()).putInt(rate).putInt(rate * format.frameSize)
        b.putShort(format.frameSize.toShort()).putShort(encoding.bits.toShort())
        if (floating) b.putShort(0).put("fact".toByteArray()).putInt(4).putInt((bytes / format.frameSize).toInt())
        b.put("data".toByteArray()).putInt(bytes.toInt())
        RandomAccessFile(file, "rw").use {
            it.seek(0); it.write(b.array())
            if (bytes > 0) {
                it.setLength(size + bytes + bytes % 2)
                if (bytes % 2 != 0L) { it.seek(size + bytes); it.write(0) }
            }
            it.fd.sync()
        }
    }
    fun info(file: File): Info = RandomAccessFile(file, "r").use { f ->
        fun tag(): String = ByteArray(4).also(f::readFully).toString(Charsets.US_ASCII)
        fun uint(): Long = Integer.reverseBytes(f.readInt()).toLong() and 0xFFFF_FFFFL
        require(tag() == "RIFF") { "Unsupported WAV file" }
        uint(); require(tag() == "WAVE") { "Unsupported WAV file" }
        var format: PcmFormat? = null
        while (f.filePointer + 8 <= f.length()) {
            val chunk = tag(); val size = uint(); val start = f.filePointer
            require(size <= f.length() - start) { "Incomplete WAV chunk" }
            when (chunk) {
                "fmt " -> {
                    require(size >= 16)
                    val codec = java.lang.Short.reverseBytes(f.readShort()).toInt()
                    val channels = java.lang.Short.reverseBytes(f.readShort()).toInt()
                    val rate = uint().toInt(); val byteRate = uint()
                    val frame = java.lang.Short.reverseBytes(f.readShort()).toInt()
                    val bits = java.lang.Short.reverseBytes(f.readShort()).toInt()
                    val encoding = PcmEncoding.entries.firstOrNull { it.wavTag == codec && it.bits == bits } ?: error("Unsupported PCM encoding")
                    format = PcmFormat(rate, channels, encoding)
                    require(frame == format.frameSize && byteRate == rate.toLong() * frame) { "Invalid WAV format" }
                }
                "data" -> {
                    val pcm = checkNotNull(format) { "Missing WAV format" }
                    require(size % pcm.frameSize == 0L) { "Incomplete sample frame" }
                    return@use Info(pcm.rate, pcm.channels, size, pcm.encoding, start)
                }
            }
            f.seek(start + size + size % 2)
        }
        error("Missing WAV audio")
    }
    fun cut(source: File, destination: File, startMs: Long, endMs: Long, checkCancelled: () -> Unit = {}) {
        require(source.canonicalPath != destination.canonicalPath) { "The original cannot be overwritten" }
        val info = info(source)
        require(startMs >= 0 && endMs > startMs && endMs <= info.durationMs) { "Cut is outside the recording" }
        val first = startMs * info.rate / 1000 * info.frameSize
        val last = if (endMs == info.durationMs) info.dataBytes else endMs * info.rate / 1000 * info.frameSize
        require(last > first)
        require(destination.createNewFile()) { "Output already exists" }
        try {
            header(destination, info.rate, info.channels, 0, info.encoding)
            RandomAccessFile(source, "r").use { input -> RandomAccessFile(destination, "rw").use { output ->
                input.seek(info.dataOffset + first); output.seek(headerSize(info.encoding))
                val buffer = ByteArray(64 * 1024)
                var remaining = last - first
                while (remaining > 0) {
                    checkCancelled()
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    check(n > 0) { "The source audio is incomplete" }
                    output.write(buffer, 0, n); remaining -= n
                }
                output.fd.sync()
            } }
            header(destination, info.rate, info.channels, last - first, info.encoding)
        } catch (error: Throwable) { destination.delete(); throw error }
    }
    /** Reads at most 1024 PCM frames per display bucket, regardless of recording length. */
    fun peaks(file: File, buckets: Int = 180): List<Float> {
        val info = info(file)
        val totalFrames = info.dataBytes / info.frameSize
        if (totalFrames == 0L) return emptyList()
        require(buckets > 0)
        return RandomAccessFile(file, "r").use { f ->
            val buffer = ByteArray(1024 * info.frameSize)
            List(buckets) { index ->
                val frame = index.toLong() * totalFrames / buckets
                f.seek(info.dataOffset + frame * info.frameSize)
                val count = minOf(buffer.size.toLong(), (totalFrames - frame) * info.frameSize).toInt()
                f.readFully(buffer, 0, count)
                pcmPeak(buffer, count, info.encoding)
            }
        }
    }
}
