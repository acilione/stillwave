package app.stillwave.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile

/** Streaming AAC-LC/M4A conversion. Callers run this work on an I/O dispatcher. */
// Encoder trim keys are string constants. Older codecs ignore these optional keys;
// absent extractor metadata falls back to zero without calling a newer API.
@SuppressLint("InlinedApi")
object AacFile {
    data class Info(val rate: Int, val channels: Int, val durationMs: Long, val bitRate: Int)
    private const val TIMEOUT_US = 10_000L
    private const val STALL_MS = 30_000L

    private fun audioTrack(extractor: MediaExtractor): Int = (0 until extractor.trackCount).firstOrNull {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
    } ?: error("No AAC audio track")

    private fun MediaFormat.intOr(key: String, fallback: Int = 0): Int = if (containsKey(key)) getInteger(key) else fallback

    fun info(file: File): Info {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val format = extractor.getTrackFormat(audioTrack(extractor))
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(rate in 8000..192000 && channels in 1..2) { "Unsupported AAC format" }
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            require(durationUs > 0) { "Empty AAC file" }
            val bitrate = format.intOr(MediaFormat.KEY_BIT_RATE).takeIf { it > 0 }
                ?: (file.length() * 8_000_000 / durationUs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val frames = durationUs * rate / 1_000_000 - format.intOr(MediaFormat.KEY_ENCODER_DELAY).coerceAtLeast(0) - format.intOr(MediaFormat.KEY_ENCODER_PADDING).coerceAtLeast(0)
            require(frames > 0) { "Empty AAC file" }
            return Info(rate, channels, frames * 1000 / rate, bitrate)
        } finally { extractor.release() }
    }

    fun encode(source: File, destination: File, bitRate: Int, checkCancelled: () -> Unit = {}) {
        require(source.canonicalFile != destination.canonicalFile) { "The original cannot be overwritten" }
        val wav = WavFile.info(source)
        require(wav.encoding == PcmEncoding.PCM16 && wav.dataBytes > 0) { "AAC encoding requires non-empty 16-bit PCM" }
        require(bitRate in 8000..512000) { "Invalid AAC bitrate" }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, wav.rate, wav.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
            ?: error("This Android device cannot encode the selected AAC format")
        require(destination.createNewFile()) { "Output already exists" }
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var codecStarted = false
        var muxerStarted = false
        var completed = false
        try {
            val encoder = MediaCodec.createByCodecName(codecName).also { codec = it }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start(); codecStarted = true
            val output = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            val info = MediaCodec.BufferInfo()
            val bytes = ByteArray(64 * 1024)
            var inputEnded = false
            var outputEnded = false
            var track = -1
            var sent = 0L
            var lastProgress = SystemClock.elapsedRealtime()
            RandomAccessFile(source, "r").use { input ->
                input.seek(wav.dataOffset)
                while (!outputEnded) {
                    checkCancelled()
                    check(SystemClock.elapsedRealtime() - lastProgress < STALL_MS) { "AAC encoder stopped responding" }
                    if (!inputEnded) {
                        val index = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val buffer = checkNotNull(encoder.getInputBuffer(index))
                            buffer.clear()
                            val available = minOf(bytes.size, buffer.remaining()) / wav.frameSize * wav.frameSize
                            check(available > 0) { "AAC encoder input buffer is too small" }
                            val count = minOf(available.toLong(), wav.dataBytes - sent).toInt()
                            if (count > 0) { input.readFully(bytes, 0, count); buffer.put(bytes, 0, count) }
                            val time = sent / wav.frameSize * 1_000_000 / wav.rate
                            inputEnded = count == 0
                            encoder.queueInputBuffer(index, 0, count, time, if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            sent += count; lastProgress = SystemClock.elapsedRealtime()
                        }
                    }
                    when (val index = encoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "AAC format changed during encoding" }
                            track = output.addTrack(encoder.outputFormat)
                            output.start(); muxerStarted = true; lastProgress = SystemClock.elapsedRealtime()
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (index >= 0) {
                            try {
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                                    check(muxerStarted) { "AAC encoder returned audio before its format" }
                                    val buffer = checkNotNull(encoder.getOutputBuffer(index))
                                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                    output.writeSampleData(track, buffer, info)
                                }
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                lastProgress = SystemClock.elapsedRealtime()
                            } finally { encoder.releaseOutputBuffer(index, false) }
                        }
                    }
                }
            }
            check(muxerStarted) { "AAC encoder returned no audio" }
            output.stop(); muxerStarted = false
            RandomAccessFile(destination, "rw").use { it.fd.sync() }
            checkCancelled()
            AacFile.info(destination)
            completed = true
        } finally {
            if (codecStarted) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (!completed) destination.delete()
        }
    }

    fun decode(source: File, destination: File, checkCancelled: () -> Unit = {}) {
        require(source.canonicalFile != destination.canonicalFile) { "The original cannot be overwritten" }
        require(destination.createNewFile()) { "Output already exists" }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        var completed = false
        try {
            extractor.setDataSource(source.absolutePath)
            val track = audioTrack(extractor)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val pcm = PcmFormat(rate, channels)
            // MediaCodec supplies PCM; trimming is explicit, once, using container metadata.
            var leadingBytes = format.intOr(MediaFormat.KEY_ENCODER_DELAY).coerceAtLeast(0).toLong() * pcm.frameSize
            val paddingBytes = format.intOr(MediaFormat.KEY_ENCODER_PADDING).coerceAtLeast(0).toLong() * pcm.frameSize
            val declaredBytes = (format.getLong(MediaFormat.KEY_DURATION) * rate / 1_000_000 * pcm.frameSize - leadingBytes - paddingBytes).coerceAtLeast(0)
            format.setInteger(MediaFormat.KEY_ENCODER_DELAY, 0)
            format.setInteger(MediaFormat.KEY_ENCODER_PADDING, 0)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start(); codecStarted = true
            WavFile.header(destination, rate, channels, 0)
            var inputEnded = false
            var outputEnded = false
            var written = 0L
            var lastProgress = SystemClock.elapsedRealtime()
            var lastSampleTime = 0L
            val info = MediaCodec.BufferInfo()
            val bytes = ByteArray(64 * 1024)
            RandomAccessFile(destination, "rw").use { output ->
                output.seek(WavFile.HEADER_SIZE)
                while (!outputEnded) {
                    checkCancelled()
                    check(SystemClock.elapsedRealtime() - lastProgress < STALL_MS) { "AAC decoder stopped responding" }
                    if (!inputEnded) {
                        val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val buffer = checkNotNull(decoder.getInputBuffer(index)); buffer.clear()
                            val count = extractor.readSampleData(buffer, 0)
                            inputEnded = count < 0
                            val time = if (inputEnded) lastSampleTime else extractor.sampleTime.also { lastSampleTime = it }
                            decoder.queueInputBuffer(index, 0, count.coerceAtLeast(0), time, if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            if (!inputEnded) extractor.advance()
                            lastProgress = SystemClock.elapsedRealtime()
                        }
                    }
                    when (val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val actual = decoder.outputFormat
                            check(actual.getInteger(MediaFormat.KEY_SAMPLE_RATE) == rate && actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == channels &&
                                actual.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_16BIT) { "AAC decoder changed the requested PCM format" }
                            lastProgress = SystemClock.elapsedRealtime()
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (index >= 0) {
                            try {
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                                    val buffer = checkNotNull(decoder.getOutputBuffer(index))
                                    check(info.size % pcm.frameSize == 0) { "Incomplete decoded sample frame" }
                                    val skip = minOf(leadingBytes, info.size.toLong()).toInt(); leadingBytes -= skip
                                    buffer.position(info.offset + skip); buffer.limit(info.offset + info.size)
                                    while (buffer.hasRemaining()) {
                                        checkCancelled()
                                        val count = minOf(bytes.size, buffer.remaining())
                                        check(written + count <= WavFile.MAX_DATA_BYTES) { "Decoded audio exceeds the WAV size limit" }
                                        buffer.get(bytes, 0, count); output.write(bytes, 0, count); written += count
                                    }
                                }
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                lastProgress = SystemClock.elapsedRealtime()
                            } finally { decoder.releaseOutputBuffer(index, false) }
                        }
                    }
                }
                written = minOf((written - paddingBytes).coerceAtLeast(0), declaredBytes)
                check(written > 0 && written % pcm.frameSize == 0L) { "AAC file contains no decoded audio" }
                output.setLength(WavFile.HEADER_SIZE + written)
                output.fd.sync()
            }
            WavFile.header(destination, rate, channels, written)
            completed = true
        } finally {
            if (codecStarted) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
            if (!completed) destination.delete()
        }
    }
}
